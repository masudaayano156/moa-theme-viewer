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
    }
    public static final class FavoriteEntry {
        public final String name, url;
        public FavoriteEntry(String name, String url) { this.name = name; this.url = url; }
    }
    private static final int FILE_PICK = 4101, SAVE_FILE = 4102, WEB_PERMISSION = 4103;
    private final Activity activity;
    private final Listener listener;
    private final FrameLayout container;
    private WebView web;
    private WebView favoritesWeb;
    private final SharedPreferences drafts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private final Map<WebView, Dialog> popups = new HashMap<>();
    private final Map<WebView, SiteDialog> siteDialogs = new HashMap<>();
    private ThemeSpec theme = ThemeSpec.MAIL;
    private boolean dark, original, destroyed, loading;
    private boolean openFavoritesPending, authNavigationPending;
    private int favoritesGeneration, documentGeneration, accountGeneration, authenticationGeneration, navigationIntentGeneration;
    private float fontScale = 1f;
    private String css = "", script = "", favoritesScript = "", draftScript = "", recoveryUrl = "", draftOwner = "";
    private int lastDraftPromptGeneration = -1;
    private AlertDialog draftDialog, permissionDialog;
    private ScriptHandler documentScript;
    private ValueCallback<Uri[]> fileCallback;
    private WebView fileOwner, permissionOwner;
    private boolean filePickerOutstanding, fileMultiple, permissionAwaitingSystem;
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
        drafts = activity.getSharedPreferences("moa_editor_drafts", Activity.MODE_PRIVATE);
        container = new FrameLayout(activity);
        web = new WebView(activity);
        container.addView(web, new FrameLayout.LayoutParams(-1, -1));
        try {
            css = readAsset("web/skin.css");
            script = readAsset("web/skin.js");
            favoritesScript = readAsset("web/account-favorites.js");
            draftScript = readAsset("web/drafts.js");
        } catch (Exception exception) {
            listener.onError("테마 파일을 읽지 못했습니다. 원본 화면으로 이용할 수 있습니다.");
        }
        configure(web);
        CookieManager.getInstance().setAcceptCookie(true);
        refreshDocumentScript();
    }

    public View getView() { return container; }
    public void openAccountFavorites() {
        navigationIntentGeneration++;
        openFavoritesPending = true;
        openInternal("https://m.dcinside.com/aside");
    }

    public void refreshAccountFavorites() {
        if (destroyed) return;
        int generation = ++favoritesGeneration;
        listener.onAccountFavorites("loading", Collections.emptyList());
        if (favoritesWeb != null) { container.removeView(favoritesWeb); favoritesWeb.destroy(); }
        favoritesWeb = new WebView(activity);
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
        if (UrlPolicy.isDcHttps(url)) {
            snapshotDraft(() -> web.loadUrl(url));
        } else {
            openExternal(url);
        }
    }
    public boolean canGoBack() { return !destroyed && (fullscreenView != null || web.canGoBack()); }
    public void goBack() {
        if (fullscreenView != null) hideFullscreen();
        else if (!destroyed && web.canGoBack()) { cancelFavoritesNavigation(); snapshotDraft(() -> web.goBack()); }
    }
    public boolean canGoForward() { return !destroyed && web.canGoForward(); }
    public void goForward() { if (canGoForward()) { cancelFavoritesNavigation(); snapshotDraft(() -> web.goForward()); } }
    public void reload() { cancelFavoritesNavigation(); if (!recoveryUrl.isEmpty()) openInternal(recoveryUrl); else snapshotDraft(() -> web.reload()); }

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
        this.theme = theme == null ? ThemeSpec.MAIL : theme;
        this.dark = dark;
        container.setBackgroundColor(this.theme.palette(dark).background);
        web.setBackgroundColor(this.theme.palette(dark).background);
        refreshDocumentScript();
        applyTheme();
    }
    public void setFontScale(float scale) {
        fontScale = Math.max(.85f, Math.min(1.4f, scale));
        refreshDocumentScript();
        applyTheme();
    }
    public void setOriginalMode(boolean enabled) {
        original = enabled;
        refreshDocumentScript();
        applyTheme();
    }

    public void openComposer() {
        cancelFavoritesNavigation();
        if (!UrlPolicy.isDcHttps(getUrl())) {
            listener.onError("먼저 글을 작성할 곳을 열어 주세요."); return;
        }
        // Use the site's own visible action, preserving server routing and permissions.
        String find = "(()=>{const es=[...document.querySelectorAll('a,button,input[type=button]')];"
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, false);
        target.setWebViewClient(new Client());
        target.setWebChromeClient(new Chrome(target));
        target.setDownloadListener((url, userAgent, disposition, mime, size) -> requestDownload(target, url, userAgent, disposition, mime));
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!isLive(view)) return true;
            if (!request.isForMainFrame()) return false;
            String url = request.getUrl().toString();
            if (view == web && request.hasGesture() && !request.isRedirect()) cancelFavoritesNavigation();
            if (UrlPolicy.isDcHttps(url)) return false;
            Dialog popup = popups.get(view);
            if (popup != null) handler.post(popup::dismiss);
            openExternal(url); return true;
        }
        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!isLive(view)) return;
            cancelPageRequests(view);
            if (UrlPolicy.isAuthenticationPage(url)) {
                invalidatePendingDownload("로그인 상태가 변경되어 저장을 취소했습니다. 파일을 다시 선택해 주세요.");
                authenticationGeneration++;
                authNavigationPending = true;
                updateDraftOwner("");
                accountGeneration++;
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
            if (!isFavoritesPage(url) && !UrlPolicy.isAuthenticationPage(url)) openFavoritesPending = false;
            documentGeneration++;
            dismissDraftDialog();
            recoveryUrl = "";
            loading = true;
            listener.onPageState(view.getTitle(), url, true);
        }
        @Override public void onPageFinished(WebView view, String url) {
            if (!isLive(view)) return;
            if (view == web && !documentKey(url).equals(documentKey(getUrl()))) return;
            CookieManager.getInstance().flush();
            if (view != web) {
                if (authNavigationPending && !UrlPolicy.isAuthenticationPage(url)) { authNavigationPending = false; refreshAccountFavorites(); }
                return;
            }
            loading = false;
            applyTheme();
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
                authNavigationPending = false;
                refreshAccountFavorites();
            }
        }
        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            if (view == web) listener.onPageState(view.getTitle(), url, loading);
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (isLive(view) && request.isForMainFrame() && view == web) {
                loading = false;
                listener.onError("페이지를 열지 못했습니다. 인터넷 연결을 확인한 뒤 새로고침해 주세요.");
                listener.onPageState(view.getTitle(), view.getUrl(), false);
            }
        }
        @Override public void onReceivedSslError(WebView view, SslErrorHandler sslHandler, SslError error) {
            sslHandler.cancel();
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
            web = new WebView(activity);
            configure(web);
            container.addView(web, new FrameLayout.LayoutParams(-1, -1));
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
            try { activity.startActivityForResult(intent, FILE_PICK); }
            catch (RuntimeException exception) { finishFileSelection(null); listener.onError("파일 선택 앱을 찾지 못했습니다."); }
            return true;
        }
        @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            if (!isUserGesture || view != owner || !isLive(owner) || resultMsg == null
                    || !(resultMsg.obj instanceof WebView.WebViewTransport)) return false;
            WebView child = new WebView(activity);
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
        fileCallback = null; fileOwner = null; filePickerOutstanding = false;
        if (callback != null) callback.onReceiveValue(selected);
    }

    private void denyPermissionIfCurrent(PermissionRequest request) {
        if (pendingPermission != request) return;
        pendingPermission = null; permissionOwner = null;
        request.deny();
        if (permissionDialog != null) { permissionDialog.dismiss(); permissionDialog = null; }
    }

    private void cancelPageRequests(WebView view) {
        SiteDialog siteDialog = siteDialogs.get(view);
        if (siteDialog != null) siteDialog.finish(false);
        if (fullscreenOwner == view) hideFullscreen();
        if (fileOwner == view) {
            ValueCallback<Uri[]> callback = fileCallback;
            fileCallback = null; fileOwner = null;
            // The old OS picker still owns FILE_PICK until its result arrives.
            if (callback != null) callback.onReceiveValue(null);
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

    private String skinSource() {
        try {
            JSONObject config = new JSONObject().put("theme", theme.id).put("dark", dark)
                .put("fontScale", fontScale).put("groupNoun", theme.groupNoun).put("displayName", theme.displayName);
            return "if(window.top===window){window.__MOA_SKIN_CSS=" + JSONObject.quote(css) + ";\n" + script
                + "\nwindow.MoaSkin&&window.MoaSkin.apply(" + config + ");}";
        } catch (Exception ex) { return ""; }
    }
    private void refreshDocumentScript() {
        if (destroyed) return;
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
            if (documentScript != null) { documentScript.remove(); documentScript = null; }
            if (!original && !script.isEmpty()) {
                documentScript = WebViewCompat.addDocumentStartJavaScript(web, skinSource(), Collections.singleton("https://m.dcinside.com"));
            }
        } catch (RuntimeException | LinkageError unavailableProvider) {
            // Older or unavailable providers still receive the onPageFinished skin.
            documentScript = null;
        }
    }
    private void applyTheme() {
        if (destroyed) return;
        if (original || !UrlPolicy.canTheme(getUrl())) {
            web.evaluateJavascript("window.MoaSkin&&window.MoaSkin.disable();", null);
        } else if (!script.isEmpty()) web.evaluateJavascript(skinSource(), null);
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
        return UrlPolicy.isDcHttps(url) ? CookieManager.getInstance().getCookie(url) : null;
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
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
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
        invalidatePendingDownload("계정 정보가 변경되어 저장을 취소했습니다. 파일을 다시 선택해 주세요.");
        draftOwner = owner;
        accountGeneration++;
        lastDraftPromptGeneration = -1;
        dismissDraftDialog();
        if (!owner.isEmpty() && !destroyed && !loading) offerDraftRestore(getUrl());
    }

    private void dismissDraftDialog() {
        if (draftDialog != null) { draftDialog.dismiss(); draftDialog = null; }
    }

    /** Text drafts are scoped to the verified account and document, never passwords/tokens. */
    public void snapshotDraft(Runnable after) {
        if (destroyed) return;
        if (!UrlPolicy.canTheme(getUrl()) || draftOwner.isEmpty()) { if (after != null) after.run(); return; }
        PageContext context = new PageContext();
        String capture = draftScript + ";JSON.stringify(window.MoaDrafts?window.MoaDrafts.capture(" + JSONObject.quote(context.url)
            + "):{eligible:false,fields:[]})";
        context.source.evaluateJavascript(capture, result -> {
            if (!context.isCurrent()) return;
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
            if (after != null && context.isCurrent()) after.run();
        });
    }
    private void offerDraftRestore(String url) {
        if (destroyed || draftOwner.isEmpty() || !UrlPolicy.canTheme(url) || lastDraftPromptGeneration == documentGeneration
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
    private String draftKey(String url, String owner) { return "v2:" + owner + ":" + sha256(documentKey(url)); }
    private String readAsset(String file) throws Exception {
        try (InputStream input = activity.getAssets().open(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    public void onSaveInstanceState(Bundle state) {
        web.saveState(state); snapshotDraft(null);
        state.putBoolean("browser.filePickerOutstanding", filePickerOutstanding);
        state.putBoolean("browser.permissionAwaitingSystem", permissionAwaitingSystem);
        state.putBoolean("browser.savePickerOutstanding", savePickerOutstanding);
    }
    public boolean onRestoreInstanceState(Bundle state) {
        if (state == null) return false;
        filePickerOutstanding = state.getBoolean("browser.filePickerOutstanding", false);
        permissionAwaitingSystem = state.getBoolean("browser.permissionAwaitingSystem", false);
        savePickerOutstanding = state.getBoolean("browser.savePickerOutstanding", false) || state.getString("browser.pendingDownload", null) != null;
        if (savePickerOutstanding) downloadCancellationNotice = "앱 화면이 다시 생성되어 저장을 취소했습니다. 파일을 다시 선택해 주세요.";
        return web.restoreState(state) != null;
    }
    public void onPause() {
        if (!destroyed) {
            hideFullscreen(); snapshotDraft(null); CookieManager.getInstance().flush(); web.onPause();
            for (WebView popup : new ArrayList<>(popups.keySet())) popup.onPause();
        }
    }
    public void onResume() {
        if (!destroyed) {
            web.onResume();
            for (WebView popup : new ArrayList<>(popups.keySet())) popup.onResume();
        }
    }
    public void destroy() {
        if (destroyed) return;
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
        downloadExecutor.shutdown();
    }
}
