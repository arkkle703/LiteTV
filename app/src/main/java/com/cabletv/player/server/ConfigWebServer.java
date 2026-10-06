package com.cabletv.player.server;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.cabletv.player.config.AppConfig;
import com.google.gson.JsonObject;

import xyz.doikki.videoplayer.player.VideoViewConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ConfigWebServer {
    private static final String TAG = "ConfigWebServer";

    /** Request line cap; a legitimate config request is a few dozen bytes. */
    private static final int MAX_REQUEST_LINE_BYTES = 2048;
    /** Header count cap, guards against a client that never sends a blank line. */
    private static final int MAX_HEADER_LINES = 64;
    /** Body cap; the only bodies we accept are two short form fields. */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    /**
     * A client that connects but never sends a request head must release its worker
     * quickly, otherwise a handful of idle sockets makes the server unavailable.
     */
    private static final int HEAD_TIMEOUT_MS = 3_000;
    /** Once a valid head has arrived, allow a slower body transfer. */
    private static final int BODY_TIMEOUT_MS = 10_000;
    /** Bounded worker pool, so many connections cannot exhaust threads. */
    private static final int MAX_WORKERS = 8;

    private static ServerSocket sServerSocket;
    private static Thread sServerThread;
    private static ExecutorService sWorkers;
    private static volatile boolean sRunning = false;

    public interface OnConfigChangeListener {
        void onM3uUrlChanged(String newUrl);
        void onEpgUrlChanged(String newUrl);
        void onEpgPreferenceChanged(String preference);
        /** The local playlist was cleared (newPath is empty) from the web page. */
        void onM3uFileChanged(String newPath);
    }

    /** Cleared by the Activity in onDestroy(); volatile because server threads read it. */
    private static volatile OnConfigChangeListener sConfigChangeListener;
    /** Kept only to answer /config/get (e.g. the playlist drop folder); never used to leak. */
    private static volatile Context sAppContext;

    public static synchronized void startServer(Context context, int port) {
        if (context != null) {
            sAppContext = context.getApplicationContext();
        }
        if (sRunning) {
            Log.i(TAG, "Server already running on port " + sServerSocket.getLocalPort());
            return;
        }

        // Bind on the calling thread so a port conflict surfaces here instead of
        // racing with the accept loop.
        try {
            sServerSocket = new ServerSocket(port);
        } catch (IOException e) {
            Log.e(TAG, "Cannot bind web server to port " + port, e);
            sServerSocket = null;
            return;
        }

        sRunning = true;
        // No queue: once all workers are busy, further connections are rejected and
        // closed immediately instead of waiting behind a stalled client.
        sWorkers = new ThreadPoolExecutor(1, MAX_WORKERS, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>());
        sServerThread = new Thread(ConfigWebServer::runServer, "ConfigWebServer");
        sServerThread.setDaemon(true);
        sServerThread.start();
        Log.i(TAG, "Web server started on port " + port);
    }

    public static void startServer(Context context) {
        startServer(context, AppConfig.getWebServerPort());
    }

    public static void setConfigChangeListener(OnConfigChangeListener listener) {
        sConfigChangeListener = listener;
    }

    public static synchronized void stopServer() {
        sRunning = false;
        if (sServerSocket != null) {
            try {
                sServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            sServerSocket = null;
        }
        if (sWorkers != null) {
            sWorkers.shutdownNow();
            sWorkers = null;
        }
        Log.i(TAG, "Web server stopped");
    }

    private static void runServer() {
        try {
            while (sRunning) {
                Socket clientSocket = sServerSocket.accept();
                ExecutorService workers = sWorkers;
                if (workers == null) {
                    closeQuietly(clientSocket);
                    break;
                }
                try {
                    workers.execute(() -> handleClient(clientSocket));
                } catch (Exception e) {
                    // Pool saturated or shutting down: drop the connection rather than queue forever.
                    closeQuietly(clientSocket);
                }
            }
        } catch (IOException e) {
            if (sRunning) {
                Log.e(TAG, "Server error", e);
            }
        } finally {
            sRunning = false;
        }
    }

    private static void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(HEAD_TIMEOUT_MS);
            InputStream in = socket.getInputStream();

            String requestLine = readLine(in, MAX_REQUEST_LINE_BYTES);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(socket, "400 Bad Request", 400, "text/plain");
                return;
            }
            String method = parts[0];
            String path = parts[1];

            // Read headers
            int contentLength = 0;
            String origin = null;
            String host = null;
            String requestContentType = "";
            for (int i = 0; i < MAX_HEADER_LINES; i++) {
                String line = readLine(in, MAX_REQUEST_LINE_BYTES);
                if (line == null || line.isEmpty()) break;
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = parseContentLength(line.substring("content-length:".length()));
                    if (contentLength < 0) {
                        sendResponse(socket, "400 Bad Request", 400, "text/plain");
                        return;
                    }
                    if (contentLength > MAX_BODY_BYTES) {
                        sendResponse(socket, "413 Payload Too Large", 413, "text/plain");
                        return;
                    }
                } else if (lower.startsWith("origin:")) {
                    origin = line.substring("origin:".length()).trim();
                } else if (lower.startsWith("host:")) {
                    host = line.substring("host:".length()).trim();
                } else if (lower.startsWith("content-type:")) {
                    requestContentType = lower.substring("content-type:".length()).trim();
                }
            }

            // Read body (exact byte count, then decode as UTF-8 — char counts and
            // Content-Length disagree for non-ASCII URLs).
            String body = "";
            if (contentLength > 0) {
                socket.setSoTimeout(BODY_TIMEOUT_MS);
                byte[] raw = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(raw, off, contentLength - off);
                    if (n == -1) break;
                    off += n;
                }
                if (off < contentLength) {
                    sendResponse(socket, "400 Bad Request", 400, "text/plain");
                    return;
                }
                body = new String(raw, "UTF-8");
            }

            if ("POST".equals(method)) {
                // The server has no authentication, so it must at least refuse
                // browser-driven cross-site writes: any page the user visits could
                // otherwise silently repoint the playlist. Browsers send Origin on
                // cross-site POSTs; local tools (curl and the page we serve) do not
                // trip this check.
                if (isCrossSite(origin, host)) {
                    Log.w(TAG, "Rejected cross-site POST from origin: " + origin);
                    sendResponse(socket, "403 Forbidden", 403, "text/plain");
                    return;
                }
                if (!requestContentType.startsWith("application/x-www-form-urlencoded")) {
                    sendResponse(socket, "415 Unsupported Media Type", 415, "text/plain");
                    return;
                }
            }

            String response;
            String contentType = "text/html; charset=utf-8";

            if ("GET".equals(method) && "/".equals(path)) {
                response = getIndexHtml();
            } else if ("GET".equals(method) && "/config/get".equals(path)) {
                response = getConfigJson();
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/m3u-url".equals(path)) {
                response = handleM3uUrlPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/epg-url".equals(path)) {
                response = handleEpgUrlPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/epg-preference".equals(path)) {
                response = handleEpgPreferencePost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/m3u-file-clear".equals(path)) {
                response = handleM3uFileClear();
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/playback".equals(path)) {
                response = handlePlaybackPost(body);
                contentType = "application/json";
            } else if ("POST".equals(method) && "/config/display".equals(path)) {
                response = handleDisplayPost(body);
                contentType = "application/json";
            } else {
                sendResponse(socket, "404 Not Found", 404, "text/plain");
                return;
            }

            sendResponse(socket, response, 200, contentType);
        } catch (java.net.SocketTimeoutException e) {
            // Idle or stalled client: expected, not worth a stack trace.
            Log.d(TAG, "Client timed out: " + socket.getInetAddress());
        } catch (Exception e) {
            Log.e(TAG, "Error handling client", e);
        } finally {
            closeQuietly(socket);
        }
    }

    /**
     * Reads one CRLF-terminated line, capped at maxBytes. Returns null at end of stream.
     */
    private static String readLine(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                return new String(buffer.toByteArray(), "UTF-8");
            }
            if (b == '\r') {
                continue;
            }
            if (buffer.size() >= maxBytes) {
                throw new IOException("Header line exceeds " + maxBytes + " bytes");
            }
            buffer.write(b);
        }
        return buffer.size() == 0 ? null : new String(buffer.toByteArray(), "UTF-8");
    }

    private static int parseContentLength(String value) {
        try {
            long length = Long.parseLong(value.trim());
            if (length < 0) return -1;
            // Clamp instead of overflowing; caller rejects anything over the cap.
            return length > MAX_BODY_BYTES ? MAX_BODY_BYTES + 1 : (int) length;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * True when the request carries an Origin that is not this server itself.
     */
    private static boolean isCrossSite(String origin, String host) {
        if (origin == null || origin.isEmpty() || "null".equals(origin)) {
            return false; // Not a browser cross-site request.
        }
        if (host == null || host.isEmpty()) {
            return true; // Origin present but no Host to compare against: refuse.
        }
        String originAuthority = origin.replaceFirst("(?i)^https?://", "");
        return !originAuthority.equalsIgnoreCase(host);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {}
    }

    private static void sendResponse(Socket socket, String body, int statusCode, String contentType) throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        String response = "HTTP/1.1 " + statusCode + " " + statusText(statusCode) + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "\r\n";

        OutputStream os = socket.getOutputStream();
        os.write(response.getBytes("UTF-8"));
        os.write(payload);
        os.flush();
    }

    private static String statusText(int statusCode) {
        switch (statusCode) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            default: return "Error";
        }
    }

    private static String handleM3uUrlPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String m3uUrl = params.get("m3u_url");
            if (m3uUrl != null && !m3uUrl.isEmpty()) {
                AppConfig.setM3uUrl(m3uUrl);
                OnConfigChangeListener listener = sConfigChangeListener;
                if (listener != null) {
                    listener.onM3uUrlChanged(m3uUrl);
                }
                JsonObject json = new JsonObject();
                json.addProperty("success", true);
                json.addProperty("message", "M3U URL saved");
                return json.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving M3U URL", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to save M3U URL");
        return json.toString();
    }

    private static String handleEpgUrlPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String epgUrl = params.get("epg_url");
            if (epgUrl != null && !epgUrl.isEmpty()) {
                AppConfig.setEpgUrl(epgUrl);
                OnConfigChangeListener listener = sConfigChangeListener;
                if (listener != null) {
                    listener.onEpgUrlChanged(epgUrl);
                }
                JsonObject json = new JsonObject();
                json.addProperty("success", true);
                json.addProperty("message", "EPG URL saved");
                return json.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving EPG URL", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to save EPG URL");
        return json.toString();
    }

    private static String handleEpgPreferencePost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String preference = params.get("epg_preference");
            if (preference != null && (AppConfig.EPG_PREFERENCE_AUTO.equals(preference)
                    || AppConfig.EPG_PREFERENCE_M3U.equals(preference)
                    || AppConfig.EPG_PREFERENCE_USER.equals(preference))) {
                AppConfig.setEpgPreference(preference);
                OnConfigChangeListener listener = sConfigChangeListener;
                if (listener != null) {
                    listener.onEpgPreferenceChanged(preference);
                }
                JsonObject json = new JsonObject();
                json.addProperty("success", true);
                json.addProperty("message", "EPG priority saved");
                return json.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving EPG preference", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to save EPG priority");
        return json.toString();
    }

    /** Removes the local playlist so the configured URL becomes the source again. */
    private static String handleM3uFileClear() {
        try {
            String path = AppConfig.getM3uFilePath();
            if (!TextUtils.isEmpty(path)) {
                //noinspection ResultOfMethodCallIgnored
                new File(path).delete();
            }
            AppConfig.setM3uFilePath("");
            OnConfigChangeListener listener = sConfigChangeListener;
            if (listener != null) {
                listener.onM3uFileChanged("");
            }
            JsonObject json = new JsonObject();
            json.addProperty("success", true);
            json.addProperty("message", "Local M3U file removed");
            return json.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error clearing local M3U file", e);
        }
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", "Failed to clear local M3U file");
        return json.toString();
    }

    private static String getConfigJson() {
        JsonObject config = new JsonObject();
        config.addProperty("m3u_url", AppConfig.getM3uUrl());
        config.addProperty("m3u_file_path", AppConfig.getM3uFilePath());
        // Where to drop a playlist when the TV has no document picker (issue #7).
        Context appContext = sAppContext;
        config.addProperty("m3u_drop_dir", appContext == null ? ""
                : com.cabletv.player.util.LocalPlaylistFolder.dropDir(appContext).getAbsolutePath());
        config.addProperty("epg_url", AppConfig.getEpgUrl());
        config.addProperty("epg_preference", AppConfig.getEpgPreference());
        config.add("m3u_epg_urls", new com.google.gson.Gson().toJsonTree(AppConfig.getM3uEpgUrls()));
        List<String> active = AppConfig.resolveEpgUrls();
        config.addProperty("active_epg_url", active.isEmpty() ? "" : active.get(0));
        config.addProperty("web_server_enabled", AppConfig.isWebServerEnabled());
        // Display and playback preferences, so the web page and the TV settings screen expose the
        // same configuration instead of each covering half of it.
        config.addProperty("epg_display_enabled", AppConfig.isEpgDisplayEnabled());
        config.addProperty("sidebar_opacity", AppConfig.getSidebarOpacity());
        config.addProperty("video_render_mode",
                AppConfig.getVideoRenderMode() == AppConfig.RENDER_TEXTURE ? "texture" : "surface");
        config.addProperty("video_decode_mode", decodeModeName(AppConfig.getVideoDecodeMode()));
        config.addProperty("video_tunneling", AppConfig.isTunnelingEnabled());
        return config.toString();
    }

    private static String decodeModeName(int mode) {
        switch (mode) {
            case VideoViewConfig.DECODE_HARDWARE:
                return "hardware";
            case VideoViewConfig.DECODE_SOFTWARE:
                return "software";
            default:
                return "auto";
        }
    }

    /**
     * Saves the playback preferences. Every provided field must be valid, otherwise nothing is
     * written — a half-applied change would be harder to notice than a rejection. These take
     * effect on the next launch (the player reads its config once per process), which the page
     * tells the user.
     */
    private static String handlePlaybackPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String render = params.get("video_render_mode");
            String decode = params.get("video_decode_mode");
            String tunneling = params.get("video_tunneling");
            if (!isValidRenderMode(render) || !isValidDecodeMode(decode) || !isValidFlag(tunneling)) {
                return jsonResult(false, "Invalid playback setting");
            }
            if (render != null) {
                AppConfig.setVideoRenderMode("texture".equals(render)
                        ? AppConfig.RENDER_TEXTURE : AppConfig.RENDER_SURFACE);
            }
            if (decode != null) {
                AppConfig.setVideoDecodeMode(decodeModeOf(decode));
            }
            if (tunneling != null) {
                AppConfig.setTunnelingEnabled("1".equals(tunneling));
            }
            return jsonResult(true, "Playback settings saved (restart the app to apply)");
        } catch (Exception e) {
            Log.e(TAG, "Error saving playback settings", e);
            return jsonResult(false, "Failed to save playback settings");
        }
    }

    /** Saves the display preferences; both are applied the next time the channel list opens. */
    private static String handleDisplayPost(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String epgDisplay = params.get("epg_display_enabled");
            String opacity = params.get("sidebar_opacity");
            if (!isValidFlag(epgDisplay) || !isValidOpacity(opacity)) {
                return jsonResult(false, "Invalid display setting");
            }
            if (epgDisplay != null) {
                AppConfig.setEpgDisplayEnabled("1".equals(epgDisplay));
            }
            if (opacity != null) {
                AppConfig.setSidebarOpacity(Integer.parseInt(opacity));
            }
            return jsonResult(true, "Display settings saved");
        } catch (Exception e) {
            Log.e(TAG, "Error saving display settings", e);
            return jsonResult(false, "Failed to save display settings");
        }
    }

    private static boolean isValidRenderMode(String value) {
        return value == null || "surface".equals(value) || "texture".equals(value);
    }

    private static boolean isValidDecodeMode(String value) {
        return value == null || "auto".equals(value) || "hardware".equals(value)
                || "software".equals(value);
    }

    private static int decodeModeOf(String value) {
        if ("hardware".equals(value)) {
            return VideoViewConfig.DECODE_HARDWARE;
        }
        if ("software".equals(value)) {
            return VideoViewConfig.DECODE_SOFTWARE;
        }
        return VideoViewConfig.DECODE_AUTO;
    }

    private static boolean isValidFlag(String value) {
        return value == null || "0".equals(value) || "1".equals(value);
    }

    private static boolean isValidOpacity(String value) {
        if (value == null) {
            return true;
        }
        return "0".equals(value) || "1".equals(value) || "2".equals(value);
    }

    private static String jsonResult(boolean success, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("success", success);
        json.addProperty("message", message);
        return json.toString();
    }

    private static Map<String, String> parseFormData(String body) {
        Map<String, String> params = new HashMap<>();
        try {
            for (String pair : body.split("&")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    params.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing form data", e);
        }
        return params;
    }

    private static String getIndexHtml() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>LiteTV 配置</title>\n" +
                "    <style>\n" +
                "        body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background-color: #1e1e1e; color: #fff; margin: 0; padding: 20px; }\n" +
                "        .container { max-width: 700px; margin: 0 auto; }\n" +
                "        h1 { color: #FF6B35; margin-bottom: 30px; text-align: center; }\n" +
                "        .section { background-color: #2a2a2a; border-left: 4px solid #FF6B35; border-radius: 4px; padding: 20px; margin-bottom: 20px; }\n" +
                "        .section h2 { color: #FF6B35; margin-top: 0; font-size: 18px; }\n" +
                "        label { display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; }\n" +
                "        input[type=\"text\"], input[type=\"url\"], textarea { width: 100%; padding: 10px; margin-bottom: 12px; background-color: #1a1a1a; color: #fff; border: 1px solid #444; border-radius: 4px; box-sizing: border-box; font-size: 14px; }\n" +
                "        input[type=\"text\"]:focus, input[type=\"url\"]:focus, textarea:focus { outline: none; border-color: #FF6B35; }\n" +
                "        button { background-color: #FF6B35; color: #fff; padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; font-weight: 500; transition: background-color 0.3s; }\n" +
                "        button:hover { background-color: #ff7d52; }\n" +
                "        .info { font-size: 12px; color: #aaa; margin-top: 8px; line-height: 1.5; }\n" +
                "        .status { padding: 10px; border-radius: 4px; margin-top: 8px; font-size: 12px; display: none; }\n" +
                "        .status.success { background-color: #2d5a2d; color: #90EE90; display: block; }\n" +
                "        .status.error { background-color: #5a2d2d; color: #FF6B6B; display: block; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"container\">\n" +
                "        <h1>📺 LiteTV 配置</h1>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>M3U 播放列表</h2>\n" +
                "            <label for=\"m3uUrl\">播放列表 URL:</label>\n" +
                "            <input type=\"url\" id=\"m3uUrl\" placeholder=\"http://example.com/playlist.m3u\">\n" +
                "            <div class=\"info\">输入 M3U 播放列表的完整 URL，例如: http://example.com/playlist.m3u</div>\n" +
                "            <button onclick=\"saveM3uUrl()\">保存 M3U URL</button>\n" +
                "            <div id=\"m3uStatus\" class=\"status\"></div>\n" +
                "            <div id=\"m3uFileInfo\" class=\"info\"></div>\n" +
                "            <div id=\"m3uDropDirInfo\" class=\"info\"></div>\n" +
                "            <button id=\"clearM3uFileBtn\" onclick=\"clearM3uFile()\" style=\"display:none;\">清除本地文件</button>\n" +
                "            <div id=\"m3uFileStatus\" class=\"status\"></div>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>EPG 电子节目单</h2>\n" +
                "            <label for=\"epgUrl\">EPG 地址模板:</label>\n" +
                "            <input type=\"url\" id=\"epgUrl\" placeholder=\"http://epg.example.com/api/diyp?ch={name}&date={date}\">\n" +
                "            <div class=\"info\">支持 {name} 和 {date} 占位符，例如: http://epg.example.com/api/diyp?ch={name}&date={date}</div>\n" +
                "            <button onclick=\"saveEpgUrl()\">保存 EPG 地址</button>\n" +
                "            <div id=\"epgStatus\" class=\"status\"></div>\n" +
                "            <label for=\"epgPreference\" style=\"margin-top:16px;\">EPG 来源优先级:</label>\n" +
                "            <select id=\"epgPreference\" style=\"width:100%;padding:10px;margin-bottom:12px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"auto\">自动：优先使用我设置的 EPG，未设置时用 M3U 内置</option>\n" +
                "                <option value=\"m3u\">优先使用 M3U 内置 EPG</option>\n" +
                "                <option value=\"user\">仅使用我设置的 EPG</option>\n" +
                "            </select>\n" +
                "            <button onclick=\"saveEpgPreference()\">保存优先级</button>\n" +
                "            <div id=\"epgPrefStatus\" class=\"status\"></div>\n" +
                "            <div id=\"epgM3uInfo\" class=\"info\"></div>\n" +
                "            <div id=\"epgActiveInfo\" class=\"info\"></div>\n" +
                "        </div>\n" +
                "        \n" +
                "        <div class=\"section\">\n" +
                "            <h2>播放与显示</h2>\n" +
                "            <label for=\"videoRenderMode\">画面输出方式:</label>\n" +
                "            <select id=\"videoRenderMode\" style=\"width:100%;padding:10px;margin-bottom:8px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"surface\">SurfaceView（推荐，画面更清晰）</option>\n" +
                "                <option value=\"texture\">TextureView（兼容备用）</option>\n" +
                "            </select>\n" +
                "            <label for=\"videoDecodeMode\">解码方式:</label>\n" +
                "            <select id=\"videoDecodeMode\" style=\"width:100%;padding:10px;margin-bottom:8px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"auto\">自动（优先硬件，失败回退软件）</option>\n" +
                "                <option value=\"hardware\">仅硬件解码</option>\n" +
                "                <option value=\"software\">优先软件解码（可解决个别频道打不开）</option>\n" +
                "            </select>\n" +
                "            <label style=\"display:flex;align-items:center;gap:8px;margin-bottom:12px;\">\n" +
                "                <input type=\"checkbox\" id=\"videoTunneling\"> 视频直通显示（实验，部分电视画面更流畅）\n" +
                "            </label>\n" +
                "            <label for=\"epgDisplay\">频道列表显示节目单:</label>\n" +
                "            <select id=\"epgDisplay\" style=\"width:100%;padding:10px;margin-bottom:8px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"1\">显示</option>\n" +
                "                <option value=\"0\">不显示</option>\n" +
                "            </select>\n" +
                "            <label for=\"sidebarOpacity\">频道列表背景:</label>\n" +
                "            <select id=\"sidebarOpacity\" style=\"width:100%;padding:10px;margin-bottom:12px;background-color:#1a1a1a;color:#fff;border:1px solid #444;border-radius:4px;font-size:14px;\">\n" +
                "                <option value=\"0\">半透明（默认）</option>\n" +
                "                <option value=\"1\">半不透明</option>\n" +
                "                <option value=\"2\">不透明</option>\n" +
                "            </select>\n" +
                "            <button onclick=\"savePlayback()\">保存播放设置</button>\n" +
                "            <div id=\"playbackStatus\" class=\"status\"></div>\n" +
                "            <div class=\"info\">播放设置需重启电视端应用后生效；显示设置下次打开频道列表时生效。</div>\n" +
                "        </div>\n" +
                "    </div>\n" +
                "    <script>\n" +
                "        function saveM3uUrl() {\n" +
                "            const url = document.getElementById('m3uUrl').value.trim();\n" +
                "            if (!url) { alert('请输入 M3U URL'); return; }\n" +
                "            fetch('/config/m3u-url', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'm3u_url=' + encodeURIComponent(url)\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('m3uStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message + ' (TV 端将自动重新加载)';\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('m3uStatus').className = 'status error';\n" +
                "                document.getElementById('m3uStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function saveEpgUrl() {\n" +
                "            const url = document.getElementById('epgUrl').value.trim();\n" +
                "            if (!url) { alert('请输入 EPG 地址'); return; }\n" +
                "            fetch('/config/epg-url', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'epg_url=' + encodeURIComponent(url)\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('epgStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message;\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('epgStatus').className = 'status error';\n" +
                "                document.getElementById('epgStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function saveEpgPreference() {\n" +
                "            const pref = document.getElementById('epgPreference').value;\n" +
                "            fetch('/config/epg-preference', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'epg_preference=' + encodeURIComponent(pref)\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('epgPrefStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message + ' (TV 端将自动重新加载)';\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('epgPrefStatus').className = 'status error';\n" +
                "                document.getElementById('epgPrefStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function clearM3uFile() {\n" +
                "            fetch('/config/m3u-file-clear', {\n" +
                "                method: 'POST',\n" +
                "                headers: {'Content-Type': 'application/x-www-form-urlencoded'},\n" +
                "                body: 'action=clear'\n" +
                "            }).then(r => r.json()).then(d => {\n" +
                "                const status = document.getElementById('m3uFileStatus');\n" +
                "                if (d.success) {\n" +
                "                    status.className = 'status success';\n" +
                "                    status.textContent = '✓ ' + d.message + ' (TV 端将自动重新加载)';\n" +
                "                    document.getElementById('m3uFileInfo').textContent = '未使用本地文件，使用上方 M3U 地址';\n" +
                "                    document.getElementById('clearM3uFileBtn').style.display = 'none';\n" +
                "                } else {\n" +
                "                    status.className = 'status error';\n" +
                "                    status.textContent = '✗ ' + d.message;\n" +
                "                }\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('m3uFileStatus').className = 'status error';\n" +
                "                document.getElementById('m3uFileStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        function savePlayback() {\n" +
                "            const body = 'video_render_mode=' + encodeURIComponent(document.getElementById('videoRenderMode').value)\n" +
                "                + '&video_decode_mode=' + encodeURIComponent(document.getElementById('videoDecodeMode').value)\n" +
                "                + '&video_tunneling=' + (document.getElementById('videoTunneling').checked ? '1' : '0')\n" +
                "                + '&epg_display_enabled=' + encodeURIComponent(document.getElementById('epgDisplay').value)\n" +
                "                + '&sidebar_opacity=' + encodeURIComponent(document.getElementById('sidebarOpacity').value);\n" +
                "            Promise.all([\n" +
                "                fetch('/config/playback', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body}),\n" +
                "                fetch('/config/display', {method: 'POST', headers: {'Content-Type': 'application/x-www-form-urlencoded'}, body: body})\n" +
                "            ]).then(rs => Promise.all(rs.map(r => r.json()))).then(ds => {\n" +
                "                const status = document.getElementById('playbackStatus');\n" +
                "                const ok = ds.every(d => d.success);\n" +
                "                status.className = 'status ' + (ok ? 'success' : 'error');\n" +
                "                status.textContent = ok ? '✓ ' + ds[0].message : '✗ ' + (ds.find(d => !d.success) || {}).message;\n" +
                "            }).catch(e => {\n" +
                "                document.getElementById('playbackStatus').className = 'status error';\n" +
                "                document.getElementById('playbackStatus').textContent = '✗ 网络错误: ' + e;\n" +
                "            });\n" +
                "        }\n" +
                "        window.onload = function() {\n" +
                "            fetch('/config/get').then(r => r.json()).then(d => {\n" +
                "                document.getElementById('m3uUrl').value = d.m3u_url || '';\n" +
                "                document.getElementById('epgUrl').value = d.epg_url || '';\n" +
                "                document.getElementById('epgPreference').value = d.epg_preference || 'auto';\n" +
                "                document.getElementById('videoRenderMode').value = d.video_render_mode || 'surface';\n" +
                "                document.getElementById('videoDecodeMode').value = d.video_decode_mode || 'auto';\n" +
                "                document.getElementById('videoTunneling').checked = !!d.video_tunneling;\n" +
                "                document.getElementById('epgDisplay').value = d.epg_display_enabled === false ? '0' : '1';\n" +
                "                document.getElementById('sidebarOpacity').value = String(d.sidebar_opacity == null ? 0 : d.sidebar_opacity);\n" +
                "                const m3uFile = d.m3u_file_path || '';\n" +
                "                const dropDir = d.m3u_drop_dir || '';\n" +
                "                if (dropDir) {\n" +
                "                    document.getElementById('m3uDropDirInfo').textContent = '也可把 .m3u 文件放到电视上：' + dropDir + '，再在电视设置里点「从文件夹导入」';\n" +
                "                }\n" +
                "                if (m3uFile) {\n" +
                "                    document.getElementById('m3uFileInfo').textContent = '本地 M3U 文件生效: ' + m3uFile;\n" +
                "                    document.getElementById('clearM3uFileBtn').style.display = 'block';\n" +
                "                } else {\n" +
                "                    document.getElementById('m3uFileInfo').textContent = '未使用本地文件，使用上方 M3U 地址';\n" +
                "                }\n" +
                "                const m3uUrls = d.m3u_epg_urls || [];\n" +
                "                document.getElementById('epgM3uInfo').textContent = m3uUrls.length\n" +
                "                    ? 'M3U 内置 EPG: ' + m3uUrls.join(' | ')\n" +
                "                    : '当前 M3U 未提供内置 EPG';\n" +
                "                const active = d.active_epg_url || '';\n" +
                "                document.getElementById('epgActiveInfo').textContent = active\n" +
                "                    ? '当前生效 EPG: ' + active\n" +
                "                    : '当前无生效 EPG 来源';\n" +
                "            }).catch(e => console.error('加载配置失败:', e));\n" +
                "        };\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }
}
