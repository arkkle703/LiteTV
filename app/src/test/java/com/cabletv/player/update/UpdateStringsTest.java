package com.cabletv.player.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.cabletv.player.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The update section is the first screen where a translated string is built with format arguments,
 * and a locale whose string lost a {@code %1$s} throws at runtime rather than at build time. These
 * cases render the arguments in both shipped locales so such a mismatch fails here instead of on a
 * viewer's television.
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateStringsTest {

    @Test
    @Config(qualifiers = "en-rUS")
    public void englishStrings_carryTheirArguments() {
        assertFormats();
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    public void chineseStrings_carryTheirArguments() {
        assertFormats();
    }

    private static void assertFormats() {
        Context context = RuntimeEnvironment.getApplication();

        String version = context.getString(R.string.app_version_format, "1.4.0", 6);
        assertTrue("version text must show the name: " + version, version.contains("1.4.0"));
        assertTrue("version text must show the code: " + version, version.contains("6"));

        String available = context.getString(R.string.update_available, "1.5.0");
        assertTrue("update line must name the version: " + available, available.contains("1.5.0"));

        String lastCheck = context.getString(R.string.update_last_check_format, "STAMP");
        assertTrue("last-check line must show the stamp: " + lastCheck, lastCheck.contains("STAMP"));

        // Every line of the section must exist in both locales.
        int[] ids = {
                R.string.update_section, R.string.update_auto_check, R.string.update_auto_check_hint,
                R.string.update_auto_check_on, R.string.update_auto_check_off,
                R.string.update_check_now, R.string.update_checking, R.string.update_up_to_date,
                R.string.update_changelog_title, R.string.update_never_checked,
                R.string.update_error_network, R.string.update_error_http,
                R.string.update_error_no_manifest, R.string.update_error_parse,
                R.string.update_error_no_build, R.string.update_error_unknown,
                R.string.update_download, R.string.update_cancel_download, R.string.update_ready,
                R.string.update_error_checksum, R.string.update_error_signature,
                R.string.update_error_cancelled, R.string.update_error_storage,
        };
        for (int id : ids) {
            assertFalse("missing text for string id " + id, context.getString(id).trim().isEmpty());
        }

        String downloading = context.getString(R.string.update_downloading, 45);
        assertTrue("the progress line must show the percentage: " + downloading,
                downloading.contains("45"));
    }
}
