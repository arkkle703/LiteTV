package com.cabletv.player.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import com.cabletv.player.config.AppConfig;

import xyz.doikki.videoplayer.exo.ExoMediaPlayer;
import xyz.doikki.videoplayer.player.VideoViewConfig;

/**
 * Covers the playback settings added for the picture-quality work (issues #1/#2): the stored
 * values must survive a round trip, unknown values must fall back to the safe default, and the
 * decode modes must map onto the decoder policies the player understands.
 */
@RunWith(RobolectricTestRunner.class)
public class PlaybackSettingsTest {

    @Before
    public void setUp() {
        com.orhanobut.hawk.Hawk.init(RuntimeEnvironment.getApplication()).build();
    }

    @Test
    public void renderMode_defaultsToSurfaceView() {
        assertEquals("the sharper path must be the default", AppConfig.RENDER_SURFACE,
                AppConfig.getVideoRenderMode());
    }

    @Test
    public void renderMode_roundTripsAndRejectsUnknownValues() {
        AppConfig.setVideoRenderMode(AppConfig.RENDER_TEXTURE);
        assertEquals(AppConfig.RENDER_TEXTURE, AppConfig.getVideoRenderMode());
        AppConfig.setVideoRenderMode(42);
        assertEquals(AppConfig.RENDER_SURFACE, AppConfig.getVideoRenderMode());
    }

    @Test
    public void decodeMode_defaultsToAutoAndRejectsUnknownValues() {
        assertEquals(VideoViewConfig.DECODE_AUTO, AppConfig.getVideoDecodeMode());
        AppConfig.setVideoDecodeMode(VideoViewConfig.DECODE_SOFTWARE);
        assertEquals(VideoViewConfig.DECODE_SOFTWARE, AppConfig.getVideoDecodeMode());
        AppConfig.setVideoDecodeMode(-7);
        assertEquals("an unknown mode must not disable decoding", VideoViewConfig.DECODE_AUTO,
                AppConfig.getVideoDecodeMode());
    }

    @Test
    public void autoDecode_keepsExoPlayersOwnPreference() {
        assertNull("auto must not override the platform choice",
                ExoMediaPlayer.createCodecSelector(VideoViewConfig.DECODE_AUTO));
    }

    @Test
    public void explicitDecodeModes_getDistinctSelectors() {
        MediaCodecSelector hardware = ExoMediaPlayer.createCodecSelector(VideoViewConfig.DECODE_HARDWARE);
        MediaCodecSelector software = ExoMediaPlayer.createCodecSelector(VideoViewConfig.DECODE_SOFTWARE);
        assertNotNull(hardware);
        assertNotNull(software);
        assertNotSame(hardware, software);
    }

    @Test
    public void videoViewConfig_exposesTheNewKnobs() {
        VideoViewConfig config = VideoViewConfig.newBuilder()
                .setVideoDecodeMode(VideoViewConfig.DECODE_SOFTWARE)
                .setTunnelingEnabled(true)
                .build();
        assertEquals(VideoViewConfig.DECODE_SOFTWARE, config.mVideoDecodeMode);
        assertTrue(config.mTunnelingEnabled);
        assertNotNull("a render factory must always be present", config.mRenderViewFactory);
    }
}
