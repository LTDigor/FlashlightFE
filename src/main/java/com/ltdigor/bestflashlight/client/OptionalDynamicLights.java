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
    private static Object[] lines;
    private static Method add;
    private static Method remove;
    private static Method setStart;
    private static Method setEnd;
    private static Method setLuminance;
    private static Vec3 smoothDirection;
    private static final double[] CONE_X = {0.0, 0.55, -0.55, 0.0, 0.0, 0.38, -0.38, 0.38, -0.38};
    private static final double[] CONE_Y = {0.0, 0.0, 0.0, 0.55, -0.55, 0.38, 0.38, -0.38, -0.38};

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

        var cameraLook = client.gameRenderer.getMainCamera().getLookVector();
        Vec3 target = new Vec3(cameraLook.x(), cameraLook.y(), cameraLook.z());
        if (target.lengthSqr() < 1.0E-12) return;
        target = target.normalize();
        smoothDirection = FlashlightBeamMath.smooth(smoothDirection, target, SMOOTHING);

        Vec3 start = client.gameRenderer.getMainCamera().getPosition();
        double range = Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1.0, 32.0);
        Vec3 reference = Math.abs(smoothDirection.y) > 0.99 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 right = smoothDirection.cross(reference).normalize();
        Vec3 up = right.cross(smoothDirection).normalize();
        double halfAngle = FlashlightBeamMath.coneOffsetRadians(FlashlightConfig.CONE_ANGLE_DEGREES.get(), 1.0);

        for (int i = 0; i < lines.length; i++) {
            Vec3 direction = FlashlightBeamMath.coneDirection(
                smoothDirection, right, up, halfAngle * CONE_X[i], halfAngle * CONE_Y[i]);
            Vec3 requestedEnd = start.add(direction.scale(range));
            HitResult hit = client.level.clip(new ClipContext(start, requestedEnd,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            Vec3 end = hit.getType() == HitResult.Type.MISS
                ? requestedEnd
                : FlashlightBeamMath.wallEnd(start, direction, start.distanceTo(hit.getLocation()), WALL_EPSILON);
            updateLine(i, start, end, i == 0 ? 15 : (i <= 4 ? 12 : 9));
        }
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
            lines = new Object[CONE_X.length];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = constructor.newInstance(new Vector3d(), new Vector3d(), i == 0 ? 15 : 9);
            }

            add = manager.getClass().getMethod("add", behavior);
            remove = manager.getClass().getMethod("remove", behavior);
            setStart = lineClass.getMethod("setStartPoint", double.class, double.class, double.class);
            setEnd = lineClass.getMethod("setEndPoint", double.class, double.class, double.class);
            setLuminance = lineClass.getMethod("setLuminance", int.class);
            available = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            available = false;
            manager = null;
            lines = null;
        }
    }

    private static void updateLine(int index, Vec3 start, Vec3 end, int luminance) {
        try {
            Object line = lines[index];
            setStart.invoke(line, start.x, start.y, start.z);
            setEnd.invoke(line, end.x, end.y, end.z);
            setLuminance.invoke(line, luminance);
            if (!linesAdded) add.invoke(manager, line);
            if (index == lines.length - 1) linesAdded = true;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            disable();
        }
    }

    private static boolean linesAdded;

    private static void removeLine() {
        if (!linesAdded || manager == null || lines == null) return;
        try {
            for (Object line : lines) remove.invoke(manager, line);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            disable();
        } finally {
            linesAdded = false;
        }
    }

    private static void disable() {
        available = false;
        manager = null;
        lines = null;
        linesAdded = false;
    }
}
