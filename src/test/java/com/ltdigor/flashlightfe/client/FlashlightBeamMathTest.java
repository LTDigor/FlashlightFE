package com.ltdigor.flashlightfe.client;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlashlightBeamMathTest {
    private static final double EPS = 1e-9;

    @Test void downwardBeamReachesFloorAcrossBlockGrid() {
        for (double offsetX : new double[]{0, 0.1, 0.25, 0.5, 0.9}) {
            for (double offsetZ : new double[]{0, 0.1, 0.25, 0.5, 0.9}) {
                for (float pitch : new float[]{85, 89, 90}) {
                    for (float yaw : new float[]{0, 45, 135, 270}) {
                        Vec3 origin = new Vec3(offsetX, 2.62, offsetZ);
                        Vec3 axis = Vec3.directionFromRotation(pitch, yaw);
                        double brightest = 0;
                        for (int x = -1; x <= 1; x++) {
                            for (int z = -1; z <= 1; z++) {
                                brightest = Math.max(brightest, FlashlightBeamMath.coneLuminance(
                                    origin, axis, new Vec3(x + 0.5, 0.5, z + 0.5), 12, Math.toRadians(7.5)));
                            }
                        }
                        assertTrue(brightest > 0, "No floor sample: " + origin + " pitch=" + pitch + " yaw=" + yaw);
                    }
                }
            }
        }
    }

    @Test void radialEdgeFadesAtTorchRateInsteadOfJumpingBetweenBlocks() {
        Vec3 axis = new Vec3(0, 0, 1);
        double previous = FlashlightBeamMath.coneLuminance(Vec3.ZERO, axis, new Vec3(0.5, 0, 4), 12, Math.toRadians(7.5));
        for (double radial = 1.5; radial <= 10.5; radial++) {
            double next = FlashlightBeamMath.coneLuminance(Vec3.ZERO, axis, new Vec3(radial, 0, 4), 12, Math.toRadians(7.5));
            assertTrue(previous >= next);
            assertTrue(previous - next <= 15.0 / 7.75 + EPS,
                "Adjacent light samples must not jump across a sub-block penumbra");
            previous = next;
        }
        assertEquals(0, previous);
    }

    @Test void endOfRangeFadesBeforeTheCutoff() {
        double atTen = FlashlightBeamMath.coneLuminance(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 10), 12, Math.toRadians(7.5));
        double atEleven = FlashlightBeamMath.coneLuminance(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 0, 11), 12, Math.toRadians(7.5));
        assertTrue(atTen > atEleven && atEleven > 0);
        assertTrue(atEleven <= 15.0 / 7.75 + EPS);
    }

    @Test void farConeStaysCircularAndDoesNotLightBehindOrigin() {
        Vec3 origin = Vec3.ZERO;
        Vec3 axis = new Vec3(0, 0, 1);
        double angle = Math.toRadians(7.5);
        assertEquals(12 * Math.tan(angle), FlashlightBeamMath.coneRadius(12, angle), EPS);
        double cardinal = FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(1.2, 0, 10), 12, angle);
        double diagonal = FlashlightBeamMath.coneLuminance(origin, axis,
            new Vec3(1.2 / Math.sqrt(2), 1.2 / Math.sqrt(2), 10), 12, angle);
        assertEquals(cardinal, diagonal, EPS);
        assertEquals(0, FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(0, 0, -0.01), 12, angle));
        assertEquals(0, FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(0, 0, 12.01), 12, angle));
    }

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
        double edge = FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(0.75, 0, 4), 12, halfAngle);
        double outside = FlashlightBeamMath.coneLuminance(origin, axis, new Vec3(10.0, 0, 4), 12, halfAngle);
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

    @Test void configuredFullAngleConvertsToHalfAngle() {
        assertEquals(Math.toRadians(7.5), FlashlightBeamMath.halfAngleRadians(15.0), EPS);
    }
}
