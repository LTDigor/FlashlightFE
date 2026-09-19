package com.ltdigor.bestflashlight.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlashlightBeamMathTest {
    private static final double EPS = 1e-9;

    @Test void smoothingKeepsDirectionNormalizedAndMovesTowardTarget() {
        Vec3 previous = new Vec3(0, 0, 1);
        Vec3 target = new Vec3(1, 0, 0);
        Vec3 result = FlashlightBeamMath.smooth(previous, target, 0.38);
        assertEquals(1.0, result.length(), EPS);
        assertTrue(result.x > 0.0);
        assertTrue(result.z > 0.0);
    }

    @Test void smoothingEndpointsAreStable() {
        Vec3 previous = new Vec3(0, 0, 1);
        Vec3 target = new Vec3(1, 0, 0);
        assertEquals(previous, FlashlightBeamMath.smooth(previous, target, 0.0));
        assertEquals(target, FlashlightBeamMath.smooth(previous, target, 1.0));
    }

    @Test void exactHalfTurnDoesNotStallOnPreviousDirection() {
        Vec3 previous = new Vec3(0, 0, 1);
        Vec3 target = new Vec3(0, 0, -1);
        Vec3 result = FlashlightBeamMath.smooth(previous, target, 0.38);

        assertEquals(1.0, result.length(), EPS);
        assertTrue(result.distanceTo(previous) > 0.1,
            "An exact 180-degree turn must move away from the previous direction");
        assertTrue(result.dot(target) > previous.dot(target),
            "Smoothing must make progress toward the new direction");
    }

    @Test void nearHalfTurnPreservesTargetsTurnSide() {
        Vec3 previous = new Vec3(0, 0, 1);
        Vec3 target = new Vec3(0.001, 0, -1).normalize();
        Vec3 result = FlashlightBeamMath.smooth(previous, target, 0.38);

        assertTrue(result.x > 0.0,
            "Near-antipodal smoothing must follow the target's lateral direction");
        assertTrue(result.dot(target) > previous.dot(target));
    }

    @Test void smoothingIsFrameRateIndependent() {
        double at60 = FlashlightBeamMath.frameIndependentFactor(0.38, 1.0 / 60.0, 60.0);
        double at120 = FlashlightBeamMath.frameIndependentFactor(0.38, 1.0 / 120.0, 60.0);
        assertEquals(0.38, at60, EPS);
        assertEquals(at60, 1.0 - Math.pow(1.0 - at120, 2.0), EPS);
    }

    @Test void coneHasBrightCoreSoftEdgeAndNoBackwardsLight() {
        Vec3 origin = Vec3.ZERO;
        Vec3 axis = new Vec3(0, 0, 1);
        double halfAngle = Math.toRadians(7.5);

        double core = FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(0, 0, 4), 12, halfAngle);
        double edge = FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(0.45, 0, 4), 12, halfAngle);
        double outside = FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(1.0, 0, 4), 12, halfAngle);
        double behind = FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(0, 0, -1), 12, halfAngle);

        assertTrue(core > edge);
        assertTrue(edge > 0.0);
        assertEquals(0.0, outside, EPS);
        assertEquals(0.0, behind, EPS);
    }

    @Test void closeWallStillReceivesLightAtBlockCentre() {
        Vec3 origin = new Vec3(0.9, 1.62, 0.5);
        Vec3 point = new Vec3(1.5, 1.5, 0.5);
        double light = FlashlightBeamMath.coneLuminance(origin, new Vec3(1, 0, 0), point, 12, Math.toRadians(7.5));
        assertTrue(light > 10.0);
    }

    @Test void hitBlockIsVisibleButBlocksBehindHitAreNot() {
        BlockPos wall = new BlockPos(1, 1, 0);
        BlockPos behind = new BlockPos(2, 1, 0);
        long wallKey = wall.asLong();

        assertTrue(FlashlightBeamMath.visibleAtSample(wall, 0.65, 0.10, wallKey));
        assertFalse(FlashlightBeamMath.visibleAtSample(behind, 1.65, 0.10, wallKey));
        assertTrue(FlashlightBeamMath.visibleAtSample(behind, 1.00, 2.00, FlashlightBeamMath.NO_HIT_BLOCK));
    }

    @Test void offRayPointBeforeWallUsesProjectedOcclusionDepth() {
        BlockPos target = new BlockPos(1, 1, 0);
        assertTrue(FlashlightBeamMath.visibleAtSample(
            target, 0.95, 1.0, FlashlightBeamMath.NO_HIT_BLOCK),
            "A point laterally offset from a sample ray must stay visible when its projected depth is before the hit");
        assertFalse(FlashlightBeamMath.visibleAtSample(
            target, 1.05, 1.0, FlashlightBeamMath.NO_HIT_BLOCK),
            "A point projected behind the sampled collision must be occluded");
    }

    @Test void coneSamplesReachConfiguredEdges() {
        double diagonal = Math.sqrt(0.5);
        double[] x = {0.0, 1.0, -1.0, 0.0, 0.0, diagonal, -diagonal, diagonal, -diagonal};
        double[] y = {0.0, 0.0, 0.0, 1.0, -1.0, diagonal, diagonal, -diagonal, -diagonal};

        assertEquals(1, FlashlightBeamMath.nearestConeSample(0.95, 0.05, x, y));
        assertEquals(5, FlashlightBeamMath.nearestConeSample(0.70, 0.70, x, y));
        assertEquals(0, FlashlightBeamMath.nearestConeSample(0.05, -0.05, x, y));
    }

    @Test void configuredFullAngleConvertsToHalfAngle() {
        assertEquals(Math.toRadians(7.5), FlashlightBeamMath.halfAngleRadians(15.0), EPS);
    }
}
