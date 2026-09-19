package com.ltdigor.bestflashlight;

import com.mojang.authlib.GameProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class EnergyGameTests {
    @GameTest(template = "empty")
    public static void chargingSimulationAndCapacity(GameTestHelper helper) {
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        var energy = lamp.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(energy != null, "Flashlight must expose FE capability");
        helper.assertTrue(energy.getEnergyStored() == 0, "New flashlight must start empty");
        helper.assertTrue(energy.receiveEnergy(20_000, true) == 10_000, "Simulation caps transfer at capacity");
        helper.assertTrue(energy.getEnergyStored() == 0, "Simulation must not charge battery");
        helper.assertTrue(energy.receiveEnergy(20_000, false) == 10_000, "Real transfer must cap capacity");
        helper.assertTrue(energy.receiveEnergy(1, false) == 0, "Full battery refuses energy");
        helper.assertTrue(energy.extractEnergy(100, false) == 0, "Battery cannot power other devices");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeCommandsToggleEveryEmptyLampWithoutChangingCharge(GameTestHelper helper) {
        FakePlayer player = player(helper, "creative-energy", GameType.CREATIVE);
        try {
            for (ItemStack lamp : lampTypes()) {
                String target;
                if (lamp.getItem() instanceof HeadbandItem) {
                    var head = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).orElseThrow()
                        .getCurios().get("head").getStacks();
                    head.setStackInSlot(0, lamp);
                    target = "headband";
                } else {
                    player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
                    target = "main";
                }
                toggle(player, target);
                helper.assertTrue(LampData.enabled(lamp), "Creative command must enable every empty lamp type");
                FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
                helper.assertTrue(LampData.enabled(lamp) && LampEnergy.stored(lamp) == 0,
                    "Creative empty lamp must emit without charge or forced shutoff");
                helper.assertTrue(LampSource.select(player) != null,
                    "Creative empty lamp must remain an active beam source");
                helper.assertTrue(hasLight(helper), "Creative empty lamp must create actual light blocks");
                toggle(player, target);
                helper.assertTrue(!LampData.enabled(lamp), "Creative command must switch every lamp type off");
                lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(37, false);
                toggle(player, target);
                FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
                helper.assertTrue(LampEnergy.stored(lamp) == 37,
                    "Creative emission must preserve partial FE for every lamp type");
                helper.assertTrue(hasLight(helper), "Creative partially charged lamp must create actual light blocks");
                toggle(player, target);
                FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
                helper.assertTrue(!hasLight(helper), "Disabled creative lamp must remove its light before next lamp fixture");
                clearEquipment(player);
            }

            ItemStack main = lamp(37), off = lamp(0);
            player.setItemSlot(EquipmentSlot.MAINHAND, main);
            player.setItemSlot(EquipmentSlot.OFFHAND, off);
            toggle(player, "main");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(main) == 37, "Creative main-hand emission must preserve partial FE");
            toggle(player, "main");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(!hasLight(helper), "Disabled main-hand lamp must remove light before offhand fixture");
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            toggle(player, "off");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampData.enabled(off) && LampEnergy.stored(off) == 0,
                "Creative offhand command must enable and emit with zero FE");
        } finally {
            FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void survivalDrainReturnsAfterCreativeMode(GameTestHelper helper) {
        FakePlayer player = player(helper, "mode-energy", GameType.CREATIVE);
        ItemStack lamp = lamp(2);
        try {
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
            toggle(player, "main");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(lamp) == 2, "Creative emission must preserve FE");
            player.setGameMode(GameType.SURVIVAL);
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(lamp) == 1 && LampData.enabled(lamp),
                "Changing to survival must immediately restore FE drain");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(lamp) == 0 && LampData.enabled(lamp),
                "Last available survival FE must still power one tick");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(!LampData.enabled(lamp), "Empty survival lamp must switch itself off");

            ItemStack transitioning = lamp(2);
            player.setItemSlot(EquipmentSlot.MAINHAND, transitioning);
            toggle(player, "main");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(transitioning) == 1, "Survival fixture must drain before mode change");
            player.setGameMode(GameType.CREATIVE);
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampEnergy.stored(transitioning) == 1 && LampData.enabled(transitioning),
                "Changing to creative must immediately stop FE drain without disabling the lamp");
        } finally {
            FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "config_energy_cost")
    public static void creativeBypassesEnergyCostAboveCapacity(GameTestHelper helper) {
        FakePlayer player = player(helper, "creative-high-cost", GameType.CREATIVE);
        int previous = FlashlightConfig.ENERGY_PER_TICK.get();
        try {
            FlashlightConfig.ENERGY_PER_TICK.set(20_000);
            FlashlightConfig.ENERGY_PER_TICK.clearCache();
            ItemStack empty = lamp(0);
            player.setItemSlot(EquipmentSlot.MAINHAND, empty);
            toggle(player, "main");
            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            helper.assertTrue(LampData.enabled(empty) && LampSource.select(player) != null && LampEnergy.stored(empty) == 0,
                "Creative lamp must bypass an FE cost greater than battery capacity");
        } finally {
            FlashlightConfig.ENERGY_PER_TICK.set(previous);
            FlashlightConfig.ENERGY_PER_TICK.clearCache();
            FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper, String name, GameType mode) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(helper.absolutePos(new net.minecraft.core.BlockPos(8, 1, 3)).getCenter());
        player.setGameMode(mode);
        return player;
    }

    private static void toggle(FakePlayer player, String target) {
        player.getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "bestflashlighttoggle " + target);
    }

    private static void clearEquipment(FakePlayer player) {
        player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).orElseThrow()
            .getCurios().get("head").getStacks().setStackInSlot(0, ItemStack.EMPTY);
    }

    private static ItemStack lamp(int energy) {
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy, false);
        return lamp;
    }

    private static List<ItemStack> lampTypes() {
        ItemStack mounted = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(mounted, lamp(0));
        return List.of(lamp(0), mounted);
    }

    private static boolean hasLight(GameTestHelper helper) {
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(0, 0, 0, 15, 7, 15))
            if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) return true;
        return false;
    }
}
