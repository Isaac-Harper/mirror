package io.monogram.mirror.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Minimal, reflection-based bridge to Sodium, so the mod keeps zero hard dependency on it (compiles and
 * runs identically whether Sodium is installed or not).
 *
 * <p>Sodium replaces vanilla terrain rendering and draws chunks from its OWN captured projection matrix
 * (the one it grabs from {@code GameRenderer} at the start of the world render), not from the projection
 * the mod sets on {@code RenderSystem}/the camera for a reflection. So during the reflection pass Sodium
 * would draw terrain with the MAIN view's projection, and the mirror shows the scene behind it instead of
 * a reflection. That captured matrix is exposed as a live {@code Matrix4f} via
 * {@code GameRenderer#sodium$getProjectionMatrix()}; {@link #terrainProjection} returns it so the renderer
 * can point it at the reflection's oblique projection for the pass and restore it after.
 */
final class SodiumCompat {
    private SodiumCompat() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mirror");
    private static final boolean PRESENT = FabricLoader.getInstance().isModLoaded("sodium");

    private static boolean probed;
    private static Method getProjectionMatrix; // GameRenderer#sodium$getProjectionMatrix() -> Matrix4fc

    /**
     * Sodium's live terrain projection matrix, or {@code null} if Sodium isn't installed (or its API
     * changed). The returned object is the very field Sodium reads when building chunk render matrices, so
     * mutating it in place redirects Sodium's terrain pass.
     */
    static Matrix4f terrainProjection(Minecraft mc) {
        if (!PRESENT || mc == null || mc.gameRenderer == null) {
            return null;
        }
        if (!probed) {
            probed = true;
            try {
                getProjectionMatrix = mc.gameRenderer.getClass().getMethod("sodium$getProjectionMatrix");
            } catch (Throwable t) {
                LOGGER.warn("[mirror] Sodium is present but its projection accessor was not found; "
                    + "reflections may show through under Sodium", t);
                getProjectionMatrix = null;
            }
        }
        if (getProjectionMatrix == null) {
            return null;
        }
        try {
            Object m = getProjectionMatrix.invoke(mc.gameRenderer);
            return (m instanceof Matrix4f mat) ? mat : null; // Matrix4fc in signature, Matrix4f in fact
        } catch (Throwable t) {
            return null;
        }
    }
}
