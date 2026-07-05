package io.monogram.mirror.client;

import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * Offscreen render targets the reflection is drawn into. While {@link #redirect} is true,
 * {@code Minecraft.getMainRenderTarget()} returns {@link #target} (see MinecraftMixin), so
 * LevelRenderer.renderLevel draws the reflected world there instead of the main framebuffer.
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
     * A copy of the main scene's depth, captured mid-frame while it's still valid (the deferred
     * pipeline discards the main render target's depth once the world framegraph resolves). The
     * top-level composite runs at the END of the frame - after the third-person player is drawn -
     * and depth-tests against this copy so the reflection stays occluded behind blocks and entities.
     */
    public static TextureTarget sceneDepth;

    /**
     * A copy of each reflection level's depth, captured mid-render (an FBO's own depth buffer is discarded
     * once its render's framegraph resolves, same as the main target). The composite of level d+1 onto
     * level d depth-tests against d's copy so a mirror-in-a-mirror reflection is occluded by geometry in
     * front of it. Index = depth - 1, like {@link #levels}.
     */
    private static TextureTarget[] reflectionDepths = new TextureTarget[0];

    /** One reflection buffer per recursion level (index = depth - 1), grown on demand and reused per frame. */
    private static TextureTarget[] levels = new TextureTarget[0];

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
        levels = trim(levels, maxLevels);
        reflectionDepths = trim(reflectionDepths, maxLevels);
    }

    private static TextureTarget[] trim(TextureTarget[] arr, int maxLevels) {
        if (arr.length <= maxLevels) {
            return arr;
        }
        for (int i = maxLevels; i < arr.length; i++) {
            if (arr[i] != null) {
                arr[i].destroyBuffers();
            }
        }
        return java.util.Arrays.copyOf(arr, maxLevels);
    }

    public static TextureTarget getOrCreateSceneDepth(int width, int height) {
        sceneDepth = ensure(sceneDepth, "mirror_scene_depth", width, height);
        return sceneDepth;
    }

    /** The captured-depth buffer for the given recursion depth (1-based), sized to the screen. */
    public static TextureTarget getOrCreateReflectionDepth(int depth, int width, int height) {
        int idx = Math.max(1, depth) - 1;
        if (idx >= reflectionDepths.length) {
            reflectionDepths = java.util.Arrays.copyOf(reflectionDepths, idx + 1);
        }
        reflectionDepths[idx] = ensure(reflectionDepths[idx], "mirror_reflection_depth_l" + (idx + 1), width, height);
        return reflectionDepths[idx];
    }

    /** The captured depth of the given level's last render, or null if never captured. */
    public static TextureTarget reflectionDepth(int depth) {
        int idx = Math.max(1, depth) - 1;
        return idx < reflectionDepths.length ? reflectionDepths[idx] : null;
    }

    private static TextureTarget ensure(TextureTarget t, String name, int width, int height) {
        if (t == null) {
            return new TextureTarget(name, width, height, true); // true = with depth
        }
        if (t.width != width || t.height != height) {
            t.resize(width, height);
        }
        return t;
    }
}
