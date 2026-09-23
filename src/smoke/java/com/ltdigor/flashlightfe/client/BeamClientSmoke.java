package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.FlashlightConfig;
import com.ltdigor.flashlightfe.FlashlightMod;
import com.ltdigor.flashlightfe.LampData;
import com.mojang.logging.LogUtils;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.nio.file.StandardOpenOption;
import java.util.Map;
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
import net.minecraft.world.item.Items;
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
import top.theillusivec4.curios.api.CuriosApi;

/** Disposable real-LDL fixture. Reflection also permits comparison with the old bridge. */
@EventBusSubscriber(modid = "bestflashlight", value = Dist.CLIENT)
public final class BeamClientSmoke {
    private static final boolean ENABLED = Boolean.getBoolean("bestflashlight.beamSmoke");
    private static final boolean BASELINE = Boolean.getBoolean("bestflashlight.beamSmoke.baseline");
    private static final Path CAPTURE_DIRECTORY = Path.of(System.getProperty("bestflashlight.beamSmoke.captureDirectory",
        Path.of(System.getProperty("user.home"), "Downloads", "Flashlight-beam-checks", BASELINE ? "baseline" : "fixed", Long.toString(System.currentTimeMillis())).toString()));
    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();
    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();
    private static final List<RemotePlayer> FIXTURE_PLAYERS = new ArrayList<>();
    private static final int DURATION = 100;
    private static boolean opened, prepared, finished;
    private static int ticks, stage = -1, stageCursor = -1, stageTick, frames, emptySamples;
    private static long startNanos, stageStartedNanos, lastFrameNanos, lastCaptureNanos;
    private static CompletableFuture<Void> pending;
    private static final String[] STAGES = {
        "down-center", "down-edge", "down-corner", "wall", "sloped-floor",
        "flight", "fast-flight-chunk-crossing", "vertical-flight", "near-vertical-turn",
        "offhand", "headband", "third-person", "obstacle", "obstacle-removed",
        "long-narrow", "maximum-cone", "two-sources-client-fixture", "four-sources-client-fixture",
        "torch-down", "torch-floor"
    };

    private static int[] selectedStages;

