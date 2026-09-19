package com.ltdigor.bestflashlight.client;

import net.minecraft.world.phys.Vec3;

/** Pure beam geometry kept separate from rendering so it can be unit-tested. */
final class FlashlightBeamMath {
    private FlashlightBeamMath() {}

    static Vec3 smooth(Vec3 previous, Vec3 target, double factor) {
        if (target == null || target.lengthSqr() < 1.0E-12) return previous;
        Vec3 normalized = target.normalize();
        if (previous == null || previous.lengthSqr() < 1.0E-12) return normalized;
        double t = Math.clamp(factor, 0.0, 1.0);
        Vec3 blended = previous.normalize().scale(1.0 - t).add(normalized.scale(t));
        return blended.lengthSqr() < 1.0E-12 ? normalized : blended.normalize();
    }

    static Vec3 wallEnd(Vec3 start, Vec3 direction, double hitDistance, double epsilon) {
        Vec3 axis = direction.normalize();
        double distance = Math.max(0.0, hitDistance - Math.max(0.0, epsilon));
        return start.add(axis.scale(distance));
    }

    static Vec3 coneDirection(Vec3 axis, Vec3 right, Vec3 up, double yaw, double pitch) {
        Vec3 direction = axis.normalize()
            .add(right.normalize().scale(Math.tan(yaw)))
            .add(up.normalize().scale(Math.tan(pitch)));
        return direction.normalize();
    }

    static double coneOffsetRadians(double fullAngleDegrees, double fraction) {
        return Math.toRadians(Math.clamp(fullAngleDegrees, 1.0, 90.0) * 0.5 * Math.clamp(fraction, -1.0, 1.0));
    }
}
