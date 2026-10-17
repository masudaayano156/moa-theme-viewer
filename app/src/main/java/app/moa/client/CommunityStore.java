package app.moa.client;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device-local community preferences. No account identifiers are inferred or sent to a server. */
public final class CommunityStore {
    static final String PREFS = "moa_community";
    public static final int MAX_RULES = 200;
    public static final int MAX_NOTES = 200;
    public static final int MAX_SAVED = 200;
    public static final int MAX_RECENT = 100;
    public static final int MAX_VALUE_LENGTH = 120;
    public static final int MAX_NOTE_LENGTH = 200;
    public static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_STORED_LENGTH = 256 * 1024;
    private static final Object LOCK = new Object();

    public static final class Rule {
        public final String kind, value;
        private Rule(String kind, String value) { this.kind = kind; this.value = value; }
    }

    public static final class UserNote {
        public final String kind, value, text;
        private UserNote(String kind, String value, String text) {
            this.kind = kind; this.value = value; this.text = text;
        }
    }

    public static final class Article {
        public final String url, title;
        public final long savedAt;
        private Article(String url, String title, long savedAt) {
            this.url = url; this.title = title; this.savedAt = savedAt;
        }
    }

    private CommunityStore() { }

    static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Rule> rules(Context context) {
        synchronized (LOCK) { return Collections.unmodifiableList(readRules(context)); }
    }

    /** A duplicate is accepted without adding a second rule. A new rule at the limit is rejected. */
    public static boolean addRule(Context context, String kind, String value) {
        value = validValue(value);
        if (!validKind(kind, true) || value == null) return false;
        synchronized (LOCK) {
            ArrayList<Rule> items = readRules(context);
            for (Rule item : items) if (same(item.kind, item.value, kind, value)) return true;
            if (items.size() >= MAX_RULES) return false;
            items.add(new Rule(kind, value));
            writeRules(context, items);
            return true;
        }
    }

    public static boolean removeRule(Context context, String kind, String value) {
        value = validValue(value);
        if (!validKind(kind, true) || value == null) return false;
        synchronized (LOCK) {
            ArrayList<Rule> items = readRules(context);
            for (int i = 0; i < items.size(); i++) {
                Rule item = items.get(i);
                if (same(item.kind, item.value, kind, value)) {
                    items.remove(i);
                    writeRules(context, items);
                    return true;
                }
            }
            return false;
        }
    }

    /** Atomic editing also works when the rule limit is reached. */
    public static boolean replaceRule(Context context, String oldKind, String oldValue, String kind, String value) {
        oldValue = validValue(oldValue); value = validValue(value);
        if (!validKind(oldKind, true) || oldValue == null || !validKind(kind, true) || value == null) return false;
        synchronized (LOCK) {
            ArrayList<Rule> items = readRules(context);
            int oldIndex = -1;
            for (int i = 0; i < items.size(); i++) if (same(items.get(i).kind, items.get(i).value, oldKind, oldValue)) { oldIndex = i; break; }
            if (oldIndex < 0) return false;
            items.remove(oldIndex);
            boolean exists = false;
            for (Rule item : items) if (same(item.kind, item.value, kind, value)) { exists = true; break; }
            if (!exists) items.add(Math.min(oldIndex, items.size()), new Rule(kind, value));
            writeRules(context, items); return true;
        }
    }

    public static List<UserNote> notes(Context context) {
        synchronized (LOCK) { return Collections.unmodifiableList(readNotes(context)); }
    }

    public static boolean putNote(Context context, String kind, String value, String text) {
        value = validValue(value);
        text = validText(text, MAX_NOTE_LENGTH, true);
        if (!validKind(kind, false) || value == null || text == null) return false;
        synchronized (LOCK) {
            ArrayList<UserNote> items = readNotes(context);
            for (int i = 0; i < items.size(); i++) {
                UserNote item = items.get(i);
                if (same(item.kind, item.value, kind, value)) {
                    items.set(i, new UserNote(kind, value, text));
                    writeNotes(context, items);
                    return true;
                }
            }
            if (items.size() >= MAX_NOTES) return false;
            items.add(new UserNote(kind, value, text));
            writeNotes(context, items);
            return true;
        }
    }

