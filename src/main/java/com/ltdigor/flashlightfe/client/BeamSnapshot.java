package com.ltdigor.flashlightfe.client;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Fully built on the client thread; safe to read from LDL chunk-render workers. */
final class BeamSnapshot {
    private final Vec3 origin;
    private final Vec3 axis;
    private final double range;
    private final double halfAngle;
    private final int[] bounds;
    private final Long2DoubleOpenHashMap light;
    private final int traceCount;

    private BeamSnapshot(Vec3 origin, Vec3 axis, double range, double halfAngle,
                         int[] bounds, Long2DoubleOpenHashMap light, int traceCount) {
        this.origin = origin;
        this.axis = axis;
        this.range = range;
        this.halfAngle = halfAngle;
        this.bounds = bounds;
        this.light = light;
        this.traceCount = traceCount;
    }

    static BeamSnapshot empty() {
        return new BeamSnapshot(Vec3.ZERO, new Vec3(0, 0, 1), 0, 0,
            new int[6], new Long2DoubleOpenHashMap(), 0);
    }

    static BeamSnapshot build(Vec3 origin, Vec3 direction, double range, double halfAngle,
                              Predicate<BlockPos> visible) {
        Vec3 axis = direction.normalize();
        int[] bounds = bounds(origin, axis, range, halfAngle);
        Long2DoubleOpenHashMap light = new Long2DoubleOpenHashMap();
        BlockPos.MutableBlockPos target = new BlockPos.MutableBlockPos();
        int traces = 0;
        double slope = Math.tan(halfAngle);
        for (int x = bounds[0]; x <= bounds[3]; x++) {
            for (int y = bounds[1]; y <= bounds[4]; y++) {
                for (int z = bounds[2]; z <= bounds[5]; z++) {
                    double luminance = FlashlightBeamMath.sampleLuminance(
                        x + 0.5 - origin.x, y + 0.5 - origin.y, z + 0.5 - origin.z, axis, range, slope);
                    if (luminance <= 0) continue;
                    traces++;
                    target.set(x, y, z);
                    if (visible.test(target)) light.put(target.asLong(), luminance);
                }
            }
        }
        return new BeamSnapshot(origin, axis, range, halfAngle, bounds, light, traces);
    }

    double lightAt(BlockPos pos) { return light.get(pos.asLong()); }
    int[] bounds() { return bounds.clone(); }
    int traceCount() { return traceCount; }
    int sampleCount() { return light.size(); }

    boolean sameLight(BeamSnapshot other) {
        return java.util.Arrays.equals(bounds, other.bounds) && light.equals(other.light);
    }

    boolean matches(Vec3 newOrigin, Vec3 newAxis, double newRange, double newHalfAngle) {
        return origin.distanceToSqr(newOrigin) <= 0.0025 * 0.0025
            && axis.dot(newAxis) >= Math.cos(Math.toRadians(0.10))
            && Double.compare(range, newRange) == 0 && Double.compare(halfAngle, newHalfAngle) == 0;
    }

    private static int[] bounds(Vec3 origin, Vec3 axis, double range, double halfAngle) {
        Vec3 end = origin.add(axis.scale(range));
        double startRadius = FlashlightBeamMath.illuminationRadius(0, halfAngle);
        double endRadius = FlashlightBeamMath.illuminationRadius(range, halfAngle);
        double xExtent = Math.sqrt(Math.max(0, 1 - axis.x * axis.x));
        double yExtent = Math.sqrt(Math.max(0, 1 - axis.y * axis.y));
        double zExtent = Math.sqrt(Math.max(0, 1 - axis.z * axis.z));
        // Coordinates are block centres, not corners. Conservative inclusive integer bounds.
        return new int[]{
            (int) Math.floor(Math.min(origin.x - startRadius * xExtent, end.x - endRadius * xExtent) - 0.5),
            (int) Math.floor(Math.min(origin.y - startRadius * yExtent, end.y - endRadius * yExtent) - 0.5),
            (int) Math.floor(Math.min(origin.z - startRadius * zExtent, end.z - endRadius * zExtent) - 0.5),
            (int) Math.ceil(Math.max(origin.x + startRadius * xExtent, end.x + endRadius * xExtent) - 0.5),
            (int) Math.ceil(Math.max(origin.y + startRadius * yExtent, end.y + endRadius * yExtent) - 0.5),
            (int) Math.ceil(Math.max(origin.z + startRadius * zExtent, end.z + endRadius * zExtent) - 0.5)
        };
    }
}
