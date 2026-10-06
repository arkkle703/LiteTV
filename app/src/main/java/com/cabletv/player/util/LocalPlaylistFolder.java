package com.cabletv.player.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Finds M3U playlists the user dropped on the device, for TVs and boxes whose system has no
 * document picker ({@code ACTION_OPEN_DOCUMENT} fails there — issue #7).
 *
 * <p>Two kinds of folder are offered, because scoped storage decides which is reachable:
 *
 * <ul>
 *   <li><b>Shared</b> {@code /sdcard/LiteTV} (plus each volume's {@code Download} and
 *       {@code LiteTV} on SD cards / USB drives). Easy to reach from a file manager, a PC over
 *       USB, or a USB stick — but only readable while the user has granted all-files access
 *       (Android 11+) or the legacy storage permission (Android 10 and older).</li>
 *   <li><b>App-specific</b> {@code .../Android/data/<pkg>/files/playlists}, which never needs a
 *       permission. Reachable over USB on most devices and always available as a fallback.</li>
 * </ul>
 *
 * <p>The scan itself is a pure directory walk so it can be unit tested without a device.
 */
public final class LocalPlaylistFolder {

    private static final String TAG = "LocalPlaylistFolder";
    /** Shared folder name users are told to copy files into. */
    public static final String FOLDER_NAME = "LiteTV";
    /** App-specific folder, always readable without any permission. */
    public static final String PLAYLISTS_SUBDIR = "playlists";
    /** File name of the single playlist slot inside {@link #PLAYLISTS_SUBDIR}. */
    public static final String SLOT_FILE_NAME = "local.m3u";
    /** Playlists bigger than this are ignored; ChannelRepository refuses them too. */
    public static final long MAX_FILE_BYTES = 8 * 1024 * 1024;

    private LocalPlaylistFolder() {
    }

    /**
     * The single app-private slot every import path writes into (file picker, folder scan, web
     * upload). ChannelRepository plays whatever this file holds, so keeping one definition stops
     * the paths from drifting apart.
     */
    public static File playlistSlot(Context context) {
        return new File(new File(context.getFilesDir(), PLAYLISTS_SUBDIR), SLOT_FILE_NAME);
    }

    /**
     * Writes playlist bytes into {@link #playlistSlot}.
     *
     * @return the stored path, or null when the content is empty, too large or could not be
     *     written.
     */
    public static String writePlaylist(Context context, byte[] content) {
        if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
            return null;
        }
        File target = playlistSlot(context);
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return null;
        }
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                out.write(content);
                out.flush();
            } finally {
                out.close();
            }
            return target.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Cannot store playlist: " + e.getMessage());
            return null;
        }
    }

    /**
     * True for the filenames and contents a playlist normally arrives as. Used to reject an
     * obviously wrong upload instead of "saving" it and leaving the channel list empty.
     */
    public static boolean looksLikePlaylist(String fileName, byte[] content) {
        if (content == null || content.length == 0) {
            return false;
        }
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (name.endsWith(".m3u") || name.endsWith(".m3u8") || name.endsWith(".txt")) {
            return true;
        }
        // No useful extension: accept anything that clearly is a playlist header.
        String head = new String(content, 0, Math.min(content.length, 256), java.nio.charset.StandardCharsets.UTF_8);
        String trimmed = head.trim();
        return trimmed.startsWith("#EXTM3U") || trimmed.contains("#genre#");
    }

    /**
     * The folder to advertise to the user: the shared {@code /sdcard/LiteTV} when it can actually
     * be written, otherwise the app-specific folder. Created on demand.
     */
    public static File dropDir(Context context) {
        File shared = new File(Environment.getExternalStorageDirectory(), FOLDER_NAME);
        if (canUseSharedStorage(context) && ensureDir(shared)) {
            return shared;
        }
        File appExternal = context.getExternalFilesDir(null);
        File fallback = new File(appExternal != null ? appExternal : context.getFilesDir(),
                PLAYLISTS_SUBDIR);
        ensureDir(fallback);
        return fallback;
    }

    /**
     * Directories searched for playlists, most convenient first: the shared folder, the shared
     * Download folder, then the app-specific folders (primary storage and every mounted volume).
     */
    public static List<File> candidateDirs(Context context) {
        LinkedHashSet<File> dirs = new LinkedHashSet<>();
        File sharedRoot = Environment.getExternalStorageDirectory();
        if (sharedRoot != null) {
            dirs.add(new File(sharedRoot, FOLDER_NAME));
            dirs.add(new File(sharedRoot, Environment.DIRECTORY_DOWNLOADS));
        }
        // Volumes (SD cards, USB drives) are discovered through the app's own dirs on each.
        File[] external = context.getExternalFilesDirs(null);
        if (external != null) {
            for (File appDir : external) {
                File volumeRoot = volumeRootOf(appDir);
                if (volumeRoot != null) {
                    dirs.add(new File(volumeRoot, FOLDER_NAME));
                    dirs.add(new File(volumeRoot, Environment.DIRECTORY_DOWNLOADS));
                }
            }
        }
        File appExternal = context.getExternalFilesDir(null);
        if (appExternal != null) {
            dirs.add(new File(appExternal, PLAYLISTS_SUBDIR));
            dirs.add(appExternal);
        }
        return new ArrayList<>(dirs);
    }

    /** Playlist files inside the given folders, newest first. Unreadable folders are skipped. */
    public static List<File> scan(List<File> dirs, long maxBytes) {
        List<File> found = new ArrayList<>();
        if (dirs == null) {
            return found;
        }
        for (File dir : dirs) {
            File[] children;
            try {
                children = dir == null ? null : dir.listFiles();
            } catch (SecurityException e) {
                continue; // no access to this volume: not an error, just skip it
            }
            if (children == null) {
                continue;
            }
            for (File file : children) {
                if (!isPlaylistFile(file)) {
                    continue;
                }
                long size = file.length();
                if (size <= 0 || size > maxBytes || found.contains(file)) {
                    continue;
                }
                found.add(file);
            }
        }
        Collections.sort(found, (first, second) ->
                Long.compare(second.lastModified(), first.lastModified()));
        return found;
    }

    /** True for the extensions a playlist is normally saved under. */
    public static boolean isPlaylistFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".m3u") || name.endsWith(".m3u8") || name.endsWith(".txt");
    }

    /** True when shared storage can be read, i.e. all-files access (11+) or legacy permission. */
    public static boolean canUseSharedStorage(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Exception e) {
                // Not available on every ROM (and on some test runtimes); treating it as denied
                // simply falls back to the app-specific folder, which always works.
                Log.d(TAG, "Cannot query all-files access: " + e);
                return false;
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true; // granted at install time
        }
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean ensureDir(File dir) {
        if (dir == null) {
            return false;
        }
        if (dir.isDirectory()) {
            return true;
        }
        try {
            return dir.mkdirs() || dir.isDirectory();
        } catch (SecurityException e) {
            Log.d(TAG, "Cannot create " + dir + ": " + e.getMessage());
            return false;
        }
    }

    /** {@code /storage/VOL/Android/data/<pkg>/files} → {@code /storage/VOL}. */
    private static File volumeRootOf(File appExternalDir) {
        File current = appExternalDir;
        for (int i = 0; i < 4 && current != null; i++) {
            current = current.getParentFile();
        }
        return current;
    }
}
