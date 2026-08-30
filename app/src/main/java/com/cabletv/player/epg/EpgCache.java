package com.cabletv.player.epg;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk EPG cache. All methods do blocking file IO and must be called off the main thread.
 */
public class EpgCache {
    private static final String TAG = "EpgCache";
    private static final String CACHE_DIR = "epg_cache";
    private static final String CACHE_FILE = "epg_programs.json";
    private static final String META_FILE = "epg_meta.json";
    // 2: programmes are keyed by normalised channel name instead of the (non-unique) tvg-id.
    // 3: repeated programmes are dropped at parse time; caches written before that still hold them.
    // 4: keeps a rolling 7-day window (per-channel fetched-day manifest) so the guide can show
    //    yesterday etc.; older days are pruned on write, and v3 caches are ignored (refetched).
    private static final int CACHE_VERSION = 4;
    /** Rolling guide window: today plus up to this many days back (and one day ahead). */
    private static final int RETENTION_DAYS = 7;
    private static final int FUTURE_DAYS = 1;

    private final File mCacheDir;
    private final File mCacheFile;
    private final File mMetaFile;
    private final Gson mGson;

    /** Save timestamp, kept in memory so freshness checks never re-read the cache. */
    private volatile long mCachedTimestamp = 0;

    public EpgCache(Context context) {
        mCacheDir = new File(context.getCacheDir(), CACHE_DIR);
        if (!mCacheDir.exists() && !mCacheDir.mkdirs()) {
            Log.w(TAG, "Cannot create EPG cache dir: " + mCacheDir);
        }
        mCacheFile = new File(mCacheDir, CACHE_FILE);
        mMetaFile = new File(mCacheDir, META_FILE);
        // Compact output: this file is only ever read back by the app and can reach several MB.
        mGson = new Gson();
    }

    /** Loaded cache contents: programmes plus which days have guide data per channel. */
    public static class CacheData {
        public final Map<String, List<EpgManager.Program>> programsByChannel = new HashMap<>();
        /** dayKey (yyyyMMdd) -> channelKey -> fetch timestamp. */
        public final Map<String, Map<String, Long>> fetchedDays = new HashMap<>();
    }

