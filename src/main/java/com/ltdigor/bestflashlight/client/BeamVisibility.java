package com.ltdigor.bestflashlight.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One-build cache. Never retained by a published snapshot or accessed by render workers. */
final class BeamVisibility implements Predicate<BlockPos> {
    private final Vec3 origin;
    private final Entity entity;
    private final Predicate<BlockPos> loaded;
    private final BlockGetter view;

    BeamVisibility(BlockGetter world, Entity entity, Vec3 origin, Predicate<BlockPos> loaded) {
        this.origin = origin;
        this.entity = entity;
        this.loaded = loaded;
        this.view = new BlockGetter() {
            private final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();

            @Override public BlockState getBlockState(BlockPos pos) {
                long key = pos.asLong();
                BlockState state = states.get(key);
                if (state == null) {
                    // Treat unloaded cells along the whole ray as opaque, without loading chunks.
                    state = loaded.test(pos) ? world.getBlockState(pos) : Blocks.BEDROCK.defaultBlockState();
                    states.put(key, state);
                }
                return state;
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return loaded.test(pos) ? world.getBlockEntity(pos) : null; }
            @Override public int getHeight() { return world.getHeight(); }
            @Override public int getMinBuildHeight() { return world.getMinBuildHeight(); }
        };
    }

    @Override public boolean test(BlockPos target) {
        if (!loaded.test(target)) return false;
        BlockHitResult hit = view.clip(new ClipContext(origin, Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }
}
