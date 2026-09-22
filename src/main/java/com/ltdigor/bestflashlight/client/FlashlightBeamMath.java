package com.ltdigor.bestflashlight.client;

import net.minecraft.world.phys.Vec3;

/** Pure beam geometry kept separate from rendering so it can be unit-tested. */
final class FlashlightBeamMath {
    private static final double MIN_CONE_RADIUS = 0.875;
    // LDL's native held lights fade 15 levels over 7.75 blocks. A sub-block
    // angular penumbra aliases into bright squares on the same block-centre grid.
    private static final double LIGHT_FALLOFF = 15.0 / 7.75;
    private static final double CORE_FRACTION = 0.68;

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
        if (origin == null || axis == null || point == null || axis.lengthSqr() < 1.0E-12 || range <= 0.0) return 0.0;

        Vec3 delta = point.subtract(origin);
        return sampleLuminance(delta.x, delta.y, delta.z, axis.normalize(), range,
            Math.tan(Math.clamp(halfAngle, Math.toRadians(0.5), Math.toRadians(45))));
    }

    /** Allocation-free inner loop; axis is normalized and slope is tan(halfAngle). */
    static double sampleLuminance(double x, double y, double z, Vec3 axis, double range, double slope) {
        double forward = x * axis.x + y * axis.y + z * axis.z;
        if (forward < 0 || forward > range || range <= 0) return 0;
        double radialSquared = Math.max(0, x * x + y * y + z * z - forward * forward);
        double coreRadius = CORE_FRACTION * Math.max(MIN_CONE_RADIUS, forward * slope);
        double peak = 15.0 * (1.0 - 0.30 * forward / range);
        double outerRadius = coreRadius + peak / LIGHT_FALLOFF;
        if (radialSquared >= outerRadius * outerRadius) return 0;
        double radialFalloff = Math.max(0, Math.sqrt(radialSquared) - coreRadius) * LIGHT_FALLOFF;
        // Fade toward the range plane as well; crossing it must not drop a bright
        // sample straight to zero during flight.
        return Math.max(0, Math.min(peak - radialFalloff, (range - forward) * LIGHT_FALLOFF));
    }

    /** Conservative radius including the native-light-like soft spill. */
    static double illuminationRadius(double forward, double halfAngle) {
        return CORE_FRACTION * coneRadius(forward, halfAngle) + 15.0 / LIGHT_FALLOFF;
    }

}
