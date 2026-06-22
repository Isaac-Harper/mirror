package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the cloud pass while a reflection is being rendered. {@code CloudRenderer} carries cross-frame
 * state - {@code prevRelativeCameraPos}/{@code prevCell*} plus its {@code ubo}/{@code utb} ring buffers.
 * The reflection re-runs {@code renderLevel} (and thus the cloud pass) from the VIRTUAL camera, which
 * overwrites that "previous camera" memo with the reflection camera's position and rotates the ring an
 * extra time - so the MAIN view's clouds jitter/shake the next frame. Clouds add nothing to a reflection
 * that fogs out at {@code REFLECTION_FOG_END} blocks, so we drop the pass entirely during the reflection
 * (mirrors the existing "no weather in reflections" decision).
 */
@Mixin(LevelRenderer.class)
public class LevelRendererCloudMixin {
    @Inject(method = "addCloudsPass", at = @At("HEAD"), cancellable = true)
    private void mirror$skipCloudsInReflection(CallbackInfo ci) {
        if (MirrorRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
