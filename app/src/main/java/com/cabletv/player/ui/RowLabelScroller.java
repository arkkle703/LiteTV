package com.cabletv.player.ui;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;

/**
 * Scrolls the label of the highlighted row when the text is too wide for it.
 *
 * <p>{@link TextView} has a marquee built in, but on the televisions this app runs on it did not
 * stay running: the guide's rows are bound while their width is still unknown, and a marquee that
 * starts before the view has been measured scrolls once and then stands still (a truncated title
 * was left frozen half-way through the text). Driving the offset here costs a handful of lines and
 * behaves the same on every device.
 *
 * <p>One label scrolls at a time: the highlighted row is the one being read, and a list where every
 * row moves is unreadable. Attaching another label releases the previous one, and a label that is
 * re-bound without the highlight stops and goes back to a plain ellipsis.
 */
class RowLabelScroller {

    /** Slow enough to read: 2px per frame is roughly 60px a second. */
    private static final int STEP_PX = 2;
    private static final long STEP_MS = 33;
    /** A pause at each end before starting over, the way a marquee reads. */
    private static final long PAUSE_MS = 1200;
    /** Retries while the row has not been measured yet; a list row is laid out within a frame. */
    private static final int MAX_LAYOUT_RETRIES = 3;

    /** Measures the label; injectable so the scrolling can be tested without real font metrics. */
    interface TextMeasurer {
        float widthOf(TextView view);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::tick;
    private final TextMeasurer mMeasurer;

    private TextView mView;
    private int mRange;
    private int mOffset;
    private int mLayoutRetries;

    RowLabelScroller() {
        this(view -> view.getPaint().measureText(String.valueOf(view.getText())));
    }

    RowLabelScroller(TextMeasurer measurer) {
        mMeasurer = measurer;
    }

    /**
     * Points the scroller at a label: {@code scrolling} means "this row is the highlighted one".
     * A label whose text fits is left alone.
     */
    void attach(TextView view, boolean scrolling) {
        if (view == null) {
            return;
        }
        if (!scrolling) {
            release(view);
            return;
        }
        if (mView != view) {
            releaseCurrent();
            mView = view;
            mOffset = 0;
            mLayoutRetries = 0;
        }
        int available = availableWidth(view);
        if (available <= 0) {
            retryAfterLayout(view);
            return;
        }
        float textWidth = mMeasurer.widthOf(view);
        if (!overflows(textWidth, available)) {
            // Nothing hidden: no scrolling and no ellipsis needed.
            stopTicking();
            view.setEllipsize(null);
            view.scrollTo(0, 0);
            return;
        }
        mRange = (int) Math.ceil(textWidth - available);
        view.setSingleLine(true);
        // Cleared so the framework does not cut the text at the view edge while it is shifted.
        view.setEllipsize(null);
        view.setHorizontallyScrolling(true);
        scheduleNext(0L);
    }

    /** Stops and forgets the label, restoring it to a plain single-line ellipsis. */
    void release(TextView view) {
        if (view == null) {
            return;
        }
        if (mView == view) {
            releaseCurrent();
        }
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.scrollTo(0, 0);
    }

    /** Stops every tick; used when the list is hidden. */
    void stop() {
        releaseCurrent();
    }

    /** True when the text is wider than the space it has. */
    static boolean overflows(float textWidth, int availableWidth) {
        return availableWidth > 0 && textWidth > availableWidth;
    }

    private void releaseCurrent() {
        stopTicking();
        if (mView != null) {
            mView.scrollTo(0, 0);
            mView = null;
        }
        mRange = 0;
        mOffset = 0;
    }

    private int availableWidth(TextView view) {
        return view.getWidth() - view.getPaddingLeft() - view.getPaddingRight();
    }

    private void retryAfterLayout(TextView view) {
        if (mLayoutRetries++ >= MAX_LAYOUT_RETRIES) {
            return;
        }
        view.post(() -> {
            if (mView == view) {
                attach(view, true);
            }
        });
    }

    private void scheduleNext(long delayMs) {
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, delayMs);
    }

    private void stopTicking() {
        mHandler.removeCallbacks(mTick);
    }

    private void tick() {
        TextView view = mView;
        if (view == null) {
            return;
        }
        int next = mOffset + STEP_PX;
        if (next > mRange) {
            // Start over, after a pause so the two ends are told apart rather than looking joined.
            mOffset = 0;
        } else {
            mOffset = next;
        }
        view.scrollTo(mOffset, 0);
        scheduleNext(mOffset == 0 ? PAUSE_MS : STEP_MS);
    }
}
