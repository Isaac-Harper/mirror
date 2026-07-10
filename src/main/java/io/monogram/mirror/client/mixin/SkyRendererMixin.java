package io.monogram.mirror.client.mixin;

import io.monogram.mirror.client.MirrorRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guards the sky draws during reflection passes. {@link SkyRenderer} draws the sky gradient
 * ({@code renderSkyDisc}), the sunrise/sunset glow, the sun/moon/stars, the dark disc, and the End sky
 * into the {@code RenderTarget} it captured at construction - the REAL main target, which the mirror's
 * FBO redirect does not cover. Re-running them for a reflection's virtual camera therefore paints the
 * virtual sky onto the actual screen - a duplicate sun/moon ("two moons") or a sunset haze across half
 * the frame.
 *
 * <p>While MirrorRenderer has swapped the sky target onto the reflection FBO (via
 * {@link SkyRendererAccessor}), the draws are ALLOWED: they land in the mirror's own buffer and give the
 * reflection a real sky (sky colour, sun, moon, stars - previously the reflected sky was just the fog
 * colour, visibly wrong at dawn and dusk). The view rotation is already correct because
 * {@code LevelRenderer.render} multiplies the virtual camera's rotation onto the model-view stack. If a
 * reflection somehow triggers these without the swap in place, they are cancelled as before.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
    @Inject(
        method = {"renderSkyDisc", "renderSunriseAndSunset", "renderSunMoonAndStars", "renderDarkDisc",
                  "renderEndSky", "renderEndFlash"},
        at = @At("HEAD"),
        cancellable = true
    )
    private void mirror$guardSkyInReflection(CallbackInfo ci) {
        if (MirrorRenderer.isRendering() && !MirrorRenderer.isSkyRedirected()) {
            ci.cancel();
        }
    }
}
