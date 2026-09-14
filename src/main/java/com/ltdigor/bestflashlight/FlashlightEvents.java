package com.ltdigor.bestflashlight;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server-owned temporary illumination. All state is confined to the server thread. */
public final class FlashlightEvents {
    private static final Map<ResourceKey<Level>, Map<BlockPos, Map<UUID, Integer>>> LIGHT_OWNERS = new HashMap<>();
    private static final Map<UUID, PlayerBeam> PLAYER_BEAMS = new HashMap<>();

    // Integer points inside a radius-four disk give 49 directions, including the axis
    // and all four cone edges. Cost remains bounded even at maximum range and angle.
    private static final int DIRECTION_RADIUS = 4;
    private static final int MAX_RAY_CELLS = 128;

    // Vanilla block light itself is isotropic. For a headlamp, only the terminal cells
    // of the forward rays become emitters. Their brightness grows with distance so the
    // backwards spill at the player's position stays roughly at light level three.
    private static final double HEAD_MOUNTED_MIN_FORWARD = 0.75;
    private static final int HEAD_MOUNTED_BACKSPILL_LEVEL = 3;
    private static final int HEAD_MOUNTED_CLOSE_WALL_LEVEL = 4;
    private static final int HANDHELD_CLOSE_WALL_LEVEL = 8;