    public static boolean removeNote(Context context, String kind, String value) {
        value = validValue(value);
        if (!validKind(kind, false) || value == null) return false;
        synchronized (LOCK) {
            ArrayList<UserNote> items = readNotes(context);
            for (int i = 0; i < items.size(); i++) {
                UserNote item = items.get(i);
                if (same(item.kind, item.value, kind, value)) {
                    items.remove(i);
                    writeNotes(context, items);
                    return true;
                }
            }
            return false;
        }
    }

    public static boolean replaceNote(Context context, String oldKind, String oldValue, String kind, String value, String text) {
        oldValue = validValue(oldValue); value = validValue(value); text = validText(text, MAX_NOTE_LENGTH, true);
        if (!validKind(oldKind, false) || oldValue == null || !validKind(kind, false) || value == null || text == null) return false;
        synchronized (LOCK) {
            ArrayList<UserNote> items = readNotes(context);
            int oldIndex = -1;
            for (int i = 0; i < items.size(); i++) if (same(items.get(i).kind, items.get(i).value, oldKind, oldValue)) { oldIndex = i; break; }
            if (oldIndex < 0) return false;
            items.remove(oldIndex);
            for (int i = 0; i < items.size(); i++) {
                if (same(items.get(i).kind, items.get(i).value, kind, value)) {
                    items.set(i, new UserNote(kind, value, text)); writeNotes(context, items); return true;
                }
            }
            items.add(Math.min(oldIndex, items.size()), new UserNote(kind, value, text));
            writeNotes(context, items); return true;
        }
    }

    /** Uses only caller-provided visible values. Words are literal substrings, never regular expressions. */
    public static boolean blocked(Context context, String text, String id, String ip, String nickname) {
        for (Rule rule : rules(context)) {
            if ("word".equals(rule.kind)) {
                if (text != null && text.contains(rule.value)) return true;
            } else if (rule.value.equals(identity(rule.kind, id, ip, nickname))) return true;
        }
        return false;
    }

    /** Prefer a present ID, then an IP, then a nickname. An absent identity never matches another kind. */
    public static String noteFor(Context context, String id, String ip, String nickname) {
        List<UserNote> items = notes(context);
        for (String kind : new String[] {"id", "ip", "nickname"}) {
            String value = identity(kind, id, ip, nickname);
            if (value == null) continue;
            for (UserNote note : items) if (same(note.kind, note.value, kind, value)) return note.text;
        }
        return "";
    }

    /** Only rules and notes are exposed to a trusted document; saved posts and history stay native. */
    public static String configurationJson(Context context) {
        synchronized (LOCK) {
            JSONObject object = new JSONObject();
            try {
                object.put("rules", rulesJson(readRules(context)));
                object.put("notes", notesJson(readNotes(context)));
            } catch (Exception impossible) { return "{\"rules\":[],\"notes\":[]}"; }
            return object.toString();
        }
    }

    public static List<Article> savedArticles(Context context) {
        synchronized (LOCK) { return Collections.unmodifiableList(readArticles(context, "saved", MAX_SAVED)); }
    }

    public static boolean isSaved(Context context, String url) {
        url = canonicalArticleUrl(url);
        if (url == null) return false;
        for (Article article : savedArticles(context)) if (url.equals(article.url)) return true;
        return false;
    }

    /** Most recently saved first. Updating a saved article refreshes its title and position. */
    public static boolean saveArticle(Context context, String url, String title) {
        return recordArticle(context, "saved", MAX_SAVED, url, title, false);
    }

    public static boolean removeSavedArticle(Context context, String url) {
        url = canonicalArticleUrl(url);
        if (url == null) return false;
        synchronized (LOCK) {
            ArrayList<Article> items = readArticles(context, "saved", MAX_SAVED);
            if (!removeArticle(items, url)) return false;
            writeArticles(context, "saved", items);
            return true;
        }
    }

    public static List<Article> recentArticles(Context context) {
        synchronized (LOCK) { return Collections.unmodifiableList(readArticles(context, "recent", MAX_RECENT)); }
    }

    public static boolean recordRecent(Context context, String url, String title) {
        return recordArticle(context, "recent", MAX_RECENT, url, title, true);
    }

