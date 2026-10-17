package app.moa.client;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;

/** Short-lived navigation correlation, armed only by a separately verified new-post action. */
public final class PostSubmissionTracker {
    private static final long WINDOW_MILLIS = 120000;
    private String gallery = "";
    private long startedAt;

    public boolean begin(String writeUrl, long now) {
        cancel();
        URI uri = parsedHttps(writeUrl, "m.dcinside.com");
        if (now < 0 || !isNewWriteDocument(writeUrl)) return false;
        gallery = uri.getRawPath().split("/")[2];
        startedAt = now;
        return true;
    }

    /** Returns at most one final post; unrelated navigation or timeout retires the pending action. */
    public String onNavigation(String url, long now) {
        if (gallery.isEmpty()) return null;
        if (now < startedAt || now - startedAt >= WINDOW_MILLIS) { cancel(); return null; }
        URI upload = parsedHttps(url, "mupload.dcinside.com");
        if (upload != null && "/write_new.php".equals(upload.getRawPath())
                && upload.getRawQuery() == null && upload.getRawFragment() == null) return null;
        String candidate = CommunityStore.canonicalArticleUrl(url);
        String expected = gallery;
        cancel();
        if (candidate == null || !expected.equals(UrlPolicy.galleryId(candidate))) return null;
        return candidate;
    }

    public void cancel() { gallery = ""; startedAt = 0; }

    public static boolean isNewWriteDocument(String value) {
        URI uri = parsedHttps(value, "m.dcinside.com");
        if (uri == null || !uri.getRawPath().matches("/write/[A-Za-z0-9_]{1,80}/?")) return false;
        String query = uri.getRawQuery();
        if (query == null) return true;
        try {
            for (String item : query.split("&")) {
                String[] pair = item.split("=", 2);
                String key = URLDecoder.decode(pair[0], "UTF-8").toLowerCase(Locale.ROOT);
                String mode = pair.length < 2 ? "" : URLDecoder.decode(pair[1], "UTF-8").toLowerCase(Locale.ROOT);
                if (("mode".equals(key) || "action".equals(key)) && ("modify".equals(mode) || "edit".equals(mode))) return false;
            }
            return true;
        } catch (Exception malformed) { return false; }
    }

    private static URI parsedHttps(String value, String host) {
        if (value == null || value.length() > 2048) return null;
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && host.equals(uri.getHost())
                    && uri.getRawUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawPath() != null ? uri : null;
        } catch (RuntimeException malformed) { return null; }
    }
}
