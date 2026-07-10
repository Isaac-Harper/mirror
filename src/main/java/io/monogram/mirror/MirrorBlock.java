package io.monogram.mirror;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A thin, horizontally-facing wood-framed mirror panel. {@code FACING} is the outward normal of the
 * reflective surface (points toward the player who placed it). The panel hugs the wall behind it.
 *
 * <p>{@code UP}/{@code DOWN}/{@code LEFT}/{@code RIGHT} record whether the in-plane edge neighbour is
 * another mirror with the same facing (LEFT/RIGHT are relative to the facing). When connected, the model
 * omits that frame edge so adjacent mirrors merge into one seamless larger mirror. The four diagonals
 * ({@code UL}/{@code UR}/{@code DL}/{@code DR}) let the model add an inner-corner frame piece where two
 * edges connect but the diagonal is empty - the concave corner of an L - without filling a true interior
 * corner (a 2x2 centre, where the diagonal IS a mirror).
 */
public class MirrorBlock extends HorizontalDirectionalBlock implements EntityBlock, SimpleWaterloggedBlock {
    public static final MapCodec<MirrorBlock> CODEC = simpleCodec(MirrorBlock::new);

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty LEFT = BooleanProperty.create("left");
    public static final BooleanProperty RIGHT = BooleanProperty.create("right");
    public static final BooleanProperty UL = BooleanProperty.create("ul");
    public static final BooleanProperty UR = BooleanProperty.create("ur");
    public static final BooleanProperty DL = BooleanProperty.create("dl");
    public static final BooleanProperty DR = BooleanProperty.create("dr");

    // Thin panel hugging the wall behind it (the FACING-opposite face); 3px deep (glass + proud frame).
    private static final VoxelShape NORTH_SHAPE = Block.box(0, 0, 13, 16, 16, 16); // faces N, on S wall
    private static final VoxelShape SOUTH_SHAPE = Block.box(0, 0, 0, 16, 16, 3);   // faces S, on N wall
    private static final VoxelShape WEST_SHAPE = Block.box(13, 0, 0, 16, 16, 16);  // faces W, on E wall
    private static final VoxelShape EAST_SHAPE = Block.box(0, 0, 0, 3, 16, 16);    // faces E, on W wall

    public MirrorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
            .setValue(FACING, Direction.NORTH)
            .setValue(UP, false).setValue(DOWN, false).setValue(LEFT, false).setValue(RIGHT, false)
            .setValue(UL, false).setValue(UR, false).setValue(DL, false).setValue(DR, false)
            .setValue(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MirrorBlockEntity(pos, state);
    }

    /**
     * The frame + glass are drawn by {@code MirrorBlockEntityRenderer}, not baked into the terrain chunk
     * mesh. Keeping the block out of the mesh is what lets the reflection skip it (no clip-plane needed),
     * so the mirror never renders into its own reflection.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, UP, DOWN, LEFT, RIGHT, UL, UR, DL, DR, WATERLOGGED);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection().getOpposite();
        boolean water = ctx.getLevel().getFluidState(ctx.getClickedPos()).getType() == Fluids.WATER;
        return withConnections(defaultBlockState().setValue(FACING, facing).setValue(WATERLOGGED, water),
            ctx.getLevel(), ctx.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tick,
            BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState,
            RandomSource random) {
        // The panel is thin like a pane, so it can share its block with water.
        if (state.getValue(WATERLOGGED)) {
            tick.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        // Recompute all eight connections from the world. A single in-plane neighbour change can flip an
        // edge AND a diagonal, so it's simplest (and robust) to re-read the whole neighbourhood.
        return withConnections(state, level, pos);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            case EAST -> EAST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    /** Set all eight connection flags from the same-facing mirror neighbours around {@code pos}. */
    private static BlockState withConnections(BlockState state, LevelReader level, BlockPos pos) {
        Direction f = state.getValue(FACING);
        Direction ccw = f.getCounterClockWise(); // LEFT direction in world
        Direction cw = f.getClockWise();          // RIGHT direction in world
        return state
            .setValue(UP, connects(level, pos.above(), f))
            .setValue(DOWN, connects(level, pos.below(), f))
            .setValue(LEFT, connects(level, pos.relative(ccw), f))
            .setValue(RIGHT, connects(level, pos.relative(cw), f))
            .setValue(UL, connects(level, pos.above().relative(ccw), f))
            .setValue(UR, connects(level, pos.above().relative(cw), f))
            .setValue(DL, connects(level, pos.below().relative(ccw), f))
            .setValue(DR, connects(level, pos.below().relative(cw), f));
    }

    private static boolean connects(LevelReader level, BlockPos pos, Direction facing) {
        return isMirror(level.getBlockState(pos), facing);
    }

    private static boolean isMirror(BlockState state, Direction facing) {
        return state.getBlock() instanceof MirrorBlock && state.getValue(FACING) == facing;
    }
}
