package com.ltdigor.flashlightfe.lighting;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Immutable desired illumination of one active lamp; owns no world state and no ownership. */
public record BeamFrame(ResourceKey<Level> dimension, Map<BlockPos, DesiredLight> lights) {
    public BeamFrame {
        lights = Map.copyOf(lights);
    }

    /** Clamps brightness into 1..15, drops dark cells and freezes positions as immutable. */
    public static BeamFrame of(ResourceKey<Level> dimension, Map<BlockPos, Integer> raw) {
        Map<BlockPos, DesiredLight> clamped = new HashMap<>();
        raw.forEach((pos, level) -> {
            if (level != null && level > DesiredLight.MIN_LEVEL - 1) {
                clamped.put(pos.immutable(), DesiredLight.of(level));
            }
        });
        return new BeamFrame(dimension, clamped);
    }

    public static BeamFrame empty(ResourceKey<Level> dimension) {
        return new BeamFrame(dimension, Map.of());
    }

    public boolean isEmpty() {
        return lights.isEmpty();
    }
}
