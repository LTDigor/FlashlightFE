package com.ltdigor.bestflashlight.client;

import com.ltdigor.bestflashlight.FlashlightConfig;
import com.ltdigor.bestflashlight.LampEnergy;
import com.ltdigor.bestflashlight.LampSource;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
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

/**
 * Optional client-only bridge to LambDynamicLights.
 *
 * No LambDynamicLights class is referenced at link time. If LDL is absent or its
 * API is incompatible this class becomes a no-op and the existing server-owned
 * temporary light blocks remain the fallback implementation.
 */
final class OptionalDynamicLights {
    private static final double NOMINAL_SMOOTHING = 0.38;
    private static final double NOMINAL_FPS = 60.0;
    private static final double MAX_FRAME_SECONDS = 0.10;
    private static final double DIAGONAL = Math.sqrt(0.5);

    // Centre + four cardinal edges + four diagonal edges. Unlike the previous
    // implementation these are occlusion probes, not nine independent light sources.
    private static final double[] SAMPLE_X = {0.0, 1.0, -1.0, 0.0, 0.0, DIAGONAL, -DIAGONAL, DIAGONAL, -DIAGONAL};
    private static final double[] SAMPLE_Y = {0.0, 0.0, 0.0, 1.0, -1.0, DIAGONAL, DIAGONAL, -DIAGONAL, -DIAGONAL};

    private static boolean initialized;
    private static boolean available;
    private static Object manager;
    private static Object behavior;
    private static Method add;
    private static Method remove;
    private static Constructor<?> boundingBoxConstructor;
    private static ConeState coneState;
    private static boolean added;
    private static ClientLevel activeLevel;
    private static Vec3 smoothDirection;
    private static long lastFrameNanos;

