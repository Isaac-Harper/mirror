package io.monogram.mirror.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.monogram.mirror.MirrorBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the mirror's frame + glass per-frame (resolving its block model) instead of letting it bake into
 * the terrain chunk mesh, and SKIPS drawing while a reflection is rendered ({@link MirrorRenderer#isRendering()}).
 * Because the mirror is never part of the world geometry the reflection re-renders, it can't appear in its
 * own reflection - the correct, precision-independent fix (no oblique-clip games on a coplanar surface).
 */
public class MirrorBlockEntityRenderer
        implements BlockEntityRenderer<MirrorBlockEntity, MirrorBlockEntityRenderer.State> {

    private final BlockModelResolver modelResolver;
    private final BlockDisplayContext displayContext = BlockDisplayContext.create();

    public MirrorBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
        this.modelResolver = ctx.blockModelResolver();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(MirrorBlockEntity be, State state, float partial, Vec3 cameraPos,
            ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderState.extractBase(be, state, crumbling);
        state.model.clear();
        if (MirrorRenderer.isRendering()) {
            return; // never build the mirror's geometry while rendering a reflection
        }
        modelResolver.update(state.model, be.getBlockState(), displayContext);
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (MirrorRenderer.isRendering() || state.model.isEmpty()) {
            return;
        }
        state.model.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
    }

    /**
     * Draw the mirror's frame + glass out to the render distance (default block-entity view distance is only
     * 64 blocks). The reflection finder now picks up mirrors as far as chunks are loaded, so the frame/glass
     * must render that far too - otherwise distant mirrors would show a reflection with no frame around it.
     */
    @Override
    public int getViewDistance() {
        return net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    public static class State extends BlockEntityRenderState {
        final BlockModelRenderState model = new BlockModelRenderState();
    }
}
