package com.ltdigor.bestflashlight.client;

import com.ltdigor.bestflashlight.FlashlightConfig;
import com.ltdigor.bestflashlight.LampSource;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3d;

/**
 * Optional client-only bridge to LambDynamicLights.
 *
 * There is deliberately no compile/runtime dependency on LDL. If its API is not
 * present the bridge becomes a no-op and the normal server-owned light blocks
 * remain the complete fallback implementation.
 */
final class OptionalDynamicLights {
    private static final double SMOOTHING = 0.38;
    private static final double WALL_EPSILON = 0.06;

    private static boolean initialized;
    private static boolean available;
    private static Object manager;
    private static Object line;
    private static Method add;
    private static Method remove;
    private static Method setStart;
    private static Method setEnd;
    private static Method setLuminance;
    private static Vec3 smoothDirection;

    private OptionalDynamicLights() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(OptionalDynamicLights::render);
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        ensureInitialized();
        if (!available) return;

        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !client.player.isAlive() || client.player.isSpectator()) {
            removeLine();
            smoothDirection = null;
            return;
        }

        LampSource source = LampSource.select(client.player);
        if (source == null) {
            removeLine();
            smoothDirection = null;
            return;
        }

        Vec3 target = client.gameRenderer.getMainCamera().getLookVector();
        if (target.lengthSqr() < 1.0E-12) return;
        target = target.normalize();
        smoothDirection = smoothDirection == null
            ? target
            : smoothDirection.scale(1.0 - SMOOTHING).add(target.scale(SMOOTHING)).normalize();

        Vec3 start = client.gameRenderer.getMainCamera().getPosition();
        double range = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        Vec3 requestedEnd = start.add(smoothDirection.scale(range));
        HitResult hit = client.level.clip(new ClipContext(start, requestedEnd,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));

        Vec3 end = hit.getType() == HitResult.Type.MISS ? requestedEnd : hit.getLocation();
        if (hit.getType() != HitResult.Type.MISS) {
            double distance = Math.max(0.0, start.distanceTo(end) - WALL_EPSILON);
            end = start.add(smoothDirection.scale(distance));
        }

        updateLine(start, end);
    }

    private static void ensureInitialized() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> lamb = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
            Object instance = lamb.getMethod("get").invoke(null);
            manager = lamb.getMethod("dynamicLightBehaviorManager").invoke(instance);

            Class<?> behavior = Class.forName("dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior");
            Class<?> lineClass = Class.forName("dev.lambdaurora.lambdynlights.api.behavior.LineLightBehavior");
            Constructor<?> constructor = lineClass.getConstructor(Vector3d.class, Vector3d.class, int.class);
            line = constructor.newInstance(new Vector3d(), new Vector3d(), 15);

            add = manager.getClass().getMethod("add", behavior);
            remove = manager.getClass().getMethod("remove", behavior);
            setStart = lineClass.getMethod("setStartPoint", double.class, double.class, double.class);
            setEnd = lineClass.getMethod("setEndPoint", double.class, double.class, double.class);
            setLuminance = lineClass.getMethod("setLuminance", int.class);
            available = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            available = false;
            manager = null;
            line = null;
        }
    }

    private static void updateLine(Vec3 start, Vec3 end) {
        try {
            setStart.invoke(line, start.x, start.y, start.z);
            setEnd.invoke(line, end.x, end.y, end.z);
            setLuminance.invoke(line, 15);
            if (!lineAdded) {
                add.invoke(manager, line);
                lineAdded = true;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            disable();
        }
    }

    private static boolean lineAdded;

    private static void removeLine() {
        if (!lineAdded || manager == null || line == null) return;
        try {
            remove.invoke(manager, line);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            disable();
        } finally {
            lineAdded = false;
        }
    }

    private static void disable() {
        available = false;
        manager = null;
        line = null;
        lineAdded = false;
    }
}
