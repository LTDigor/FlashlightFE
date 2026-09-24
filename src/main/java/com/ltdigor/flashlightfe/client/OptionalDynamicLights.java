package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.FlashlightConfig;
import com.ltdigor.flashlightfe.FlashlightNetwork;
import com.ltdigor.flashlightfe.LampEnergy;
import com.ltdigor.flashlightfe.LampSource;
import com.ltdigor.flashlightfe.lighting.EmitterTransform;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Optional client-only bridge to LambDynamicLights.
 *
 * LDL polls occlusion/chunk work from its client-tick pipeline, so expensive world
 * probes stay tick-driven and cached. Frames update only the aiming target; geometry,
 * bounds and light values are published together after all visibility checks finish.
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
    private static final int STATIC_OCCLUSION_REFRESH_TICKS = 4;
    private static final Map<UUID, DynamicCone> CONES = new HashMap<>();

    private static boolean initialized;
    private static volatile boolean available;
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
        NeoForge.EVENT_BUS.addListener(OptionalDynamicLights::renderFrame);
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

        if (activeLevel != client.level) {
            deactivateAll();
            activeLevel = client.level;
            reportedSupport = null;
            readyReported = false;
        }

        reportSupport(connection, support);
        if (!support) {
            deactivateAll();
            activeLevel = client.level;
            readyReported = false;
            fallbackGraceTicks = 0;
            return;
        }

        if (serverFallbackEnabled && fallbackGraceTicks <= 0) {
            deactivateAll();
            activeLevel = client.level;
            readyReported = false;
            return;
        }

        updateTrackedPlayers(client);
        if (serverFallbackEnabled) {
            fallbackGraceTicks--;
            return;
        }
        if (available) reportReady(connection);
    }

    private static void renderFrame(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft client = Minecraft.getInstance();
        if (!available || client.level == null || client.player == null || activeLevel != client.level) return;
        if (serverFallbackEnabled && fallbackGraceTicks <= 0) return;

        double range = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        double halfAngle = FlashlightBeamMath.halfAngleRadians(FlashlightConfig.CONE_ANGLE_DEGREES.get());
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        double deltaSeconds = Math.max(0.0, event.getPartialTick().getRealtimeDeltaTicks()) / 20.0;

        for (Player player : client.level.players()) {
            DynamicCone cone = CONES.get(player.getUUID());
            if (cone == null || !player.isAlive() || player.isSpectator()) continue;

            Vec3 look = player.getViewVector(partialTick);
            if (look.lengthSqr() < 1.0E-12) continue;
            look = look.normalize();
            Vec3 eye = player.getEyePosition(partialTick);
            Vec3 start;
            Vec3 target = look;

            if (player == client.player && client.options.getCameraType().isFirstPerson()) {
                var camera = event.getCamera();
                var cameraLook = camera.getLookVector();
                target = new Vec3(cameraLook.x(), cameraLook.y(), cameraLook.z());
                start = camera.getPosition();
            } else {
                Vec3 emitter = emitterOrigin(player, cone.headMounted, cone.offHand, look, eye);
                start = cone.useEmitter ? emitter : eye;
            }

            cone.frameUpdate(start, target, range, halfAngle, deltaSeconds);
        }
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
                if (!LampEnergy.hasPower(candidate.stack(), player)) return false;
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
                start = emitter;
            }

            DynamicCone cone = CONES.computeIfAbsent(player.getUUID(), OptionalDynamicLights::createCone);
            boolean localFirstPerson = player == client.player && client.options.getCameraType().isFirstPerson();
            boolean useEmitter = !localFirstPerson && emitterPathClear(client.level, player, eye, emitter);
            if (!localFirstPerson) start = useEmitter ? emitter : eye;
            cone.configureSource(source.headMounted(), source.offHand(), useEmitter);
            cone.tickUpdate(client.level, player, start, target, range, halfAngle);
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
        return emitterOrigin(player, source.headMounted(), source.offHand(), look, eye);
    }

    private static Vec3 emitterOrigin(Player player, boolean headMounted, boolean offHand, Vec3 look, Vec3 eye) {
        boolean rightHand = (player.getMainArm() == HumanoidArm.RIGHT) != offHand;
        EmitterTransform transform = headMounted ? EmitterTransform.HEADBAND : EmitterTransform.HANDHELD;
        return transform.origin(eye, look, Math.toRadians(player.getYRot()), rightHand, headMounted);
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
        private BeamSnapshot lastBuilt;
        private int ticksSinceBuild = STATIC_OCCLUSION_REFRESH_TICKS;
        private boolean headMounted;
        private boolean offHand;
        private boolean useEmitter;
        private boolean added;
        // Read by the development smoke fixture, never logged in normal play.
        private long lastBuildNanos;
        private long lastBuildSequence;
        private int lastTraceCount;
        private int lastSampleCount;

        private DynamicCone(ConeState state, Object behavior) {
            this.state = state;
            this.behavior = behavior;
        }

        private void configureSource(boolean headMounted, boolean offHand, boolean useEmitter) {
            this.headMounted = headMounted;
            this.offHand = offHand;
            this.useEmitter = useEmitter;
        }

        private void tickUpdate(ClientLevel level, Player player, Vec3 start, Vec3 target,
                                double range, double halfAngle) {
            if (smoothDirection == null || smoothDirection.lengthSqr() < 1.0E-12) {
                smoothDirection = target.normalize();
            }
            Vec3 axis = smoothDirection.normalize();
            if (lastBuilt == null || !lastBuilt.matches(start, axis, range, halfAngle)
                || ++ticksSinceBuild >= STATIC_OCCLUSION_REFRESH_TICKS) {
                long began = System.nanoTime();
                BeamSnapshot next = BeamSnapshot.build(start, axis, range, halfAngle,
                    new BeamVisibility(level, player, start, level::hasChunkAt));
                lastBuildNanos = System.nanoTime() - began;
                lastBuildSequence++;
                lastTraceCount = next.traceCount();
                lastSampleCount = next.sampleCount();
                state.publish(next);
                lastBuilt = next;
                ticksSinceBuild = 0;
            }
            state.setActive(true);
        }

        private void frameUpdate(Vec3 start, Vec3 target, double range, double halfAngle, double deltaSeconds) {
            double smoothing = FlashlightBeamMath.frameIndependentFactor(
                NOMINAL_SMOOTHING, deltaSeconds, NOMINAL_FPS);
            smoothDirection = FlashlightBeamMath.smooth(smoothDirection, target, smoothing);
            // Never move published light independently of its visibility results.
        }
    }

    static final class ConeState {
        private record Published(BeamSnapshot snapshot, boolean active, long revision) {}
        private volatile Published published = new Published(BeamSnapshot.empty(), false, 1);
        private long reportedRevision;

        void publish(BeamSnapshot snapshot) {
            Published old = published;
            if (old.snapshot.sameLight(snapshot)) return;
            published = new Published(snapshot, old.active, old.revision + 1);
        }

        void setActive(boolean active) {
            Published old = published;
            if (old.active == active) return;
            published = new Published(active ? old.snapshot : BeamSnapshot.empty(), active, old.revision + 1);
        }

        boolean isActive() { return published.active; }

        double lightAt(BlockPos pos) {
            Published current = published;
            return current.active ? current.snapshot.lightAt(pos) : 0;
        }

        int[] bounds() { return published.snapshot.bounds(); }

        boolean hasChanged() {
            long revision = published.revision;
            if (reportedRevision == revision) return false;
            reportedRevision = revision;
            return true;
        }
    }
}
