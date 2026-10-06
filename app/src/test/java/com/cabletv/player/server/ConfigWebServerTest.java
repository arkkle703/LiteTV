package com.cabletv.player.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import com.cabletv.player.config.AppConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP-level tests of the config web server's local-file support: /config/get reports the
 * active file, POST /config/m3u-file-clear removes it and notifies the app, and the
 * cross-site / content-type guards still hold. Uses raw sockets because the test runs under
 * Robolectric, which shadows HttpURLConnection rather than doing real networking.
 */
@RunWith(RobolectricTestRunner.class)
public class ConfigWebServerTest {

    private final AtomicReference<String> mLastFileChange = new AtomicReference<>();
    private int mPort;

    @Before
    public void setUp() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        AppConfig.setM3uUrl("");
        AppConfig.setM3uFilePath("");
        mPort = freePort();
        ConfigWebServer.setConfigChangeListener(new ConfigWebServer.OnConfigChangeListener() {
            @Override public void onM3uUrlChanged(String u) { }
            @Override public void onEpgUrlChanged(String u) { }
            @Override public void onEpgPreferenceChanged(String p) { }
            @Override public void onM3uFileChanged(String path) { mLastFileChange.set(path); }
        });
        ConfigWebServer.startServer(context, mPort);
        mLastFileChange.set(null);
    }

    @After
    public void tearDown() {
        ConfigWebServer.stopServer();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /** Sends one HTTP request over a raw socket. Returns {status, body}. */
    private String[] request(String method, String path, String extraHeaders, String body)
            throws IOException {
        Socket socket = new Socket("127.0.0.1", mPort);
        socket.setSoTimeout(10_000);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: localhost:").append(mPort).append("\r\n");
        head.append("Connection: close\r\n");
        if (extraHeaders != null) {
            head.append(extraHeaders);
        }
        if (body != null) {
            head.append("Content-Length: ").append(body.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        out.flush();

        InputStream in = socket.getInputStream();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            raw.write(buf, 0, n);
        }
        socket.close();

        String all = raw.toString("UTF-8");
        int sep = all.indexOf("\r\n\r\n");
        String headPart = sep >= 0 ? all.substring(0, sep) : all;
        String bodyPart = sep >= 0 ? all.substring(sep + 4) : "";
        Matcher m = Pattern.compile("HTTP/1.1 (\\d+)").matcher(headPart);
        if (!m.find()) {
            fail("No status line in response: " + all);
        }
        return new String[]{m.group(1), bodyPart};
    }

    // ---- /config/get ----

    @Test
    public void playbackPost_savesValidSettingsAndReportsThemBack() throws Exception {
        String[] res = request("POST", "/config/playback",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "video_render_mode=texture&video_decode_mode=software&video_tunneling=1");
        assertEquals("200", res[0]);
        assertTrue("must report success, got " + res[1], res[1].contains("\"success\":true"));
        assertEquals(AppConfig.RENDER_TEXTURE, AppConfig.getVideoRenderMode());
        assertEquals(xyz.doikki.videoplayer.player.VideoViewConfig.DECODE_SOFTWARE,
                AppConfig.getVideoDecodeMode());
        assertTrue(AppConfig.isTunnelingEnabled());

        String[] config = request("GET", "/config/get", null, null);
        assertTrue("the page must be able to read them back: " + config[1],
                config[1].contains("\"video_render_mode\":\"texture\""));
        assertTrue(config[1].contains("\"video_decode_mode\":\"software\""));
        assertTrue(config[1].contains("\"video_tunneling\":true"));
    }

    @Test
    public void playbackPost_rejectsAnInvalidValueWithoutWritingAnything() throws Exception {
        AppConfig.setVideoRenderMode(AppConfig.RENDER_SURFACE);
        String[] res = request("POST", "/config/playback",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "video_render_mode=texture&video_decode_mode=quantum");
        assertEquals("200", res[0]);
        assertTrue("invalid input must be rejected: " + res[1], res[1].contains("\"success\":false"));
        assertEquals("a partial write would be worse than a rejection", AppConfig.RENDER_SURFACE,
                AppConfig.getVideoRenderMode());
    }

    @Test
    public void displayPost_savesEpgToggleAndOpacity() throws Exception {
        String[] res = request("POST", "/config/display",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "epg_display_enabled=0&sidebar_opacity=2");
        assertEquals("200", res[0]);
        assertTrue(res[1].contains("\"success\":true"));
        assertTrue(!AppConfig.isEpgDisplayEnabled());
        assertEquals(AppConfig.SIDEBAR_OPAQUE, AppConfig.getSidebarOpacity());
    }

    @Test
    public void displayPost_rejectsOutOfRangeOpacity() throws Exception {
        AppConfig.setSidebarOpacity(AppConfig.SIDEBAR_TRANSLUCENT);
        String[] res = request("POST", "/config/display",
                "Content-Type: application/x-www-form-urlencoded\r\n",
                "sidebar_opacity=9");
        assertTrue(res[1].contains("\"success\":false"));
        assertEquals(AppConfig.SIDEBAR_TRANSLUCENT, AppConfig.getSidebarOpacity());
    }

    @Test
    public void getConfig_reportsLocalFilePath() throws Exception {
        AppConfig.setM3uFilePath("/data/files/playlists/local.m3u");
        String[] res = request("GET", "/config/get", null, null);

        assertEquals("200", res[0]);
        assertTrue("JSON must include m3u_file_path",
                res[1].contains("\"m3u_file_path\":\"/data/files/playlists/local.m3u\""));
        assertTrue("JSON must include m3u_url", res[1].contains("\"m3u_url\""));
    }

    // ---- /config/m3u-file-clear ----

    @Test
    public void clearRemovesFileSettingAndNotifies() throws Exception {
        // Prepare a fake copied file exactly where the settings screen would put it.
        Context context = RuntimeEnvironment.getApplication();
        File dir = new File(context.getFilesDir(), "playlists");
        assertTrue(dir.mkdirs() || dir.exists());
        File local = new File(dir, "local.m3u");
        try (FileOutputStream f = new FileOutputStream(local)) {
            f.write("#EXTM3U\n".getBytes(StandardCharsets.UTF_8));
        }
        AppConfig.setM3uFilePath(local.getAbsolutePath());

        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: application/x-www-form-urlencoded\r\n", "action=clear");

        assertEquals("200", res[0]);
        assertTrue(res[1].contains("\"success\":true"));
        assertEquals("", AppConfig.getM3uFilePath());
        assertEquals("", mLastFileChange.get());
        assertTrue("the copied file must be deleted", !local.exists());
    }

    // ---- guards still work ----

    @Test
    public void crossSitePost_rejected403() throws Exception {
        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "Origin: http://evil.example\r\n",
                "action=clear");
        assertEquals("403", res[0]);
    }

    @Test
    public void sameOriginPost_allowed() throws Exception {
        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "Origin: http://localhost:" + mPort + "\r\n",
                "action=clear");
        assertEquals("200", res[0]);
    }

    @Test
    public void wrongContentType_rejected415() throws Exception {
        String[] res = request("POST", "/config/m3u-file-clear",
                "Content-Type: text/plain\r\n", "action=clear");
        assertEquals("415", res[0]);
    }

    @Test
    public void unknownPath_404() throws Exception {
        String[] res = request("GET", "/config/nope", null, null);
        assertEquals("404", res[0]);
    }
}