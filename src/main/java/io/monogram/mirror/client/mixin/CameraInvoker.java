package io.monogram.mirror.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link Camera}'s protected setters so we can construct a reflected virtual camera
 * (the camera a mirror's second world render is performed from).
 */
@Mixin(Camera.class)
public interface CameraInvoker {
    @Invoker("setPosition")
    void mirror$setPosition(Vec3 pos);

    @Invoker("setRotation")
    void mirror$setRotation(float yaw, float pitch);

    // Marking the virtual camera "detached" makes the player entity render in the reflection
    // (the first-person self-skip only applies to a non-detached camera).
    @Accessor("detached")
    void mirror$setDetached(boolean detached);

    // A fresh Camera has a degenerate (identity) cull frustum that culls every entity. Build a
    // real one so entities - including the player - appear in the reflection.
    @Invoker("prepareCullFrustum")
    void mirror$prepareCullFrustum(Matrix4fc modelViewMatrix, Matrix4f projectionMatrix, Vec3 cameraPos);
}
