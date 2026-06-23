package io.monogram.mirror.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.monogram.mirror.client.MirrorFbo;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a reflection is rendering, point the main render target at our offscreen FBO so the world
 * re-render (LevelRenderer.render) draws the reflected world there instead of the screen. In 26.2 the
 * main render target moved from Minecraft to GameRenderer, and render() imports it into the framegraph
 * as the "main" target via GameRenderer.mainRenderTarget() - so redirecting that one method makes the
 * ENTIRE main pass (terrain, entities, sky) draw into our FBO.
 */
@Mixin(GameRenderer.class)
public class GameRendererTargetMixin {
    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void mirror$redirectToReflectionFbo(CallbackInfoReturnable<RenderTarget> cir) {
        if (MirrorFbo.redirect && MirrorFbo.target != null) {
            cir.setReturnValue(MirrorFbo.target);
        }
    }
}
