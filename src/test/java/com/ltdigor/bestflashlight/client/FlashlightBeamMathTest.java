package com.ltdigor.bestflashlight.client;

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

    @Test void closeWallNeverPushesBeamThroughWallOrBehindCamera() {
        Vec3 start = new Vec3(10, 20, 30);
        Vec3 direction = new Vec3(0, 0, 1);
        Vec3 normal = FlashlightBeamMath.wallEnd(start, direction, 0.40, 0.06);
        assertEquals(0.34, normal.distanceTo(start), EPS);
        Vec3 touching = FlashlightBeamMath.wallEnd(start, direction, 0.02, 0.06);
        assertEquals(start, touching);
    }

    @Test void coneCenterIsAxisAndOffsetsRemainNormalized() {
        Vec3 axis = new Vec3(0, 0, 1);
        Vec3 right = new Vec3(1, 0, 0);
        Vec3 up = new Vec3(0, 1, 0);
        assertEquals(axis, FlashlightBeamMath.coneDirection(axis, right, up, 0, 0));
        Vec3 edge = FlashlightBeamMath.coneDirection(axis, right, up, Math.toRadians(7.5), 0);
        assertEquals(1.0, edge.length(), EPS);
        assertTrue(edge.x > 0 && edge.z > 0);
    }

    @Test void configuredFullAngleConvertsToHalfAngle() {
        assertEquals(Math.toRadians(7.5), FlashlightBeamMath.coneOffsetRadians(15.0, 1.0), EPS);
        assertEquals(-Math.toRadians(7.5), FlashlightBeamMath.coneOffsetRadians(15.0, -1.0), EPS);
    }
}
