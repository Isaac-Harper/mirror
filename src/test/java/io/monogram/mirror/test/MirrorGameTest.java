package io.monogram.mirror.test;

import io.monogram.mirror.MirrorBlock;
import io.monogram.mirror.MirrorMod;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluids;

/**
 * In-world integration tests for {@link MirrorBlock}. These run headlessly on a real server (no client,
 * no rendering) via Fabric's GameTest harness, exercising the block's server-side behaviour: the
 * connection flags that merge adjacent mirrors, and waterlogging. Reflection rendering is client-only and
 * cannot be GameTested; the merge logic is the mod's core common behaviour and is what these cover.
 *
 * <p>Registered through the {@code fabric-gametest} entrypoint in fabric.mod.json. Positions are relative
 * to the 8x8x8 empty structure. {@code GameTestHelper.setBlock} places with neighbour updates, so an
 * existing mirror recomputes its connections (via {@code updateShape}) the moment a neighbour is placed or
 * removed - which is exactly the behaviour under test.
 */
public class MirrorGameTest {

    private static BlockState mirror(Direction facing) {
        return MirrorMod.MIRROR_BLOCK.defaultBlockState().setValue(MirrorBlock.FACING, facing);
    }

    /**
     * A mirror drops the frame edge it shares with a same-facing neighbour (so the two merge), and puts it
     * back when that neighbour is removed. For a NORTH-facing mirror the RIGHT edge is to the east and UP is
     * above, so placing mirrors there flips those flags on the original block.
     */
    @GameTest
    public void mirrorsMergeWithSameFacingNeighbours(GameTestHelper helper) {
        BlockPos center = new BlockPos(2, 2, 2);
        BlockPos east = new BlockPos(3, 2, 2);   // RIGHT of a NORTH-facing mirror (clockwise = EAST)
        BlockPos above = new BlockPos(2, 3, 2);  // UP

        helper.setBlock(center, mirror(Direction.NORTH));
        // No neighbours yet: the mirror stands alone with every edge framed.
        assertConnection(helper, center, MirrorBlock.RIGHT, false);
        assertConnection(helper, center, MirrorBlock.UP, false);

        // Place a same-facing mirror to the east: the centre mirror should connect on its RIGHT edge only.
        helper.setBlock(east, mirror(Direction.NORTH));
        assertConnection(helper, center, MirrorBlock.RIGHT, true);
        assertConnection(helper, center, MirrorBlock.LEFT, false);
        assertConnection(helper, center, MirrorBlock.UP, false);
        assertConnection(helper, center, MirrorBlock.DOWN, false);

        // Place another above: the centre now connects UP as well, and stays connected on the RIGHT.
        helper.setBlock(above, mirror(Direction.NORTH));
        assertConnection(helper, center, MirrorBlock.UP, true);
        assertConnection(helper, center, MirrorBlock.RIGHT, true);

        // Break the east neighbour: the RIGHT edge frames back up, the UP connection is untouched.
        helper.setBlock(east, Blocks.AIR.defaultBlockState());
        assertConnection(helper, center, MirrorBlock.RIGHT, false);
        assertConnection(helper, center, MirrorBlock.UP, true);

        helper.succeed();
    }

    /**
     * A mirror placed in water shares its block with the water: a waterlogged mirror reports a water fluid
     * state (so the water renders and flows around the thin panel), and a dry one reports no fluid.
     */
    @GameTest
    public void waterloggedMirrorReportsWaterFluid(GameTestHelper helper) {
        BlockPos wet = new BlockPos(2, 2, 2);
        BlockPos dry = new BlockPos(4, 2, 2);

        helper.setBlock(wet, mirror(Direction.NORTH).setValue(MirrorBlock.WATERLOGGED, true));
        if (helper.getBlockState(wet).getFluidState().getType() != Fluids.WATER) {
            throw helper.assertionException(wet, "a waterlogged mirror should report a water fluid state");
        }

        helper.setBlock(dry, mirror(Direction.NORTH));
        if (!helper.getBlockState(dry).getFluidState().isEmpty()) {
            throw helper.assertionException(dry, "a non-waterlogged mirror should report no fluid");
        }

        helper.succeed();
    }

    private static void assertConnection(GameTestHelper helper, BlockPos relPos, BooleanProperty edge,
            boolean expected) {
        boolean actual = helper.getBlockState(relPos).getValue(edge);
        if (actual != expected) {
            throw helper.assertionException(relPos, "mirror %s connection should be %s but was %s",
                edge.getName(), expected, actual);
        }
    }
}
