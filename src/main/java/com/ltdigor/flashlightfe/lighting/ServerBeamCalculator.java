package com.ltdigor.flashlightfe.lighting;

import com.ltdigor.flashlightfe.FlashlightConfig;
import com.ltdigor.flashlightfe.FlashlightMod;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Pure server-side beam geometry: reads the world through non-loading queries and never
 * mutates it. Output is an immutable {@link BeamFrame} of desired illumination.
 */
public final class ServerBeamCalculator {
    // Integer points inside a radius-three disk give 29 directions, including the
    // axis and cone edges. This keeps fallback quality while cutting server ray work
    // by about 41% compared with the previous 49-ray disk.
    private static final int DIRECTION_RADIUS = 3;
    private static final int MAX_RAY_CELLS = 128;
    // Vanilla block light is isotropic. A headlamp therefore only publishes the terminal
    // cells of its forward rays; their brightness grows with distance so the backwards
    // spill at the wearer stays around light level three instead of a personal halo.
    static final double HEAD_MOUNTED_MIN_FORWARD = 0.75;
    // Keep handheld emitters off the player's own cells: a level 15 source at the eyes
    // spreads vanilla block light in every direction and reads as a personal glow.
    static final double HANDHELD_MIN_FORWARD = 2.0;
    private static final int HEAD_MOUNTED_BACKSPILL_LEVEL = 3;

    private ServerBeamCalculator() {}

    public static int handheldBrightness(double forward, double range) {
        return Math.clamp(15 - (int) Math.floor(9.0 * forward / range), 1, 15);
    }

    public static int headBrightness(double forward) {
        return Math.clamp(HEAD_MOUNTED_BACKSPILL_LEVEL + (int) Math.ceil(forward), 1, 15);
    }

    public static double configuredRange() {
        return Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
    }

    public static double configuredFullAngleDegrees() {
        return Math.clamp(FlashlightConfig.CONE_ANGLE_DEGREES.get(), 1.0, 90.0);
    }

    public static BeamFrame handheld(ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 look) {
        Map<BlockPos, Integer> cells = new HashMap<>();
        traceCone(player, level, origin, look, false, cells);
        return BeamFrame.of(level.dimension(), cells);
    }

    public static BeamFrame headMounted(ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 look) {
        Map<BlockPos, Integer> cells = new HashMap<>();
        traceCone(player, level, origin, look, true, cells);
        return BeamFrame.of(level.dimension(), cells);
    }

    /** Short probe for the close-wall case: the last hostable cell just in front of the eye. */
    public static BeamFrame closeWall(ServerPlayer player, ServerLevel level, Vec3 eye, Vec3 look, int lightLevel) {
        Vec3 axis = look.lengthSqr() < 1.0E-12 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
        BlockPos best = fallbackCellAlong(player, level, eye, axis);
        if (best == null) best = fallbackCellAlong(player, level, eye, axis.scale(-1.0));
        if (best == null) return BeamFrame.empty(level.dimension());
        return BeamFrame.of(level.dimension(), Map.of(best.immutable(), Math.clamp(lightLevel, 1, 15)));
    }

