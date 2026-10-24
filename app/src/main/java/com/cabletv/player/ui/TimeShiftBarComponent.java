package com.cabletv.player.ui;

// 配色说明：本文件里出现的颜色值全部来自 LiteTV 原有调色板（见 res/values/colors.xml）——
// 主色 colorAccent(#FF6B35) 与其暗调 item_selected_dim_text(#FF9966)、正文 text_primary/FFFFFF、
// 次要 text_secondary/CCCCCC、节目单 epg_text_color/888888、next_program_text/999999。
// 没有引入新的色相，改色只需改 colors.xml。

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.cabletv.player.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 播放控制面板（mytv 风格，电视友好）。
 *
 * <p>原来这里是一条只能用手指拖的时移条——遥控器用户根本没法操作。现在改成三段：
 * 标题行（频道 + 位置）/ 可拖动的进度条（手机仍可手指拖）/ 一排按钮。
 * 按钮用左右键移动光标、OK 执行，面板自动隐藏，遥控器上全程不需要触摸。
 *
 * <p>三颗按钮固定顺序：后退 10 秒、前进 10 秒、回到直播（直播状态下变成"从头回看"）。
 * 组件本身不懂回看机制，只把绝对时间点或"回到直播"的意图抛给 {@link OnSeekListener}。
 */
public class TimeShiftBarComponent extends FrameLayout {
    private static final int PANEL_HEIGHT_DP = 182;
    private static final int AUTO_HIDE_MS = 8000;
    private static final long STEP_MS = 10_000L;

    private final TextView mChannelLabel;
    private final TextView mPositionLabel;
    private final TextView mWindowLabel;
    private final SeekBar mSeekBar;
    private final LinearLayout mButtonRow;
    private final List<TextView> mButtons = new ArrayList<>();

    private OnSeekListener mSeekListener;
    private final Runnable mAutoHideRunnable = this::hideSmooth;

    private long mWindowStartMs = 0;
    private long mWindowEndMs = 0;
    private long mPositionMs = 0;
    private boolean mDragging = false;
    private boolean mReplayMode = false;
    private int mFocusIndex = 0;

    public interface OnSeekListener {
        /** 用户把播放位置定在这个绝对时间（epoch 毫秒）。 */
        void onSeekTo(long targetMs);

        /** 用户要求直接回到直播。 */
        void onBackToLive();
    }

    public TimeShiftBarComponent(@NonNull Context context) {
        super(context);
        setBackgroundResource(R.drawable.bg_control_panel);
        setPadding(dp2px(32), dp2px(16), dp2px(32), dp2px(16));
        setVisibility(GONE);
        setClickable(true);

        // 标题行：频道名 --------------------------------- 位置
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        addView(header, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        TextView caption = new TextView(context);
        caption.setText(R.string.control_title);
        caption.setTextColor(0xFFFF9966);
        caption.setTextSize(12);
        caption.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(caption);

        mChannelLabel = new TextView(context);
        mChannelLabel.setTextColor(0xFFFFFFFF);
        mChannelLabel.setTextSize(14);
        mChannelLabel.setMaxLines(1);
        LinearLayout.LayoutParams channelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        channelLp.leftMargin = dp2px(10);
        header.addView(mChannelLabel, channelLp);

        mPositionLabel = new TextView(context);
        mPositionLabel.setTextColor(0xFFFF9966);
        mPositionLabel.setTextSize(14);
        header.addView(mPositionLabel);

        // 进度条：手机可以直接拖，遥控器用下面的按钮
        mSeekBar = new SeekBar(context);
        mSeekBar.setMax(1000);
        mSeekBar.setProgress(0);
        mSeekBar.setFocusable(false);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp2px(32));
        barLp.topMargin = dp2px(34);
        addView(mSeekBar, barLp);

        mWindowLabel = new TextView(context);
        mWindowLabel.setTextColor(0xFF999999);
        mWindowLabel.setTextSize(11);
        FrameLayout.LayoutParams windowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        windowLp.topMargin = dp2px(64);
        addView(mWindowLabel, windowLp);

        // 按钮行
        mButtonRow = new LinearLayout(context);
        mButtonRow.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp2px(44));
        rowLp.topMargin = dp2px(96);
        addView(mButtonRow, rowLp);

