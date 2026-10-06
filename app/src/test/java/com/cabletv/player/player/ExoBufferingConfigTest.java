package com.cabletv.player.player;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;

/**
 * Guards the live buffering policy. {@code DefaultLoadControl} rejects a configuration whose
 * {@code minBufferMs} is below either playback threshold, and shipping exactly that combination
 * crashed the app as soon as it tried to play the first channel (issue #6). The second test
 * proves the assertion is really what this build of ExoPlayer enforces, so the first test cannot
 * silently become vacuous.
 */
@RunWith(RobolectricTestRunner.class)
public class ExoBufferingConfigTest {

    @Test
    public void appLoadControl_isValid() {
        // Constructing it is the assertion: an invalid combination throws IllegalArgumentException.
        LoadControl loadControl = ExoMediaPlayer.createDefaultLoadControl();
        assertNotNull(loadControl);
    }

    @Test
    public void invalidThresholds_areRejected() {
        // The regression that shipped in 1.3.0: minBufferMs (1s) < after-rebuffer (2.5s).
        try {
            new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_000, 10_000, 500, 2_500)
                    .build();
            fail("Expected the invalid buffering combination to be rejected");
        } catch (IllegalArgumentException expected) {
            // ExoPlayer asserts minBufferMs >= bufferForPlaybackAfterRebufferMs.
        }
    }
}
