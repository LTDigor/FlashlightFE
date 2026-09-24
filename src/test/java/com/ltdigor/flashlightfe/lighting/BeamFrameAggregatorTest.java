package com.ltdigor.flashlightfe.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class BeamFrameAggregatorTest {
    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final BlockPos X = BlockPos.containing(1, 2, 3);
    private static final BlockPos Y = BlockPos.containing(4, 5, 6);
    private static final BlockPos Z = BlockPos.containing(7, 8, 9);

    private static BeamFrame frame(Map<BlockPos, Integer> lights) {
        return BeamFrame.of(OVERWORLD, lights);
    }

    @Test
    void overlappingCellsResolveByMaximumBrightness() {
        var aggregate = BeamFrameAggregator.aggregate(List.of(
            frame(Map.of(X, 5)),
            frame(Map.of(X, 12))));
        assertEquals(12, aggregate.get(OVERWORLD).get(X).level());
    }

    @Test
    void independentPositionsAreUnioned() {
        var aggregate = BeamFrameAggregator.aggregate(List.of(
            frame(Map.of(X, 5)),
            frame(Map.of(Y, 12))));
        assertEquals(Map.of(X, 5, Y, 12),
            Map.of(X, aggregate.get(OVERWORLD).get(X).level(), Y, aggregate.get(OVERWORLD).get(Y).level()));
    }

    @Test
    void emptyFramesDoNotContribute() {
        var aggregate = BeamFrameAggregator.aggregate(List.of(
            BeamFrame.empty(OVERWORLD),
            frame(Map.of()),
            frame(Map.of(X, 9))));
        assertEquals(1, aggregate.size());
        assertEquals(9, aggregate.get(OVERWORLD).get(X).level());
    }

    @Test
    void aggregationIsIndependentOfPlayerOrder() {
        BeamFrame a = frame(Map.of(X, 15, Y, 10));
        BeamFrame b = frame(Map.of(X, 8, Z, 12));
        BeamFrame c = frame(Map.of(Y, 3));
        assertEquals(BeamFrameAggregator.aggregate(List.of(a, b, c)),
            BeamFrameAggregator.aggregate(List.of(c, a, b)));
        assertEquals(BeamFrameAggregator.aggregate(List.of(a, b, c)),
            BeamFrameAggregator.aggregate(List.of(b, c, a)));
    }

    @Test
    void framesClampAndDropNonPositiveCells() {
        BeamFrame clamped = frame(Map.of(X, 40, Y, 0, Z, -3));
        assertEquals(1, clamped.lights().size(), "Non-positive cells must be dropped");
        assertEquals(15, clamped.lights().get(X).level(), "Out-of-range cells must clamp to 15");
    }

    @Test
    void dimensionsStaySeparated() {
        var aggregate = BeamFrameAggregator.aggregate(List.of(
            frame(Map.of(X, 9)),
            BeamFrame.of(Level.NETHER, Map.of(X, 4))));
        assertEquals(9, aggregate.get(OVERWORLD).get(X).level());
        assertEquals(4, aggregate.get(Level.NETHER).get(X).level());
    }
}
