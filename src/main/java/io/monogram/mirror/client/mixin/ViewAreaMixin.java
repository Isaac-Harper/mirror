package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorFbo;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops the reflection from shifting the render-section grid onto the mirror's viewpoint. 26.2's
 * {@code LevelRenderer.repositionCamera} (called by {@code render()}, which the reflection invokes) does two
 * things: it shifts {@link ViewArea}'s section grid to the camera's section AND it sets the section
 * dispatcher's camera position. We need the latter for the reflection's draw (sorting/translucency), but the
 * former, run for the virtual camera, leaves the main view's grid shifted - and {@code ViewArea.repositionCamera}
 * also {@code invalidate()}s the occlusion graph, compounding the corruption. So we cancel just the inner
 * grid shift while redirecting and let the outer repositionCamera (and setCameraPosition) run.
 */
@Mixin(ViewArea.class)
public class ViewAreaMixin {
    @Inject(method = "repositionCamera", at = @At("HEAD"), cancellable = true)
    private void mirror$skipGridShiftInReflection(SectionPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (MirrorFbo.redirect) {
            cir.setReturnValue(false); // section grid did not move
        }
    }
}
