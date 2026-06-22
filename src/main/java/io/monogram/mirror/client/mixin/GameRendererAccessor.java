package io.monogram.mirror.client.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the private resources GameRenderer feeds to LevelRenderer.renderLevel. */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("resourcePool")
    CrossFrameResourcePool mirror$resourcePool();

    @Accessor("fogRenderer")
    FogRenderer mirror$fogRenderer();

    // View-bobbing is folded into the projection matrix in renderLevel (projection *= bobPose), NOT into
    // the camera's view/position. To pin the mirror quad to the bobbing terrain we recompute the same
    // bobPose and apply it to the quad's projection. bobHurt always applies; bobView is gated on the option.
    @Invoker("bobHurt")
    void mirror$bobHurt(CameraRenderState cam, PoseStack pose);

    @Invoker("bobView")
    void mirror$bobView(CameraRenderState cam, PoseStack pose);
}
