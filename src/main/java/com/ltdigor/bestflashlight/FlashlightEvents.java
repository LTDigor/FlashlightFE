package com.ltdigor.bestflashlight;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Server-owned temporary illumination. All state is confined to the server thread. */
public final class FlashlightEvents {
    private static final Map<ResourceKey<Level>, Map<BlockPos, Map<UUID, Integer>>> LIGHT_OWNERS = new HashMap<>();
    private static final Map<UUID, PlayerBeam> PLAYER_BEAMS = new HashMap<>();
    private static final Map<UUID, BeamCache> BEAM_CACHE = new HashMap<>();
    private static final ConcurrentLinkedQueue<CleanupRearm> PENDING_CLEANUP_REARM =
        new ConcurrentLinkedQueue<>();

    // Integer points inside a radius-three disk give 29 directions, including the
    // axis and cone edges. This keeps fallback quality while cutting server ray work
    // by about 41% compared with the previous 49-ray disk.
    private static final int DIRECTION_RADIUS = 3;
    private static final int MAX_RAY_CELLS = 128;
    private static final int STATIC_BEAM_REFRESH_TICKS = 4;
    private static final double CACHE_POSITION_EPSILON_SQR = 0.01 * 0.01;
    private static final double CACHE_DIRECTION_DOT = Math.cos(Math.toRadians(0.20));

    // Vanilla block light itself is isotropic. For a headlamp, only the terminal cells
    // of the forward rays become emitters. Their brightness grows with distance so the
    // backwards spill at the player's position stays roughly at light level three.
    private static final double HEAD_MOUNTED_MIN_FORWARD = 0.75;
    private static final int HEAD_MOUNTED_BACKSPILL_LEVEL = 3;
    private static final int HEAD_MOUNTED_CLOSE_WALL_LEVEL = 4;
    private static final int HANDHELD_CLOSE_WALL_LEVEL = 15;

    private FlashlightEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(FlashlightEvents::onServerStopped);
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        LampSource.returnInvalidFlashlights(player);
        if (!player.isAlive() || player.isSpectator()) {
            clearPlayer(player);
            return;
        }

        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle().normalize();
        LampSource source = LampSource.select(player, candidate -> {
            if (!LampEnergy.hasPower(candidate.stack(), player)) {
                LampData.setEnabled(candidate.stack(), false);
                return false;
            }
            Vec3 candidateEmitter = emitterOrigin(player, candidate, look);
            return FlashlightConfig.WORKS_UNDERWATER.get() || !isSubmerged(level, candidateEmitter);
        });
        if (source == null) {
            clearPlayer(player);
            return;
        }
        Vec3 emitter = emitterOrigin(player, source, look);
        UUID owner = player.getUUID();

        // In an all-LDL session every client renders every tracked player's cone.
        // Keep FE and source validation authoritative on the server, but skip the
        // temporary block-light beam entirely.
        if (!DynamicLightCoordination.useServerFallback(player)) {
            clearPlayer(player);
            BEAM_CACHE.remove(owner);
            if (LampEnergy.consume(source.stack(), player)) {
                FlashlightOwnerSync.syncDrain(player, source);
            }
            return;
        }

        Vec3 eye = player.getEyePosition();
        double configuredRange = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        double configuredAngle = Math.clamp(FlashlightConfig.CONE_ANGLE_DEGREES.get(), 1.0, 90.0);
        long gameTick = level.getGameTime();
        BeamCache cached = BEAM_CACHE.get(owner);
        PlayerBeam previous = PLAYER_BEAMS.get(owner);
        boolean reuse = cached != null
            && previous != null
            && cached.dimension().equals(level.dimension())
            && cached.headMounted() == source.headMounted()
            && cached.offHand() == source.offHand()
            && cached.eye().distanceToSqr(eye) <= CACHE_POSITION_EPSILON_SQR
            && cached.emitter().distanceToSqr(emitter) <= CACHE_POSITION_EPSILON_SQR
            && cached.look().dot(look) >= CACHE_DIRECTION_DOT
            && Double.compare(cached.range(), configuredRange) == 0
            && Double.compare(cached.fullAngleDegrees(), configuredAngle) == 0
            && gameTick - cached.computedAtTick() < STATIC_BEAM_REFRESH_TICKS;

        if (reuse) {
            if (!LampEnergy.consume(source.stack(), player)) {
                clearPlayer(player);
            } else {
                FlashlightOwnerSync.syncDrain(player, source);
            }
            return;
        }

        // A hand/head model may geometrically overlap a nearby wall. That must not turn
        // the lamp off: start tracing from the eyes and let each beam ray stop at the wall.
        Vec3 origin = emitterPathClear(player, level, emitter) ? emitter : eye;
        Map<BlockPos, Integer> next;
        if (source.headMounted()) {
            next = computeHeadMountedBeam(player, level, origin, look);
            if (next.isEmpty()) {
                next = closeWallFallback(player, level, eye, look, HEAD_MOUNTED_CLOSE_WALL_LEVEL);
            }
        } else {
            next = computeBeam(player, level, origin, look);
            if (next.isEmpty()) {
                next = closeWallFallback(player, level, eye, look, HANDHELD_CLOSE_WALL_LEVEL);
            }
        }

        if (next.isEmpty() || !LampEnergy.consume(source.stack(), player)) {
            BEAM_CACHE.remove(owner);
            clearPlayer(player);
            return;
        }
        FlashlightOwnerSync.syncDrain(player, source);

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
        BEAM_CACHE.put(owner, new BeamCache(
            level.dimension(), eye, emitter, look, source.headMounted(), source.offHand(),
            configuredRange, configuredAngle, gameTick
        ));
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

