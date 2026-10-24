package com.cabletv.player.ui;

// 配色说明：本文件里出现的颜色值全部来自 LiteTV 原有调色板（见 res/values/colors.xml）——
// 主色 colorAccent(#FF6B35) 与其暗调 item_selected_dim_text(#FF9966)、正文 text_primary/FFFFFF、
// 次要 text_secondary/CCCCCC、节目单 epg_text_color/888888、next_program_text/999999。
// 没有引入新的色相，改色只需改 colors.xml。

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.cabletv.player.R;
import com.cabletv.player.epg.EpgManager;
import com.cabletv.player.model.Channel;
import xyz.doikki.videoplayer.controller.ControlWrapper;
import xyz.doikki.videoplayer.controller.IControlComponent;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 换台信息卡（mytv 风格）：左上角一张深色圆角卡片，换台时滑出。
 *
 * <p>电视上看"信息"和平板上不一样：不能靠触摸去点，也不能只闪一下。
 * 这里是四行固定结构——台标 + 频道名 + 频道号 / 当前节目 / 进度 / 下一档，
 * 停留 5 秒自动淡出；连续换台时重置计时器并原地刷新，不会叠出一堆卡片。
 */
public class PlaybackInfoComponent extends FrameLayout implements IControlComponent {

    private static final int AUTO_HIDE_MS = 5000;

    private final LinearLayout mCard;
    private final ImageView mLogoView;
    private final TextView mChannelNameView;
    private final TextView mChannelNumberView;
    private final TextView mCurrentProgramView;
    private final ProgressBar mProgressBar;
    private final TextView mTimeRangeView;
    private final TextView mNextProgramView;

    private EpgManager mEpgManager;
    private Channel mCurrentChannel;
    private int mChannelNumber = 0;
    private boolean mReplayMode = false;
    private final Runnable mHideRunnable = this::fadeOut;

    public PlaybackInfoComponent(@NonNull Context context) {
        super(context);
        setClickable(true);

        mCard = new LinearLayout(context);
        mCard.setOrientation(LinearLayout.VERTICAL);
        mCard.setBackgroundResource(R.drawable.bg_tune_card);
        mCard.setPadding(dp2px(18), dp2px(14), dp2px(18), dp2px(14));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp2px(460), LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.START;
        addView(mCard, cardLp);

        // 第一行：台标 + 频道名 + 频道号
        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        mCard.addView(titleRow, new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        mLogoView = new ImageView(context);
        mLogoView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                dp2px(40), dp2px(40));
        logoLp.rightMargin = dp2px(12);
        titleRow.addView(mLogoView, logoLp);

        mChannelNameView = new TextView(context);
        mChannelNameView.setTextColor(0xFFFFFFFF);
        mChannelNameView.setTextSize(21);
        mChannelNameView.setMaxLines(1);
        mChannelNameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(mChannelNameView, new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f));

        mChannelNumberView = new TextView(context);
        mChannelNumberView.setTextColor(0xFFFF9966);
        mChannelNumberView.setTextSize(13);
        mChannelNumberView.setSingleLine(true);
        titleRow.addView(mChannelNumberView);

        // 第二行：当前节目（回看时前面带「回看」标记）
        mCurrentProgramView = new TextView(context);
        mCurrentProgramView.setTextColor(0xFFFFFFFF);
        mCurrentProgramView.setTextSize(15);
        mCurrentProgramView.setSingleLine(true);
        mCurrentProgramView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams programLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        programLp.topMargin = dp2px(10);
        mCard.addView(mCurrentProgramView, programLp);

