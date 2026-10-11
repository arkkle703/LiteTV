package xyz.doikki.videoplayer.render;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * Renders video through a {@link SurfaceView} rather than a TextureView.
 *
 * <p>This is the default because the two paths are not equivalent on TV hardware:
 *
 * <ul>
 *   <li>A SurfaceView owns a real video layer that the display hardware composites. A TextureView
 *       is sampled through the GPU into the app's window instead, which softens or aliases the
 *       picture and adds a frame of latency.</li>
 *   <li>Tuner/IPTV feeds are frequently 1080i. SoC video pipelines deinterlace the fields when the
 *       frames go to a video layer; frames routed through a SurfaceTexture reach the GPU still
 *       interlaced, which shows up as the combing ("锯齿") users reported against other players
 *       (issue #1). Interlaced detection needs the layer, so this is not something the app can
 *       fix by scaling differently.</li>
 *   <li>Tunneling and {@code Surface#setFrameRate} only apply to a SurfaceView-provided surface,
 *       so the sharper/smoother output modes are unreachable through a TextureView.</li>
 * </ul>
 *
 * <p>{@link TextureRenderView} remains selectable as a fallback for devices whose SurfaceView
 * misbehaves.
 */
@SuppressLint("ViewConstructor")
public class SurfaceRenderView extends SurfaceView implements IRenderView, SurfaceHolder.Callback {

    private static final String TAG = "SurfaceRenderView";

    private final MeasureHelper mMeasureHelper = new MeasureHelper();

    @Nullable
    private AbstractPlayer mMediaPlayer;

    public SurfaceRenderView(Context context) {
        super(context);
        getHolder().addCallback(this);
    }

    /**
     * Swallows a framework crash that Android 6 throws whenever this view's window becomes visible
     * again after another activity covered it.
     *
     * <p>{@code SurfaceView.updateWindow()} dereferences its {@code IWindowSession} without a null
     * check on those releases; the session is already gone once the window has been hidden, so the
     * traversal that makes the player visible again dies with
     * {@code NullPointerException: ... IWindowSession.finishDrawing ... on a null object reference},
     * taking the whole process with it. Seen on a MiTV 4 (Android 6): leaving the settings screen
     * crashed the app back to the launcher, and no code of ours was on the stack.
     *
     * <p>Catching it is the only place the app can intervene — the throw happens inside the
     * framework's own traversal. The surface is re-created on the next layout pass, which is why
     * the picture comes back rather than staying blank.
     */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        try {
            super.onWindowVisibilityChanged(visibility);
        } catch (NullPointerException e) {
            Log.w(TAG, "Ignoring the platform SurfaceView bug while becoming visible", e);
        }
    }

    @Override
    public void attachToPlayer(@NonNull AbstractPlayer player) {
        mMediaPlayer = player;
        // The surface usually does not exist yet at this point; surfaceCreated() hands it over as
        // soon as the view is attached to a window.
        SurfaceHolder holder = getHolder();
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            player.setDisplay(holder);
        }
    }

    @Override
    public void setVideoSize(int videoWidth, int videoHeight) {
        if (videoWidth > 0 && videoHeight > 0) {
            mMeasureHelper.setVideoSize(videoWidth, videoHeight);
            requestLayout();
        }
    }

    @Override
    public void setVideoRotation(int degree) {
        mMeasureHelper.setVideoRotation(degree);
        // Best effort: a SurfaceView's layer honours view transforms from API 24 on. Landscape
        // TV/IPTV content needs no rotation, so the older gap is not worth a workaround.
        setRotation(degree);
    }

    @Override
    public void setScaleType(int scaleType) {
        mMeasureHelper.setScreenScale(scaleType);
        requestLayout();
    }

    @Override
    public View getView() {
        return this;
    }

    /** A SurfaceView's frames live in a separate layer and cannot be captured into a bitmap. */
    @Override
    public Bitmap doScreenShot() {
        return null;
    }

    @Override
    public void release() {
        getHolder().removeCallback(this);
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(null);
            mMediaPlayer = null;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int[] measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec);
        setMeasuredDimension(measuredSize[0], measuredSize[1]);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(holder);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Drop the dead surface so the decoder does not keep writing into it.
        if (mMediaPlayer != null) {
            mMediaPlayer.setDisplay(null);
        }
    }
}
