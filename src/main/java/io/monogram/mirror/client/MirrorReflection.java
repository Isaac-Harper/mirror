package io.monogram.mirror.client;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Pure reflection math for an axis-aligned (block-face) mirror plane.
 *
 * A mirror reflects the scene across its plane, so the virtual camera is the main camera
 * reflected across that plane: position mirrored, view direction mirrored. Mirror blocks
 * face horizontally (N/S/E/W), so the plane is vertical and pitch is preserved.
 */
public final class MirrorReflection {
    private MirrorReflection() {}

    /** Reflect a point across the mirror plane (plane point = surface center, normal = surface normal). */
    public static Vec3 reflectPoint(Vec3 p, MirrorSurface m) {
        double d = p.subtract(m.center()).dot(m.normal());
        return p.subtract(m.normal().scale(2.0 * d));
    }

    /**
     * Reflected camera yaw for a vertical, axis-aligned plane.
     * MC yaw: 0 = +Z, 90 = -X, 180 = -Z, 270 = +X. Reflecting across a plane whose normal lies
     * on the Z axis negates the look-Z component (yaw' = 180 - yaw); on the X axis, the look-X
     * component (yaw' = -yaw). General/tilted planes would instead reflect the look vector.
     */
    public static float reflectYaw(float yaw, Direction facing) {
        return (facing.getAxis() == Direction.Axis.Z) ? (180.0f - yaw) : (-yaw);
    }
}
