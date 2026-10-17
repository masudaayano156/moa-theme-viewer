package app.moa.client;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import org.json.JSONObject;

/** Local presentation and writing options. These settings never contain account credentials. */
public final class CommunityOptions {
    static final String PREFS = "moa_community_options";
    public static final int MAX_AFFIX_LENGTH = 2000;
    public static final String IMAGE_NORMAL = "normal", IMAGE_HIDE = "hide", IMAGE_TAP_GIF = "tap_gif";
    private static final int[] ACCENTS = {
            0xFF0B57D0, 0xFF1565C0, 0xFF0277BD, 0xFF00838F, 0xFF00695C, 0xFF107C41,
            0xFF2E7D32, 0xFF558B2F, 0xFF827717, 0xFFF9A825, 0xFFEF6C00, 0xFFD84315,
            0xFFC62828, 0xFFAD1457, 0xFF8E24AA, 0xFF5E35B1, 0xFF6D4C41, 0xFF455A64
    };
    private static final Object LOCK = new Object();

    public static final class Affixes {
        public final String postPrefix, postSuffix, commentPrefix, commentSuffix;
        private Affixes(String postPrefix, String postSuffix, String commentPrefix, String commentSuffix) {
            this.postPrefix = postPrefix; this.postSuffix = postSuffix;
            this.commentPrefix = commentPrefix; this.commentSuffix = commentSuffix;
        }
    }

    private CommunityOptions() { }

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String imageMode(Context context) {
        String mode = storedString(preferences(context), "image_mode", IMAGE_NORMAL);
        return validImageMode(mode) ? mode : IMAGE_NORMAL;
    }

    public static boolean setImageMode(Context context, String mode) {
        if (!validImageMode(mode)) return false;
        preferences(context).edit().putString("image_mode", mode).apply();
        return true;
    }

    public static boolean splitEnabled(Context context) {
        try { return preferences(context).getBoolean("split_enabled", false); }
        catch (ClassCastException malformed) { return false; }
    }

