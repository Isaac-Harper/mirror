package io.monogram.mirror.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.monogram.mirror.client.MirrorFbo;
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
    // Seed the main target's depth with the captured world depth RIGHT BEFORE the first-person hand/item is
    // drawn (renderItemInHand draws into the main target after the framegraph, where the world depth is gone).
    // The hand then depth-writes on top, so the main target ends up holding world + hand depth - which the
    // composite tests against, so the reflection is hidden both behind walls AND behind the held item. Only
    // when mirrors are near (otherwise leave vanilla hand rendering untouched).
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;FLorg/joml/Matrix4fc;)V",
            shift = At.Shift.BEFORE
        )
    )
    private void mirror$seedHandDepth(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!MirrorRenderer.hasMirrors() || MirrorFbo.sceneDepth == null) {
            return;
        }
        RenderTarget main = ((GameRenderer) (Object) this).mainRenderTarget();
        if (main != null) {
            MirrorFbo.seedDepthInto(main); // no-op on a size mismatch (one-frame-stale snapshot after a resize)
        }
    }

    // Right after the final renderAllFeatures(): the third-person player and held item are now drawn,
    // so our reflection's world re-render no longer steals them - AND the scene depth buffer is still
    // intact (the later 3D-crosshair pass clears it, which is why TAIL lost occlusion).
    @Inject(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;renderAllFeatures(Lnet/minecraft/client/renderer/SubmitNodeStorage;)V",
            shift = At.Shift.AFTER
        )
    )
    private void mirror$renderReflections(DeltaTracker deltaTracker, CallbackInfo ci) {
        MirrorRenderer.renderAll();
    }
}
