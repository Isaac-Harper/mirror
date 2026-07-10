package io.monogram.mirror.client.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Shares the loaded cloud texture with the mirror's dedicated reflection {@link CloudRenderer}.
 * The texture arrives via resource reload on the vanilla instance only; the reflection instance
 * (which exists so reflections never touch vanilla's cross-frame camera memos or ring buffers)
 * copies the reference each pass, so resource packs and reloads stay in sync.
 */
@Mixin(CloudRenderer.class)
public interface CloudRendererAccessor {
    @Accessor("texture")
    CloudRenderer.TextureData mirror$texture();

    @Accessor("texture")
    void mirror$setTexture(CloudRenderer.TextureData texture);
}
