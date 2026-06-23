package io.monogram.mirror.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * Offscreen render targets the reflection is drawn into. While {@link #redirect} is true,
 * {@code GameRenderer.mainRenderTarget()} returns {@link #target} (see GameRendererTargetMixin), so
 * {@code LevelRenderer.render} draws the reflected world there instead of the main framebuffer.
 *
 * <p>Recursive (mirror-in-mirror) reflections need one buffer per depth level: level 1 holds a
 * mirror's reflected world; level 2 holds the reflection seen *inside* level 1 and is composited
 * onto level 1 before level 1 is composited onto the screen. {@link #target} is repointed at the
 * level currently being rendered.
 */
public final class MirrorFbo {
    private MirrorFbo() {}

    /** The buffer the world render is currently being redirected into (level 1 or level 2). */
    public static TextureTarget target;
    public static boolean redirect = false;

    /**
     * A copy of the main scene's depth, taken at the TAIL of the framegraph main pass (LevelRendererMainPassMixin)
     * while the depth is still live - by the time renderAll's composite runs (after the framegraph) the main
     * target's depth is gone, so the top-level composite depth-tests against this copy to stay occluded behind
     * blocks and walls.
     */
    public static TextureTarget sceneDepth;

    private static TextureTarget level1;
    private static TextureTarget level2;

    public static TextureTarget getOrCreateSceneDepth(int width, int height) {
        sceneDepth = ensure(sceneDepth, "mirror_scene_depth", width, height);
        return sceneDepth;
    }

    /** The reflection buffer for the given recursion depth (1 or 2), sized to the screen. */
    public static TextureTarget level(int depth, int width, int height) {
        if (depth <= 1) {
            level1 = ensure(level1, "mirror_reflection_l1", width, height);
            return level1;
        }
        level2 = ensure(level2, "mirror_reflection_l2", width, height);
        return level2;
    }

    private static TextureTarget ensure(TextureTarget t, String name, int width, int height) {
        if (t == null) {
            // 26.2 requires the colour format on the ctor; RGBA8_UNORM matches the main render target.
            return new TextureTarget(name, width, height, true, GpuFormat.RGBA8_UNORM); // true = with depth
        }
        if (t.width != width || t.height != height) {
            t.resize(width, height);
        }
        return t;
    }
}
