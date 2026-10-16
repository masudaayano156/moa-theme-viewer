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
    private LinearLayout root;
    private FrameLayout content;
    private View browserView;
    private ScrollView homeView;
    private ProgressBar progress;
    private LinearLayout errorBanner;
    private TextView errorText;
    private LinearLayout bottomNavigation;
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
        colors = theme.palette(dark);
        loadFavorites();
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
        content.addView(browserView, new FrameLayout.LayoutParams(-1, -1));
        browser.setTheme(theme, dark);
        browser.setFontScale(fontScale);
        setContentView(root);
        buildChrome();
        boolean restored = savedInstanceState != null && browser.onRestoreInstanceState(savedInstanceState);
        if (restored) {
            browser.setOriginalMode(savedInstanceState.getBoolean("ui.originalMode", false));
            pageUrl = browser.getUrl() == null ? "" : browser.getUrl();
            setBrowserVisible(savedInstanceState.getBoolean("ui.browserVisible", false));
        } else {
            setBrowserVisible(false);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        browser.refreshAccountFavorites();
    }

    private void buildChrome() {
        colors = theme.palette(dark);
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

    private View buildHeader() {
        LinearLayout header = horizontal();
        header.setTag("header-" + theme.id);
        header.setPadding(dp(theme == ThemeSpec.MAIL ? 12 : 8), dp(4), dp(theme == ThemeSpec.MAIL ? 12 : 8), dp(4));
        header.setMinimumHeight(dp(theme == ThemeSpec.MAIL ? 64 : 56));
        header.setBackgroundColor(colors.surface);
        if (theme == ThemeSpec.MAIL) {
            LinearLayout searchBar = horizontal();
            searchBar.setTag("mail-search-bar");
            searchBar.setBackground(round(dark ? 0xFF293544 : 0xFFEAF1FB, 28, Color.TRANSPARENT));
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
            FrameLayout home = headerAction("home", theme.displayName + " 홈", v -> showHome());
            home.setBackground(round(dark ? 0xFF24282C : 0xFFEFF3F4, 24, Color.TRANSPARENT));
            header.addView(home, new LinearLayout.LayoutParams(dp(48), dp(48)));
            FrameLayout center = new FrameLayout(this);
            center.setTag("post-centered-mark");
            ImageView mark = new ImageView(this);
            mark.setImageResource(theme.iconRes);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            center.addView(mark, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            header.addView(center, new LinearLayout.LayoutParams(0, dp(48), 1));
            header.addView(headerAction("menu", "메뉴", this::showMenu), new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else {
            TextView title = text(theme == ThemeSpec.PHOTOGRAM ? "Photogram" : theme.displayName,
                    theme == ThemeSpec.PHOTOGRAM ? 28 : theme == ThemeSpec.SHEET ? 18 : 24,
                    theme == ThemeSpec.CAFE || theme == ThemeSpec.SHEET ? colors.accent : colors.text, true);
            title.setTag("header-wordmark");
            if (theme == ThemeSpec.PHOTOGRAM) title.setTypeface(Typeface.create("cursive", Typeface.NORMAL));
            if (theme == ThemeSpec.EBOOK || theme == ThemeSpec.MAGAZINE) title.setTypeface(Typeface.SERIF, Typeface.BOLD);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER_VERTICAL);
            title.setMinHeight(dp(48));
            title.setPadding(dp(8), 0, dp(8), 0);
            title.setContentDescription(theme.displayName + " 홈");
            title.setOnClickListener(v -> showHome());
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(headerAction("search", theme.searchHint, v -> showExplore()), new LinearLayout.LayoutParams(dp(48), dp(48)));
            header.addView(headerAction("menu", "메뉴", this::showMenu), new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        return header;
    }

    private FrameLayout headerAction(String glyph, String description, View.OnClickListener click) {
        FrameLayout action = new FrameLayout(this);
        action.setContentDescription(description);
        action.setFocusable(true);
        action.setOnClickListener(click);
        UiGlyphView icon = new UiGlyphView(this, glyph, colors.text);
        action.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        return action;
    }

    private View buildNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setTag("navigation-" + theme.id);
        bottomNavigation = navigation;
        navigation.setGravity(Gravity.CENTER);
        navigation.setBackgroundColor(theme == ThemeSpec.MAIL ? (dark ? 0xFF1B2633 : 0xFFF0F4F9) : colors.surface);
        navigation.setPadding(dp(8), dp(3), dp(8), dp(3));
        switch (theme) {
            case MAIL:
                navigation.setTag("mail-action-dock");
                addNav(navigation, "inbox", "홈", !browserVisible, v -> showHome());
                TextView compose = text("✎  편지 쓰기", 14, colors.text, true);
                compose.setContentDescription("글쓰기");
                compose.setGravity(Gravity.CENTER);
                compose.setMinHeight(dp(48));
                compose.setPadding(dp(14), dp(10), dp(14), dp(10));
                compose.setBackground(round(dark ? 0xFF374E6B : 0xFFD3E3FD, 18, Color.TRANSPARENT));
                compose.setOnClickListener(v -> compose());
                navigation.addView(compose, new LinearLayout.LayoutParams(0, -2, 1));
                break;
            case POST:
            case PHOTOGRAM:
                addNav(navigation, "home", "홈", !browserVisible, v -> showHome());
                addNav(navigation, "search", "탐색", false, v -> showExplore());
                addNav(navigation, theme == ThemeSpec.PHOTOGRAM ? "add" : "edit", "글쓰기", false, v -> compose());
                addNav(navigation, "bookmark", "계정 즐겨찾기", false, v -> openAccountFavorites());
                addNav(navigation, "settings", "설정", false, v -> showSettings());
                break;
            case SHEET:
                navigation.setTag("sheet-tabs");
                addNav(navigation, "grid", "홈", !browserVisible, v -> showHome());
                addNav(navigation, "add", "글쓰기", false, v -> compose());
                addNav(navigation, "settings", "설정", false, v -> showSettings());
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
        int tint = active ? colors.accent : colors.text;
        if (theme == ThemeSpec.MAIL) {
            FrameLayout capsule = new FrameLayout(this);
            if (active) capsule.setBackground(round(dark ? 0xFF374E6B : 0xFFD3E3FD, 16, Color.TRANSPARENT));
            capsule.addView(new UiGlyphView(this, symbol, tint), new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
            item.addView(capsule, new LinearLayout.LayoutParams(dp(48), dp(28)));
        } else if (!textOnly) {
            item.addView(new UiGlyphView(this, symbol, tint), new LinearLayout.LayoutParams(dp(25), dp(25)));
        }
        if (!iconOnly) {
            String shown = label;
            if (label.equals("홈")) shown = theme == ThemeSpec.MAIL ? "주제함" : theme == ThemeSpec.SHEET ? "시트 목록"
                    : theme == ThemeSpec.EBOOK ? "서가" : theme == ThemeSpec.CAFE ? "내 게시판" : theme == ThemeSpec.MAGAZINE ? "섹션" : label;
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
        homeView.setBackgroundColor(colors.background);
        FrameLayout homeFrame = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (getChildCount() > 0) getChildAt(0).getLayoutParams().width = Math.min(dp(720), View.MeasureSpec.getSize(widthSpec));
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        LinearLayout home = vertical();
        home.setTag("home-content");
        int gutter = theme == ThemeSpec.MAIL || theme == ThemeSpec.POST || theme == ThemeSpec.SHEET ? 8 : compactLayout() ? 16 : 20;
        home.setPadding(dp(gutter), dp(10), dp(gutter), dp(20));
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
        section.addView(text(favoritesHeading(), 18, colors.text, true), new LinearLayout.LayoutParams(compactLayout() ? -1 : 0, -2, compactLayout() ? 0 : 1));
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
        browserView.setVisibility(browserVisible ? View.VISIBLE : View.GONE);
    }

    private void addHomeIdentity(LinearLayout home) {
        if (theme == ThemeSpec.MAIL) return;
        LinearLayout intro = vertical();
        intro.setTag("home-identity-" + theme.id);
        intro.setPadding(dp(12), dp(12), dp(12), dp(15));
        String title;
        String subtitle;
        switch (theme) {
            case POST: title = "나의 채널"; subtitle = "즐겨찾는 채널의 이야기를 이어서 읽으세요."; break;
            case PHOTOGRAM: title = "Following"; subtitle = "내가 즐겨찾는 피드"; break;
            case CAFE: title = "내 카페 공간"; subtitle = "관심 게시판으로 바로 들어가세요.";
                intro.setBackground(round(alpha(colors.accent, .08f), 18, Color.TRANSPARENT)); break;
            case SHEET: title = "내 통합 문서"; subtitle = "고정 시트를 선택하면 글 목록이 열립니다.";
                intro.setBackground(round(colors.surface, 0, colors.border)); break;
            case EBOOK: title = "책장"; subtitle = "좋아하는 이야기를 한 권씩 펼쳐 보세요."; break;
            default: title = "THE COLLECTION"; subtitle = "내가 선택한 이야기의 지면"; break;
        }
        TextView heading = text(title, theme == ThemeSpec.EBOOK || theme == ThemeSpec.MAGAZINE ? 28 : 22, colors.text, true);
        if (theme == ThemeSpec.EBOOK || theme == ThemeSpec.MAGAZINE) heading.setTypeface(Typeface.SERIF, Typeface.BOLD);
        intro.addView(heading);
        TextView body = text(subtitle, 12, colors.mutedText, false);
        body.setPadding(0, dp(8), 0, 0);
        intro.addView(body);
        home.addView(intro, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addFavoriteCollection(LinearLayout parent, List<Favorite> entries, boolean fromAccount) {
        if (entries.isEmpty()) return;
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
                initial.setBackground(round(colors.surface, 30, colors.accent));
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
                cell.topMargin = dp(theme == ThemeSpec.SHEET || theme == ThemeSpec.MAIL ? 1 : 10);
                if (column > 0) cell.leftMargin = dp(12);
                if (index < entries.size()) row.addView(favoriteCard(entries.get(index), index, fromAccount), cell);
                else row.addView(new View(this), cell);
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
        card.setPadding(dp(sheet ? 0 : 12), dp(sheet ? 0 : 15), dp(12), dp(sheet ? 0 : 15));
        card.setMinimumHeight(dp(60));
        int radius = theme == ThemeSpec.CAFE ? 16 : 0;
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
        String detailLabel = !fromAccount ? "이 기기에 저장한 페이지" : theme == ThemeSpec.MAIL ? "주제함 열기"
                : theme == ThemeSpec.POST ? "채널의 이야기 읽기" : theme == ThemeSpec.CAFE ? "게시판 둘러보기" : "계정 즐겨찾기";
        TextView detail = text(detailLabel, 11, colors.mutedText, false);
        detail.setPadding(0, dp(6), 0, 0);
        info.addView(detail);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView chevron = text(theme == ThemeSpec.MAIL ? "★" : "›", 21, colors.mutedText, false);
        chevron.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 23);
        chevron.setPadding(dp(8), 0, 0, 0);
        card.addView(chevron);
        bindFavorite(card, favorite, fromAccount);
        return card;
    }

    private TextView initial(Favorite favorite, int size) {
        TextView initial = text(new String(Character.toChars(favorite.name.codePointAt(0))), size, colors.accent, true);
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
            case MAIL: return "즐겨찾는 주제함";
            case POST: return "관심 채널";
            case PHOTOGRAM: return "즐겨찾는 피드";
            case SHEET: return "고정 시트";
            case CAFE: return "관심 게시판";
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
        String url = browser == null ? "" : browser.getUrl();
        return url != null && !url.isEmpty() && !"about:blank".equals(url);
    }

    private void setBrowserVisible(boolean visible) {
        boolean wasVisible = browserVisible;
        browserVisible = visible;
        if (wasVisible != visible) {
            if (visible) browser.onResume();
            else browser.onPause();
        }
        browserView.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (homeView != null) homeView.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (progress != null) progress.setVisibility(loading && visible ? View.VISIBLE : View.INVISIBLE);
        if (errorBanner != null) errorBanner.setVisibility(latestError != null && visible ? View.VISIBLE : View.GONE);
        if (bottomNavigation != null && bottomNavigation.getParent() == root) {
            root.removeView(bottomNavigation);
            root.addView(buildNavigation());
        }
        updateHeader();
    }

    private void navigate(String url) {
        guardComposerNavigation(() -> {
            latestError = null;
            pendingComposerTarget = "";
            if (errorBanner != null) errorBanner.setVisibility(View.GONE);
            browser.setOriginalMode(false);
            setBrowserVisible(true);
            browser.open(url);
        });
    }

    private void guardComposerNavigation(Runnable action) {
        String url = browser.getUrl();
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.contains("/write") || lower.contains("mode=write")) {
            dialogBuilder().setTitle("작성 화면에서 이동할까요?")
                    .setMessage("새 페이지를 열면 아직 등록하지 않은 내용이 사라질 수 있어요. 테마 변경과 홈 화면 전환은 작성 화면을 그대로 유지해요.")
                    .setNegativeButton("계속 작성", null).setPositiveButton("이동", (d, w) -> action.run()).show();
        } else action.run();
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
                            browser.setOriginalMode(false);
                            setBrowserVisible(true);
                            browser.open(targetUrl);
                        });
                    }).setNegativeButton("닫기", null).show();
        } else browser.openComposer();
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
                navigate("https://search.dcinside.com/combine/q/" + Uri.encode(value));
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
            case MAIL: return "검색창 · 주제함 서랍 · 편지 목록";
            case POST: return "중앙 심벌 · 채널 피드 · 아이콘 탐색";
            case PHOTOGRAM: return "원형 피드 · 사진 카드 · 간결한 탐색";
            case SHEET: return "행 번호 · 열 구분 · 시트 탭";
            case CAFE: return "게시판 카드 · 카페 홈 · 글 목록";
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
            browser.setTheme(theme, dark);
            buildChrome();
        };
        if (matchLauncher) browser.snapshotDraft(apply);
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
            browser.setTheme(theme, dark);
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
            browser.snapshotDraft(() -> {
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
        showAdaptiveDialog(dialog, () -> { dialog.dismiss(); showSettings(); }, () -> {
            // Keyboard and accessibility adjustments do not end a touch
            // gesture. Refresh the native chrome on every dismissal route.
            if (Float.compare(displayedFontScale, fontScale) != 0) buildChrome();
        });
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
            menu.getMenu().add(0, 1, 0, "뒤로 가기").setEnabled(browser.canGoBack());
            menu.getMenu().add(0, 2, 1, "새로고침");
            menu.getMenu().add(0, 3, 2, browser.isOriginalMode() ? "테마 화면 보기" : "원본 화면 보기");
            menu.getMenu().add(0, 4, 3, "현재 페이지 저장");
            menu.getMenu().add(0, 5, 4, "페이지 주소 공유");
        }
        menu.getMenu().add(0, 6, 5, "로그인");
        menu.getMenu().add(0, 7, 6, "화면 스타일");
        menu.getMenu().add(0, 8, 7, "환경 설정");
        menu.getMenu().add(0, 9, 8, "앱 정보 · 개인정보");
        menu.getMenu().add(0, 10, 9, "글쓰기");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: navigateBack(); break;
                case 2: guardComposerNavigation(() -> browser.reload()); break;
                case 3: browser.setOriginalMode(!browser.isOriginalMode()); updateHeader(); break;
                case 4: saveCurrent(); break;
                case 5:
                    String url = browser.getUrl();
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
        addDrawerAction(panel, dialog, "inbox", "모든 주제함", this::showHome);
        addDrawerAction(panel, dialog, "search", "주제함 찾기", this::showExplore);
        addDrawerAction(panel, dialog, "edit", "편지 쓰기", this::compose);
        panel.addView(line(), new LinearLayout.LayoutParams(-1, dp(1)));
        TextView label = text("즐겨찾는 주제함", 11, colors.mutedText, true);
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
            addDrawerAction(panel, dialog, "refresh", "새로고침", () -> guardComposerNavigation(() -> browser.reload()));
            addDrawerAction(panel, dialog, "page", browser.isOriginalMode() ? "테마 화면 보기" : "원본 화면 보기", () -> {
                browser.setOriginalMode(!browser.isOriginalMode()); updateHeader();
            });
            addDrawerAction(panel, dialog, "bookmark", "현재 페이지 저장", this::saveCurrent);
            addDrawerAction(panel, dialog, "share", "페이지 주소 공유", this::shareCurrent);
        }
        addDrawerAction(panel, dialog, "grid", "화면 스타일", this::showThemes);
        addDrawerAction(panel, dialog, "settings", "환경 설정", this::showSettings);
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
        String url = browser.getUrl();
        if (url != null && url.startsWith("https://")) {
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url);
            startActivity(Intent.createChooser(share, "페이지 주소 공유"));
        }
    }

    private void saveCurrent() {
        String url = browser.getUrl();
        if (url == null || url.isEmpty()) return;
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
                + "연결한 커뮤니티의 화면 배치를 선택한 스타일로 바꾸는 테마 뷰어입니다. 연결 서비스는 디시인사이드이며, 콘텐츠와 광고는 원본 서비스가 제공합니다. 디시인사이드 및 각 테마에서 참고한 서비스의 공식 앱이나 제휴 앱이 아닙니다.\n\n"
                + "글·댓글·추천 등은 원본 서비스의 기능입니다. 테마는 화면 표현을 바꾸며 공개 범위와 이용 권한은 바꾸지 않습니다. 지원이 어려운 페이지는 원본 보기로 이용할 수 있습니다.\n\n"
                + "개인정보와 기기 저장\n"
                + "선택한 테마, 글자 크기, 직접 저장한 페이지는 이 기기에 저장됩니다. 작성 중인 텍스트 초안도 복원을 위해 기기에 임시 저장될 수 있습니다. 비밀번호와 첨부파일은 초안에 저장하지 않습니다. 로그인 쿠키와 웹 저장소는 Android WebView에 저장되고 로그인 정보·작성 내용은 해당 서비스로 전달됩니다. 앱은 별도의 계정 서버나 자체 분석 도구를 사용하지 않습니다.\n\n"
                + "계정 즐겨찾기는 로그인한 서비스에서 불러옵니다. 이 목록을 별도 파일로 저장하지 않으며, 로그아웃 상태가 확인되거나 새로 불러오기에 실패하면 홈의 계정 목록을 비웁니다. 직접 저장한 페이지는 계정 즐겨찾기와 분리하여 이 기기에 보관합니다.\n\n"
                + "사진을 첨부할 때 Android 파일 선택창에서 직접 고릅니다. 원본 페이지가 외부 서비스를 포함하면 해당 서비스의 개인정보 처리방침이 적용됩니다.\n\n"
                + "테마를 바꿔도 열린 페이지는 다시 불러오지 않습니다. 앱 강제 종료·기기 재시작 뒤 작성 중 내용 복구는 보장되지 않으니 긴 글은 등록 전 별도로 보관해 주세요.\n\n"
                + "콘텐츠 출처: dcinside.com\n테마 아이콘: 자체 제작 독립 심벌";
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
        runOnUiThread(() -> {
            pageTitle = title == null ? "" : title;
            pageUrl = url == null ? "" : url;
            loading = isLoading;
            if (isLoading) {
                latestError = null;
                if (errorBanner != null) errorBanner.setVisibility(View.GONE);
            }
            if (progress != null) progress.setVisibility(isLoading && browserVisible ? View.VISIBLE : View.INVISIBLE);
            updateHeader();
            if (!isLoading && !pendingComposerTarget.isEmpty()
                    && pendingComposerTarget.equals(UrlPolicy.galleryId(pageUrl))) {
                pendingComposerTarget = "";
                browser.openComposer();
            }
        });
    }

    @Override public void onError(String message) {
        runOnUiThread(() -> {
            latestError = message;
            pendingComposerTarget = "";
            loading = false;
            if (progress != null) progress.setVisibility(View.INVISIBLE);
            if (errorText != null) errorText.setText(message);
            if (errorBanner != null) errorBanner.setVisibility(browserVisible ? View.VISIBLE : View.GONE);
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
        guardComposerNavigation(() -> {
            pendingComposerTarget = "";
            setBrowserVisible(true);
            browser.open(LOGIN_URL);
        });
    }

    private void openAccountFavorites() {
        guardComposerNavigation(() -> {
            pendingComposerTarget = "";
            setBrowserVisible(true);
            browser.openAccountFavorites();
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
            if (browser.canGoBack()) guardComposerNavigation(() -> browser.goBack());
            else showHome();
        } else finish();
    }

    @Override public void onBackPressed() { navigateBack(); }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!browser.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!browser.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putBoolean("ui.browserVisible", browserVisible);
        state.putBoolean("ui.originalMode", browser.isOriginalMode());
        browser.onSaveInstanceState(state);
        super.onSaveInstanceState(state);
    }

    @Override protected void onPause() { if (browser != null) browser.onPause(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (browser != null && browserVisible) browser.onResume(); }
    @Override protected void onDestroy() {
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
                : android.R.style.Theme_Material_Light_Dialog_Alert);
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
