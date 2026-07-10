package io.monogram.mirror.client;

import io.monogram.mirror.MirrorBlockEntity;
import io.monogram.mirror.MirrorMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

        // Mirror discovery is a registry kept in sync by these events (O(mirrors), no chunk scanning):
        // every loaded mirror block entity registers its position, and unregisters on break/chunk unload.
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, world) -> {
            if (be instanceof MirrorBlockEntity) {
                MirrorRenderer.onMirrorLoaded(be.getBlockPos());
            }
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, world) -> {
            if (be instanceof MirrorBlockEntity) {
                MirrorRenderer.onMirrorUnloaded(be.getBlockPos());
            }
        });

        // Leaving a world: drop the world-tied caches and free the screen-sized reflection targets,
        // otherwise the last ClientLevel and a stack of full-resolution buffers survive into the menus.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
            client.execute(MirrorRenderer::onClientDisconnect));
    }
}