    private static int[] selectStages() {
        String selection = System.getProperty("bestflashlight.beamSmoke.stages", "").trim();
        if (selection.isEmpty()) return java.util.stream.IntStream.range(0, STAGES.length).toArray();
        List<Integer> indices = new ArrayList<>();
        for (String requested : selection.split(",")) {
            int index = java.util.Arrays.asList(STAGES).indexOf(requested.trim());
            if (index < 0) throw new IllegalArgumentException("Unknown beam smoke stage: " + requested);
            indices.add(index);
        }
        return indices.stream().mapToInt(Integer::intValue).toArray();
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (startNanos == 0) startNanos = System.nanoTime();
            if (selectedStages == null) selectedStages = selectStages();
            if (System.nanoTime() - startNanos > 600_000_000_000L) throw new AssertionError("Beam smoke timed out after 10 minutes");
            if (stage < 0 && System.nanoTime() - startNanos > 180_000_000_000L)
                throw new AssertionError("Beam smoke world/handshake startup timed out after 3 minutes");
            if (stage >= 0 && System.nanoTime() - stageStartedNanos > 60_000_000_000L)
                throw new AssertionError("Beam smoke stage timed out after 60 seconds: " + STAGES[stage]);
            if (pending != null) {
                if (!pending.isDone()) return;
                pending.join(); pending = null;
            }
            if (!opened && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                opened = true;
                if (!ModList.get().isLoaded("lambdynlights")) throw new AssertionError("Real LambDynamicLights runtime missing");
                Files.createDirectories(CAPTURE_DIRECTORY.resolve("screenshots"));
                Files.deleteIfExists(mc.gameDirectory.toPath().resolve("beam-smoke-result.txt"));
                Files.writeString(mc.gameDirectory.toPath().resolve("beam-smoke.csv"),
                    "stage,tick,frame,x,y,z,pitch,yaw,cones,positiveAirSamples,maxAirLight,frameMillis,buildNanos,traces,samples,generation,buildSequence,range,angle\n");
                Files.writeString(mc.gameDirectory.toPath().resolve("beam-smoke-environment.txt"),
                    "java=" + System.getProperty("java.version") + "\nos=" + System.getProperty("os.name")
                    + "\narch=" + System.getProperty("os.arch") + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
                    + "\nbaseline=" + BASELINE + "\nmods=" + ModList.get().getMods() + "\n");
                mc.options.renderDistance().set(6); mc.options.simulationDistance().set(5);
                mc.options.pauseOnLostFocus = false; mc.options.hideGui = false;
                mc.getTutorial().setStep(TutorialSteps.NONE); mc.getToasts().clear();
                mc.createWorldOpenFlows().createFreshLevel("Beam-smoke-" + System.currentTimeMillis(),
                    new LevelSettings("Beam smoke", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
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
            if (++ticks < 100) return; // Chunk mesh, equipment sync and bridge handshake warm-up.
            if (stage < 0 || stageTick >= DURATION) {
                if (++stageCursor == selectedStages.length) { finish(mc, "CAPTURE_COMPLETE: real LDL active; visual review required; empty sample frames=" + emptySamples); return; }
                stage = selectedStages[stageCursor];
                stageTick = 0; stageStartedNanos = System.nanoTime();
                configure(mc);
                return;
            }
            stageTick++;
            animate(mc);
        } catch (Throwable failure) {
            LogUtils.getLogger().error("BEAM_SMOKE_FAIL", failure);
            finish(mc, "FAIL: " + failure);
        }
    }

    private static void prepare(Minecraft mc) {
        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        var level = player.serverLevel();
        level.setDayTime(18000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        // Opaque roof prevents moon/sky lighting from hiding beam gaps.
        for (int x = -20; x <= 48; x++) for (int z = -12; z <= 24; z++) {
            level.setBlock(new BlockPos(x, -60, z), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(new BlockPos(x, -42, z), Blocks.STONE.defaultBlockState(), 3);
            if (x == -20 || x == 48 || z == -12 || z == 24)
                for (int y = -59; y < -42; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 3);
        }
        for (int x = -6; x <= 6; x++) for (int y = -59; y <= -50; y++)
            level.setBlock(new BlockPos(x, y, 8), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(5, -59, 4), Blocks.STONE_BRICK_STAIRS.defaultBlockState(), 3);
        level.setBlock(new BlockPos(6, -59, 4), Blocks.STONE_SLAB.defaultBlockState(), 3);
        level.setBlock(new BlockPos(7, -59, 4), Blocks.IRON_BARS.defaultBlockState(), 3);
        player.setItemSlot(EquipmentSlot.MAINHAND, lamp());
        player.getAbilities().flying = true; player.onUpdateAbilities();
        player.teleportTo(0.5, -58, 0.5);
    }

    private static ItemStack lamp() {
        var lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(10000, false);
        LampData.setEnabled(lamp, true);
        return lamp;
    }

    private static void configure(Minecraft mc) {
        mc.options.setCameraType(stage == 11 ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        double range = stage == 14 || stage == 15 ? 32.0 : 12.0;
        double angle = stage == 15 ? 90.0 : 15.0;
        FlashlightConfig.BEAM_RANGE.set(range); FlashlightConfig.BEAM_RANGE.clearCache();
        FlashlightConfig.CONE_ANGLE_DEGREES.set(angle); FlashlightConfig.CONE_ANGLE_DEGREES.clearCache();
        if (FlashlightConfig.BEAM_RANGE.get() != range || FlashlightConfig.CONE_ANGLE_DEGREES.get() != angle)
            throw new AssertionError("Beam smoke configuration did not apply");
        removeFixturePlayers(mc);
        if (stage == 16 || stage == 17) {
            int count = stage == 16 ? 1 : 3;
            for (int i = 0; i < count; i++) {
                // Client-only source fixture; this does not simulate multiplayer networking.
                var player = new RemotePlayer(mc.level, new GameProfile(new UUID(0xBEAFL, i + 1), "BeamFixture" + i));
                player.setId(-1000 - i);
                player.setPos(-3.5 + i * 3, -58, -1.5);
                player.xo = player.getX(); player.yo = player.getY(); player.zo = player.getZ();
                player.setYRot(0); player.yRotO = 0; player.setXRot(45); player.xRotO = 45;
                player.setItemSlot(EquipmentSlot.MAINHAND, lamp());
                mc.level.addEntity(player); FIXTURE_PLAYERS.add(player);
            }
        }
        int poseStage = stage == 18 ? 2 : stage == 19 ? 4 : stage;
        pose(mc, poseStage == 1 || poseStage == 2 ? 0.0 : 0.5, -58, poseStage == 2 ? 0.0 : 0.5,
            poseStage == 3 ? 0 : poseStage == 4 || poseStage >= 12 ? 45 : 90, 0);
        pending = mc.getSingleplayerServer().submit(() -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            player.getAbilities().flying = true; player.onUpdateAbilities();
            player.setItemSlot(EquipmentSlot.MAINHAND, stage == 9 || stage == 10 ? ItemStack.EMPTY : torchReference() ? new ItemStack(Items.TORCH) : lamp());
            player.setItemSlot(EquipmentSlot.OFFHAND, stage == 9 ? lamp() : ItemStack.EMPTY);
            var inventory = CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head").getStacks();
            var band = stage == 10 ? new ItemStack(FlashlightMod.HEADBAND.get()) : ItemStack.EMPTY;
            if (!band.isEmpty()) { LampData.mount(band, lamp()); LampData.setEnabled(band, true); }
            inventory.setStackInSlot(0, band);
            // At pitch 45 from eye (0.5,-56.38,0.5), z=2 intersects y≈-58.
            for (int y = -59; y <= -58; y++) player.serverLevel().setBlock(new BlockPos(0, y, 2),
                stage == 12 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        });
        LogUtils.getLogger().info("BEAM_SMOKE_STAGE {}", STAGES[stage]);
    }

    private static void animate(Minecraft mc) {
        double t = stageTick / (double) DURATION;
        switch (stage) {
            case 5 -> pose(mc, 0.5 + 10 * t, -56, -2, 65, 0);
            case 6 -> pose(mc, -8 + 48 * t, -54, -2, 70, 0);
            case 7 -> pose(mc, 4.5, -58 + 8 * Math.sin(Math.PI * t), -2, 90, 0);
            case 8 -> pose(mc, 0.5, -58, 0.5, (float) (85 + 5 * Math.sin(Math.PI * t)), (float) (360 * t));
            default -> { }
        }
    }

    private static void pose(Minecraft mc, double x, double y, double z, float pitch, float yaw) {
        mc.player.setPos(x, y, z); mc.player.setDeltaMovement(0, 0, 0);
        mc.player.getAbilities().flying = true;
        mc.player.setXRot(pitch); mc.player.setYRot(yaw);
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || stage < 0 || stage >= STAGES.length || stageTick < 20) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        try {
            long now = System.nanoTime();
            double frameMillis = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1_000_000.0;
            lastFrameNanos = now;
            frames++;
            if (!(boolean) field(OptionalDynamicLights.class, null, "available")
                || !torchReference() && (boolean) field(OptionalDynamicLights.class, null, "serverFallbackEnabled"))
                throw new AssertionError("Real LDL bridge unavailable or server fallback still active");
            var cones = (Map<?, ?>) field(OptionalDynamicLights.class, null, "CONES");
            int expectedCones = stage == 16 ? 2 : stage == 17 ? 4 : 1;
            if (!torchReference() && cones.size() != expectedCones)
                throw new AssertionError("Expected " + expectedCones + " LDL sources, found " + cones.size());
            int positive = 0; double max = 0;
            long buildNanos = 0, traces = 0, samples = 0, generation = 0, buildSequence = 0;
            for (Object cone : torchReference() ? java.util.List.of() : cones.values()) {
                Object behavior = field(cone.getClass(), cone, "behavior");
                if ((boolean) method(behavior.getClass(), "isRemoved").invoke(behavior))
                    throw new AssertionError("LDL cone unexpectedly inactive");
                buildNanos += optionalLong(cone, "lastBuildNanos");
                traces += optionalLong(cone, "lastTraceCount");
                samples += optionalLong(cone, "lastSampleCount");
                buildSequence += optionalLong(cone, "lastBuildSequence");
                Object state = field(cone.getClass(), cone, "state");
                generation += generation(state);
                Method light = method(behavior.getClass(), "lightAtPos", BlockPos.class, double.class);
                // LDL queries air beside surfaces, never the opaque solid blocks themselves.
                int cx = (int) Math.floor(mc.player.getX()), cz = (int) Math.floor(mc.player.getZ());
                for (int dx = -4; dx <= 4; dx++) for (int dz = -2; dz <= 9; dz++) {
                    BlockPos pos = stage == 3 ? new BlockPos(dx, -59 + dz, 7) : new BlockPos(cx + dx, -59, cz + dz);
                    double value = (double) light.invoke(behavior, pos, 1.935483870967742);
                    if (value > 0) positive++;
                    max = Math.max(max, value);
                }
            }
            if (positive == 0 && !torchReference()) {
                emptySamples++;
                if (!BASELINE && (stage <= 2 || stage >= 5 && stage <= 7))
                    throw new AssertionError("Visible in-range floor has no sampled air light: " + STAGES[stage]);
            }
            // The moving surface patch is diagnostic, not proof that the entire cone is empty.
            Files.writeString(mc.gameDirectory.toPath().resolve("beam-smoke.csv"),
                STAGES[stage] + "," + stageTick + "," + frames + "," + mc.player.getX() + "," + mc.player.getY()
                + "," + mc.player.getZ() + "," + mc.player.getXRot() + "," + mc.player.getYRot() + ","
                + cones.size() + "," + positive + "," + max + "," + frameMillis + "," + buildNanos + "," + traces + "," + samples + "," + generation + "," + buildSequence + "," + FlashlightConfig.BEAM_RANGE.get()
                + "," + FlashlightConfig.CONE_ANGLE_DEGREES.get() + "\n",
                StandardOpenOption.APPEND);
            if (now - lastCaptureNanos >= 250_000_000L) {
                lastCaptureNanos = now;
                Screenshot.grab(CAPTURE_DIRECTORY.toFile(), "beam-" + STAGES[stage] + "-" + stageTick + "-" + frames + ".png",
                    mc.getMainRenderTarget(), message -> {});
            }
        } catch (Throwable failure) {
            LogUtils.getLogger().error("BEAM_SMOKE_FAIL", failure);
            finish(mc, "FAIL: " + failure);
        }
    }

    private static boolean torchReference() { return stage == 18 || stage == 19; }

    private static long optionalLong(Object owner, String name) throws ReflectiveOperationException {
        try { return ((Number) field(owner.getClass(), owner, name)).longValue(); }
        catch (NoSuchFieldException absentInBaseline) { return -1; }
    }

    private static Object field(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        String key = type.getName() + "#" + name;
        if (!FIELD_CACHE.containsKey(key)) {
            try {
                Field field = type.getDeclaredField(name); field.setAccessible(true); FIELD_CACHE.put(key, field);
            } catch (NoSuchFieldException absent) { FIELD_CACHE.put(key, null); }
        }
        Field field = FIELD_CACHE.get(key);
        if (field == null) throw new NoSuchFieldException(key);
        return field.get(owner);
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws ReflectiveOperationException {
        String key = type.getName() + "#" + name;
        Method method = METHOD_CACHE.get(key);
        if (method == null) {
            method = type.getMethod(name, arguments); method.setAccessible(true); METHOD_CACHE.put(key, method);
        }
        return method;
    }

    private static long generation(Object state) throws ReflectiveOperationException {
        try {
            Object published = field(state.getClass(), state, "published");
            return ((Number) field(published.getClass(), published, "revision")).longValue();
        } catch (NoSuchFieldException absentInBaseline) { return optionalLong(state, "revision"); }
    }

    private static void removeFixturePlayers(Minecraft mc) {
        if (mc.level != null) for (RemotePlayer player : FIXTURE_PLAYERS)
            mc.level.removeEntity(player.getId(), Entity.RemovalReason.DISCARDED);
        FIXTURE_PLAYERS.clear();
    }

    private static void finish(Minecraft mc, String result) {
        finished = true;
        removeFixturePlayers(mc);
        try { Files.writeString(mc.gameDirectory.toPath().resolve("beam-smoke-result.txt"), result + "\n"); }
        catch (Exception e) { LogUtils.getLogger().error("Could not write smoke result", e); }
        LogUtils.getLogger().info("BEAM_SMOKE_RESULT {}", result);
        mc.stop();
    }
}
