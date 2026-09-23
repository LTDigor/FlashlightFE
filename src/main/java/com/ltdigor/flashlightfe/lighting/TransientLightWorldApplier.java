package com.ltdigor.flashlightfe.lighting;

import com.ltdigor.flashlightfe.FlashlightMod;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** The only place where the server fallback lighting mutates the world. */
final class TransientLightWorldApplier {
    private static final LongAdder ADDITIONS = new LongAdder();
    private static final LongAdder UPDATES = new LongAdder();
    private static final LongAdder REMOVALS = new LongAdder();

    private TransientLightWorldApplier() {}

    static long additions() {
        return ADDITIONS.sum();
    }

    static long updates() {
        return UPDATES.sum();
    }

    static long removals() {
        return REMOVALS.sum();
    }

    static void resetCounters() {
        ADDITIONS.reset();
        UPDATES.reset();
        REMOVALS.reset();
    }

    /**
     * Updates run before additions and removals last: while a beam sweeps, cells that
     * stay lit keep their carrier, so the visible pool never dips between reconciles.
     */
    static void apply(ServerLevel level, AppliedDimensionState applied, ReconciliationPlan plan) {
        plan.updates().forEach((pos, light) -> update(level, applied, pos, light));
        plan.additions().forEach((pos, light) -> install(level, applied, pos, light));
        plan.removals().forEach(pos -> restore(level, applied, pos));
    }

    /** Orphan adoption: a persisted carrier the desired frame still wants is kept, not recreated. */
    static void adopt(ServerLevel level, AppliedDimensionState applied, BlockPos pos, DesiredLight light) {
        BlockState current = level.getBlockState(pos);
        if (!current.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) return;
        RestorableEnvironment environment = environmentOf(current);
        if (current.getValue(TransientLightBlock.LEVEL) != light.level()) {
            level.setBlock(pos, current.setValue(TransientLightBlock.LEVEL, light.level()),
                TransientLightBlock.UPDATE_FLAGS);
            UPDATES.increment();
        }
        applied.put(pos, new AppliedLight(light.level(), environment));
    }

    private static void update(ServerLevel level, AppliedDimensionState applied, BlockPos pos, DesiredLight light) {
        if (!ServerBeamCalculator.isLoaded(level, pos)) return;
        BlockState current = level.getBlockState(pos);
        if (!current.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
            applied.remove(pos); // Another system replaced the carrier; never fight it.
            return;
        }
        RestorableEnvironment environment = environmentOf(current);
        if (current.getValue(TransientLightBlock.LEVEL) != light.level()) {
            // Brightness changes in place: no remove/recreate, water metadata untouched.
            level.setBlock(pos, current.setValue(TransientLightBlock.LEVEL, light.level()),
                TransientLightBlock.UPDATE_FLAGS);
            UPDATES.increment();
        }
        applied.put(pos, new AppliedLight(light.level(), environment));
    }

    private static void install(ServerLevel level, AppliedDimensionState applied, BlockPos pos, DesiredLight light) {
        if (!ServerBeamCalculator.isLoaded(level, pos)) return;
        BlockState current = level.getBlockState(pos);
        if (current.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
            adopt(level, applied, pos, light);
            return;
        }
        if (!ServerBeamCalculator.canHostTransientLight(current)) return;
        RestorableEnvironment environment = RestorableEnvironment.of(current);
        level.setBlock(pos, TransientLightBlock.carrier(light.level(), environment), TransientLightBlock.UPDATE_FLAGS);
        ADDITIONS.increment();
        if (environment instanceof RestorableEnvironment.Water water) {
            scheduleFluidTick(level, pos, water);
        }
        applied.put(pos, new AppliedLight(light.level(), environment));
    }

    /** Removal: only an own carrier is restored; anything else already occupying the cell stays. */
    static void restore(ServerLevel level, AppliedDimensionState applied, BlockPos pos) {
        applied.remove(pos);
        if (!ServerBeamCalculator.isLoaded(level, pos)) return;
        BlockState current = level.getBlockState(pos);
        if (!current.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) return;
        RestorableEnvironment environment = environmentOf(current);
        level.setBlock(pos, environment.restored(), TransientLightBlock.UPDATE_FLAGS);
        REMOVALS.increment();
        if (environment instanceof RestorableEnvironment.Water water) {
            scheduleFluidTick(level, pos, water);
        } else {
            // The carrier disappears without broad neighbor updates, so stable water next
            // door would never notice the new air cell; re-arm those fluid sources by hand.
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = pos.relative(direction);
                if (!level.hasChunkAt(neighborPos)) continue;
                FluidState neighborFluid = level.getFluidState(neighborPos);
                if (!neighborFluid.isEmpty()) {
                    level.scheduleTick(neighborPos, neighborFluid.getType(), neighborFluid.getType().getTickDelay(level));
                }
            }
        }
    }

    private static void scheduleFluidTick(ServerLevel level, BlockPos pos, RestorableEnvironment.Water water) {
        FluidState fluid = level.getBlockState(pos).getFluidState();
        if (!fluid.isEmpty()) {
            level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
        }
    }

    static RestorableEnvironment environmentOf(BlockState carrier) {
        return carrier.getValue(TransientLightBlock.WATERLOGGED)
            ? new RestorableEnvironment.Water(carrier.getValue(TransientLightBlock.WATER_LEVEL))
            : new RestorableEnvironment.Air();
    }
}
