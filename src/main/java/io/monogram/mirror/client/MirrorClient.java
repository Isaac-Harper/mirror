package io.monogram.mirror.client;

import io.monogram.mirror.MirrorMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/**
 * Client entry point. The reflection pass is split across two points of the world render:
 * <ul>
 *   <li>AFTER_TRANSLUCENT_FEATURES (here): {@link MirrorRenderer#captureSceneDepth} snapshots the
 *       scene depth while the framegraph still has it.</li>
 *   <li>End of {@code GameRenderer.renderLevel} ({@code GameRendererMixin}): {@link MirrorRenderer#renderAll}
 *       renders + composites the reflections, after the third-person player and held item are drawn,
 *       depth-testing against the captured depth.</li>
 * </ul>
 */
public class MirrorClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(MirrorRenderer::captureSceneDepth);
        // The mirror's frame + glass are drawn here (per-frame), not baked into the chunk mesh, and the
        // renderer skips the reflection pass - so the mirror never appears in its own reflection.
        BlockEntityRendererRegistry.register(MirrorMod.MIRROR_BLOCK_ENTITY, MirrorBlockEntityRenderer::new);
    }
}
