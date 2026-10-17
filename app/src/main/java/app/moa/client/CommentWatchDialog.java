package app.moa.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Explicit, address-by-address consent for server-side public comment checks. */
public final class CommentWatchDialog {
    public interface Host {
        ThemeSpec.Palette palette();
        boolean isDark();
        String currentUrl();
        void openPushSettings();
    }

    private final Activity activity;
    private final Host host;
    private AlertDialog dialog, confirmation;
    private LinearLayout content;
    private ThemeSpec.Palette colors;
    private List<PushRelayClient.Watch> watches = Collections.emptyList();
    private long requestGeneration;
    private boolean busy;

    public CommentWatchDialog(Activity activity, Host host) { this.activity = activity; this.host = host; }

    public void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (dialog != null && dialog.isShowing()) return;
        colors = host.palette();
        busy = false;
        watches = Collections.emptyList();
        requestGeneration++;
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(12));
        content.setBackgroundColor(colors.surface);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        AlertDialog created = new AlertDialog.Builder(activity, host.isDark()
                ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("글별 댓글 알림").setView(scroll).setPositiveButton("닫기", null).create();
        dialog = created;
        created.setOnDismissListener(ignored -> { requestGeneration++; busy = false; if (dialog == created) dialog = null; });
        created.setOnShowListener(ignored -> decorate(created));
        render("");
        created.show();
        if (PushRelayClient.isPaired(activity)) refresh();
    }

    public void dismiss() {
        requestGeneration++;
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
        if (dialog != null) dialog.dismiss();
        watches = Collections.emptyList();
    }

    /** Only resize existing windows; do not restart requests or reset the selected watches. */
    public void resize() {
        if (dialog != null && dialog.isShowing()) decorate(dialog);
        if (confirmation != null && confirmation.isShowing()) decorate(confirmation);
    }

    private boolean current(long expected) {
        return requestGeneration == expected && dialog != null && dialog.isShowing()
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void render(String message) {
        content.removeAllViews();
        paragraph("선택한 공개 글 주소와 댓글 수를 Cloudflare 알림 서버에 저장해요. 앱은 로그인 쿠키·비밀번호·본문·닉네임을 보내지 않아요. 서버가 공개 페이지를 읽지만 본문·닉네임은 저장하지 않아요.");
        paragraph("댓글 수 증가를 주기적으로 확인하며 즉시 알림은 아니에요. 기기당 20개 글을 등록할 수 있고, 확인 간격은 최소 5분부터 서버 대기량에 따라 늘어납니다. 본인 댓글도 포함되며 삭제와 추가가 겹치면 놓칠 수 있어요.");
        if (!message.isEmpty()) {
            TextView status = paragraph(message);
            status.setTag("watch_status");
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            status.setTextColor(colors.accent);
        }
        long resumeAt = PushRelayClient.watchResumeAt(activity);
        if (resumeAt > 0) paragraph("오늘 서버의 알림 처리량을 사용해 잠시 대기 중이에요. 다시 처리 가능한 시각: " + date(resumeAt));
        if (!PushRelayClient.isPaired(activity)) {
            paragraph("알림을 허용하면 앱이 이 기기를 자동으로 연결해요. 운영자에게 연결 코드를 받을 필요가 없어요.");
            button("푸시 연결 설정", "watch_connect", () -> { dismiss(); host.openPushSettings(); });
            return;
        }
        Switch automatic = new Switch(activity);
        automatic.setText("앞으로 내가 쓴 새 글 자동 등록");
        automatic.setTextSize(14); automatic.setTextColor(colors.text); automatic.setMinHeight(dp(48));
        automatic.setTag("watch_auto_own"); automatic.setChecked(CommunityOptions.autoWatchOwnPosts(activity));
        automatic.setEnabled(!busy);
        automatic.setOnCheckedChangeListener((view, checked) -> {
            if (!checked) { CommunityOptions.setAutoWatchOwnPosts(activity, false); render("앞으로 새 글을 자동 등록하지 않아요. 기존 등록 글은 아래에서 각각 해제할 수 있어요."); }
            else {
                automatic.setOnCheckedChangeListener(null); automatic.setChecked(false); confirmAutomatic();
            }
        });
        content.addView(automatic, new LinearLayout.LayoutParams(-1, -2));
        paragraph("자동 등록을 켜면 이 앱에서 새 글 등록이 확인된 경우에만 그 주소를 서버에 보내요. 예전에 쓴 글은 자동으로 가져오지 않으며, 첫 확인 전에 달린 댓글은 기준에 포함되어 알림을 놓칠 수 있어요.");
        String currentUrl = CommunityStore.canonicalArticleUrl(host.currentUrl());
        if (currentUrl != null) {
            heading("현재 글");
            paragraph(articleLabel(currentUrl));
            boolean saved = false;
            for (PushRelayClient.Watch watch : watches) if (watch.url.equals(currentUrl)) saved = true;
            final boolean renew = saved;
            button(saved ? "현재 글 알림 유지" : "현재 글 댓글 알림 등록", "watch_current", () -> {
                if (renew) change(currentUrl, null);
                else confirmWatch(currentUrl);
            });
        } else paragraph("게시글을 연 뒤 이 화면에서 그 글의 댓글 알림을 등록할 수 있어요.");
        button("알림 목록 새로고침", "watch_refresh", this::refresh);
        heading("등록한 글 · " + watches.size() + " / 20");
        if (watches.isEmpty() && !busy) paragraph("아직 등록한 글이 없어요.");
        for (PushRelayClient.Watch watch : watches) {
            View line = new View(activity); line.setBackgroundColor(colors.border);
            content.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            heading(articleLabel(watch.url));
            paragraph(statusText(watch.status) + (watch.baselineCount == null ? "" : " · 댓글 " + watch.baselineCount));
            if (watch.lastChecked > 0) paragraph("최근 확인 " + date(watch.lastChecked));
            paragraph("확인 유지 기한 " + date(watch.expiresAt));
            button("30일 연장", "watch_renew_" + watch.watchId, () -> change(watch.url, null));
            button("이 글 알림 해제", "watch_remove_" + watch.watchId, () -> change(null, watch.watchId));
        }
        paragraph("전체 5개 글·글당 3명 제한은 없어요. 무료 서버의 처리량을 함께 사용하므로 이용량이 많거나 사이트 연결이 지연되면 알림도 늦어질 수 있어요. 같은 글은 함께 확인하며 원본 글의 조회수에 영향을 줄 수 있습니다.");
    }

    private void confirmWatch(String url) {
        if (busy || dialog == null || !dialog.isShowing() || activity.isDestroyed()) return;
        AlertDialog consent = new AlertDialog.Builder(activity, host.isDark()
                ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("이 공개 글의 댓글을 확인할까요?")
                .setMessage("아래 주소를 Cloudflare 알림 서버에 보내고 30일 동안 저장합니다. 알림 목록을 확인하면 유지 기한을 자동으로 연장합니다. 서버가 로그인 없이 댓글 수를 주기적으로 확인해요. 첫 확인은 기준만 저장하고 이후 증가할 때 알립니다.\n\n"
                        + url + "\n\n쿠키·비밀번호·본문·닉네임은 전송하지 않아요.")
                .setNegativeButton("취소", null).setPositiveButton("동의하고 등록", (ignored, which) -> change(url, null)).create();
        confirmation = consent;
        consent.setOnDismissListener(ignored -> { if (confirmation == consent) confirmation = null; });
        consent.setOnShowListener(ignored -> decorate(consent));
        consent.show();
    }

    private void confirmAutomatic() {
        if (dialog == null || !dialog.isShowing() || activity.isDestroyed()) return;
        AlertDialog consent = new AlertDialog.Builder(activity, host.isDark()
                ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle("앞으로 쓴 글을 자동 등록할까요?")
                .setMessage("이 앱에서 새 글 등록이 확인될 때마다 해당 공개 글 주소를 Cloudflare 서버로 보내 30일 동안 댓글 수를 확인합니다. 알림 목록을 확인하면 유지 기한을 자동으로 연장합니다.\n\n"
                        + "예전에 쓴 글이나 읽기만 한 글은 등록하지 않아요. 쿠키·비밀번호·본문·닉네임은 보내지 않아요. 기기당 20개까지 등록할 수 있으며, 최초 확인 이후의 댓글 수 증가를 서버 대기 순서에 따라 알립니다.\n\n"
                        + "끄면 앞으로의 자동 등록이 중단됩니다. 기존 글의 확인도 멈추려면 목록에서 해제해 주세요.")
                .setNegativeButton("취소", null).setPositiveButton("동의하고 켜기", (ignored, which) -> CommunityOptions.setAutoWatchOwnPosts(activity, true)).create();
        confirmation = consent;
        consent.setOnDismissListener(ignored -> {
            if (confirmation == consent) confirmation = null;
            if (dialog != null && dialog.isShowing() && !activity.isDestroyed()) render("");
        });
        consent.setOnShowListener(ignored -> decorate(consent)); consent.show();
    }

    private void refresh() {
        if (busy || dialog == null || !dialog.isShowing()) return;
        busy = true;
        final long expected = ++requestGeneration;
        render("알림 목록을 확인하고 있어요.");
        PushRelayClient.listWatches(activity, new PushRelayClient.WatchesCallback() {
            @Override public void onSuccess(List<PushRelayClient.Watch> result) {
                if (!current(expected)) return;
                busy = false; watches = new ArrayList<>(result); render("");
            }
            @Override public void onError(String message) {
                if (!current(expected)) return;
                busy = false; render(message);
            }
        });
    }

    private void change(String url, String watchId) {
        if (busy || dialog == null || !dialog.isShowing()) return;
        busy = true;
        final long expected = ++requestGeneration;
        render("댓글 알림 설정을 변경하고 있어요.");
        PushRelayClient.Callback callback = new PushRelayClient.Callback() {
            @Override public void onSuccess(String message) {
                if (!current(expected)) return;
                busy = false; refresh();
            }
            @Override public void onError(String message) {
                if (!current(expected)) return;
                busy = false; render(message);
            }
        };
        if (url != null) PushRelayClient.watch(activity, url, callback);
        else PushRelayClient.unwatch(activity, watchId, callback);
    }

    private TextView paragraph(String value) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextSize(13); view.setTextColor(colors.mutedText);
        view.setLineSpacing(dp(3), 1f); view.setPadding(0, dp(7), 0, dp(7));
        content.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private void heading(String value) {
        TextView view = paragraph(value); view.setTextColor(colors.text); view.setTextSize(15); view.setTypeface(null, Typeface.BOLD);
    }
    private void button(String label, String tag, Runnable action) {
        Button button = new Button(activity);
        button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setTextColor(colors.accent);
        button.setTag(tag); button.setMinHeight(dp(48)); button.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = new GradientDrawable(); background.setColor(colors.tint); background.setCornerRadius(dp(12));
        button.setBackground(background); button.setBackgroundTintList(ColorStateList.valueOf(colors.tint));
        button.setEnabled(!busy); button.setOnClickListener(ignored -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(5); params.bottomMargin = dp(5);
        content.addView(button, params);
    }
    private void decorate(AlertDialog target) {
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE}) {
            Button button = target.getButton(which); if (button != null) button.setTextColor(colors.accent);
        }
        Window window = target.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(colors.surface));
            int configured = activity.getResources().getConfiguration().screenWidthDp;
            int width = (configured > 0 ? dp(configured) : activity.getResources().getDisplayMetrics().widthPixels) - dp(24);
            window.setLayout(Math.max(1, Math.min(dp(560), width)), -2);
        }
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private static String date(long seconds) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(seconds * 1000)); }
    private static String articleLabel(String url) {
        String[] parts = URI.create(url).getPath().split("/");
        return parts[2] + " · " + parts[3] + "번 글";
    }
    private static String statusText(String value) {
        switch (value) {
            case "active": return "주기적으로 확인 중";
            case "blocked": return "사이트 접근 제한 · 나중에 재시도";
            case "rate_limited": return "사이트 요청 제한 · 나중에 재시도";
            case "unavailable": return "공개 글을 확인할 수 없음";
            case "parse_error": return "댓글 수를 읽지 못함";
            case "delivery_error": return "알림 전송 재시도 중";
            case "token_expired": return "푸시 연결 정보 갱신 필요";
            default: return "첫 댓글 수 확인 대기";
        }
    }
}
