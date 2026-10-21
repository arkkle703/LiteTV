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
    /**
     * Pauses at the two ends. The one after a full pass is deliberately much longer: a title that
     * only overflows a little is back at its start within a second, and restarting immediately made
     * short guides look like they never stopped twitching.
     */
    private static final long PAUSE_END_MS = 1600;
    /** Pause after rewinding to the start, scaled by how much of the text had been hidden. */
    private static final long PAUSE_START_BASE_MS = 2200;
    /** Retries while the row has not been measured yet; a list row is laid out within a frame. */
    private static final int MAX_LAYOUT_RETRIES = 3;

    /** Measures the label; injectable so the scrolling can be tested without real font metrics. */
    interface TextMeasurer {
        float widthOf(TextView view);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTick = this::tick;
    private final TextMeasurer mMeasurer;

    /** Where the loop currently is, so each end is held for its own pause. */
    private enum Phase {
        /** Moving towards the far end. */
        SCROLLING,
        /** Reached the far end: holding so the hidden tail can be read. */
        AT_END,
        /** Back at the start: holding before the next pass. */
        AT_START
    }

    private TextView mView;
    private int mRange;
    private int mOffset;
    private int mLayoutRetries;
    private Phase mPhase = Phase.SCROLLING;

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
        boolean sameView = mView == view;
        if (!sameView) {
            releaseCurrent();
            mView = view;
            mOffset = 0;
        }
        mLayoutRetries = 0;
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
        int newRange = (int) Math.ceil(textWidth - available);
        // Repeated rebinds (the guide calls notifyDataSetChanged whenever its data or focus
        // changes) must not restart the marquee: a fresh attach for the same text on the same view
        // keeps the offset, so the pauses at the ends are actually honoured instead of being cut
        // short by the next rebind jumping back to the start.
        if (sameView && newRange == mRange) {
            // Same text on the same row: keep the position, and leave an end pause alone rather
            // than rescheduling over it (a rebind must not shorten the hold).
            if (mPhase == Phase.SCROLLING) {
                scheduleNext(STEP_MS);
            }
            return;
        }
        mOffset = 0;
        mRange = newRange;
        mPhase = Phase.SCROLLING;
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
        mPhase = Phase.SCROLLING;
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
            // Gave up waiting for a measurement: settle on a plain ellipsis instead of leaving the
            // label in a half-configured marquee state.
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setSelected(false);
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
        switch (mPhase) {
            case AT_END:
                // Held long enough to read the tail, now rewind to the start.
                mPhase = Phase.AT_START;
                mOffset = 0;
                view.scrollTo(0, 0);
                // The longer the hidden tail was, the longer the reader needs at the start before
                // it all scrolls away again.
                float hidden = mRange > 0
                        ? (float) mRange / (mRange + availableWidth(view)) : 0f;
                scheduleNext(PAUSE_START_BASE_MS + (long) (PAUSE_START_BASE_MS * hidden));
                return;
            case AT_START:
                mPhase = Phase.SCROLLING;
                scheduleNext(STEP_MS);
                return;
            default:
                break;
        }
        int next = mOffset + STEP_PX;
        if (next >= mRange) {
            // Reached the far end (or overshot by the last step): clamp and hold there. This is
            // reached with mOffset == mRange, so it must not be detected by comparing offsets --
            // an even range lands exactly on it and an offset test would skip the pause entirely.
            mPhase = Phase.AT_END;
            mOffset = mRange;
            view.scrollTo(mOffset, 0);
            scheduleNext(PAUSE_END_MS);
            return;
        }
        mOffset = next;
        view.scrollTo(mOffset, 0);
        scheduleNext(STEP_MS);
    }
}
