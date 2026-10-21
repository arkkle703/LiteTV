package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Duration;

/**
 * The marquee has to hold at the far end before it rewinds. That end used to be detected by
 * comparing the offset with the range, which never fires: the scroll steps by two pixels and an
 * even range lands exactly on it, so the label jumped back with no pause and the loop looked like
 * a twitch. These cases pin the two holds for both an even and an odd range.
 */
@RunWith(RobolectricTestRunner.class)
public class RowLabelScrollerPauseTest {

    private Context mContext;
    private RowLabelScroller mScroller;
    private TextView mView;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mScroller = new RowLabelScroller(view -> 400f);   // a text wider than the row
        mView = new TextView(mContext);
        mView.setSingleLine(true);
        mView.setText("实况录像-2026/2027赛季中国男子篮球职业联赛 第2轮 北京首钢-广东东阳光");
        mView.measure(0, 0);
        mView.layout(0, 0, 140, 40);                    // 140px available -> range 260 (even)
    }

    private void advance(long millis) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** Scrolls until the label stops moving, which is the far end. */
    private int scrollUntilItStops() {
        int last = -1;
        for (int i = 0; i < 120; i++) {
            advance(100);
            int now = mView.getScrollX();
            if (now == last && now > 0) {
                return now;
            }
            last = now;
        }
        return last;
    }

    @Test
    public void holdsAtTheFarEndBeforeRewinding() {
        mScroller.attach(mView, true);
        int atEnd = scrollUntilItStops();
        assertTrue("the label should have reached the end", atEnd > 0);

        advance(400);                                   // inside the end hold
        assertEquals("it must stay at the end while the pause runs", atEnd, mView.getScrollX());
        assertTrue("the end hold has not expired yet", mView.getScrollX() > 0);

        advance(2200);                                  // past the hold
        assertEquals("after the hold it rewinds to the start", 0, mView.getScrollX());
    }

    @Test
    public void holdsAtTheStartBeforeScrollingAgain() {
        mScroller.attach(mView, true);
        scrollUntilItStops();
        advance(2200);                                  // past the end hold -> at the start

        assertEquals(0, mView.getScrollX());
        advance(1500);                                  // inside the start hold
        assertEquals("the start hold keeps it still", 0, mView.getScrollX());
        advance(4000);                                  // and then it moves again
        assertTrue("scrolling resumes", mView.getScrollX() > 0);
    }

    @Test
    public void rebindingMidPauseDoesNotCancelTheHold() {
        mScroller.attach(mView, true);
        int atEnd = scrollUntilItStops();                // reach the end and start holding

        mScroller.attach(mView, true);                   // a rebind while holding (guide refresh)
        advance(400);
        assertEquals("a rebind must not shorten the end hold", atEnd, mView.getScrollX());
    }
}
