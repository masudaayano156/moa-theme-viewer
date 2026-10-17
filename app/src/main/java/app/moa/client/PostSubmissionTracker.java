package app.moa.client;

import java.net.URI;

/** Short-lived navigation correlation, armed only by a separately verified new-post action. */
public final class PostSubmissionTracker {
    private static final long WINDOW_MILLIS = 120000;
    private String gallery = "";
    private long startedAt;

    public boolean begin(String writeUrl, long now) {
        cancel();
        URI uri = parsedHttps(writeUrl, "m.dcinside.com");
        if (now < 0 || uri == null || !uri.getRawPath().matches("/write/[A-Za-z0-9_]{1,80}/?")) return false;
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
