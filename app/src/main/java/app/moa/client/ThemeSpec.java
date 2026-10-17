package app.moa.client;

/** Presentation names are independent of the underlying community identifiers. */
public enum ThemeSpec {
    MAIL("mail", "메일함", "라벨", "메일함에서 검색", "LauncherMail", R.drawable.ic_theme_mail),
    POST("post", "X", "커뮤니티", "검색", "LauncherPost", R.drawable.ic_theme_post),
    PHOTOGRAM("photogram", "인스타그램", "피드", "검색", "LauncherPhotogram", R.drawable.ic_theme_photogram),
    SHEET("sheet", "엑셀", "시트", "통합 문서에서 검색", "LauncherSheet", R.drawable.ic_theme_sheet),
    CAFE("cafe", "네이버 카페", "카페", "카페 검색", "LauncherCafe", R.drawable.ic_theme_cafe),
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
        return this == MAIL ? name : name + " " + groupNoun;
    }

    /** Old or unknown preference values safely fall back to the initial theme. */
    public static ThemeSpec fromId(String id) {
        if (id != null) for (ThemeSpec theme : values()) {
            if (theme.id.equalsIgnoreCase(id.trim())) return theme;
        }
        return MAIL;
    }

    public Palette palette(boolean dark) {
        switch (this) {
            default:
                return dark ? new Palette(0xFF131314, 0xFF1E1F20, 0xFFE3E3E3, 0xFFC4C7C5, 0xFFA8C7FA, 0xFF062E6F, 0xFF444746, 0xFF282A2C)
                        : new Palette(0xFFF6F8FC, 0xFFFFFFFF, 0xFF1F1F1F, 0xFF5F6368, 0xFF0B57D0, 0xFFFFFFFF, 0xFFE0E3E7, 0xFFE9EEF6);
            case POST:
                return dark ? new Palette(0xFF000000, 0xFF000000, 0xFFE7E9EA, 0xFF71767B, 0xFF1D9BF0, 0xFFFFFFFF, 0xFF2F3336, 0xFF16181C)
                        : new Palette(0xFFFFFFFF, 0xFFFFFFFF, 0xFF0F1419, 0xFF536471, 0xFF1D9BF0, 0xFFFFFFFF, 0xFFEFF3F4, 0xFFF7F9F9);
            case PHOTOGRAM:
                return dark ? new Palette(0xFF000000, 0xFF000000, 0xFFF5F5F5, 0xFFA8A8A8, 0xFF0095F6, 0xFFFFFFFF, 0xFF262626, 0xFF121212)
                        : new Palette(0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000, 0xFF737373, 0xFF0095F6, 0xFFFFFFFF, 0xFFDBDBDB, 0xFFF5F5F5);
            case SHEET:
                return dark ? new Palette(0xFF1B1B1B, 0xFF252525, 0xFFF5F5F5, 0xFFBDBDBD, 0xFF54B054, 0xFF102A12, 0xFF444444, 0xFF233829)
                        : new Palette(0xFFF5F5F5, 0xFFFFFFFF, 0xFF242424, 0xFF616161, 0xFF107C41, 0xFFFFFFFF, 0xFFD1D1D1, 0xFFE8F3EC);
            case CAFE:
                return dark ? new Palette(0xFF161819, 0xFF202325, 0xFFF1F3F5, 0xFFADB5BD, 0xFF58D68D, 0xFF073B1B, 0xFF3B4145, 0xFF223B2D)
                        : new Palette(0xFFF5F6F8, 0xFFFFFFFF, 0xFF222222, 0xFF767676, 0xFF008A3B, 0xFFFFFFFF, 0xFFE5E7EB, 0xFFE8F7EE);
            case EBOOK:
                return dark ? new Palette(0xFF1E1B18, 0xFF29241F, 0xFFEEE1D1, 0xFFC0AE98, 0xFFEAC59C, 0xFF38291D, 0xFF494039, 0xFF3B3026)
                        : new Palette(0xFFF7F0E5, 0xFFFFFAF2, 0xFF3B3027, 0xFF756456, 0xFF845536, 0xFFFFFFFF, 0xFFE2D4C1, 0xFFEEE3D2);
            case MAGAZINE:
                return dark ? new Palette(0xFF191715, 0xFF24211F, 0xFFEEE7DE, 0xFFBFB3A7, 0xFFF0A08F, 0xFF452018, 0xFF4B4138, 0xFF392A24)
                        : new Palette(0xFFF5F1EB, 0xFFFFFDF9, 0xFF24211E, 0xFF746B63, 0xFFAF3D2E, 0xFFFFFFFF, 0xFFDED5CC, 0xFFF2E5DD);
        }
    }

    public static final class Palette {
        public final int background;
        public final int surface;
        public final int text;
        public final int mutedText;
        public final int accent;
        public final int onAccent;
        public final int border;
        public final int tint;

        private Palette(int background, int surface, int text, int mutedText,
                        int accent, int onAccent, int border, int tint) {
            this.background = background;
            this.surface = surface;
            this.text = text;
            this.mutedText = mutedText;
            this.accent = accent;
            this.onAccent = onAccent;
            this.border = border;
            this.tint = tint;
        }
    }
}
