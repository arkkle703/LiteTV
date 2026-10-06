package xyz.doikki.videoplayer.player;


import androidx.annotation.Nullable;

import xyz.doikki.videoplayer.render.RenderViewFactory;
import xyz.doikki.videoplayer.render.SurfaceRenderViewFactory;

/**
 * 播放器全局配置
 */
public class VideoViewConfig {

    /** Let the platform pick the decoder, preferring hardware (see the ExoPlayer policy). */
    public static final int DECODE_AUTO = 0;
    /** Insist on a hardware decoder; a stream it cannot handle fails instead of turning soft. */
    public static final int DECODE_HARDWARE = 1;
    /**
     * Prefer a software decoder. Slow for HD/4K, but it is the way out when a device's hardware
     * decoder is broken for a particular stream (many channels then "cannot play" at all).
     */
    public static final int DECODE_SOFTWARE = 2;

    public static Builder newBuilder() {
        return new Builder();
    }

    public final boolean mPlayOnMobileNetwork;

    public final boolean mEnableOrientation;

    public final boolean mEnableAudioFocus;

    public final boolean mIsEnableLog;

    public final ProgressManager mProgressManager;

    public final PlayerFactory mPlayerFactory;

    public final int mScreenScaleType;

    public final RenderViewFactory mRenderViewFactory;

    /** One of {@link #DECODE_AUTO}, {@link #DECODE_HARDWARE}, {@link #DECODE_SOFTWARE}. */
    public final int mVideoDecodeMode;

    /**
     * Routes decoded frames straight to the display ("tunneling") when the device supports it.
     * Off by default: tunneling has many device-specific quirks and must be validated per box.
     */
    public final boolean mTunnelingEnabled;

    public final boolean mAdaptCutout;

    private VideoViewConfig(Builder builder) {
        mIsEnableLog = builder.mIsEnableLog;
        mEnableOrientation = builder.mEnableOrientation;
        mPlayOnMobileNetwork = builder.mPlayOnMobileNetwork;
        mEnableAudioFocus = builder.mEnableAudioFocus;
        mProgressManager = builder.mProgressManager;
        mScreenScaleType = builder.mScreenScaleType;
        if (builder.mPlayerFactory == null) {
            //默认为AndroidMediaPlayer
            mPlayerFactory = AndroidMediaPlayerFactory.create();
        } else {
            mPlayerFactory = builder.mPlayerFactory;
        }
        if (builder.mRenderViewFactory == null) {
            // Default to a SurfaceView: the video layer keeps the picture sharp and lets the SoC
            // deinterlace 1080i feeds, unlike the GPU-composited TextureView fallback.
            mRenderViewFactory = SurfaceRenderViewFactory.create();
        } else {
            mRenderViewFactory = builder.mRenderViewFactory;
        }
        mVideoDecodeMode = builder.mVideoDecodeMode;
        mTunnelingEnabled = builder.mTunnelingEnabled;
        mAdaptCutout = builder.mAdaptCutout;
    }


    public final static class Builder {

        private boolean mIsEnableLog;
        private boolean mPlayOnMobileNetwork = true;
        private boolean mEnableOrientation;
        private boolean mEnableAudioFocus = true;
        private ProgressManager mProgressManager;
        private PlayerFactory mPlayerFactory;
        private int mScreenScaleType;
        private RenderViewFactory mRenderViewFactory;
        private int mVideoDecodeMode = DECODE_AUTO;
        private boolean mTunnelingEnabled = false;
        private boolean mAdaptCutout = true;

        /**
         * 是否监听设备方向来切换全屏/半屏， 默认不开启
         */
        public Builder setEnableOrientation(boolean enableOrientation) {
            mEnableOrientation = enableOrientation;
            return this;
        }

        /**
         * 在移动环境下调用start()后是否继续播放，默认不继续播放
         */
        public Builder setPlayOnMobileNetwork(boolean playOnMobileNetwork) {
            mPlayOnMobileNetwork = playOnMobileNetwork;
            return this;
        }

        /**
         * 是否开启AudioFocus监听， 默认开启
         */
        public Builder setEnableAudioFocus(boolean enableAudioFocus) {
            mEnableAudioFocus = enableAudioFocus;
            return this;
        }

        /**
         * 设置进度管理器，用于保存播放进度
         */
        public Builder setProgressManager(@Nullable ProgressManager progressManager) {
            mProgressManager = progressManager;
            return this;
        }

        /**
         * 是否打印日志
         */
        public Builder setLogEnabled(boolean enableLog) {
            mIsEnableLog = enableLog;
            return this;
        }

        /**
         * 自定义播放核心
         */
        public Builder setPlayerFactory(PlayerFactory playerFactory) {
            mPlayerFactory = playerFactory;
            return this;
        }

        /**
         * 设置视频比例
         */
        public Builder setScreenScaleType(int screenScaleType) {
            mScreenScaleType = screenScaleType;
            return this;
        }

        /**
         * 自定义RenderView
         */
        public Builder setRenderViewFactory(RenderViewFactory renderViewFactory) {
            mRenderViewFactory = renderViewFactory;
            return this;
        }

        /**
         * How the video decoder is chosen; see {@link #DECODE_AUTO} and friends.
         */
        public Builder setVideoDecodeMode(int videoDecodeMode) {
            mVideoDecodeMode = videoDecodeMode;
            return this;
        }

        /**
         * Whether to let the decoder send frames straight to the display when supported.
         */
        public Builder setTunnelingEnabled(boolean tunnelingEnabled) {
            mTunnelingEnabled = tunnelingEnabled;
            return this;
        }

        /**
         * 是否适配刘海屏，默认适配
         */
        public Builder setAdaptCutout(boolean adaptCutout) {
            mAdaptCutout = adaptCutout;
            return this;
        }

        public VideoViewConfig build() {
            return new VideoViewConfig(this);
        }
    }
}
