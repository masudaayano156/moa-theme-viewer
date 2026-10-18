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
import android.widget.TextView;
import java.net.URI;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** Recent automatic watches and explicit controls for individual public posts. */
public final class CommentWatchDialog {
    public interface Host {
        ThemeSpec.Palette palette();
        boolean isDark();
        String currentUrl();
        void openPushSettings();
    }

    private final Activity activity;
    private final Host host;
    private AlertDialog dialog;
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
                .setTitle("최근 알림 글").setView(scroll).setPositiveButton("닫기", null).create();
        dialog = created;
        created.setOnDismissListener(ignored -> { requestGeneration++; busy = false; if (dialog == created) dialog = null; });
        created.setOnShowListener(ignored -> decorate(created));
        render("");
        created.show();
        if (PushRelayClient.isPaired(activity)) refresh();
    }

    public void dismiss() {
        requestGeneration++;
        if (dialog != null) dialog.dismiss();
        watches = Collections.emptyList();
    }

    /** Only resize existing windows; do not restart requests or reset the selected watches. */
    public void resize() {
        if (dialog != null && dialog.isShowing()) decorate(dialog);
    }

    private boolean current(long expected) {
        return requestGeneration == expected && dialog != null && dialog.isShowing()
                && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void render(String message) {
        content.removeAllViews();
        paragraph("알림을 켜면 이 앱에서 작성한 글의 댓글과 내 댓글의 답글을 작성 후 3일간 자동으로 받아요. 글 개수 제한이나 글마다 따로 동의하는 절차는 없어요.");
        paragraph("3일이 지나면 해당 글 확인을 자동으로 멈춥니다. 댓글이 달리거나 앱을 열어도 기간은 늘어나지 않아요. 주기적으로 확인하므로 즉시 알림은 아니에요. 서버 대기량에 따라 더 늦어질 수 있고, 본인 댓글도 포함됩니다.");
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
        if (!CommunityOptions.autoWatchOwnPosts(activity))
            button("내 글 댓글 알림 켜기", "watch_enable_notifications", () -> { dismiss(); host.openPushSettings(); });
        String currentUrl = PushNotifications.canonicalWatchTarget(host.currentUrl());
        if (currentUrl != null) {
            heading(currentUrl.contains("?comment=") ? "현재 댓글" : "현재 글");
            paragraph(articleLabel(currentUrl));
            boolean saved = false;
            for (PushRelayClient.Watch watch : watches) if (watch.url.equals(currentUrl)) saved = true;
            button(saved ? "지금부터 3일 더 받기" : currentUrl.contains("?comment=") ? "이 댓글 답글 알림 받기" : "이 글도 알림 받기", "watch_current", () -> change(currentUrl, null));
        } else paragraph("게시글을 연 뒤 이 화면에서 그 글의 댓글 알림을 등록할 수 있어요.");
        button("알림 목록 새로고침", "watch_refresh", this::refresh);
        heading("최근 알림 글");
        if (watches.isEmpty() && !busy) paragraph("앞으로 작성한 글과 댓글의 알림이 여기에 자동으로 나타납니다.");
        for (PushRelayClient.Watch watch : watches) {
            View line = new View(activity); line.setBackgroundColor(colors.border);
            content.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            heading(articleLabel(watch.url));
            paragraph(statusText(watch.status) + (watch.baselineCount == null ? "" : " · 댓글 " + watch.baselineCount));
            if (watch.lastChecked > 0) paragraph("최근 확인 " + date(watch.lastChecked));
            paragraph("자동 확인 종료 " + date(watch.expiresAt));
            button("지금부터 3일 더 받기", "watch_renew_" + watch.watchId, () -> change(watch.url, null));
            button("이 글 알림 해제", "watch_remove_" + watch.watchId, () -> change(null, watch.watchId));
        }
        paragraph("목록에는 최근 글만 표시합니다. 이 목록의 표시 개수는 알림을 받을 수 있는 글 수의 제한이 아닙니다. 원하는 다른 글도 ‘이 글도 알림 받기’로 추가할 수 있어요. 공개 글 주소와 댓글 수만 서버에 저장하며 원본 글의 조회수에 영향을 줄 수 있습니다.");
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
        return parts[2] + " · " + parts[3] + "번 글" + (url.contains("?comment=") ? " · 댓글 답글" : "");
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
