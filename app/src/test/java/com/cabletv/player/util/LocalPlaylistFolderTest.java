package com.cabletv.player.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The folder import path (issue #7) is what TVs without a document picker rely on, so the scan
 * has to pick the right files: playlist extensions only, non-empty, under the size cap, and
 * newest first so the file the user just copied is easy to find.
 */
public class LocalPlaylistFolderTest {

    @Rule
    public TemporaryFolder mTemp = new TemporaryFolder();

    private File write(String name, int bytes) throws Exception {
        File file = mTemp.newFile(name);
        byte[] content = new byte[bytes];
        Arrays.fill(content, (byte) 'a');
        org.junit.Assert.assertTrue(file.setLastModified(1_000_000_000L));
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        out.write(content);
        out.close();
        return file;
    }

    @Test
    public void acceptsPlaylistExtensionsOnly() throws Exception {
        assertTrue(LocalPlaylistFolder.isPlaylistFile(write("a.m3u", 10)));
        assertTrue(LocalPlaylistFolder.isPlaylistFile(write("b.M3U8", 10)));
        assertTrue(LocalPlaylistFolder.isPlaylistFile(write("c.txt", 10)));
        assertFalse(LocalPlaylistFolder.isPlaylistFile(write("d.mp4", 10)));
        assertFalse(LocalPlaylistFolder.isPlaylistFile(write("e", 10)));
        assertFalse(LocalPlaylistFolder.isPlaylistFile(mTemp.getRoot()));
        assertFalse(LocalPlaylistFolder.isPlaylistFile(null));
    }

    @Test
    public void scanKeepsPlaylistsAndDropsEverythingElse() throws Exception {
        write("keep.m3u", 100);
        write("keep2.txt", 100);
        write("ignore.mkv", 100);

        List<File> found = LocalPlaylistFolder.scan(
                Collections.singletonList(mTemp.getRoot()), LocalPlaylistFolder.MAX_FILE_BYTES);

        List<String> names = new ArrayList<>();
        for (File file : found) {
            names.add(file.getName());
        }
        assertEquals(2, found.size());
        assertTrue(names.contains("keep.m3u"));
        assertTrue(names.contains("keep2.txt"));
        assertFalse(names.contains("ignore.mkv"));
    }

    @Test
    public void scanSkipsEmptyAndOversizedFiles() throws Exception {
        write("ok.m3u", 50);
        write("empty.m3u", 0);
        write("huge.m3u", 50);

        // A cap of 10 bytes leaves only nothing eligible, then a cap of 100 keeps the 50-byte one.
        List<File> withTinyCap = LocalPlaylistFolder.scan(
                Collections.singletonList(mTemp.getRoot()), 10);
        assertTrue("50-byte file must not pass a 10-byte cap", withTinyCap.isEmpty());

        List<File> withRoom = LocalPlaylistFolder.scan(
                Collections.singletonList(mTemp.getRoot()), 100);
        assertEquals(2, withRoom.size()); // ok.m3u and huge.m3u (the empty one never counts)
    }

    @Test
    public void scanOrdersNewestFirstAndSkipsMissingDirs() throws Exception {
        File older = write("older.m3u", 10);
        File newer = write("newer.m3u", 10);
        older.setLastModified(1_000_000_000L);
        newer.setLastModified(2_000_000_000L);

        List<File> dirs = new ArrayList<>();
        dirs.add(new File(mTemp.getRoot(), "does-not-exist"));
        dirs.add(mTemp.getRoot());

        List<File> found = LocalPlaylistFolder.scan(dirs, LocalPlaylistFolder.MAX_FILE_BYTES);

        assertEquals(2, found.size());
        assertEquals("newer.m3u", found.get(0).getName());
        assertEquals("older.m3u", found.get(1).getName());
    }

    @Test
    public void scanToleratesNull() {
        assertTrue(LocalPlaylistFolder.scan(null, 100).isEmpty());
    }
}
