package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the sky/celestial draws while a reflection is being rendered. {@link SkyRenderer} draws the sky
 * gradient ({@code renderSkyDisc}), the sunrise/sunset glow ({@code renderSunriseAndSunset}), the sun/moon/
 * stars ({@code renderSunMoonAndStars}) and the dark disc ({@code renderDarkDisc}) into ONE persistent
 * off-screen {@code renderTarget} that the sky dome later samples. Re-running any of them for the reflection's
 * virtual camera clobbers that shared target, which then bleeds into the MAIN view - a duplicate sun/moon
 * ("two moons") or a sunset haze across half the screen. A reflection fogs out long before the sky matters,
 * so we drop these passes during it; the reflection just samples the main view's already-rendered sky. Same
 * call we make for clouds and weather. (renderEndSky/renderEndFlash are End-only and not hit in normal play.)
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
    @Inject(
        method = {"renderSkyDisc", "renderSunriseAndSunset", "renderSunMoonAndStars", "renderDarkDisc"},
        at = @At("HEAD"),
        cancellable = true
    )
    private void mirror$skipSkyInReflection(CallbackInfo ci) {
        if (MirrorRenderer.isRendering()) {
            ci.cancel();
        }
    }
}
