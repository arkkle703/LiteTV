package com.cabletv.player.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import com.orhanobut.hawk.Hawk;

import java.util.Arrays;
import java.util.Collections;

/**
 * Config-layer tests: the local-file path, M3U URL and EPG settings are persisted through
 * Hawk. These mirror what the settings screen writes and what ChannelRepository reads.
 */
@RunWith(RobolectricTestRunner.class)
public class AppConfigTest {

    @Before
    public void setUp() {
        // App.onCreate normally does this; re-init with the Robolectric context to be explicit.
        Hawk.init(RuntimeEnvironment.getApplication()).build();
    }

    @Test
    public void m3uFilePath_roundTrips() {
        AppConfig.setM3uFilePath("");
        assertEquals("", AppConfig.getM3uFilePath());

        AppConfig.setM3uFilePath("/storage/usb1/channels.m3u");
        assertEquals("/storage/usb1/channels.m3u", AppConfig.getM3uFilePath());

        AppConfig.setM3uFilePath("");
        assertEquals("", AppConfig.getM3uFilePath());
    }

    @Test
    public void m3uUrlRoundTrips_andDefaultsToEmpty() {
        assertEquals("", AppConfig.getM3uUrl());
        AppConfig.setM3uUrl("http://example.com/list.m3u");
        assertEquals("http://example.com/list.m3u", AppConfig.getM3uUrl());
    }

    @Test
    public void epgPreference_defaultsToAuto() {
        assertEquals(AppConfig.EPG_PREFERENCE_AUTO, AppConfig.getEpgPreference());
        AppConfig.setEpgPreference(AppConfig.EPG_PREFERENCE_M3U);
        assertEquals(AppConfig.EPG_PREFERENCE_M3U, AppConfig.getEpgPreference());
        // Garbage falls back to auto.
        AppConfig.setEpgPreference("bogus");
        assertEquals(AppConfig.EPG_PREFERENCE_AUTO, AppConfig.getEpgPreference());
    }

    @Test
    public void resolveEpgUrls_autoPrefersUserThenM3u() {
        AppConfig.setEpgUrl("http://user/epg.xml");
        AppConfig.setM3uEpgUrls(Arrays.asList("http://m3u/epg.xml"));
        AppConfig.setEpgPreference(AppConfig.EPG_PREFERENCE_AUTO);
        assertEquals(Collections.singletonList("http://user/epg.xml"), AppConfig.resolveEpgUrls());

        AppConfig.setEpgUrl("");
        assertEquals(Collections.singletonList("http://m3u/epg.xml"), AppConfig.resolveEpgUrls());
    }

    @Test
    public void resolveEpgUrls_m3uPreferenceWins() {
        AppConfig.setEpgUrl("http://user/epg.xml");
        AppConfig.setM3uEpgUrls(Arrays.asList("http://m3u/epg.xml"));
        AppConfig.setEpgPreference(AppConfig.EPG_PREFERENCE_M3U);
        assertEquals(Collections.singletonList("http://m3u/epg.xml"), AppConfig.resolveEpgUrls());

        AppConfig.setEpgPreference(AppConfig.EPG_PREFERENCE_USER);
        assertEquals(Collections.singletonList("http://user/epg.xml"), AppConfig.resolveEpgUrls());
    }

    @Test
    public void m3uEpgUrls_emptyByDefault() {
        assertTrue(AppConfig.getM3uEpgUrls().isEmpty());
        AppConfig.setM3uEpgUrls(Collections.singletonList("http://m3u/epg.xml"));
        assertFalse(AppConfig.getM3uEpgUrls().isEmpty());
    }
}