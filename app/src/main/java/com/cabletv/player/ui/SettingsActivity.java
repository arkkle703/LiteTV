package com.cabletv.player.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.cabletv.player.R;
import com.cabletv.player.config.AppConfig;
import com.cabletv.player.server.ConfigWebServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class SettingsActivity extends Activity {
    private static final String TAG = "SettingsActivity";
    private static final int REQUEST_PICK_M3U_FILE = 1001;
    /** Same ceiling as ChannelRepository, so a playlist that loads here also loads there. */
    private static final long MAX_LOCAL_M3U_BYTES = 8 * 1024 * 1024;

    private EditText mM3uUrlInput;
    private EditText mEpgUrlInput;
    private CheckBox mWebServerToggle;
    private TextView mWebServerAddress;
    private CheckBox mEpgDisplayToggle;
    private RadioGroup mSidebarOpacityGroup;
    private RadioGroup mEpgPreferenceGroup;
    private TextView mEpgM3uEmbeddedText;
    private TextView mM3uFilePathText;
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        ImmersiveMode.onWindowFocusChanged(this, hasFocus);
    }

    /** Error string id of the last failed file copy (0 when none), shown once by onActivityResult. */
    private int mLastFilePickError = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImmersiveMode.apply(this);
        setContentView(R.layout.activity_settings);

        mM3uUrlInput = findViewById(R.id.m3u_url_input);
        mEpgUrlInput = findViewById(R.id.epg_url_input);
        mWebServerToggle = findViewById(R.id.web_server_toggle);
        mWebServerAddress = findViewById(R.id.web_server_address);
        mSidebarOpacityGroup = findViewById(R.id.sidebar_opacity_group);
        mEpgPreferenceGroup = findViewById(R.id.epg_preference_group);
        mEpgM3uEmbeddedText = findViewById(R.id.epg_m3u_embedded);
        mM3uFilePathText = findViewById(R.id.m3u_file_path_text);

        Button saveM3uBtn = findViewById(R.id.save_m3u_btn);
        Button saveEpgBtn = findViewById(R.id.save_epg_btn);
        Button pickM3uFileBtn = findViewById(R.id.pick_m3u_file_btn);
        Button clearM3uFileBtn = findViewById(R.id.clear_m3u_file_btn);

        // Find or create EPG display toggle
        try {
            mEpgDisplayToggle = findViewById(R.id.epg_display_toggle);
            if (mEpgDisplayToggle == null) {
                createToggleIfNotExists();
            }
        } catch (Exception e) {
            createToggleIfNotExists();
        }

        // Load current values
        loadValues();

        // Setup listeners
        saveM3uBtn.setOnClickListener(v -> saveM3uUrl());
        saveEpgBtn.setOnClickListener(v -> saveEpgUrl());
        pickM3uFileBtn.setOnClickListener(v -> pickM3uFile());
        clearM3uFileBtn.setOnClickListener(v -> clearM3uFile());
        mWebServerToggle.setOnCheckedChangeListener((btn, isChecked) -> handleWebServerToggle(isChecked));

        if (mEpgDisplayToggle != null) {
            mEpgDisplayToggle.setOnCheckedChangeListener((btn, isChecked) -> {
                AppConfig.setEpgDisplayEnabled(isChecked);
                Toast.makeText(this, isChecked ? R.string.epg_display_enabled : R.string.epg_display_disabled,
                        Toast.LENGTH_SHORT).show();
            });
        }

        if (mSidebarOpacityGroup != null) {
            mSidebarOpacityGroup.setOnCheckedChangeListener((group, checkedId) -> {
                AppConfig.setSidebarOpacity(opacityOf(checkedId));
                Toast.makeText(this, R.string.sidebar_opacity_saved, Toast.LENGTH_SHORT).show();
            });
        }

        if (mEpgPreferenceGroup != null) {
            mEpgPreferenceGroup.setOnCheckedChangeListener((group, checkedId) -> {
                AppConfig.setEpgPreference(epgPreferenceOf(checkedId));
                Toast.makeText(this, R.string.epg_preference_saved, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private static String epgPreferenceOf(int checkedId) {
        if (checkedId == R.id.epg_preference_m3u) {
            return AppConfig.EPG_PREFERENCE_M3U;
        }
        if (checkedId == R.id.epg_preference_user) {
            return AppConfig.EPG_PREFERENCE_USER;
        }
        return AppConfig.EPG_PREFERENCE_AUTO;
    }

    private static int epgPreferenceRadioId(String preference) {
        if (AppConfig.EPG_PREFERENCE_M3U.equals(preference)) {
            return R.id.epg_preference_m3u;
        }
        if (AppConfig.EPG_PREFERENCE_USER.equals(preference)) {
            return R.id.epg_preference_user;
        }
        return R.id.epg_preference_auto;
    }

    private static int opacityOf(int checkedId) {
        if (checkedId == R.id.sidebar_opacity_opaque) {
            return AppConfig.SIDEBAR_OPAQUE;
        }
        if (checkedId == R.id.sidebar_opacity_semi) {
            return AppConfig.SIDEBAR_SEMI_OPAQUE;
        }
        return AppConfig.SIDEBAR_TRANSLUCENT;
    }

    private static int radioIdOf(int opacity) {
        switch (opacity) {
            case AppConfig.SIDEBAR_OPAQUE:
                return R.id.sidebar_opacity_opaque;
            case AppConfig.SIDEBAR_SEMI_OPAQUE:
                return R.id.sidebar_opacity_semi;
            default:
                return R.id.sidebar_opacity_translucent;
        }
    }

    private void loadValues() {
        mM3uUrlInput.setText(AppConfig.getM3uUrl());
        mEpgUrlInput.setText(AppConfig.getEpgUrl());
        mWebServerToggle.setChecked(AppConfig.isWebServerEnabled());
        if (mEpgDisplayToggle != null) {
            mEpgDisplayToggle.setChecked(AppConfig.isEpgDisplayEnabled());
        }
        if (mSidebarOpacityGroup != null) {
            // Checked before the listener is attached, so restoring the saved value is not
            // mistaken for the viewer choosing it again.
            mSidebarOpacityGroup.check(radioIdOf(AppConfig.getSidebarOpacity()));
        }
        if (mEpgPreferenceGroup != null) {
            mEpgPreferenceGroup.check(epgPreferenceRadioId(AppConfig.getEpgPreference()));
        }
        updateM3uEpgInfo();
        updateM3uFileInfo();
        updateWebServerAddress();
    }

    /**
     * Opens the system file picker (SAF), so a USB drive or the internal storage can be
     * browsed with the remote. No storage permission is needed for ACTION_OPEN_DOCUMENT.
     */
    private void pickM3uFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // m3u files often carry no useful MIME type
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_PICK_M3U_FILE);
        } catch (Exception e) {
            Log.w(TAG, "No document picker available", e);
            Toast.makeText(this, R.string.m3u_file_pick_error, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_M3U_FILE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Best effort: the file is copied into app storage below, so the URI is not needed
            // again. Some providers refuse persistable grants and throw here.
        }
        String path = copyToAppStorage(uri);
        if (path == null) {
            int errorRes = mLastFilePickError;
            mLastFilePickError = 0;
            Toast.makeText(this, errorRes != 0 ? errorRes : R.string.m3u_file_copy_error,
                    Toast.LENGTH_LONG).show();
            return;
        }
        AppConfig.setM3uFilePath(path);
        Toast.makeText(this, R.string.m3u_file_saved, Toast.LENGTH_SHORT).show();
        updateM3uFileInfo();
    }

    /**
     * Copies the picked document into app-private storage so the playlist survives the drive
     * being unplugged and stays readable by path (ChannelRepository reads by path).
     *
     * @return the absolute path of the copy, or null when the file could not be read.
     */
    private String copyToAppStorage(Uri uri) {
        mLastFilePickError = 0;
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            try {
                File dir = new File(getFilesDir(), "playlists");
                if (!dir.exists() && !dir.mkdirs()) {
                    return null;
                }
                File target = new File(dir, "local.m3u");
                OutputStream out = new FileOutputStream(target);
                try {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_LOCAL_M3U_BYTES) {
                            mLastFilePickError = R.string.m3u_file_too_large;
                            return null;
                        }
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                } finally {
                    closeQuietly(out);
                }
                return target.getAbsolutePath();
            } finally {
                closeQuietly(in);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error copying picked M3U file", e);
            return null;
        }
    }

    private void clearM3uFile() {
        String path = AppConfig.getM3uFilePath();
        if (!TextUtils.isEmpty(path)) {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        }
        AppConfig.setM3uFilePath("");
        Toast.makeText(this, R.string.m3u_file_cleared, Toast.LENGTH_SHORT).show();
        updateM3uFileInfo();
    }

    /** Shows which local playlist is active, if any. */
    private void updateM3uFileInfo() {
        if (mM3uFilePathText == null) {
            return;
        }
        String path = AppConfig.getM3uFilePath();
        if (TextUtils.isEmpty(path)) {
            mM3uFilePathText.setText(R.string.m3u_file_none);
        } else {
            String name = new File(path).getName();
            if (new File(path).exists()) {
                mM3uFilePathText.setText(getString(R.string.m3u_file_selected, name));
            } else {
                // Stale reference (e.g. app data moved): tell the user to pick it again.
                mM3uFilePathText.setText(getString(R.string.m3u_file_missing, name));
            }
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    /** Shows which EPG feed the current playlist embedded, if any. */
    private void updateM3uEpgInfo() {
        if (mEpgM3uEmbeddedText == null) {
            return;
        }
        List<String> urls = AppConfig.getM3uEpgUrls();
        if (urls.isEmpty()) {
            mEpgM3uEmbeddedText.setText(R.string.epg_m3u_embedded_none);
        } else {
            mEpgM3uEmbeddedText.setText(getString(R.string.epg_m3u_embedded_found,
                    android.text.TextUtils.join(", ", urls)));
        }
    }

    private void createToggleIfNotExists() {
        // Create toggles programmatically if layout doesn't contain them
        // This is a fallback for when the layout doesn't define these checkboxes
    }

    private void saveM3uUrl() {
        String url = mM3uUrlInput.getText().toString().trim();
        AppConfig.setM3uUrl(url);
        Toast.makeText(this, R.string.m3u_url_saved, Toast.LENGTH_SHORT).show();
    }

    private void saveEpgUrl() {
        String url = mEpgUrlInput.getText().toString().trim();
        AppConfig.setEpgUrl(url);
        Toast.makeText(this, R.string.epg_url_saved, Toast.LENGTH_SHORT).show();
    }

    private void handleWebServerToggle(boolean enabled) {
        AppConfig.setWebServerEnabled(enabled);
        if (enabled) {
            ConfigWebServer.startServer(this);
            Toast.makeText(this, R.string.web_server_started, Toast.LENGTH_SHORT).show();
        } else {
            ConfigWebServer.stopServer();
            Toast.makeText(this, R.string.web_server_stopped, Toast.LENGTH_SHORT).show();
        }
        updateWebServerAddress();
    }

    private void updateWebServerAddress() {
        if (AppConfig.isWebServerEnabled()) {
            String ip = getLocalIpAddress();
            int port = AppConfig.getWebServerPort();
            String address = "http://" + ip + ":" + port;
            mWebServerAddress.setText(getString(R.string.server_address_format, address));
        } else {
            mWebServerAddress.setText("");
        }
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                int ipInt = wifiManager.getConnectionInfo().getIpAddress();
                return String.format("%d.%d.%d.%d",
                        (ipInt & 0xff),
                        (ipInt >> 8 & 0xff),
                        (ipInt >> 16 & 0xff),
                        (ipInt >> 24 & 0xff));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }
}
