package io.monogram.mirror.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * Offscreen render targets the reflection is drawn into. While {@link #redirect} is true,
 * {@code GameRenderer.mainRenderTarget()} returns {@link #target} (see GameRendererTargetMixin), so
 * {@code LevelRenderer.render} draws the reflected world there instead of the main framebuffer.
 *
 * <p>Recursive (mirror-in-mirror) reflections need one buffer per depth level: level 1 holds a
 * mirror's reflected world; each deeper level holds the reflection seen *inside* the level above it
 * and is composited onto that level before it is composited upward. {@link #target} is repointed at
 * the level currently being rendered.
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

    /** One reflection buffer per recursion level (index = depth - 1), grown on demand and reused per frame. */
    private static TextureTarget[] levels = new TextureTarget[0];

    public static TextureTarget getOrCreateSceneDepth(int width, int height) {
        sceneDepth = ensure(sceneDepth, "mirror_scene_depth", width, height);
        return sceneDepth;
    }

    /** The reflection buffer for the given recursion depth (1-based), sized to the screen. Each level keeps
     *  its own persistent target because depth d is composited onto depth d-1 before d-1 is done with. */
    public static TextureTarget level(int depth, int width, int height) {
        int idx = Math.max(1, depth) - 1;
        if (idx >= levels.length) {
            levels = java.util.Arrays.copyOf(levels, idx + 1);
        }
        levels[idx] = ensure(levels[idx], "mirror_reflection_l" + (idx + 1), width, height);
        return levels[idx];
    }

    /** Free the buffers of levels beyond {@code maxLevels}, so lowering the recursion depth in the config
     *  releases the VRAM those deeper levels held instead of keeping it until the game exits. */
    public static void trim(int maxLevels) {
        if (levels.length <= maxLevels) {
            return;
        }
        for (int i = maxLevels; i < levels.length; i++) {
            if (levels[i] != null) {
                levels[i].destroyBuffers();
            }
        }
        levels = java.util.Arrays.copyOf(levels, maxLevels);
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
