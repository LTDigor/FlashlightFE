package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.FlashlightConfig;
import com.ltdigor.flashlightfe.FlashlightMod;
import com.ltdigor.flashlightfe.LampData;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Real renderer acceptance fixture. Its runner supplies the user's cached shader/mod files. */
@EventBusSubscriber(modid = "bestflashlight", value = Dist.CLIENT)
public final class ShaderBeamSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("bestflashlight.shaderSmoke");
    private static final boolean BASELINE = Boolean.getBoolean("bestflashlight.shaderBeam.disabled");
    private static final String[] PHASES = {"off", "on", "reference"};
    private static final List<Case> CASES = cases();
    private static boolean opened, prepared, finished, capture;
    private static boolean changingWorld, changedWorld;
    private static int index = -1, phase = 2, ticks;
    private static long started;
    private static CompletableFuture<?> pending;
    private static Path output;
    private record Case(String name, double distance, double angle, float pitch, float yaw, String source) {}

    private static List<Case> cases() {
        var result = new ArrayList<Case>();
        for (double angle : new double[]{15, 35, 50}) for (double distance : new double[]{.5, 1, 2, 4, 8})
            result.add(new Case("wall-" + (int) angle + "-" + distance, distance, angle, 0, 0, "main"));
        result.add(new Case("ceiling", 1, 35, -90, 0, "main"));
        result.add(new Case("ceiling-yaw", 1, 35, -90, 135, "main"));
        result.add(new Case("floor", 4, 35, 90, 0, "main"));
        result.add(new Case("offhand", 4, 35, 0, 0, "off"));
        result.add(new Case("headlamp", 4, 35, 0, 0, "head"));
        result.add(new Case("reload", 4, 35, 0, 0, "reload"));
        result.add(new Case("toggle-shaders", 4, 35, 0, 0, "toggle"));
        result.add(new Case("third-person", 4, 35, 0, 0, "third"));
        result.add(new Case("camera-handoff", 4, 35, 0, 0, "handoff"));
        result.add(new Case("discharged", 4, 35, 0, 0, "empty"));
        result.add(new Case("sloped-floor", 4, 35, 45, 0, "main"));
        result.add(new Case("near-ceiling", .5, 15, -89, 45, "main"));
        result.add(new Case("obstacles", 4, 35, 0, 0, "obstacles"));
        result.add(new Case("world-change", 4, 35, 0, 0, "world"));
        result.add(new Case("grid-offset", 4, 35, 0, 0, "offset"));
        String selected = System.getProperty("bestflashlight.shaderSmoke.cases", "");
        if (!selected.isBlank()) result.removeIf(c -> !List.of(selected.split(",")).contains(c.name));
        if (result.isEmpty()) throw new IllegalArgumentException("No matching shader beam cases");
        return result;
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || finished || changingWorld) return;
        var mc = Minecraft.getInstance();
        try {
            if (started == 0) started = System.nanoTime();
            if (System.nanoTime() - started > 900_000_000_000L) throw new AssertionError("Shader smoke timeout");
            if (pending != null) {
                if (!pending.isDone()) return;
                pending.join(); pending = null;
            }
            if (!opened && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                if (changedWorld && ShaderBeamBridge.state().active())
                    throw new AssertionError("Shader beam retained after leaving world");
                for (String mod : new String[]{"iris", "sodium", "lambdynlights"})
                    if (!ModList.get().isLoaded(mod)) throw new AssertionError("Missing fixture mod: " + mod);
                opened = true;
                output = mc.gameDirectory.toPath().resolve(BASELINE ? "baseline" : "fixed");
                Files.createDirectories(output.resolve("screenshots"));
                if (!Files.exists(output.resolve("cases.csv")))
                    Files.writeString(output.resolve("cases.csv"), "case,phase,distance,angle,pitch,yaw,shaderActive\n");
                mc.options.hideGui = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.fov().set(70);
                mc.options.bobView().set(false);
                mc.getTutorial().setStep(TutorialSteps.NONE);
                mc.createWorldOpenFlows().createFreshLevel("Shader-beam-" + System.currentTimeMillis(),
                    new LevelSettings("Shader beam test", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(4101L, false, false),
                    access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(), mc.screen);
                return;
            }
            if (mc.player == null || mc.level == null || mc.screen != null || mc.getOverlay() != null) return;
            if (!prepared) {
                prepared = true;
                pending = mc.getSingleplayerServer().submit(() -> prepare(mc));
                return;
            }
            if (capture) return;
            if (index >= 0 && CASES.get(index).source.equals("world") && phase == 1 && ticks == 40 && !changedWorld) {
                if (!BASELINE && !ShaderBeamBridge.state().active()) throw new AssertionError("World-change probe has no active beam");
                changingWorld = true;
                mc.level.disconnect();
                mc.disconnect(new TitleScreen());
                changedWorld = true;
                opened = prepared = false;
                ticks = -1;
                changingWorld = false;
                return;
            }
            if (ticks < 0) {
                ticks = 0;
                configure(mc);
            } else if (index < 0 || ticks >= 80) {
                if (++phase == PHASES.length) { phase = 0; index++; }
                if (index == CASES.size()) { finish(mc, "CAPTURE_COMPLETE"); return; }
                ticks = 0;
                configure(mc);
            }
            pose(mc);
            // Bulk replacement of reference LIGHT cells can leave a previously invisible
            // Sodium section using its old lightmap. Rebuild after light packets settle.
            if (ticks == 20) {
                for (int z : new int[]{-1, 1}) LogUtils.getLogger().info("SHADER_BEAM_LIGHT_PROBE {} {} z={} client={}",
                    CASES.get(index).name, PHASES[phase], z,
                    mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, new BlockPos(0, -57, z)));
                mc.levelRenderer.allChanged();
            }
            if (++ticks == 79) capture = true;
        } catch (Throwable failure) {
            LogUtils.getLogger().error("SHADER_BEAM_SMOKE_FAIL", failure);
            finish(mc, "FAIL: " + failure);
        }
    }

    private static void prepare(Minecraft mc) {
        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        var level = player.serverLevel();
        level.setDayTime(18000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        for (int x = -13; x <= 13; x++) for (int y = -59; y <= -29; y++) for (int z = -13; z <= 13; z++) {
            boolean shell = x <= -12 || x >= 12 || y <= -58 || y >= -30 || z <= -12 || z >= 12;
            level.setBlock(new BlockPos(x, y, z), (shell ? Blocks.WHITE_CONCRETE : Blocks.AIR).defaultBlockState(), 3);
        }
        player.getAbilities().flying = true; player.onUpdateAbilities();
        player.teleportTo(.5, -46.12, 8);
    }

    private static void configure(Minecraft mc) throws Exception {
        Case c = CASES.get(index);
        mc.options.setCameraType(c.source.equals("third") || c.source.equals("handoff") && phase == 1
            ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        if (c.source.equals("reload") && phase == 0)
            Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
        if (c.source.equals("toggle") && phase <= 1) {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object config = iris.getMethod("getIrisConfig").invoke(null);
            config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, phase == 1);
            config.getClass().getMethod("save").invoke(config);
            iris.getMethod("reload").invoke(null);
        }
        FlashlightConfig.CONE_ANGLE_DEGREES.set(c.angle); FlashlightConfig.CONE_ANGLE_DEGREES.clearCache();
        FlashlightConfig.BEAM_RANGE.set(12.0); FlashlightConfig.BEAM_RANGE.clearCache();
        FlashlightConfig.BEAM_BRIGHTNESS.set(15); FlashlightConfig.BEAM_BRIGHTNESS.clearCache();
        FlashlightConfig.BEAM_SOFTNESS.set(.35); FlashlightConfig.BEAM_SOFTNESS.clearCache();
        int configuredPhase = phase;
        pending = mc.getSingleplayerServer().submit(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            player.setGameMode(c.source.equals("empty") ? GameType.SURVIVAL : GameType.CREATIVE);
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            for (var slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD})
                player.setItemSlot(slot, ItemStack.EMPTY);
            if (configuredPhase == 1 || c.source.equals("toggle") && configuredPhase == 0) {
                ItemStack lamp = new ItemStack(c.source.equals("head") ? FlashlightMod.HEADLAMP.get() : FlashlightMod.FLASHLIGHT.get());
                if (!c.source.equals("empty")) lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(10000, false);
                LampData.setEnabled(lamp, true);
                player.setItemSlot(c.source.equals("head") ? EquipmentSlot.HEAD : c.source.equals("off") ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND, lamp);
            }
            // Full level-15 material reference, using vanilla invisible light blocks.
            var level = player.serverLevel();
            for (int a = -11; a <= 11; a++) for (int b = -57; b <= -31; b++)
                level.setBlock(new BlockPos(a, b, 11), (configuredPhase == 2 ? Blocks.LIGHT : Blocks.AIR).defaultBlockState(), 3);
            for (int x = -11; x <= 11; x++) for (int z = -11; z <= 10; z++) for (int y : new int[]{-57, -31})
                level.setBlock(new BlockPos(x, y, z), (configuredPhase == 2 ? Blocks.LIGHT : Blocks.AIR).defaultBlockState(), 3);
            level.setBlock(new BlockPos(0, -45, 10), (c.source.equals("obstacles") ? Blocks.BLACK_CONCRETE : Blocks.AIR).defaultBlockState(), 3);
            level.setBlock(new BlockPos(-1, -45, 10), (c.source.equals("obstacles") ? Blocks.STONE_SLAB : Blocks.AIR).defaultBlockState(), 3);
            level.setBlock(new BlockPos(1, -45, 10), (c.source.equals("obstacles") ? Blocks.STONE_STAIRS : Blocks.AIR).defaultBlockState(), 3);
        });
        LogUtils.getLogger().info("SHADER_BEAM_CASE {} {}", c.name, PHASES[phase]);
    }

    private static void pose(Minecraft mc) {
        Case c = CASES.get(index);
        double eyeY = c.pitch < 0 ? -30 - c.distance : c.pitch > 0 ? -57 + c.distance : -44.5;
        double z = c.pitch == 0 ? 12 - c.distance : .5;
        double x = c.source.equals("offset") ? .17 : .5;
        if (c.source.equals("offset")) eyeY += .27;
        mc.player.setPos(x, eyeY - mc.player.getEyeHeight(), z);
        mc.player.xo = mc.player.getX(); mc.player.yo = mc.player.getY(); mc.player.zo = mc.player.getZ();
        mc.player.setDeltaMovement(0, 0, 0);
        mc.player.getAbilities().flying = true;
        mc.player.setXRot(c.pitch); mc.player.xRotO = c.pitch;
        mc.player.setYRot(c.yaw); mc.player.yRotO = c.yaw;
    }

    @SubscribeEvent public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (ENABLED && !finished && index >= 0 && index < CASES.size()
            && CASES.get(index).source.equals("handoff") && phase == 1 && ticks >= 40) {
            // Switch between client ticks: this specifically exercises the handoff race.
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || changingWorld || index < 0 || index >= CASES.size()) return;
        var mc = Minecraft.getInstance();
        try {
            var transitionCones = OptionalDynamicLights.class.getDeclaredField("CONES");
            transitionCones.setAccessible(true);
            if (!BASELINE && ShaderBeamBridge.state().active()
                && !((java.util.Map<?, ?>) transitionCones.get(null)).isEmpty())
                throw new AssertionError("Double lighting during renderer handoff");
            if (!capture) return;
            Case c = CASES.get(index);
            if (!OptionalDynamicLights.dynamicBeamMode()) throw new AssertionError("LDL negotiation not active");
            Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            boolean shaderPipeline = pipeline != null && pipeline.getClass().getName().equals("net.irisshaders.iris.pipeline.IrisRenderingPipeline");
            if (shaderPipeline != !(c.source.equals("toggle") && phase == 0))
                throw new AssertionError("Complementary shader pipeline is not active");
            boolean active = ShaderBeamBridge.state().active();
            boolean expectedBeam = phase == 1 && !c.source.equals("third") && !c.source.equals("empty");
            if (!BASELINE && active != expectedBeam) throw new AssertionError("Incorrect shader state: " + c.name + " " + PHASES[phase] + " active=" + active);
            var conesField = OptionalDynamicLights.class.getDeclaredField("CONES");
            conesField.setAccessible(true);
            var cones = (java.util.Map<?, ?>) conesField.get(null);
            if (!BASELINE && active && !cones.isEmpty()) throw new AssertionError("Double lighting: local LDL cone still registered");
            if (c.source.equals("third") && phase == 1 && cones.isEmpty()) throw new AssertionError("Third-person LDL fallback missing");
            if (c.source.equals("toggle") && phase == 0 && cones.isEmpty()) throw new AssertionError("Shader-off LDL fallback missing");
            String file = c.name + "-" + PHASES[phase] + ".png";
            Screenshot.grab(output.toFile(), file, mc.getMainRenderTarget(), message -> {});
            Files.writeString(output.resolve("cases.csv"), c.name + "," + PHASES[phase] + "," + c.distance + "," + c.angle
                + "," + c.pitch + "," + c.yaw + "," + active + "\n", StandardOpenOption.APPEND);
            capture = false;
            ticks = 80;
        } catch (Throwable failure) {
            LogUtils.getLogger().error("SHADER_BEAM_SMOKE_FAIL", failure);
            finish(mc, "FAIL: " + failure);
        }
    }

    private static void finish(Minecraft mc, String result) {
        finished = true;
        try { if (output != null) Files.writeString(output.resolve("result.txt"), result); }
        catch (Exception exception) { LogUtils.getLogger().error("Cannot save shader smoke result", exception); }
        mc.stop();
    }
}
