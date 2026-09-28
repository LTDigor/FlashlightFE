package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.FlashlightConfig;
import com.ltdigor.flashlightfe.LampSource;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Optional Iris adapter. No Iris classes are linked when the mod is absent. */
public final class ShaderBeamBridge {
    private static final ShaderBeamSession SESSION = new ShaderBeamSession();
    private static ShaderBeamState state = ShaderBeamState.OFF;
    private static Object currentPipeline;
    private static boolean warned;
    private static boolean disabled = Boolean.getBoolean("bestflashlight.shaderBeam.disabled");

    private ShaderBeamBridge() {}

    public static void begin(Object pipeline) {
        SESSION.begin(pipeline);
        state = ShaderBeamState.OFF;
    }

    public static void finish(Object pipeline) {
        SESSION.finish(pipeline);
        if (SESSION.ready(pipeline)) LogUtils.getLogger().info("Flashlight surface shader compiled successfully");
    }

    public static void destroy(Object pipeline) {
        SESSION.destroy(pipeline);
        if (currentPipeline == pipeline) {
            currentPipeline = null;
            state = ShaderBeamState.OFF;
        }
    }

    static void clearWorld() {
        currentPipeline = null;
        state = ShaderBeamState.OFF;
    }

    public static Optional<String> patch(String program, String source) {
        if (disabled || source == null) return Optional.empty();
        try {
            String pack = (String) Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPackName").invoke(null);
            if (!ComplementaryBeamPatch.supportedPack(pack)) return Optional.empty();
            Optional<String> result = ComplementaryBeamPatch.patch(pack, program, source);
            if (result.isPresent()) SESSION.patched(program);
            else if (ComplementaryBeamPatch.surfaceProgram(program)
                && (program.equals("gbuffers_terrain") || source.contains("DoLighting"))) {
                SESSION.reject();
                warn("Unsupported Complementary lighting structure in " + program, null);
            }
            return result;
        } catch (ReflectiveOperationException | LinkageError exception) {
            SESSION.reject();
            warn("Could not identify Iris shader pack", exception);
            return Optional.empty();
        }
    }

    /** Called by Iris before rendering the world and before PER_FRAME uniform updates. */
    public static void frame(Object pipeline) {
        currentPipeline = pipeline;
        Minecraft mc = Minecraft.getInstance();
        if (disabled || !SESSION.ready(pipeline) || !OptionalDynamicLights.dynamicBeamMode()
            || mc.level == null || mc.player == null || !mc.options.getCameraType().isFirstPerson()
            || mc.getCameraEntity() != mc.player || !mc.player.isAlive() || mc.player.isSpectator()) {
            state = ShaderBeamState.OFF;
            return;
        }
        var camera = mc.gameRenderer.getMainCamera();
        LampSource lamp = OptionalDynamicLights.selectSource(mc.level, mc.player,
            mc.player.getLookAngle(), mc.player.getEyePosition());
        if (lamp == null || !OptionalDynamicLights.releaseLocalCone(mc.player)) {
            state = ShaderBeamState.OFF;
            return;
        }
        var look = camera.getLookVector();
        state = new ShaderBeamState(true, Vec3.ZERO, new Vec3(look.x(), look.y(), look.z()).normalize(),
            Math.clamp(FlashlightConfig.BEAM_RANGE.get(), 1, 32),
            Math.toRadians(Math.clamp(FlashlightConfig.CONE_ANGLE_DEGREES.get(), 1, 90) / 2),
            Math.clamp(FlashlightConfig.BEAM_BRIGHTNESS.get(), 1, 15),
            Math.clamp(FlashlightConfig.BEAM_SOFTNESS.get(), 0, 1));
    }

    static boolean replacesLocalCone() {
        Minecraft mc = Minecraft.getInstance();
        return state.active() && currentPipeline != null && SESSION.ready(currentPipeline)
            && OptionalDynamicLights.dynamicBeamMode() && mc.options.getCameraType().isFirstPerson();
    }

    public static ShaderBeamState state() { return state; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerUniforms(Object holder) {
        if (disabled) return;
        try {
            Class<?> frequency = Class.forName("net.irisshaders.iris.gl.uniform.UniformUpdateFrequency");
            Object perFrame = Enum.valueOf((Class<? extends Enum>) frequency, "PER_FRAME");
            Class<?> api = Class.forName("net.irisshaders.iris.gl.uniform.UniformHolder");
            Method vector3 = api.getMethod("uniform3f", frequency, String.class, Supplier.class);
            Method vector4 = api.getMethod("uniform4f", frequency, String.class, Supplier.class);
            vector3.invoke(holder, perFrame, "bestflashlightBeamOrigin", (Supplier<Vector3f>) () -> state.origin().toVector3f());
            vector3.invoke(holder, perFrame, "bestflashlightBeamDirection", (Supplier<Vector3f>) () -> state.direction().toVector3f());
            vector4.invoke(holder, perFrame, "bestflashlightBeamParams", (Supplier<Vector4f>) () -> state.active()
                ? new Vector4f((float) state.range(), (float) Math.tan(state.halfAngle()), state.brightness() / 15f, (float) state.softness())
                : new Vector4f());
            SESSION.uniformsRegistered();
        } catch (ReflectiveOperationException | LinkageError exception) {
            SESSION.reject();
            disabled = true;
            state = ShaderBeamState.OFF;
            warn("Could not register flashlight uniforms; retaining LDL lighting", exception);
        }
    }

    private static void warn(String message, Throwable exception) {
        if (!warned) { warned = true; LogUtils.getLogger().warn(message, exception); }
    }
}
