package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives the mirror reflection pass at the very end of the world render - after terrain, entities,
 * the third-person player, and the held item are all drawn. Rendering a reflection re-runs the
 * deferred pipeline and consumes its pending feature draws; doing that mid-frame (e.g. via the
 * AFTER_TRANSLUCENT_FEATURES event) erased the third-person self from the main view. At TAIL the
 * main view is complete, so we only composite the mirror surfaces on top of it.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    // Right after the final renderAllFeatures(): the third-person player and held item are now drawn,
    // so our reflection's world re-render no longer steals them - AND the scene depth buffer is still
    // intact (the later 3D-crosshair pass clears it, which is why TAIL lost occlusion).
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures()V",
            shift = At.Shift.AFTER
        )
    )
    private void mirror$renderReflections(DeltaTracker deltaTracker, CallbackInfo ci) {
        MirrorRenderer.renderAll();
    }
}
