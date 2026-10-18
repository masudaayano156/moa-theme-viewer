package app.moa.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Native shell. The live WebView is kept intact when changing theme or opening settings. */
public final class MainActivity extends Activity implements BrowserController.Listener {
    private static final String LOGIN_URL = "https://msign.dcinside.com/login?r_url=https%3A%2F%2Fm.dcinside.com%2Faside";
    private SharedPreferences preferences;
    private ThemeSpec theme;
    private ThemeSpec.Palette colors;
    private boolean dark;
    private boolean matchLauncher;
    private float fontScale;
    private BrowserController browser;
    private BrowserController detailBrowser;
    private LinearLayout browserPanes;
    private boolean detailOpen, activityResumed, primaryResumed = true, detailResumed;
    private boolean primaryLoading, detailLoading;
    private CommunityToolsDialog communityDialog;
    private CommentWatchDialog commentWatchDialog;
    private long accountSwitchGeneration;
    private AlertDialog userActionDialog;
    private AlertDialog notificationConsentDialog;
    private LinearLayout root;
    private FrameLayout content;
    private View browserView;
    private ScrollView homeView;
    private ProgressBar progress;
    private LinearLayout errorBanner;
    private TextView errorText;
    private LinearLayout bottomNavigation;
    private View composeFloatingAction;
    private boolean browserVisible;
    private boolean loading;
    private String latestError;
    private String pageTitle = "";
    private String pageUrl = "";
    private String pendingComposerTarget = "";
    private final ArrayList<Favorite> favorites = new ArrayList<>();
    private final ArrayList<Favorite> accountFavorites = new ArrayList<>();
    private String accountState = "loading";
    private AlertDialog adaptiveDialog;
    private AlertDialog mailDrawer;
    private Runnable rebuildDialog;
    private boolean launcherChangePending;
    private boolean pendingLauncherValue;
    private long launcherChangeGeneration;
    private Runnable launcherTimeout;
    private static final int PUSH_PERMISSION_REQUEST = 4201;
    private AlertDialog pushDialog;
    private boolean pushBusy;
    private long pushUiGeneration;
    private String pushStatus = "";
    private Runnable pushTimeout;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences("moa_preferences", MODE_PRIVATE);
        theme = ThemeSpec.fromId(preferences.getString("theme", "mail"));
        dark = preferences.getBoolean("dark", false);
        fontScale = Math.max(.85f, Math.min(1.4f, preferences.getFloat("fontScale", 1.0f)));
        matchLauncher = preferences.getBoolean("matchLauncher", true);
        // A launcher may restart the process while its alias is changing. The
        // durable preferences are authoritative if that change was interrupted.
        if (!LauncherIdentity.setTheme(this, theme, matchLauncher)) {
            latestError = LauncherIdentity.getLastError();
        }
        colors = selectedPalette();
        loadFavorites();
        CommunityStore.migrateLegacySavedArticles(this);
        NotificationConsent.migrateEnabledConnection(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            } else {
                root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        content = new FrameLayout(this);
        browser = new BrowserController(this, this);
        browserView = browser.getView();
        browserPanes = horizontal();
        browserPanes.setTag("community-browser-panes");
        browserPanes.addView(browserView, new LinearLayout.LayoutParams(-1, -1));
        content.addView(browserPanes, new FrameLayout.LayoutParams(-1, -1));
        content.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) applyPaneLayout();
        });
        applyBrowserThemes();
        setContentView(root);
        buildChrome();
        boolean restored = savedInstanceState != null && browser.onRestoreInstanceState(savedInstanceState);
        if (restored) {
            browser.setOriginalMode(savedInstanceState.getBoolean("ui.originalMode", false));
            pageUrl = browser.getUrl() == null ? "" : browser.getUrl();
            Bundle detailState = savedInstanceState.getBundle("ui.detailState");
            if (detailState != null && savedInstanceState.getBoolean("ui.detailOpen", false)) {
                createDetailBrowser();
                if (detailBrowser.onRestoreInstanceState(detailState)) {
                    detailOpen = true;
                    detailBrowser.setOriginalMode(detailState.getBoolean("ui.originalMode", false));
                } else discardDetailBrowser();
            }
            setBrowserVisible(savedInstanceState.getBoolean("ui.browserVisible", false));
        } else {
            setBrowserVisible(false);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        browser.refreshAccountFavorites();
        if (AccountProfiles.activeId(this).equals(preferences.getString("profile.loginPending", ""))) {
            preferences.edit().remove("profile.loginPending").apply();
            setBrowserVisible(true);
            browser.open(LOGIN_URL);
        }
        handlePushIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePushIntent(intent);
    }

    private void handlePushIntent(Intent intent) {
        String target = PushNotifications.consumeTarget(intent);
        if (target != null && browser != null) navigate(target);
    }

    private void buildChrome() {
        colors = selectedPalette();
        // Retain the live content subtree in its parent. Detaching it clears
        // WebView focus (and can close the IME) even if the same object is added
        // back immediately afterwards.
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            if (root.getChildAt(i) != content) root.removeViewAt(i);
        }
        root.setBackgroundColor(colors.background);
        root.addView(buildHeader(), 0);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(colors.accent));
        root.addView(progress, 1, new LinearLayout.LayoutParams(-1, dp(2)));
        progress.setVisibility(loading && browserVisible ? View.VISIBLE : View.INVISIBLE);
        errorBanner = new LinearLayout(this);
        errorBanner.setGravity(Gravity.CENTER_VERTICAL);
        errorBanner.setPadding(dp(18), dp(10), dp(8), dp(10));
        errorBanner.setBackgroundColor(dark ? Color.rgb(65, 47, 27) : Color.rgb(255, 245, 224));
        errorText = text(latestError == null ? "" : latestError, 12, colors.text, false);
        errorBanner.addView(errorText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dismiss = plainAction("×", "안내 닫기", v -> {
            latestError = null;
            errorBanner.setVisibility(View.GONE);
        });
        errorBanner.addView(dismiss, new LinearLayout.LayoutParams(dp(48), dp(48)));
        errorBanner.setVisibility(latestError != null && browserVisible ? View.VISIBLE : View.GONE);
        root.addView(errorBanner, 2, new LinearLayout.LayoutParams(-1, -2));
        ViewGroup currentParent = (ViewGroup) content.getParent();
        if (currentParent != root) {
            if (currentParent != null) currentParent.removeView(content);
            root.addView(content, 3, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        root.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        root.addView(buildNavigation());
        rebuildHome();
        updateHeader();
        updateSystemColors();
    }

    private ThemeSpec.Palette selectedPalette() {
        ThemeSpec.Palette base = theme.palette(dark);
        return CommunityOptions.accentIndex(this) < 0 ? base : base.withAccent(CommunityOptions.accentColor(this, base.accent));
    }

    private BrowserController currentBrowser() { return detailOpen && detailBrowser != null ? detailBrowser : browser; }

    private void applyBrowserThemes() {
        if (browser != null) { browser.setTheme(theme, dark); browser.setFontScale(fontScale); }
        if (detailBrowser != null) { detailBrowser.setTheme(theme, dark); detailBrowser.setFontScale(fontScale); }
    }

    private boolean wideSplitEnabled() {
        int width = content != null && content.getWidth() > 0
                ? Math.round(content.getWidth() / getResources().getDisplayMetrics().density)
                : getResources().getConfiguration().screenWidthDp;
        return CommunityOptions.splitEnabled(this) && width >= 720;
    }

    private void applyPaneLayout() {
        if (browserPanes == null || browserView == null) return;
        boolean split = detailOpen && detailBrowser != null && wideSplitEnabled();
        boolean primaryVisible = browserVisible && (!detailOpen || split);
        boolean detailVisible = browserVisible && detailOpen && detailBrowser != null;
        browserPanes.setVisibility(browserVisible ? View.VISIBLE : View.GONE);
        browserView.setVisibility(primaryVisible ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams left = (LinearLayout.LayoutParams) browserView.getLayoutParams();
        int leftWidth = split ? 0 : -1;
        float leftWeight = split ? .45f : 0f;
        if (left.width != leftWidth || left.weight != leftWeight) {
            left.width = leftWidth; left.height = -1; left.weight = leftWeight; browserView.setLayoutParams(left);
        }
        if (detailBrowser != null) {
            View detail = detailBrowser.getView(); detail.setVisibility(detailVisible ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams right = (LinearLayout.LayoutParams) detail.getLayoutParams();
            int rightWidth = split ? 0 : -1; float rightWeight = split ? .55f : 0f;
            if (right.width != rightWidth || right.weight != rightWeight) {
                right.width = rightWidth; right.height = -1; right.weight = rightWeight; detail.setLayoutParams(right);
            }
        }
        boolean resumePrimary = primaryVisible && activityResumed;
        if (primaryResumed != resumePrimary) {
            primaryResumed = resumePrimary; if (resumePrimary) browser.onResume(); else browser.onPause();
        }
        boolean resumeDetail = detailVisible && activityResumed;
        if (detailBrowser != null && detailResumed != resumeDetail) {
            detailResumed = resumeDetail; if (resumeDetail) detailBrowser.onResume(); else detailBrowser.onPause();
        }
    }

    private void createDetailBrowser() {
        if (detailBrowser != null) return;
        final BrowserController[] owner = new BrowserController[1];
        BrowserController detail = new BrowserController(this, new BrowserController.Listener() {
            @Override public void onPageState(String title, String url, boolean busy) {
                if (owner[0] != null) onPanePageState(owner[0], title, url, busy);
            }
            @Override public void onError(String message) {
                if (owner[0] == detailBrowser) onPaneError(owner[0], message);
            }
            @Override public void onUserActions(String nickname, String id, String ip) {
                if (owner[0] == detailBrowser) MainActivity.this.onUserActions(nickname, id, ip);
            }
            @Override public void onOwnArticlePublished(String url) {
                if (owner[0] == detailBrowser) MainActivity.this.onOwnArticlePublished(url);
            }
            @Override public void onOwnCommentPublished(String url) {
                if (owner[0] == detailBrowser) MainActivity.this.onOwnCommentPublished(url);
            }
            @Override public void onOwnCommentRestored(String url, long publishedAtMillis) {
                if (owner[0] == detailBrowser) MainActivity.this.onOwnCommentRestored(url, publishedAtMillis);
            }
            @Override public void onAuthenticationChanged() { forwardAuthentication(owner[0], true); }
            @Override public void onAuthenticationSettled() { forwardAuthentication(owner[0], false); }
        });
        owner[0] = detail; detailBrowser = detail; detailResumed = true;
        detail.getView().setTag("community-detail-pane");
        browserPanes.addView(detail.getView(), new LinearLayout.LayoutParams(-1, -1));
        detail.setTheme(theme, dark); detail.setFontScale(fontScale);
        detail.refreshAccountFavorites();
    }

    @Override public void onAuthenticationChanged() { forwardAuthentication(browser, true); }
    @Override public void onAuthenticationSettled() { forwardAuthentication(browser, false); }

    private void forwardAuthentication(BrowserController source, boolean starting) {
        if (source == null || isDestroyed()) return;
        BrowserController peer = source == browser ? detailBrowser : source == detailBrowser ? browser : null;
        if (peer == null || !source.profileName().equals(peer.profileName())) return;
        if (starting) peer.beginPeerAuthentication(); else peer.finishPeerAuthentication();
    }

    private void discardDetailBrowser() {
        BrowserController closing = detailBrowser;
        detailBrowser = null; detailOpen = false; detailResumed = false; detailLoading = false;
        if (closing != null) { browserPanes.removeView(closing.getView()); closing.destroy(); }
        applyPaneLayout();
        if (browser != null) onPanePageState(browser, browser.getTitle(), browser.getUrl(), primaryLoading);
    }

    @Override public boolean onOpenArticle(String url) {
        String target = CommunityStore.canonicalArticleUrl(url);
        if (!browserVisible || !wideSplitEnabled() || target == null || !isFloatingComposeList(browser.getUrl())) return false;
        Runnable open = () -> {
            createDetailBrowser(); detailOpen = true; latestError = null;
            applyPaneLayout(); detailBrowser.setOriginalMode(false); detailBrowser.open(target);
        };
        if (detailOpen && detailBrowser != null) {
            BrowserController detail = detailBrowser;
            detail.guardNavigation(() -> detail.snapshotDraft(open), () -> setBrowserVisible(true));
        } else open.run();
        return true;
    }

    private void snapshotAllDrafts(Runnable after) {
        browser.snapshotDraft(() -> {
            if (detailBrowser != null) detailBrowser.snapshotDraft(after); else if (after != null) after.run();
        });
    }

    private void guardAllPanesAndSnapshot(Runnable action) {
        browser.guardNavigation(() -> browser.snapshotDraft(() -> {
            BrowserController detail = detailBrowser;
            if (detail != null) detail.guardNavigation(() -> detail.snapshotDraft(action), () -> setBrowserVisible(true));
            else action.run();
        }), () -> setBrowserVisible(true));
    }

    private View buildHeader() {
        LinearLayout header = horizontal();
        header.setTag("header-" + theme.id);
        header.setPadding(dp(theme == ThemeSpec.MAIL ? 12 : 8), dp(4), dp(theme == ThemeSpec.MAIL ? 12 : 8), dp(4));
        header.setMinimumHeight(dp(theme == ThemeSpec.MAIL ? 64 : 56));
        header.setBackgroundColor(theme == ThemeSpec.SHEET
                ? (CommunityOptions.accentIndex(this) >= 0 ? colors.accent : dark ? 0xFF185C37 : 0xFF107C41) : colors.surface);
        if (theme == ThemeSpec.MAIL) {
            LinearLayout searchBar = horizontal();
            searchBar.setTag("mail-search-bar");
            searchBar.setBackground(round(colors.tint, 28, Color.TRANSPARENT));
            searchBar.addView(headerAction("menu", "메뉴", v -> showMailDrawer()), new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView search = text("메일함에서 검색", 15, colors.mutedText, false);
            search.setTag("header-search");
            search.setContentDescription("탐색");
            search.setSingleLine(true);
            search.setEllipsize(android.text.TextUtils.TruncateAt.END);
            search.setGravity(Gravity.CENTER_VERTICAL);
            search.setMinHeight(dp(48));
            search.setOnClickListener(v -> showExplore());
            searchBar.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
            FrameLayout account = headerAction("account", theme.displayName + " 홈", v -> showHome());
            searchBar.addView(account, new LinearLayout.LayoutParams(dp(48), dp(48)));
            header.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));
        } else if (theme == ThemeSpec.POST) {
            FrameLayout home = headerAction("account", "메뉴", this::showMenu);
            home.setBackground(round(colors.tint, 24, Color.TRANSPARENT));
            header.addView(home, new LinearLayout.LayoutParams(dp(48), dp(48)));
            FrameLayout center = new FrameLayout(this);
            center.setTag("post-centered-mark");
            UiGlyphView mark = new UiGlyphView(this, "x-mark", colors.text);
            center.setContentDescription(theme.displayName + " 홈");
            center.setFocusable(true);
            center.setOnClickListener(v -> showHome());
            center.addView(mark, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            header.addView(center, new LinearLayout.LayoutParams(0, dp(48), 1));
            header.addView(headerAction("settings", "설정", v -> showSettings()), new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else {
            TextView title = text(theme == ThemeSpec.PHOTOGRAM ? "Instagram" : theme == ThemeSpec.CAFE ? "Café" : theme.displayName,
                    theme == ThemeSpec.PHOTOGRAM ? 28 : theme == ThemeSpec.SHEET ? 18 : 24,
                    theme == ThemeSpec.SHEET ? (CommunityOptions.accentIndex(this) >= 0 ? colors.onAccent : Color.WHITE) : colors.text, true);
            title.setTag("header-wordmark");
            if (theme == ThemeSpec.PHOTOGRAM) title.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
            if (theme == ThemeSpec.CAFE) title.setTypeface(Typeface.SERIF, Typeface.BOLD_ITALIC);
            if (theme == ThemeSpec.EBOOK || theme == ThemeSpec.MAGAZINE) title.setTypeface(Typeface.SERIF, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setMinHeight(dp(48));
            title.setPadding(dp(8), 0, dp(8), 0);
            title.setContentDescription(theme.displayName + " 홈");
            title.setOnClickListener(v -> showHome());
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (theme == ThemeSpec.CAFE) header.addView(headerAction("plus", "글쓰기", v -> compose()), new LinearLayout.LayoutParams(dp(48), dp(48)));
            header.addView(headerAction(theme == ThemeSpec.PHOTOGRAM ? "add" : "search", theme == ThemeSpec.PHOTOGRAM ? "글쓰기" : theme.searchHint, v -> { if (theme == ThemeSpec.PHOTOGRAM) compose(); else showExplore(); }), new LinearLayout.LayoutParams(dp(48), dp(48)));
            header.addView(headerAction("menu", "메뉴", this::showMenu), new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        return header;
    }

    private FrameLayout headerAction(String glyph, String description, View.OnClickListener click) {
        FrameLayout action = new FrameLayout(this);
        action.setContentDescription(description);
        action.setFocusable(true);
        action.setOnClickListener(click);
        UiGlyphView icon = new UiGlyphView(this, glyph, theme == ThemeSpec.SHEET
                ? (CommunityOptions.accentIndex(this) >= 0 ? colors.onAccent : Color.WHITE) : colors.text);
        action.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        return action;
    }

    private View buildNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setTag("navigation-" + theme.id);
        bottomNavigation = navigation;
        navigation.setGravity(Gravity.CENTER);
        navigation.setBackgroundColor(theme == ThemeSpec.MAIL ? colors.tint : colors.surface);
        navigation.setPadding(dp(8), dp(3), dp(8), dp(3));
        switch (theme) {
            case MAIL:
                navigation.setTag("mail-action-dock");
                addNav(navigation, "inbox", "홈", !browserVisible, v -> showHome());
                addNav(navigation, "folder", "라벨", false, v -> showMailDrawer());
                break;
            case POST:
                addNav(navigation, "home", "홈", !browserVisible, v -> showHome());
                addNav(navigation, "search", "탐색", false, v -> showExplore());
                addNav(navigation, "bookmark", "계정 즐겨찾기", false, v -> openAccountFavorites());
                addNav(navigation, "account", "설정", false, v -> showSettings());
                break;
            case PHOTOGRAM:
                addNav(navigation, "home", "홈", !browserVisible, v -> showHome());
                addNav(navigation, "search", "탐색", false, v -> showExplore());
                addNav(navigation, "add", "글쓰기", false, v -> compose());
                addNav(navigation, "bookmark", "계정 즐겨찾기", false, v -> openAccountFavorites());
                addNav(navigation, "account", "설정", false, v -> showSettings());
                break;
            case SHEET:
                navigation.setTag("sheet-tabs");
                navigation.addView(sheetAction("grid", "홈", v -> showHome()), new LinearLayout.LayoutParams(dp(48), dp(48)));
                TextView sheet = text("시트 목록", 13, colors.accent, true);
                sheet.setContentDescription("시트 목록");
                sheet.setGravity(Gravity.CENTER);
                sheet.setMinHeight(dp(48));
                sheet.setBackground(round(colors.tint, 0, colors.border));
                sheet.setOnClickListener(v -> showHome());
                navigation.addView(sheet, new LinearLayout.LayoutParams(0, -2, 1));
                navigation.addView(sheetAction("plus", "글쓰기", v -> compose()), new LinearLayout.LayoutParams(dp(48), dp(48)));
                navigation.addView(sheetAction("settings", "설정", v -> showSettings()), new LinearLayout.LayoutParams(dp(48), dp(48)));
                break;
            case EBOOK:
                navigation.setTag("reader-controls");
                addNav(navigation, "book", "홈", !browserVisible, v -> showHome());
                addNav(navigation, "search", "탐색", false, v -> showExplore());
                addNav(navigation, "settings", "설정", false, v -> showSettings());
                break;
            default:
                addNav(navigation, "home", "홈", !browserVisible, v -> showHome());
                addNav(navigation, "search", "탐색", false, v -> showExplore());
                addNav(navigation, "edit", "글쓰기", false, v -> compose());
                break;
        }
        return navigation;
    }

    private FrameLayout sheetAction(String glyph, String description, View.OnClickListener click) {
        FrameLayout action = new FrameLayout(this);
        action.setContentDescription(description);
        action.setFocusable(true);
        action.setOnClickListener(click);
        action.addView(new UiGlyphView(this, glyph, colors.text), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        return action;
    }

    private void rebuildComposeAction() {
        if (composeFloatingAction != null) content.removeView(composeFloatingAction);
        composeFloatingAction = null;
        if (theme != ThemeSpec.MAIL && theme != ThemeSpec.POST) return;
        LinearLayout button = horizontal();
        button.setTag("compose-floating-" + theme.id);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription("글쓰기");
        button.setFocusable(true);
        button.setMinimumHeight(dp(56));
        button.setMinimumWidth(dp(56));
        button.setPadding(dp(16), dp(14), dp(16), dp(14));
        int fill = theme == ThemeSpec.MAIL ? (dark ? 0xFF004A77 : 0xFFC2E7FF) : colors.accent;
        int ink = theme == ThemeSpec.MAIL ? (dark ? 0xFFC2E7FF : 0xFF001D35) : colors.onAccent;
        if (theme == ThemeSpec.MAIL && CommunityOptions.accentIndex(this) >= 0) { fill = colors.tint; ink = colors.accent; }
        button.setBackground(round(fill, theme == ThemeSpec.MAIL ? 16 : 32, Color.TRANSPARENT));
        button.setElevation(dp(4));
        button.addView(new UiGlyphView(this, theme == ThemeSpec.MAIL ? "pencil" : "plus", ink), new LinearLayout.LayoutParams(dp(24), dp(24)));
        if (theme == ThemeSpec.MAIL && !compactLayout()) {
            TextView caption = text("편지쓰기", 14, ink, true);
            caption.setPadding(dp(12), 0, 0, 0);
            button.addView(caption, new LinearLayout.LayoutParams(-2, -2));
        }
        button.setOnClickListener(v -> compose());
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM);
        position.setMargins(dp(16), dp(16), dp(16), dp(16));
        content.addView(button, position);
        composeFloatingAction = button;
        updateComposeActionVisibility();
    }

    private void updateComposeActionVisibility() {
        if (composeFloatingAction == null) return;
        boolean show = !browserVisible || (!loading && latestError == null && pendingComposerTarget.isEmpty()
                && isFloatingComposeList(pageUrl));
        composeFloatingAction.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private boolean isFloatingComposeList(String value) {
        if (value == null || value.isEmpty()) return false;
        Uri uri = Uri.parse(value);
        String path = uri.getPath();
        return "https".equalsIgnoreCase(uri.getScheme()) && "m.dcinside.com".equalsIgnoreCase(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1 && path != null
                && path.matches("^/(?:board|mini|person)/[A-Za-z0-9_]+/?$");
    }

    private void hideComposeForNavigation() {
        if (composeFloatingAction != null) composeFloatingAction.setVisibility(View.GONE);
    }

    private void addNav(LinearLayout navigation, String icon, String label, boolean active, View.OnClickListener click) {
        navigation.addView(nav(icon, label, active, click), new LinearLayout.LayoutParams(0, -2, 1));
    }

    private View nav(String symbol, String label, boolean active, View.OnClickListener click) {
        boolean iconOnly = theme == ThemeSpec.POST || theme == ThemeSpec.PHOTOGRAM;
        boolean textOnly = theme == ThemeSpec.EBOOK || theme == ThemeSpec.MAGAZINE;
        LinearLayout item = vertical();
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(dp(iconOnly ? 48 : 58));
        item.setPadding(dp(2), dp(4), dp(2), dp(4));
        item.setContentDescription(label);
        item.setFocusable(true);
        item.setOnClickListener(click);
        int tint = active && !iconOnly ? colors.accent : colors.text;
        item.setSelected(active);
        if (theme == ThemeSpec.MAIL) {
            FrameLayout capsule = new FrameLayout(this);
            if (active) capsule.setBackground(round(CommunityOptions.accentIndex(this) >= 0 ? colors.tint
                    : dark ? 0xFF004A77 : 0xFFD3E3FD, 16, Color.TRANSPARENT));
            capsule.addView(new UiGlyphView(this, symbol, tint), new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
            item.addView(capsule, new LinearLayout.LayoutParams(dp(48), dp(28)));
        } else if (!textOnly) {
            item.addView(new UiGlyphView(this, active && iconOnly && "home".equals(symbol) ? "home-filled" : symbol, tint), new LinearLayout.LayoutParams(dp(25), dp(25)));
        }
        if (!iconOnly) {
            String shown = label;
            if (label.equals("홈")) shown = theme == ThemeSpec.MAIL ? "메일" : theme == ThemeSpec.SHEET ? "시트 목록"
                    : theme == ThemeSpec.EBOOK ? "서가" : theme == ThemeSpec.CAFE ? "내 카페" : theme == ThemeSpec.MAGAZINE ? "섹션" : label;
            if (theme == ThemeSpec.EBOOK && label.equals("설정")) shown = "Aa · 읽기 설정";
            if (theme == ThemeSpec.SHEET && label.equals("설정")) shown = "보기";
            if (theme == ThemeSpec.MAGAZINE && label.equals("글쓰기")) shown = "기고";
            TextView caption = text(shown, textOnly ? 12 : 10, active ? colors.accent : colors.mutedText, active);
            caption.setGravity(Gravity.CENTER);
            caption.setPadding(0, dp(textOnly ? 0 : 3), 0, 0);
            item.addView(caption);
        }
        if (active && (theme == ThemeSpec.SHEET || textOnly)) {
            View underline = new View(this);
            underline.setBackgroundColor(colors.accent);
            LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(dp(28), dp(2));
            rule.topMargin = dp(5);
            item.addView(underline, rule);
        }
        return item;
    }
    private void rebuildHome() {
        if (homeView != null) content.removeView(homeView);
        homeView = new ScrollView(this);
        homeView.setFillViewport(true);
        homeView.setClipToPadding(false);
        homeView.setBackgroundColor(theme == ThemeSpec.CAFE ? colors.surface : colors.background);
        FrameLayout homeFrame = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (getChildCount() > 0) getChildAt(0).getLayoutParams().width = Math.min(dp(720), View.MeasureSpec.getSize(widthSpec));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        LinearLayout home = vertical();
        home.setTag("home-content");
        int gutter = theme == ThemeSpec.MAIL || theme == ThemeSpec.POST || theme == ThemeSpec.SHEET ? 8 : compactLayout() ? 16 : 20;
        home.setPadding(dp(gutter), dp(10), dp(gutter), dp(theme == ThemeSpec.MAIL || theme == ThemeSpec.POST ? 96 : 20));
        FrameLayout.LayoutParams homeParams = new FrameLayout.LayoutParams(
                Math.min(dp(720), dp(getResources().getConfiguration().screenWidthDp)), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        homeFrame.addView(home, homeParams);
        homeView.addView(homeFrame, new ScrollView.LayoutParams(-1, -2));
        if (hasOpenPage()) {
            LinearLayout resume = horizontal();
            resume.setPadding(dp(15), dp(13), dp(13), dp(13));
            resume.setBackground(round(alpha(colors.accent, .08f), 15, alpha(colors.accent, .14f)));
            TextView resumeText = text("열어 둔 페이지 계속 보기", 12, colors.accent, true);
            resume.addView(resumeText, new LinearLayout.LayoutParams(0, -2, 1));
            resume.addView(text("→", 18, colors.accent, false));
            resume.setOnClickListener(v -> setBrowserVisible(true));
            LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(-1, -2);
            resumeParams.bottomMargin = dp(23);
            home.addView(resume, resumeParams);
        }

        addHomeIdentity(home);
        LinearLayout section = compactLayout() ? vertical() : horizontal();
        section.addView(text(favoritesHeading(), theme == ThemeSpec.MAIL ? 12 : 16, theme == ThemeSpec.MAIL ? colors.mutedText : colors.text, true), new LinearLayout.LayoutParams(compactLayout() ? -1 : 0, -2, compactLayout() ? 0 : 1));
        TextView refreshFavorites = text("새로고침", 11, colors.accent, true);
        refreshFavorites.setPadding(dp(10), dp(12), 0, dp(12));
        refreshFavorites.setContentDescription("계정 즐겨찾기 새로고침");
        refreshFavorites.setOnClickListener(v -> browser.refreshAccountFavorites());
        refreshFavorites.setMinHeight(dp(48));
        refreshFavorites.setMinWidth(dp(48));
        refreshFavorites.setGravity(Gravity.CENTER_VERTICAL);
        section.addView(refreshFavorites);
        home.addView(section);
        if (accountFavorites.isEmpty()) {
        LinearLayout empty = vertical();
        empty.setContentDescription("즐겨찾기 빈 상태");
        empty.setPadding(dp(20), dp(22), dp(20), dp(21));
        empty.setBackground(round(colors.surface, 19, colors.border));
        String stateTitle = "로그인하고 관심 " + theme.groupNoun + "을 이어보세요";
        String stateBody = "내 계정에 저장한 즐겨찾기로 시작해 보세요.";
        String stateAction = "로그인";
        if ("loading".equals(accountState)) {
            stateTitle = "즐겨찾기를 확인하고 있어요";
            stateBody = "로그인 상태와 계정에 저장된 목록을 확인합니다.";
        } else if ("emptyOrUnavailable".equals(accountState)) {
            stateTitle = "즐겨찾기를 확인해 주세요";
            stateBody = "원본 즐겨찾기에서 목록을 확인하거나 추가한 뒤 다시 불러오세요.";
            stateAction = "원본 즐겨찾기";
        } else if ("error".equals(accountState)) {
            stateTitle = "즐겨찾기를 불러오지 못했어요";
            stateBody = "인터넷 연결을 확인한 뒤 위의 새로고침을 눌러주세요.";
        }
        TextView emptyTitle = text(stateTitle, 16, colors.text, true);
        emptyTitle.setLineSpacing(dp(3), 1f);
        empty.addView(emptyTitle);
        TextView emptyBody = text(stateBody, 12, colors.mutedText, false);
        emptyBody.setPadding(0, dp(11), 0, dp(19));
        emptyBody.setLineSpacing(dp(3), 1f);
        empty.addView(emptyBody);
        TextView login = text(stateAction, 13, colors.onAccent, true);
        login.setContentDescription(stateAction);
        login.setGravity(Gravity.CENTER);
        login.setMinHeight(dp(48));
        login.setPadding(dp(10), dp(10), dp(10), dp(10));
        login.setBackground(round(colors.accent, 13, Color.TRANSPARENT));
        boolean opensAccountFavorites = "emptyOrUnavailable".equals(accountState);
        login.setOnClickListener(v -> { if (opensAccountFavorites) openAccountFavorites(); else openLogin(); });
        empty.addView(login, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-1, -2);
        emptyParams.topMargin = dp(15);
        home.addView(empty, emptyParams);
        } else {
            addFavoriteCollection(home, accountFavorites, true);
        }
        if (!favorites.isEmpty()) {
            TextView localTitle = text("이 기기에 저장한 페이지", 15, colors.text, true);
            localTitle.setPadding(0, dp(24), 0, dp(3));
            home.addView(localTitle);
        }
        addFavoriteCollection(home, favorites, false);
        TextView style = text("화면 스타일  ·  " + theme.displayName + "  ›", 12, colors.mutedText, false);
        style.setContentDescription("화면 스타일");
        style.setMinHeight(dp(48));
        style.setPadding(0, dp(18), 0, dp(12));
        style.setOnClickListener(v -> showThemes());
        home.addView(style, new LinearLayout.LayoutParams(-1, -2));
        TextView settings = text("환경 설정", 12, colors.mutedText, false);
        settings.setContentDescription("설정");
        settings.setMinHeight(dp(48));
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setOnClickListener(v -> showSettings());
        home.addView(settings, new LinearLayout.LayoutParams(-1, -2));
        content.addView(homeView, new FrameLayout.LayoutParams(-1, -1));
        homeView.setVisibility(browserVisible ? View.GONE : View.VISIBLE);
        applyPaneLayout();
        rebuildComposeAction();
    }

    private void addHomeIdentity(LinearLayout home) {
        if (theme == ThemeSpec.MAIL || theme == ThemeSpec.PHOTOGRAM) return;
        if (theme == ThemeSpec.POST || theme == ThemeSpec.CAFE) {
            LinearLayout tabs = horizontal();
            tabs.setTag("home-identity-" + theme.id);
            tabs.setBackgroundColor(colors.surface);
            String[] labels = theme == ThemeSpec.POST ? new String[]{"커뮤니티", "탐색"} : new String[]{"내 카페", "카페 검색"};
            for (int i = 0; i < labels.length; i++) {
                final boolean homeTab = i == 0;
                LinearLayout tab = vertical();
                tab.setGravity(Gravity.CENTER);
                tab.setMinimumHeight(dp(48));
                TextView label = text(labels[i], 15, homeTab ? colors.text : colors.mutedText, homeTab);
                label.setGravity(Gravity.CENTER);
                label.setPadding(dp(6), dp(11), dp(6), dp(9));
                tab.addView(label);
                View rule = new View(this);
                rule.setBackgroundColor(homeTab ? colors.accent : Color.TRANSPARENT);
                tab.addView(rule, new LinearLayout.LayoutParams(dp(44), dp(3)));
                tab.setFocusable(true);
                tab.setContentDescription(labels[i]);
                tab.setOnClickListener(v -> { if (homeTab) showHome(); else showExplore(); });
                tabs.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
            }
            home.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        LinearLayout intro = vertical();
        intro.setTag("home-identity-" + theme.id);
        intro.setPadding(dp(12), dp(12), dp(12), dp(15));
        if (theme == ThemeSpec.SHEET) {
            LinearLayout formula = horizontal();
            formula.setTag("sheet-formula-context");
            formula.setBackground(round(colors.surface, 0, colors.border));
            TextView cell = text("보기", 13, colors.mutedText, false);
            cell.setGravity(Gravity.CENTER);
            cell.setSingleLine(true);
            cell.setMinWidth(dp(48));
            cell.setMinHeight(dp(40));
            formula.addView(cell, new LinearLayout.LayoutParams(-2, -2));
            TextView fx = text("ƒx", 17, colors.mutedText, false);
            fx.setGravity(Gravity.CENTER);
            fx.setSingleLine(true);
            fx.setMinWidth(dp(42));
            fx.setMinHeight(dp(40));
            formula.addView(fx, new LinearLayout.LayoutParams(-2, -2));
            TextView context = text("내 통합 문서", 13, colors.text, false);
            context.setPadding(dp(10), dp(9), dp(8), dp(9));
            formula.addView(context, new LinearLayout.LayoutParams(0, -2, 1));
            home.addView(formula, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        String title = theme == ThemeSpec.EBOOK ? "책장" : "THE COLLECTION";
        String subtitle = theme == ThemeSpec.EBOOK ? "좋아하는 이야기를 한 권씩 펼쳐 보세요." : "내가 선택한 이야기의 지면";
        TextView heading = text(title, 28, colors.text, true);
        heading.setTypeface(Typeface.SERIF, Typeface.BOLD);
        intro.addView(heading);
        TextView body = text(subtitle, 12, colors.mutedText, false);
        body.setPadding(0, dp(8), 0, 0);
        intro.addView(body);
        home.addView(intro, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addFavoriteCollection(LinearLayout parent, List<Favorite> entries, boolean fromAccount) {
        if (entries.isEmpty()) return;
        if (theme == ThemeSpec.CAFE && fromAccount) {
            LinearLayout grid = vertical();
            grid.setTag("cafe-favorites-grid");
            int columns = compactLayout() ? (getResources().getConfiguration().fontScale * fontScale > 1.5f ? 2 : 3) : 4;
            for (int index = 0; index < entries.size();) {
                LinearLayout row = horizontal();
                row.setGravity(Gravity.TOP);
                for (int column = 0; column < columns; column++, index++) {
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    if (index >= entries.size()) { row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1)); continue; }
                    Favorite favorite = entries.get(index);
                    LinearLayout tile = vertical();
                    tile.setGravity(Gravity.CENTER_HORIZONTAL);
                    tile.setPadding(dp(4), dp(12), dp(4), dp(18));
                    TextView avatar = initial(favorite, 24);
                    avatar.setBackground(round(colors.tint, 30, Color.TRANSPARENT));
                    tile.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));
                    TextView name = text(favorite.name, 12, colors.text, false);
                    name.setGravity(Gravity.CENTER);
                    name.setMaxLines(2);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    name.setPadding(0, dp(8), 0, 0);
                    tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
                    bindFavorite(tile, favorite, true);
                    row.addView(tile, cell);
                }
                grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
            parent.addView(grid, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        if (theme == ThemeSpec.PHOTOGRAM && fromAccount) {
            HorizontalScrollView strip = new HorizontalScrollView(this);
            strip.setTag("photogram-channel-strip");
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout channels = horizontal();
            channels.setGravity(Gravity.TOP);
            for (Favorite favorite : entries) {
                LinearLayout channel = vertical();
                channel.setGravity(Gravity.CENTER_HORIZONTAL);
                channel.setPadding(dp(5), dp(8), dp(5), dp(10));
                TextView initial = initial(favorite, 26);
                initial.setBackground(round(colors.tint, 30, colors.border));
                channel.addView(initial, new LinearLayout.LayoutParams(dp(60), dp(60)));
                TextView name = text(favorite.name, 11, colors.text, false);
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setPadding(0, dp(7), 0, 0);
                channel.addView(name, new LinearLayout.LayoutParams(-1, -2));
                bindFavorite(channel, favorite, true);
                channels.addView(channel, new LinearLayout.LayoutParams(dp(92), -2));
            }
            strip.addView(channels);
            parent.addView(strip, new LinearLayout.LayoutParams(-1, -2));
            TextView prompt = text("피드를 선택하면 게시글을 사진 카드로 볼 수 있어요.", 12, colors.mutedText, false);
            prompt.setPadding(dp(8), dp(16), dp(8), dp(20));
            parent.addView(prompt);
            return;
        }
        if (theme == ThemeSpec.SHEET) parent.addView(sheetColumns());
        boolean grid = theme == ThemeSpec.EBOOK || theme == ThemeSpec.MAGAZINE || theme == ThemeSpec.PHOTOGRAM;
        int columns = grid && !compactLayout() ? 2 : 1;
        for (int index = 0; index < entries.size();) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.TOP);
            for (int column = 0; column < columns; column++, index++) {
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                cell.topMargin = dp(theme == ThemeSpec.SHEET || theme == ThemeSpec.MAIL || theme == ThemeSpec.POST || theme == ThemeSpec.CAFE ? 1 : 10);
                if (column > 0) cell.leftMargin = dp(12);
                if (index < entries.size()) row.addView(favoriteCard(entries.get(index), index, fromAccount), cell);
                else row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1));
            }
            parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private View sheetColumns() {
        LinearLayout columns = horizontal();
        columns.setTag("sheet-column-headings");
        columns.setBackgroundColor(alpha(colors.accent, .09f));
        TextView number = text("#", 12, colors.mutedText, true);
        number.setGravity(Gravity.CENTER);
        columns.addView(number, new LinearLayout.LayoutParams(dp(42), dp(36)));
        TextView name = text("A   시트 이름", 12, colors.mutedText, true);
        name.setPadding(dp(12), dp(10), 0, dp(10));
        columns.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        return columns;
    }

    private View favoriteCard(Favorite favorite, int index, boolean fromAccount) {
        if (theme == ThemeSpec.EBOOK || theme == ThemeSpec.MAGAZINE || theme == ThemeSpec.PHOTOGRAM) {
            LinearLayout tile = vertical();
            tile.setTag(theme.id + "-favorite-tile");
            boolean book = theme == ThemeSpec.EBOOK;
            tile.setPadding(dp(book ? 13 : 16), dp(18), dp(16), dp(15));
            tile.setMinimumHeight(dp(book ? 180 : 148));
            tile.setBackground(round(book ? alpha(colors.accent, .12f) : colors.surface,
                    book ? 3 : theme == ThemeSpec.PHOTOGRAM ? 12 : 0, colors.border));
            TextView eyebrow = text(book ? "MY LIBRARY" : theme == ThemeSpec.MAGAZINE ? "COLLECTION  /  " + String.format(Locale.ROOT, "%02d", index + 1) : "SAVED", 9, colors.accent, true);
            eyebrow.setPadding(0, 0, 0, dp(20));
            tile.addView(eyebrow);
            TextView name = text(favorite.name, book ? 20 : 19, colors.text, true);
            if (book || theme == ThemeSpec.MAGAZINE) name.setTypeface(Typeface.SERIF, Typeface.BOLD);
            name.setLineSpacing(dp(3), 1f);
            tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView caption = text(book ? "펼쳐 읽기  →" : "이야기 열기  →", 11, colors.mutedText, false);
            caption.setPadding(0, dp(24), 0, 0);
            tile.addView(caption);
            bindFavorite(tile, favorite, fromAccount);
            return tile;
        }
        LinearLayout card = horizontal();
        card.setTag(theme.id + "-favorite-row");
        card.setGravity(Gravity.CENTER_VERTICAL);
        boolean sheet = theme == ThemeSpec.SHEET;
        card.setPadding(dp(sheet ? 0 : 12), dp(sheet ? 0 : 11), dp(12), dp(sheet ? 0 : 11));
        card.setMinimumHeight(dp(60));
        int radius = 0;
        card.setBackground(round(colors.surface, radius,
                theme == ThemeSpec.POST || theme == ThemeSpec.PHOTOGRAM || theme == ThemeSpec.MAIL ? Color.TRANSPARENT : colors.border));
        TextView avatar = initial(favorite, sheet ? 13 : 21);
        if (sheet) {
            avatar.setText(String.valueOf(index + 1));
            avatar.setBackground(round(alpha(colors.accent, .06f), 0, colors.border));
        }
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(sheet ? 42 : 46), dp(sheet ? 60 : 46));
        iconParams.rightMargin = dp(sheet ? 12 : 14);
        card.addView(avatar, iconParams);
        LinearLayout info = vertical();
        TextView name = text(theme.labelFor(favorite.name), 15, colors.text, true);
        info.addView(name);
        String detailLabel = !fromAccount ? "이 기기에 저장한 페이지" : theme == ThemeSpec.MAIL ? "라벨의 메일 보기"
                : theme == ThemeSpec.POST ? "커뮤니티 게시물" : theme == ThemeSpec.CAFE ? "카페글 보기" : "계정 즐겨찾기";
        TextView detail = text(detailLabel, 11, colors.mutedText, false);
        detail.setPadding(0, dp(6), 0, 0);
        info.addView(detail);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = text(theme == ThemeSpec.MAIL ? "☆" : "›", 21, colors.mutedText, false);
        chevron.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 23);
        chevron.setPadding(dp(8), 0, 0, 0);
        card.addView(chevron);
        bindFavorite(card, favorite, fromAccount);
        return card;
    }

    private TextView initial(Favorite favorite, int size) {
        TextView initial = text(new String(Character.toChars(favorite.name.codePointAt(0))), size, theme == ThemeSpec.PHOTOGRAM ? colors.text : colors.accent, true);
        initial.setGravity(Gravity.CENTER);
        initial.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size);
        initial.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        initial.setBackground(round(alpha(colors.accent, dark ? .20f : .08f),
                theme == ThemeSpec.CAFE ? 14 : 30, Color.TRANSPARENT));
        return initial;
    }

    private void bindFavorite(View card, Favorite favorite, boolean fromAccount) {
        card.setContentDescription(theme.labelFor(favorite.name) + " 열기");
        card.setFocusable(true);
        card.setOnClickListener(v -> navigate(favorite.url));
        card.setOnLongClickListener(v -> {
            if (fromAccount) {
                dialogBuilder().setTitle(favorite.name).setItems(new String[]{"원본 즐겨찾기 관리"},
                        (dialog, which) -> openAccountFavorites()).show();
                return true;
            }
            dialogBuilder().setTitle(favorite.name)
                    .setItems(new String[]{"이름 바꾸기", "목록에서 제거"}, (dialog, which) -> {
                        if (which == 0) renameFavorite(favorite);
                        else { favorites.remove(favorite); saveFavorites(); rebuildHome(); }
                    }).show();
            return true;
        });
    }

    private String favoritesHeading() {
        switch (theme) {
            case MAIL: return "라벨";
            case POST: return "내 커뮤니티";
            case PHOTOGRAM: return "즐겨찾기";
            case SHEET: return "고정 시트";
            case CAFE: return "즐겨찾는 카페";
            case EBOOK: return "내 서가";
            default: return "관심 섹션";
        }
    }

    private void showHome() {
        boolean returnedFromPage = browserVisible;
        pendingComposerTarget = "";
        setBrowserVisible(false);
        rebuildHome();
        if (returnedFromPage) browser.refreshAccountFavorites();
    }

    private boolean hasOpenPage() {
        String url = browser == null ? "" : currentBrowser().getUrl();
        return url != null && !url.isEmpty() && !"about:blank".equals(url);
    }

    private void setBrowserVisible(boolean visible) {
        browserVisible = visible;
        applyPaneLayout();
        BrowserController active = currentBrowser();
        pageUrl = active.getUrl() == null ? "" : active.getUrl();
        pageTitle = active.getTitle();
        loading = active == detailBrowser ? detailLoading : primaryLoading;
        if (homeView != null) homeView.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (progress != null) progress.setVisibility(loading && visible ? View.VISIBLE : View.INVISIBLE);
        if (errorBanner != null) errorBanner.setVisibility(latestError != null && visible ? View.VISIBLE : View.GONE);
        if (bottomNavigation != null && bottomNavigation.getParent() == root) {
            root.removeView(bottomNavigation);
            root.addView(buildNavigation());
        }
        updateHeader();
        updateComposeActionVisibility();
    }

    private void navigate(String url) {
        Runnable open = () -> {
            discardDetailBrowser();
            latestError = null;
            pendingComposerTarget = "";
            if (errorBanner != null) errorBanner.setVisibility(View.GONE);
            browser.setOriginalMode(false);
            setBrowserVisible(true);
            hideComposeForNavigation();
            browser.open(url);
        };
        if (detailBrowser != null) guardAllPanesAndSnapshot(open); else guardComposerNavigation(open);
    }

    private void guardComposerNavigation(Runnable action) {
        currentBrowser().guardNavigation(action, () -> setBrowserVisible(true));
    }

    private void compose() {
        if (!browserVisible || !hasOpenPage()) {
            ArrayList<Favorite> targets = new ArrayList<>(accountFavorites);
            targets.addAll(favorites);
            // Saved pages also include searches and sign-in pages. They are
            // useful bookmarks, but cannot resolve a community write action.
            targets.removeIf(favorite -> {
                String host = Uri.parse(favorite.url).getHost();
                return UrlPolicy.galleryId(favorite.url).isEmpty()
                        || !("m.dcinside.com".equalsIgnoreCase(host)
                        || "gall.dcinside.com".equalsIgnoreCase(host));
            });
            if (targets.isEmpty()) { toast("먼저 글을 쓸 " + theme.groupNoun + "을 열어주세요"); showExplore(); return; }
            String[] names = new String[targets.size()];
            for (int i = 0; i < targets.size(); i++) names[i] = theme.labelFor(targets.get(i).name);
            dialogBuilder().setTitle("어디에 글을 쓸까요?")
                    .setItems(names, (dialog, position) -> {
                        String targetUrl = targets.get(position).url;
                        guardComposerNavigation(() -> {
                            pendingComposerTarget = UrlPolicy.galleryId(targetUrl);
                            currentBrowser().setOriginalMode(false);
                            setBrowserVisible(true);
                            hideComposeForNavigation();
                            currentBrowser().open(targetUrl);
                        });
                    }).setNegativeButton("닫기", null).show();
        } else { hideComposeForNavigation(); currentBrowser().openComposer(); }
    }

    private void showExplore() {
        showExplore("");
    }

    private void showExplore(String entered) {
        LinearLayout panel = vertical();
        panel.setPadding(dp(23), dp(8), dp(23), dp(7));
        TextView hint = text("이름으로 검색하거나 원본 페이지 주소를 붙여 넣으세요.", 13, colors.mutedText, false);
        hint.setPadding(0, 0, 0, dp(10));
        panel.addView(hint);
        EditText input = input(theme.searchHint + " 또는 주소");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        // Input type/single-line setters reset TextView's pixel minimum.
        input.setMinHeight(dp(48));
        input.setText(entered);
        input.setSelection(input.length());
        panel.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView sample = text("페이지를 연 뒤 메뉴에서 이 기기에 저장할 수 있어요.", 11, colors.mutedText, false);
        sample.setPadding(0, dp(12), 0, 0);
        panel.addView(sample);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setTitle(theme.searchHint).setView(scroll)
                .setNegativeButton("취소", null).setPositiveButton("열기", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("검색어나 주소를 입력하세요"); return; }
            if (value.contains("://") || value.startsWith("www.") || value.startsWith("m.dcinside.com") || value.startsWith("gall.dcinside.com")) {
                String url = value.contains("://") ? value : "https://" + value;
                if (!UrlPolicy.isDcHttps(url)) {
                    input.setError("https://로 시작하는 디시 페이지 주소를 입력하세요"); return;
                }
                dialog.dismiss();
                navigate(url);
            } else {
                dialog.dismiss();
                navigate("https://m.dcinside.com/search?keyword=" + Uri.encode(value));
            }
        }));
        showAdaptiveDialog(dialog, () -> {
            String current = input.getText().toString();
            int start = input.getSelectionStart();
            int end = input.getSelectionEnd();
            boolean focused = input.hasFocus();
            dialog.dismiss();
            showExplore(current);
            EditText replacement = adaptiveDialog.getWindow().getDecorView().findViewWithTag("text-input");
            if (replacement != null) {
                if (start >= 0 && end >= 0) replacement.setSelection(Math.min(start, replacement.length()), Math.min(end, replacement.length()));
                if (focused) replacement.requestFocus(); else replacement.clearFocus();
            }
        });
    }

    private void showThemes() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = vertical();
        list.setPadding(dp(16), dp(6), dp(16), dp(16));
        scroll.addView(list);
        AlertDialog dialog = dialogBuilder().setTitle("화면 스타일").setView(scroll)
                .setNegativeButton("닫기", null).create();
        for (ThemeSpec option : ThemeSpec.values()) {
            LinearLayout row = horizontal();
            row.setPadding(dp(13), dp(13), dp(13), dp(13));
            row.setBackground(round(option == theme ? alpha(colors.accent, .10f) : colors.surface, 14,
                    option == theme ? colors.accent : colors.border));
            ImageView icon = new ImageView(this);
            icon.setImageResource(option.iconRes);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout info = vertical();
            info.setPadding(dp(14), 0, 0, 0);
            info.addView(text(option.displayName, 15, colors.text, true));
            TextView desc = text(themeDescription(option), 11, colors.mutedText, false);
            desc.setPadding(0, dp(4), 0, 0);
            info.addView(desc);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            if (option == theme) row.addView(text("✓", 18, colors.accent, true));
            row.setOnClickListener(v -> { dialog.dismiss(); selectTheme(option); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(8);
            list.addView(row, params);
        }
        showAdaptiveDialog(dialog, () -> { dialog.dismiss(); showThemes(); });
    }

    private String themeDescription(ThemeSpec option) {
        switch (option) {
            case MAIL: return "Gmail 검색창 · 라벨 · 편지 목록";
            case POST: return "X · 커뮤니티 피드 · 게시하기";
            case PHOTOGRAM: return "Instagram · 피드 · 사진 카드";
            case SHEET: return "Excel · 셀 격자 · 시트 탭";
            case CAFE: return "네이버 카페 · 내 카페 · 카페글";
            case EBOOK: return "책 표지 서가 · 읽기 설정 · 독서 화면";
            default: return "섹션 타일 · 큰 제목 · 지면 구성";
        }
    }

    private void selectTheme(ThemeSpec selected) {
        Runnable apply = () -> {
            ThemeSpec previousTheme = theme;
            // Persist before touching launcher aliases: some launchers restart
            // the task despite DONT_KILL_APP, before this callback can continue.
            if (!preferences.edit().putString("theme", selected.id).commit()) {
                toast("테마 설정을 저장하지 못했어요. 다시 시도해 주세요.");
                return;
            }
            if (matchLauncher && !LauncherIdentity.setTheme(this, selected, true)) {
                preferences.edit().putString("theme", previousTheme.id).commit();
                toast("홈 화면 아이콘을 바꾸지 못했어요. 테마를 다시 선택해 주세요.");
                return;
            }
            theme = selected;
            applyBrowserThemes();
            buildChrome();
        };
        if (matchLauncher) snapshotAllDrafts(apply);
        else apply.run();
    }

    private void showSettings() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = vertical();
        panel.setPadding(dp(23), dp(6), dp(23), dp(18));
        scroll.addView(panel);
        TextView themeChoice = settingsRow("화면 스타일", theme.displayName + "   ›");
        panel.addView(themeChoice);
        AlertDialog dialog = dialogBuilder().setTitle("환경 설정").setView(scroll)
                .setPositiveButton("완료", null).create();
        float displayedFontScale = fontScale;
        themeChoice.setOnClickListener(v -> { dialog.dismiss(); showThemes(); });
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        Switch darkSwitch = settingSwitch("어두운 화면", dark);
        darkSwitch.setOnCheckedChangeListener((button, checked) -> {
            dark = checked;
            preferences.edit().putBoolean("dark", dark).apply();
            applyBrowserThemes();
            buildChrome();
            // Dialog controls are independent of the activity chrome; rebuild
            // them as well so the open settings use the newly selected palette.
            dialog.dismiss();
            showSettings();
        });
        panel.addView(darkSwitch);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        TextView sizeLabel = text("글자 크기  " + Math.round(fontScale * 100) + "%", 14, colors.text, true);
        sizeLabel.setPadding(0, dp(20), 0, dp(7));
        panel.addView(sizeLabel);
        SeekBar size = new SeekBar(this);
        size.setMax(55);
        size.setProgress(Math.round(fontScale * 100) - 85);
        size.setProgressTintList(ColorStateList.valueOf(colors.accent));
        size.setThumbTintList(ColorStateList.valueOf(colors.accent));
        size.setContentDescription("글자 크기");
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                fontScale = (85 + value) / 100f;
                sizeLabel.setText("글자 크기  " + (85 + value) + "%");
                if (fromUser) {
                    preferences.edit().putFloat("fontScale", fontScale).apply();
                    browser.setFontScale(fontScale);
                    if (detailBrowser != null) detailBrowser.setFontScale(fontScale);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                dialog.dismiss();
                showSettings();
            }
        });
        panel.addView(size, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout scale = horizontal();
        scale.setPadding(dp(7), 0, dp(7), dp(17));
        scale.addView(text("작게", 11, colors.mutedText, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView larger = text("크게", 11, colors.mutedText, false);
        larger.setGravity(Gravity.END);
        scale.addView(larger, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(scale);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        Switch launcherSwitch = settingSwitch("홈 화면 이름·아이콘도 맞추기", launcherChangePending ? pendingLauncherValue : matchLauncher);
        launcherSwitch.setTag("launcher-setting");
        launcherSwitch.setEnabled(!launcherChangePending);
        launcherSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (launcherChangePending) return;
            launcherChangePending = true;
            pendingLauncherValue = checked;
            long request = ++launcherChangeGeneration;
            button.setEnabled(false);
            launcherTimeout = () -> {
                if (!launcherChangePending || request != launcherChangeGeneration || isDestroyed()) return;
                finishLauncherChange(button);
                toast("설정을 저장하지 못했어요. 다시 시도해 주세요.");
            };
            root.postDelayed(launcherTimeout, 5000);
            snapshotAllDrafts(() -> {
                if (!launcherChangePending || request != launcherChangeGeneration || isDestroyed()) return;
                boolean stored = preferences.edit().putBoolean("matchLauncher", checked).commit();
                if (stored && LauncherIdentity.setTheme(this, theme, checked)) {
                    matchLauncher = checked;
                    toast("홈 화면 반영에 잠시 시간이 걸릴 수 있어요");
                } else {
                    preferences.edit().putBoolean("matchLauncher", matchLauncher).commit();
                    toast("아이콘을 변경하지 못했어요. 설정을 다시 열어 시도해 주세요.");
                }
                finishLauncherChange(button);
            });
        });
        panel.addView(launcherSwitch);
        TextView launcherHint = text("앱 목록에는 선택한 테마의 이름이 표시됩니다. 휴대폰의 테마 아이콘 설정에 따라 색상이 달라질 수 있어요.", 11, colors.mutedText, false);
        launcherHint.setLineSpacing(dp(3), 1f);
        launcherHint.setPadding(0, 0, 0, dp(7));
        panel.addView(launcherHint);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        TextView pushChoice = settingsRow("푸시 알림", PushClient.isEnabled(this) ? "켜짐   ›" : "꺼짐   ›");
        pushChoice.setOnClickListener(v -> { dialog.dismiss(); showPushSettings(); });
        panel.addView(pushChoice);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        TextView communityChoice = settingsRow("커뮤니티 도구", "차단 · 메모 · 보관함 · 작성   ›");
        communityChoice.setTag("community-tools-setting");
        communityChoice.setOnClickListener(v -> { dialog.dismiss(); showCommunityTools(); });
        panel.addView(communityChoice);
        TextView accountChoice = settingsRow("간편 계정", AccountProfiles.activeLabel(this) + "   ›");
        accountChoice.setTag("account-profiles-setting");
        accountChoice.setOnClickListener(v -> { dialog.dismiss(); showAccountProfiles(); });
        panel.addView(accountChoice);
        showAdaptiveDialog(dialog, () -> { dialog.dismiss(); showSettings(); }, () -> {
            // Keyboard and accessibility adjustments do not end a touch
            // gesture. Refresh the native chrome on every dismissal route.
            if (Float.compare(displayedFontScale, fontScale) != 0) buildChrome();
        });
    }

    private void showCommunityTools() {
        if (communityDialog != null) communityDialog.dismiss();
        communityDialog = new CommunityToolsDialog(this, new CommunityToolsDialog.Host() {
            @Override public ThemeSpec.Palette palette() { return colors; }
            @Override public boolean isDark() { return dark; }
            @Override public String currentUrl() { return currentBrowser().getUrl(); }
            @Override public String currentTitle() { return currentBrowser().getTitle(); }
            @Override public void openUrl(String url) { navigate(url); }
            @Override public void onOptionsChanged() {
                browser.refreshCommunityOptions();
                if (detailBrowser != null) detailBrowser.refreshCommunityOptions();
                buildChrome(); applyPaneLayout();
            }
            @Override public void onAction(String action) {
                switch (action) {
                    case "accounts": showAccountProfiles(); break;
                    case "push": showCommentWatches(); break;
                    case "split": applyPaneLayout(); break;
                    case "media_editor":
                        startActivity(MediaToolsActivity.createIntent(MainActivity.this, new ArrayList<>(), true)
                                .putExtra(MediaToolsActivity.EXTRA_STANDALONE, true));
                        break;
                    case "autopicture": case "source_affixes":
                        openOriginalCommunityTool(action); break;
                    default: toast("이 기능은 현재 화면에서 사용할 수 없어요.");
                }
            }
        });
        communityDialog.show();
    }

    private void openOriginalCommunityTool(String kind) {
        BrowserController current = currentBrowser();
        if (!UrlPolicy.canTheme(current.getUrl())) {
            toast("먼저 게시판이나 글을 열어주세요.");
            return;
        }
        setBrowserVisible(true);
        current.openCommunitySourceTool(kind);
    }

    private void showCommentWatches() {
        if (commentWatchDialog != null) commentWatchDialog.dismiss();
        commentWatchDialog = new CommentWatchDialog(this, new CommentWatchDialog.Host() {
            @Override public ThemeSpec.Palette palette() { return colors; }
            @Override public boolean isDark() { return dark; }
            @Override public String currentUrl() { return currentBrowser().getUrl(); }
            @Override public void openPushSettings() { showPushSettings(); }
        });
        commentWatchDialog.show();
    }

    @Override public void onUserActions(String nickname, String id, String ip) {
        if (isFinishing() || isDestroyed() || userActionDialog != null && userActionDialog.isShowing()) return;
        String cleanId = identityActionValue(id), cleanIp = identityActionValue(ip), cleanNickname = identityActionValue(nickname);
        String kind = !cleanId.isEmpty() ? "id" : !cleanIp.isEmpty() ? "ip" : "nickname";
        String value = !cleanId.isEmpty() ? cleanId : !cleanIp.isEmpty() ? cleanIp : cleanNickname;
        if (value.isEmpty()) { toast("이용자 정보를 확인할 수 없어요."); return; }
        String label = "id".equals(kind) ? "아이디" : "ip".equals(kind) ? "IP" : "닉네임";
        AlertDialog dialog = dialogBuilder().setTitle(cleanNickname.isEmpty() ? "이용자 도구" : cleanNickname)
                .setItems(new String[] {"나만 보는 메모", label + "로 차단"}, (d, which) -> {
                    if (which == 0) editUserNote(kind, value, label);
                    else if (CommunityStore.addRule(this, kind, value)) {
                        refreshCommunityOptions(); toast(label + "가 일치하는 글·댓글을 이 기기에서 숨겨요.");
                    } else toast("차단을 저장하지 못했어요. 최대 200개까지 저장할 수 있어요.");
                }).setNegativeButton("닫기", null).create();
        userActionDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (userActionDialog == dialog) userActionDialog = null; });
        dialog.show(); tintDialogActions(dialog);
    }

    private void editUserNote(String kind, String value, String label) {
        EditText memo = input("나만 보는 메모 · 최대 200자");
        memo.setTag("user-note-text"); memo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        memo.setFilters(new InputFilter[] {new InputFilter.LengthFilter(CommunityStore.MAX_NOTE_LENGTH)});
        memo.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        for (CommunityStore.UserNote note : CommunityStore.notes(this)) {
            if (kind.equals(note.kind) && value.equals(note.value)) { memo.setText(note.text); break; }
        }
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(8), dp(24), dp(12));
        panel.addView(text(label + " · " + value + "\n빈칸으로 저장하면 메모를 삭제해요.", 13, colors.mutedText, false));
        panel.addView(memo);
        AlertDialog dialog = dialogBuilder().setTitle("이용자 메모").setView(panel)
                .setNegativeButton("취소", null).setPositiveButton("저장", null).create();
        userActionDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (userActionDialog == dialog) userActionDialog = null; });
        dialog.show(); tintDialogActions(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String entered = memo.getText().toString().trim();
            boolean accepted = entered.isEmpty() || CommunityStore.putNote(this, kind, value, entered);
            if (!accepted) { memo.setError("메모를 저장하지 못했어요. 총 200개까지 저장할 수 있어요."); return; }
            if (entered.isEmpty()) CommunityStore.removeNote(this, kind, value);
            dialog.dismiss(); refreshCommunityOptions();
        });
    }

    private static String identityActionValue(String input) {
        if (input == null || input.length() > CommunityStore.MAX_VALUE_LENGTH) return "";
        String value = input.trim();
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return "";
        return value;
    }

    private void refreshCommunityOptions() {
        browser.refreshCommunityOptions();
        if (detailBrowser != null) detailBrowser.refreshCommunityOptions();
    }

    @Override public void onOwnArticlePublished(String url) {
        OwnPostWatchQueue.EnqueueResult result = OwnPostWatchQueue.enqueue(this, url);
        if (result == OwnPostWatchQueue.EnqueueResult.STORAGE_ERROR)
            toast("글은 등록됐지만 댓글 알림 준비를 저장하지 못했어요. 휴대폰 저장 공간을 확인해주세요.");
    }

    @Override public void onOwnCommentPublished(String url) {
        OwnPostWatchQueue.EnqueueResult result = OwnPostWatchQueue.enqueue(this, url);
        if (result == OwnPostWatchQueue.EnqueueResult.STORAGE_ERROR)
            toast("댓글은 등록됐지만 답글 알림 준비를 저장하지 못했어요. 휴대폰 저장 공간을 확인해주세요.");
    }

    @Override public void onOwnCommentRestored(String url, long publishedAtMillis) {
        OwnPostWatchQueue.restoreComment(this, url, publishedAtMillis);
    }

    private void showAccountProfiles() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = vertical(); panel.setTag("account-profiles"); panel.setPadding(dp(23), dp(10), dp(23), dp(18));
        scroll.addView(panel);
        boolean supported = AccountProfiles.supported();
        TextView explanation = text(supported
                ? "계정마다 WebView 로그인 공간을 따로 사용해요. 비밀번호를 앱에 저장하지 않으며 각 공간에서 직접 로그인합니다. 최대 5개까지 사용할 수 있어요."
                : "이 기기의 Android System WebView는 별도 계정 공간을 지원하지 않아요. 현재 계정은 계속 사용할 수 있습니다. WebView를 업데이트한 뒤 다시 확인해주세요.", 13, colors.text, false);
        explanation.setLineSpacing(dp(3), 1f); panel.addView(explanation);
        AlertDialog dialog = dialogBuilder().setTitle("간편 계정").setView(scroll).setPositiveButton("닫기", null).create();
        String active = AccountProfiles.activeId(this);
        List<AccountProfiles.Entry> entries = AccountProfiles.entries(this);
        for (AccountProfiles.Entry entry : entries) {
            LinearLayout row = horizontal(); row.setPadding(0, dp(8), 0, dp(8));
            TextView select = settingsRow(entry.label, active.equals(entry.id) ? "현재 계정" : "전환");
            select.setTag("account-switch-" + entry.id);
            select.setEnabled(supported || AccountProfiles.DEFAULT.equals(entry.id));
            select.setOnClickListener(v -> { dialog.dismiss(); switchAccountProfile(entry.id, false); });
            row.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
            TextView rename = plainAction("이름", "계정 이름 바꾸기", v -> { dialog.dismiss(); editAccountLabel(entry); });
            rename.setTag("account-rename-" + entry.id);
            row.addView(rename, new LinearLayout.LayoutParams(dp(56), dp(48)));
            panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView add = settingsRow("새 계정 공간 추가", "+");
        add.setTag("account-add"); add.setEnabled(supported && entries.size() < AccountProfiles.MAX_PROFILES);
        add.setAlpha(add.isEnabled() ? 1f : .5f);
        add.setOnClickListener(v -> { dialog.dismiss(); editAccountLabel(null); }); panel.addView(add);
        if (entries.size() >= AccountProfiles.MAX_PROFILES) panel.addView(text("계정 공간 5개를 모두 사용하고 있어요.", 12, colors.mutedText, false));
        TextView login = settingsRow("현재 계정에서 로그인", "›");
        login.setOnClickListener(v -> { dialog.dismiss(); openLogin(); }); panel.addView(login);
        showAdaptiveDialog(dialog, () -> { dialog.dismiss(); showAccountProfiles(); });
    }

    private void editAccountLabel(AccountProfiles.Entry entry) {
        EditText name = input("계정 공간 이름");
        name.setTag("account-label"); name.setSingleLine(true);
        name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(40)});
        name.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        if (entry != null) name.setText(entry.label);
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(10), dp(24), dp(12)); panel.addView(name);
        AlertDialog dialog = dialogBuilder().setTitle(entry == null ? "새 계정 공간" : "계정 이름 바꾸기").setView(panel)
                .setNegativeButton("취소", null).setPositiveButton(entry == null ? "추가하고 로그인" : "저장", null).create();
        dialog.show(); tintDialogActions(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) { name.setError("이름을 입력해주세요."); return; }
            if (entry == null) {
                AccountProfiles.Entry added = AccountProfiles.add(this, label);
                if (added == null) { name.setError("새 계정 공간을 추가하지 못했어요. WebView 지원 여부와 개수를 확인해주세요."); return; }
                dialog.dismiss(); switchAccountProfile(added.id, true);
            } else {
                if (!AccountProfiles.rename(this, entry.id, label)) { name.setError("이름을 저장하지 못했어요."); return; }
                dialog.dismiss(); showAccountProfiles();
            }
        });
    }

    private void switchAccountProfile(String profile, boolean loginAfterSwitch) {
        if (profile.equals(AccountProfiles.activeId(this))) { if (loginAfterSwitch) openLogin(); return; }
        final long request = ++accountSwitchGeneration;
        guardAllPanesAndSnapshot(() -> {
            if (request != accountSwitchGeneration || isFinishing() || isDestroyed()) return;
            if (!AccountProfiles.activate(this, profile)) { toast("계정을 전환하지 못했어요."); return; }
            if (loginAfterSwitch) preferences.edit().putString("profile.loginPending", profile).commit();
            recreate();
        });
    }

    private void showPushSettings() {
        if (isFinishing() || isDestroyed()) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = vertical();
        panel.setPadding(dp(23), dp(8), dp(23), dp(16));
        scroll.addView(panel);
        boolean enabled = PushClient.isEnabled(this);
        boolean paired = PushRelayClient.isPaired(this);
        String state = pushBusy ? "알림 연결을 확인하고 있어요" : !enabled || !CommunityOptions.autoWatchOwnPosts(this) ? "댓글 알림 꺼짐"
                : !PushNotifications.canNotify(this) ? "휴대폰에서 알림이 차단되어 있어요"
                : PushRelayClient.connectionStatus(this);
        panel.addView(text(state, 17, colors.text, true));
        TextView description = text("알림을 켜면 이 앱에서 작성한 글의 댓글과 내 댓글에 달린 답글을 자동으로 알려드려요. 글마다 등록하거나 별도 스위치를 켤 필요가 없어요. 서버가 주기적으로 확인하므로 알림은 늦어질 수 있습니다.", 13, colors.mutedText, false);
        description.setPadding(0, dp(12), 0, dp(12));
        description.setLineSpacing(dp(3), 1f);
        panel.addView(description);
        panel.addView(text(CommunityOptions.autoWatchOwnPosts(this) && enabled
                ? "내 글·댓글 알림 · 자동으로 받기" : "내 글·댓글 알림 · 꺼짐", 14, colors.text, true));
        long pendingPosts = OwnPostWatchQueue.pendingCount(this);
        if (pendingPosts > 0) panel.addView(text("연결을 기다리는 글·댓글 " + pendingPosts + "개 · 연결되면 자동으로 이어서 처리해요.", 12, colors.mutedText, false));
        if (!browser.supportsAutomaticPostTracking()) panel.addView(text("글·댓글 작성 감지를 위해 Android System WebView를 업데이트해 주세요.", 12, colors.accent, false));
        if (!pushStatus.isEmpty()) {
            TextView status = text(pushStatus, 13, colors.text, false);
            status.setPadding(0, 0, 0, dp(12));
            panel.addView(status);
        }
        if (!PushClient.isConfigured(this)) {
            panel.addView(text("이 빌드에는 푸시 연결 설정이 준비되지 않았어요.", 13, colors.mutedText, false));
        } else {
            TextView dataNotice = text("알림을 켜면 기기별 푸시 식별자가 Google과 발송 서버에 등록됩니다. 이 앱이 확인 메시지를 받아 자동으로 기기를 연결하며, 별도 코드나 계정 가입은 필요 없어요. 디시 비밀번호·로그인 쿠키는 보내지 않아요.", 12, colors.mutedText, false);
            dataNotice.setPadding(0, dp(8), 0, dp(12));
            panel.addView(dataNotice);
            panel.addView(pushAction(enabled && CommunityOptions.autoWatchOwnPosts(this) ? "알림 연결 다시 확인" : "댓글 알림 켜기", !pushBusy, this::beginPushEnable));
            if (enabled) {
                if (PushRelayClient.isConfigured(this)) {
                    if (!paired || PushRelayClient.isConnecting(this)) panel.addView(text(
                            "확인 메시지는 화면에 표시하지 않아요. 앱을 다시 열면 진행 중인 연결을 이어서 확인합니다.", 12, colors.mutedText, false));
                    panel.addView(pushAction("테스트 알림 받기", !pushBusy && paired && PushRelayClient.isConnectionReady(this)
                            && PushNotifications.canNotify(this), this::testPush));
                    if (paired) panel.addView(pushAction("최근 알림 글 보기", !pushBusy, () -> {
                        if (pushDialog != null) pushDialog.dismiss(); showCommentWatches();
                    }));
                } else panel.addView(text("발송 서버 연결이 아직 준비되지 않았어요.", 13, colors.mutedText, false));
            }
        }
        PushNotifications.TestStatus testStatus = PushNotifications.lastTestStatus(this);
        if (testStatus.receivedAtMillis > 0) panel.addView(text(
                "posted".equals(testStatus.resultCode) ? "테스트 알림 · 휴대폰 수신을 확인했어요."
                        : "테스트 알림은 수신됐어요. 휴대폰 알림 허용 상태를 확인해주세요.", 12, colors.mutedText, false));
        if (enabled) panel.addView(pushAction("알림 끄고 연결 해제", true, this::disablePush));
        panel.addView(pushAction("휴대폰 알림 설정", true, () -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (android.content.ActivityNotFoundException unavailable) { toast("휴대폰 설정에서 앱 알림을 확인해 주세요."); }
        }));
        AlertDialog dialog = dialogBuilder().setTitle("댓글 알림").setView(scroll).setPositiveButton("닫기", null).create();
        pushDialog = dialog;
        showAdaptiveDialog(dialog, () -> { dialog.dismiss(); showPushSettings(); }, () -> {
            if (pushDialog == dialog) pushDialog = null;
        });
    }

    private TextView pushAction(String label, boolean enabled, Runnable action) {
        TextView item = plainAction(label, label, v -> action.run());
        item.setMinHeight(dp(48));
        item.setEnabled(enabled);
        item.setAlpha(enabled ? 1f : .45f);
        return item;
    }

    private long startPushAction() {
        pushBusy = true;
        long request = ++pushUiGeneration;
        if (pushTimeout != null) root.removeCallbacks(pushTimeout);
        pushTimeout = () -> finishPushAction(request, PushRelayClient.isConnecting(this)
                ? PushRelayClient.connectionStatus(this) : "확인에 시간이 걸리고 있어요. 네트워크를 확인한 뒤 다시 시도해 주세요.");
        root.postDelayed(pushTimeout, 90_000);
        if (pushDialog != null && pushDialog.isShowing()) { pushDialog.dismiss(); showPushSettings(); }
        return request;
    }

    private void finishPushAction(long request, String message) {
        if (request != pushUiGeneration || isFinishing() || isDestroyed()) return;
        if (pushTimeout != null) { root.removeCallbacks(pushTimeout); pushTimeout = null; }
        pushBusy = false;
        pushStatus = message;
        if (pushDialog != null && pushDialog.isShowing()) { pushDialog.dismiss(); showPushSettings(); }
    }

    private void beginPushEnable() {
        if (pushBusy) return;
        if (!CommunityOptions.autoWatchOwnPosts(this)) { showNotificationConsent(); return; }
        if (Build.VERSION.SDK_INT >= 33 && !PushNotifications.hasPermission(this)) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, PUSH_PERMISSION_REQUEST);
            return;
        }
        long request = startPushAction();
        PushRelayClient.requestConnection(this);
        PushClient.enable(this, new PushClient.TokenCallback() {
            @Override public void onToken(String token) {
                PushRelayClient.connect(getApplicationContext(), token,
                        request == pushUiGeneration && !isFinishing() && !isDestroyed() ? pushRelayResult(request) : null);
            }
            @Override public void onError(String message) { finishPushAction(request, message); }
        });
    }

    private PushRelayClient.Callback pushRelayResult(long request) {
        return new PushRelayClient.Callback() {
            @Override public void onSuccess(String message) { finishPushAction(request, message); if (!isFinishing() && !isDestroyed()) refreshCommunityOptions(); }
            @Override public void onError(String message) { finishPushAction(request, message); }
        };
    }

    private void testPush() {
        if (pushBusy) return;
        long request = startPushAction();
        PushClient.getToken(this, new PushClient.TokenCallback() {
            @Override public void onToken(String token) {
                if (request != pushUiGeneration || isFinishing() || isDestroyed()) return;
                PushRelayClient.connect(MainActivity.this, token, new PushRelayClient.Callback() {
                    @Override public void onSuccess(String message) {
                        if (request == pushUiGeneration && !isFinishing() && !isDestroyed())
                            PushRelayClient.test(MainActivity.this, pushRelayResult(request));
                    }
                    @Override public void onError(String message) { finishPushAction(request, message); }
                });
            }
            @Override public void onError(String message) { finishPushAction(request, message); }
        });
    }

    private void disablePush() {
        long request = startPushAction();
        CommunityOptions.setAutoWatchOwnPosts(this, false);
        PushClient.disable(this);
        PushRelayClient.disconnect(this, pushRelayResult(request));
    }

    private void maybeShowNotificationConsent() {
        if (!activityResumed || isFinishing() || isDestroyed() || notificationConsentDialog != null
                || adaptiveDialog != null && adaptiveDialog.isShowing()
                || !PushClient.isConfigured(this) || !PushRelayClient.isConfigured(this)
                || !NotificationConsent.needsPrompt(this)) return;
        showNotificationConsent();
    }

    private void showNotificationConsent() {
        if (isFinishing() || isDestroyed() || notificationConsentDialog != null) return;
        if (pushDialog != null) pushDialog.dismiss();
        AlertDialog dialog = dialogBuilder().setTitle("글과 댓글의 새 답글 알림을 받을까요?")
                .setMessage("한 번 켜면 이 앱에서 작성한 글의 새 댓글과 내가 쓴 댓글의 답글 알림을 각각 작성 후 3일간 자동으로 받아요. 글마다 등록할 필요는 없어요.\n\n"
                        + "기기 연결 정보는 Google과 알림 서버에, 작성한 공개 글·댓글의 주소와 댓글 번호·새 답글 확인 정보는 알림 서버에 저장됩니다. 본문·닉네임·로그인 정보는 보내지 않아요.\n\n"
                        + "댓글을 주기적으로 확인하므로 알림이 늦어질 수 있고, 설정에서 언제든 끌 수 있어요.")
                .setNegativeButton("나중에", (d, w) -> NotificationConsent.postpone(this))
                .setPositiveButton("알림 받기", (d, w) -> {
                    if (!NotificationConsent.accept(this)) { toast("알림 설정을 저장하지 못했어요."); return; }
                    notificationConsentDialog = null;
                    root.post(this::beginPushEnable);
                }).create();
        notificationConsentDialog = dialog;
        dialog.setOnCancelListener(ignored -> NotificationConsent.postpone(this));
        showAdaptiveDialog(dialog, () -> { notificationConsentDialog = null; dialog.dismiss(); showNotificationConsent(); }, () -> {
            if (notificationConsentDialog == dialog) notificationConsentDialog = null;
        });
    }

    private void resumePushConnection() {
        if (!PushClient.isEnabled(this) || !PushClient.isConfigured(this) || !PushNotifications.hasPermission(this)) return;
        PushRelayClient.resumeRegistration(this);
        PushClient.getToken(this, new PushClient.TokenCallback() {
            @Override public void onToken(String token) { PushRelayClient.refreshToken(getApplicationContext(), token); }
            @Override public void onError(String ignored) { /* The explicit settings action can display a retry message. */ }
        });
        if (pushDialog != null && pushDialog.isShowing()) {
            if (PushRelayClient.isConnecting(this)) {
                long request = startPushAction(); PushRelayClient.observeConnection(this, pushRelayResult(request));
            } else { pushDialog.dismiss(); showPushSettings(); }
        }
    }

    private TextView settingsRow(String title, String value) {
        TextView row = text(title + "\n" + value, 14, colors.text, true);
        row.setLineSpacing(dp(9), 1);
        row.setPadding(0, dp(17), 0, dp(19));
        row.setMinHeight(dp(64));
        return row;
    }

    private Switch settingSwitch(String title, boolean checked) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextSize(13 * fontScale);
        control.setTextColor(colors.text);
        control.setPadding(0, dp(14), 0, dp(14));
        control.setMinHeight(dp(63));
        control.setChecked(checked);
        control.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{colors.accent, colors.mutedText}));
        return control;
    }

    private void finishLauncherChange(android.widget.CompoundButton originatingControl) {
        launcherChangeGeneration++;
        if (launcherTimeout != null) root.removeCallbacks(launcherTimeout);
        launcherTimeout = null;
        pendingLauncherValue = matchLauncher;
        originatingControl.setChecked(matchLauncher);
        originatingControl.setEnabled(true);
        // A fold/font change may have replaced the settings dialog while its
        // draft snapshot was waiting for JavaScript.
        if (adaptiveDialog != null && adaptiveDialog.isShowing()) {
            Switch current = adaptiveDialog.getWindow().getDecorView().findViewWithTag("launcher-setting");
            if (current != null) {
                current.setChecked(matchLauncher);
                current.setEnabled(true);
            }
        }
        launcherChangePending = false;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (browserVisible) {
            menu.getMenu().add(0, 1, 0, "뒤로 가기").setEnabled(detailOpen || currentBrowser().canGoBack());
            menu.getMenu().add(0, 2, 1, "새로고침");
            menu.getMenu().add(0, 3, 2, currentBrowser().isOriginalMode() ? "테마 화면 보기" : "원본 화면 보기");
            menu.getMenu().add(0, 4, 3, "현재 페이지 저장");
            menu.getMenu().add(0, 5, 4, "페이지 주소 공유");
        }
        menu.getMenu().add(0, 6, 5, "로그인");
        menu.getMenu().add(0, 7, 6, "화면 스타일");
        menu.getMenu().add(0, 8, 7, "환경 설정");
        menu.getMenu().add(0, 9, 8, "앱 정보 · 개인정보");
        menu.getMenu().add(0, 10, 9, "글쓰기");
        menu.getMenu().add(0, 11, 10, "커뮤니티 도구");
        menu.getMenu().add(0, 12, 11, "간편 계정");
        menu.getMenu().add(0, 13, 12, "댓글 알림 관리");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: navigateBack(); break;
                case 2: guardComposerNavigation(() -> currentBrowser().reload()); break;
                case 3: currentBrowser().setOriginalMode(!currentBrowser().isOriginalMode()); updateHeader(); break;
                case 4: saveCurrent(); break;
                case 5:
                    String url = currentBrowser().getUrl();
                    if (url != null && url.startsWith("https://")) {
                        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
                        startActivity(Intent.createChooser(share, "페이지 주소 공유"));
                    }
                    break;
                case 6: openLogin(); break;
                case 7: showThemes(); break;
                case 8: showSettings(); break;
                case 9: showAbout(); break;
                case 10: compose(); break;
                case 11: showCommunityTools(); break;
                case 12: showAccountProfiles(); break;
                case 13: showCommentWatches(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void showMailDrawer() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = vertical();
        panel.setTag("mail-navigation-drawer");
        panel.setPadding(dp(12), dp(24), dp(12), dp(20));
        panel.setBackgroundColor(colors.surface);
        TextView title = text("메일함", 25, colors.text, true);
        title.setPadding(dp(16), 0, 0, dp(24));
        panel.addView(title);
        scroll.addView(panel);
        AlertDialog dialog = dialogBuilder().setView(scroll).create();
        mailDrawer = dialog;
        addDrawerAction(panel, dialog, "inbox", "전체보관함", this::showHome);
        addDrawerAction(panel, dialog, "search", "메일 검색", this::showExplore);
        addDrawerAction(panel, dialog, "edit", "편지쓰기", this::compose);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        TextView label = text("라벨", 11, colors.mutedText, true);
        label.setPadding(dp(16), dp(22), dp(16), dp(8));
        panel.addView(label);
        if (accountFavorites.isEmpty()) {
            addDrawerAction(panel, dialog, "account", "로그인하고 목록 불러오기", this::openLogin);
        } else for (Favorite favorite : accountFavorites) {
            addDrawerAction(panel, dialog, "folder", theme.labelFor(favorite.name), () -> navigate(favorite.url));
        }
        addDrawerAction(panel, dialog, "bookmark", "원본 즐겨찾기 관리", this::openAccountFavorites);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        if (browserVisible) {
            addDrawerAction(panel, dialog, "back", "뒤로 가기", this::navigateBack);
            addDrawerAction(panel, dialog, "refresh", "새로고침", () -> guardComposerNavigation(() -> currentBrowser().reload()));
            addDrawerAction(panel, dialog, "page", currentBrowser().isOriginalMode() ? "테마 화면 보기" : "원본 화면 보기", () -> {
                currentBrowser().setOriginalMode(!currentBrowser().isOriginalMode()); updateHeader();
            });
            addDrawerAction(panel, dialog, "bookmark", "현재 페이지 저장", this::saveCurrent);
            addDrawerAction(panel, dialog, "share", "페이지 주소 공유", this::shareCurrent);
        }
        addDrawerAction(panel, dialog, "grid", "화면 스타일", this::showThemes);
        addDrawerAction(panel, dialog, "settings", "환경 설정", this::showSettings);
        addDrawerAction(panel, dialog, "settings", "커뮤니티 도구", this::showCommunityTools);
        addDrawerAction(panel, dialog, "account", "간편 계정", this::showAccountProfiles);
        addDrawerAction(panel, dialog, "inbox", "댓글 알림 관리", this::showCommentWatches);
        addDrawerAction(panel, dialog, "account", "로그인", this::openLogin);
        addDrawerAction(panel, dialog, "page", "앱 정보 · 개인정보", this::showAbout);
        showAdaptiveDialog(dialog, () -> { dialog.dismiss(); showMailDrawer(); });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(colors.surface));
            dialog.getWindow().setGravity(Gravity.START | Gravity.TOP);
            dialog.getWindow().setLayout(dp(Math.min(360, Math.max(200, getResources().getConfiguration().screenWidthDp - 24))), -1);
        }
    }

    private void addDrawerAction(LinearLayout panel, AlertDialog owner, String glyph, String label, Runnable action) {
        LinearLayout row = horizontal();
        row.setContentDescription(label);
        row.setFocusable(true);
        row.setMinimumHeight(dp(52));
        row.setPadding(dp(16), dp(13), dp(12), dp(13));
        row.addView(new UiGlyphView(this, glyph, colors.mutedText), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView title = text(label, 14, colors.text, false);
        title.setPadding(dp(22), 0, 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { owner.dismiss(); action.run(); });
        panel.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void shareCurrent() {
        String url = currentBrowser().getUrl();
        if (url != null && url.startsWith("https://")) {
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
            startActivity(Intent.createChooser(share, "페이지 주소 공유"));
        }
    }

    private void saveCurrent() {
        String url = currentBrowser().getUrl();
        if (url == null || url.isEmpty()) return;
        if (CommunityStore.canonicalArticleUrl(url) != null) {
            CommunityStore.saveArticle(this, url, currentBrowser().getTitle());
            toast("글 보관함에 저장했어요");
            return;
        }
        for (Favorite favorite : favorites) {
            if (favorite.url.equals(url)) { toast("이미 저장되어 있어요"); return; }
        }
        EditText name = input("목록에 표시할 이름");
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(24), dp(10), dp(24), dp(10));
        wrapper.addView(name);
        AlertDialog dialog = dialogBuilder().setTitle("관심 " + theme.groupNoun + " 저장").setView(wrapper)
                .setNegativeButton("취소", null).setPositiveButton("저장", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty()) { name.setError("표시할 이름을 입력하세요"); return; }
            favorites.add(new Favorite(title, url));
            saveFavorites();
            rebuildHome();
            dialog.dismiss();
            toast("홈 화면에 저장했어요");
        }));
        dialog.show();
        tintDialogActions(dialog);
    }

    private void renameFavorite(Favorite favorite) {
        EditText input = input("표시할 이름");
        input.setText(favorite.name);
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(24), dp(10), dp(24), dp(10));
        wrapper.addView(input);
        AlertDialog dialog = dialogBuilder().setTitle("이름 바꾸기").setView(wrapper)
                .setNegativeButton("취소", null).setPositiveButton("저장", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("이름을 입력하세요"); return; }
            favorite.name = name;
            saveFavorites();
            rebuildHome();
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showAbout() {
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        String details = "모아 " + version + " · 개발 미리보기\n\n"
                + "연결한 커뮤니티의 화면 배치를 선택한 스타일로 바꾸는 테마 뷰어입니다. 연결 서비스와 콘텐츠 출처는 디시인사이드입니다. 디시인사이드 및 각 테마에서 참고한 서비스의 공식 앱이나 제휴 앱이 아닙니다.\n\n"
                + "알려진 광고 영역과 게시판 아래의 외부 추천·검색 영역은 화면에서 숨깁니다. 원본 보기에서도 같은 정리가 적용됩니다. 광고의 네트워크 요청이나 추적을 모두 차단하는 기능은 아닙니다.\n\n"
                + "글·댓글·추천 등은 원본 서비스의 기능입니다. 테마는 화면 표현을 바꾸며 공개 범위와 이용 권한은 바꾸지 않습니다. 지원이 어려운 페이지는 원본 보기로 이용할 수 있습니다.\n\n"
                + "개인정보와 기기 저장\n"
                + "선택한 테마, 글자 크기, 직접 저장한 페이지, 차단 규칙, 이용자 메모, 글 보관함, 최근 본 글과 작성 문구는 이 기기에 저장됩니다. 최근 기록은 도구에서 끄거나 삭제할 수 있습니다. 작성 중인 텍스트 초안도 복원을 위해 기기에 임시 저장될 수 있습니다. 비밀번호와 첨부파일은 초안에 저장하지 않습니다. 간편 계정은 지원되는 WebView의 별도 로그인 공간을 사용하며 앱에는 공간 이름만 저장합니다. 로그인 쿠키와 웹 저장소는 Android WebView에 저장되고 로그인 정보·작성 내용은 해당 서비스로 전달됩니다. 앱은 별도의 계정 서버나 자체 분석 도구를 사용하지 않습니다.\n\n"
                + "계정 즐겨찾기는 로그인한 서비스에서 불러옵니다. 이 목록을 별도 파일로 저장하지 않으며, 로그아웃 상태가 확인되거나 새로 불러오기에 실패하면 홈의 계정 목록을 비웁니다. 직접 저장한 페이지는 계정 즐겨찾기와 분리하여 이 기기에 보관합니다.\n\n"
                + "푸시 알림은 선택 기능입니다. 켜면 Google FCM에 기기별 식별자가 등록되고, 기기 수신 확인을 거친 자동 연결 시 Cloudflare 발송 서버에 푸시 토큰과 임의의 기기 연결 정보가 저장됩니다. 알림에 동의한 뒤 이 앱에서 작성한 글·댓글은 각각 작성 후 3일간 자동으로 등록되며, 공개 글·댓글 주소, 댓글 번호와 최신 답글 번호 등 확인 정보가 서버에 저장됩니다. 이전 글이나 댓글도 직접 추가할 수 있습니다. 글 단위로 묶어 2분 30초를 기준으로 확인합니다. 분 단위 예약으로 실제 확인 간격은 보통 약 3분이며, 알림 배분·발송이나 혼잡으로 더 늦어질 수 있습니다. 디시 로그인 비밀번호·쿠키와 비공개 글은 서버에 전달하지 않습니다. 공개 글을 서버가 조회하므로 해당 사이트에 서버의 접근 기록이 남을 수 있습니다. 즉시 알림은 아니며 네트워크·사이트 제한에 따라 지연되거나 중단될 수 있습니다. 알림을 끄면 기기에서 표시를 중단하고 토큰 폐기·서버 연결 삭제를 요청합니다. 통신 실패 시 서버 삭제는 다음 연결 때 재시도합니다.\n\n"
                + "사진을 첨부할 때 Android 파일 선택창에서 직접 고릅니다. 원본 페이지가 외부 서비스를 포함하면 해당 서비스의 개인정보 처리방침이 적용됩니다.\n\n"
                + "테마를 바꿔도 열린 페이지는 다시 불러오지 않습니다. 앱 강제 종료·기기 재시작 뒤 작성 중 내용 복구는 보장되지 않으니 긴 글은 등록 전 별도로 보관해 주세요.\n\n"
                + "콘텐츠 출처: dcinside.com\n화면 스타일 참고: Gmail · X · Instagram · Excel · 네이버 카페\n각 서비스의 공식 앱이 아닌 모아 테마 뷰어입니다.";
        ScrollView scroll = new ScrollView(this);
        TextView body = text(details, 13, colors.text, false);
        body.setPadding(dp(24), dp(10), dp(24), dp(20));
        body.setLineSpacing(dp(4), 1f);
        scroll.addView(body);
        dialogBuilder().setTitle("앱 정보 · 개인정보").setView(scroll)
                .setPositiveButton("확인", null).show();
    }

    private void loadFavorites() {
        String saved = preferences.getString("favorites", null);
        if (saved == null) return;
        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                String name = object.optString("name");
                String url = object.optString("url");
                if (!name.isEmpty() && UrlPolicy.isDcHttps(url)) favorites.add(new Favorite(name, url));
            }
        } catch (Exception ignored) { favorites.clear(); }
    }

    private void saveFavorites() {
        JSONArray array = new JSONArray();
        for (Favorite favorite : favorites) {
            try {
                JSONObject object = new JSONObject();
                object.put("name", favorite.name);
                object.put("url", favorite.url);
                array.put(object);
            } catch (Exception ignored) { }
        }
        preferences.edit().putString("favorites", array.toString()).apply();
    }

    @Override public void onPageState(String title, String url, boolean isLoading) {
        onPanePageState(browser, title, url, isLoading);
    }

    private void onPanePageState(BrowserController source, String title, String url, boolean isLoading) {
        runOnUiThread(() -> {
            if (isDestroyed() || source == null || (source != browser && source != detailBrowser)) return;
            if (source == browser) primaryLoading = isLoading; else detailLoading = isLoading;
            if (source != currentBrowser()) return;
            pageTitle = title == null ? "" : title;
            pageUrl = url == null ? "" : url;
            loading = isLoading;
            if (isLoading) {
                latestError = null;
                if (errorBanner != null) errorBanner.setVisibility(View.GONE);
            }
            if (progress != null) progress.setVisibility(isLoading && browserVisible ? View.VISIBLE : View.INVISIBLE);
            updateComposeActionVisibility();
            updateHeader();
            if (!isLoading && !pendingComposerTarget.isEmpty()
                    && pendingComposerTarget.equals(UrlPolicy.galleryId(pageUrl))) {
                pendingComposerTarget = "";
                source.openComposer();
            }
        });
    }

    @Override public void onError(String message) {
        onPaneError(browser, message);
    }

    private void onPaneError(BrowserController source, String message) {
        runOnUiThread(() -> {
            if (source != null && source != currentBrowser()) return;
            latestError = message;
            pendingComposerTarget = "";
            loading = false;
            if (progress != null) progress.setVisibility(View.INVISIBLE);
            if (errorText != null) errorText.setText(message);
            if (errorBanner != null) errorBanner.setVisibility(browserVisible ? View.VISIBLE : View.GONE);
            updateComposeActionVisibility();
            if (!browserVisible) toast(message);
        });
    }

    @Override public void onAccountFavorites(String state, List<BrowserController.FavoriteEntry> items) {
        runOnUiThread(() -> {
            accountState = state == null ? "error" : state;
            accountFavorites.clear();
            if ("ready".equals(accountState) && items != null) {
                for (BrowserController.FavoriteEntry item : items) {
                    if (item != null && item.name != null && !item.name.trim().isEmpty()
                            && UrlPolicy.isDcHttps(item.url)) {
                        accountFavorites.add(new Favorite(item.name.trim(), item.url));
                    }
                }
                if (accountFavorites.isEmpty()) accountState = "emptyOrUnavailable";
            }
            if (content != null && browserView != null) rebuildHome();
            if (mailDrawer != null && mailDrawer.isShowing()) {
                mailDrawer.dismiss();
                showMailDrawer();
            }
            updateHeader();
        });
    }

    private void updateHeader() {
        // Community titles belong to the compact web page header. Native
        // chrome retains only the selected app identity and real actions.
        setTitle(theme.displayName);
    }
    private void openLogin() {
        guardAllPanesAndSnapshot(() -> {
            pendingComposerTarget = "";
            setBrowserVisible(true);
            hideComposeForNavigation();
            currentBrowser().open(LOGIN_URL);
        });
    }

    private void openAccountFavorites() {
        guardComposerNavigation(() -> {
            pendingComposerTarget = "";
            setBrowserVisible(true);
            hideComposeForNavigation();
            currentBrowser().openAccountFavorites();
        });
    }

    private void updateSystemColors() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(colors.surface);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        if (dark) flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int appearances = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : appearances, appearances);
        }
    }

    private void navigateBack() {
        if (browserVisible) {
            if (detailOpen && detailBrowser != null) {
                BrowserController detail = detailBrowser;
                detail.guardNavigation(() -> detail.snapshotDraft(this::discardDetailBrowser), () -> setBrowserVisible(true));
            } else if (browser.canGoBack()) guardComposerNavigation(() -> browser.goBack());
            else showHome();
        } else if (hasOpenPage()) guardAllPanesAndSnapshot(this::finish);
        else finish();
    }

    @Override public void onBackPressed() { navigateBack(); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (detailBrowser != null && detailBrowser.ownsActivityResult(requestCode)
                && detailBrowser.onActivityResult(requestCode, resultCode, data)) return;
        if (browser.ownsActivityResult(requestCode) && browser.onActivityResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PUSH_PERMISSION_REQUEST) {
            if (PushNotifications.hasPermission(this)) beginPushEnable();
            else {
                CommunityOptions.setAutoWatchOwnPosts(this, false);
                pushStatus = "알림 권한이 허용되지 않았어요. 필요할 때 휴대폰 알림 설정에서 변경할 수 있어요.";
                if (pushDialog != null && pushDialog.isShowing()) { pushDialog.dismiss(); showPushSettings(); }
            }
            return;
        }
        if (detailBrowser != null && detailBrowser.ownsPermissionRequest(requestCode)
                && detailBrowser.onRequestPermissionsResult(requestCode, permissions, grantResults)) return;
        if (browser.ownsPermissionRequest(requestCode)
                && browser.onRequestPermissionsResult(requestCode, permissions, grantResults)) return;
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("ui.browserVisible", browserVisible);
        state.putBoolean("ui.originalMode", browser.isOriginalMode());
        browser.onSaveInstanceState(state);
        state.putBoolean("ui.detailOpen", detailOpen && detailBrowser != null);
        if (detailOpen && detailBrowser != null) {
            Bundle detailState = new Bundle();
            detailState.putBoolean("ui.originalMode", detailBrowser.isOriginalMode());
            detailBrowser.onSaveInstanceState(detailState);
            state.putBundle("ui.detailState", detailState);
        }
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() {
        PushRelayClient.cancelConnectionObservers(); pushUiGeneration++; pushBusy = false;
        if (pushTimeout != null && root != null) { root.removeCallbacks(pushTimeout); pushTimeout = null; }
        activityResumed = false;
        if (browser != null) browser.onPause(); primaryResumed = false;
        if (detailBrowser != null) detailBrowser.onPause(); detailResumed = false;
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); activityResumed = true; applyPaneLayout(); resumePushConnection();
        root.post(this::maybeShowNotificationConsent);
    }
    @Override protected void onDestroy() {
        pushUiGeneration++;
        PushRelayClient.cancelConnectionObservers();
        if (pushTimeout != null && root != null) { root.removeCallbacks(pushTimeout); pushTimeout = null; }
        if (pushDialog != null) { pushDialog.dismiss(); pushDialog = null; }
        if (notificationConsentDialog != null) { notificationConsentDialog.dismiss(); notificationConsentDialog = null; }
        accountSwitchGeneration++;
        if (communityDialog != null) { communityDialog.dismiss(); communityDialog = null; }
        if (commentWatchDialog != null) { commentWatchDialog.dismiss(); commentWatchDialog = null; }
        if (userActionDialog != null) { userActionDialog.dismiss(); userActionDialog = null; }
        launcherChangeGeneration++;
        launcherChangePending = false;
        if (launcherTimeout != null && root != null) root.removeCallbacks(launcherTimeout);
        launcherTimeout = null;
        if (adaptiveDialog != null) {
            adaptiveDialog.setOnDismissListener(null);
            adaptiveDialog.dismiss();
            adaptiveDialog = null;
            rebuildDialog = null;
        }
        if (browser != null) browser.destroy();
        if (detailBrowser != null) detailBrowser.destroy();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (browser == null || root == null) return;
        // Folding, multi-window resizing and display/font size changes must
        // remeasure native pixels without recreating the live editor WebView.
        // That preserves DOM text, attachment inputs and pending file callbacks.
        int scrollY = homeView == null ? 0 : homeView.getScrollY();
        Runnable restoreDialog = adaptiveDialog != null && adaptiveDialog.isShowing() ? rebuildDialog : null;
        buildChrome();
        applyPaneLayout();
        if (communityDialog != null) communityDialog.resize();
        if (commentWatchDialog != null) commentWatchDialog.resize();
        root.requestApplyInsets();
        if (homeView != null) homeView.post(() -> homeView.scrollTo(0, scrollY));
        if (restoreDialog != null) restoreDialog.run();
    }

    private boolean compactLayout() {
        Configuration configuration = getResources().getConfiguration();
        return configuration.screenWidthDp < 360 || configuration.fontScale * fontScale > 1.5f;
    }

    private void showAdaptiveDialog(AlertDialog dialog, Runnable rebuild) {
        showAdaptiveDialog(dialog, rebuild, null);
    }

    private void showAdaptiveDialog(AlertDialog dialog, Runnable rebuild, Runnable dismissed) {
        adaptiveDialog = dialog;
        rebuildDialog = rebuild;
        dialog.setOnDismissListener(ignored -> {
            if (adaptiveDialog == dialog) {
                adaptiveDialog = null;
                rebuildDialog = null;
            }
            if (dismissed != null) dismissed.run();
        });
        dialog.show();
        tintDialogActions(dialog);
        // Android's regular floating dialog width is too generous in a split
        // window and unnecessarily wide on tablets. Keep a readable form width.
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(
                dp(Math.min(560, Math.max(200, getResources().getConfiguration().screenWidthDp - 32))), -2);
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this, dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert) {
            @Override public AlertDialog show() {
                AlertDialog dialog = super.show();
                tintDialogActions(dialog);
                return dialog;
            }
        };
    }

    private void tintDialogActions(AlertDialog dialog) {
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            if (dialog.getButton(which) != null) dialog.getButton(which).setTextColor(colors.accent);
        }
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size * fontScale);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView plainAction(String value, String description, View.OnClickListener click) {
        TextView view = text(value, 19, colors.text, false);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 19);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setOnClickListener(click);
        return view;
    }

    private EditText input(String hint) {
        EditText input = new EditText(this);
        input.setTag("text-input");
        input.setTextSize(14 * fontScale);
        input.setTextColor(colors.text);
        input.setHintTextColor(colors.mutedText);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setMinHeight(dp(48));
        input.setPadding(dp(13), dp(11), dp(13), dp(11));
        input.setBackground(round(colors.background, 12, colors.border));
        return input;
    }

    private View line() {
        View line = new View(this);
        line.setBackgroundColor(colors.border);
        return line;
    }

    private GradientDrawable round(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (border != Color.TRANSPARENT) drawable.setStroke(dp(1), border);
        return drawable;
    }

    private int alpha(int color, float alpha) {
        return Color.argb(Math.round(alpha * 255), Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private static final class Favorite {
        String name;
        final String url;
        Favorite(String name, String url) { this.name = name; this.url = url; }
    }
}
