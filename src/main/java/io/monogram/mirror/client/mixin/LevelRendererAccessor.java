package io.monogram.mirror.client.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the mirror swap {@link LevelRenderer}'s cloud renderer onto its dedicated reflection instance
 * for the duration of a reflection pass (and back), so the redirected cloud pass draws the virtual
 * camera's clouds without touching vanilla's instance. The matching public getter already exists.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
    @Mutable
    @Accessor("cloudRenderer")
    void mirror$setCloudRenderer(CloudRenderer cloudRenderer);
}
