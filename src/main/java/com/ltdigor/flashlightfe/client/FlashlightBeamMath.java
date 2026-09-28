package com.ltdigor.flashlightfe.client;

import net.minecraft.world.phys.Vec3;

/** Pure beam geometry kept separate from rendering so it can be unit-tested. */
final class FlashlightBeamMath {
    private static final double MIN_CONE_RADIUS = 0.875;
    private static final double NEAR_FIELD_BACKWARD = 0.5;

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
        return coneLuminance(origin, axis, point, range, halfAngle, 15, 0.35);
    }

    static double coneLuminance(Vec3 origin, Vec3 axis, Vec3 point, double range,
                                double halfAngle, int brightness, double softness) {
        if (origin == null || axis == null || point == null || axis.lengthSqr() < 1.0E-12 || range <= 0.0) return 0.0;

        Vec3 delta = point.subtract(origin);
        return sampleLuminance(delta.x, delta.y, delta.z, axis.normalize(), range,
            Math.tan(Math.clamp(halfAngle, Math.toRadians(0.5), Math.toRadians(45))), brightness, softness);
    }

    /** Allocation-free inner loop; axis is normalized and slope is tan(halfAngle). */
    static double sampleLuminance(double x, double y, double z, Vec3 axis, double range,
                                  double slope, int brightness, double softness) {
        double forward = x * axis.x + y * axis.y + z * axis.z;
        if (forward <= -NEAR_FIELD_BACKWARD || forward > range || range <= 0) return 0;
        double radialSquared = Math.max(0, x * x + y * y + z * z - forward * forward);
        double radius = Math.max(MIN_CONE_RADIUS, Math.max(0.0, forward) * slope);
        if (radialSquared >= radius * radius) return 0;
        double peak = Math.clamp(brightness, 1, 15);
        double edgeWidth = Math.clamp(softness, 0.0, 1.0);
        double radial = Math.sqrt(radialSquared) / radius;
        double radialFactor = edgeWidth <= 0 ? 1.0
            : Math.pow(1.0 - radial * radial, 2.0 * edgeWidth)
                * smoothstep(Math.min(1.0, (1.0 - radial) / edgeWidth));
        double endWidth = Math.min(range, Math.max(1.0, range * 0.2));
        double axialFactor = smoothstep(Math.min(1.0, (range - forward) / endWidth));
        // A block centre immediately behind the emitter can be the only air sample
        // before a close wall. Fade that sub-block near field to zero at half a block.
        double nearFactor = forward < 0
            ? smoothstep((forward + NEAR_FIELD_BACKWARD) / NEAR_FIELD_BACKWARD) : 1.0;
        return peak * radialFactor * axialFactor * nearFactor;
    }

    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** Cone radius with a small bounded near field for block-centre samples. */
    static double illuminationRadius(double forward, double halfAngle) {
        return coneRadius(forward, halfAngle);
    }

}
