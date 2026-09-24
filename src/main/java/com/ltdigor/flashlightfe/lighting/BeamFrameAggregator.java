package com.ltdigor.flashlightfe.lighting;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Merges per-source beam frames per dimension. Overlapping cells resolve by maximum
 * requested brightness, which makes the result independent of player processing order.
 */
public final class BeamFrameAggregator {
    private BeamFrameAggregator() {}

    public static Map<ResourceKey<Level>, Map<BlockPos, DesiredLight>> aggregate(Collection<BeamFrame> frames) {
        Map<ResourceKey<Level>, Map<BlockPos, DesiredLight>> aggregate = new HashMap<>();
        for (BeamFrame frame : frames) {
            if (frame.isEmpty()) continue;
            merge(frame, aggregate.computeIfAbsent(frame.dimension(), ignored -> new HashMap<>()));
        }
        return aggregate;
    }

    public static Map<BlockPos, DesiredLight> aggregateDimension(Collection<BeamFrame> frames,
                                                                 ResourceKey<Level> dimension) {
        Map<BlockPos, DesiredLight> aggregate = new HashMap<>();
        for (BeamFrame frame : frames) {
            if (frame.dimension().equals(dimension)) merge(frame, aggregate);
        }
        return aggregate;
    }

    private static void merge(BeamFrame frame, Map<BlockPos, DesiredLight> target) {
        frame.lights().forEach((pos, light) -> target.merge(pos, light, DesiredLight::brighter));
    }
}
