package com.cabletv.player.epg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exercises the rolling 7-day window of the disk cache: multi-day round trips, pruning of days
 * older than the window, and the version guard that drops legacy cache files.
 */
@RunWith(RobolectricTestRunner.class)
public class EpgCacheDayTest {

    private Context mContext;
    private EpgCache mCache;

    /** Noon of the day {@code daysAgo} days before today, in the device timezone. */
    private static long noon(int daysAgo) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo);
        calendar.set(Calendar.HOUR_OF_DAY, 12);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mCache = new EpgCache(mContext);
        mCache.clearCache();
    }

    @Test
    public void roundTripsTodayAndYesterdayWithManifest() {
        List<EpgManager.Program> list = new ArrayList<>();
        list.add(new EpgManager.Program("昨日节目", noon(1), noon(1) + 3600_000L));
        list.add(new EpgManager.Program("今日节目", noon(0), noon(0) + 3600_000L));
        Map<String, List<EpgManager.Program>> programs = new HashMap<>();
        programs.put("cctv1", list);

        Map<String, Map<String, Long>> days = new HashMap<>();
        days.put(EpgCache.dayKey(noon(1)), channelTimes("cctv1", 1000L));
        days.put(EpgCache.dayKey(noon(0)), channelTimes("cctv1", 2000L));
        mCache.savePrograms(programs, days);

        EpgCache.CacheData loaded = mCache.loadPrograms();
        assertNotNull(loaded);
        assertEquals(1, loaded.programsByChannel.size());
        assertEquals("both days survive the round trip", 2, loaded.programsByChannel.get("cctv1").size());
        assertEquals("manifest round-trips too", 2, loaded.fetchedDays.size());
        assertTrue(loaded.fetchedDays.containsKey(EpgCache.dayKey(noon(1))));
    }

    @Test
    public void prunesDaysOlderThanTheWindow() {
        List<EpgManager.Program> list = new ArrayList<>();
        list.add(new EpgManager.Program("八天前", noon(8), noon(8) + 3600_000L));
        list.add(new EpgManager.Program("今日", noon(0), noon(0) + 3600_000L));
        Map<String, List<EpgManager.Program>> programs = new HashMap<>();
        programs.put("cctv1", list);
        mCache.savePrograms(programs, Collections.emptyMap());

        EpgCache.CacheData loaded = mCache.loadPrograms();
        assertNotNull(loaded);
        List<EpgManager.Program> kept = loaded.programsByChannel.get("cctv1");
        assertEquals("only today survives", 1, kept.size());
        assertEquals("今日", kept.get(0).title);
    }

    @Test
    public void prunesStaleManifestDays() {
        Map<String, Map<String, Long>> days = new HashMap<>();
        days.put(EpgCache.dayKey(noon(9)), channelTimes("cctv1", 1000L));
        days.put(EpgCache.dayKey(noon(0)), channelTimes("cctv1", 1000L));
        mCache.savePrograms(Collections.emptyMap(), days);

        EpgCache.CacheData loaded = mCache.loadPrograms();
        assertNotNull(loaded);
        assertEquals("stale day pruned, today kept", 1, loaded.fetchedDays.size());
        assertTrue(loaded.fetchedDays.containsKey(EpgCache.dayKey(noon(0))));
    }

    @Test
    public void ignoresCacheFilesFromAnOlderVersion() throws Exception {
        File dir = new File(mContext.getCacheDir(), "epg_cache");
        assertTrue(dir.mkdirs() || dir.exists());
        File file = new File(dir, "epg_programs.json");
        Writer writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8);
        writer.write("{\"version\":3,\"timestamp\":1,\"channels\":{}}");
        writer.close();

        assertNull("v3 cache must be ignored so it is refetched", mCache.loadPrograms());
    }

    @Test
    public void dayKeyMatchesLocalCalendarDate() {
        String key = EpgCache.dayKey(noon(0));
        assertEquals("yyyyMMdd", 8, key.length());
        Calendar calendar = Calendar.getInstance();
        String expected = String.format("%04d%02d%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
        assertEquals(expected, key);
    }

    private static Map<String, Long> channelTimes(String channel, long fetchedAt) {
        Map<String, Long> times = new HashMap<>();
        times.put(channel, fetchedAt);
        return times;
    }
}