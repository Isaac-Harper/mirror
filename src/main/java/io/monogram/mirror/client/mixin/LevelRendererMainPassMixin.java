package io.monogram.mirror.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.monogram.mirror.client.MirrorFbo;
import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Snapshots the live main scene depth so the reflection composite can occlude against it. 26.2 draws the
 * scene depth into a framegraph resource ({@code targets.main}) that is released by the time our reflection
 * runs (at the end of {@code GameRenderer.renderLevel}, after the framegraph) - so the composite had no real
 * depth to test and the mirror showed through every wall. This injects at the TAIL of the framegraph main
 * pass executor ({@code lambda$addMainPass$0}), where the main pass has finished drawing and {@code targets.main}
 * is still live, and copies its depth into {@link MirrorFbo#sceneDepth}. Skipped while a reflection is itself
 * rendering (that pass re-enters this lambda) so the main scene's depth isn't overwritten by a reflection's,
 * and skipped entirely while no mirrors are near - the copy is a full-resolution blit every frame, and worlds
 * without mirrors shouldn't pay for it.
 *
 * <p>{@code require = 0}: the synthetic lambda name is not a mapping contract and can shift on any 26.2.x
 * patch. If the injection misses, reflections degrade to compositing without scene-depth occlusion
 * (MirrorRenderer logs it once per world) instead of the whole game failing to start.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMainPassMixin {
    @Shadow @Final private LevelTargetBundle targets;

    @Inject(method = "lambda$addMainPass$0", at = @At("TAIL"), require = 0)
    private void mirror$captureSceneDepth(CallbackInfo ci) {
        if (MirrorRenderer.isRendering() || !MirrorRenderer.hasMirrors() || targets == null || targets.main == null) {
            return;
        }
        RenderTarget main;
        try {
            main = targets.main.get();
        } catch (Exception e) {
            return; // handle not backed by a resource at this moment - nothing to copy
        }
        if (main != null) {
            MirrorFbo.captureSceneDepth(main);
        }
    }
}
