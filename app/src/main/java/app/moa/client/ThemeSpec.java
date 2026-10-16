package app.moa.client;

/** Presentation names are independent of the underlying community identifiers. */
public enum ThemeSpec {
    MAIL("mail", "메일함", "주제함", "메일함에서 글 검색", "LauncherMail", R.drawable.ic_theme_mail),
    POST("post", "포스트", "채널", "채널에서 글 검색", "LauncherPost", R.drawable.ic_theme_post),
    PHOTOGRAM("photogram", "포토그램", "주제 피드", "주제 피드에서 글 검색", "LauncherPhotogram", R.drawable.ic_theme_photogram),
    SHEET("sheet", "스프레드 시트", "시트", "시트에서 글 검색", "LauncherSheet", R.drawable.ic_theme_sheet),
    CAFE("cafe", "카페", "게시판", "게시판에서 글 검색", "LauncherCafe", R.drawable.ic_theme_cafe),
    EBOOK("ebook", "E-book", "서가", "서가에서 글 검색", "LauncherEbook", R.drawable.ic_theme_ebook),
    MAGAZINE("magazine", "매거진", "섹션", "섹션에서 글 검색", "LauncherMagazine", R.drawable.ic_theme_magazine);

    public final String id;
    public final String displayName;
    public final String groupNoun;
    public final String searchHint;
    public final String launcherAlias;
    public final int iconRes;

    ThemeSpec(String id, String displayName, String groupNoun, String searchHint,
              String launcherAlias, int iconRes) {
        this.id = id;
        this.displayName = displayName;
        this.groupNoun = groupNoun;
        this.searchHint = searchHint;
        this.launcherAlias = "app.moa.client." + launcherAlias;
        this.iconRes = iconRes;
    }

    public String labelFor(String baseName) {
        String name = baseName == null ? "" : baseName.trim();
        if (name.isEmpty()) return groupNoun;
        return this == MAIL ? name + "함" : name + " " + groupNoun;
    }

    /** Old or unknown preference values safely fall back to the initial theme. */
    public static ThemeSpec fromId(String id) {
        if (id != null) for (ThemeSpec theme : values()) {
            if (theme.id.equalsIgnoreCase(id.trim())) return theme;
        }
        return MAIL;
    }

    public Palette palette(boolean dark) {
        int accent;
        int onAccent;
        switch (this) {
            case POST:
                accent = dark ? 0xFFF3F5F7 : 0xFF15191E;
                onAccent = dark ? 0xFF12161B : 0xFFFFFFFF;
                break;
            case PHOTOGRAM:
                accent = dark ? 0xFFFF9FB9 : 0xFFB52F71;
                onAccent = dark ? 0xFF47172D : 0xFFFFFFFF;
                break;
            case SHEET:
                accent = dark ? 0xFF71D8A5 : 0xFF147A4B;
                onAccent = dark ? 0xFF0D3524 : 0xFFFFFFFF;
                break;
            case CAFE:
                accent = dark ? 0xFF64E298 : 0xFF087D3E;
                onAccent = dark ? 0xFF06331C : 0xFFFFFFFF;
                break;
            case EBOOK:
                accent = dark ? 0xFFEAC59C : 0xFF845536;
                onAccent = dark ? 0xFF38291D : 0xFFFFFFFF;
                break;
            case MAGAZINE:
                accent = dark ? 0xFFFFAA98 : 0xFFB74632;
                onAccent = dark ? 0xFF481E17 : 0xFFFFFFFF;
                break;
            default:
                accent = dark ? 0xFFA8C7FA : 0xFF255BB7;
                onAccent = dark ? 0xFF15335E : 0xFFFFFFFF;
                break;
        }
        if (dark) {
            return new Palette(this == EBOOK ? 0xFF1E1B18 : 0xFF101419,
                    this == EBOOK ? 0xFF29241F : 0xFF1B222A,
                    0xFFF0F2F5, 0xFFB0BBC7, accent, onAccent,
                    this == EBOOK ? 0xFF494039 : 0xFF35404C);
        }
        int background = 0xFFF6F8FC;
        int surface = 0xFFFFFFFF;
        int border = 0xFFDDE3EB;
        switch (this) {
            case POST: background = 0xFFFFFFFF; border = 0xFFEFF3F4; break;
            case PHOTOGRAM: background = 0xFFFFFFFF; border = 0xFFDBDBDB; break;
            case SHEET: background = 0xFFF1F7F3; border = 0xFFD2E5D9; break;
            case CAFE: background = 0xFFF6F7F8; border = 0xFFE5E7E9; break;
            case EBOOK: background = 0xFFF7F0E5; surface = 0xFFFFFAF2; border = 0xFFE2D4C1; break;
            case MAGAZINE: background = 0xFFFFF8F3; border = 0xFFEEDAD1; break;
            default: break;
        }
        return new Palette(background, surface, 0xFF20252C, 0xFF5E6977,
                accent, onAccent, border);
    }

    public static final class Palette {
        public final int background;
        public final int surface;
        public final int text;
        public final int mutedText;
        public final int accent;
        public final int onAccent;
        public final int border;

        private Palette(int background, int surface, int text, int mutedText,
                        int accent, int onAccent, int border) {
            this.background = background;
            this.surface = surface;
            this.text = text;
            this.mutedText = mutedText;
            this.accent = accent;
            this.onAccent = onAccent;
            this.border = border;
        }
    }
}