    public void savePrograms(Map<String, List<EpgManager.Program>> programsByChannel,
                             Map<String, Map<String, Long>> fetchedDays) {
        long timestamp = System.currentTimeMillis();
        File tmp = new File(mCacheDir, CACHE_FILE + ".tmp");
        try {
            // The system may wipe the cache directory while we are running, so re-create it here
            // rather than trusting the one made in the constructor.
            if (!mCacheDir.exists() && !mCacheDir.mkdirs()) {
                throw new IOException("Cannot create EPG cache dir: " + mCacheDir);
            }
            JsonObject root = new JsonObject();
            root.addProperty("version", CACHE_VERSION);
            root.addProperty("timestamp", timestamp);

            // Only days inside the rolling window survive a write; tomorrow is kept because the
            // feeds (51zmt's e.xml etc.) carry it alongside today.
            long todayDay = localDayNumber(System.currentTimeMillis());
            long minDay = todayDay - (RETENTION_DAYS - 1);
            long maxDay = todayDay + FUTURE_DAYS;

            JsonObject channelsObj = new JsonObject();
            for (Map.Entry<String, List<EpgManager.Program>> entry : programsByChannel.entrySet()) {
                JsonArray programsArray = new JsonArray();
                for (EpgManager.Program prog : entry.getValue()) {
                    long day = localDayNumber(prog.startTime);
                    if (day < minDay || day > maxDay) {
                        continue; // stale day: drop rather than let the window grow forever
                    }
                    JsonObject progObj = new JsonObject();
                    progObj.addProperty("title", prog.title);
                    progObj.addProperty("startTime", prog.startTime);
                    progObj.addProperty("endTime", prog.endTime);
                    programsArray.add(progObj);
                }
                if (programsArray.size() > 0) {
                    channelsObj.add(entry.getKey(), programsArray);
                }
            }
            root.add("channels", channelsObj);

            JsonObject daysObj = new JsonObject();
            for (Map.Entry<String, Map<String, Long>> dayEntry : fetchedDays.entrySet()) {
                long day = dayKeyToDayNumber(dayEntry.getKey());
                if (day < minDay || day > maxDay) {
                    continue;
                }
                JsonObject channelsOfDay = new JsonObject();
                for (Map.Entry<String, Long> channelEntry : dayEntry.getValue().entrySet()) {
                    channelsOfDay.addProperty(channelEntry.getKey(), channelEntry.getValue());
                }
                daysObj.add(dayEntry.getKey(), channelsOfDay);
            }
            root.add("days", daysObj);

            // Write to a temp file first so a crash or a kill never leaves a truncated cache.
            Writer writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"), 32 * 1024);
            try {
                mGson.toJson(root, writer);
                writer.flush();
            } finally {
                closeQuietly(writer);
            }

            if (mCacheFile.exists() && !mCacheFile.delete()) {
                Log.w(TAG, "Cannot replace existing EPG cache");
            }
            if (!tmp.renameTo(mCacheFile)) {
                throw new IOException("Cannot move EPG cache into place");
            }
            writeMeta(timestamp);
            mCachedTimestamp = timestamp;
            Log.d(TAG, "✓ EPG cache saved to " + mCacheFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Error saving EPG cache: " + e.getMessage(), e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    public CacheData loadPrograms() {
        if (!mCacheFile.exists()) {
            Log.d(TAG, "EPG cache file does not exist");
            return null;
        }

        Reader reader = null;
        try {
            reader = openReader(mCacheFile);
            JsonObject root = mGson.fromJson(reader, JsonObject.class);
            if (root == null) {
                Log.d(TAG, "Failed to parse EPG cache");
                return null;
            }

            int version = root.getAsJsonPrimitive("version").getAsInt();
            if (version != CACHE_VERSION) {
                Log.d(TAG, "EPG cache version mismatch, ignoring");
                return null;
            }
            if (root.has("timestamp")) {
                mCachedTimestamp = root.getAsJsonPrimitive("timestamp").getAsLong();
            }

            CacheData data = new CacheData();
            JsonObject channelsObj = root.has("channels") ? root.getAsJsonObject("channels") : null;
            if (channelsObj != null) {
                for (String channelId : channelsObj.keySet()) {
                    JsonArray programsArray = channelsObj.getAsJsonArray(channelId);
                    List<EpgManager.Program> programs = new ArrayList<>();
                    for (int i = 0; i < programsArray.size(); i++) {
                        JsonObject progObj = programsArray.get(i).getAsJsonObject();
                        String title = progObj.getAsJsonPrimitive("title").getAsString();
                        long startTime = progObj.getAsJsonPrimitive("startTime").getAsLong();
                        long endTime = progObj.getAsJsonPrimitive("endTime").getAsLong();
                        programs.add(new EpgManager.Program(title, startTime, endTime));
                    }
                    if (programs.size() > 0) {
                        data.programsByChannel.put(channelId, programs);
                    }
                }
            }
            JsonObject daysObj = root.has("days") ? root.getAsJsonObject("days") : null;
            if (daysObj != null) {
                for (String dayKey : daysObj.keySet()) {
                    JsonObject channelsOfDay = daysObj.getAsJsonObject(dayKey);
                    Map<String, Long> channelTimes = new HashMap<>();
                    for (String channelId : channelsOfDay.keySet()) {
                        channelTimes.put(channelId,
                                channelsOfDay.getAsJsonPrimitive(channelId).getAsLong());
                    }
                    data.fetchedDays.put(dayKey, channelTimes);
                }
            }

            Log.d(TAG, "✓ EPG cache loaded from " + mCacheFile.getAbsolutePath()
                    + " (" + data.programsByChannel.size() + " channels, "
                    + data.fetchedDays.size() + " days)");
            return data;
        } catch (Exception e) {
            Log.e(TAG, "Error loading EPG cache: " + e.getMessage(), e);
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    public void clearCache() {
        mCachedTimestamp = 0;
        if (mCacheFile.exists()) {
            if (mCacheFile.delete()) {
                Log.d(TAG, "✓ EPG cache cleared");
            } else {
                Log.w(TAG, "Failed to delete EPG cache file");
            }
        }
        if (mMetaFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            mMetaFile.delete();
        }
    }

    public long getCacheTimestamp() {
        long known = mCachedTimestamp;
        if (known > 0) {
            return known;
        }
        if (!mCacheFile.exists()) {
            return 0;
        }
        long timestamp = readMetaTimestamp();
        if (timestamp <= 0) {
            // Cache written before the sidecar existed: the file's mtime is close enough.
            timestamp = mCacheFile.lastModified();
        }
        mCachedTimestamp = timestamp;
        return timestamp;
    }

    /**
     * The single freshness rule for EPG data: young enough, and from today. Both conditions
     * matter — a feed downloaded at 23:50 yesterday is only minutes old but its programmes are
     * already the wrong day's.
     */
    public boolean isFresh(int validityHours) {
        long timestamp = getCacheTimestamp();
        if (timestamp == 0) {
            Log.d(TAG, "No EPG cache timestamp available");
            return false;
        }
        long ageHours = (System.currentTimeMillis() - timestamp) / (1000 * 60 * 60);
        boolean fresh = ageHours < validityHours && isSameLocalDay(timestamp, System.currentTimeMillis());
        Log.d(TAG, "EPG cache age " + ageHours + "h (limit " + validityHours + "h), fresh: " + fresh);
        return fresh;
    }

    private void writeMeta(long timestamp) {
        Writer writer = null;
        try {
            JsonObject meta = new JsonObject();
            meta.addProperty("version", CACHE_VERSION);
            meta.addProperty("timestamp", timestamp);
            writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(mMetaFile), "UTF-8"));
            mGson.toJson(meta, writer);
            writer.flush();
        } catch (Exception e) {
            Log.w(TAG, "Cannot write EPG cache metadata: " + e.getMessage());
        } finally {
            closeQuietly(writer);
        }
    }

    private long readMetaTimestamp() {
        if (!mMetaFile.exists()) {
            return 0;
        }
        Reader reader = null;
        try {
            reader = openReader(mMetaFile);
            JsonObject meta = mGson.fromJson(reader, JsonObject.class);
            if (meta != null && meta.has("timestamp")
                    && meta.has("version")
                    && meta.getAsJsonPrimitive("version").getAsInt() == CACHE_VERSION) {
                return meta.getAsJsonPrimitive("timestamp").getAsLong();
            }
        } catch (Exception e) {
            Log.w(TAG, "Cannot read EPG cache metadata: " + e.getMessage());
        } finally {
            closeQuietly(reader);
        }
        return 0;
    }

    private static Reader openReader(File file) throws IOException {
        return new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"), 32 * 1024);
    }

    /** Day boundaries follow the device's timezone, not UTC. */
    private static boolean isSameLocalDay(long first, long second) {
        return localDayNumber(first) == localDayNumber(second);
    }

    /** Monotonic local calendar day (year*372 + day-of-year), stable across midnight. */
    public static long localDayNumber(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.YEAR) * 372L + calendar.get(Calendar.DAY_OF_YEAR);
    }

    /** Key used in the fetched-day manifest and the UI's day selection. */
    public static String dayKey(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        StringBuilder key = new StringBuilder(8);
        key.append(calendar.get(Calendar.YEAR));
        appendTwoDigits(key, calendar.get(Calendar.MONTH) + 1);
        appendTwoDigits(key, calendar.get(Calendar.DAY_OF_MONTH));
        return key.toString();
    }

    private static void appendTwoDigits(StringBuilder out, int value) {
        if (value < 10) {
            out.append('0');
        }
        out.append(value);
    }

    private static long dayKeyToDayNumber(String dayKey) {
        try {
            if (dayKey == null || dayKey.length() < 8) {
                return Long.MAX_VALUE; // unparsable: fall outside the retained window
            }
            int year = Integer.parseInt(dayKey.substring(0, 4));
            int month = Integer.parseInt(dayKey.substring(4, 6));
            int day = Integer.parseInt(dayKey.substring(6, 8));
            Calendar calendar = Calendar.getInstance();
            calendar.set(Calendar.YEAR, year);
            calendar.set(Calendar.MONTH, month - 1);
            calendar.set(Calendar.DAY_OF_MONTH, day);
            return localDayNumber(calendar.getTimeInMillis());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
