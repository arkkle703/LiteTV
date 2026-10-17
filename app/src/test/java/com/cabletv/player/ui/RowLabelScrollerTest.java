package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Duration;

/**
 * A label too wide for its row scrolls while it is the highlighted one. The framework's own marquee
 * was tried first and stalled on the television (it starts before the row has been measured), so the
 * offset is driven here — which these cases pin down, including that the scrolling stops and resets
 * when the row is no longer the highlighted one.
 */
@RunWith(RobolectricTestRunner.class)
public class RowLabelScrollerTest {

    private static final String LONG_TEXT = "实况录像-2026年亚运会 女子篮球决赛 完整版 第7轮";

    private Context mContext;
    private RowLabelScroller mScroller;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        // Robolectric has no font metrics, so the label is reported as wider than its row.
        mScroller = new RowLabelScroller(view -> 500f);
    }

    /** A label laid out to a known width, the way a row is before it is bound. */
    private TextView laidOutLabel(String text, int widthPx) {
        TextView view = new TextView(mContext);
        view.setText(text);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.measure(0, 0);
        view.layout(0, 0, widthPx, 40);
        return view;
    }

    private static void advance(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    @Test
    public void overflows_onlyWhenTheTextIsWiderThanTheSpace() {
        assertTrue(RowLabelScroller.overflows(120f, 100));
        assertFalse("a label that fits must not move", RowLabelScroller.overflows(80f, 100));
        assertFalse("not measured yet", RowLabelScroller.overflows(120f, 0));
    }

    @Test
    public void highlightedLabelScrolls() {
        TextView view = laidOutLabel(LONG_TEXT, 120);
        mScroller.attach(view, true);
        advance(500);

        assertTrue("the text should have moved: scrollX=" + view.getScrollX(), view.getScrollX() > 0);
    }

    @Test
    public void labelThatFitsStaysStill() {
        RowLabelScroller fitting = new RowLabelScroller(view -> 10f);
        TextView view = laidOutLabel("新闻联播", 400);
        fitting.attach(view, true);
        advance(500);

        assertEquals(0, view.getScrollX());
    }

    @Test
    public void releasingStopsAndRewinds() {
        TextView view = laidOutLabel(LONG_TEXT, 120);
        mScroller.attach(view, true);
        advance(500);
        assertTrue(view.getScrollX() > 0);

        mScroller.attach(view, false);
        assertEquals("a row that is no longer highlighted goes back to its start", 0, view.getScrollX());
        advance(500);
        assertEquals("and stops moving", 0, view.getScrollX());
        assertEquals(TextUtils.TruncateAt.END, view.getEllipsize());
    }

    @Test
    public void onlyOneLabelScrollsAtATime() {
        TextView first = laidOutLabel(LONG_TEXT, 120);
        TextView second = laidOutLabel(LONG_TEXT, 120);
        mScroller.attach(first, true);
        advance(400);
        assertTrue(first.getScrollX() > 0);

        mScroller.attach(second, true);
        advance(400);

        assertEquals("the row that lost the highlight stops", 0, first.getScrollX());
        assertTrue(second.getScrollX() > 0);
    }

    @Test
    public void stoppingLeavesTheLabelsAlone() {
        TextView view = laidOutLabel(LONG_TEXT, 120);
        mScroller.attach(view, true);
        advance(400);

        mScroller.stop();
        assertEquals(0, view.getScrollX());
        advance(400);
        assertEquals(0, view.getScrollX());
    }
}
