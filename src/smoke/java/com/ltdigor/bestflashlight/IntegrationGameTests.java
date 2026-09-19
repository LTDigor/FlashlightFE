package com.ltdigor.bestflashlight;

import com.ltdigor.bestflashlight.mixin.AbstractContainerMenuAccessor;
import blusunrize.immersiveengineering.common.blocks.metal.ChargingStationBlockEntity;
import blusunrize.immersiveengineering.common.register.IEBlocks;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class IntegrationGameTests {
    @GameTest(template = "empty")
    public static void immersiveChargingStationChargesBothForms(GameTestHelper helper) {
        BlockPos pos = new BlockPos(3, 1, 3);
        helper.setBlock(pos, IEBlocks.MetalDevices.CHARGING_STATION.get());
        ChargingStationBlockEntity station = helper.getBlockEntity(pos);
        for (boolean mounted : List.of(false, true)) {
            ItemStack item = lamp(0);
            if (mounted) { ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get()); LampData.mount(band, item); item = band; }
            helper.assertTrue(station.isStackValid(0, item), "IE station must accept the item");
            station.inventory.set(0, item);
            station.energyStorage.receiveEnergy(32_000, false);
            station.tickServer();
            var battery = item.getCapability(Capabilities.EnergyStorage.ITEM);
            int first = battery.getEnergyStored();
            helper.assertTrue(first > 0 && first <= 10_000, "Real IE tick must transfer FE");
            for (int i = 0; i < 100; i++) { station.energyStorage.receiveEnergy(32_000, false); station.tickServer(); }
            helper.assertTrue(battery.getEnergyStored() == 10_000, "IE must fill either form without overcharging");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void tickSelectionConsumesExactlyOneSource(GameTestHelper helper) {
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fe-test"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(helper.absolutePos(new BlockPos(8, 1, 3)).getCenter());
            ItemStack main = lamp(3), off = lamp(20);
            LampData.setEnabled(main, true); LampData.setEnabled(off, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, main); player.setItemSlot(EquipmentSlot.OFFHAND, off);
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(main) == 2 && LampEnergy.stored(off) == 20, "One emitting source consumes exactly one FE");
            LampData.setEnabled(main, false);
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(main) == 2 && LampEnergy.stored(off) == 19, "Disabled primary permits offhand beam");
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(off) == 19, "Unequipped lamp has no idle consumption");
            main = lamp(1); LampData.setEnabled(main, true); player.setItemSlot(EquipmentSlot.MAINHAND, main);
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(main) == 0 && !LampData.enabled(main),
                "Final affordable FE tick must leave the lamp off immediately without negative charge");
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ownerSnapshotSuppressesOnlyPureEnergyDiffs(GameTestHelper helper) {
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "owner-sync"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            ItemStack lamp = lamp(20);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
            player.inventoryMenu.sendAllDataToRemote();

            int slotIndex = -1;
            for (int i = 0; i < player.inventoryMenu.slots.size(); i++) {
                if (player.inventoryMenu.slots.get(i).getItem() == lamp) {
                    slotIndex = i;
                    break;
                }
            }
            helper.assertTrue(slotIndex >= 0, "Main-hand flashlight must appear in the inventory menu");

            var remote = ((AbstractContainerMenuAccessor) player.inventoryMenu).bestflashlight$getRemoteSlots();
            helper.assertTrue(LampEnergy.stored(remote.get(slotIndex)) == 20 && !LampData.enabled(remote.get(slotIndex)),
                "Fixture remote snapshot must start disabled with full test charge");

            LampData.setEnabled(lamp, true);
            lamp.set(LampData.ENERGY.get(), 19);
            FlashlightOwnerSync.advanceOnlyEnergy(player.inventoryMenu, lamp);
            helper.assertTrue(LampEnergy.stored(remote.get(slotIndex)) == 20 && !LampData.enabled(remote.get(slotIndex)),
                "ENERGY optimization must not swallow a simultaneous enabled-state change");

            player.inventoryMenu.sendAllDataToRemote();
            int expectedClientEnergy = LampEnergy.stored(remote.get(slotIndex));
            lamp.set(LampData.ENERGY.get(), 37);
            int reportedBefore = FlashlightOwnerSync.advanceOnlyEnergy(player.inventoryMenu, lamp);
            helper.assertTrue(reportedBefore == expectedClientEnergy,
                "Owner FE packet guard must use the actual remote snapshot, not infer the previous charge");
            helper.assertTrue(LampEnergy.stored(remote.get(slotIndex)) == 37 && LampData.enabled(remote.get(slotIndex)),
                "Any pure ENERGY diff, including an external charge jump, must advance the remote menu snapshot");
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingCarrierInvalidatesOwnerBeamCacheImmediately(GameTestHelper helper) {
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "carrier-cache"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(helper.absolutePos(new BlockPos(8, 1, 3)).getCenter());
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            ItemStack lamp = lamp(20);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(beamCache(player.getUUID()) != null,
                "Emitting player must own a server beam cache");

            BlockPos carrier = null;
            for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 15)) {
                if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
                    carrier = helper.absolutePos(pos);
                    break;
                }
            }
            helper.assertTrue(carrier != null, "Beam must place at least one temporary carrier");

            helper.getLevel().setBlock(carrier, Blocks.STONE.defaultBlockState(), 3);

            helper.assertTrue(beamCache(player.getUUID()) == null,
                "Replacing an owned carrier must invalidate the owner's cached beam immediately");
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void staticBeamCacheStillDrainsAndInvalidatesOnMovement(GameTestHelper helper) {
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "beam-cache"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(helper.absolutePos(new BlockPos(8, 1, 3)).getCenter());
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            ItemStack lamp = lamp(20);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            Object firstCache = beamCache(player.getUUID());
            helper.assertTrue(firstCache != null && LampEnergy.stored(lamp) == 19,
                "First emitting tick must build beam cache and consume FE");

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            Object secondCache = beamCache(player.getUUID());
            helper.assertTrue(firstCache == secondCache,
                "Static second tick must reuse cached server beam geometry");
            helper.assertTrue(LampEnergy.stored(lamp) == 18,
                "Reusing beam geometry must still consume FE every tick");

            player.setPos(player.getX() + 0.25, player.getY(), player.getZ());
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            Object movedCache = beamCache(player.getUUID());
            helper.assertTrue(movedCache != null && movedCache != secondCache,
                "Player movement must invalidate cached beam geometry immediately");
            helper.assertTrue(LampEnergy.stored(lamp) == 17,
                "Recomputed beam must still consume exactly one tick of FE");
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void curiosHeadSlotEmitsWithFreeHands(GameTestHelper helper) {
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fe-test"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            var inventory = CuriosApi.getCuriosInventory(player).orElseThrow();
            var head = inventory.getCurios().get("head");
            helper.assertTrue(head != null && head.getStacks().getSlots() == 1, "Player must receive one Curios head slot");
            ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get()); LampData.mount(band, lamp(50));
            LampData.setEnabled(band, true); head.getStacks().setStackInSlot(0, band);
            player.setPos(helper.absolutePos(new BlockPos(8, 1, 3)).getCenter());
            helper.assertTrue(LampSource.select(player).headMounted() && LampSource.toggleTarget(player).equals("headband"), "Free hands must select forehead lamp");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(head.getStacks().getStackInSlot(0)) == 49, "Curios battery drains once per emitting tick");
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));
            helper.assertTrue(LampSource.select(player).stack().getItem() instanceof HeadbandItem, "Armor helmet must coexist with band");
        } finally { remove(helper, player); }
        helper.succeed();
    }


    @GameTest(template = "empty")
    public static void emptyPrioritySourceFallsThroughInSameTick(GameTestHelper helper) {
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fallback-energy"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(helper.absolutePos(new BlockPos(8, 1, 3)).getCenter());
            ItemStack main = lamp(0);
            ItemStack off = lamp(10);
            LampData.setEnabled(main, true);
            LampData.setEnabled(off, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, main);
            player.setItemSlot(EquipmentSlot.OFFHAND, off);

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            helper.assertTrue(!LampData.enabled(main),
                "An empty higher-priority source must switch itself off");
            helper.assertTrue(LampEnergy.stored(off) == 9,
                "A powered fallback source must emit in the same tick without a blackout gap");
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "config_underwater_fallback")
    public static void submergedHandheldDoesNotStarveDryHeadband(GameTestHelper helper) {
        boolean originalWorksUnderwater = FlashlightConfig.WORKS_UNDERWATER.get();
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fallback-water"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(8.5, 1.4, 3.5)));
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            helper.setBlock(8, 2, 4, Blocks.WATER);

            ItemStack main = lamp(10);
            LampData.setEnabled(main, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, main);

            ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
            LampData.mount(band, lamp(10));
            LampData.setEnabled(band, true);
            var head = CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head").getStacks();
            head.setStackInSlot(0, band);

            FlashlightConfig.WORKS_UNDERWATER.set(false);
            FlashlightConfig.WORKS_UNDERWATER.clearCache();
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            helper.assertTrue(LampEnergy.stored(main) == 10,
                "A submerged handheld skipped by config must not consume FE");
            helper.assertTrue(LampEnergy.stored(head.getStackInSlot(0)) == 9,
                "A dry enabled headband must emit instead of being starved by the submerged handheld");
        } finally {
            FlashlightConfig.WORKS_UNDERWATER.set(originalWorksUnderwater);
            FlashlightConfig.WORKS_UNDERWATER.clearCache();
            remove(helper, player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "config_underwater_zero_cost")
    public static void waterDisabledAndZeroCostBehave(GameTestHelper helper) {
        boolean originalWorksUnderwater = FlashlightConfig.WORKS_UNDERWATER.get();
        int originalEnergyPerTick = FlashlightConfig.ENERGY_PER_TICK.get();
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fe-test"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(helper.absolutePos(new BlockPos(8, 1, 3)).getCenter());
            for (int x=6;x<=10;x++) for(int y=1;y<=4;y++) for(int z=1;z<=6;z++) helper.setBlock(x,y,z,Blocks.WATER);
            ItemStack item = lamp(100); LampData.setEnabled(item,true); player.setItemSlot(EquipmentSlot.MAINHAND,item);
            FlashlightConfig.WORKS_UNDERWATER.set(false); FlashlightConfig.WORKS_UNDERWATER.clearCache();
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(item)==100, "Disabled underwater light must not consume");
            FlashlightConfig.WORKS_UNDERWATER.set(true); FlashlightConfig.WORKS_UNDERWATER.clearCache();
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(item)==99, "Enabled underwater light consumes normally");
            FlashlightConfig.ENERGY_PER_TICK.set(0); FlashlightConfig.ENERGY_PER_TICK.clearCache();
            ItemStack empty=lamp(0); LampData.setEnabled(empty,true);
            helper.assertTrue(LampEnergy.consume(empty) && LampEnergy.stored(empty)==0, "Zero cost permits empty battery without underflow");
        } finally {
            FlashlightConfig.WORKS_UNDERWATER.set(originalWorksUnderwater);
            FlashlightConfig.WORKS_UNDERWATER.clearCache();
            FlashlightConfig.ENERGY_PER_TICK.set(originalEnergyPerTick);
            FlashlightConfig.ENERGY_PER_TICK.clearCache();
            remove(helper,player);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void deathDimensionLogoutClearOwnedLight(GameTestHelper helper) {
        ServerPlayer player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "fe-test"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            player.setPos(helper.absolutePos(new BlockPos(8,1,3)).getCenter());
            ItemStack lamp=lamp(100); LampData.setEnabled(lamp,true); player.setItemSlot(EquipmentSlot.MAINHAND,lamp);
            for (int reason=0;reason<3;reason++) {
                FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
                helper.assertTrue(lightCount(helper)>0,"Tick must create actual light blocks");
                if(reason==0) FlashlightEvents.onLivingDeath(new LivingDeathEvent(player,helper.getLevel().damageSources().generic()));
                if(reason==1) FlashlightEvents.onPlayerChangedDimension(new PlayerEvent.PlayerChangedDimensionEvent(player,Level.OVERWORLD,Level.NETHER));
                if(reason==2) FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
                helper.assertTrue(lightCount(helper)==0,"Lifecycle event must remove the player's lights");
            }
        } finally { remove(helper,player); }
        helper.succeed();
    }
    private static int lightCount(GameTestHelper helper) {
        int count=0;
        for(BlockPos pos:BlockPos.betweenClosed(0,0,0,15,7,15)) if(helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) count++;
        return count;
    }

    @GameTest(template = "empty", batch = "emitter_occlusion")
    public static void emitterOffsetCannotShineThroughNearbyPane(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "pane-test"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            var pane = Blocks.GLASS_PANE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WEST, true);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 8; y++) helper.setBlock(new BlockPos(x, y, 4), pane);
            var feet = helper.absoluteVec(new net.minecraft.world.phys.Vec3(8.5, 1, 4.12));
            player.setPos(feet.x, feet.y, feet.z);
            player.setYRot(0); player.setXRot(0);
            ItemStack lamp = lamp(50); LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(lamp) == 49,
                "A nearby partial collision must clip the beam without switching the flashlight off");
            boolean cameraSideLight = false;
            for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 4)) {
                if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
                    cameraSideLight = true;
                    break;
                }
            }
            helper.assertTrue(cameraSideLight,
                "A pane sharing the eye block must retain a temporary light on the camera side");
            for (BlockPos pos : BlockPos.betweenClosed(0, 0, 5, 15, 7, 15)) {
                helper.assertTrue(!helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                    "Handheld emitter must not jump through nearby glass pane");
            }
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "emitter_occlusion")
    public static void partialCollisionCellCanUseOpenSide(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "pane-open-side-test"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            var pane = Blocks.GLASS_PANE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WEST, true);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 8; y++) {
                helper.setBlock(new BlockPos(x, y, 4), pane);
            }

            var feet = helper.absoluteVec(new net.minecraft.world.phys.Vec3(8.5, 1, 4.12));
            player.setPos(feet.x, feet.y, feet.z);
            player.setYRot(180.0F);
            player.setXRot(0.0F);
            ItemStack lamp = lamp(50);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            boolean openSideLight = false;
            int paneZ = helper.absolutePos(new BlockPos(0, 0, 4)).getZ();
            for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 15)) {
                if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
                    helper.assertTrue(pos.getZ() < paneZ,
                        "Looking away from a pane must never place fallback light through its collision plane");
                    openSideLight = true;
                }
            }
            helper.assertTrue(openSideLight,
                "A partial-collision eye cell must still allow fallback light on its open side");
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "emitter_occlusion")
    public static void partialCollisionFallbackCannotJumpThroughWallBehindPlayer(GameTestHelper helper) {
        var player = new net.neoforged.neoforge.common.util.FakePlayer(helper.getLevel(),
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "pane-back-wall-test"));
        try {
            player.setGameMode(GameType.SURVIVAL);
            var pane = Blocks.GLASS_PANE.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.EAST, true)
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WEST, true);
            for (int x = 0; x < 16; x++) for (int y = 0; y < 8; y++) {
                helper.setBlock(new BlockPos(x, y, 4), pane);
                helper.setBlock(new BlockPos(x, y, 3), Blocks.STONE);
            }
            var feet = helper.absoluteVec(new net.minecraft.world.phys.Vec3(8.5, 1, 4.12));
            player.setPos(feet.x, feet.y, feet.z);
            player.setYRot(0); player.setXRot(0);
            ItemStack lamp = lamp(50); LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 2)) {
                helper.assertTrue(!helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                    "Close-wall fallback must never tunnel through a solid block behind the player");
            }
        } finally { remove(helper, player); }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Object beamCache(UUID player) {
        try {
            Field field = FlashlightEvents.class.getDeclaredField("BEAM_CACHE");
            field.setAccessible(true);
            return ((Map<UUID, Object>) field.get(null)).get(player);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect server beam cache", exception);
        }
    }

    private static ItemStack lamp(int energy) {
        ItemStack lamp=new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy,false);
        return lamp;
    }
    private static void remove(GameTestHelper helper,ServerPlayer player) {
        FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
        player.discard();
    }
}
