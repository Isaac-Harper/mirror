package io.monogram.mirror.client;

import io.monogram.mirror.MirrorMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

/**
 * Client entry point. {@link MirrorRenderer#renderAll} renders + composites the reflections at the end of
 * {@code GameRenderer.renderLevel} ({@code GameRendererMixin}) - after the third-person player and held item
 * are drawn, depth-testing the composite against the main render target's own (still-live) depth.
 */
public class MirrorClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // The mirror's frame + glass are drawn here (per-frame), not baked into the chunk mesh, and the
        // renderer skips the reflection pass - so the mirror never appears in its own reflection.
        BlockEntityRendererRegistry.register(MirrorMod.MIRROR_BLOCK_ENTITY, MirrorBlockEntityRenderer::new);
    }
}
