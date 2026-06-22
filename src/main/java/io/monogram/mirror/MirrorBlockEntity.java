package io.monogram.mirror;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marker block entity for the mirror. It holds no data - its only purpose is to give the mirror a
 * {@link io.monogram.mirror.client.MirrorBlockEntityRenderer}, which draws the frame + glass per-frame
 * (so the block is NOT baked into the terrain chunk mesh) and skips drawing during the reflection pass.
 * That is what keeps the mirror out of its own reflection - no fragile near-plane clip required.
 */
public class MirrorBlockEntity extends BlockEntity {
    public MirrorBlockEntity(BlockPos pos, BlockState state) {
        super(MirrorMod.MIRROR_BLOCK_ENTITY, pos, state);
    }
}
