package com.ltdigor.flashlightfe.lighting;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Carriers the manager currently believes exist in one dimension; holds no player ids. */
final class AppliedDimensionState {
    private final Map<BlockPos, AppliedLight> lights = new HashMap<>();

    Map<BlockPos, AppliedLight> lights() {
        return lights;
    }

    AppliedLight get(BlockPos pos) {
        return lights.get(pos);
    }

    void put(BlockPos pos, AppliedLight light) {
        lights.put(pos.immutable(), light);
    }

    void remove(BlockPos pos) {
        lights.remove(pos);
    }

    boolean isEmpty() {
        return lights.isEmpty();
    }

    void clear() {
        lights.clear();
    }

    /** Stale entries of an unloaded chunk are dropped; persisted carriers return via chunk-load recovery. */
    void removeChunk(int chunkX, int chunkZ) {
        lights.keySet().removeIf(pos -> (pos.getX() >> 4) == chunkX && (pos.getZ() >> 4) == chunkZ);
    }
}