        // 第三行：进度
        mProgressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setMax(1000);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp2px(4));
        barLp.topMargin = dp2px(8);
        mProgressBar.setProgressDrawable(context.getDrawable(R.drawable.progress_bar_accent));
        mCard.addView(mProgressBar, barLp);

        mTimeRangeView = new TextView(context);
        mTimeRangeView.setTextColor(0xFF999999);
        mTimeRangeView.setTextSize(11);
        LinearLayout.LayoutParams rangeLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rangeLp.topMargin = dp2px(6);
        mCard.addView(mTimeRangeView, rangeLp);

        // 第四行：下一档
        mNextProgramView = new TextView(context);
        mNextProgramView.setTextColor(0xFF999999);
        mNextProgramView.setTextSize(12);
        mNextProgramView.setMaxLines(1);
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        nextLp.topMargin = dp2px(4);
        mCard.addView(mNextProgramView, nextLp);

        setVisibility(GONE);
    }

    /** 卡片自己贴在左上角，四周留出安全边距。 */
    public void setSafeMargins(int left, int top) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mCard.getLayoutParams();
        lp.leftMargin = left;
        lp.topMargin = top;
        mCard.setLayoutParams(lp);
    }

    public void setEpgManager(EpgManager epgManager) {
        mEpgManager = epgManager;
        if (mEpgManager != null) {
            // 卡片常常先出现、节目单后到：没有再刷一次就会一直停在「无节目单」
            mEpgManager.addOnEpgUpdatedListener(this::updateProgramInfo);
        }
    }

    /** 换台 / 开始回看时调用。 */
    public void showChannelInfo(Channel channel) {
        mCurrentChannel = channel;
        if (channel == null) {
            return;
        }
        mChannelNameView.setText(channel.name);
        if (channel.logo != null && !channel.logo.isEmpty()) {
            Glide.with(getContext()).load(channel.logo).centerInside().into(mLogoView);
        } else {
            mLogoView.setImageDrawable(null);
        }
        updateProgramInfo();
        showWithAutoHide();
    }

    /** 频道号（播放列表里的序号），0 表示不显示。 */
    public void setChannelNumber(int number) {
        mChannelNumber = number;
        refreshNumberLabel();
    }

    /** 回看中在节目名前面加标记，避免误以为在看直播。 */
    public void setReplayMode(boolean replay) {
        mReplayMode = replay;
        refreshNumberLabel();
        updateProgramInfo();
    }

    private void refreshNumberLabel() {
        String number = mChannelNumber > 0 ? String.valueOf(mChannelNumber) : "";
        mChannelNumberView.setText(mReplayMode
                ? getContext().getString(R.string.tune_info_replay) : number);
    }

    private void updateProgramInfo() {
        if (mCurrentChannel == null) {
            return;
        }
        EpgManager.Program current = mEpgManager != null
                ? mEpgManager.getCurrentProgram(mCurrentChannel) : null;

        if (current == null) {
            mCurrentProgramView.setText(R.string.no_epg_data);
            mProgressBar.setProgress(0);
            mTimeRangeView.setText("");
            mNextProgramView.setText("");
            return;
        }

        String prefix = mReplayMode
                ? getContext().getString(R.string.tune_info_replay) + " · " : "";
        mCurrentProgramView.setText(prefix + current.title);

        long now = System.currentTimeMillis();
        long duration = Math.max(1, current.endTime - current.startTime);
        long elapsed = Math.max(0, Math.min(duration, now - current.startTime));
        mProgressBar.setProgress((int) (elapsed * 1000 / duration));

        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mTimeRangeView.setText(fmt.format(new Date(current.startTime))
                + " – " + fmt.format(new Date(current.endTime)));

        String next = mEpgManager != null ? mEpgManager.getNextProgramInfo(mCurrentChannel) : null;
        mNextProgramView.setText(next != null
                ? getContext().getString(R.string.next_program, next) : "");
    }

    private void showWithAutoHide() {
        removeCallbacks(mHideRunnable);
        updateProgramInfo();
        setVisibility(VISIBLE);
        if (getAlpha() < 1f) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(180).start();
        }
        postDelayed(mHideRunnable, AUTO_HIDE_MS);
    }

    /** 立刻收起（例如打开了播放控制面板）。 */
    public void hideNow() {
        removeCallbacks(mHideRunnable);
        setVisibility(GONE);
        setAlpha(1f);
    }

    private void fadeOut() {
        if (getVisibility() != VISIBLE) {
            return;
        }
        animate().alpha(0f).setDuration(260).withEndAction(() -> {
            setVisibility(GONE);
            setAlpha(1f);
        }).start();
    }

    @Override
    public void attach(@NonNull ControlWrapper controlWrapper) {
    }

    @Nullable
    @Override
    public View getView() {
        return this;
    }

    @Override
    public void onVisibilityChanged(boolean isVisible, Animation anim) {
        if (isVisible) {
            showWithAutoHide();
        }
    }

    @Override
    public void onPlayStateChanged(int playState) {
    }

    @Override
    public void onPlayerStateChanged(int playerState) {
    }

    @Override
    public void setProgress(int duration, int position) {
        // 进度条跟的是节目进度，不是直播流里的位置
    }

    @Override
    public void onLockStateChanged(boolean isLocked) {
    }

    private int dp2px(int dp) {
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }
}
