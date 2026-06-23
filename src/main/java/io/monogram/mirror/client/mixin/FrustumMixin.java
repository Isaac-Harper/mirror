package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the reflection from hard-freezing the client. 26.2's section selection runs the visible-section
 * frustum through {@link Frustum#offsetToFullyIncludeCameraCube} (via {@code SectionOcclusionGraph.offsetFrustum}),
 * which loops - stepping the frustum's camera backward along its view vector - until the camera's block cube
 * tests as fully inside the frustum. For the reflection's virtual camera that condition is never met, so the
 * loop spins forever and the render thread hangs the instant a mirror comes on screen (confirmed by thread dump).
 * The offset is only a culling refinement (it keeps the section the camera sits in from being culled), so we
 * skip it during the reflection and return the frustum unchanged - the section set is then chosen from the
 * virtual frustum as-is, no freeze.
 */
@Mixin(Frustum.class)
public class FrustumMixin {
    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"), cancellable = true)
    private void mirror$skipOffsetInReflection(int blocks, CallbackInfoReturnable<Frustum> cir) {
        if (MirrorRenderer.isRendering()) {
            cir.setReturnValue((Frustum) (Object) this);
        }
    }
}
