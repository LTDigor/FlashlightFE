package com.ltdigor.bestflashlight.client;

import com.ltdigor.bestflashlight.FlashlightConfig;
import com.ltdigor.bestflashlight.FlashlightNetwork;
import com.ltdigor.bestflashlight.LampSource;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Optional client-only bridge to LambDynamicLights.
 *
 * LDL polls custom behavior changes from its client-tick pipeline, so beam geometry
 * and occlusion are also updated once per client tick. This avoids doing nine world
 * raycasts per rendered frame while staying aligned with LDL's actual rebuild cadence.
 *
 * When every connected client reports a compatible, enabled LDL bridge, the server
 * disables its temporary block-light beam. This bridge then renders one cone for each
 * tracked player, not just the local player. Mixed/old-client sessions keep the server
 * fallback and this bridge stays inactive to avoid double-lighting.
 */
final class OptionalDynamicLights {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double NOMINAL_SMOOTHING = 0.38;
    private static final double NOMINAL_FPS = 60.0;
    private static final double CLIENT_TICK_SECONDS = 1.0 / 20.0;
    private static final int STATIC_OCCLUSION_REFRESH_TICKS = 4;
    private static final double OCCLUSION_POSITION_EPSILON_SQR = 0.01 * 0.01;
    private static final double OCCLUSION_DIRECTION_DOT = Math.cos(Math.toRadians(0.20));
    private static final double DIAGONAL = Math.sqrt(0.5);

    // Centre, outer cardinal/diagonal ring, and a half-radius ring. Seventeen
    // probes close the largest gaps of the old nine-ray pattern while tick-driven
    // updates remain substantially cheaper than nine probes per rendered frame.
    private static final double[] SAMPLE_X = {
        0.0,
        1.0, -1.0, 0.0, 0.0, DIAGONAL, -DIAGONAL, DIAGONAL, -DIAGONAL,
        0.5, -0.5, 0.0, 0.0, 0.5 * DIAGONAL, -0.5 * DIAGONAL, 0.5 * DIAGONAL, -0.5 * DIAGONAL
    };
    private static final double[] SAMPLE_Y = {
        0.0,
        0.0, 0.0, 1.0, -1.0, DIAGONAL, DIAGONAL, -DIAGONAL, -DIAGONAL,
        0.0, 0.0, 0.5, -0.5, 0.5 * DIAGONAL, 0.5 * DIAGONAL, -0.5 * DIAGONAL, -0.5 * DIAGONAL
    };

    private static final Map<UUID, DynamicCone> CONES = new HashMap<>();

    private static boolean initialized;
    private static boolean available;
    private static boolean serverFallbackEnabled = true;
    private static int fallbackGraceTicks;
    private static Object manager;
    private static Method add;
    private static Method remove;
    private static Constructor<?> boundingBoxConstructor;
    private static Object config;
    private static Method getDynamicLightsMode;
    private static Method dynamicLightsModeIsEnabled;
    private static ClientLevel activeLevel;
    private static ClientPacketListener activeConnection;
    private static Boolean reportedSupport;
    private static boolean readyReported;

