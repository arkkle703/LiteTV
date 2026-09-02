package com.cabletv.player.ui;

import android.app.Activity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Hides the system bars (status bar on phones, nav bar everywhere) so playback fills the whole
 * screen. The flags are re-applied whenever the window regains focus: a pull-down of the status
 * bar or the system's auto-restore would otherwise leave the chrome on screen until relaunch.
 * TV boxes usually have nothing to hide, but the same call is harmless there.
 */
public final class ImmersiveMode {

    private ImmersiveMode() {
    }

    public static void apply(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        // Pre-API-21 cutout/edge handling is not needed; keep the fullscreen window flags for
        // devices that honour them even before the view flags take effect.
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    /** Call from onWindowFocusChanged(hasFocus) so the bars stay hidden after focus returns. */
    public static void onWindowFocusChanged(Activity activity, boolean hasFocus) {
        if (hasFocus) {
            apply(activity);
        }
    }
}