    private FlashlightEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onServerStopped);
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LampSource.returnInvalidFlashlights(player);
        LampSource source = player.isAlive() && !player.isSpectator() ? LampSource.select(player) : null;
        if (source == null) {
            clearPlayer(player);
            return;
        }

        if (!LampEnergy.hasPower(source.stack(), player)) {
            LampData.setEnabled(source.stack(), false);
            clearPlayer(player);
            return;
        }
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        Vec3 emitter = emitterOrigin(player, source, look);
        if (!FlashlightConfig.WORKS_UNDERWATER.get() && isSubmerged(level, emitter)) {
            clearPlayer(player);
            return;
        }

        // A hand/head model may geometrically overlap a nearby wall. That must not turn
        // the lamp off: start tracing from the eyes and let each beam ray stop at the wall.
        Vec3 eye = player.getEyePosition();
        Vec3 origin = emitterPathClear(player, level, emitter) ? emitter : eye;
        Map<BlockPos, Integer> next;
        if (source.headMounted()) {
            next = computeHeadMountedBeam(player, level, origin, look);
            if (next.isEmpty()) {
                // If the wall is inside the headlamp's near field there is no terminal
                // cell far enough in front. Keep one dim local source instead of making
                // the lamp blink off or recreating a bright 360-degree halo.
                next = closeWallFallback(level, eye, HEAD_MOUNTED_CLOSE_WALL_LEVEL);
            }
        } else {
            next = computeBeam(player, level, origin, look);
            if (next.isEmpty()) {
                next = closeWallFallback(level, eye, HANDHELD_CLOSE_WALL_LEVEL);
            }
        }

        if (next.isEmpty() || !LampEnergy.consume(source.stack(), player)) {
            clearPlayer(player);
            return;
        }

        UUID owner = player.getUUID();
        PlayerBeam previous = PLAYER_BEAMS.get(owner);
        if (previous != null && !previous.dimension().equals(level.dimension())) {
            clearPlayer(player);
            previous = null;
        }
        if (previous != null) {
            for (BlockPos pos : previous.positions()) {
                if (!next.containsKey(pos)) releaseLight(level, level.dimension(), pos, owner);
            }
        }
        for (var entry : next.entrySet()) {
            acquireLight(level, level.dimension(), entry.getKey(), owner, entry.getValue());
        }
        PLAYER_BEAMS.put(owner, new PlayerBeam(level.dimension(), new HashSet<>(next.keySet())));
    }

    private static Vec3 emitterOrigin(ServerPlayer player, LampSource source, Vec3 look) {
        Vec3 eye = player.getEyePosition();
        if (source.headMounted()) return eye.add(look.scale(0.45)).add(0.0, 0.15, 0.0);
        double yaw = Math.toRadians(player.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
        boolean rightHand = (player.getMainArm() == HumanoidArm.RIGHT) != source.offHand();
        return eye.add(look.scale(0.55)).add(right.scale(rightHand ? 0.35 : -0.35)).add(0.0, -0.45, 0.0);
    }

    private static boolean emitterPathClear(ServerPlayer player, ServerLevel level, Vec3 origin) {
        Vec3 eye = player.getEyePosition();
        CollisionContext context = CollisionContext.of(player);
        // The short offset spans at most twelve cells. Check the complete segment,
        // including its starting cell: thin obstacles can lie between eye and lamp
        // even when both endpoints occupy the same block.
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(eye), BlockPos.containing(origin))) {
            if (!loaded(level, pos)) return false;
            if (level.getBlockState(pos).getCollisionShape(level, pos, context).clip(eye, origin, pos) != null) return false;
        }
        return true;
    }

    private static boolean isSubmerged(ServerLevel level, Vec3 origin) {
        BlockPos pos = BlockPos.containing(origin);
        if (!loaded(level, pos)) return false;
        var fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && origin.y < pos.getY() + fluid.getHeight(level, pos);
    }

    private static Map<BlockPos, Integer> closeWallFallback(ServerLevel level, Vec3 eye, int lightLevel) {
        Map<BlockPos, Integer> result = new HashMap<>();
        BlockPos pos = BlockPos.containing(eye);
        if (loaded(level, pos) && acceptsLight(level.getBlockState(pos))) {
            result.put(pos, Math.clamp(lightLevel, 1, 15));
        }
        return result;
    }

    /** Trace a fixed disk of rays with exact voxel traversal and per-block shape clipping. */
    private static Map<BlockPos, Integer> computeBeam(ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 look) {
        Map<BlockPos, Integer> result = new HashMap<>();
        if (look.lengthSqr() < 1.0E-12) return result;
        Vec3 axis = look.normalize();
        Vec3 reference = Math.abs(axis.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 horizontal = axis.cross(reference).normalize();
        Vec3 vertical = horizontal.cross(axis).normalize();
        double range = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        double angle = Math.clamp(FlashlightConfig.CONE_ANGLE_DEGREES.get(), 1.0, 90.0);
        double radius = range * Math.tan(Math.toRadians(angle * 0.5));
        Vec3 endCenter = origin.add(axis.scale(range));
        CollisionContext context = CollisionContext.of(player);
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int u = -DIRECTION_RADIUS; u <= DIRECTION_RADIUS; u++) {
            for (int v = -DIRECTION_RADIUS; v <= DIRECTION_RADIUS; v++) {
                if (u * u + v * v > DIRECTION_RADIUS * DIRECTION_RADIUS) continue;
                Vec3 end = endCenter.add(horizontal.scale(radius * u / DIRECTION_RADIUS))
                    .add(vertical.scale(radius * v / DIRECTION_RADIUS));
                traceRay(level, origin, end, axis, range, context, states, result);
            }
        }
        return result;
    }

    /**
     * Head-mounted light uses only the last open cell of each cone ray. A full string of
     * bright block-light emitters would inevitably illuminate the player from all sides.
     */
    private static Map<BlockPos, Integer> computeHeadMountedBeam(ServerPlayer player, ServerLevel level,
                                                                 Vec3 origin, Vec3 look) {
        Map<BlockPos, Integer> result = new HashMap<>();
        if (look.lengthSqr() < 1.0E-12) return result;
        Vec3 axis = look.normalize();
        Vec3 reference = Math.abs(axis.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 horizontal = axis.cross(reference).normalize();
        Vec3 vertical = horizontal.cross(axis).normalize();
        double range = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        double angle = Math.clamp(FlashlightConfig.CONE_ANGLE_DEGREES.get(), 1.0, 90.0);
        double radius = range * Math.tan(Math.toRadians(angle * 0.5));
        Vec3 endCenter = origin.add(axis.scale(range));
        CollisionContext context = CollisionContext.of(player);
        Map<BlockPos, BlockState> states = new HashMap<>();
        for (int u = -DIRECTION_RADIUS; u <= DIRECTION_RADIUS; u++) {
            for (int v = -DIRECTION_RADIUS; v <= DIRECTION_RADIUS; v++) {
                if (u * u + v * v > DIRECTION_RADIUS * DIRECTION_RADIUS) continue;
                Vec3 end = endCenter.add(horizontal.scale(radius * u / DIRECTION_RADIUS))
                    .add(vertical.scale(radius * v / DIRECTION_RADIUS));
                traceTerminalRay(level, origin, end, axis, range, context, states, result);
            }
        }
        return result;
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
            if (!loaded(level, pos)) return;
            BlockState state = states.computeIfAbsent(pos, level::getBlockState);
            if (state.getCollisionShape(level, pos, context).clip(origin, end, pos) != null) return;
            double forward = pos.getCenter().subtract(origin).dot(axis);
            if (forward >= 0.0 && forward <= range && acceptsLight(state)) {
                int brightness = 15 - (int) Math.floor(9.0 * forward / range);
                result.merge(pos, brightness, Math::max);
            }

            double next = Math.min(crossX, Math.min(crossY, crossZ));
            if (next > 1.0) return;
            // Advance one boundary at a time. Ties visit the adjacent boundary cell
            // too, preventing diagonal rays from slipping through touching solids.
            if (crossX <= crossY && crossX <= crossZ) {
                x += stepX;
                crossX += strideX;
            } else if (crossY <= crossZ) {
                y += stepY;
                crossY += strideY;
            } else {
                z += stepZ;
                crossZ += strideZ;
            }
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
            if (!loaded(level, pos)) break;
            BlockState state = states.computeIfAbsent(pos, level::getBlockState);
            if (state.getCollisionShape(level, pos, context).clip(origin, end, pos) != null) break;
            double forward = pos.getCenter().subtract(origin).dot(axis);
            if (forward >= HEAD_MOUNTED_MIN_FORWARD && forward <= range && acceptsLight(state)) {
                terminal = pos;
                terminalForward = forward;
            }

            double next = Math.min(crossX, Math.min(crossY, crossZ));
            if (next > 1.0) break;
            if (crossX <= crossY && crossX <= crossZ) {
                x += stepX;
                crossX += strideX;
            } else if (crossY <= crossZ) {
                y += stepY;
                crossY += strideY;
            } else {
                z += stepZ;
                crossZ += strideZ;
            }
        }

        if (terminal != null) {
            // A source N blocks ahead with level (N + backspill) contributes roughly
            // 'backspill' light back at the player while still lighting the target strongly.
            int brightness = Math.clamp(
                HEAD_MOUNTED_BACKSPILL_LEVEL + (int) Math.ceil(terminalForward),
                1,
                15
            );
            result.merge(terminal, brightness, Math::max);
        }
    }

    private static double firstCrossing(double start, int cell, double delta, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        return (cell + (step > 0 ? 1.0 : 0.0) - start) / delta;
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    private static boolean acceptsLight(BlockState state) {
        return state.isAir() || state.is(FlashlightMod.FLASHLIGHT_LIGHT.get())
            || (state.is(Blocks.WATER) && state.getFluidState().isSource());
    }

    private static void acquireLight(ServerLevel level, ResourceKey<Level> dimension, BlockPos pos, UUID owner, int lightLevel) {
        if (!loaded(level, pos)) return;
        BlockState current = level.getBlockState(pos);
        if (!acceptsLight(current)) {
            forgetLight(dimension, pos);
            return;
        }
        Map<BlockPos, Map<UUID, Integer>> dimensionLights = LIGHT_OWNERS.computeIfAbsent(dimension, ignored -> new HashMap<>());
        Map<UUID, Integer> owners = dimensionLights.computeIfAbsent(pos.immutable(), ignored -> new HashMap<>());
        owners.put(owner, Math.clamp(lightLevel, 1, 15));
        int strongest = owners.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        boolean waterlogged = current.is(Blocks.WATER)
            || (current.is(FlashlightMod.FLASHLIGHT_LIGHT.get()) && current.getValue(FlashlightLightBlock.WATERLOGGED));
        BlockState desired = FlashlightMod.FLASHLIGHT_LIGHT.get().defaultBlockState()
            .setValue(FlashlightLightBlock.LEVEL, strongest).setValue(FlashlightLightBlock.WATERLOGGED, waterlogged);
        if (current != desired) level.setBlock(pos, desired, FlashlightLightBlock.UPDATE_FLAGS);
    }

    private static void releaseLight(ServerLevel level, ResourceKey<Level> dimension, BlockPos pos, UUID owner) {
        Map<BlockPos, Map<UUID, Integer>> dimensionLights = LIGHT_OWNERS.get(dimension);
        if (dimensionLights == null) return;
        Map<UUID, Integer> owners = dimensionLights.get(pos);
        if (owners == null) return;
        owners.remove(owner);
        if (owners.isEmpty()) {
            forgetLight(dimension, pos);
            if (level != null && loaded(level, pos)) FlashlightLightBlock.restore(level, pos);
            return;
        }
        if (level == null || !loaded(level, pos)) return;
        BlockState current = level.getBlockState(pos);
        if (!current.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
            forgetLight(dimension, pos);
            return;
        }
        int strongest = owners.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        if (current.getValue(FlashlightLightBlock.LEVEL) != strongest) {
            level.setBlock(pos, current.setValue(FlashlightLightBlock.LEVEL, strongest), FlashlightLightBlock.UPDATE_FLAGS);
        }
    }

    public static boolean isTrackedLight(ResourceKey<Level> dimension, BlockPos pos) {
        Map<BlockPos, Map<UUID, Integer>> lights = LIGHT_OWNERS.get(dimension);
        return lights != null && lights.containsKey(pos);
    }

    static void forgetLight(ResourceKey<Level> dimension, BlockPos pos) {
        Map<BlockPos, Map<UUID, Integer>> lights = LIGHT_OWNERS.get(dimension);
        if (lights != null) {
            lights.remove(pos);
            if (lights.isEmpty()) LIGHT_OWNERS.remove(dimension);
        }
    }

    private static void clearPlayer(ServerPlayer player) {
        PlayerBeam previous = PLAYER_BEAMS.remove(player.getUUID());
        if (previous == null) return;
        ServerLevel level = player.getServer().getLevel(previous.dimension());
        for (BlockPos pos : previous.positions()) releaseLight(level, previous.dimension(), pos, player.getUUID());
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearPlayer(player);
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearPlayer(player);
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearPlayer(player);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LIGHT_OWNERS.remove(level.dimension());
            PLAYER_BEAMS.values().removeIf(beam -> beam.dimension().equals(level.dimension()));
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        LIGHT_OWNERS.clear();
        PLAYER_BEAMS.clear();
    }

    private record PlayerBeam(ResourceKey<Level> dimension, Set<BlockPos> positions) {}
}
