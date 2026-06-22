package io.monogram.mirror.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link LevelRenderer#applyFrustum}, which rebuilds the visible-section list for a given
 * frustum. The reflection re-applies it for the VIRTUAL camera's frustum so it renders the sections
 * IT sees, instead of reusing the main camera's set (which makes distant sections pop in/out as you
 * move and turn).
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Invoker("applyFrustum")
    void mirror$applyFrustum(Frustum frustum);
}
