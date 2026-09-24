package com.ltdigor.flashlightfe.lighting;

import com.ltdigor.flashlightfe.FlashlightMod;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
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

/**
 * Passive invisible light carrier. It owns no lifecycle: no self-scheduled ticks, no
 * ownership bookkeeping. The manager decides when a carrier appears, changes brightness
 * or disappears; this block only describes itself and interoperates with fluids.
 */
public final class TransientLightBlock extends Block implements BucketPickup, LiquidBlockContainer {
    public static final MapCodec<TransientLightBlock> CODEC = simpleCodec(TransientLightBlock::new);
    public static final IntegerProperty LEVEL = BlockStateProperties.LEVEL;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final IntegerProperty WATER_LEVEL = IntegerProperty.create("water_level", 0, 15);
    // Client light updates without neighbor updates: neighbor refreshes could read an
    // unloaded chunk across the carrier boundary and add useless block update spam.
    public static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public TransientLightBlock(Properties properties) {
        super(properties.replaceable().noCollission().noOcclusion().noLootTable()
            .lightLevel(state -> state.getValue(LEVEL)));
        registerDefaultState(stateDefinition.any()
            .setValue(LEVEL, 15)
            .setValue(WATERLOGGED, false)
            .setValue(WATER_LEVEL, 0));
    }

    public static BlockState carrier(int level, RestorableEnvironment environment) {
        BlockState state = FlashlightMod.FLASHLIGHT_LIGHT.get().defaultBlockState()
            .setValue(LEVEL, Math.clamp(level, DesiredLight.MIN_LEVEL, DesiredLight.MAX_LEVEL));
        if (environment instanceof RestorableEnvironment.Water water) {
            return state.setValue(WATERLOGGED, true).setValue(WATER_LEVEL, water.level());
        }
        return state.setValue(WATERLOGGED, false).setValue(WATER_LEVEL, 0);
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
    protected FluidState getFluidState(BlockState state) {
        if (!state.getValue(WATERLOGGED)) return Fluids.EMPTY.defaultFluidState();
        return Blocks.WATER.defaultBlockState()
            .setValue(LiquidBlock.LEVEL, state.getValue(WATER_LEVEL))
            .getFluidState();
    }

    @Override
    public boolean canPlaceLiquid(Player player, BlockGetter level, BlockPos pos,
                                  BlockState state, Fluid fluid) {
        // The carrier is air-like. Water is represented inside the carrier so its
        // light can survive flowing-water updates; every other fluid must still be
        // allowed to replace a dry carrier exactly as it would replace air.
        return !state.getValue(WATERLOGGED);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state, FluidState fluidState) {
        if (state.getValue(WATERLOGGED) || fluidState.isEmpty()) return false;

        if (!level.isClientSide()) {
            if (fluidState.getType().isSame(Fluids.WATER)) {
                BlockState legacy = fluidState.createLegacyBlock();
                int waterLevel = legacy.is(Blocks.WATER) ? legacy.getValue(LiquidBlock.LEVEL) : 0;
                level.setBlock(
                    pos,
                    state.setValue(WATERLOGGED, true).setValue(WATER_LEVEL, waterLevel),
                    Block.UPDATE_ALL
                );
                level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
            } else {
                // Non-water fluids cannot be represented by this carrier; let the real
                // fluid block take the cell. The manager notices on the next reconcile.
                level.setBlock(pos, fluidState.createLegacyBlock(), Block.UPDATE_ALL);
            }
        }
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
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            FluidState fluid = getFluidState(state);
            level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    /**
     * Keeps a carrier alive while flowing water recomputes the cell: the fluid tick is
     * redirected into carrier metadata instead of replacing the block.
     */
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
}
