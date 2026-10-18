package app.moa.client;

import android.content.Context;
import android.content.SharedPreferences;

/** One explanation for notifications and automatic new-post registration. */
public final class NotificationConsent {
    private static final String PREFS = "moa_notification_consent";
    private static final int VERSION = 1;
    private NotificationConsent() { }

    /** The old hidden per-post option must not mute an already enabled notification connection. */
    public static void migrateEnabledConnection(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("global_connection_migrated", false)) return;
        if (PushClient.isEnabled(context) && PushRelayClient.isPaired(context) && PushNotifications.canNotify(context))
            CommunityOptions.setAutoWatchOwnPosts(context, true);
        prefs.edit().putBoolean("global_connection_migrated", true).commit();
    }
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
    public static boolean needsPrompt(Context context) {
        if (CommunityOptions.autoWatchOwnPosts(context)) {
            prefs(context).edit().putInt("version", VERSION).apply();
            return false;
        }
        return prefs(context).getInt("version", 0) < VERSION;
    }
    public static boolean accept(Context context) {
        if (!prefs(context).edit().putInt("version", VERSION).commit()) return false;
        CommunityOptions.setAutoWatchOwnPosts(context, true);
        return true;
    }
    public static void postpone(Context context) {
        prefs(context).edit().putInt("version", VERSION).apply();
    }
}