    private static Map<BlockPos, Integer> closeWallFallback(ServerPlayer player, ServerLevel level,
                                                               Vec3 eye, Vec3 look, int lightLevel) {
        Map<BlockPos, Integer> result = new HashMap<>();
        Vec3 axis = look.lengthSqr() < 1.0E-12 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();

        BlockPos best = fallbackCellAlong(player, level, eye, axis);
        if (best == null) best = fallbackCellAlong(player, level, eye, axis.scale(-1.0));

        if (best != null) result.put(best.immutable(), Math.clamp(lightLevel, 1, 15));
        return result;
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
            if (!loaded(level, pos)) break;
            if (acceptsLight(level.getBlockState(pos))) best = pos;
        }
        return best;
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
        return state.isAir() || state.is(FlashlightMod.FLASHLIGHT_LIGHT.get()) || state.is(Blocks.WATER);
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
        int strongest = strongest(owners);
        boolean replacingWater = current.is(Blocks.WATER);
        boolean waterlogged = replacingWater
            || (current.is(FlashlightMod.FLASHLIGHT_LIGHT.get()) && current.getValue(FlashlightLightBlock.WATERLOGGED));
        int waterLevel = replacingWater
            ? current.getValue(LiquidBlock.LEVEL)
            : current.is(FlashlightMod.FLASHLIGHT_LIGHT.get())
                ? current.getValue(FlashlightLightBlock.WATER_LEVEL)
                : 0;
        BlockState desired = FlashlightMod.FLASHLIGHT_LIGHT.get().defaultBlockState()
            .setValue(FlashlightLightBlock.LEVEL, strongest)
            .setValue(FlashlightLightBlock.WATERLOGGED, waterlogged)
            .setValue(FlashlightLightBlock.WATER_LEVEL, waterLevel);
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
        int strongest = strongest(owners);
        if (current.getValue(FlashlightLightBlock.LEVEL) != strongest) {
            level.setBlock(pos, current.setValue(FlashlightLightBlock.LEVEL, strongest), FlashlightLightBlock.UPDATE_FLAGS);
        }
    }

    private static int strongest(Map<UUID, Integer> owners) {
        int strongest = 0;
        for (int value : owners.values()) strongest = Math.max(strongest, value);
        return strongest;
    }

    public static boolean isTrackedLight(ResourceKey<Level> dimension, BlockPos pos) {
        Map<BlockPos, Map<UUID, Integer>> lights = LIGHT_OWNERS.get(dimension);
        return lights != null && lights.containsKey(pos);
    }

    static void forgetLight(ResourceKey<Level> dimension, BlockPos pos) {
        Map<BlockPos, Map<UUID, Integer>> lights = LIGHT_OWNERS.get(dimension);
        if (lights != null) {
            Map<UUID, Integer> removedOwners = lights.remove(pos);
            if (removedOwners != null) {
                for (UUID owner : removedOwners.keySet()) BEAM_CACHE.remove(owner);
            }
            if (lights.isEmpty()) LIGHT_OWNERS.remove(dimension);
        }
    }

    private static void clearPlayer(ServerPlayer player) {
        UUID owner = player.getUUID();
        BEAM_CACHE.remove(owner);
        PlayerBeam previous = PLAYER_BEAMS.remove(owner);
        if (previous == null) return;
        ServerLevel level = player.getServer().getLevel(previous.dimension());
        for (BlockPos pos : previous.positions()) releaseLight(level, previous.dimension(), pos, owner);
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) DynamicLightCoordination.joined(player);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearPlayer(player);
            DynamicLightCoordination.left(player);
            LampSource.resetLegacyCheck(player.getUUID());
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearPlayer(player);
            DynamicLightCoordination.changedDimension(player, event.getFrom(), event.getTo());
        }
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) clearPlayer(player);
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.isNewChunk()) return;
        event.getChunk().findBlocks(
            state -> state.is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
            (pos, state) -> PENDING_CLEANUP_REARM.add(
                new CleanupRearm(level.dimension(), pos.immutable()))
        );
    }

    private static void onServerTick(ServerTickEvent.Pre event) {
        if (PENDING_CLEANUP_REARM.isEmpty()) return;
        MinecraftServer server = event.getServer();
        CleanupRearm pending;
        while ((pending = PENDING_CLEANUP_REARM.poll()) != null) {
            ServerLevel level = server.getLevel(pending.dimension());
            if (level != null && loaded(level, pending.pos())) {
                FlashlightLightBlock.rearmCleanup(level, pending.pos());
            }
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LIGHT_OWNERS.remove(level.dimension());
            PLAYER_BEAMS.values().removeIf(beam -> beam.dimension().equals(level.dimension()));
            BEAM_CACHE.values().removeIf(cache -> cache.dimension().equals(level.dimension()));
            PENDING_CLEANUP_REARM.removeIf(pending -> pending.dimension().equals(level.dimension()));
            DynamicLightCoordination.levelUnloaded(level.dimension());
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        LIGHT_OWNERS.clear();
        PLAYER_BEAMS.clear();
        BEAM_CACHE.clear();
        PENDING_CLEANUP_REARM.clear();
        DynamicLightCoordination.reset();
        LampSource.clearLegacyChecks();
    }

    private record CleanupRearm(ResourceKey<Level> dimension, BlockPos pos) {}
    private record PlayerBeam(ResourceKey<Level> dimension, Set<BlockPos> positions) {}
    private record BeamCache(ResourceKey<Level> dimension, Vec3 eye, Vec3 emitter, Vec3 look,
                             boolean headMounted, boolean offHand, double range,
                             double fullAngleDegrees, long computedAtTick) {}
}
