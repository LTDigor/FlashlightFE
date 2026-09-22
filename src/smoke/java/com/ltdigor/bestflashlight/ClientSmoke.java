package com.ltdigor.bestflashlight;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.model.ModelResourceLocation;
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
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import com.ltdigor.bestflashlight.client.ButtonAnimation;
import com.ltdigor.bestflashlight.client.FlashlightClientEvents;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;

/** Isolated development world, actual client rendering and key/command path. Never shipped. */
@EventBusSubscriber(modid="bestflashlight",value=Dist.CLIENT)
public final class ClientSmoke {
    private static boolean opened, prepared;
    private static int ticks;
    private static java.util.concurrent.CompletableFuture<Void> pending;
    private static CompletableFuture<Void> resourceReload;
    private static long resourceReloadDeadlineNanos;
    private static void serverStep(Minecraft mc, Runnable action) { pending = mc.getSingleplayerServer().submit(action); }
    private static final boolean RELOAD = Boolean.getBoolean("bestflashlight.smoke.reload");
    private static final BlockPos WATER_ORPHAN = new BlockPos(-5, -59, -6);
    private static final BlockPos DRY_ORPHAN = new BlockPos(-5, -58, -6);
    private static volatile boolean reloadedOrphans;
    private static InputConstants.Key originalHandheldKey;
    private static boolean awaitingBlockUse, blockUseObserved, reboundBlockUseObserved;
    private static String capturePrefix;
    private static long captureUntilNanos, lastCaptureNanos;
    private static int captureFrame;
    private static boolean awaitingEmptyPress, emptyPressAccepted, emptyPressMoved;
    private static boolean awaitingNativePress, nativePressAccepted, nativePressDeepened;
    private static long nativePressDeadlineNanos;
    @SubscribeEvent public static void press(FlashlightNetwork.PressEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (awaitingNativePress && mc.player != null && event.owner().equals(mc.player.getUUID())
                && event.hand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && !event.previousEnabled() && event.enabled())
            nativePressAccepted = true;
        if (!awaitingEmptyPress || mc.player == null || !event.owner().equals(mc.player.getUUID())) return;
        if (event.hand() != net.minecraft.world.InteractionHand.OFF_HAND || event.previousEnabled() || event.enabled())
            throw new AssertionError("Empty survival press returned wrong authoritative animation state");
        emptyPressAccepted = true;
    }
    @SubscribeEvent public static void render(RenderFrameEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (awaitingNativePress && mc.player != null && ButtonAnimation.offset(mc.player.getUUID(),
                net.minecraft.world.InteractionHand.MAIN_HAND, true) < -.35)
            nativePressDeepened = true;
        if (capturePrefix == null) return;
        long now = System.nanoTime();
        if (now > captureUntilNanos || captureFrame >= 12) {
            capturePrefix = null;
            return;
        }
        if (now - lastCaptureNanos < 50_000_000L) return;
        lastCaptureNanos = now;
        Screenshot.grab(mc.gameDirectory, "%s-frame-%03d.png".formatted(capturePrefix, captureFrame++),
            mc.getMainRenderTarget(), message -> {});
    }
    @SubscribeEvent public static void serverStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (!RELOAD || !Boolean.getBoolean("bestflashlight.smoke")) return;
        var level = event.getServer().overworld();
        reloadedOrphans = level.getBlockState(WATER_ORPHAN).is(FlashlightMod.FLASHLIGHT_LIGHT.get()) &&
            level.getBlockState(DRY_ORPHAN).is(FlashlightMod.FLASHLIGHT_LIGHT.get());
        if (!reloadedOrphans) throw new AssertionError("Orphan fixture was not persisted on disk");
        LogUtils.getLogger().info("FLASHLIGHT_RELOAD_CHUNKS: both orphan blocks loaded before scheduled ticks resume");
    }
    private static String worldName;
    private static int savedEnergy;
    private static Path marker(Minecraft mc) { return mc.gameDirectory.toPath().resolve("smoke-world.txt"); }
    private static void readMarker(Minecraft mc) {
        try {
            var lines = Files.readAllLines(marker(mc));
            worldName = lines.get(0); savedEnergy = Integer.parseInt(lines.get(1));
        } catch (IOException e) { throw new AssertionError("Run clientSmoke before clientReloadSmoke", e); }
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("bestflashlight.smoke")) return;
        Minecraft mc=Minecraft.getInstance();
        if (awaitingBlockUse && mc.screen instanceof AbstractContainerScreen<?>) {
            if (originalHandheldKey == null) blockUseObserved = true;
            else reboundBlockUseObserved = true;
            awaitingBlockUse = false;
            mc.player.closeContainer();
        }
        if (pending != null) {
            if (!pending.isDone()) return;
            pending.join();
            pending = null;
        }
        if (resourceReload != null) {
            if (!resourceReload.isDone()) {
                if (System.nanoTime() > resourceReloadDeadlineNanos)
                    throw new AssertionError("Timed out waiting 60 seconds for Minecraft resource-pack reload");
                return;
            }
            resourceReload.join();
            resourceReload = null;
            resourceReloadDeadlineNanos = 0;
            assertRenderers(mc);
            LogUtils.getLogger().info("FLASHLIGHT_RESOURCE_RELOAD_SMOKE_PASS: native item and Curios renderers reloaded");
        }
        if(!opened && mc.screen instanceof TitleScreen && mc.getOverlay()==null) {
            opened=true;
            assertRenderers(mc);
            mc.options.renderDistance().set(4); mc.options.simulationDistance().set(5); mc.options.pauseOnLostFocus=false; mc.options.hideGui=true;
            mc.getTutorial().setStep(TutorialSteps.NONE);
            mc.getToasts().clear();
            if (RELOAD) {
                readMarker(mc);
                mc.createWorldOpenFlows().openWorld(worldName, () -> { throw new AssertionError("World reload cancelled"); });
                return;
            }
            worldName = "BestFlashlight-test-"+System.currentTimeMillis();
            mc.createWorldOpenFlows().createFreshLevel(worldName,
                new LevelSettings("BestFlashlight test",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
                new WorldOptions(4101L,false,false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
            return;
        }
        if(mc.player==null || mc.level==null || mc.screen!=null || mc.getOverlay()!=null) return;
        if(!prepared) {
            prepared=true;
            if (RELOAD) {
                serverStep(mc, () -> {
                    var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                    var band = LampSource.headband(p);
                    if (band.isEmpty() || LampEnergy.stored(band) != savedEnergy || LampData.enabled(band))
                        throw new AssertionError("Saved Curios battery/state did not survive world reload");
                    if (!LampData.mounted(band).getHoverName().getString().equals("Reload battery"))
                        throw new AssertionError("Nested custom name did not survive world reload");
                    var off = p.getOffhandItem();
                    if (!FlashlightMod.isFlashlight(off) || LampData.enabled(off) || LampEnergy.stored(off) != 1000)
                        throw new AssertionError("Disabled offhand flashlight charge/state did not survive world reload");
                    if (!reloadedOrphans) throw new AssertionError("Chunk reload preflight did not run");
                    LogUtils.getLogger().info("FLASHLIGHT_RELOAD_LOADED: saved battery {}, orphan blocks persisted", savedEnergy);
                });
                return;
            }
            FlashlightClientEvents.HANDHELD.setKey(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
            FlashlightClientEvents.HEADBAND.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_I));
            KeyMapping.resetMapping();
            serverStep(mc, () -> {
                var player=mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                var level=player.serverLevel();
                level.setDayTime(18000); level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
                for(int x=-7;x<=7;x++) for(int z=-8;z<=20;z++) {
                    level.setBlock(new BlockPos(x,-60,z),Blocks.STONE.defaultBlockState(),3);
                    level.setBlock(new BlockPos(x,-53,z),Blocks.STONE.defaultBlockState(),3);
                    for(int y=-59;y<-53;y++) level.setBlock(new BlockPos(x,y,z),
                        (x==-7||x==7||z==-8||z==20 ? Blocks.STONE : Blocks.AIR).defaultBlockState(),3);
                }
                level.setBlock(new BlockPos(0,-58,2), Blocks.BARREL.defaultBlockState(), 3);
                level.setBlock(new BlockPos(0,-58,-2), Blocks.GLOWSTONE.defaultBlockState(), 3);
                player.teleportTo(0.5,-59,0.5); player.setYRot(0); player.setXRot(0);
                ItemStack lamp=new ItemStack(FlashlightMod.FLASHLIGHT.get());
                lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(10000,false);
                lamp.set(DataComponents.CUSTOM_NAME, Component.literal("Reload battery"));
                ItemStack band=new ItemStack(FlashlightMod.HEADBAND.get()); LampData.mount(band,lamp); LampData.setEnabled(band,true);
                CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head").getStacks().setStackInSlot(0,band);
                player.setItemSlot(EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));
            });
            return;
        }
        if (awaitingNativePress) {
            if (!nativePressDeepened) {
                if (System.nanoTime() > nativePressDeadlineNanos)
                    throw new AssertionError("Accepted native press never reached a rendered deep button phase: accepted="
                        + nativePressAccepted + ", offset=" + ButtonAnimation.offset(mc.player.getUUID(),
                        net.minecraft.world.InteractionHand.MAIN_HAND, true));
                return;
            }
            awaitingNativePress = false;
        }
        ticks++;
        if (ticks >= 430 && ticks <= 450) {
            mc.player.yBodyRot = 45;
            mc.player.yBodyRotO = 45;
        }
        if (awaitingEmptyPress && ButtonAnimation.offset(mc.player.getUUID(),
                net.minecraft.world.InteractionHand.OFF_HAND, false) < -.35)
            emptyPressMoved = true;
        if (RELOAD) {
            if (ticks == 200) serverStep(mc, () -> {
                var p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                var level = p.serverLevel();
                if (!level.getBlockState(WATER_ORPHAN).is(Blocks.WATER) || !level.getFluidState(WATER_ORPHAN).isSource())
                    throw new AssertionError("Reloaded orphan light did not restore source water");
                if (!level.getBlockState(DRY_ORPHAN).isAir()) throw new AssertionError("Reloaded dry orphan light did not disappear");
                for (BlockPos pos : BlockPos.betweenClosed(-6,-59,-7,6,-54,19))
                    if (level.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get()))
                        throw new AssertionError("Light remained after world reload at " + pos);
                if (LampEnergy.stored(LampSource.headband(p)) != savedEnergy) throw new AssertionError("Disabled lamp drained after reload");
                LogUtils.getLogger().info("FLASHLIGHT_RELOAD_SMOKE_PASS: saved Curios battery/name/state, chunk reload, scheduled orphan cleanup, water preserved");
                mc.execute(mc::stop);
            });
            return;
        }
        if(ticks==100) {
            if(LampSource.headband(mc.player).isEmpty()) throw new AssertionError("Headband did not synchronize to client");
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); mc.options.fov().set(40); mc.options.hideGui=true;
        }
        if(ticks==140) shot(mc,"headband-with-helmet.png");
        if(ticks==160) serverStep(mc, () -> mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).setItemSlot(EquipmentSlot.HEAD,ItemStack.EMPTY));
        if(ticks==200) shot(mc,"headband-forehead.png");
        if(ticks==205) {
            resourceReload = mc.reloadResourcePacks();
            resourceReloadDeadlineNanos = System.nanoTime() + 60_000_000_000L;
            return;
        }
        if(ticks==210) { mc.options.keyShift.setDown(true); mc.player.setYRot(45); mc.player.setYHeadRot(45); }
        if(ticks==215) {
            if (!mc.player.isCrouching()) throw new AssertionError("Client sneak key did not produce a crouching capture pose");
            shot(mc,"headband-sneaking-turned.png"); mc.options.keyShift.setDown(false); mc.player.setYRot(0); mc.player.setYHeadRot(0);
        }
        if(ticks==220) { mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.fov().set(70); }
        if(ticks==250) shot(mc,"beam-12-blocks-15-degrees.png");
        if(ticks==270) {
            postKey(GLFW.GLFW_KEY_I, GLFW.GLFW_PRESS);
            postKey(GLFW.GLFW_KEY_I, GLFW.GLFW_REPEAT);
            postKey(GLFW.GLFW_KEY_I, GLFW.GLFW_RELEASE);
        }
        if(ticks==290) {
            if(LampData.enabled(LampSource.headband(mc.player))) throw new AssertionError("Raw I press did not turn headlamp off or repeated while held");
            mc.setScreen(new ChatScreen(""));
            postKey(GLFW.GLFW_KEY_I, GLFW.GLFW_PRESS);
            mc.setScreen(null);
            postKey(GLFW.GLFW_KEY_I, GLFW.GLFW_PRESS);
        }
        if(ticks==310) {
            if(!LampData.enabled(LampSource.headband(mc.player))) throw new AssertionError("Chat-filtered raw I press did not leave one accepted headband toggle");
            if(LampEnergy.stored(LampSource.headband(mc.player)) != 10000)
                throw new AssertionError("Creative headlamp changed FE while rendering and toggling");
            serverStep(mc, () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                LampData.setEnabled(LampSource.headband(player), false);
                ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
                lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1000, false);
                player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE));
                player.setItemSlot(EquipmentSlot.OFFHAND, lamp);
            });
        }
        if(ticks==340) {
            var blockedUse = new InputEvent.MouseButton.Pre(GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS, 0);
            NeoForge.EVENT_BUS.post(blockedUse);
            if (blockedUse.isCanceled()) throw new AssertionError("RMB consumed despite occupied non-flashlight main hand");
            if (LampData.enabled(mc.player.getOffhandItem())) throw new AssertionError("HAND_USE improperly fell back around occupied main hand");
            actualRightClick(mc, true);
        }
        if(ticks==350) {
            if (!blockUseObserved) throw new AssertionError("Occupied-main RMB did not preserve actual barrel interaction");
            serverStep(mc, () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
                lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(10000, false);
                player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
                player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            });
        }
        if(ticks==370) actualRightClick(mc, false);
        if(ticks==390) {
            if (!LampData.enabled(mc.player.getMainHandItem()) || mc.screen != null)
                throw new AssertionError("Default RMB did not suppress barrel use and toggle main-hand flashlight");
            serverStep(mc, () -> LampData.setEnabled(
                mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).getMainHandItem(), false));
        }
        if(ticks==400) {
            mc.options.hideGui = false;
            originalHandheldKey = FlashlightClientEvents.HANDHELD.getKey();
            FlashlightClientEvents.HANDHELD.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));
            mc.options.save();
            FlashlightClientEvents.HANDHELD.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_L));
            reloadOptions(mc);
            if (!FlashlightClientEvents.HANDHELD.matches(GLFW.GLFW_KEY_K, 0))
                throw new AssertionError("Handheld rebind did not survive options save/reload");
        }
        if(ticks==401) {
            shot(mc, "native-flashlight-button-first-person-raised.png");
            startCapture("button-fp");
            awaitingNativePress = true;
            nativePressAccepted = false;
            nativePressDeepened = false;
            nativePressDeadlineNanos = System.nanoTime() + 2_000_000_000L;
            postKey(GLFW.GLFW_KEY_K, GLFW.GLFW_PRESS);
            postKey(GLFW.GLFW_KEY_K, GLFW.GLFW_REPEAT);
        }
        if(ticks==402) {
            if (!LampData.enabled(mc.player.getMainHandItem())) throw new AssertionError("Native rebound key did not enable main-hand flashlight");
            if (!nativePressAccepted || !nativePressDeepened)
                throw new AssertionError("Accepted native press did not reach a rendered deep button phase");
            shot(mc, "native-flashlight-button-first-person-depressed.png");
        }
        if(ticks==410) {
            if (Math.abs(ButtonAnimation.offset(mc.player.getUUID(), net.minecraft.world.InteractionHand.MAIN_HAND, true) + .30) > .02)
                throw new AssertionError("Native renderer button did not settle to enabled state");
            shot(mc, "native-flashlight-button-first-person-latched.png");
        }
        if(ticks==420) {
            var lamp = mc.player.getMainHandItem();
            var onModel = mc.getItemRenderer().getModel(lamp, mc.level, mc.player, 0);
            ItemStack off = lamp.copy(); LampData.setEnabled(off, false);
            var offModel = mc.getItemRenderer().getModel(off, mc.level, mc.player, 0);
            if(onModel == mc.getModelManager().getMissingModel() || offModel == mc.getModelManager().getMissingModel())
                throw new AssertionError("Native flashlight model did not load");
            actualRightClick(mc, true);
        }
        if(ticks==430) {
            if (!reboundBlockUseObserved || !LampData.enabled(mc.player.getMainHandItem()))
                throw new AssertionError("RMB did not preserve actual barrel interaction after handheld rebind");
            serverStep(mc, () -> mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID())
                .serverLevel().setBlock(new BlockPos(0,-58,2), Blocks.AIR.defaultBlockState(), 3));
            mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); mc.options.fov().set(40); mc.options.hideGui = true;
            shot(mc, "native-flashlight-button-third-person-latched.png");
        }
        if(ticks==438) {
            startCapture("button-tp");
            postKey(GLFW.GLFW_KEY_K, GLFW.GLFW_PRESS);
        }
        if(ticks==440) {
            if (ButtonAnimation.offset(mc.player.getUUID(), net.minecraft.world.InteractionHand.MAIN_HAND, false) >= 0)
                throw new AssertionError("Third-person native renderer lacked button release movement");
            shot(mc, "native-flashlight-button-third-person.png");
        }
        if(ticks==450) {
            shot(mc, "native-flashlight-button-third-person-raised.png");
            serverStep(mc, () -> {
            var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE));
            ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
            lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1000, false);
            player.setItemSlot(EquipmentSlot.OFFHAND, lamp);
            });
        }
        if(ticks==455) postKey(GLFW.GLFW_KEY_K, GLFW.GLFW_PRESS);
        if(ticks==460) {
            mc.options.setCameraType(CameraType.FIRST_PERSON); mc.options.fov().set(70); mc.options.hideGui = false;
        }
        if(ticks==462) shot(mc, "offhand-flashlight-left-arm.png");
        if(ticks==470) {
            if (!LampData.enabled(mc.player.getOffhandItem()) || LampEnergy.stored(mc.player.getOffhandItem()) != 1000)
                throw new AssertionError("Rebound handheld key did not fall back to offhand with occupied main hand: main="
                    + mc.player.getMainHandItem() + ", off=" + mc.player.getOffhandItem() + ", offEnabled="
                    + LampData.enabled(mc.player.getOffhandItem()) + ", offFE=" + LampEnergy.stored(mc.player.getOffhandItem())
                    + ", binding=" + FlashlightClientEvents.HANDHELD.getKey().getName());
            FlashlightClientEvents.HANDHELD.setKey(originalHandheldKey);
            KeyMapping.resetMapping();
            mc.options.save();
            serverStep(mc, () -> mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID())
                .setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY));
        }
        if(ticks==480) {
            if (!mc.player.getMainHandItem().isEmpty()) throw new AssertionError("Empty-main RMB fixture did not synchronize");
            actualRightClick(mc, false);
        }
        if(ticks==490) {
            if (LampData.enabled(mc.player.getOffhandItem()) || LampEnergy.stored(mc.player.getOffhandItem()) != 1000)
                throw new AssertionError("Default RMB with empty main hand did not toggle offhand flashlight exactly once");
            serverStep(mc, () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.setGameMode(GameType.SURVIVAL);
                player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(FlashlightMod.FLASHLIGHT.get()));
            });
        }
        if(ticks==500) {
            awaitingEmptyPress = true;
            actualRightClick(mc, false);
        }
        if(ticks==520) {
            if (!emptyPressAccepted || !emptyPressMoved || LampData.enabled(mc.player.getOffhandItem())
                    || LampEnergy.stored(mc.player.getOffhandItem()) != 0)
                throw new AssertionError("Accepted empty survival press lacked down/up animation or changed lamp state: event="
                    + emptyPressAccepted + ", moved=" + emptyPressMoved + ", enabled="
                    + LampData.enabled(mc.player.getOffhandItem()) + ", FE=" + LampEnergy.stored(mc.player.getOffhandItem()));
            awaitingEmptyPress = false;
            serverStep(mc, () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.setGameMode(GameType.CREATIVE);
                ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
                lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(1000, false);
                player.setItemSlot(EquipmentSlot.OFFHAND, lamp);
            });
        }
        if(ticks==530) {
            LogUtils.getLogger().info("FLASHLIGHT_INPUT_SMOKE_PASS: raw press, hold/repeat, chat ignored, RMB use preserved, persisted rebind, offhand fallback");
            LogUtils.getLogger().info("FLASHLIGHT_CLIENT_SMOKE_PASS: world, head-slot sync, helmet coexistence, forehead renderer, creative FE, native FP/TP button frames");
            serverStep(mc, () -> {
                var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
                player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                LampData.setEnabled(player.getOffhandItem(), false);
                var band = LampSource.headband(player);
                LampData.setEnabled(band, false);
                savedEnergy = LampEnergy.stored(band);
                if (savedEnergy != 10000) throw new AssertionError("Creative headband must preserve full server energy");
                var level = player.serverLevel();
                for (var direction : net.minecraft.core.Direction.values())
                    level.setBlock(WATER_ORPHAN.relative(direction), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(WATER_ORPHAN, FlashlightMod.FLASHLIGHT_LIGHT.get().defaultBlockState().setValue(FlashlightLightBlock.WATERLOGGED, true), 3);
                level.setBlock(DRY_ORPHAN, FlashlightMod.FLASHLIGHT_LIGHT.get().defaultBlockState(), 3);
                try { Files.writeString(marker(mc), worldName + "\n" + savedEnergy + "\n"); }
                catch (IOException e) { throw new AssertionError(e); }
                mc.execute(mc::stop);
            });
        }
    }
    private static void shot(Minecraft mc,String name) {
        mc.getTutorial().setStep(TutorialSteps.NONE);
        mc.getToasts().clear();
        Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),message -> LogUtils.getLogger().info("Screenshot: {}",message.getString()));
    }
    private static void assertRenderers(Minecraft mc) {
        if(CuriosRendererRegistry.getRenderer(FlashlightMod.HEADBAND.get()).isEmpty()) throw new AssertionError("Missing headband renderer");
        if(mc.getItemRenderer().getModel(new ItemStack(FlashlightMod.HEADBAND.get()),null,null,0)==mc.getModelManager().getMissingModel())
            throw new AssertionError("Missing headband item model");
        assertModel(mc, ModelResourceLocation.inventory(FlashlightMod.resource("flashlight")), "flashlight inventory");
        for (String name : new String[] {"flashlight_button", "headband_empty", "headband_loaded", "headband_loaded_on"})
            assertModel(mc, ModelResourceLocation.standalone(FlashlightMod.resource("item/" + name)), name);
    }
    private static void assertModel(Minecraft mc, ModelResourceLocation id, String name) {
        if (mc.getModelManager().getModel(id) == mc.getModelManager().getMissingModel())
            throw new AssertionError("Missing baked model: " + name);
    }
    private static void postKey(int key, int action) {
        NeoForge.EVENT_BUS.post(new InputEvent.Key(key, 0, action, 0));
    }
    private static void startCapture(String prefix) {
        capturePrefix = prefix;
        captureFrame = 0;
        lastCaptureNanos = 0;
        captureUntilNanos = System.nanoTime() + 600_000_000L;
    }
    private static void reloadOptions(Minecraft mc) {
        try {
            var method = mc.options.getClass().getDeclaredMethod("load");
            method.setAccessible(true);
            method.invoke(mc.options);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not reload options.txt for binding persistence probe", e);
        }
    }
    private static void actualRightClick(Minecraft mc, boolean expectBlockUse) {
        try {
            var method = mc.mouseHandler.getClass().getDeclaredMethod("onPress", long.class, int.class, int.class, int.class);
            method.setAccessible(true);
            awaitingBlockUse = expectBlockUse;
            method.invoke(mc.mouseHandler, mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS, 0);
            method.invoke(mc.mouseHandler, mc.getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not invoke native mouse input path", e);
        }
    }
}
