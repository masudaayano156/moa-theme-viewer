package app.moa.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Native, device-local controls; account and media workflows are explicitly delegated to their owner. */
public final class CommunityToolsDialog {
    public interface Host {
        ThemeSpec.Palette palette();
        boolean isDark();
        String currentUrl();
        String currentTitle();
        void openUrl(String url);
        void onOptionsChanged();
        void onAction(String action);
    }

    private static final String[] RULE_KINDS = {"word", "id", "ip", "nickname"};
    private static final String[] RULE_LABELS = {"단어", "아이디", "IP", "닉네임"};
    private static final String[] NOTE_KINDS = {"id", "ip", "nickname"};
    private static final String[] NOTE_LABELS = {"아이디", "IP", "닉네임"};
    private final Activity activity;
    private final Host host;
    private AlertDialog dialog;
    private AlertDialog confirmation;
    private LinearLayout content;
    private ScrollView scroll;
    private ThemeSpec.Palette colors;

    public CommunityToolsDialog(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) { showMain(); return; }
        colors = host.palette();
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(12));
        scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        dialog = new AlertDialog.Builder(activity, host.isDark()
                ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("커뮤니티 도구").setView(scroll)
                .setNeutralButton("도구 목록", null).setPositiveButton("닫기", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> showMain());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(colors.accent);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colors.accent);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(colors.surface));
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                resize();
            }
        });
        showMain();
        dialog.show();
    }

    public void dismiss() {
        if (confirmation != null) confirmation.dismiss();
        if (dialog != null) dialog.dismiss();
    }

    /** Reflow the same dialog, retaining text fields, focus and confirmation state. */
    public void resize() {
        int configured = activity.getResources().getConfiguration().screenWidthDp;
        int available = configured > 0 ? dp(configured) : activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(1, Math.min(dp(560), available - dp(24)));
        for (AlertDialog shown : new AlertDialog[] {dialog, confirmation}) {
            if (shown != null && shown.isShowing() && shown.getWindow() != null) shown.getWindow().setLayout(width, -2);
        }
    }

    private void page(String title) {
        colors = host.palette();
        dialog.setTitle(title);
        content.removeAllViews();
        content.setBackgroundColor(colors.surface);
        if (dialog.isShowing()) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(colors.accent);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colors.accent);
        }
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void showMain() {
        page("커뮤니티 도구");
        paragraph("차단·메모·보관함·최근 글은 이 기기에 저장돼요. 앱은 이 정보를 서버에 업로드하지 않아요. 이용자 메모는 내 화면에만 표시해요.");
        button("차단 관리", "community_rules", () -> showRules(null));
        button("이용자 메모", "community_notes", () -> showNotes(null));
        button("글 보관함", "community_saved", () -> showArticles(false));
        button("최근 본 글", "community_recent", () -> showArticles(true));
        button("머리말 · 꼬리말", "community_affixes", this::showAffixScopes);
        button("이미지 표시 · GIF 보기", "community_images", this::showImages);
        button("테마색 · 분할 화면", "community_display", this::showDisplay);
        heading("계정 · 작성 · 알림");
        action("간편 계정 전환", "accounts");
        action("사진 편집 · 움짤 만들기", "media_editor");
        action("게시판별 자동 짤방", "autopicture");
        action("앱 안 알림", "in_app_alerts");
        heading("텍스트 초안 자동 보관");
        paragraph("페이지 이동이나 앱이 백그라운드로 갈 때 작성 중인 텍스트를 임시 보관하고, 같은 계정·작성 화면에서 복원을 안내해요. 첨부파일과 글자 서식은 보관하지 않으며 강제 종료 직전의 내용까지 저장되는 것은 아니에요.");
    }

    private void showRules(CommunityStore.Rule editing) {
        page("차단 관리");
        paragraph("단어는 글·댓글에 포함된 문자열을 그대로 비교해요. 아이디·IP·닉네임은 화면에서 확인되는 값과 정확히 일치할 때 숨겨요. 정규식이나 IP 일부 일치는 사용하지 않아요.");
        Spinner kind = spinner(RULE_LABELS, "rule_kind");
        EditText value = input("차단할 값", CommunityStore.MAX_VALUE_LENGTH, false, "rule_value");
        if (editing != null) { kind.setSelection(indexOf(RULE_KINDS, editing.kind)); value.setText(editing.value); }
        TextView status = status();
        button(editing == null ? "차단 추가" : "차단 수정", "rule_save", () -> {
            String selected = RULE_KINDS[kind.getSelectedItemPosition()];
            String entered = value.getText().toString();
            boolean accepted;
            if (editing == null) accepted = CommunityStore.addRule(activity, selected, entered);
            else accepted = CommunityStore.replaceRule(activity, editing.kind, editing.value, selected, entered);
            if (!accepted) { status.setText("값을 확인해주세요. 최대 120자, 차단은 200개까지 저장할 수 있어요."); return; }
            host.onOptionsChanged(); showRules(null);
        });
        if (editing != null) button("수정 취소", "rule_cancel", () -> showRules(null));
        List<CommunityStore.Rule> rules = CommunityStore.rules(activity);
        heading("차단 목록 · " + rules.size() + "/200");
        if (rules.isEmpty()) paragraph("등록한 차단이 없어요.");
        for (CommunityStore.Rule rule : rules) {
            TextView title = paragraph(RULE_LABELS[indexOf(RULE_KINDS, rule.kind)] + " · " + rule.value);
            title.setTextIsSelectable(true);
            pairedButtons("수정", () -> showRules(rule), "삭제", () -> {
                CommunityStore.removeRule(activity, rule.kind, rule.value);
                host.onOptionsChanged(); showRules(null);
            });
        }
    }

    private void showNotes(CommunityStore.UserNote editing) {
        page("이용자 메모");
        paragraph("화면에서 확인되는 아이디·IP·닉네임을 입력하세요. 닉네임은 여러 사람이 사용할 수 있고 IP도 바뀔 수 있어요. 메모는 나에게만 보여요.");
        Spinner kind = spinner(NOTE_LABELS, "note_kind");
        EditText value = input("이용자 값", CommunityStore.MAX_VALUE_LENGTH, false, "note_value");
        EditText text = input("나만 보는 메모", CommunityStore.MAX_NOTE_LENGTH, true, "note_text");
        if (editing != null) {
            kind.setSelection(indexOf(NOTE_KINDS, editing.kind)); value.setText(editing.value); text.setText(editing.text);
        }
        TextView status = status();
        button(editing == null ? "메모 저장" : "메모 수정", "note_save", () -> {
            String selected = NOTE_KINDS[kind.getSelectedItemPosition()];
            String entered = value.getText().toString();
            boolean accepted = editing == null ? CommunityStore.putNote(activity, selected, entered, text.getText().toString())
                    : CommunityStore.replaceNote(activity, editing.kind, editing.value, selected, entered, text.getText().toString());
            if (!accepted) { status.setText("이용자 값과 메모를 확인해주세요. 메모는 200자, 총 200개까지 저장할 수 있어요."); return; }
            host.onOptionsChanged(); showNotes(null);
        });
        if (editing != null) button("수정 취소", "note_cancel", () -> showNotes(null));
        List<CommunityStore.UserNote> notes = CommunityStore.notes(activity);
        heading("메모 목록 · " + notes.size() + "/200");
        if (notes.isEmpty()) paragraph("저장한 메모가 없어요.");
        for (CommunityStore.UserNote note : notes) {
            paragraph(NOTE_LABELS[indexOf(NOTE_KINDS, note.kind)] + " · " + note.value + "\n" + note.text);
            pairedButtons("수정", () -> showNotes(note), "삭제", () -> {
                CommunityStore.removeNote(activity, note.kind, note.value);
                host.onOptionsChanged(); showNotes(null);
            });
        }
    }

    private void showArticles(boolean recent) {
        page(recent ? "최근 본 글" : "글 보관함");
        if (recent) {
            paragraph("최근 100개 글을 이 기기에 기록해요. 기록을 꺼도 기존 목록은 유지되며 전체 삭제로 지울 수 있어요.");
            CheckBox enabled = checkbox("최근 본 글 기록", CommunityStore.isHistoryEnabled(activity), "history_enabled");
            enabled.setOnCheckedChangeListener((view, checked) -> {
                CommunityStore.setHistoryEnabled(activity, checked); host.onOptionsChanged();
            });
            button("최근 기록 전체 삭제", "history_clear", () -> confirm("최근 기록을 모두 삭제할까요?", () -> {
                CommunityStore.clearHistory(activity); showArticles(true);
            }));
        } else {
            paragraph("글의 링크와 제목을 보관해요. 원문이 삭제되면 다시 볼 수 없어요. 최대 200개이며 가득 차면 오래된 보관 링크부터 정리돼요.");
            String target = CommunityStore.canonicalArticleUrl(host.currentUrl());
            Button save = button("현재 글 보관", "article_save_current", () -> {
                if (CommunityStore.saveArticle(activity, host.currentUrl(), host.currentTitle())) showArticles(false);
            });
            save.setEnabled(target != null);
            save.setAlpha(target == null ? .5f : 1f);
            if (target == null) paragraph("글을 연 뒤 현재 글을 보관할 수 있어요.");
        }
        EditText query = input("제목에서 찾기", 200, false, "article_search");
        LinearLayout entries = new LinearLayout(activity);
        entries.setOrientation(LinearLayout.VERTICAL);
        content.addView(entries, new LinearLayout.LayoutParams(-1, -2));
        Runnable render = () -> {
            entries.removeAllViews();
            List<CommunityStore.Article> items = recent ? CommunityStore.recentArticles(activity) : CommunityStore.savedArticles(activity);
            String filter = query.getText().toString().trim().toLowerCase(Locale.ROOT);
            int count = 0;
            for (CommunityStore.Article article : items) {
                if (!article.title.toLowerCase(Locale.ROOT).contains(filter)) continue;
                count++;
                Button open = makeButton(article.title, () -> { dismiss(); host.openUrl(article.url); });
                open.setTag("article_open"); entries.addView(open, spaced());
                TextView date = label(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(article.savedAt)), 12, false);
                entries.addView(date, spaced());
                Button remove = makeButton("목록에서 삭제", () -> {
                    if (recent) CommunityStore.removeRecentArticle(activity, article.url);
                    else CommunityStore.removeSavedArticle(activity, article.url);
                    showArticles(recent);
                });
                remove.setTag("article_delete"); entries.addView(remove, spaced());
            }
            if (count == 0) entries.addView(label(filter.isEmpty() ? "아직 글이 없어요." : "일치하는 제목이 없어요.", 14, false), spaced());
        };
        query.addTextChangedListener(watcher(render)); render.run();
    }

    private void showAffixScopes() {
        page("머리말 · 꼬리말");
        paragraph("저장한 문구는 작성 화면의 ‘머리말·꼬리말 넣기’로 삽입해요. 글이나 댓글을 자동으로 등록하지 않아요. 문구마다 2,000자까지 저장할 수 있어요.");
        button("모든 게시판 공통 문구", "affixes_global", () -> showAffixEditor(""));
        String gallery = CommunityOptions.galleryIdFromUrl(host.currentUrl());
        Button perGallery = button("현재 게시판 전용 문구", "affixes_gallery", () -> showAffixEditor(gallery));
        perGallery.setEnabled(!gallery.isEmpty());
        perGallery.setAlpha(gallery.isEmpty() ? .5f : 1f);
        if (gallery.isEmpty()) paragraph("게시판이나 글을 열면 그 게시판만의 문구를 설정할 수 있어요.");
        else paragraph("현재 게시판 ID · " + gallery + (CommunityOptions.hasGalleryAffixes(activity, gallery) ? "\n전용 문구 사용 중" : "\n공통 문구 사용 중"));
        action("원본 사이트의 머리말 · 꼬리말 설정", "source_affixes");
    }

    private void showAffixEditor(String galleryId) {
        page(galleryId.isEmpty() ? "공통 머리말 · 꼬리말" : "게시판 전용 문구");
        if (!galleryId.isEmpty()) paragraph("게시판 ID · " + galleryId + "\n전용 문구가 없으면 공통 문구를 사용해요. 빈 문구를 저장하면 이 게시판에서는 삽입할 문구가 없어져요.");
        CommunityOptions.Affixes values = CommunityOptions.affixes(activity, galleryId);
        heading("글 머리말"); EditText postPrefix = input("본문 앞에 넣을 문구", 2000, true, "post_prefix"); postPrefix.setText(values.postPrefix);
        heading("글 꼬리말"); EditText postSuffix = input("본문 뒤에 넣을 문구", 2000, true, "post_suffix"); postSuffix.setText(values.postSuffix);
        heading("댓글 머리말"); EditText commentPrefix = input("댓글 앞에 넣을 문구", 2000, true, "comment_prefix"); commentPrefix.setText(values.commentPrefix);
        heading("댓글 꼬리말"); EditText commentSuffix = input("댓글 뒤에 넣을 문구", 2000, true, "comment_suffix"); commentSuffix.setText(values.commentSuffix);
        TextView status = status();
        button("문구 저장", "affixes_save", () -> {
            if (!CommunityOptions.saveAffixes(activity, galleryId, postPrefix.getText().toString(), postSuffix.getText().toString(),
                    commentPrefix.getText().toString(), commentSuffix.getText().toString())) { status.setText("입력한 문구를 확인해주세요."); return; }
            host.onOptionsChanged(); showAffixScopes();
        });
        button(galleryId.isEmpty() ? "공통 문구 지우기" : "전용 문구 지우고 공통 문구 사용", "affixes_clear", () -> confirm("저장한 문구를 지울까요?", () -> {
            CommunityOptions.clearAffixes(activity, galleryId); host.onOptionsChanged(); showAffixScopes();
        }));
    }

    private void showImages() {
        page("이미지 표시 · GIF 보기");
        paragraph("본문 이미지를 숨기면 확인된 사진 서버 3곳의 첨부 사진 요청도 제한해요. 모든 외부 이미지나 시스템 아이콘·인증 이미지를 차단하지는 않아요. 이미 열린 페이지는 작성 내용을 보관한 뒤 새로고침해야 요청 제한이 완전히 적용돼요.");
        paragraph("GIF 눌러서 보기는 주소에서 GIF임을 확인할 수 있는 사진만 가렸다가 보여줘요. 다운로드나 재생을 멈추는 기능은 아니며 이미지를 이미 불러왔을 수 있어요.");
        String current = CommunityOptions.imageMode(activity);
        String[] values = {CommunityOptions.IMAGE_NORMAL, CommunityOptions.IMAGE_HIDE, CommunityOptions.IMAGE_TAP_GIF};
        String[] names = {"이미지 모두 표시", "본문 이미지 숨김 · 알려진 사진 요청 제한", "GIF 눌러서 보기"};
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            button((value.equals(current) ? "✓ " : "") + names[i], "image_" + value, () -> {
                CommunityOptions.setImageMode(activity, value); host.onOptionsChanged(); showImages();
            });
        }
    }

    private void showDisplay() {
        page("테마색 · 분할 화면");
        CheckBox split = checkbox("넓은 화면에서 목록과 본문 나란히 보기", CommunityOptions.splitEnabled(activity), "split_enabled");
        paragraph("태블릿·폴드의 넓은 화면에서 사용해요. 공간이 부족하면 한 화면으로 표시해요.");
        split.setOnCheckedChangeListener((view, checked) -> {
            CommunityOptions.setSplitEnabled(activity, checked); host.onOptionsChanged(); host.onAction("split");
        });
        heading("강조색");
        paragraph("배경과 글자색은 현재 테마·야간 모드를 유지하고 버튼과 선택 표시의 색상을 바꿔요.");
        button((CommunityOptions.accentIndex(activity) < 0 ? "✓ " : "") + "테마 기본색", "accent_default", () -> {
            CommunityOptions.setAccentIndex(activity, -1); host.onOptionsChanged(); showDisplay();
        });
        String[] names = {"블루", "코발트", "스카이", "시안", "틸", "포레스트", "그린", "올리브", "머스터드", "골드", "오렌지", "테라코타", "레드", "로즈", "퍼플", "바이올렛", "브라운", "슬레이트"};
        int[] palette = CommunityOptions.accentColors();
        int columns = activity.getResources().getConfiguration().screenWidthDp < 360 ? 3 : 4;
        for (int offset = 0; offset < palette.length; offset += columns) {
            LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL);
            content.addView(row, spaced());
            for (int j = offset; j < Math.min(offset + columns, palette.length); j++) {
                final int index = j;
                Button swatch = makeButton((CommunityOptions.accentIndex(activity) == j ? "✓ " : "") + names[j], () -> {
                    CommunityOptions.setAccentIndex(activity, index); host.onOptionsChanged(); showDisplay();
                });
                swatch.setTag("accent_" + j); swatch.setTextSize(12);
                swatch.setBackgroundTintList(ColorStateList.valueOf(palette[j]));
                swatch.setTextColor(CommunityOptions.onAccentColor(palette[j]));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                params.setMargins(dp(2), 0, dp(2), 0); row.addView(swatch, params);
            }
        }
    }

    private void action(String label, String action) { button(label, "action_" + action, () -> { dismiss(); host.onAction(action); }); }

    private void confirm(String title, Runnable action) {
        confirmation = new AlertDialog.Builder(activity, host.isDark()
                ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(title).setNegativeButton("취소", null).setPositiveButton("삭제", (which, button) -> action.run()).create();
        confirmation.show();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colors.accent);
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(colors.accent);
    }

    private TextView heading(String text) { TextView view = label(text, 16, true); content.addView(view, spaced()); return view; }
    private TextView paragraph(String text) { TextView view = label(text, 14, false); content.addView(view, spaced()); return view; }
    private TextView status() { TextView view = paragraph(""); view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); return view; }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(activity); view.setText(text); view.setTextColor(colors.text); view.setTextSize(size);
        view.setLineSpacing(dp(3), 1f); if (bold) view.setTypeface(null, Typeface.BOLD); return view;
    }

    private EditText input(String hint, int maximum, boolean multiline, String tag) {
        EditText view = new EditText(activity); view.setTag(tag); view.setHint(hint); view.setTextColor(colors.text); view.setHintTextColor(colors.mutedText);
        view.setTextSize(16); view.setMinHeight(dp(48)); view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        view.setSingleLine(!multiline); if (multiline) { view.setMinLines(2); view.setMaxLines(5); }
        view.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        view.setFilters(new InputFilter[] {new InputFilter.LengthFilter(maximum)});
        view.setBackgroundTintList(ColorStateList.valueOf(colors.accent));
        content.addView(view, spaced()); return view;
    }

    private Spinner spinner(String[] labels, String tag) {
        Spinner spinner = new Spinner(activity); spinner.setTag(tag); spinner.setMinimumHeight(dp(48));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView result = (TextView) super.getView(position, convertView, parent); result.setTextColor(colors.text); return result;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView result = (TextView) super.getDropDownView(position, convertView, parent);
                result.setTextColor(colors.text); result.setBackgroundColor(colors.surface); result.setMinHeight(dp(48)); return result;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter);
        spinner.setPopupBackgroundDrawable(new ColorDrawable(colors.surface));
        content.addView(spinner, spaced()); return spinner;
    }

    private CheckBox checkbox(String title, boolean checked, String tag) {
        CheckBox view = new CheckBox(activity); view.setText(title); view.setTag(tag); view.setTextSize(15); view.setTextColor(colors.text);
        view.setButtonTintList(ColorStateList.valueOf(colors.accent)); view.setMinHeight(dp(48)); view.setChecked(checked);
        content.addView(view, spaced()); return view;
    }

    private Button button(String label, String tag, Runnable action) {
        Button view = makeButton(label, action); view.setTag(tag); content.addView(view, spaced()); return view;
    }

    private Button makeButton(String label, Runnable action) {
        Button view = new Button(activity); view.setText(label); view.setAllCaps(false); view.setTextSize(15); view.setTextColor(colors.text);
        view.setMinHeight(dp(48)); view.setMinimumWidth(0); view.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(colors.tint); shape.setCornerRadius(dp(12)); shape.setStroke(dp(1), colors.border);
        view.setBackground(shape); view.setOnClickListener(ignored -> action.run()); return view;
    }

    private void pairedButtons(String first, Runnable firstAction, String second, Runnable secondAction) {
        LinearLayout row = new LinearLayout(activity); row.setOrientation(LinearLayout.HORIZONTAL); content.addView(row, spaced());
        for (Button view : new Button[] {makeButton(first, firstAction), makeButton(second, secondAction)}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(dp(2), 0, dp(2), 0); row.addView(view, params);
        }
    }

    private LinearLayout.LayoutParams spaced() { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(5), 0, dp(5)); return params; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static int indexOf(String[] values, String value) { for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i; return 0; }
    private static TextWatcher watcher(Runnable action) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { action.run(); }
            @Override public void afterTextChanged(Editable text) { }
        };
    }
}
