package app.moa.client;

import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;

/** Suppress verified service ad loaders on community reading pages.
 * Caller restricts interception to GET subresources in this controller's WebView.
 * Ordinary images, author videos/embeds, authentication and editor requests pass through.
 */
public final class AdRequestPolicy {
    private volatile boolean reading;

    public void update(String documentUrl) {
        reading = ImageRequestPolicy.isReadDocument(documentUrl);
    }

    public WebResourceResponse intercept(String requestUrl) {
        if (!reading || !isAdLoader(requestUrl)) return null;
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                Collections.singletonMap("Cache-Control", "no-store"), new ByteArrayInputStream(new byte[0]));
    }

    static boolean isAdLoader(String value) {
        if (value == null || value.length() > 8192) return false;
        final URI url;
        try { url = new URI(value); } catch (Exception invalid) { return false; }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null ||
                url.getRawUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443) ||
                url.getRawFragment() != null) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT), path = url.getRawPath();
        if (path == null) return false;
        // These exact loader paths were verified in the source mobile article
        // and its advertising SDK. Shared CDN hosts are never blocked wholesale.
        if (host.equals("pagead2.googlesyndication.com")) return path.equals("/pagead/js/adsbygoogle_direct.js");
        if (host.equals("t1.kakaocdn.net")) return path.equals("/kas/static/ba.min.js");
        if (host.equals("ssl.pstatic.net")) return path.equals("/adimg3.search/adpost/js/adpost_show_ads_v2.min.js");
        if (host.equals("display.ad.daum.net") || host.equals("serv.ds.kakao.com")) return path.equals("/sdk/banner");
        if (host.equals("addc.dcinside.com")) return path.matches("/NetInsight/text/dcinside/(?:mw/body@(?:auto|reply)_26|pv/mweb@body_26)");
        return false;
    }
}
