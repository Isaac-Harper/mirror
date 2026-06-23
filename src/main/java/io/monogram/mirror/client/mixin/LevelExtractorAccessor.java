package io.monogram.mirror.client.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link LevelExtractor#applyFrustum}, which rebuilds the visible-section list for a frustum. In
 * 26.2 this moved off LevelRenderer onto LevelExtractor, and {@code extract()} only calls it when the
 * occlusion graph signals a per-frame frustum update. The reflection runs a second extract per frame (for
 * the virtual camera) and the end-of-frame restore runs a third; both skip the gated applyFrustum (we
 * suppress the signal during the pass), so we force it: once for the virtual frustum (so the reflection
 * culls its own sections) and once to restore the main view (else main-view geometry stays culled to the
 * reflected frustum and pops/vanishes as the camera turns).
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
    @Invoker("applyFrustum")
    void mirror$applyFrustum(Frustum frustum);
}
