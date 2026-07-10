package io.monogram.mirror.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link SkyRenderer} draws straight into a {@code RenderTarget} reference it captured at construction
 * (the real main target), NOT into {@code GameRenderer.mainRenderTarget()} - so the mirror's FBO redirect
 * never applies to it. To draw a sky inside a reflection, MirrorRenderer swaps this field to the pass's
 * FBO for the duration of the redirected level render and restores it after.
 */
@Mixin(SkyRenderer.class)
public interface SkyRendererAccessor {
    @Accessor("renderTarget")
    RenderTarget mirror$renderTarget();

    @Mutable
    @Accessor("renderTarget")
    void mirror$setRenderTarget(RenderTarget target);
}
