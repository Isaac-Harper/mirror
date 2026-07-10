package io.monogram.mirror.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * A reflective glass rectangle in the world. The mirror plane passes through {@code center} with
 * outward {@code normal}. The glass extends from the cell centre by per-edge offsets along the face's
 * horizontal axis (normal × up: {@code rightOff}/{@code leftOff}) and vertical axis ({@code upOff}/
 * {@code downOff}). A CONNECTED edge reaches the cell boundary (0.5) so it joins its neighbour
 * seamlessly; a FRAMED edge is inset by the wood frame so the reflection never reaches the wood.
 */
public record MirrorSurface(BlockPos pos, Direction facing, Vec3 center, Vec3 normal,
                            double rightOff, double leftOff, double upOff, double downOff) {

    /** Wood frame thickness in blocks - keep in sync with the block models. */
    public static final double FRAME = 2.0 / 16.0;
    /** Glass-panel depth: the framed panel hugs the back of the cell (against the wall), this deep. */
    public static final double DEPTH = 2.0 / 16.0;
    /** Glass plane offset from block centre along the normal: front face of the back-mounted panel.
     *  Package-visible so MirrorRenderer can compute a plane key from a bare pos/facing without
     *  building a surface (it runs per mirror block entity per reflection pass). */
    static final double GLASS_OFFSET = -(0.5 - DEPTH);
    /**
     * Half-extent of a framed edge's glass: inset from the 0.5 cell boundary by exactly the frame width,
     * so the reflection fills the frame opening edge-to-edge. (The earlier corner z-fighting was the glass
     * model's side faces, not the quad extent, so no extra margin is needed.) Connected edges stay at 0.5
     * so merged mirrors still join seamlessly.
     */
    private static final double INSET = 0.5 - FRAME;

    /**
     * A 1x1 mirror cell. {@code up/down/left/right} are the cell's connection flags (model space):
     * {@code +horiz} (normal × up) is RIGHT, {@code -horiz} is LEFT, {@code +up} is UP, {@code -up} DOWN.
     */
    public static MirrorSurface single(BlockPos pos, Direction facing,
                                       boolean up, boolean down, boolean left, boolean right) {
        Vec3 n = facing.getUnitVec3();
        Vec3 center = Vec3.atCenterOf(pos).add(n.scale(GLASS_OFFSET)); // glass = back-panel's viewer-facing face
        return new MirrorSurface(pos.immutable(), facing, center, n,
            right ? 0.5 : INSET, left ? 0.5 : INSET, up ? 0.5 : INSET, down ? 0.5 : INSET);
    }
}
