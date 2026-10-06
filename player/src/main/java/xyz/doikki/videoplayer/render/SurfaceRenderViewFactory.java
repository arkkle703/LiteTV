package xyz.doikki.videoplayer.render;

import android.content.Context;

/** Creates {@link SurfaceRenderView}s; see that class for why this is the default. */
public class SurfaceRenderViewFactory extends RenderViewFactory {

    public static SurfaceRenderViewFactory create() {
        return new SurfaceRenderViewFactory();
    }

    @Override
    public IRenderView createRenderView(Context context) {
        return new SurfaceRenderView(context);
    }
}
