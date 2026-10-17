package app.moa.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

/** Explicit, device-local consent for FCM. Tokens are never logged or broadcast. */
public final class PushClient {
    static final String PREFS = "moa_push";
    private static final Object LOCK = new Object();
    private static long generation;
    private static Task<Void> deletion;

    public interface TokenCallback {
        void onToken(String token);
        void onError(String message);
    }

    private PushClient() { }

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean("enabled", false);
    }

    /** Source-only builds without local Firebase resources continue working normally. */
    public static boolean isConfigured(Context context) {
        try {
            FirebaseOptions options = FirebaseOptions.fromResource(context);
            return options != null && present(options.getApplicationId())
                    && present(options.getApiKey()) && present(options.getGcmSenderId())
                    && present(options.getProjectId());
        } catch (RuntimeException unavailable) { return false; }
    }

    public static String cachedToken(Context context) {
        return isEnabled(context) ? preferences(context).getString("current_token", "") : "";
    }

    public static void enable(Context context, TokenCallback callback) {
        Context app = context.getApplicationContext();
        if (!isConfigured(app)) {
            error(callback, "이 빌드에는 푸시 연결 설정이 없어요.");
            return;
        }
        if (!PushNotifications.hasPermission(app)) {
            error(callback, "Android 알림 권한을 먼저 허용해 주세요.");
            return;
        }
        synchronized (LOCK) {
            generation++;
            preferences(app).edit().putBoolean("enabled", true).apply();
        }
        getToken(app, callback);
    }

    public static void getToken(Context context, TokenCallback callback) {
        Context app = context.getApplicationContext();
        final long expected;
        final Task<Void> pending;
        synchronized (LOCK) {
            if (!isEnabled(app)) {
                error(callback, "푸시 알림이 꺼져 있어요.");
                return;
            }
            expected = generation;
            pending = deletion;
        }
        Runnable request = () -> requestToken(app, expected, callback);
        // A quick off/on must not let the earlier deletion revoke the new token.
        if (pending != null && !pending.isComplete()) {
            pending.addOnCompleteListener(ignored -> request.run());
        } else request.run();
    }

    private static void requestToken(Context app, long expected, TokenCallback callback) {
        synchronized (LOCK) {
            if (generation != expected || !isEnabled(app)) return;
            if (!PushNotifications.hasPermission(app)) {
                error(callback, "Android 알림 권한이 꺼져 있어요.");
                return;
            }
            try {
                FirebaseMessaging messaging = messaging(app);
                if (messaging == null) {
                    error(callback, "이 빌드에는 푸시 연결 설정이 없어요.");
                    return;
                }
                messaging.setAutoInitEnabled(true);
                messaging.getToken().addOnCompleteListener(task -> {
                    synchronized (LOCK) {
                        if (generation != expected || !isEnabled(app)) return;
                        if (!task.isSuccessful() || !validToken(task.getResult())) {
                            error(callback, "푸시 연결 정보를 받지 못했어요. 네트워크를 확인하고 다시 시도해 주세요.");
                            return;
                        }
                        acceptToken(app, task.getResult());
                        String token = task.getResult();
                        onMain(() -> {
                            synchronized (LOCK) {
                                if (generation == expected && isEnabled(app)) callback.onToken(token);
                            }
                        });
                    }
                });
            } catch (RuntimeException unavailable) {
                error(callback, "푸시 연결을 시작하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
            }
        }
    }

    /** Call remote unpairing separately before clearing any server pairing credentials. */
    public static void disable(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            generation++;
            preferences(app).edit().putBoolean("enabled", false)
                    .remove("current_token").remove("previous_token").apply();
            PushNotifications.cancelAll(app);
            try {
                FirebaseMessaging messaging = messaging(app);
                if (messaging != null) {
                    messaging.setAutoInitEnabled(false);
                    deletion = messaging.deleteToken();
                }
            } catch (RuntimeException unavailable) {
                // Local opt-out still takes effect immediately, including incoming messages.
            }
        }
    }

    static boolean acceptToken(Context context, String token) {
        synchronized (LOCK) {
            if (!isEnabled(context) || !validToken(token)
                    || (deletion != null && !deletion.isComplete())) return false;
            SharedPreferences prefs = preferences(context);
            String previous = prefs.getString("current_token", "");
            SharedPreferences.Editor edit = prefs.edit().putString("current_token", token);
            if (!previous.isEmpty() && !previous.equals(token)) edit.putString("previous_token", previous);
            edit.apply();
            return true;
        }
    }

    private static FirebaseMessaging messaging(Context context) {
        if (!isConfigured(context)) return null;
        FirebaseApp app;
        try { app = FirebaseApp.getInstance(); }
        catch (IllegalStateException absent) { app = FirebaseApp.initializeApp(context); }
        return app == null ? null : FirebaseMessaging.getInstance();
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }

    private static boolean validToken(String token) {
        return token != null && token.length() >= 16 && token.length() <= 4096
                && token.matches("[\\x21-\\x7E]+");
    }

    private static void error(TokenCallback callback, String message) {
        onMain(() -> callback.onError(message));
    }

    private static void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else new Handler(Looper.getMainLooper()).post(action);
    }
}