    public static boolean isHistoryEnabled(Context context) {
        try { return preferences(context).getBoolean("history_enabled", true); }
        catch (ClassCastException malformed) { return true; }
    }

    /** Stopping history preserves already saved history; clearHistory is a separate user action. */
    public static void setHistoryEnabled(Context context, boolean enabled) {
        synchronized (LOCK) { preferences(context).edit().putBoolean("history_enabled", enabled).apply(); }
    }

    public static void clearHistory(Context context) {
        synchronized (LOCK) { preferences(context).edit().remove("recent").apply(); }
    }

    public static boolean removeRecentArticle(Context context, String url) {
        url = canonicalArticleUrl(url);
        if (url == null) return false;
        synchronized (LOCK) {
            ArrayList<Article> items = readArticles(context, "recent", MAX_RECENT);
            if (!removeArticle(items, url)) return false;
            writeArticles(context, "recent", items);
            return true;
        }
    }

    /** Direct mobile posts only. Discard query and fragment before storing or navigating the result. */
    public static String canonicalArticleUrl(String value) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || !"m.dcinside.com".equals(uri.getRawAuthority())
                    || !uri.getRawPath().matches("/(?:board|mini|person)/[A-Za-z0-9_]{1,80}/[1-9][0-9]{0,18}")) return null;
            return "https://m.dcinside.com" + uri.getRawPath();
        } catch (RuntimeException malformed) { return null; }
    }

    /** Copies old locally saved post links once, without changing or deleting old favorites. */
    public static int migrateLegacySavedArticles(Context context) {
        synchronized (LOCK) {
            SharedPreferences prefs = preferences(context);
            try { if (prefs.getBoolean("legacy_saved_migrated", false)) return 0; }
            catch (ClassCastException ignored) { }
            JSONArray legacy = readArray(context.getApplicationContext()
                    .getSharedPreferences("moa_preferences", Context.MODE_PRIVATE), "favorites");
            ArrayList<Article> items = readArticles(context, "saved", MAX_SAVED);
            Set<String> known = new HashSet<>();
            for (Article article : items) known.add(article.url);
            int added = 0;
            for (int i = 0; i < legacy.length() && items.size() < MAX_SAVED; i++) {
                JSONObject object = legacy.optJSONObject(i);
                if (object == null) continue;
                String url = canonicalArticleUrl(string(object, "url"));
                if (url == null || !known.add(url)) continue;
                items.add(new Article(url, articleTitle(string(object, "name")), System.currentTimeMillis()));
                added++;
            }
            prefs.edit().putString("saved", articlesJson(items).toString())
                    .putBoolean("legacy_saved_migrated", true).apply();
            return added;
        }
    }

    private static boolean recordArticle(Context context, String key, int limit, String url, String title, boolean history) {
        url = canonicalArticleUrl(url);
        if (url == null) return false;
        synchronized (LOCK) {
            if (history && !isHistoryEnabled(context)) return false;
            ArrayList<Article> items = readArticles(context, key, limit);
            removeArticle(items, url);
            items.add(0, new Article(url, articleTitle(title), System.currentTimeMillis()));
            while (items.size() > limit) items.remove(items.size() - 1);
            writeArticles(context, key, items);
            return true;
        }
    }

    private static boolean removeArticle(ArrayList<Article> items, String url) {
        for (int i = 0; i < items.size(); i++) {
            if (url.equals(items.get(i).url)) { items.remove(i); return true; }
        }
        return false;
    }

    private static ArrayList<Rule> readRules(Context context) {
        JSONArray array = readArray(preferences(context), "rules");
        ArrayList<Rule> result = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (int i = 0; i < array.length() && result.size() < MAX_RULES; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String kind = string(item, "kind"), value = validValue(string(item, "value"));
            if (!validKind(kind, true) || value == null || !known.add(kind + "\u0000" + value)) continue;
            result.add(new Rule(kind, value));
        }
        return result;
    }

    private static ArrayList<UserNote> readNotes(Context context) {
        JSONArray array = readArray(preferences(context), "notes");
        ArrayList<UserNote> result = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (int i = 0; i < array.length() && result.size() < MAX_NOTES; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String kind = string(item, "kind"), value = validValue(string(item, "value"));
            String text = validText(string(item, "text"), MAX_NOTE_LENGTH, true);
            if (!validKind(kind, false) || value == null || text == null || !known.add(kind + "\u0000" + value)) continue;
            result.add(new UserNote(kind, value, text));
        }
        return result;
    }

    private static ArrayList<Article> readArticles(Context context, String key, int limit) {
        JSONArray array = readArray(preferences(context), key);
        ArrayList<Article> result = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (int i = 0; i < array.length() && result.size() < limit; i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String url = canonicalArticleUrl(string(item, "url"));
            Object timestamp = item.opt("savedAt");
            if (url == null || !(timestamp instanceof Number) || ((Number) timestamp).longValue() <= 0
                    || !known.add(url)) continue;
            result.add(new Article(url, articleTitle(string(item, "title")), ((Number) timestamp).longValue()));
        }
        return result;
    }

    private static JSONArray readArray(SharedPreferences prefs, String key) {
        try {
            String value = prefs.getString(key, "[]");
            if (value == null || value.length() > MAX_STORED_LENGTH) return new JSONArray();
            return new JSONArray(value);
        } catch (Exception malformed) { return new JSONArray(); }
    }

    private static void writeRules(Context context, List<Rule> items) {
        preferences(context).edit().putString("rules", rulesJson(items).toString()).apply();
    }

    private static void writeNotes(Context context, List<UserNote> items) {
        preferences(context).edit().putString("notes", notesJson(items).toString()).apply();
    }

    private static void writeArticles(Context context, String key, List<Article> items) {
        preferences(context).edit().putString(key, articlesJson(items).toString()).apply();
    }

    private static JSONArray rulesJson(List<Rule> items) {
        JSONArray array = new JSONArray();
        for (Rule item : items) {
            try { array.put(new JSONObject().put("kind", item.kind).put("value", item.value)); }
            catch (Exception impossible) { }
        }
        return array;
    }

    private static JSONArray notesJson(List<UserNote> items) {
        JSONArray array = new JSONArray();
        for (UserNote item : items) {
            try { array.put(new JSONObject().put("kind", item.kind).put("value", item.value).put("text", item.text)); }
            catch (Exception impossible) { }
        }
        return array;
    }

    private static JSONArray articlesJson(List<Article> items) {
        JSONArray array = new JSONArray();
        for (Article item : items) {
            try { array.put(new JSONObject().put("url", item.url).put("title", item.title).put("savedAt", item.savedAt)); }
            catch (Exception impossible) { }
        }
        return array;
    }

    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static boolean same(String firstKind, String firstValue, String secondKind, String secondValue) {
        return firstKind.equals(secondKind) && firstValue.equals(secondValue);
    }

    private static boolean validKind(String kind, boolean words) {
        return "id".equals(kind) || "ip".equals(kind) || "nickname".equals(kind) || (words && "word".equals(kind));
    }

    private static String validValue(String value) { return validText(value, MAX_VALUE_LENGTH, false); }

    private static String identity(String kind, String id, String ip, String nickname) {
        return validValue("id".equals(kind) ? id : "ip".equals(kind) ? ip : nickname);
    }

    private static String validText(String value, int maximum, boolean multiline) {
        if (value == null || value.length() > maximum) return null;
        value = value.trim();
        if (value.isEmpty()) return null;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return null;
            } else if (Character.isLowSurrogate(ch) || (Character.isISOControl(ch)
                    && !(multiline && (ch == '\n' || ch == '\r' || ch == '\t')))) return null;
        }
        return value;
    }

    private static String articleTitle(String value) {
        if (value == null) return "제목 없는 글";
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < value.length() && title.length() < MAX_TITLE_LENGTH; ) {
            int point = value.codePointAt(i);
            i += Character.charCount(point);
            if (point >= 0xD800 && point <= 0xDFFF) continue;
            if (Character.isISOControl(point)) point = ' ';
            if (title.length() + Character.charCount(point) > MAX_TITLE_LENGTH) break;
            title.appendCodePoint(point);
        }
        String result = title.toString().trim();
        return result.isEmpty() ? "제목 없는 글" : result;
    }
}
