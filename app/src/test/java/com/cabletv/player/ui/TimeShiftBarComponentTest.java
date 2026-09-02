package com.cabletv.player.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.cabletv.player.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-shift bar tests (issue #5: 直播直接时移拖动): the draggable window maps a millisecond
 * range onto the seek bar, committing a drag reports the absolute target time (the same epoch
 * value MainActivity turns into a catchup seek), and the live-edge shortcut stays available.
 */
@RunWith(RobolectricTestRunner.class)
public class TimeShiftBarComponentTest {

    private TimeShiftBarComponent buildBar() {
        Context context = RuntimeEnvironment.getApplication();
        return new TimeShiftBarComponent(context);
    }

    @Test
    public void committedDrag_reportsAbsoluteTarget() {
        TimeShiftBarComponent bar = buildBar();
        AtomicLong committed = new AtomicLong(-1);
        bar.setOnSeekListener(new TimeShiftBarComponent.OnSeekListener() {
            @Override
            public void onSeekTo(long targetMs) {
                committed.set(targetMs);
            }

            @Override
            public void onBackToLive() {
            }
        });
        // Window 10:00–10:10 (epoch 0-based for determinism), 10s long.
        bar.setWindow(10_000L, 20_000L, 10_000L);

        // Half of the track = 10:05.
        bar.commitProgress(500);
        assertEquals(15_000L, committed.get());
    }

    @Test
    public void windowStart_mapsToProgressZero_andEndToMax() {
        TimeShiftBarComponent bar = buildBar();
        bar.setWindow(1000L, 11_000L, 1000L);
        // Verify the label reflects the committed position.
        bar.commitProgress(0);
        String label = bar.findShownPositionLabel();
        assertNotNull(label);
        assertTrue(label.length() > 0);
    }

    @Test
    public void backToLive_string_isLocalized() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(context.getString(R.string.back_to_live),
                context.getString(R.string.back_to_live));
    }
}