    private OptionalDynamicLights() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(OptionalDynamicLights::render);
    }

    static void clientTick() {
        if (!available || !added) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !client.player.isAlive() || client.player.isSpectator()
            || !hasActivePoweredSource(client) || client.level != activeLevel) {
            deactivate();
        }
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        ensureInitialized();
        if (!available) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !client.player.isAlive() || client.player.isSpectator()) {
            deactivate();
            return;
        }

        LampSource source = LampSource.select(client.player);
        if (source == null || !LampEnergy.hasPower(source.stack(), client.player)) {
            deactivate();
            return;
        }

        if (activeLevel != null && activeLevel != client.level) deactivate();

        var camera = client.gameRenderer.getMainCamera();
        boolean firstPerson = client.options.getCameraType().isFirstPerson();
        Vec3 start;
        Vec3 target;
        if (firstPerson) {
            start = camera.getPosition();
            var cameraLook = camera.getLookVector();
            target = new Vec3(cameraLook.x(), cameraLook.y(), cameraLook.z());
        } else {
            start = client.player.getEyePosition();
            target = client.player.getLookAngle();
        }

        if (target.lengthSqr() < 1.0E-12) {
            deactivate();
            return;
        }
        Vec3 emitter = emitterOrigin(client.player, source, target.normalize());
        if (!firstPerson) start = emitter;
        if (!FlashlightConfig.WORKS_UNDERWATER.get() && isSubmerged(client.level, emitter)) {
            deactivate();
            return;
        }

        long now = System.nanoTime();
        double deltaSeconds = lastFrameNanos == 0L
            ? 1.0 / NOMINAL_FPS
            : Math.min(MAX_FRAME_SECONDS, Math.max(0.0, (now - lastFrameNanos) / 1_000_000_000.0));
        lastFrameNanos = now;

        double smoothing = FlashlightBeamMath.frameIndependentFactor(NOMINAL_SMOOTHING, deltaSeconds, NOMINAL_FPS);
        smoothDirection = FlashlightBeamMath.smooth(smoothDirection, target, smoothing);
        if (smoothDirection == null || smoothDirection.lengthSqr() < 1.0E-12) {
            deactivate();
            return;
        }

        Vec3 axis = smoothDirection.normalize();
        Vec3 reference = Math.abs(axis.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = axis.cross(reference).normalize();
        Vec3 up = right.cross(axis).normalize();
        double range = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        double halfAngle = FlashlightBeamMath.halfAngleRadians(FlashlightConfig.CONE_ANGLE_DEGREES.get());

        double[] hitDistances = new double[SAMPLE_X.length];
        long[] hitBlocks = new long[SAMPLE_X.length];
        Arrays.fill(hitBlocks, FlashlightBeamMath.NO_HIT_BLOCK);
        for (int i = 0; i < SAMPLE_X.length; i++) {
            Vec3 rayDirection = FlashlightBeamMath.coneDirection(
                axis, right, up, halfAngle * SAMPLE_X[i], halfAngle * SAMPLE_Y[i]);
            Vec3 requestedEnd = start.add(rayDirection.scale(range));
            BlockHitResult hit = client.level.clip(new ClipContext(
                start, requestedEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (hit.getType() == HitResult.Type.MISS) {
                hitDistances[i] = range;
            } else {
                hitDistances[i] = Math.max(0.0, start.distanceTo(hit.getLocation()));
                hitBlocks[i] = hit.getBlockPos().asLong();
            }
        }

        coneState.update(start, axis, right, up, range, halfAngle, hitDistances, hitBlocks);
        activeLevel = client.level;
        if (!added) {
            try {
                // Mark first so a partially successful reflective add is still removable
                // if the invoked implementation throws after registering the source.
                added = true;
                add.invoke(manager, behavior);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                disable();
            }
        }
    }

    private static boolean hasActivePoweredSource(Minecraft client) {
        LampSource source = LampSource.select(client.player);
        return source != null && LampEnergy.hasPower(source.stack(), client.player);
    }

    private static Vec3 emitterOrigin(Player player, LampSource source, Vec3 look) {
        Vec3 eye = player.getEyePosition();
        if (source.headMounted()) return eye.add(look.scale(0.45)).add(0.0, 0.15, 0.0);
        double yaw = Math.toRadians(player.getYRot());
        Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
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
        try {
            Class<?> lamb = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
            Object instance = lamb.getMethod("get").invoke(null);
            manager = lamb.getMethod("dynamicLightBehaviorManager").invoke(instance);

            Class<?> behaviorClass = Class.forName("dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior");
            Class<?> boundingBoxClass = Class.forName(
                "dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior$BoundingBox");
            boundingBoxConstructor = boundingBoxClass.getConstructor(
                int.class, int.class, int.class, int.class, int.class, int.class);

            add = manager.getClass().getMethod("add", behaviorClass);
            remove = manager.getClass().getMethod("remove", behaviorClass);
            coneState = new ConeState();
            InvocationHandler handler = OptionalDynamicLights::invokeBehavior;
            behavior = Proxy.newProxyInstance(behaviorClass.getClassLoader(), new Class<?>[]{behaviorClass}, handler);
            available = true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disable();
        }
    }

    private static Object invokeBehavior(Object proxy, Method method, Object[] args) throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "lightAtPos" -> coneState.lightAt((BlockPos) args[0]);
            case "getBoundingBox" -> {
                int[] bounds = coneState.bounds();
                yield boundingBoxConstructor.newInstance(
                    bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            }
            case "hasChanged" -> coneState.hasChanged();
            case "isRemoved" -> false;
            case "toString" -> "FlashlightFE dynamic cone";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException("Unsupported LambDynamicLights method: " + method);
        };
    }

    private static void deactivate() {
        removeBehaviorQuietly();
        activeLevel = null;
        smoothDirection = null;
        lastFrameNanos = 0L;
    }

    private static void removeBehaviorQuietly() {
        if (!added) return;
        try {
            if (manager != null && behavior != null && remove != null) remove.invoke(manager, behavior);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // The LDL world/manager can disappear during client world teardown.
        } finally {
            added = false;
        }
    }

    private static void disable() {
        removeBehaviorQuietly();
        available = false;
        manager = null;
        behavior = null;
        add = null;
        remove = null;
        boundingBoxConstructor = null;
        coneState = null;
        activeLevel = null;
        smoothDirection = null;
        lastFrameNanos = 0L;
    }

    /**
     * Mutable state behind the reflected DynamicLightBehavior. One behavior gives LDL
     * the actual spotlight volume while the nine probes only define wall occlusion.
     */
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
        private long revision = 1L;
        private long reportedRevision;

        private void update(Vec3 newOrigin, Vec3 newAxis, Vec3 newRight, Vec3 newUp,
                            double newRange, double newHalfAngle, double[] newHitDistances, long[] newHitBlocks) {
            boolean changed = origin.distanceToSqr(newOrigin) > POSITION_EPSILON_SQR
                || axis.dot(newAxis) < DIRECTION_DOT_EPSILON
                || Math.abs(range - newRange) > VALUE_EPSILON
                || Math.abs(halfAngle - newHalfAngle) > VALUE_EPSILON
                || occlusionChanged(newHitDistances, newHitBlocks);

            origin = newOrigin;
            axis = newAxis;
            right = newRight;
            up = newUp;
            range = newRange;
            halfAngle = newHalfAngle;
            hitDistances = newHitDistances.clone();
            hitBlocks = newHitBlocks.clone();
            if (changed) revision++;
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
