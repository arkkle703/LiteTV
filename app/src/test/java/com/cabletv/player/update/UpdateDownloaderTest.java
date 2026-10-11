package com.cabletv.player.update;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exercises the download path against a real HTTP server, because that is where the interesting
 * behaviour lives: resuming, restarting when a server ignores Range, checksum rejection and
 * cancellation. The downloader talks plain HTTP here (its production scheme requirement is a
 * constructor argument), so the tests can serve from a loopback socket. The server is written on
 * raw sockets because the JDK's own HTTP server is not on the Android unit-test classpath.
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateDownloaderTest {

    private static final String PATH = "/LiteTV-v1.5.0-x86.apk";
    private static final int BODY_SIZE = 4096;

    private TestServer mServer;
    private Context mContext;

    @Before
    public void setUp() throws IOException {
        mContext = RuntimeEnvironment.getApplication();
        UpdateDownloader.clear(mContext);
        mServer = new TestServer();
    }

    @After
    public void tearDown() {
        UpdateDownloader.clear(mContext);
        mServer.stop();
    }

    @Test
    public void download_writesTheFileWhenTheChecksumMatches() throws Exception {
        byte[] body = payload("the-apk");
        mServer.setBody(body);
        File apk = download(body, null);
        assertArrayEquals(body, readAll(apk));
        assertFalse("the finished file must replace the partial one",
                partFile().exists());
    }

    @Test
    public void download_reusesAnAlreadyVerifiedFile() throws Exception {
        byte[] body = payload("cached");
        mServer.setBody(body);
        download(body, null);
        int requests = mServer.requestCount();
        download(body, null);
        assertEquals("a second press must not download the same build again",
                requests, mServer.requestCount());
    }

    @Test
    public void download_rejectsAChecksumMismatchAndDeletesTheFile() throws Exception {
        byte[] body = payload("tampered");
        mServer.setBody(body);
        try {
            download(body, sha256("something-else"));
            fail("a file that does not match the manifest must be refused");
        } catch (UpdateDownloader.DownloadException e) {
            assertEquals(UpdateError.CHECKSUM, e.error);
        }
        assertFalse("a rejected download must not be left on disk",
                UpdateDownloader.targetFile(mContext).exists());
    }

    @Test
    public void download_rejectsASizeMismatch() throws Exception {
        byte[] body = payload("short");
        mServer.setBody(body);
        try {
            new UpdateDownloader("http").download(mContext, mServer.url(PATH), body.length + 10L,
                    sha256(body), null, new AtomicBoolean(false));
            fail("a truncated download must be refused");
        } catch (UpdateDownloader.DownloadException e) {
            assertEquals(UpdateError.CHECKSUM, e.error);
        }
    }

    @Test
    public void download_resumesAPartialFile() throws Exception {
        byte[] body = payload("resume-me");
        mServer.setBody(body);
        int prefix = 100;
        writePart(prefix, body);

        File apk = download(body, null);

        assertArrayEquals("the resumed download must produce the whole file", body, readAll(apk));
        assertEquals("the resume must ask for the remaining bytes only",
                prefix, mServer.rangeStart);
    }

    @Test
    public void download_restartsWhenTheServerIgnoresTheRange() throws Exception {
        byte[] body = payload("ignored-range");
        mServer.setBody(body);
        mServer.ignoreRange = true;
        writePart(100, body);

        File apk = download(body, null);

        assertArrayEquals("appending to a partial file would corrupt it", body, readAll(apk));
        assertEquals("the server is asked twice: once with a range, once from scratch",
                2, mServer.requestCount());
    }

    @Test
    public void download_reportsHttpErrors() throws Exception {
        byte[] body = payload("x");
        mServer.setBody(body);
        mServer.status = 500;
        try {
            download(body, null);
            fail("a server error must be reported");
        } catch (UpdateDownloader.DownloadException e) {
            assertEquals(UpdateError.HTTP, e.error);
        }
    }

    @Test
    public void download_followsARedirect() throws Exception {
        byte[] body = payload("behind-a-redirect");
        mServer.setBody(body);
        mServer.redirectTo = "/moved" + PATH;
        File apk = download(body, null);
        assertArrayEquals(body, readAll(apk));
    }

    @Test
    public void download_canBeCancelledAndKeepsThePartialFile() throws Exception {
        byte[] body = payload("cancel-me");
        mServer.setBody(body);
        AtomicBoolean cancel = new AtomicBoolean(false);
        try {
            new UpdateDownloader("http").download(mContext, mServer.url(PATH), body.length,
                    sha256(body), (downloaded, total) -> cancel.set(true), cancel);
            fail("cancelling must stop the download");
        } catch (UpdateDownloader.DownloadException e) {
            assertEquals(UpdateError.CANCELLED, e.error);
        }
        assertFalse("a cancelled download must not be offered as complete",
                UpdateDownloader.targetFile(mContext).exists());
        assertTrue("the partial file is what makes a retry resume instead of restart",
                partFile().exists());
    }

    @Test
    public void download_refusesAPlainHttpUrlInProduction() throws Exception {
        mServer.setBody(payload("x"));
        try {
            new UpdateDownloader().download(mContext, mServer.url(PATH), 1L, sha256(new byte[0]),
                    null, new AtomicBoolean(false));
            fail("the production downloader must refuse plain http");
        } catch (UpdateDownloader.DownloadException e) {
            assertEquals(UpdateError.HTTP, e.error);
        }
        assertEquals("nothing should have been requested", 0, mServer.requestCount());
    }

    @Test
    public void targetFile_prefersTheInternalCacheWhichNeedsNoPermission() throws Exception {
        File target = UpdateDownloader.targetFile(mContext);
        assertEquals("the internal cache is writable everywhere; shared storage is not",
                mContext.getCacheDir().getAbsolutePath(), target.getParentFile().getAbsolutePath());
    }

    @Test
    public void ensureWritable_rejectsADirectoryThatCannotBeCreated() {
        // A path that cannot exist stands in for a shared-storage view this app may not write to.
        File impossible = new File("/proc/litetv-not-a-directory/cache");
        assertFalse(UpdateDownloader.ensureWritable(impossible));
        assertFalse(UpdateDownloader.ensureWritable(null));
    }

    @Test
    public void ensureWritable_acceptsAUsableDirectoryAndCleansUpAfterItself() {
        File dir = new File(mContext.getCacheDir(), "probe-target");
        assertTrue(UpdateDownloader.ensureWritable(dir));
        assertTrue(dir.isDirectory());
        assertEquals("the probe file must not be left behind", 0, dir.listFiles().length);
    }

    private File download(byte[] body, String expectedSha) throws Exception {
        return new UpdateDownloader("http").download(mContext, mServer.url(PATH), body.length,
                expectedSha != null ? expectedSha : sha256(body), null, new AtomicBoolean(false));
    }

    private File partFile() {
        return new File(UpdateDownloader.targetFile(mContext).getParentFile(), "update.apk.part");
    }

    /** Writes a partial file as if a previous attempt had been interrupted. */
    private void writePart(int length, byte[] body) throws IOException {
        try (FileOutputStream out = new FileOutputStream(partFile())) {
            out.write(body, 0, length);
        }
    }

    private static byte[] payload(String seed) {
        return payload(seed, BODY_SIZE);
    }

    private static byte[] payload(String seed, int size) {
        byte[] bytes = new byte[size];
        byte[] seedBytes = seed.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < size; i++) {
            bytes[i] = seedBytes[i % seedBytes.length];
        }
        return bytes;
    }

    private static byte[] readAll(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return UpdateDownloader.toHex(digest.digest(data));
    }

    private static String sha256(String text) throws Exception {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** A loopback HTTP server with the few knobs these tests need. */
    private static class TestServer {
        private final ServerSocket mSocket;
        private final AtomicLong mRequests = new AtomicLong();
        private volatile byte[] mBody = new byte[0];
        private volatile int status = 200;
        private volatile boolean ignoreRange;
        private volatile String redirectTo;
        private volatile boolean stopped;
        /** Offset of the last Range request, or -1 when the last request had none. */
        volatile int rangeStart = -1;

        TestServer() throws IOException {
            mSocket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(this::serve, "test-http");
            thread.setDaemon(true);
            thread.start();
        }

        void setBody(byte[] body) {
            mBody = body;
        }

        int requestCount() {
            return (int) mRequests.get();
        }

        String url(String path) {
            return "http://127.0.0.1:" + mSocket.getLocalPort() + path;
        }

        void stop() {
            stopped = true;
            try {
                mSocket.close();
            } catch (IOException ignored) {
                // Closing the listener is enough to end the accept loop.
            }
        }

        private void serve() {
            while (!stopped) {
                try (Socket socket = mSocket.accept()) {
                    handle(socket);
                } catch (IOException e) {
                    // Either the listener was closed by stop() or a client went away; both are fine.
                    if (stopped) {
                        return;
                    }
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            mRequests.incrementAndGet();
            String path = pathOf(requestLine);
            String range = null;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("range:")) {
                    range = line.substring("range:".length()).trim();
                }
            }

            OutputStream out = socket.getOutputStream();
            if (redirectTo != null && PATH.equals(path)) {
                // Redirect only the original path; otherwise the target would redirect too.
                writeHead(out, 302, 0, "Location: " + redirectTo);
                return;
            }
            if (status != 200) {
                writeHead(out, status, 0, null);
                return;
            }
            byte[] body = mBody;
            boolean partial = range != null && !ignoreRange;
            int start = 0;
            if (range != null) {
                start = parseRangeStart(range);
                rangeStart = start;
                if (ignoreRange) {
                    start = 0;
                }
            }
            String extra = partial
                    ? "Content-Range: bytes " + start + "-" + (body.length - 1) + "/" + body.length
                    : null;
            writeHead(out, partial ? 206 : 200, body.length - start, extra);
            out.write(body, start, body.length - start);
            out.flush();
        }

        private void writeHead(OutputStream out, int code, int length, String extra) throws IOException {
            StringBuilder head = new StringBuilder()
                    .append("HTTP/1.1 ").append(code).append("\r\n")
                    .append("Content-Length: ").append(Math.max(0, length)).append("\r\n")
                    .append("Connection: close\r\n");
            if (extra != null) {
                head.append(extra).append("\r\n");
            }
            head.append("\r\n");
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        private static int parseRangeStart(String range) {
            try {
                return Integer.parseInt(range.replace("bytes=", "").replace("-", "").trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String pathOf(String requestLine) {
            String[] parts = requestLine.split(" ");
            return parts.length > 1 ? parts[1] : "";
        }
    }
}
