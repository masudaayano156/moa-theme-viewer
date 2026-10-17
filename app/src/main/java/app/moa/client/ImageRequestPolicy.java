package app.moa.client;

import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Narrow, optional blocking of verified uploaded-image endpoints while reading.
 * This deliberately does not intercept all images, arbitrary third-party media,
 * captcha resources, site icons, editors, authentication, or direct media pages.
 * Call only for GET subresources, never main-frame navigation or form requests.
 */
public final class ImageRequestPolicy {
    private static final Set<String> CONTENT_HOSTS = Collections.unmodifiableSet(new HashSet<>(java.util.Arrays.asList(
            "dcimg2.dcinside.co.kr", "dcimg2.dcinside.com", "dcimg6.dcinside.co.kr")));
    private static final byte[] EMPTY_IMAGE = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGBgAAAABQABpfZFQAAAAABJRU5ErkJggg==");
    private volatile boolean blockKnownImages;

    public void update(String mode, String documentUrl) {
        blockKnownImages = "hide".equals(mode) && isReadDocument(documentUrl);
    }

    public WebResourceResponse intercept(String requestUrl) {
        if (!blockKnownImages || !isUploadedImage(requestUrl)) return null;
        return new WebResourceResponse("image/png", null, 200, "OK",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(EMPTY_IMAGE));
    }

    static boolean isReadDocument(String value) {
        URI url = parse(value);
        if (!https(url) || !"m.dcinside.com".equalsIgnoreCase(url.getHost()) || url.getRawPath() == null ||
                !url.getRawPath().matches("/(?:board|mini|person)/[A-Za-z0-9_]+(?:/[1-9][0-9]*)?/?")) return false;
        String query = url.getRawQuery();
        if (query != null) for (String part : query.split("&")) {
            int equal = part.indexOf('='); if (equal < 0) continue;
            try {
                String key = decode(part.substring(0,equal)), entry = decode(part.substring(equal+1));
                if ((key.equals("mode") || key.equals("action")) && (entry.equalsIgnoreCase("modify") || entry.equalsIgnoreCase("write") || entry.equalsIgnoreCase("edit"))) return false;
            } catch (IllegalArgumentException exception) { return false; }
        }
        return true;
    }

    static boolean isUploadedImage(String value) {
        URI url = parse(value);
        if (!https(url) || !CONTENT_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT)) ||
                !"/viewimage.php".equals(url.getRawPath()) || url.getRawQuery() == null || url.getRawFragment() != null) return false;
        int ids = 0, images = 0;
        for (String part : url.getRawQuery().split("&")) {
            int equal = part.indexOf('='); if (equal < 0) continue;
            try {
                String key = decode(part.substring(0,equal)), entry = decode(part.substring(equal+1));
                if (key.equals("id")) { if (!entry.matches("[A-Za-z0-9_-]{1,256}")) return false; ids++; }
                if (key.equals("no")) { if (!entry.matches("[A-Za-z0-9_-]{1,4096}")) return false; images++; }
            } catch (IllegalArgumentException exception) { return false; }
        }
        return ids == 1 && images == 1;
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException exception) { throw new IllegalArgumentException(exception); }
    }
    private static URI parse(String value) {
        if (value == null || value.length() > 8192) return null;
        try { return new URI(value); } catch (Exception exception) { return null; }
    }
    private static boolean https(URI url) {
        return url != null && "https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null &&
                url.getRawUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443);
    }
}
