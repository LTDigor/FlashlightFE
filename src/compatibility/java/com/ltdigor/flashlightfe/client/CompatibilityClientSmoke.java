package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.*;
import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
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

/** Real optional-mod runtime matrix. No Curios or LDL classes in the compile classpath. */
@EventBusSubscriber(modid = "bestflashlight", value = Dist.CLIENT)
public final class CompatibilityClientSmoke {
    private static final String CURIOS = System.getProperty("bestflashlight.compatibility.curios", "absent");
    private static final boolean LDL = Boolean.getBoolean("bestflashlight.compatibility.ldl");
    private static final String[] STAGES = {"off", "main", "offhand", "head", "head-front", "head-turned", "reload",
        "close-wall", "ldl-disabled", "ldl-restored", "cleanup"};
    private static boolean opened, prepared, finished;
    private static int stage = -1, ticks;
    private static CompletableFuture<?> pending;
    private static Map<String, Integer> originalSlots;
    private static long deadline = System.nanoTime() + 240_000_000_000L;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("bestflashlight.compatibility") || finished) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            require(System.nanoTime() < deadline, "Runtime matrix timeout");
            if (pending != null) {
                if (!pending.isDone()) return;
                pending.join(); pending = null;
            }
            if (!opened && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                opened = true;
                require(ModList.get().isLoaded("curios") == !CURIOS.equals("absent"), "Curios runtime mismatch");
                require(ModList.get().isLoaded("lambdynlights") == LDL, "LDL runtime mismatch");
                if (CURIOS.equals("absent")) {
                    try { Class.forName("top.theillusivec4.curios.api.CuriosApi"); throw new AssertionError("Curios leaked onto classpath"); }
                    catch (ClassNotFoundException expected) { }
                }
                if (!LDL) {
                    try { Class.forName("dev.lambdaurora.lambdynlights.LambDynLights"); throw new AssertionError("LDL leaked onto classpath"); }
                    catch (ClassNotFoundException expected) { }
                } else setLdl(true);
                Files.writeString(mc.gameDirectory.toPath().resolve("compatibility-result.txt"), "RUNNING\n");
                mc.options.renderDistance().set(4); mc.options.simulationDistance().set(5);
                mc.options.pauseOnLostFocus = false; mc.options.hideGui = true;
                mc.getTutorial().setStep(TutorialSteps.NONE); mc.getToasts().clear();
                mc.createWorldOpenFlows().createFreshLevel("Flashlight-compatibility",
                    new LevelSettings("Flashlight compatibility", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(4101, false, false),
                    access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(), mc.screen);
                return;
            }
            // Desktop focus/escape must not leave this disposable automated world paused.
            if (prepared && mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
            if (mc.player == null || mc.level == null || mc.screen != null || mc.getOverlay() != null) return;
            if (!prepared) {
                prepared = true; pending = mc.getSingleplayerServer().submit(() -> prepare(mc)); return;
            }
            if (++ticks < 70) return;
            ticks = 0;
            if (stage >= 0) {
                verifyClient(mc);
                Screenshot.grab(mc.gameDirectory, STAGES[stage] + ".png", mc.getMainRenderTarget(), message -> {});
                int checked = stage;
                pending = mc.getSingleplayerServer().submit(() -> verifyServer(mc, checked));
            }
            CompletableFuture<?> verification = pending;
            if (++stage == STAGES.length) {
                if (pending != null) pending.join();
                finish(mc, "PASS: " + CURIOS + ", LDL=" + LDL + "; all stages=" + String.join(",", STAGES));
                return;
            }
            mc.options.hideGui = !(stage == 1 || stage == 2 || stage == 7);
            mc.player.setYRot(0); mc.player.setXRot(stage == 5 ? -20 : 0);
            mc.options.setCameraType(stage >= 4 && stage <= 6 ? CameraType.THIRD_PERSON_FRONT : CameraType.FIRST_PERSON);
            if (stage == 6) { pending = mc.reloadResourcePacks(); }
            if (LDL && (stage == 8 || stage == 9)) setLdl(stage == 9);
            CompletableFuture<?> previous = pending;
            pending = mc.getSingleplayerServer().submit(() -> configure(mc, stage));
            if (previous != null) pending = CompletableFuture.allOf(previous, pending);
            if (verification != null) pending = CompletableFuture.allOf(verification, pending);
        } catch (Throwable failure) {
            LogUtils.getLogger().error("COMPATIBILITY_FAIL", failure);
            finish(mc, "FAIL: " + failure);
        }
    }

    private static void prepare(Minecraft mc) {
        var p = player(mc); var level = p.serverLevel();
        level.setDayTime(18000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        for (int x = -12; x <= 12; x++) for (int y = -60; y <= -49; y++) for (int z = -8; z <= 12; z++)
            level.setBlock(new BlockPos(x,y,z), (x == -12 || x == 12 || y == -60 || y == -49 || z == -8 || z == 12 || z == 8
                ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
        p.teleportTo(.5,-59,.5); p.setYRot(0); p.setXRot(0);
        p.setNoGravity(true);
        originalSlots = slots(p);
        require(originalSlots.containsKey("head") == CURIOS.equals("head"), "External head fixture mismatch: " + originalSlots);
        p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        ItemStack headlamp = lamp(true);
        p.setItemInHand(InteractionHand.MAIN_HAND, headlamp);
        require(headlamp.use(level, p, InteractionHand.MAIN_HAND).getResult().consumesAction(), "Headlamp use did not equip");
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        require(!LampSource.headlamp(p).isEmpty(), "Equipped headlamp not recognized");
        if (CURIOS.equals("head")) require(p.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "Curios equip changed helmet");
        else require(p.getItemBySlot(EquipmentSlot.HEAD).is(FlashlightMod.HEADLAMP.get()), "Fallback did not equip vanilla HEAD");
        require(originalSlots.equals(slots(p)), "Equipping changed Curios slot definitions");
        LampData.setEnabled(LampSource.headlamp(p), false);
    }

    private static void configure(Minecraft mc, int n) {
        var p = player(mc);
        p.setGameMode(n == 3 ? GameType.SURVIVAL : GameType.CREATIVE);
        p.teleportTo(.5, -59, n == 7 ? 7.65 : .5); p.setYRot(0); p.setXRot(n == 5 ? -20 : 0);
        p.setItemInHand(InteractionHand.MAIN_HAND, n == 1 || n == 7 || n == 8 || n == 9 ? lamp(false) : ItemStack.EMPTY);
        p.setItemInHand(InteractionHand.OFF_HAND, n == 2 ? lamp(false) : ItemStack.EMPTY);
        LampData.setEnabled(LampSource.headlamp(p), n >= 3 && n <= 6);
        p.serverLevel().setBlock(new BlockPos(0,-55,-2), (n >= 4 && n <= 6 ? Blocks.GLOWSTONE : Blocks.AIR).defaultBlockState(), 3);
    }

    private static void verifyClient(Minecraft mc) throws Exception {
        require(mc.getItemRenderer().getModel(new ItemStack(FlashlightMod.HEADLAMP.get()), mc.level, mc.player, 0)
            != mc.getModelManager().getMissingModel(), "Missing headlamp baked model");
        if (stage >= 3 && stage <= 6) {
            require(LampData.enabled(LampSource.headlamp(mc.player)), "Headlamp client state not synchronized");
            if (stage == 3) require(LampEnergy.stored(LampSource.headlamp(mc.player)) < 500, "Headlamp owner FE packet did not sync");
        }
        boolean fallback = (boolean) field(OptionalDynamicLights.class, null, "serverFallbackEnabled");
        boolean expectedDynamic = LDL && stage != 8;
        require(fallback != expectedDynamic, "Wrong negotiated backend: fallback=" + fallback + " stage=" + stage);
        var cones = (Map<?,?>) field(OptionalDynamicLights.class, null, "CONES");
        if (expectedDynamic && stage > 0 && stage < 10) {
            require(cones.size() == 1, "Expected one real LDL cone, got " + cones.size());
            boolean positive = false;
            for (Object cone : cones.values()) {
                Object behavior = field(cone.getClass(), cone, "behavior");
                var light = behavior.getClass().getMethod("lightAtPos", BlockPos.class, double.class);
                for (int x=-3; x<=3; x++) for (int y=-59; y<=-54; y++) for (int z=0; z<=7; z++)
                    positive |= ((Number)light.invoke(behavior, new BlockPos(x,y,z), 1.935483870967742)).doubleValue() > 0;
            }
            require(positive, "Real LDL cone emitted no light");
        }
        if (stage == 0 || stage == 10 || !expectedDynamic) require(cones.isEmpty(), "Stale/doubled LDL sources");
    }

    private static void verifyServer(Minecraft mc, int n) {
        var p = player(mc);
        require(originalSlots.equals(slots(p)), "Curios slots changed at " + STAGES[n]);
        int carriers = 0;
        for (BlockPos pos : BlockPos.betweenClosed(-12,-60,-8,12,-49,12))
            if (p.serverLevel().getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) carriers++;
        boolean fallback = !LDL || n == 8;
        if (n == 0 || n == 10 || !fallback) require(carriers == 0, "Residual/double server light: " + carriers);
        else require(carriers > 0, "Fallback emitted no light at " + STAGES[n]);
        LogUtils.getLogger().info("COMPATIBILITY_STAGE_PASS: {} Curios={} LDL={} carriers={}", STAGES[n], CURIOS, LDL, carriers);
    }

    private static ServerPlayer player(Minecraft mc) { return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()); }
    private static ItemStack lamp(boolean head) {
        ItemStack stack = new ItemStack(head ? FlashlightMod.HEADLAMP.get() : FlashlightMod.FLASHLIGHT.get());
        stack.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(500, false);
        LampData.setEnabled(stack, true); return stack;
    }
    private static Map<String,Integer> slots(ServerPlayer p) {
        Map<String,Integer> result = new TreeMap<>();
        if (CURIOS.equals("absent")) return result;
        try {
            var api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            var inventory = (java.util.Optional<?>)api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class).invoke(null, p);
            if (inventory.isEmpty()) return result;
            Object value = inventory.orElseThrow();
            var map = (Map<?,?>)value.getClass().getMethod("getCurios").invoke(value);
            for (var entry : map.entrySet()) {
                Object stacks = entry.getValue().getClass().getMethod("getStacks").invoke(entry.getValue());
                result.put(entry.getKey().toString(), (int) stacks.getClass().getMethod("getSlots").invoke(stacks));
            }
            return result;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void setLdl(boolean enabled) throws Exception {
        var cls = Class.forName("dev.lambdaurora.lambdynlights.LambDynLights");
        Object instance = cls.getMethod("get").invoke(null);
        Object config = cls.getField("config").get(instance);
        var mode = Class.forName("dev.lambdaurora.lambdynlights.DynamicLightsMode");
        config.getClass().getMethod("setDynamicLightsMode", mode).invoke(config, mode.getField(enabled ? "FANCY" : "OFF").get(null));
    }
    private static Object field(Class<?> cls, Object target, String name) throws Exception {
        Field field = cls.getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void finish(Minecraft mc, String result) {
        finished = true;
        try { Files.writeString(mc.gameDirectory.toPath().resolve("compatibility-result.txt"), result + "\n"); }
        catch (Exception failure) { LogUtils.getLogger().error("Cannot write compatibility result", failure); }
        LogUtils.getLogger().info("COMPATIBILITY_RESULT: {}", result); mc.stop();
    }
}