        addButton(context, getString(R.string.control_rewind), () -> seekBy(-STEP_MS));
        addButton(context, getString(R.string.control_forward), () -> seekBy(STEP_MS));
        addButton(context, getString(R.string.control_live), this::activateThirdButton);

        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mPositionMs = windowPosition(progress);
                    updateLabels();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mDragging = true;
                cancelAutoHide();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                mDragging = false;
                commitProgress(seekBar.getProgress());
                scheduleAutoHide();
            }
        });

        applyButtonFocus();
    }

    private void addButton(Context context, String label, Runnable action) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        // 焦点由 MainActivity 统一管，交给视图层会和自定义高亮打架
        button.setFocusable(false);
        button.setSingleLine(true);
        button.setPadding(dp2px(18), 0, dp2px(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT);
        lp.rightMargin = dp2px(12);
        mButtonRow.addView(button, lp);
        button.setOnClickListener(v -> {
            action.run();
            scheduleAutoHide();
        });
        mButtons.add(button);
    }

    private String getString(int resId) {
        return getContext().getString(resId);
    }

    // ------------------------------------------------------------------ 遥控器

    /** 左右键在按钮之间移动光标。 */
    public boolean handleHorizontal(boolean right) {
        if (mButtons.isEmpty()) {
            return true;
        }
        int next = mFocusIndex + (right ? 1 : -1);
        if (next < 0 || next >= mButtons.size()) {
            return true;
        }
        mFocusIndex = next;
        applyButtonFocus();
        scheduleAutoHide();
        return true;
    }

    /** OK 执行光标所在的按钮。 */
    public boolean handleOk() {
        if (mFocusIndex >= 0 && mFocusIndex < mButtons.size()) {
            mButtons.get(mFocusIndex).performClick();
        }
        return true;
    }

    public int getFocusIndex() {
        return mFocusIndex;
    }

    private void applyButtonFocus() {
        for (int i = 0; i < mButtons.size(); i++) {
            TextView button = mButtons.get(i);
            if (i == mFocusIndex) {
                button.setBackgroundResource(R.drawable.bg_control_button_focused);
                button.setTextColor(0xFFFFFFFF);
            } else {
                button.setBackgroundResource(R.drawable.bg_control_button);
                button.setTextColor(0xFFCCCCCC);
            }
        }
    }

    /** 直播 / 回看切换时第三颗按钮的含义跟着变。 */
    public void setReplayMode(boolean replay) {
        mReplayMode = replay;
        if (mButtons.size() >= 3) {
            mButtons.get(2).setText(replay
                    ? getString(R.string.control_live)
                    : getString(R.string.control_replay_from_start));
        }
        applyButtonFocus();
    }

    /** 直播状态下"回到直播"没有意义，改叫"从头回看"。 */
    public void setBackToLiveVisible(boolean visible) {
        setReplayMode(visible);
    }

    private void activateThirdButton() {
        if (mSeekListener == null) {
            return;
        }
        if (mReplayMode) {
            mSeekListener.onBackToLive();
        } else {
            // 从头回看 = 把播放位置定在窗口左端（节目开始）
            mSeekListener.onSeekTo(mWindowStartMs);
        }
    }

    private void seekBy(long deltaMs) {
        if (mSeekListener == null) {
            return;
        }
        long target = Math.max(mWindowStartMs, Math.min(mWindowEndMs, mPositionMs + deltaMs));
        mPositionMs = target;
        mSeekBar.setProgress(progressFor(target));
        updateLabels();
        mSeekListener.onSeekTo(target);
    }

    // ------------------------------------------------------------------ 数据

    /** 提交一个进度值（0–1000）：刷新标签并抛出绝对时间。 */
    void commitProgress(int progress) {
        mPositionMs = windowPosition(progress);
        mSeekBar.setProgress(progress);
        updateLabels();
        if (mSeekListener != null) {
            mSeekListener.onSeekTo(mPositionMs);
        }
    }

    /** 当前显示的位置标签（测试用）。 */
    String findShownPositionLabel() {
        return mPositionLabel.getText().toString();
    }

    /** 贴在屏幕底部、占满宽度。 */
    public FrameLayout.LayoutParams createBottomLayoutParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp2px(PANEL_HEIGHT_DP));
        lp.gravity = Gravity.BOTTOM;
        return lp;
    }

    private long windowPosition(int progress) {
        if (mWindowEndMs <= mWindowStartMs) {
            return mWindowStartMs;
        }
        return mWindowStartMs + (mWindowEndMs - mWindowStartMs) * progress / 1000L;
    }

    public void setOnSeekListener(@Nullable OnSeekListener listener) {
        mSeekListener = listener;
    }

    /** 设置可拖的窗口（epoch 毫秒）以及当前播放位置。 */
    public void setWindow(long windowStartMs, long windowEndMs, long positionMs) {
        mWindowStartMs = windowStartMs;
        mWindowEndMs = Math.max(windowStartMs + 1000, windowEndMs);
        mPositionMs = Math.max(windowStartMs, Math.min(mWindowEndMs, positionMs));
        mSeekBar.setProgress(progressFor(mPositionMs));
        updateLabels();
    }

    public void setChannelLabel(String label) {
        mChannelLabel.setText(label);
    }

    private int progressFor(long positionMs) {
        long span = mWindowEndMs - mWindowStartMs;
        if (span <= 0) {
            return 0;
        }
        long rel = Math.max(0, Math.min(span, positionMs - mWindowStartMs));
        return (int) Math.round(rel * 1000.0 / span);
    }

    private void updateLabels() {
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mPositionLabel.setText(getContext().getString(R.string.timeshift_position,
                timeFmt.format(new Date(mPositionMs))));
        mWindowLabel.setText(getContext().getString(R.string.timeshift_window,
                timeFmt.format(new Date(mWindowStartMs)),
                timeFmt.format(new Date(mWindowEndMs))));
    }

    /** 直播时让光标跟着真实时间走（拖动过程中不抢）。 */
    public void tickIfIdle() {
        if (mDragging || getVisibility() != VISIBLE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now > mWindowEndMs || now <= mWindowStartMs) {
            return;
        }
        mPositionMs = now;
        mSeekBar.setProgress(progressFor(mPositionMs));
        updateLabels();
    }

    public void show() {
        removeCallbacks(mAutoHideRunnable);
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(160).start();
        scheduleAutoHide();
    }

    public void hideSmooth() {
        if (getVisibility() != VISIBLE) {
            return;
        }
        animate().alpha(0f).setDuration(220).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
        }).start();
    }

    public void hide() {
        removeCallbacks(mAutoHideRunnable);
        setVisibility(GONE);
        setAlpha(1f);
    }

    private void scheduleAutoHide() {
        cancelAutoHide();
        postDelayed(mAutoHideRunnable, AUTO_HIDE_MS);
    }

    private void cancelAutoHide() {
        removeCallbacks(mAutoHideRunnable);
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}
