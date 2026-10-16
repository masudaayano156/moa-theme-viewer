package app.moa.client;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Navigation decisions are based on parsed URLs, never visual theme labels. */
public final class UrlPolicy {
    private UrlPolicy() {}

    public static boolean isDcHttps(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443) && host != null
                && (host.equalsIgnoreCase("dcinside.com") || host.toLowerCase(Locale.ROOT).endsWith(".dcinside.com"));
        } catch (RuntimeException ex) { return false; }
    }

    public static boolean canTheme(String value) {
        if (!isDcHttps(value)) return false;
        URI uri = URI.create(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath();
        if (!"m.dcinside.com".equals(host)) return false;
        return path != null && (path.startsWith("/board/") || path.startsWith("/mini/")
            || path.startsWith("/person/") || path.startsWith("/write/")
            || path.startsWith("/modify/") || path.startsWith("/search"));
    }

    public static String galleryId(String value) {
        if (!isDcHttps(value)) return "";
        URI uri = URI.create(value);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if ("m.dcinside.com".equalsIgnoreCase(uri.getHost())) {
            String[] parts = path.split("/");
            if (parts.length >= 3 && ("board".equals(parts[1]) || "mini".equals(parts[1])
                || "person".equals(parts[1]) || "write".equals(parts[1]) || "modify".equals(parts[1]))) {
                return validId(parts[2]) ? parts[2] : "";
            }
            return "";
        }
        if (!"gall.dcinside.com".equalsIgnoreCase(uri.getHost())
                || !path.matches("^/(?:mgallery/|mini/|person/)?board/(?:lists|view|write|modify)/?$")) return "";
        String query = uri.getRawQuery();
        String result = null;
        if (query != null) {
            for (String item : query.split("&")) {
                String[] pair = item.split("=", 2);
                try {
                    if (pair.length == 2 && "id".equals(URLDecoder.decode(pair[0], "UTF-8"))) {
                        if (result != null) return "";
                        String id = URLDecoder.decode(pair[1], "UTF-8");
                        if (!validId(id)) return "";
                        result = id;
                    }
                } catch (Exception invalidQuery) { return ""; }
            }
        }
        return result == null ? "" : result;
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[A-Za-z0-9_]{1,80}");
    }

    public static boolean isAuthenticationPage(String value) {
        if (!isDcHttps(value)) return false;
        URI uri = URI.create(value);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        return "msign.dcinside.com".equals(host) || "sign.dcinside.com".equals(host)
            || path.startsWith("/auth/") || path.equals("/logout") || path.startsWith("/logout/");
    }

}
