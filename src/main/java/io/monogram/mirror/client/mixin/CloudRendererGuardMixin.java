package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guards the cloud pass during reflection passes. {@code CloudRenderer} carries cross-frame state -
 * {@code prevRelativeCameraPos}/{@code prevCell*} plus its {@code ubo}/{@code utb} ring buffers - so
 * re-running it for the VIRTUAL camera overwrites the "previous camera" memo and rotates the rings an
 * extra time, making the MAIN view's clouds jitter the next frame.
 *
 * <p>While MirrorRenderer has swapped in its dedicated reflection {@code CloudRenderer} (own memos, own
 * rings, shared texture - see {@link LevelRendererAccessor}), the pass is ALLOWED so the reflection gets
 * real clouds; without clouds a mirror showing clear sky reads as a hole in the wall. Any reflection
 * pass without the swap in place (only the frame's first pass renders clouds, bounding the cost) is
 * cancelled as before.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererGuardMixin {
    @Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
        at = @At("HEAD"), cancellable = true)
    private void mirror$guardPrepareInReflection(CallbackInfo ci) {
        cancelInReflection(ci);
    }

    @Inject(method = "render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V",
        at = @At("HEAD"), cancellable = true)
    private void mirror$guardRenderInReflection(CallbackInfo ci) {
        cancelInReflection(ci);
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    private void mirror$guardRenderOitInReflection(CallbackInfo ci) {
        cancelInReflection(ci);
    }

    private static void cancelInReflection(CallbackInfo ci) {
        if (MirrorRenderer.isRendering() && !MirrorRenderer.isCloudsRedirected()) {
            ci.cancel();
        }
    }
}
