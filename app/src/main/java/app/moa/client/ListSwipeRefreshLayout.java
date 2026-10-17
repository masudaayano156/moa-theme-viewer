package app.moa.client;

import android.content.Context;
import android.content.res.Configuration;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/** Standard refresh indicator with a single-finger, predominantly vertical gesture gate. */
final class ListSwipeRefreshLayout extends SwipeRefreshLayout {
    interface Eligibility { boolean allowed(); }
    interface DocumentProbe { void check(java.util.function.Consumer<Boolean> result); }
    private final Eligibility eligibility;
    private final DocumentProbe probe;
    private int slop;
    private float startX, startY;
    private boolean blocked, vertical, documentAllowed;
    private long gesture;
    private final Runnable loadingTimeout = () -> setRefreshing(false);

    ListSwipeRefreshLayout(Context context, Eligibility eligibility, DocumentProbe probe, Runnable refresh) {
        super(context);
        this.eligibility = eligibility;
        this.probe = probe;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setOnChildScrollUpCallback((parent, child) -> !eligibility.allowed());
        setOnRefreshListener(() -> {
            if (!blocked && vertical && documentAllowed && eligibility.allowed()) refresh.run();
            else finish();
        });
    }

    void beginLoading() { removeCallbacks(loadingTimeout); setRefreshing(true); postDelayed(loadingTimeout, 20_000); }
    void finish() { removeCallbacks(loadingTimeout); setRefreshing(false); }

    void colors(int accent, int surface) {
        setColorSchemeColors(accent);
        setProgressBackgroundColorSchemeColor(surface);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            startX = event.getX(); startY = event.getY(); vertical = false;
            blocked = !eligibility.allowed() || event.getPointerCount() != 1;
            documentAllowed = false;
            long expected = ++gesture;
            if (!blocked) probe.check(allowed -> {
                if (gesture != expected) return;
                documentAllowed = Boolean.TRUE.equals(allowed);
                if (!documentAllowed) blocked = true;
            });
        } else if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_POINTER_DOWN || !eligibility.allowed()) {
            cancelGesture(event); return false;
        }
        if (blocked) return false;
        if (action == MotionEvent.ACTION_MOVE && !documentAllowed) return false;
        if (!acceptDirection(event)) return false;
        return super.onInterceptTouchEvent(event);
    }

    private boolean acceptDirection(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !vertical) {
            float dx = Math.abs(event.getX() - startX), dy = event.getY() - startY;
            if (dx > slop && dx * 1.5f >= Math.abs(dy)) { cancelGesture(event); return false; }
            if (dy < -slop) { cancelGesture(event); return false; }
            if (dy <= slop || dy <= dx * 1.5f) return false;
            vertical = true;
        }
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (blocked || event.getPointerCount() != 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                || !eligibility.allowed()) { cancelGesture(event); return false; }
        if (!documentAllowed && event.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        if (!acceptDirection(event)) return false;
        return super.onTouchEvent(event);
    }

    private void cancelGesture(MotionEvent event) {
        if (!blocked) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.onTouchEvent(cancel); super.onInterceptTouchEvent(cancel); cancel.recycle();
        }
        blocked = true;
        documentAllowed = false;
        if (!isRefreshing()) setRefreshing(false);
    }

    @Override protected void onDetachedFromWindow() { finish(); super.onDetachedFromWindow(); }

    @Override protected void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        finish();
        slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        float density = getResources().getDisplayMetrics().density;
        setSize(DEFAULT);
        setDistanceToTriggerSync(Math.round(64 * density));
        setProgressViewOffset(false, -getProgressCircleDiameter(), Math.round(64 * density));
    }
}
