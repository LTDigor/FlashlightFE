package com.ltdigor.bestflashlight;

import blusunrize.immersiveengineering.common.blocks.metal.ChargingStationBlockEntity;
import blusunrize.immersiveengineering.common.register.IEBlocks;
import java.util.List;
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
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(main) == 0 && !LampData.enabled(main), "Empty lamp disables without negative charge");
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

    @GameTest(template = "empty", batch = "config")
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
