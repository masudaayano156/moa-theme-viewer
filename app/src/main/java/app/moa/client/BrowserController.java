package app.moa.client;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.view.Gravity;
import android.content.res.ColorStateList;
import android.widget.Toast;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One persistent WebView keeps the site's forms, cookies and JS handlers intact. */
public final class BrowserController {
    public interface Listener {
        void onPageState(String title, String url, boolean loading);
        void onError(String message);
        default void onAccountFavorites(String state, List<FavoriteEntry> items) { }
        default boolean onOpenArticle(String url) { return false; }
        default void onUserActions(String nickname, String id, String ip) { }
        default void onOwnArticlePublished(String url) { }
        default void onAuthenticationChanged() { }
        default void onAuthenticationSettled() { }
    }
    public static final class FavoriteEntry {
        public final String name, url;
        public FavoriteEntry(String name, String url) { this.name = name; this.url = url; }
    }
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_REQUEST_BLOCK = new java.util.concurrent.atomic.AtomicInteger(4100);
    private final int FILE_PICK, SAVE_FILE, WEB_PERMISSION, MEDIA_PICK;
    private final Activity activity;
    private final Listener listener;
    private final String profileName;
    private final CookieManager cookies;
    private final ImageRequestPolicy imagePolicy = new ImageRequestPolicy();
    private final FrameLayout container;
    private WebView web;
    private WebView favoritesWeb;
    private final SharedPreferences drafts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Map<WebView, Dialog> popups = new HashMap<>();
    private final Map<WebView, SiteDialog> siteDialogs = new HashMap<>();
    private ThemeSpec theme = ThemeSpec.MAIL;
    private boolean dark, original, loading;
    private volatile boolean destroyed;
    private boolean openFavoritesPending, authNavigationPending;
    private int favoritesGeneration, documentGeneration, accountGeneration, authenticationGeneration, navigationIntentGeneration;
    private float fontScale = 1f;
    private String css = "", script = "", favoritesScript = "", draftScript = "", cleanupScript = "", recoveryUrl = "", draftOwner = "";
    private String communityScript = "", communityCss = "";
    private int lastDraftPromptGeneration = -1;
    private AlertDialog draftDialog, permissionDialog;
    private NavigationCheck navigationCheck;
    private PageContext approvedNavigationContext;
    private ScriptHandler documentScript;
    private PresentationGate presentation;
    private LinearLayout presentationCover;
    private TextView presentationMessage;
    private Button presentationRetry, presentationOriginal;
    private boolean browserPaused;
    private long presentationWork;
    private ValueCallback<Uri[]> fileCallback;
    private WebView fileOwner, permissionOwner;
    private boolean filePickerOutstanding, fileMultiple, permissionAwaitingSystem;
    private boolean mediaPickerOutstanding;
    private AlertDialog fileActionDialog;
    private final PostSubmissionTracker postSubmissions = new PostSubmissionTracker();
    private String publishedCandidate = "";
    private boolean peerIdentityWaiting, peerPageStale;
    private DownloadRequest pendingDownload;
    private boolean savePickerOutstanding;
    private String downloadCancellationNotice;
    private PermissionRequest pendingPermission;
    private View fullscreenView;
    private WebView fullscreenOwner;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    public BrowserController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        int requestBlock = NEXT_REQUEST_BLOCK.getAndAdd(10);
        if (requestBlock > 65000) throw new IllegalStateException("Restart the application before creating more browser instances");
        FILE_PICK=requestBlock+1; SAVE_FILE=requestBlock+2; WEB_PERMISSION=requestBlock+3; MEDIA_PICK=requestBlock+4;
        this.profileName = AccountProfiles.activeId(activity);
        this.cookies = AccountProfiles.cookies(profileName);
        drafts = activity.getSharedPreferences("moa_editor_drafts", Activity.MODE_PRIVATE);
        container = new FrameLayout(activity);
        web = createWebView();
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        try {
            css = readAsset("web/skin.css") + "\n" + readAsset("web/list-layout.css") + "\n" + readAsset("web/detail-layout.css");
            script = readAsset("web/detail-layout.js") + "\n" + readAsset("web/skin.js");
            favoritesScript = readAsset("web/account-favorites.js");
            draftScript = readAsset("web/drafts.js");
            cleanupScript = readAsset("web/page-cleanup.js");
            communityScript = readAsset("web/community-tools.js");
            communityCss = readAsset("web/community-tools.css");
        } catch (Exception exception) {
            listener.onError("테마 파일을 읽지 못했습니다. 원본 화면으로 이용할 수 있습니다.");
        }
        configure(web);
        cookies.setAcceptCookie(true);
        refreshDocumentScript();
    }

    public View getView() { return container; }
    public String profileName() { return profileName; }
    private WebView createWebView() {
        WebView created = new WebView(activity);
        try { AccountProfiles.attach(created, profileName); }
        catch (RuntimeException | LinkageError error) { created.destroy(); throw error; }
        return created;
    }

    public void openAccountFavorites() {
        navigationIntentGeneration++;
        openFavoritesPending = true;
        openInternal("https://m.dcinside.com/aside");
    }

    public void refreshAccountFavorites() {
        if (destroyed) return;
        if (peerIdentityWaiting) return;
        int generation = ++favoritesGeneration;
        listener.onAccountFavorites("loading", Collections.emptyList());
        if (favoritesWeb != null) { container.removeView(favoritesWeb); favoritesWeb.destroy(); }
        favoritesWeb = createWebView();
        configure(favoritesWeb);
        favoritesWeb.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        favoritesWeb.setDownloadListener(null);
        favoritesWeb.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) { result.cancel(); return true; }
            @Override public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) { result.cancel(); return true; }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, android.webkit.JsPromptResult result) { result.cancel(); return true; }
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) { callback.onReceiveValue(null); return true; }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, android.os.Message message) { return false; }
        });
        favoritesWeb.setVisibility(View.INVISIBLE);
        container.addView(favoritesWeb, new FrameLayout.LayoutParams(1,1));
        favoritesWeb.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && !UrlPolicy.isDcHttps(request.getUrl().toString());
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (generation != favoritesGeneration || destroyed) return;
                if (!"https://m.dcinside.com/aside".equals(url)) {
                    favoritesGeneration++;
                    updateDraftOwner("");
                    listener.onAccountFavorites("signedOut", Collections.emptyList()); return;
                }
                view.evaluateJavascript(favoritesScript + ";" + favoriteReadScript("begin"),
                    result -> readFavoriteResult(result, generation, 0));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && generation == favoritesGeneration && !destroyed) { favoritesGeneration++; updateDraftOwner(""); listener.onAccountFavorites("error", Collections.emptyList()); }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler errorHandler, SslError error) {
                errorHandler.cancel();
                if (generation == favoritesGeneration && !destroyed) { favoritesGeneration++; updateDraftOwner(""); listener.onAccountFavorites("error", Collections.emptyList()); }
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                container.removeView(view); view.destroy();
                if (view == favoritesWeb) favoritesWeb = null;
                if (generation == favoritesGeneration && !destroyed) { favoritesGeneration++; updateDraftOwner(""); listener.onAccountFavorites("error", Collections.emptyList()); }
                return true;
            }
        });
        favoritesWeb.loadUrl("https://m.dcinside.com/aside");
        handler.postDelayed(() -> {
            if (!destroyed && generation == favoritesGeneration) {
                favoritesGeneration++;
                updateDraftOwner("");
                listener.onAccountFavorites("error", Collections.emptyList());
            }
        }, 20000);
    }

    private void readFavoriteResult(String raw, int generation, int attempt) {
        if (destroyed || generation != favoritesGeneration) return;
        try {
            Object outer = new org.json.JSONTokener(raw).nextValue();
            JSONObject result = new JSONObject(outer instanceof String ? (String) outer : "{}");
            if (result.optBoolean("identityKnown", false)) {
                String identity = result.optString("identity", "");
                updateDraftOwner(identity.isEmpty() ? "guest" : "member:" + sha256(identity));
            } else updateDraftOwner("");
            String state = result.optString("state", "error");
            if ("pending".equals(state) && attempt < 20 && favoritesWeb != null) {
                handler.postDelayed(() -> {
                    if (!destroyed && generation == favoritesGeneration && favoritesWeb != null) favoritesWeb.evaluateJavascript(
                        favoriteReadScript("read"),
                        value -> readFavoriteResult(value, generation, attempt+1));
                }, 400); return;
            }
            favoritesGeneration++;
            List<FavoriteEntry> items = new ArrayList<>();
            JSONArray data = result.optJSONArray("items");
            if ("ready".equals(state) && data != null) for (int i=0; i<data.length(); i++) {
                JSONObject row = data.optJSONObject(i);
                if (row == null) continue;
                String url = row.optString("url", ""), name = row.optString("name", "");
                if (UrlPolicy.isDcHttps(url) && "m.dcinside.com".equals(Uri.parse(url).getHost()) && !UrlPolicy.galleryId(url).isEmpty() && !name.isEmpty()) items.add(new FavoriteEntry(name,url));
            }
            if ("pending".equals(state)) state = "error";
            if ("ready".equals(state) && items.isEmpty()) state = "emptyOrUnavailable";
            listener.onAccountFavorites(state, items);
        } catch (Exception error) { favoritesGeneration++; updateDraftOwner(""); listener.onAccountFavorites("error", Collections.emptyList()); }
    }
    private String favoriteReadScript(String method) {
        return "(()=>{const api=window.MoaAccountFavorites;const r=api?api." + method + "():{state:'error',items:[]};"
            + "r.identityKnown=!!document.querySelector('#user_id');r.identity=api&&api.getIdentity?api.getIdentity():'';return JSON.stringify(r);})()";
    }
    public String getUrl() { return destroyed ? "" : (web.getUrl() == null ? recoveryUrl : web.getUrl()); }
    public String getTitle() { return destroyed || web.getTitle() == null ? "" : web.getTitle(); }
    public boolean isOriginalMode() { return original; }
    public void open(String url) {
        cancelFavoritesNavigation();
        openInternal(url);
    }
    private void openInternal(String url) {
        if (destroyed) return;
        postSubmissions.cancel();
        if (peerIdentityWaiting) { listener.onError("다른 화면의 로그인 처리가 끝난 뒤 다시 열어 주세요."); return; }
        if (UrlPolicy.isDcHttps(url)) {
            snapshotDraft(() -> { prepareNavigation(url); web.loadUrl(url); });
        } else {
            openExternal(url);
        }
    }
    public boolean canGoBack() { return !destroyed && (fullscreenView != null || web.canGoBack()); }
    public void goBack() {
        postSubmissions.cancel();
        if (fullscreenView != null) hideFullscreen();
        else if (!destroyed && web.canGoBack()) { cancelFavoritesNavigation(); snapshotDraft(() -> { prepareHistoryNavigation(-1); web.goBack(); }); }
    }
    public boolean canGoForward() { return !destroyed && web.canGoForward(); }
    public void goForward() { if (canGoForward()) { postSubmissions.cancel(); publishedCandidate=""; cancelFavoritesNavigation(); snapshotDraft(() -> { prepareHistoryNavigation(1); web.goForward(); }); } }
    public void reload() {
        postSubmissions.cancel();
        if (peerIdentityWaiting) { listener.onError("다른 화면의 로그인 처리가 끝난 뒤 새로고침해 주세요."); return; }
        cancelFavoritesNavigation(); if (!recoveryUrl.isEmpty()) openInternal(recoveryUrl); else snapshotDraft(() -> { beginPresentation(getUrl(), false); web.reload(); });
    }

    private void cancelFavoritesNavigation() {
        navigationIntentGeneration++;
        openFavoritesPending = false;
    }

    private boolean isFavoritesPage(String url) {
        if (!UrlPolicy.isDcHttps(url)) return false;
        Uri parsed = Uri.parse(url);
        return "m.dcinside.com".equalsIgnoreCase(parsed.getHost()) && "/aside".equals(parsed.getPath());
    }

    public void setTheme(ThemeSpec theme, boolean dark) {
        cancelNavigationGuard();
        this.theme = theme == null ? ThemeSpec.MAIL : theme;
        this.dark = dark;
        container.setBackgroundColor(this.theme.palette(dark).background);
        web.setBackgroundColor(this.theme.palette(dark).background);
        refreshDocumentScript();
        restartCoveredPresentation();
        applyTheme();
    }
    public void setFontScale(float scale) {
        fontScale = Math.max(.85f, Math.min(1.4f, scale));
        refreshDocumentScript();
        restartCoveredPresentation();
        applyTheme();
    }
    public void setOriginalMode(boolean enabled) {
        boolean changed = original != enabled;
        original = enabled;
        refreshDocumentScript();
        if (changed && !enabled && UrlPolicy.canTheme(getUrl())) beginPresentation(getUrl(), !loading);
        applyTheme();
    }

    public void openComposer() {
        cancelFavoritesNavigation();
        if (!UrlPolicy.isDcHttps(getUrl())) {
            listener.onError("먼저 글을 작성할 곳을 열어 주세요."); return;
        }
        // Use the site's own visible action, preserving server routing and permissions.
        String find = "(()=>{if(document.querySelector('#writeForm'))return 'already-open';const es=[...document.querySelectorAll('a,button,input[type=button]')];"
            + "const el=es.find(e=>e.getClientRects().length && /^(글쓰기|글 쓰기)$/.test((e.innerText||e.value||e.getAttribute('aria-label')||'').trim()));"
            + "if(el){el.click();return 'opened';}return document.querySelector('#writeForm')?'already-open':'missing';})()";
        web.evaluateJavascript(find, result -> {
            if ("\"missing\"".equals(result)) listener.onError("이 화면에는 글쓰기 버튼이 없습니다. 목록으로 돌아가서 선택해 주세요.");
        });
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configure(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        cookies.setAcceptThirdPartyCookies(target, false);
        target.setWebViewClient(new Client());
        target.setWebChromeClient(new Chrome(target));
        target.setDownloadListener((url, userAgent, disposition, mime, size) -> requestDownload(target, url, userAgent, disposition, mime));
        if (target == web) installLocalActions(target);
    }

    private void installLocalActions(WebView target) {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
            WebViewCompat.addWebMessageListener(target,"MoaLocalActions",Collections.singleton("https://m.dcinside.com"),
                (view,message,origin,mainFrame,reply) -> {
                    if (destroyed || view != web || !mainFrame || !"https".equals(origin.getScheme())
                            || !"m.dcinside.com".equals(origin.getHost()) || origin.getPort() != -1 && origin.getPort() != 443
                            || !UrlPolicy.canTheme(getUrl())) return;
                    String raw;
                    try { raw = message.getData(); } catch (RuntimeException unsupportedMessage) { return; }
                    if (raw == null || raw.length() > 2048) return;
                    try {
                        JSONObject value = new JSONObject(raw);
                        if ("submitting_post".equals(value.optString("action"))) {
                            java.net.URI uri = java.net.URI.create(getUrl());
                            if (uri.getPath().matches("/write/[A-Za-z0-9_]{1,80}/?")) {
                                postSubmissions.begin(getUrl(),android.os.SystemClock.elapsedRealtime());
                            }
                        } else if ("user".equals(value.optString("action"))) {
                            String name = safeIdentity(value.optString("nickname")), id = safeIdentity(value.optString("id")), ip = safeIdentity(value.optString("ip"));
                            if (!name.isEmpty() || !id.isEmpty() || !ip.isEmpty()) listener.onUserActions(name,id,ip);
                        }
                    } catch (Exception invalid) { }
                });
        } catch (RuntimeException | LinkageError unavailable) { /* Native tools remain available in settings. */ }
    }

    private static String safeIdentity(String value) {
        return value != null && value.length() <= 120 && !value.matches("(?s).*[\\x00-\\x1F\\x7F].*") ? value.trim() : "";
    }

    private final class Client extends WebViewClient {
        @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (view != web || destroyed || request.isForMainFrame() || !"GET".equals(request.getMethod())) return null;
            return imagePolicy.intercept(request.getUrl().toString());
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!isLive(view)) return true;
            if (!request.isForMainFrame()) return false;
            String url = request.getUrl().toString();
            if (view == web && request.hasGesture() && !request.isRedirect()) { cancelFavoritesNavigation(); postSubmissions.cancel(); }
            if (UrlPolicy.isDcHttps(url)) {
                if (view == web && request.hasGesture() && !request.isRedirect()
                        && CommunityStore.canonicalArticleUrl(url) != null && listener.onOpenArticle(url)) return true;
                if (view == web && !sameDocumentFragment(getUrl(), url)) prepareNavigation(url);
                return false;
            }
            Dialog popup = popups.get(view);
            if (popup != null) handler.post(popup::dismiss);
            openExternal(url); return true;
        }
        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!isLive(view)) return;
            cancelPageRequests(view);
            if (view == web) peerPageStale = false;
            if (UrlPolicy.isAuthenticationPage(url)) {
                if (!authNavigationPending) listener.onAuthenticationChanged();
                if (view != web) beginPeerAuthentication();
                invalidatePendingDownload("로그인 상태가 변경되어 저장을 취소했습니다. 파일을 다시 선택해 주세요.");
                authenticationGeneration++;
                authNavigationPending = true;
                updateDraftOwner("");
                accountGeneration++;
                // A popup can change the shared session while the main document
                // still belongs to the old account. Keep it covered until reload.
                restartCoveredPresentation();
                favoritesGeneration++;
                if (favoritesWeb != null) {
                    favoritesWeb.stopLoading();
                    container.removeView(favoritesWeb);
                    favoritesWeb.destroy();
                    favoritesWeb = null;
                }
                listener.onAccountFavorites("loading", Collections.emptyList());
            }
            if (view != web) return;
            imagePolicy.update(CommunityOptions.imageMode(activity),url);
            String article = postSubmissions.onNavigation(url,android.os.SystemClock.elapsedRealtime());
            publishedCandidate = article == null ? "" : article;
            if (!isFavoritesPage(url) && !UrlPolicy.isAuthenticationPage(url)) openFavoritesPending = false;
            documentGeneration++;
            beginPresentation(url, false);
            dismissDraftDialog();
            recoveryUrl = "";
            loading = true;
            listener.onPageState(view.getTitle(), url, true);
        }
        @Override public void onPageCommitVisible(WebView view, String url) {
            if (view != web || !isLive(view)) return;
            PresentationGate gate = presentation;
            if (gate != null && gate.matchesDocument(url)) {
                gate.committed = true;
                if (gate.failed) showPresentationCover(true, presentationMessage.getText().toString());
                else gate.probe();
            }
        }
        @Override public void onPageFinished(WebView view, String url) {
            if (!isLive(view)) return;
            if (view == web && !documentKey(url).equals(documentKey(getUrl()))) return;
            cookies.flush();
            if (view != web) {
                if (UrlPolicy.canTheme(url) && !cleanupScript.isEmpty()) view.evaluateJavascript(cleanupScript, null);
                if (authNavigationPending && !UrlPolicy.isAuthenticationPage(url)) settleAuthentication();
                return;
            }
            loading = false;
            if (UrlPolicy.canTheme(url)) view.evaluateJavascript(draftScript, null);
            applyTheme();
            if (presentation == null) recordRecentArticle();
            listener.onPageState(view.getTitle(), url, false);
            offerDraftRestore(url);
            if (openFavoritesPending && isFavoritesPage(url)) {
                openFavoritesPending = false;
                final WebView source = view;
                final int page = documentGeneration, auth = authenticationGeneration, intent = navigationIntentGeneration;
                view.evaluateJavascript(favoritesScript + ";JSON.stringify(window.MoaAccountFavorites?window.MoaAccountFavorites.begin():{state:'error'})", result -> {
                    if (destroyed || source != web || page != documentGeneration || auth != authenticationGeneration
                            || intent != navigationIntentGeneration || !isFavoritesPage(getUrl())) return;
                    try {
                        Object decoded = new org.json.JSONTokener(result == null ? "null" : result).nextValue();
                        JSONObject response = new JSONObject(decoded instanceof String ? (String) decoded : "{}");
                        if ("signedOut".equals(response.optString("state"))) {
                            openFavoritesPending = true;
                            navigationIntentGeneration++;
                            openInternal("https://msign.dcinside.com/login?r_url=https%3A%2F%2Fm.dcinside.com%2Faside");
                        }
                    } catch (Exception ignored) { }
                });
            }
            if (isFavoritesPage(url) || (url != null && authNavigationPending && !UrlPolicy.isAuthenticationPage(url))) {
                if (authNavigationPending) settleAuthentication(); else refreshAccountFavorites();
            }
        }
        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            if (view == web) listener.onPageState(view.getTitle(), url, loading);
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (isLive(view) && request.isForMainFrame() && view == web) {
                loading = false;
                if (presentation != null && presentation.url.equals(presentationUrl(request.getUrl().toString())))
                    failPresentation("페이지 연결을 완료하지 못했습니다. 인터넷 연결을 확인해 주세요.");
                listener.onError("페이지를 열지 못했습니다. 인터넷 연결을 확인한 뒤 새로고침해 주세요.");
                listener.onPageState(view.getTitle(), view.getUrl(), false);
            }
        }
        @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
            sslHandler.cancel();
            if (view == web && isLive(view) && presentation != null && error != null
                    && presentation.url.equals(presentationUrl(error.getUrl()))) failPresentation("사이트의 보안 연결을 확인하지 못했습니다.");
            if (isLive(view)) listener.onError("사이트의 보안 연결을 확인할 수 없어 열기를 중단했습니다.");
        }
        @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            if (!isLive(view)) return true;
            cancelPageRequests(view);
            if (view != web) {
                Dialog dialog = popups.get(view);
                if (dialog != null) dialog.dismiss();
                return true;
            }
            recoveryUrl = getUrl();
            documentGeneration++;
            dismissDraftDialog();
            hideFullscreen();
            if (documentScript != null) { try { documentScript.remove(); } catch (RuntimeException ignored) { } documentScript = null; }
            container.removeView(view);
            view.destroy();
            web = createWebView();
            configure(web);
            container.addView(web, 0, new FrameLayout.LayoutParams(-1, -1));
            beginPresentation(recoveryUrl, false);
            failPresentation("웹 화면이 중단되었습니다. 새로고침으로 다시 열 수 있습니다.");
            web.setBackgroundColor(theme.palette(dark).background);
            refreshDocumentScript();
            loading = false;
            listener.onError("웹 화면을 다시 준비했습니다. 새로고침을 눌러 페이지를 다시 열어 주세요.");
            listener.onPageState("", recoveryUrl, false);
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        private final WebView owner;
        Chrome(WebView owner) { this.owner = owner; }
        @Override public void onReceivedTitle(WebView view, String title) {
            if (isLive(view) && view == web) listener.onPageState(title, getUrl(), loading);
        }
        @Override public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
            return showSiteDialog(view, owner, message, result, false);
        }
        @Override public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
            return showSiteDialog(view, owner, message, result, true);
        }
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (!isLive(view) || filePickerOutstanding) { callback.onReceiveValue(null); return true; }
            fileCallback = callback;
            fileOwner = view;
            filePickerOutstanding = true;
            fileMultiple = params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            List<String> types = new ArrayList<>();
            for (String type : params.getAcceptTypes() == null ? new String[0] : params.getAcceptTypes()) {
                if (type == null) continue;
                for (String item : type.split(",")) {
                    item = item.trim();
                    if (item.startsWith(".")) item = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.substring(1));
                    if (item != null && item.contains("/")) types.add(item);
                }
            }
            intent.setType(types.size() == 1 ? types.get(0) : "*/*");
            if (types.size() > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            boolean images = types.isEmpty() || types.stream().anyMatch(type -> type.startsWith("image/") || "*/*".equals(type));
            if (!images) {
                try { activity.startActivityForResult(intent, FILE_PICK); }
                catch (RuntimeException exception) { finishFileSelection(null); listener.onError("파일 선택 앱을 찾지 못했습니다."); }
            } else {
                boolean[] launched = {false};
                fileActionDialog = new AlertDialog.Builder(activity).setTitle("사진 첨부")
                    .setItems(new String[]{"원본 파일 선택", "사진 편집 · GIF 만들기"}, (dialog, which) -> {
                        if (fileCallback == null || !isLive(fileOwner)) return;
                        launched[0] = true;
                        try {
                            if (which == 0) activity.startActivityForResult(intent, FILE_PICK);
                            else {
                                mediaPickerOutstanding = true;
                                activity.startActivityForResult(MediaToolsActivity.createIntent(activity,new ArrayList<>(),true), MEDIA_PICK);
                            }
                        } catch (RuntimeException unavailable) { finishFileSelection(null); listener.onError("첨부 도구를 열지 못했습니다."); }
                    }).create();
                fileActionDialog.setOnDismissListener(ignored -> { fileActionDialog = null; if (!launched[0]) finishFileSelection(null); });
                fileActionDialog.show();
            }
            return true;
        }
        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            if (!isUserGesture || view != owner || !isLive(owner) || resultMsg == null
                    || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
            WebView child = createWebView();
            configure(child);
            Dialog dialog = new Dialog(activity);
            dialog.setContentView(child);
            dialog.setOnDismissListener(ignored -> { cancelPageRequests(child); popups.remove(child); child.destroy(); });
            popups.put(child, dialog);
            dialog.show();
            if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(child);
            resultMsg.sendToTarget(); return true;
        }
        @Override public void onCloseWindow(WebView window) {
            Dialog dialog = popups.get(window);
            if (dialog != null) dialog.dismiss();
        }
        @Override public void onShowCustomView(View view, CustomViewCallback callback) {
            if (!isLive(owner) || fullscreenView != null || view == null || view.getParent() != null) { callback.onCustomViewHidden(); return; }
            ViewGroup host = owner == web ? container : (owner.getParent() instanceof ViewGroup ? (ViewGroup) owner.getParent() : null);
            if (host == null) { callback.onCustomViewHidden(); return; }
            fullscreenView = view; fullscreenCallback = callback; fullscreenOwner = owner;
            host.addView(view, new ViewGroup.LayoutParams(-1, -1));
            owner.setVisibility(View.GONE);
        }
        @Override public void onHideCustomView() { if (fullscreenOwner == owner) hideFullscreen(); }
        @Override public void onPermissionRequest(PermissionRequest request) {
            activity.runOnUiThread(() -> requestWebPermission(owner, request));
        }
        @Override public void onPermissionRequestCanceled(PermissionRequest request) {
            if (pendingPermission == request) {
                pendingPermission = null; permissionOwner = null;
                if (permissionDialog != null) { permissionDialog.dismiss(); permissionDialog = null; }
            }
        }
    }

    /** Native dialogs may not outlive the document that created their JS result. */
    private final class SiteDialog {
        final WebView owner;
        final android.webkit.JsResult result;
        AlertDialog dialog;
        boolean resolved;
        SiteDialog(WebView owner, android.webkit.JsResult result) { this.owner = owner; this.result = result; }
        void finish(boolean accepted) {
            if (resolved) return;
            resolved = true;
            if (siteDialogs.get(owner) == this) siteDialogs.remove(owner);
            if (dialog != null) dialog.dismiss();
            if (accepted && isLive(owner)) result.confirm(); else result.cancel();
        }
    }

    private boolean showSiteDialog(WebView view, WebView owner, String message, android.webkit.JsResult result, boolean confirmation) {
        if (view != owner || !isLive(owner) || activity.isFinishing() || activity.isDestroyed() || siteDialogs.containsKey(owner)) {
            result.cancel(); return true;
        }
        SiteDialog request = new SiteDialog(owner, result);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(confirmation ? "사이트 확인" : "사이트 안내")
            .setMessage(message).setPositiveButton("확인", (dialog, which) -> request.finish(true))
            .setOnCancelListener(dialog -> request.finish(false));
        if (confirmation) builder.setNegativeButton("취소", (dialog, which) -> request.finish(false));
        request.dialog = builder.create();
        request.dialog.setOnDismissListener(dialog -> request.finish(false));
        siteDialogs.put(owner, request);
        try { request.dialog.show(); }
        catch (RuntimeException unavailableWindow) { request.finish(false); }
        return true;
    }

    private void hideFullscreen() {
        if (fullscreenView == null) return;
        View finished = fullscreenView;
        WebView owner = fullscreenOwner;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenView = null; fullscreenOwner = null; fullscreenCallback = null;
        if (finished.getParent() instanceof ViewGroup) ((ViewGroup) finished.getParent()).removeView(finished);
        if (isLive(owner)) owner.setVisibility(View.VISIBLE);
        if (callback != null) callback.onCustomViewHidden();
    }

    private boolean isLive(WebView view) {
        return !destroyed && view != null && (view == web || popups.containsKey(view));
    }

    private void finishFileSelection(Uri[] selected) {
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null; fileOwner = null; filePickerOutstanding = false; mediaPickerOutstanding = false;
        if (callback != null) callback.onReceiveValue(selected);
    }

    private void denyPermissionIfCurrent(PermissionRequest request) {
        if (pendingPermission != request) return;
        pendingPermission = null; permissionOwner = null;
        request.deny();
        if (permissionDialog != null) { permissionDialog.dismiss(); permissionDialog = null; }
    }

    private void cancelPageRequests(WebView view) {
        if (view == web) cancelNavigationGuard();
        SiteDialog siteDialog = siteDialogs.get(view);
        if (siteDialog != null) siteDialog.finish(false);
        if (fullscreenOwner == view) hideFullscreen();
        if (fileOwner == view) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null; fileOwner = null;
            // The old OS picker still owns FILE_PICK until its result arrives.
            if (callback != null) callback.onReceiveValue(null);
            if (fileActionDialog != null) fileActionDialog.dismiss();
        }
        if (permissionOwner == view && pendingPermission != null) denyPermissionIfCurrent(pendingPermission);
    }

    private boolean isSafeDocument(Uri uri, boolean forWrite) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme()) || uri.getAuthority() == null) return false;
        try {
            ProviderInfo provider = activity.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
            if (provider != null && (activity.getPackageName().equals(provider.packageName)
                    || (provider.applicationInfo != null && provider.applicationInfo.uid == android.os.Process.myUid()))) return false;
            int mode = forWrite ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION;
            // ACTION_OPEN/CREATE_DOCUMENT grants the selected URI. Do not open cloud
            // provider files on the UI thread; the WebView/download worker does I/O.
            return activity.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), mode)
                == PackageManager.PERMISSION_GRANTED;
        } catch (Exception invalidDocument) { return false; }
    }

    private void requestWebPermission(WebView owner, PermissionRequest request) {
        if (!isLive(owner) || pendingPermission != null || permissionAwaitingSystem
                || !UrlPolicy.isDcHttps(request.getOrigin().toString())) { request.deny(); return; }
        pendingPermission = request;
        permissionOwner = owner;
        List<String> permissions = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) permissions.add(Manifest.permission.RECORD_AUDIO);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) permissions.add(Manifest.permission.CAMERA);
        }
        if (permissions.isEmpty()) { request.deny(); pendingPermission = null; permissionOwner = null; return; }
        permissionDialog = new AlertDialog.Builder(activity).setTitle("사이트 권한 요청")
            .setMessage("디시인사이드에서 선택한 녹음 또는 촬영 기능에 마이크·카메라 권한이 필요합니다.")
            .setPositiveButton("계속", (dialog, which) -> {
                if (pendingPermission != request) return;
                permissionAwaitingSystem = true;
                activity.requestPermissions(permissions.toArray(new String[0]), WEB_PERMISSION);
            }).setNegativeButton("취소", (dialog, which) -> denyPermissionIfCurrent(request))
            .setOnCancelListener(dialog -> denyPermissionIfCurrent(request)).show();
    }
    public boolean onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != WEB_PERMISSION) return false;
        if (!permissionAwaitingSystem) return true;
        permissionAwaitingSystem = false;
        PermissionRequest request = pendingPermission; pendingPermission = null;
        WebView owner = permissionOwner; permissionOwner = null;
        if (request == null) return true;
        if (!isLive(owner)) { request.deny(); return true; }
        List<String> accepted = new ArrayList<>();
        for (int index=0; index<Math.min(permissions.length,results.length); index++)
            if (results[index] == PackageManager.PERMISSION_GRANTED) accepted.add(permissions[index]);
        List<String> granted = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && accepted.contains(Manifest.permission.RECORD_AUDIO)) granted.add(resource);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && accepted.contains(Manifest.permission.CAMERA)) granted.add(resource);
        }
        if (granted.isEmpty()) request.deny(); else request.grant(granted.toArray(new String[0]));
        return true;
    }

    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MEDIA_PICK) {
            List<Uri> result = resultCode == Activity.RESULT_OK ? MediaToolsActivity.outputUris(data) : Collections.emptyList();
            boolean valid = mediaPickerOutstanding && filePickerOutstanding && isLive(fileOwner) && !result.isEmpty()
                    && (fileMultiple || result.size() == 1);
            for (Uri uri:result) if (!isSafeEditedMedia(uri)) valid=false;
            finishFileSelection(valid ? result.toArray(new Uri[0]) : null);
            return true;
        }
        if (requestCode == FILE_PICK) {
            List<Uri> selected = new ArrayList<>();
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) selected.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) selected.add(data.getData());
            }
            selected = new ArrayList<>(new LinkedHashSet<>(selected));
            boolean valid = !destroyed && isLive(fileOwner) && selected.size() <= 50 && (fileMultiple || selected.size() <= 1);
            for (Uri uri : selected) if (!isSafeDocument(uri, false)) valid = false;
            finishFileSelection(valid && !selected.isEmpty() ? selected.toArray(new Uri[0]) : null);
            return true;
        }
        if (requestCode == SAVE_FILE) {
            DownloadRequest request = pendingDownload;
            boolean expected = savePickerOutstanding;
            String cancellation = downloadCancellationNotice;
            pendingDownload = null; savePickerOutstanding = false; downloadCancellationNotice = null;
            if (!destroyed && expected && resultCode == Activity.RESULT_OK && data != null) {
                if (request == null) {
                    listener.onError(cancellation == null ? "저장 요청이 만료되었습니다. 파일을 다시 선택해 주세요." : cancellation);
                } else if (!Objects.equals(request.cookie, downloadCookie(request.url))) {
                    listener.onError("로그인 상태가 변경되어 저장을 취소했습니다. 파일을 다시 선택해 주세요.");
                } else if (isSafeDocument(data.getData(), true)) saveDownload(request, data.getData());
                else listener.onError("선택한 저장 위치에 접근할 수 없습니다.");
            }
            return true;
        }
        return false;
    }
    public boolean ownsActivityResult(int code) {
        return code == MEDIA_PICK && mediaPickerOutstanding || code == FILE_PICK && filePickerOutstanding && !mediaPickerOutstanding
                || code == SAVE_FILE && savePickerOutstanding;
    }
    public boolean ownsPermissionRequest(int code) { return code == WEB_PERMISSION && permissionAwaitingSystem; }

    private boolean isSafeEditedMedia(Uri uri) {
        if (!MediaToolsActivity.validOutputUri(uri)) return false;
        try {
            java.io.File file = new java.io.File(MediaImageOps.directory(activity), uri.getLastPathSegment());
            return MediaImageOps.ownedFile(activity,file) && file.isFile() && file.length()>0 && file.length()<=25L*1024*1024;
        } catch (Exception invalid) { return false; }
    }

    private String skinSource() {
        try {
            JSONObject config = new JSONObject().put("theme", theme.id).put("dark", dark)
                .put("fontScale", fontScale).put("groupNoun", theme.groupNoun).put("displayName", theme.displayName);
            if (CommunityOptions.accentIndex(activity) >= 0) {
                ThemeSpec.Palette palette = theme.palette(dark).withAccent(CommunityOptions.accentColor(activity, theme.palette(dark).accent));
                config.put("accent", String.format(java.util.Locale.ROOT,"#%06x", palette.accent & 0xFFFFFF))
                    .put("accentOn", String.format(java.util.Locale.ROOT,"#%06x", palette.onAccent & 0xFFFFFF))
                    .put("accentTint", String.format(java.util.Locale.ROOT,"#%06x", palette.tint & 0xFFFFFF));
            }
            return "if(window.top===window){window.__MOA_SKIN_CSS=" + JSONObject.quote(css) + ";\n" + script
                + "\nwindow.MoaSkin&&window.MoaSkin.apply(" + config + ");}";
        } catch (Exception ex) { return ""; }
    }
    private void refreshDocumentScript() {
        if (destroyed) return;
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
            if (documentScript != null) { documentScript.remove(); documentScript = null; }
            String source = cleanupScript + "\n" + draftScript + "\n" + communitySource("") + "\n" + submissionHook() + ((!original && !script.isEmpty()) ? "\n" + skinSource() : "");
            documentScript = WebViewCompat.addDocumentStartJavaScript(web, source, Collections.singleton("https://m.dcinside.com"));
        } catch (RuntimeException | LinkageError unavailableProvider) {
            // Older or unavailable providers still receive the onPageFinished skin.
            documentScript = null;
        }
    }
    private void applyTheme() {
        if (destroyed) return;
        if (peerPageStale) { showPeerAccountCover(); return; }
        if (UrlPolicy.canTheme(getUrl()) && !cleanupScript.isEmpty()) web.evaluateJavascript(cleanupScript, null);
        if (UrlPolicy.canTheme(getUrl()) && !communityScript.isEmpty()) web.evaluateJavascript(communitySource(getUrl()), null);
        if (original || !UrlPolicy.canTheme(getUrl())) {
            if (original && presentation != null) revealOriginalPresentation();
            else {
                clearPresentation();
                web.evaluateJavascript("window.MoaSkin&&window.MoaSkin.disable();", null);
            }
        } else if (presentation != null) presentation.probe();
        else if (!script.isEmpty()) web.evaluateJavascript(skinSource(), null);
    }

    private String communitySource(String url) {
        if (communityScript.isEmpty()) return "";
        JSONObject config = CommunityOptions.webConfig(activity, UrlPolicy.galleryId(url));
        return "if(window.top===window){window.__MOA_COMMUNITY_CSS=" + JSONObject.quote(communityCss) + ";\n"
                + communityScript + "\nwindow.MoaCommunityTools&&window.MoaCommunityTools.apply(" + config + ");}";
    }

    private String submissionHook() {
        return "if(window.top===window&&!window.__moaSubmitHook){window.__moaSubmitHook=true;document.addEventListener('click',function(e){if(!e.isTrusted||!window.MoaLocalActions)return;var n=e.target&&e.target.closest&&e.target.closest('.wrt-tit-box button[onclick],#writeForm button[onclick]');if(n&&/^\\s*write_submit\\(\\);?\\s*$/.test(n.getAttribute('onclick')||''))MoaLocalActions.postMessage(JSON.stringify({action:'submitting_post'}));},true);}";
    }

    public void beginPeerAuthentication() {
        if (destroyed) return;
        peerIdentityWaiting = true; peerPageStale = UrlPolicy.canTheme(getUrl());
        postSubmissions.cancel(); publishedCandidate = "";
        cancelPageRequests(web);
        authenticationGeneration++; favoritesGeneration++;
        if (favoritesWeb != null) { container.removeView(favoritesWeb); favoritesWeb.destroy(); favoritesWeb = null; }
        updateDraftOwner("");
        if (peerPageStale) showPeerAccountCover();
    }

    public void finishPeerAuthentication() {
        if (destroyed) return;
        peerIdentityWaiting = false;
        refreshAccountFavorites();
        if (peerPageStale && isListPage(getUrl())) guardNavigation(this::reload);
    }

    private void settleAuthentication() {
        authNavigationPending = false;
        finishPeerAuthentication();
        listener.onAuthenticationSettled();
    }

    private boolean isListPage(String value) {
        try { return UrlPolicy.canTheme(value) && java.net.URI.create(value).getPath().matches("/(?:board|mini|person)/[A-Za-z0-9_]+/?"); }
        catch (RuntimeException error) { return false; }
    }

    private void showPeerAccountCover() {
        if (destroyed || !peerPageStale) return;
        if (presentation != null) presentation.suspend();
        presentation = new PresentationGate(getUrl(), false); presentation.failed = true;
        showPresentationCover(true,"다른 화면에서 로그인 상태가 바뀌었습니다. 작성 내용이 있으면 복사한 뒤 새로고침해 주세요.");
    }

    private void copyPendingText() {
        if (destroyed || !UrlPolicy.canTheme(getUrl())) return;
        PageContext context = new PageContext();
        web.evaluateJavascript(draftScript + ";JSON.stringify(window.MoaDrafts?window.MoaDrafts.capture("+JSONObject.quote(context.url)+"):{fields:[]})", result -> {
            if (!context.isCurrent()) return;
            try {
                Object decoded = new org.json.JSONTokener(result).nextValue();
                JSONArray fields = new JSONObject(decoded instanceof String ? (String)decoded : "{}").optJSONArray("fields");
                StringBuilder text = new StringBuilder();
                if (fields != null) for(int i=0;i<fields.length();i++) { JSONObject field=fields.optJSONObject(i);if(field!=null)text.append(field.optString("text")).append("\n\n"); }
                if(text.length()==0) { listener.onError("복사할 작성 내용이 없습니다."); return; }
                android.content.ClipboardManager clipboard=activity.getSystemService(android.content.ClipboardManager.class);
                if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("작성 중인 내용",text.toString()));
                Toast.makeText(activity,"작성 내용을 복사했습니다.",Toast.LENGTH_SHORT).show();
            } catch(Exception error) { listener.onError("작성 내용을 복사하지 못했습니다."); }
        });
    }

    public void refreshCommunityOptions() {
        if (destroyed) return;
        imagePolicy.update(CommunityOptions.imageMode(activity),getUrl());
        refreshDocumentScript();
        restartCoveredPresentation();
        applyTheme();
    }

    private void recordRecentArticle() {
        String url = CommunityStore.canonicalArticleUrl(getUrl());
        if (url == null || !CommunityStore.isHistoryEnabled(activity) && publishedCandidate.isEmpty()) return;
        PageContext page = new PageContext();
        web.evaluateJavascript("/*moa-article-metadata*/(()=>{const n=document.querySelector('.gallview-tit-box>.tit');return n?JSON.stringify({url:location.href,title:n.textContent.slice(0,200)}):null})()", result -> {
            if (!page.isCurrent()) return;
            try {
                Object decoded = new org.json.JSONTokener(result == null ? "null" : result).nextValue();
                if (!(decoded instanceof String)) return;
                JSONObject value = new JSONObject((String)decoded);
                if (url.equals(CommunityStore.canonicalArticleUrl(value.optString("url")))) {
                    CommunityStore.recordRecent(activity, url, value.optString("title"));
                    if (url.equals(publishedCandidate)) { publishedCandidate=""; listener.onOwnArticlePublished(url); }
                }
            } catch (Exception invalid) { }
        });
    }

    public void openCommunitySourceTool(String kind) {
        if (destroyed || !UrlPolicy.canTheme(getUrl())) { listener.onError("먼저 게시판이나 글을 열어 주세요."); return; }
        final String selector;
        switch(kind) {
            case "autopicture": selector = "#galleryConfig a[href^=\"javascript:moveAutozzal(\"]"; break;
            case "source_affixes": selector = "#galleryConfig a[href^=\"javascript:moveGallText(\"]"; break;
            case "voice": selector = "#comment_write #submit_voice"; break;
            default: listener.onError("지원하지 않는 원본 도구입니다."); return;
        }
        guardNavigation(() -> {
            PageContext page = new PageContext();
            web.evaluateJavascript("(()=>{const n=document.querySelector(" + JSONObject.quote(selector) + ");if(!n||n.disabled)return false;n.click();return true})()", found -> {
                if (page.isCurrent() && !"true".equals(found)) listener.onError("이 페이지에는 해당 원본 설정이 없습니다. 게시판 목록에서 다시 시도해 주세요.");
            });
        });
    }

    private String presentationUrl(String url) {
        if (url == null) return "";
        int fragment = url.indexOf('#');
        return fragment < 0 ? url : url.substring(0, fragment);
    }

    private boolean sameDocumentFragment(String current, String next) {
        return current != null && next != null && presentationUrl(current).equals(presentationUrl(next))
                && (!current.equals(next) || next.contains("#"));
    }

    private void prepareNavigation(String url) {
        imagePolicy.update(CommunityOptions.imageMode(activity),url);
        if (!sameDocumentFragment(getUrl(), url)) beginPresentation(url, false);
    }

    private void prepareHistoryNavigation(int offset) {
        android.webkit.WebBackForwardList history = web.copyBackForwardList();
        int index = history.getCurrentIndex() + offset;
        String target = index >= 0 && index < history.getSize() ? history.getItemAtIndex(index).getUrl() : getUrl();
        prepareNavigation(target);
    }

    private void beginPresentation(String url, boolean committed) {
        if (destroyed) return;
        if (peerPageStale) { showPeerAccountCover(); return; }
        if (original || !UrlPolicy.canTheme(url)) { clearPresentation(); return; }
        if (presentation != null) presentation.suspend();
        presentation = new PresentationGate(url, committed);
        showPresentationCover(false, "테마 화면을 준비하고 있어요");
        presentation.resume();
    }

    private void restartCoveredPresentation() {
        if (presentation == null) return;
        String url = presentation.url;
        boolean committed = presentation.committed;
        beginPresentation(url, committed);
    }

    private void showPresentationCover(boolean failure, String message) {
        if (presentationCover == null) {
            presentationCover = new LinearLayout(activity);
            presentationCover.setOrientation(LinearLayout.VERTICAL);
            presentationCover.setGravity(Gravity.CENTER);
            int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            presentationCover.setPadding(padding, padding, padding, padding);
            presentationCover.setClickable(true);
            presentationCover.setFocusable(true);
            presentationCover.setTag("page-presentation-cover");
            presentationMessage = new TextView(activity);
            presentationMessage.setGravity(Gravity.CENTER);
            presentationMessage.setTextSize(16);
            presentationMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            presentationCover.addView(presentationMessage, new LinearLayout.LayoutParams(-1, -2));
            presentationRetry = new Button(activity);
            presentationRetry.setText("화면 다시 준비");
            presentationRetry.setOnClickListener(ignored -> {
                PresentationGate gate = presentation;
                if (gate == null || !gate.isCurrent()) return;
                if (peerIdentityWaiting) { listener.onError("로그인 화면을 먼저 완료하거나 닫아 주세요."); return; }
                if (peerPageStale) guardNavigation(this::reload);
                else if (gate.committed) beginPresentation(gate.url, true);
                else guardNavigation(this::reload);
            });
            presentationCover.addView(presentationRetry, new LinearLayout.LayoutParams(-1, -2));
            presentationOriginal = new Button(activity);
            presentationOriginal.setText("원본 보기");
            presentationOriginal.setOnClickListener(ignored -> setOriginalMode(true));
            presentationCover.addView(presentationOriginal, new LinearLayout.LayoutParams(-1, -2));
        }
        ThemeSpec.Palette palette = theme.palette(dark);
        if (CommunityOptions.accentIndex(activity)>=0) palette=palette.withAccent(CommunityOptions.accentColor(activity,palette.accent));
        presentationCover.setBackgroundColor(palette.background);
        presentationMessage.setTextColor(palette.text);
        presentationMessage.setText(message);
        presentationRetry.setTextColor(palette.onAccent);
        presentationRetry.setBackgroundTintList(ColorStateList.valueOf(palette.accent));
        presentationOriginal.setTextColor(palette.accent);
        presentationOriginal.setBackgroundTintList(ColorStateList.valueOf(palette.surface));
        presentationOriginal.setText(peerPageStale ? "작성 내용 복사" : "원본 보기");
        presentationOriginal.setOnClickListener(ignored -> { if(peerPageStale) copyPendingText(); else setOriginalMode(true); });
        presentationRetry.setText(!peerPageStale && presentation != null && presentation.committed ? "화면 다시 준비" : "페이지 다시 열기");
        presentationRetry.setVisibility(failure ? View.VISIBLE : View.GONE);
        presentationOriginal.setVisibility(failure ? View.VISIBLE : View.GONE);
        if (presentationCover.getParent() == null) container.addView(presentationCover, new FrameLayout.LayoutParams(-1, -1));
        presentationCover.bringToFront();
        web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private void clearPresentation() {
        boolean wasCovered = presentation != null;
        if (presentation != null) presentation.suspend();
        presentation = null;
        if (presentationCover != null) container.removeView(presentationCover);
        if (web != null) web.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (wasCovered && !destroyed) recordRecentArticle();
    }

    private void failPresentation(String message) {
        PresentationGate gate = presentation;
        if (gate == null || !gate.isCurrent()) return;
        gate.suspend();
        gate.failed = true;
        showPresentationCover(true, message + " 화면을 다시 준비하거나 원본으로 볼 수 있어요.");
    }

    private void revealOriginalPresentation() {
        PresentationGate gate = presentation;
        if (gate == null) return;
        gate.suspend();
        long work = gate.work;
        Runnable reveal = () -> {
            if (gate.isCurrent() && gate.work == work && original) clearPresentation();
        };
        // Only the user's explicit original-view selection can release a cover
        // when JavaScript or the compositor no longer responds.
        handler.postDelayed(reveal, 1000);
        try {
            gate.source.evaluateJavascript("window.MoaSkin&&window.MoaSkin.disable();", ignored -> {
                if (!gate.isCurrent() || gate.work != work || !original) return;
                try {
                    gate.source.postVisualStateCallback(work, new WebView.VisualStateCallback() {
                        @Override public void onComplete(long requestId) { reveal.run(); }
                    });
                } catch (RuntimeException ignoredRenderer) { /* bounded explicit fallback remains scheduled */ }
            });
        } catch (RuntimeException ignored) { /* explicit fallback above remains bounded */ }
    }

    /** Keep native cover opaque until the intended DOM and its next drawn frame agree. */
    private final class PresentationGate {
        final WebView source = web;
        final int page = documentGeneration, account = accountGeneration;
        final String url, themeId = theme.id;
        final boolean night = dark;
        final float scale = fontScale;
        boolean committed, failed, inFlight, visualPending, applied;
        long work;
        final Runnable poll = this::probe;
        final Runnable timeout = () -> {
            if (isCurrent() && !browserPaused) failPresentation("테마 화면 준비가 지연되고 있습니다.");
        };
        PresentationGate(String url, boolean committed) { this.url = presentationUrl(url); this.committed = committed; }
        boolean isCurrent() {
            return presentation == this && !destroyed && source == web && page == documentGeneration
                    && account == accountGeneration && themeId.equals(theme.id) && night == dark && scale == fontScale;
        }
        boolean matchesDocument(String value) {
            return isCurrent() && url.equals(presentationUrl(value)) && url.equals(presentationUrl(getUrl()));
        }
        void suspend() {
            handler.removeCallbacks(poll); handler.removeCallbacks(timeout);
            work = ++presentationWork;
            inFlight = false; visualPending = false;
        }
        void resume() {
            if (!isCurrent() || browserPaused || failed) return;
            handler.removeCallbacks(timeout);
            handler.postDelayed(timeout, 12000);
            probe();
        }
        void probe() {
            if (!isCurrent() || original || browserPaused || failed || !committed || inFlight || visualPending) return;
            if (!matchesDocument(getUrl())) return;
            inFlight = true;
            long request = work = ++presentationWork;
            String sourceScript = applied ? "" : cleanupScript + "\n" + skinSource() + "\n" + communitySource(getUrl()) + "\n";
            applied = true;
            String query = sourceScript + ";JSON.stringify(window.MoaSkin&&window.MoaSkin.presentationState?window.MoaSkin.presentationState():{ready:false,reason:'missing-presentation-api'})";
            try {
                source.evaluateJavascript(query, response -> {
                    if (!isCurrent() || work != request || original || browserPaused || !matchesDocument(getUrl())) return;
                    inFlight = false;
                    boolean ready = false;
                    String reason = "";
                    try {
                        Object decoded = new org.json.JSONTokener(response == null ? "null" : response).nextValue();
                        JSONObject state = new JSONObject(decoded instanceof String ? (String) decoded : "{}");
                        ready = state.optBoolean("ready", false) && themeId.equals(state.optString("theme"))
                                && night == state.optBoolean("dark") && Math.abs(scale - state.optDouble("fontScale", 0)) < .001;
                        reason = state.optString("reason", "");
                    } catch (Exception ignored) { }
                    if (!ready) {
                        if (reason.startsWith("unsupported")) failPresentation("이 화면에 테마를 적용하지 못했습니다.");
                        else handler.postDelayed(poll, 100);
                        return;
                    }
                    visualPending = true;
                    try {
                        source.postVisualStateCallback(request, new WebView.VisualStateCallback() {
                            @Override public void onComplete(long requestId) {
                                if (requestId == request && work == request && isCurrent() && !browserPaused
                                        && !original && matchesDocument(getUrl())) clearPresentation();
                            }
                        });
                    } catch (RuntimeException unavailableRenderer) { failPresentation("화면 표시 준비를 완료하지 못했습니다."); }
                });
            } catch (RuntimeException unavailableRenderer) { failPresentation("테마 화면을 준비하지 못했습니다."); }
        }
    }


    private void openExternal(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
            } else if ("intent".equalsIgnoreCase(scheme)) {
                Intent intent = Intent.parseUri(value, Intent.URI_INTENT_SCHEME);
                intent.setComponent(null); intent.setSelector(null);
                intent.setAction(Intent.ACTION_VIEW);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setFlags(0);
                String fallback = intent.getStringExtra("browser_fallback_url");
                intent.replaceExtras((Bundle) null);
                intent.setClipData(null);
                Uri target = intent.getData();
                String targetScheme = target == null || target.getScheme() == null ? "" : target.getScheme().toLowerCase(java.util.Locale.ROOT);
                if (targetScheme.isEmpty() || Arrays.asList("file", "content", "javascript", "data").contains(targetScheme)) {
                    listener.onError("열 수 없는 링크 형식입니다."); return;
                }
                new AlertDialog.Builder(activity).setTitle("외부 앱 열기").setMessage("선택한 링크를 다른 앱에서 엽니다.")
                    .setPositiveButton("열기", (dialog, which) -> {
                        try { activity.startActivity(intent); }
                        catch (ActivityNotFoundException missing) {
                            if (fallback != null && "https".equalsIgnoreCase(Uri.parse(fallback).getScheme())) openExternal(fallback);
                            else listener.onError("이 기능에 필요한 외부 앱이 설치되어 있지 않습니다.");
                        } catch (Exception ex) { listener.onError("외부 앱을 열 수 없습니다."); }
                    }).setNegativeButton("취소", null).show();
            } else {
                listener.onError("이 링크는 원본 브라우저 또는 외부 앱에서 확인해 주세요.");
            }
        } catch (Exception exception) { listener.onError("연결할 앱을 찾지 못했습니다."); }
    }

    private static final class DownloadRequest {
        final String url, userAgent, referrer, cookie;
        DownloadRequest(String url, String userAgent, String referrer, String cookie) {
            this.url = url; this.userAgent = userAgent; this.referrer = referrer; this.cookie = cookie;
        }
    }

    private String downloadCookie(String url) {
        return UrlPolicy.isDcHttps(url) ? cookies.getCookie(url) : null;
    }

    private void invalidatePendingDownload(String notice) {
        if (pendingDownload == null) return;
        pendingDownload = null;
        downloadCancellationNotice = notice;
        // The old OS picker retains SAVE_FILE until its result is consumed.
    }

    private void requestDownload(WebView source, String url, String userAgent, String disposition, String mime) {
        if (!isLive(source) || savePickerOutstanding) return;
        if (!"https".equalsIgnoreCase(Uri.parse(url).getScheme())) { listener.onError("이 파일은 원본 브라우저에서 저장해 주세요."); return; }
        String downloadMime = mime == null ? "application/octet-stream" : mime;
        pendingDownload = new DownloadRequest(url, userAgent == null ? source.getSettings().getUserAgentString() : userAgent,
            source.getUrl(), downloadCookie(url));
        savePickerOutstanding = true;
        downloadCancellationNotice = null;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(downloadMime)
            .putExtra(Intent.EXTRA_TITLE, URLUtil.guessFileName(url, disposition, downloadMime));
        try { activity.startActivityForResult(intent, SAVE_FILE); }
        catch (Exception ex) { pendingDownload = null; savePickerOutstanding = false; listener.onError("파일 저장 위치를 선택할 수 없습니다."); }
    }
    private void saveDownload(DownloadRequest request, Uri destination) {
        final String originalUrl = request.url, cookie = request.cookie, referrer = request.referrer, userAgent = request.userAgent;
        Toast.makeText(activity, "파일을 저장하고 있습니다.", Toast.LENGTH_SHORT).show();
        downloadExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL target = new URL(originalUrl);
                for (int redirects = 0; redirects < 6; redirects++) {
                    if (!"https".equalsIgnoreCase(target.getProtocol())) throw new IllegalArgumentException("HTTPS required");
                    connection = (HttpURLConnection) target.openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
                    connection.setRequestProperty("User-Agent", userAgent);
                    if (UrlPolicy.isDcHttps(target.toString())) {
                        if (target.getHost().equalsIgnoreCase(new URL(originalUrl).getHost()) && cookie != null) connection.setRequestProperty("Cookie", cookie);
                        if (UrlPolicy.isDcHttps(referrer)) connection.setRequestProperty("Referer", referrer);
                    }
                    int response = connection.getResponseCode();
                    if (response >= 300 && response < 400) {
                        String next = connection.getHeaderField("Location");
                        connection.disconnect(); connection = null;
                        if (next == null) throw new IllegalStateException("Missing redirect");
                        target = new URL(target, next); continue;
                    }
                    if (response < 200 || response >= 300) throw new IllegalStateException("Download failed");
                    try (InputStream input = connection.getInputStream(); OutputStream output = activity.getContentResolver().openOutputStream(destination)) {
                        if (output == null) throw new IllegalStateException("No destination");
                        byte[] buffer = new byte[32768]; int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (destroyed || Thread.currentThread().isInterrupted()) throw new java.io.IOException("Download cancelled");
                            output.write(buffer, 0, count);
                        }
                    }
                    handler.post(() -> { if (!destroyed) Toast.makeText(activity, "파일을 저장했습니다.", Toast.LENGTH_LONG).show(); });
                    return;
                }
                throw new IllegalStateException("Too many redirects");
            } catch (Exception exception) {
                handler.post(() -> { if (!destroyed) listener.onError("파일 저장을 완료하지 못했습니다. 원본 보기에서 다시 시도해 주세요."); });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private final class PageContext {
        final WebView source = web;
        final int page = documentGeneration, account = accountGeneration;
        final String url = getUrl(), owner = draftOwner;
        boolean isCurrent() {
            return !destroyed && source == web && page == documentGeneration && account == accountGeneration
                && owner.equals(draftOwner) && documentKey(url).equals(documentKey(getUrl()));
        }
    }

    private String documentKey(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getScheme() + "://" + uri.getAuthority() + uri.getRawPath();
        } catch (RuntimeException error) { return ""; }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte part : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void updateDraftOwner(String owner) {
        if (draftOwner.equals(owner)) return;
        cancelNavigationGuard();
        invalidatePendingDownload("계정 정보가 변경되어 저장을 취소했습니다. 파일을 다시 선택해 주세요.");
        draftOwner = owner;
        accountGeneration++;
        restartCoveredPresentation();
        lastDraftPromptGeneration = -1;
        dismissDraftDialog();
        if (!owner.isEmpty() && !destroyed && !loading) offerDraftRestore(getUrl());
    }

    private void dismissDraftDialog() {
        if (draftDialog != null) { draftDialog.dismiss(); draftDialog = null; }
    }

    /** Native navigation checks the actual editor; opening a writer once is not a dirty flag. */
    public void guardNavigation(Runnable action) {
        guardNavigation(action, null);
    }

    public void guardNavigation(Runnable action, Runnable stay) {
        if (destroyed || action == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (navigationCheck != null) return;
        if (!UrlPolicy.canTheme(getUrl())) { action.run(); return; }
        NavigationCheck check = new NavigationCheck(action, stay);
        navigationCheck = check;
        handler.postDelayed(check.timeout, 1500);
        try {
            check.context.source.evaluateJavascript(draftScript + ";JSON.stringify(window.MoaDrafts?window.MoaDrafts.navigationState("
                    + JSONObject.quote(check.context.url) + "):{state:'unknown'})", result -> {
                String state = "unknown";
                try {
                    Object decoded = new org.json.JSONTokener(result == null ? "null" : result).nextValue();
                    state = new JSONObject(decoded instanceof String ? (String) decoded : "{}").optString("state", "unknown");
                } catch (Exception ignored) { }
                check.answer(state);
            });
        } catch (RuntimeException unavailableRenderer) { check.answer("unknown"); }
    }

    public void cancelNavigationGuard() {
        NavigationCheck check = navigationCheck;
        navigationCheck = null;
        if (check == null) return;
        handler.removeCallbacks(check.timeout);
        if (check.dialog != null) { check.dialog.dismiss(); check.dialog = null; }
    }

    private final class NavigationCheck {
        final PageContext context = new PageContext();
        final Runnable action;
        final Runnable stay;
        final Runnable timeout = () -> answer("unknown");
        boolean answered;
        AlertDialog dialog;
        NavigationCheck(Runnable action, Runnable stay) { this.action = action; this.stay = stay; }
        boolean isCurrent() {
            return navigationCheck == this && context.isCurrent() && !activity.isFinishing() && !activity.isDestroyed();
        }
        void answer(String state) {
            if (navigationCheck != this || answered) return;
            if (!isCurrent()) { cancelNavigationGuard(); return; }
            answered = true;
            handler.removeCallbacks(timeout);
            if ("clean".equals(state)) { approve(); return; }
            boolean dirty = "dirty".equals(state);
            dialog = new AlertDialog.Builder(activity, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                    : android.R.style.Theme_Material_Light_Dialog_Alert)
                    .setTitle(dirty ? "작성 중인 내용을 두고 이동할까요?" : "현재 페이지에서 이동할까요?")
                    .setMessage(dirty ? "아직 등록하지 않은 글·댓글 또는 첨부파일이 있습니다. 이동하면 작성 내용이 사라질 수 있어요."
                            : "현재 작성 상태를 확인하지 못했습니다. 입력하거나 첨부한 내용이 있다면 이동할 때 사라질 수 있어요.")
                    .setNegativeButton(dirty ? "계속 작성" : "머무르기", (d, w) -> {
                        if (!isCurrent()) return;
                        cancelNavigationGuard();
                        if (stay != null) stay.run();
                    })
                    .setPositiveButton("이동", (d, w) -> approve()).create();
            dialog.setOnDismissListener(ignored -> {
                if (navigationCheck == this) cancelNavigationGuard();
            });
            dialog.show();
            int accent = theme.palette(dark).accent;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(accent);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(accent);
        }
        void approve() {
            if (!isCurrent()) {
                if (navigationCheck == this) cancelNavigationGuard();
                return;
            }
            boolean explicitlyApproved = dialog != null;
            cancelNavigationGuard();
            approvedNavigationContext = explicitlyApproved ? context : null;
            try { action.run(); }
            finally { approvedNavigationContext = null; }
        }
    }

    /** Text drafts are scoped to the verified account and document, never passwords/tokens. */
    public void snapshotDraft(Runnable after) {
        if (destroyed) return;
        if (peerPageStale) { if(after!=null)after.run(); return; }
        if (!UrlPolicy.canTheme(getUrl()) || draftOwner.isEmpty()) { if (after != null) after.run(); return; }
        PageContext context = new PageContext();
        boolean allowTimedNavigation = after != null && approvedNavigationContext != null && approvedNavigationContext.isCurrent();
        boolean[] completed = {false};
        Runnable proceed = () -> {
            if (completed[0]) return;
            completed[0] = true;
            if (after != null && context.isCurrent()) after.run();
        };
        // Only an explicit discard/move confirmation may proceed when the renderer
        // stops answering. Ordinary background snapshots never get that permission.
        if (allowTimedNavigation) handler.postDelayed(proceed, 1000);
        String capture = draftScript + ";JSON.stringify(window.MoaDrafts?window.MoaDrafts.capture(" + JSONObject.quote(context.url)
            + "):{eligible:false,fields:[]})";
        context.source.evaluateJavascript(capture, result -> {
            if (completed[0]) return;
            handler.removeCallbacks(proceed);
            if (!context.isCurrent()) { completed[0] = true; return; }
            try {
                Object decoded = new org.json.JSONTokener(result).nextValue();
                JSONObject snapshot = new JSONObject(decoded instanceof String ? (String) decoded : "{}");
                JSONArray fields = snapshot.optJSONArray("fields");
                String key = draftKey(context.url, context.owner);
                if (snapshot.optBoolean("eligible", false) && fields != null) {
                    if (fields.length() > 0) drafts.edit().putString(key, fields.toString()).commit();
                    else drafts.edit().remove(key).commit();
                }
            } catch (Exception ignored) { }
            proceed.run();
        });
    }
    private void offerDraftRestore(String url) {
        if (destroyed || peerPageStale || draftOwner.isEmpty() || !UrlPolicy.canTheme(url) || lastDraftPromptGeneration == documentGeneration
                || !documentKey(url).equals(documentKey(getUrl()))) return;
        PageContext context = new PageContext();
        String stored = drafts.getString(draftKey(url, context.owner), null);
        if (stored == null) return;
        try { new JSONArray(stored); } catch (Exception invalidDraft) { return; }
        context.source.evaluateJavascript(draftScript + ";!!(window.MoaDrafts&&window.MoaDrafts.canRestore(" + stored + "," + JSONObject.quote(context.url) + "))", exists -> {
            if (!"true".equals(exists) || !context.isCurrent() || activity.isFinishing() || activity.isDestroyed()) return;
            lastDraftPromptGeneration = documentGeneration;
            dismissDraftDialog();
            draftDialog = new AlertDialog.Builder(activity).setTitle("작성 중인 내용 복원")
                .setMessage("현재 계정으로 작성하던 텍스트가 있습니다. 첨부파일과 답글 대상은 다시 확인해 주세요.")
                .setPositiveButton("복원", (dialog, which) -> {
                    if (!context.isCurrent()) return;
                    context.source.evaluateJavascript(draftScript + ";window.MoaDrafts&&window.MoaDrafts.restore(" + stored + "," + JSONObject.quote(context.url) + ")", null);
                }).setNegativeButton("건너뛰기", null).show();
        });
    }
    private String draftKey(String url, String owner) { return (AccountProfiles.DEFAULT.equals(profileName) ? "v2:" : "v3:" + profileName + ":") + owner + ":" + sha256(documentKey(url)); }
    private String readAsset(String file) throws Exception {
        try (InputStream input = activity.getAssets().open(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    public void onSaveInstanceState(Bundle state) {
        web.saveState(state); snapshotDraft(null);
        state.putString("browser.presentationUrl", getUrl());
        state.putString("browser.profile", profileName);
        state.putBoolean("browser.filePickerOutstanding", filePickerOutstanding);
        state.putBoolean("browser.mediaPickerOutstanding", mediaPickerOutstanding);
        state.putBoolean("browser.permissionAwaitingSystem", permissionAwaitingSystem);
        state.putBoolean("browser.savePickerOutstanding", savePickerOutstanding);
    }
    public boolean onRestoreInstanceState(Bundle state) {
        if (state == null) return false;
        if (!profileName.equals(state.getString("browser.profile", AccountProfiles.DEFAULT))) return false;
        // Callback objects cannot survive Activity recreation. Fresh request
        // codes keep an old OS result away from this new pane's later picker.
        boolean interruptedPicker = state.getBoolean("browser.filePickerOutstanding", false)
                || state.getBoolean("browser.mediaPickerOutstanding", false)
                || state.getBoolean("browser.savePickerOutstanding", false) || state.getString("browser.pendingDownload", null) != null;
        filePickerOutstanding = false; mediaPickerOutstanding = false; permissionAwaitingSystem = false; savePickerOutstanding = false;
        if (interruptedPicker) listener.onError("화면이 다시 생성되어 이전 첨부·저장 선택을 취소했습니다. 다시 선택해 주세요.");
        String restoredUrl = state.getString("browser.presentationUrl", "");
        prepareNavigation(restoredUrl);
        boolean restored = web.restoreState(state) != null;
        if (!restored) clearPresentation();
        return restored;
    }
    public void onPause() {
        if (!destroyed) {
            browserPaused = true;
            if (presentation != null) presentation.suspend();
            cancelNavigationGuard();
            hideFullscreen(); snapshotDraft(null); cookies.flush(); web.onPause();
            for (WebView popup : new ArrayList<>(popups.keySet())) popup.onPause();
        }
    }
    public void onResume() {
        if (!destroyed) {
            browserPaused = false;
            web.onResume();
            if (presentation != null) presentation.resume();
            for (WebView popup : new ArrayList<>(popups.keySet())) popup.onResume();
        }
    }
    public void destroy() {
        if (destroyed) return;
        cancelNavigationGuard();
        clearPresentation();
        destroyed = true; handler.removeCallbacksAndMessages(null);
        for (SiteDialog request : new ArrayList<>(siteDialogs.values())) request.finish(false);
        pendingDownload = null;
        hideFullscreen();
        documentGeneration++; accountGeneration++;
        dismissDraftDialog();
        favoritesGeneration++;
        if (favoritesWeb != null) { container.removeView(favoritesWeb); favoritesWeb.destroy(); favoritesWeb = null; }
        if (pendingPermission != null) denyPermissionIfCurrent(pendingPermission);
        finishFileSelection(null);
        for (Dialog dialog : new ArrayList<>(popups.values())) dialog.dismiss();
        popups.clear();
        if (documentScript != null) documentScript.remove();
        web.stopLoading(); container.removeView(web); web.destroy();
        downloadExecutor.shutdownNow();
    }
}
