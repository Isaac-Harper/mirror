package io.monogram.mirror.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.monogram.mirror.client.MirrorFbo;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a reflection is rendering, point getMainRenderTarget() at our offscreen FBO so
 * LevelRenderer.renderLevel draws the reflected world there instead of the screen.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void mirror$redirectToReflectionFbo(CallbackInfoReturnable<RenderTarget> cir) {
        if (MirrorFbo.redirect && MirrorFbo.target != null) {
            cir.setReturnValue(MirrorFbo.target);
        }
    }
}
