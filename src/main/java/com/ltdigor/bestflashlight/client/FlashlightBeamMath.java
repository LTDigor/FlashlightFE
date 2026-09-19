package com.ltdigor.bestflashlight.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Pure beam geometry kept separate from rendering so it can be unit-tested. */
final class FlashlightBeamMath {
    static final long NO_HIT_BLOCK = Long.MIN_VALUE;
    private static final double MIN_CONE_RADIUS = 0.35;
    private static final double HIT_EPSILON = 0.03;

    private FlashlightBeamMath() {}

    static Vec3 smooth(Vec3 previous, Vec3 target, double factor) {
        if (target == null || target.lengthSqr() < 1.0E-12) return previous;
        Vec3 to = target.normalize();
        if (previous == null || previous.lengthSqr() < 1.0E-12) return to;

        Vec3 from = previous.normalize();
        double t = Math.clamp(factor, 0.0, 1.0);
        if (t <= 0.0) return from;
        if (t >= 1.0) return to;

        double dot = Math.clamp(from.dot(to), -1.0, 1.0);
        if (dot > 0.9995) {
            Vec3 blended = from.scale(1.0 - t).add(to.scale(t));
            return blended.lengthSqr() < 1.0E-12 ? to : blended.normalize();
        }

        if (dot < -0.9995) {
            // Near-antipodal vectors make the regular slerp denominator unstable.
            // Preserve the target's tiny lateral component when it exists; only an
            // exact half-turn needs an arbitrary but stable perpendicular direction.
            Vec3 perpendicular = to.subtract(from.scale(dot));
            if (perpendicular.lengthSqr() < 1.0E-12) {
                Vec3 reference = Math.abs(from.y) < 0.9
                    ? new Vec3(0.0, 1.0, 0.0)
                    : new Vec3(1.0, 0.0, 0.0);
                perpendicular = from.cross(reference);
            }
            perpendicular = perpendicular.normalize();
            double angle = Math.acos(dot) * t;
            return from.scale(Math.cos(angle)).add(perpendicular.scale(Math.sin(angle))).normalize();
        }

        double angle = Math.acos(dot);
        double sinAngle = Math.sin(angle);
        double fromWeight = Math.sin((1.0 - t) * angle) / sinAngle;
        double toWeight = Math.sin(t * angle) / sinAngle;
        return from.scale(fromWeight).add(to.scale(toWeight)).normalize();
    }

    /**
     * Converts a smoothing coefficient tuned for one frame rate into an equivalent
     * coefficient for the actual frame duration.
     */
    static double frameIndependentFactor(double nominalFactor, double deltaSeconds, double nominalFps) {
        double factor = Math.clamp(nominalFactor, 0.0, 1.0);
        if (factor == 0.0 || deltaSeconds <= 0.0 || nominalFps <= 0.0) return 0.0;
        if (factor == 1.0) return 1.0;
        return 1.0 - Math.pow(1.0 - factor, deltaSeconds * nominalFps);
    }

    static Vec3 coneDirection(Vec3 axis, Vec3 right, Vec3 up, double yaw, double pitch) {
        Vec3 direction = axis.normalize()
            .add(right.normalize().scale(Math.tan(yaw)))
            .add(up.normalize().scale(Math.tan(pitch)));
        return direction.lengthSqr() < 1.0E-12 ? axis.normalize() : direction.normalize();
    }

    static double halfAngleRadians(double fullAngleDegrees) {
        return Math.toRadians(Math.clamp(fullAngleDegrees, 1.0, 90.0) * 0.5);
    }

    static double coneRadius(double forward, double halfAngle) {
        double angle = Math.clamp(halfAngle, Math.toRadians(0.5), Math.toRadians(45.0));
        return Math.max(MIN_CONE_RADIUS, Math.max(0.0, forward) * Math.tan(angle));
    }

    /**
     * Soft cone intensity. The non-zero near-field radius is intentional: Minecraft
     * asks for light at block centres, so a wall only centimetres in front of the
     * camera must still have at least one sample point inside the beam.
     */
    static double coneLuminance(Vec3 origin, Vec3 axis, Vec3 point, double range, double halfAngle) {
        if (origin == null || axis == null || point == null || axis.lengthSqr() < 1.0E-12 || range <= 0.0) return 0.0;

        Vec3 direction = axis.normalize();
        Vec3 delta = point.subtract(origin);
        double forward = delta.dot(direction);
        if (forward < 0.0 || forward > range) return 0.0;

        double radialSquared = Math.max(0.0, delta.lengthSqr() - forward * forward);
        double radial = Math.sqrt(radialSquared) / coneRadius(forward, halfAngle);
        if (radial >= 1.0) return 0.0;

        // Full intensity in the core, then a smooth edge instead of nine overlapping cylinders.
        double edgeStart = 0.68;
        double edgeFactor;
        if (radial <= edgeStart) {
            edgeFactor = 1.0;
        } else {
            double t = Math.clamp((radial - edgeStart) / (1.0 - edgeStart), 0.0, 1.0);
            double smoothStep = t * t * (3.0 - 2.0 * t);
            edgeFactor = 1.0 - smoothStep;
        }

        double distanceFactor = 1.0 - 0.30 * Math.clamp(forward / range, 0.0, 1.0);
        return Math.clamp(15.0 * edgeFactor * distanceFactor, 0.0, 15.0);
    }

    static int nearestConeSample(double x, double y, double[] sampleX, double[] sampleY) {
        if (sampleX.length == 0 || sampleX.length != sampleY.length) {
            throw new IllegalArgumentException("Cone sample arrays must be non-empty and equally sized");
        }
        int best = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; i < sampleX.length; i++) {
            double dx = x - sampleX[i];
            double dy = y - sampleY[i];
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * A ray hit blocks light behind its collision surface, but the hit block itself
     * must remain illuminated. This is what fixes the "flashlight turns off at a wall"
     * case without pushing the light source through the wall.
     */
    static boolean visibleAtSample(BlockPos target, double pointDistance, double hitDistance, long hitBlock) {
        if (hitBlock != NO_HIT_BLOCK && target.asLong() == hitBlock) return true;
        return pointDistance <= hitDistance + HIT_EPSILON;
    }
}