    /** The eye-to-emitter segment must be free of colliders, otherwise the beam starts at the eye. */
    public static boolean emitterPathClear(ServerPlayer player, ServerLevel level, Vec3 eye, Vec3 origin) {
        CollisionContext context = CollisionContext.of(player);
        // The short offset spans at most twelve cells. Check the complete segment,
        // including its starting cell: thin obstacles can lie between eye and lamp
        // even when both endpoints occupy the same block.
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(eye), BlockPos.containing(origin))) {
            if (!isLoaded(level, pos)) return false;
            if (level.getBlockState(pos).getCollisionShape(level, pos, context).clip(eye, origin, pos) != null) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSubmerged(ServerLevel level, Vec3 origin) {
        BlockPos pos = BlockPos.containing(origin);
        if (!isLoaded(level, pos)) return false;
        var fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && origin.y < pos.getY() + fluid.getHeight(level, pos);
    }

    public static boolean isLoaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static void traceCone(ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 look,
                                  boolean headMounted, Map<BlockPos, Integer> cells) {
        if (look.lengthSqr() < 1.0E-12) return;
        Vec3 axis = look.normalize();
        Vec3 reference = Math.abs(axis.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 horizontal = axis.cross(reference).normalize();
        Vec3 vertical = horizontal.cross(axis).normalize();
        double range = configuredRange();
        double radius = range * Math.tan(Math.toRadians(configuredFullAngleDegrees() * 0.5));
        Vec3 endCenter = origin.add(axis.scale(range));
        CollisionContext context = CollisionContext.of(player);
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int u = -DIRECTION_RADIUS; u <= DIRECTION_RADIUS; u++) {
            for (int v = -DIRECTION_RADIUS; v <= DIRECTION_RADIUS; v++) {
                if (u * u + v * v > DIRECTION_RADIUS * DIRECTION_RADIUS) continue;
                Vec3 end = endCenter.add(horizontal.scale(radius * u / DIRECTION_RADIUS))
                    .add(vertical.scale(radius * v / DIRECTION_RADIUS));
                if (headMounted) {
                    traceTerminalRay(level, origin, end, axis, range, context, states, cells);
                } else {
                    traceRay(level, origin, end, axis, range, context, states, cells);
                }
            }
        }
    }

    private static void traceRay(ServerLevel level, Vec3 origin, Vec3 end, Vec3 axis, double range,
                                 CollisionContext context, Map<BlockPos, BlockState> states,
                                 Map<BlockPos, Integer> result) {
        Vec3 delta = end.subtract(origin);
        BlockPos cell = BlockPos.containing(origin);
        int x = cell.getX(), y = cell.getY(), z = cell.getZ();
        int stepX = (int) Math.signum(delta.x), stepY = (int) Math.signum(delta.y), stepZ = (int) Math.signum(delta.z);
        double crossX = firstCrossing(origin.x, x, delta.x, stepX);
        double crossY = firstCrossing(origin.y, y, delta.y, stepY);
        double crossZ = firstCrossing(origin.z, z, delta.z, stepZ);
        double strideX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / delta.x);
        double strideY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / delta.y);
        double strideZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / delta.z);

        for (int visited = 0; visited < MAX_RAY_CELLS; visited++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isLoaded(level, pos)) return;
            BlockState state = states.computeIfAbsent(pos, level::getBlockState);
            if (state.getCollisionShape(level, pos, context).clip(origin, end, pos) != null) return;
            double forward = pos.getCenter().subtract(origin).dot(axis);
            if (forward >= HANDHELD_MIN_FORWARD && forward <= range && canHostTransientLight(state)) {
                result.merge(pos, handheldBrightness(forward, range), Math::max);
            }

            double next = Math.min(crossX, Math.min(crossY, crossZ));
            if (next > 1.0) return;
            int tiedAxes = tiedAxes(crossX, crossY, crossZ, next);
            if (boundaryCollision(level, origin, end, context, states,
                                  x, y, z, stepX, stepY, stepZ, tiedAxes)) return;
            if ((tiedAxes & 1) != 0) { x += stepX; crossX += strideX; }
            if ((tiedAxes & 2) != 0) { y += stepY; crossY += strideY; }
            if ((tiedAxes & 4) != 0) { z += stepZ; crossZ += strideZ; }
        }
    }

    private static void traceTerminalRay(ServerLevel level, Vec3 origin, Vec3 end, Vec3 axis, double range,
                                         CollisionContext context, Map<BlockPos, BlockState> states,
                                         Map<BlockPos, Integer> result) {
        Vec3 delta = end.subtract(origin);
        BlockPos cell = BlockPos.containing(origin);
        int x = cell.getX(), y = cell.getY(), z = cell.getZ();
        int stepX = (int) Math.signum(delta.x), stepY = (int) Math.signum(delta.y), stepZ = (int) Math.signum(delta.z);
        double crossX = firstCrossing(origin.x, x, delta.x, stepX);
        double crossY = firstCrossing(origin.y, y, delta.y, stepY);
        double crossZ = firstCrossing(origin.z, z, delta.z, stepZ);
        double strideX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / delta.x);
        double strideY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / delta.y);
        double strideZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / delta.z);
        BlockPos terminal = null;
        double terminalForward = 0.0;

        for (int visited = 0; visited < MAX_RAY_CELLS; visited++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isLoaded(level, pos)) break;
            BlockState state = states.computeIfAbsent(pos, level::getBlockState);
            if (state.getCollisionShape(level, pos, context).clip(origin, end, pos) != null) break;
            double forward = pos.getCenter().subtract(origin).dot(axis);
            if (forward >= HEAD_MOUNTED_MIN_FORWARD && forward <= range && canHostTransientLight(state)) {
                terminal = pos;
                terminalForward = forward;
            }

            double next = Math.min(crossX, Math.min(crossY, crossZ));
            if (next > 1.0) break;
            int tiedAxes = tiedAxes(crossX, crossY, crossZ, next);
            if (boundaryCollision(level, origin, end, context, states,
                                  x, y, z, stepX, stepY, stepZ, tiedAxes)) break;
            if ((tiedAxes & 1) != 0) { x += stepX; crossX += strideX; }
            if ((tiedAxes & 2) != 0) { y += stepY; crossY += strideY; }
            if ((tiedAxes & 4) != 0) { z += stepZ; crossZ += strideZ; }
        }

        if (terminal != null) {
            result.merge(terminal, headBrightness(terminalForward), Math::max);
        }
    }

    private static BlockPos fallbackCellAlong(ServerPlayer player, ServerLevel level, Vec3 eye, Vec3 direction) {
        Vec3 end = eye.add(direction.scale(1.25));
        var hit = level.clip(new net.minecraft.world.level.ClipContext(
            eye, end,
            net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            player
        ));
        double maxDistance = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
            ? 1.25
            : Math.max(0.0, eye.distanceTo(hit.getLocation()) - 1.0E-4);

        BlockPos best = null;
        BlockPos previous = null;
        for (double distance = 0.0; distance <= maxDistance + 1.0E-9; distance += 0.125) {
            BlockPos pos = BlockPos.containing(eye.add(direction.scale(distance)));
            if (pos.equals(previous)) continue;
            previous = pos;
            if (!isLoaded(level, pos)) break;
            if (canHostTransientLight(level.getBlockState(pos))) best = pos;
        }
        return best;
    }

    /**
     * Only air, water and an existing own carrier may host transient light. A generic
     * replaceable check would also swallow plants, snow layers and modded objects.
     */
    public static boolean canHostTransientLight(BlockState state) {
        return state.isAir() || state.is(FlashlightMod.FLASHLIGHT_LIGHT.get()) || state.is(Blocks.WATER);
    }

    private static double firstCrossing(double start, int cell, double delta, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        return (cell + (step > 0 ? 1.0 : 0.0) - start) / delta;
    }

    private static int tiedAxes(double crossX, double crossY, double crossZ, double next) {
        int axes = 0;
        if (crossX == next) axes |= 1;
        if (crossY == next) axes |= 2;
        if (crossZ == next) axes |= 4;
        return axes;
    }

    /** Corner cells of the voxel step are tested too, so thin walls cannot be tunneled. */
    private static boolean boundaryCollision(ServerLevel level, Vec3 origin, Vec3 end, CollisionContext context,
                                             Map<BlockPos, BlockState> states,
                                             int x, int y, int z, int stepX, int stepY, int stepZ,
                                             int tiedAxes) {
        if (Integer.bitCount(tiedAxes) <= 1) return false;
        for (int subset = tiedAxes; subset > 0; subset = (subset - 1) & tiedAxes) {
            if (subset == tiedAxes) continue; // The fully advanced cell is visited next.
            BlockPos pos = new BlockPos(
                x + ((subset & 1) != 0 ? stepX : 0),
                y + ((subset & 2) != 0 ? stepY : 0),
                z + ((subset & 4) != 0 ? stepZ : 0)
            );
            if (!isLoaded(level, pos)) return true;
            BlockState state = states.computeIfAbsent(pos, level::getBlockState);
            if (state.getCollisionShape(level, pos, context).clip(origin, end, pos) != null) return true;
        }
        return false;
    }
}
