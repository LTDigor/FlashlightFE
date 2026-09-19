package com.ltdigor.bestflashlight;

import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Invisible light carrier whose water state survives ownership changes and reloads. */
public final class FlashlightLightBlock extends Block implements BucketPickup, LiquidBlockContainer {
    public static final MapCodec<FlashlightLightBlock> CODEC = simpleCodec(FlashlightLightBlock::new);
    public static final IntegerProperty LEVEL = BlockStateProperties.LEVEL;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final IntegerProperty WATER_LEVEL = IntegerProperty.create("water_level", 0, 15);
    // Air/water fluid and support shapes remain unchanged. Avoid neighbor
    // updates, which could otherwise read an unloaded chunk across its boundary.
    static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CLEANUP_DELAY = 100;

    public FlashlightLightBlock(Properties properties) {
        super(properties.replaceable().noCollission().noOcclusion().noLootTable()
            .lightLevel(state -> state.getValue(LEVEL)));
        registerDefaultState(stateDefinition.any()
            .setValue(LEVEL, 15)
            .setValue(WATERLOGGED, false)
            .setValue(WATER_LEVEL, 0));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LEVEL, WATERLOGGED, WATER_LEVEL);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        // Keep the carrier replaceable for normal block placement, but do not let
        // neighboring fluid propagation overwrite it before the owning beam releases it.
        return false;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        if (!state.getValue(WATERLOGGED)) return Fluids.EMPTY.defaultFluidState();
        return Blocks.WATER.defaultBlockState()
            .setValue(LiquidBlock.LEVEL, state.getValue(WATER_LEVEL))
            .getFluidState();
    }

    @Override
    public boolean canPlaceLiquid(Player player, BlockGetter level, BlockPos pos,
                                  BlockState state, Fluid fluid) {
        return !state.getValue(WATERLOGGED) && fluid.isSame(Fluids.WATER);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (state.getValue(WATERLOGGED) || !fluidState.getType().isSame(Fluids.WATER)) return false;
        BlockState legacy = fluidState.createLegacyBlock();
        int waterLevel = legacy.is(Blocks.WATER) ? legacy.getValue(LiquidBlock.LEVEL) : 0;
        level.setBlock(
            pos,
            state.setValue(WATERLOGGED, true).setValue(WATER_LEVEL, waterLevel),
            Block.UPDATE_ALL
        );
        level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
        return true;
    }

    @Override
    public ItemStack pickupBlock(Player player, LevelAccessor level,
                                 BlockPos pos, BlockState state) {
        if (!state.getValue(WATERLOGGED) || !getFluidState(state).isSource()) return ItemStack.EMPTY;

        level.setBlock(
            pos,
            state.setValue(WATERLOGGED, false).setValue(WATER_LEVEL, 0),
            Block.UPDATE_ALL
        );
        return new ItemStack(Items.WATER_BUCKET);
    }

    @Override
    public Optional<SoundEvent> getPickupSound() {
        return Fluids.WATER.getPickupSound();
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide) {
            level.scheduleTick(pos, this, CLEANUP_DELAY);
            if (state.getValue(WATERLOGGED)) {
                FluidState fluid = getFluidState(state);
                level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
            }
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            FluidState fluid = getFluidState(state);
            level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    public static boolean preserveCarrierDuringFluidTick(Level level, BlockPos pos,
                                                         BlockState replacement, int flags) {
        if (!replacement.isAir() && !replacement.is(Blocks.WATER)) {
            return level.setBlock(pos, replacement, flags);
        }

        BlockState current = level.getBlockState(pos);
        if (!current.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
            return level.setBlock(pos, replacement, flags);
        }

        BlockState next = current;
        if (replacement.isAir()) {
            next = current.setValue(WATERLOGGED, false).setValue(WATER_LEVEL, 0);
        } else if (replacement.is(Blocks.WATER)) {
            next = current
                .setValue(WATERLOGGED, true)
                .setValue(WATER_LEVEL, replacement.getValue(LiquidBlock.LEVEL));
        } else {
            return level.setBlock(pos, replacement, flags);
        }

        if (next == current) return false;
        return level.setBlock(pos, next, flags);
    }

    static void rearmCleanup(ServerLevel level, BlockPos pos) {
        if (level.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
            level.scheduleTick(pos, FlashlightMod.FLASHLIGHT_LIGHT.get(), CLEANUP_DELAY);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean movedByPiston) {
        if (!level.isClientSide && !replacement.is(this)) FlashlightEvents.forgetLight(level.dimension(), pos);
        super.onRemove(state, level, pos, replacement, movedByPiston);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (FlashlightEvents.isTrackedLight(level.dimension(), pos)) {
            level.scheduleTick(pos, this, CLEANUP_DELAY);
        } else {
            restore(level, pos);
        }
    }


    static void restore(ServerLevel level, BlockPos pos) {
        BlockState current = level.getBlockState(pos);
        if (current.getBlock() instanceof FlashlightLightBlock) {
            boolean waterlogged = current.getValue(WATERLOGGED);
            BlockState restored = waterlogged
                ? Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, current.getValue(WATER_LEVEL))
                : Blocks.AIR.defaultBlockState();
            level.setBlock(pos, restored, UPDATE_FLAGS);
            if (waterlogged) {
                FluidState fluid = restored.getFluidState();
                level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
            } else {
                // The carrier is intentionally removed without broad neighbor block
                // updates. Re-arm nearby fluid sources explicitly so stable water can
                // flow into the newly restored air cell.
                for (Direction direction : Direction.values()) {
                    BlockPos neighborPos = pos.relative(direction);
                    if (!level.hasChunkAt(neighborPos)) continue;
                    FluidState neighborFluid = level.getFluidState(neighborPos);
                    if (!neighborFluid.isEmpty()) {
                        level.scheduleTick(
                            neighborPos,
                            neighborFluid.getType(),
                            neighborFluid.getType().getTickDelay(level)
                        );
                    }
                }
            }
        }
    }
}
