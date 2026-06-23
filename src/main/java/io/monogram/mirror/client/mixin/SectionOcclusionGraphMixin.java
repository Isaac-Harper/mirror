package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorFbo;
import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps the reflection's world re-render from corrupting the MAIN view's chunk bookkeeping - the core 26.2
 * port problem. 26.2 consolidated the once-per-frame section bookkeeping into {@code LevelRenderer.render}
 * (which the reflection must call to draw) and {@code LevelExtractor.extract} (which the reflection re-runs
 * for its virtual camera). Two pieces of that bookkeeping, left running, leave the main view broken:
 *
 * <ul>
 *   <li>{@code update} rebuilds the section-visibility octree around the CURRENT camera. Running it for the
 *       reflection leaves the octree centred on the mirror's viewpoint, so the main view's section set is
 *       wrong the rest of the frame. Cancel it while redirecting. Section SELECTION
 *       ({@code addSectionsInFrustum}) is pure frustum culling over the octree with no occlusion-visible
 *       gate, so freezing the octree on the main camera doesn't starve the reflection - it still draws every
 *       section in its own (forced) frustum.</li>
 *   <li>{@code consumeFrustumUpdate} reads-and-clears the per-frame "frustum changed" flag inside
 *       {@code extract()}. The reflection's extra extracts (the virtual-camera extract plus the end-of-frame
 *       restore extract) would consume the main view's pending signal, leaving the main view a frame stale
 *       (it cuts off as you turn). Suppress it for the whole pass so the real flag survives for next frame's
 *       main extract; the reflection forces its frustum explicitly via {@link LevelExtractorAccessor}.</li>
 * </ul>
 */
@Mixin(SectionOcclusionGraph.class)
public class SectionOcclusionGraphMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void mirror$skipOctreeRebuildInReflection(CameraRenderState cam, int viewDistance,
            ChunkLoadingRenderState chunkLoading, CallbackInfo ci) {
        if (MirrorFbo.redirect) {
            ci.cancel();
        }
    }

    @Inject(method = "consumeFrustumUpdate", at = @At("HEAD"), cancellable = true)
    private void mirror$preserveFrustumSignalDuringPass(CallbackInfoReturnable<Boolean> cir) {
        if (MirrorRenderer.isRendering()) {
            cir.setReturnValue(false); // don't consume the main view's pending frustum-update signal
        }
    }
}