    private OptionalDynamicLights() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(OptionalDynamicLights::fallbackMode);
    }

    static void clientTick() {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection != activeConnection) {
            deactivateAll();
            activeConnection = connection;
            reportedSupport = null;
            readyReported = false;
            serverFallbackEnabled = true;
            fallbackGraceTicks = 0;
        }

        ensureInitialized();
        boolean support = available && isDynamicLightingEnabled();

        // Do not negotiate before the play world and LocalPlayer exist. A support
        // report sent during the connection transition can arrive before
        // PlayerLoggedInEvent, then be cleared by server-side join initialization.
        if (client.level == null || client.player == null) {
            deactivateAll();
            reportedSupport = null;
            readyReported = false;
            return;
        }

        reportSupport(connection, support);
        if (!support) {
            deactivateAll();
            readyReported = false;
            fallbackGraceTicks = 0;
            return;
        }

        if (serverFallbackEnabled && fallbackGraceTicks <= 0) {
            deactivateAll();
            readyReported = false;
            return;
        }

        if (activeLevel != client.level) {
            deactivateAll();
            activeLevel = client.level;
        }

        updateTrackedPlayers(client);
        if (serverFallbackEnabled) {
            fallbackGraceTicks--;
            return;
        }
        if (available) reportReady(connection);
    }

    private static void fallbackMode(FlashlightNetwork.FallbackModeEvent event) {
        boolean wasDynamic = !serverFallbackEnabled;
        serverFallbackEnabled = event.enabled();
        readyReported = false;
        if (serverFallbackEnabled) {
            // Server fallback takes at least a player tick to repopulate temporary
            // light blocks. Keep existing cones briefly to avoid a handoff blackout.
            fallbackGraceTicks = wasDynamic ? 2 : 0;
        } else {
            fallbackGraceTicks = 0;
        }
    }

    private static void reportSupport(ClientPacketListener connection, boolean support) {
        if (connection == null || !connection.hasChannel(FlashlightNetwork.DynamicSupport.TYPE)) {
            reportedSupport = null;
            return;
        }
        if (Objects.equals(reportedSupport, support)) return;
        PacketDistributor.sendToServer(new FlashlightNetwork.DynamicSupport(support));
        reportedSupport = support;
    }

    private static void reportReady(ClientPacketListener connection) {
        if (readyReported || connection == null
            || !connection.hasChannel(FlashlightNetwork.DynamicReady.TYPE)) {
            return;
        }
        PacketDistributor.sendToServer(new FlashlightNetwork.DynamicReady());
        readyReported = true;
    }

    private static void updateTrackedPlayers(Minecraft client) {
        double range = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        double halfAngle = FlashlightBeamMath.halfAngleRadians(FlashlightConfig.CONE_ANGLE_DEGREES.get());
        double maxDistance = client.options.getEffectiveRenderDistance() * 16.0 + range + 16.0;
        double maxDistanceSqr = maxDistance * maxDistance;
        Set<UUID> seen = new HashSet<>();

        for (Player player : client.level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            if (player != client.player && player.distanceToSqr(client.player) > maxDistanceSqr) continue;

            Vec3 look = player.getLookAngle();
            if (look.lengthSqr() < 1.0E-12) continue;
            look = look.normalize();
            Vec3 eye = player.getEyePosition();

            Vec3 selectionLook = look;
            Vec3 selectionEye = eye;
            LampSource source = LampSource.select(player, candidate -> {
                Vec3 candidateEmitter = emitterOrigin(player, candidate, selectionLook, selectionEye);
                return FlashlightConfig.WORKS_UNDERWATER.get() || !isSubmerged(client.level, candidateEmitter);
            });
            if (source == null) continue;

            Vec3 emitter = emitterOrigin(player, source, look, eye);
            Vec3 start;
            Vec3 target = look;
            if (player == client.player && client.options.getCameraType().isFirstPerson()) {
                var camera = client.gameRenderer.getMainCamera();
                var cameraLook = camera.getLookVector();
                target = new Vec3(cameraLook.x(), cameraLook.y(), cameraLook.z());
                start = camera.getPosition();
            } else {
                start = emitterPathClear(client.level, player, eye, emitter) ? emitter : eye;
            }

            DynamicCone cone = CONES.computeIfAbsent(player.getUUID(), OptionalDynamicLights::createCone);
            cone.update(client.level, player, start, target, range, halfAngle);
            if (!cone.added && !addCone(cone)) {
                disable();
                return;
            }
            seen.add(player.getUUID());
        }

        var iterator = CONES.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (seen.contains(entry.getKey())) continue;
            if (!removeCone(entry.getValue())) {
                iterator.remove();
                disable();
                return;
            }
            iterator.remove();
        }
    }

    private static boolean emitterPathClear(ClientLevel level, Player player, Vec3 eye, Vec3 emitter) {
        BlockHitResult hit = level.clip(new ClipContext(
            eye, emitter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static Vec3 emitterOrigin(Player player, LampSource source, Vec3 look, Vec3 eye) {
        if (source.headMounted()) return eye.add(look.scale(0.45)).add(0.0, 0.15, 0.0);
        Vec3 right = new Vec3(-look.z, 0.0, look.x);
        if (right.lengthSqr() < 1.0E-12) {
            double yaw = Math.toRadians(player.getYRot());
            right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
        } else {
            right = right.normalize();
        }
        boolean rightHand = (player.getMainArm() == HumanoidArm.RIGHT) != source.offHand();
        return eye.add(look.scale(0.55)).add(right.scale(rightHand ? 0.35 : -0.35)).add(0.0, -0.45, 0.0);
    }

    private static boolean isSubmerged(ClientLevel level, Vec3 origin) {
        BlockPos pos = BlockPos.containing(origin);
        if (!level.hasChunkAt(pos)) return false;
        var fluid = level.getFluidState(pos);
        return fluid.is(FluidTags.WATER) && origin.y < pos.getY() + fluid.getHeight(level, pos);
    }

    private static void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        Class<?> lamb;
        try {
            lamb = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
        } catch (ClassNotFoundException exception) {
            disable();
            return;
        }

        try {
            Object instance = lamb.getMethod("get").invoke(null);
            manager = lamb.getMethod("dynamicLightBehaviorManager").invoke(instance);

            Class<?> behaviorClass = Class.forName("dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior");
            Class<?> boundingBoxClass = Class.forName(
                "dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior$BoundingBox");
            boundingBoxConstructor = boundingBoxClass.getConstructor(
                int.class, int.class, int.class, int.class, int.class, int.class);

            add = manager.getClass().getMethod("add", behaviorClass);
            remove = manager.getClass().getMethod("remove", behaviorClass);

            config = lamb.getField("config").get(instance);
            getDynamicLightsMode = config.getClass().getMethod("getDynamicLightsMode");
            Object mode = getDynamicLightsMode.invoke(config);
            dynamicLightsModeIsEnabled = mode.getClass().getMethod("isEnabled");

            available = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            LOGGER.warn("LambDynamicLights was detected but its API is incompatible; using server light fallback", exception);
            disable();
        }
    }

    private static boolean isDynamicLightingEnabled() {
        if (!available) return false;
        try {
            Object mode = getDynamicLightsMode.invoke(config);
            return (boolean) dynamicLightsModeIsEnabled.invoke(mode);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            LOGGER.warn("Could not query LambDynamicLights mode; using server light fallback", exception);
            disable();
            return false;
        }
    }

    private static DynamicCone createCone(UUID owner) {
        ConeState state = new ConeState();
        try {
            Class<?> behaviorClass = Class.forName("dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior");
            InvocationHandler handler = (proxy, method, args) -> invokeBehavior(owner, state, proxy, method, args);
            Object behavior = Proxy.newProxyInstance(
                behaviorClass.getClassLoader(), new Class<?>[]{behaviorClass}, handler);
            return new DynamicCone(state, behavior);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("LDL behavior API disappeared after initialization", exception);
        }
    }

    private static Object invokeBehavior(UUID owner, ConeState state, Object proxy, Method method, Object[] args)
        throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "lightAtPos" -> available && state.isActive() ? state.lightAt((BlockPos) args[0]) : 0.0;
            case "getBoundingBox" -> {
                int[] bounds = state.bounds();
                yield boundingBoxConstructor.newInstance(
                    bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            }
            case "hasChanged" -> state.hasChanged();
            case "isRemoved" -> !available || !state.isActive();
            case "toString" -> "FlashlightFE dynamic cone " + owner;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            default -> throw new UnsupportedOperationException("Unsupported LambDynamicLights method: " + method);
        };
    }

    private static boolean addCone(DynamicCone cone) {
        try {
            cone.state.setActive(true);
            cone.added = true;
            add.invoke(manager, cone.behavior);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            LOGGER.warn("LambDynamicLights flashlight source registration failed; using server light fallback", exception);
            return false;
        }
    }

    private static boolean removeCone(DynamicCone cone) {
        cone.state.setActive(false);
        if (!cone.added) return true;
        try {
            if (manager != null && remove != null) remove.invoke(manager, cone.behavior);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            LOGGER.warn("LambDynamicLights flashlight source removal failed; disabling optional bridge", exception);
            return false;
        } finally {
            cone.added = false;
        }
    }

    private static void deactivateAll() {
        boolean failed = false;
        for (DynamicCone cone : CONES.values()) {
            if (!removeCone(cone)) failed = true;
        }
        CONES.clear();
        activeLevel = null;
        if (failed) disable();
    }

    private static void disable() {
        for (DynamicCone cone : CONES.values()) {
            cone.state.setActive(false);
            if (cone.added && manager != null && remove != null) {
                try {
                    remove.invoke(manager, cone.behavior);
                } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                    // The proxy remains fail-closed: zero light and isRemoved() == true.
                }
            }
            cone.added = false;
        }
        CONES.clear();
        available = false;
        manager = null;
        add = null;
        remove = null;
        boundingBoxConstructor = null;
        config = null;
        getDynamicLightsMode = null;
        dynamicLightsModeIsEnabled = null;
        activeLevel = null;
        fallbackGraceTicks = 0;
        readyReported = false;
        reportSupportLossNow();
    }

    private static void reportSupportLossNow() {
        Minecraft client = Minecraft.getInstance();
        ClientPacketListener connection = client.getConnection();
        if (connection != null && client.level != null && client.player != null
            && connection.hasChannel(FlashlightNetwork.DynamicSupport.TYPE)) {
            PacketDistributor.sendToServer(new FlashlightNetwork.DynamicSupport(false));
            reportedSupport = false;
        } else {
            reportedSupport = null;
        }
    }

    private static final class DynamicCone {
        private final ConeState state;
        private final Object behavior;
        private Vec3 smoothDirection;
        private Vec3 lastProbeStart;
        private Vec3 lastProbeAxis;
        private double[] hitDistances;
        private long[] hitBlocks;
        private int ticksSinceProbe = STATIC_OCCLUSION_REFRESH_TICKS;
        private boolean added;

        private DynamicCone(ConeState state, Object behavior) {
            this.state = state;
            this.behavior = behavior;
        }

        private void update(ClientLevel level, Player player, Vec3 start, Vec3 target,
                            double range, double halfAngle) {
            double smoothing = FlashlightBeamMath.frameIndependentFactor(
                NOMINAL_SMOOTHING, CLIENT_TICK_SECONDS, NOMINAL_FPS);
            smoothDirection = FlashlightBeamMath.smooth(smoothDirection, target, smoothing);
            Vec3 axis = smoothDirection == null || smoothDirection.lengthSqr() < 1.0E-12
                ? target.normalize()
                : smoothDirection.normalize();

            Vec3 reference = Math.abs(axis.y) > 0.99
                ? new Vec3(1.0, 0.0, 0.0)
                : new Vec3(0.0, 1.0, 0.0);
            Vec3 right = axis.cross(reference).normalize();
            Vec3 up = right.cross(axis).normalize();

            boolean refreshOcclusion = hitDistances == null || hitBlocks == null
                || lastProbeStart == null || lastProbeAxis == null
                || lastProbeStart.distanceToSqr(start) > OCCLUSION_POSITION_EPSILON_SQR
                || lastProbeAxis.dot(axis) < OCCLUSION_DIRECTION_DOT
                || ticksSinceProbe >= STATIC_OCCLUSION_REFRESH_TICKS;

            if (refreshOcclusion) {
                hitDistances = new double[SAMPLE_X.length];
                hitBlocks = new long[SAMPLE_X.length];
                Arrays.fill(hitBlocks, FlashlightBeamMath.NO_HIT_BLOCK);
                for (int i = 0; i < SAMPLE_X.length; i++) {
                    Vec3 rayDirection = FlashlightBeamMath.coneDirection(
                        axis, right, up, halfAngle * SAMPLE_X[i], halfAngle * SAMPLE_Y[i]);
                    Vec3 requestedEnd = start.add(rayDirection.scale(range));
                    BlockHitResult hit = level.clip(new ClipContext(
                        start, requestedEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
                    if (hit.getType() == HitResult.Type.MISS) {
                        hitDistances[i] = range;
                    } else {
                        hitDistances[i] = Math.max(0.0, start.distanceTo(hit.getLocation()));
                        hitBlocks[i] = hit.getBlockPos().asLong();
                    }
                }
                lastProbeStart = start;
                lastProbeAxis = axis;
                ticksSinceProbe = 0;
            } else {
                ticksSinceProbe++;
            }

            state.update(start, axis, right, up, range, halfAngle, hitDistances, hitBlocks);
            state.setActive(true);
        }
    }

    private static final class ConeState {
        private static final double POSITION_EPSILON_SQR = 0.0025 * 0.0025;
        private static final double DIRECTION_DOT_EPSILON = Math.cos(Math.toRadians(0.10));
        private static final double VALUE_EPSILON = 1.0E-5;
        private static final double HIT_DISTANCE_EPSILON = 0.02;

        private Vec3 origin = Vec3.ZERO;
        private Vec3 axis = new Vec3(0.0, 0.0, 1.0);
        private Vec3 right = new Vec3(1.0, 0.0, 0.0);
        private Vec3 up = new Vec3(0.0, 1.0, 0.0);
        private double range = 1.0;
        private double halfAngle = Math.toRadians(7.5);
        private double[] hitDistances = new double[SAMPLE_X.length];
        private long[] hitBlocks = new long[SAMPLE_X.length];
        private boolean active;
        private long revision = 1L;
        private long reportedRevision;

        private void setActive(boolean value) {
            if (active != value) {
                active = value;
                revision++;
            }
        }

        private boolean isActive() {
            return active;
        }

        private void update(Vec3 newOrigin, Vec3 newAxis, Vec3 newRight, Vec3 newUp,
                            double newRange, double newHalfAngle, double[] newHitDistances, long[] newHitBlocks) {
            boolean changed = origin.distanceToSqr(newOrigin) > POSITION_EPSILON_SQR
                || axis.dot(newAxis) < DIRECTION_DOT_EPSILON
                || Math.abs(range - newRange) > VALUE_EPSILON
                || Math.abs(halfAngle - newHalfAngle) > VALUE_EPSILON
                || occlusionChanged(newHitDistances, newHitBlocks);

            if (!changed) return;
            origin = newOrigin;
            axis = newAxis;
            right = newRight;
            up = newUp;
            range = newRange;
            halfAngle = newHalfAngle;
            hitDistances = newHitDistances.clone();
            hitBlocks = newHitBlocks.clone();
            revision++;
        }

        private boolean occlusionChanged(double[] distances, long[] blocks) {
            if (distances.length != hitDistances.length || blocks.length != hitBlocks.length) return true;
            for (int i = 0; i < distances.length; i++) {
                if (Math.abs(distances[i] - hitDistances[i]) > HIT_DISTANCE_EPSILON || blocks[i] != hitBlocks[i]) return true;
            }
            return false;
        }

        private double lightAt(BlockPos pos) {
            Vec3 point = Vec3.atCenterOf(pos);
            double luminance = FlashlightBeamMath.coneLuminance(origin, axis, point, range, halfAngle);
            if (luminance <= 0.0) return 0.0;

            Vec3 delta = point.subtract(origin);
            double forward = delta.dot(axis);
            double radius = FlashlightBeamMath.coneRadius(forward, halfAngle);
            double normalizedX = delta.dot(right) / radius;
            double normalizedY = delta.dot(up) / radius;
            int sample = FlashlightBeamMath.nearestConeSample(normalizedX, normalizedY, SAMPLE_X, SAMPLE_Y);
            return FlashlightBeamMath.visibleAtSample(
                pos, delta.length(), hitDistances[sample], hitBlocks[sample]) ? luminance : 0.0;
        }

        private int[] bounds() {
            Vec3 end = origin.add(axis.scale(range));
            double startRadius = FlashlightBeamMath.coneRadius(0.0, halfAngle);
            double endRadius = FlashlightBeamMath.coneRadius(range, halfAngle);

            double startX = startRadius * Math.sqrt(Math.max(0.0, 1.0 - axis.x * axis.x));
            double startY = startRadius * Math.sqrt(Math.max(0.0, 1.0 - axis.y * axis.y));
            double startZ = startRadius * Math.sqrt(Math.max(0.0, 1.0 - axis.z * axis.z));
            double endX = endRadius * Math.sqrt(Math.max(0.0, 1.0 - axis.x * axis.x));
            double endY = endRadius * Math.sqrt(Math.max(0.0, 1.0 - axis.y * axis.y));
            double endZ = endRadius * Math.sqrt(Math.max(0.0, 1.0 - axis.z * axis.z));

            return new int[]{
                (int) Math.floor(Math.min(origin.x - startX, end.x - endX)),
                (int) Math.floor(Math.min(origin.y - startY, end.y - endY)),
                (int) Math.floor(Math.min(origin.z - startZ, end.z - endZ)),
                (int) Math.ceil(Math.max(origin.x + startX, end.x + endX)),
                (int) Math.ceil(Math.max(origin.y + startY, end.y + endY)),
                (int) Math.ceil(Math.max(origin.z + startZ, end.z + endZ))
            };
        }

        private boolean hasChanged() {
            if (reportedRevision == revision) return false;
            reportedRevision = revision;
            return true;
        }
    }
}
