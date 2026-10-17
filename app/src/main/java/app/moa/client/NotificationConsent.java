package app.moa.client;

import android.content.Context;
import android.content.SharedPreferences;

/** One explanation for notifications and automatic new-post registration. */
public final class NotificationConsent {
    private static final String PREFS = "moa_notification_consent";
    private static final int VERSION = 1;
    private NotificationConsent() { }
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