    public static void setSplitEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("split_enabled", enabled).apply();
    }

    public static boolean autoWatchOwnPosts(Context context) {
        try { return preferences(context).getBoolean("auto_watch_own_posts", false); }
        catch (ClassCastException malformed) { return false; }
    }

    public static void setAutoWatchOwnPosts(Context context, boolean enabled) {
        preferences(context).edit().putBoolean("auto_watch_own_posts", enabled).apply();
    }

    public static int accentIndex(Context context) {
        try {
            int value = preferences(context).getInt("accent_index", -1);
            return value >= 0 && value < ACCENTS.length ? value : -1;
        } catch (ClassCastException malformed) { return -1; }
    }

    public static boolean setAccentIndex(Context context, int value) {
        if (value < -1 || value >= ACCENTS.length) return false;
        preferences(context).edit().putInt("accent_index", value).apply();
        return true;
    }

    public static int[] accentColors() { return ACCENTS.clone(); }

    public static int accentColor(Context context, int fallback) {
        int index = accentIndex(context);
        return index < 0 ? fallback : ACCENTS[index];
    }

    /** Picks the higher-contrast foreground, always meeting at least 4.5:1 on an opaque accent. */
    public static int onAccentColor(int background) {
        double brightness = .2126 * linear((background >> 16) & 255)
                + .7152 * linear((background >> 8) & 255) + .0722 * linear(background & 255);
        return (brightness + .05) / .05 >= 1.05 / (brightness + .05) ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** Empty gallery ID selects the global defaults. Invalid IDs cannot create a preference key. */
    public static boolean saveAffixes(Context context, String galleryId, String postPrefix, String postSuffix,
                                     String commentPrefix, String commentSuffix) {
        if (!validGalleryId(galleryId) || !validAffix(postPrefix) || !validAffix(postSuffix)
                || !validAffix(commentPrefix) || !validAffix(commentSuffix)) return false;
        synchronized (LOCK) {
            try {
                JSONObject object = new JSONObject().put("postPrefix", postPrefix).put("postSuffix", postSuffix)
                        .put("commentPrefix", commentPrefix).put("commentSuffix", commentSuffix);
                preferences(context).edit().putString(affixKey(galleryId), object.toString()).apply();
                return true;
            } catch (Exception impossible) { return false; }
        }
    }

    public static Affixes affixes(Context context, String galleryId) {
        synchronized (LOCK) {
            if (validGalleryId(galleryId) && !galleryId.isEmpty()) {
                Affixes local = readAffixes(preferences(context), affixKey(galleryId));
                if (local != null) return local;
            }
            Affixes global = readAffixes(preferences(context), affixKey(""));
            return global == null ? new Affixes("", "", "", "") : global;
        }
    }

    public static boolean hasGalleryAffixes(Context context, String galleryId) {
        return validGalleryId(galleryId) && !galleryId.isEmpty()
                && readAffixes(preferences(context), affixKey(galleryId)) != null;
    }

    public static boolean clearAffixes(Context context, String galleryId) {
        if (!validGalleryId(galleryId)) return false;
        synchronized (LOCK) { preferences(context).edit().remove(affixKey(galleryId)).apply(); }
        return true;
    }

    public static String galleryIdFromUrl(String value) {
        if (value == null || value.length() > 2048) return "";
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"m.dcinside.com".equals(uri.getRawAuthority())) return "";
            String rawPath = uri.getRawPath();
            if (!rawPath.matches("/(?:board|mini|person)/[A-Za-z0-9_]{1,80}(?:/[1-9][0-9]{0,18})?/?")
                    && !rawPath.matches("/(?:write|mini/write|person/write)/[A-Za-z0-9_]{1,80}/?")) return "";
            String[] path = rawPath.split("/");
            if (path.length >= 3 && ("board".equals(path[1]) || "mini".equals(path[1])
                    || "person".equals(path[1]) || "write".equals(path[1]))) {
                String id = path[2];
                if ("write".equals(id) && path.length >= 4) id = path[3];
                if (!"write".equals(id) && validGalleryId(id) && !id.isEmpty()) return id;
            }
        } catch (RuntimeException malformed) { }
        return "";
    }

    public static JSONObject webConfig(Context context, String galleryId) {
        try {
            JSONObject object = new JSONObject(CommunityStore.configurationJson(context));
            Affixes affixes = affixes(context, galleryId);
            object.put("postPrefix", affixes.postPrefix).put("postSuffix", affixes.postSuffix)
                    .put("commentPrefix", affixes.commentPrefix).put("commentSuffix", affixes.commentSuffix)
                    .put("imagesHidden", IMAGE_HIDE.equals(imageMode(context)))
                    .put("gifTapToPlay", IMAGE_TAP_GIF.equals(imageMode(context)));
            return object;
        } catch (Exception impossible) { return new JSONObject(); }
    }

    private static Affixes readAffixes(SharedPreferences prefs, String key) {
        String raw = storedString(prefs, key, "");
        if (raw.isEmpty() || raw.length() > 4 * MAX_AFFIX_LENGTH * 6 + 256) return null;
        try {
            JSONObject value = new JSONObject(raw);
            String[] fields = {"postPrefix", "postSuffix", "commentPrefix", "commentSuffix"};
            String[] strings = new String[4];
            for (int i = 0; i < fields.length; i++) {
                Object field = value.opt(fields[i]);
                if (!(field instanceof String) || !validAffix((String) field)) return null;
                strings[i] = (String) field;
            }
            return new Affixes(strings[0], strings[1], strings[2], strings[3]);
        } catch (Exception malformed) { return null; }
    }

    private static String affixKey(String galleryId) { return "affixes:" + (galleryId.isEmpty() ? "global" : "gallery:" + galleryId); }
    private static boolean validGalleryId(String value) { return value != null && (value.isEmpty() || value.matches("[A-Za-z0-9_]{1,80}")); }
    private static boolean validImageMode(String value) { return IMAGE_NORMAL.equals(value) || IMAGE_HIDE.equals(value) || IMAGE_TAP_GIF.equals(value); }

    private static boolean validAffix(String value) {
        if (value == null || value.length() > MAX_AFFIX_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch) || (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')) return false;
        }
        return true;
    }

    private static String storedString(SharedPreferences prefs, String key, String fallback) {
        try { String value = prefs.getString(key, fallback); return value == null ? fallback : value; }
        catch (ClassCastException malformed) { return fallback; }
    }

    private static double linear(int value) {
        double channel = value / 255.0;
        return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4);
    }
}
