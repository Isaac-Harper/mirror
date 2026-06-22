package io.monogram.mirror.client;

import com.mojang.blaze3d.pipeline.TextureTarget;

/**
 * Offscreen render targets the reflection is drawn into. While {@link #redirect} is true,
 * {@code Minecraft.getMainRenderTarget()} returns {@link #target} (see MinecraftMixin), so
 * LevelRenderer.renderLevel draws the reflected world there instead of the main framebuffer.
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
     * A copy of the main scene's depth, captured mid-frame while it's still valid (the deferred
     * pipeline discards the main render target's depth once the world framegraph resolves). The
     * top-level composite runs at the END of the frame - after the third-person player is drawn -
     * and depth-tests against this copy so the reflection stays occluded behind blocks and entities.
     */
    public static TextureTarget sceneDepth;

    /**
     * A copy of the depth-1 reflection FBO's depth, captured mid-render (its own depth buffer is discarded
     * once that render's framegraph resolves, same as the main target). The nested (depth-2) composite
     * depth-tests against this so a mirror-in-a-mirror reflection is occluded by geometry in front of it.
     */
    public static TextureTarget reflectionDepth;

    private static TextureTarget level1;
    private static TextureTarget level2;

    /** The reflection buffer for the given recursion depth (1 or 2), sized to the screen. */
    public static TextureTarget level(int depth, int width, int height) {
        if (depth <= 1) {
            level1 = ensure(level1, "mirror_reflection_l1", width, height);
            return level1;
        }
        level2 = ensure(level2, "mirror_reflection_l2", width, height);
        return level2;
    }

    public static TextureTarget getOrCreateSceneDepth(int width, int height) {
        sceneDepth = ensure(sceneDepth, "mirror_scene_depth", width, height);
        return sceneDepth;
    }

    public static TextureTarget getOrCreateReflectionDepth(int width, int height) {
        reflectionDepth = ensure(reflectionDepth, "mirror_reflection_depth", width, height);
        return reflectionDepth;
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
