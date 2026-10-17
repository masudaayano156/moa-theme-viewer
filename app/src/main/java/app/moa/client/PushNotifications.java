package app.moa.client;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import java.net.URI;
import java.util.Map;

/** Data messages are rendered locally so source names and post text cannot leak. */
public final class PushNotifications {
    public static final String ACTION_OPEN = "app.moa.client.OPEN_PUSH";
    public static final String EXTRA_TARGET_URL = "app.moa.client.push.target";
    private static final String CHANNEL = "moa_updates_v1";
    private static final String TAG = "moa_push:";
    private static final String DIAGNOSTICS = "moa_push_diagnostics";
    private static final Object DELIVERY_LOCK = new Object();

    private PushNotifications() { }

    public static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canNotify(Context context) {
        return blockedReason(context).isEmpty();
    }

    private static String blockedReason(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!PushClient.isEnabled(context)) return "app_disabled";
        if (!hasPermission(context)) return "permission_denied";
        if (manager == null) return "service_unavailable";
        if (!manager.areNotificationsEnabled()) return "app_blocked";
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE ? "channel_blocked" : "";
    }

    /** Local-only test receipt. Posted means handed to Android, not read by a person. */
    public static final class TestStatus {
        public final String eventId, resultCode;
        public final long receivedAtMillis, postedAtMillis;
        private TestStatus(String eventId, String resultCode, long receivedAtMillis, long postedAtMillis) {
            this.eventId = eventId; this.resultCode = resultCode;
            this.receivedAtMillis = receivedAtMillis; this.postedAtMillis = postedAtMillis;
        }
    }

    public static TestStatus lastTestStatus(Context context) {
        synchronized (DELIVERY_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE);
            return new TestStatus(prefs.getString("event", ""), prefs.getString("result", "none"),
                    prefs.getLong("received", 0), prefs.getLong("posted", 0));
        }
    }

    private static void testReceived(Context context, String event) {
        synchronized (DELIVERY_LOCK) {
            context.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE).edit()
                    .putString("event", event).putString("result", "received")
                    .putLong("received", System.currentTimeMillis()).putLong("posted", 0).apply();
        }
    }

    private static void testResult(Context context, String event, String result) {
        synchronized (DELIVERY_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE);
            // A slower callback for an older test must not replace a newer receipt.
            if (!event.equals(prefs.getString("event", ""))) return;
            prefs.edit().putString("result", result)
                    .putLong("posted", "posted".equals(result) ? System.currentTimeMillis() : 0).apply();
        }
    }

    /** Only direct post URLs, with no credentials, query, fragment, redirect, or editor path. */
    public static String validatedTarget(String value) {
        if (value == null || value.length() > 240) return null;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"m.dcinside.com".equals(uri.getHost())
                    || uri.getRawUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/(?:board|mini|person)/[A-Za-z0-9_]{1,80}/[1-9][0-9]{0,18}")) return null;
            return uri.toASCIIString();
        } catch (RuntimeException malformed) { return null; }
    }

    /** Consuming once prevents an Activity recreation from navigating repeatedly. */
    public static String consumeTarget(Intent intent) {
        if (intent == null || !ACTION_OPEN.equals(intent.getAction())) return null;
        String value;
        try { value = intent.getStringExtra(EXTRA_TARGET_URL); }
        catch (RuntimeException malformed) { value = null; }
        intent.removeExtra(EXTRA_TARGET_URL);
        intent.setAction(Intent.ACTION_MAIN);
        return validatedTarget(value);
    }

    public static boolean show(Context context, Map<String, String> data) {
        if (data == null) return false;
        String kind = data.get("kind");
        boolean test = "test".equals(kind);
        if (!test && !"comment".equals(kind)) return false;
        String event = data.get("event_id");
        if (event == null || !event.matches("[A-Za-z0-9_-]{1,128}")) return false;
        String url = test ? null : validatedTarget(data.get("url"));
        if (!test && url == null) return false;
        if (test) testReceived(context, event);
        String blocked = blockedReason(context);
        if (!blocked.isEmpty()) {
            if (test) testResult(context, event, blocked);
            return false;
        }
        SharedPreferences prefs = context.getSharedPreferences("moa_preferences", Context.MODE_PRIVATE);
        ThemeSpec theme = ThemeSpec.fromId(prefs.getString("theme", "mail"));
        boolean dark = prefs.getBoolean("dark", false);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "새 소식", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("연결한 기기의 새 소식 알림");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
        Intent open = new Intent(context, MainActivity.class).setAction(ACTION_OPEN)
                .setData(Uri.parse("moa-push://event/" + event))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (url != null) open.putExtra(EXTRA_TARGET_URL, url);
        PendingIntent pending = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int icon = icon(theme);
        Notification publicVersion = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(icon).setContentTitle(theme.displayName)
                .setContentText("새 알림이 있습니다.").setVisibility(Notification.VISIBILITY_PUBLIC).build();
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(icon).setColor(theme.palette(dark).accent)
                .setContentTitle(theme.displayName)
                .setContentText(test ? "알림 연결을 확인했어요." : message(theme))
                .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SOCIAL).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion).build();
        try {
            // Consent can change while this service prepares the notification.
            synchronized (DELIVERY_LOCK) {
                blocked = blockedReason(context);
                if (!blocked.isEmpty()) {
                    if (test) testResult(context, event, blocked);
                    return false;
                }
                manager.notify(TAG + event, 0, notification);
                if (test) testResult(context, event, "posted");
                return true;
            }
        } catch (SecurityException permissionChanged) {
            if (test) {
                String reason = blockedReason(context);
                testResult(context, event, reason.isEmpty() ? "posting_error" : reason);
            }
            return false;
        }
    }

    public static void cancelAll(Context context) {
        synchronized (DELIVERY_LOCK) {
            context.getSharedPreferences(DIAGNOSTICS, Context.MODE_PRIVATE).edit().clear().apply();
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        synchronized (DELIVERY_LOCK) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getTag() != null && item.getTag().startsWith(TAG)) manager.cancel(item.getTag(), item.getId());
            }
        }
    }

    private static String message(ThemeSpec theme) {
        switch (theme) {
            case MAIL: return "새 답장이 도착했어요.";
            case SHEET: return "새 메모가 도착했어요.";
            case EBOOK: return "새 독서 메모가 도착했어요.";
            default: return "새 댓글이 도착했어요.";
        }
    }

    private static int icon(ThemeSpec theme) {
        switch (theme) {
            case POST: return R.drawable.ic_notification_post;
            case PHOTOGRAM: return R.drawable.ic_notification_photogram;
            case SHEET: return R.drawable.ic_notification_sheet;
            case CAFE: return R.drawable.ic_notification_cafe;
            case EBOOK: return R.drawable.ic_notification_ebook;
            case MAGAZINE: return R.drawable.ic_notification_magazine;
            default: return R.drawable.ic_notification_mail;
        }
    }
}
