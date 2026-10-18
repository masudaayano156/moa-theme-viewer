package app.moa.client;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
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

    public static boolean isSearchPage(String value) {
        if (!isDcHttps(value)) return false;
        URI uri = URI.create(value);
        return "search.dcinside.com".equalsIgnoreCase(uri.getHost())
                || "m.dcinside.com".equalsIgnoreCase(uri.getHost()) && uri.getPath() != null && uri.getPath().matches("/search(?:/(?:search_main|gall_name|gall_content|movie))?/?");
    }

    /** Known reading/search routes only. Writers, account pages and arbitrary redirects stay untouched. */
    public static String mobileReadingUrl(String value) {
        if (!isDcHttps(value)) return null;
        try {
            URI uri = URI.create(value);String host = uri.getHost().toLowerCase(Locale.ROOT), path = uri.getRawPath();
            if (host.equals("search.dcinside.com") && path != null && path.matches("/(?:combine|gallery)/q/[^/]+/?")) {
                String query = URLDecoder.decode(path.replaceFirst("^/(?:combine|gallery)/q/", "").replaceFirst("/$", ""),"UTF-8");
                if (query.length() > 200 || query.matches("(?s).*[\\x00-\\x1f\\x7f].*")) return null;
                return "https://m.dcinside.com/search?keyword=" + URLEncoder.encode(query,"UTF-8").replace("+","%20");
            }
            if (host.equals("m.dcinside.com") && path != null && (path.matches("/(?:board|mini|person)/[A-Za-z0-9_]{1,80}(?:/[1-9][0-9]{0,18})?/?") || path.matches("/search(?:/(?:search_main|gall_name|gall_content|movie))?/?"))) return value;
            if (!host.equals("gall.dcinside.com") || path == null || !path.matches("/(?:mgallery/|mini/|person/)?board/(?:lists|view)/?")) return null;
            String id = galleryId(value), no = null;
            if (id.isEmpty()) return null;
            boolean view = path.matches(".*/view/?");StringBuilder filters = new StringBuilder();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] field = part.split("=",2);String key = URLDecoder.decode(field[0],"UTF-8"), entry = field.length == 2 ? URLDecoder.decode(field[1],"UTF-8") : "";
                if (key.equals("no")) { if (no != null || !entry.matches("[1-9][0-9]{0,18}")) return null; no=entry; }
                else if (key.matches("page|headid|recommend|notice|s_type|serval|s_pos|lsort|comment|cpage|csort")) {
                    if (entry.length()>2000 || entry.matches("(?s).*[\\x00-\\x1f\\x7f].*")) return null;
                    filters.append(filters.length()==0?'?':'&').append(key).append('=').append(URLEncoder.encode(entry,"UTF-8").replace("+","%20"));
                }
            }
            if (view && no == null) return null;
            String kind = path.startsWith("/mini/") ? "mini" : path.startsWith("/person/") ? "person" : "board";
            return "https://m.dcinside.com/"+kind+"/"+id+(view?"/"+no:"")+filters;
        } catch (Exception invalid) { return null; }
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
