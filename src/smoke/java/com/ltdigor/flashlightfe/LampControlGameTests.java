package com.ltdigor.flashlightfe;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class LampControlGameTests {
    @GameTest(template = "empty")
    public static void actionsRouteToTheirIndependentLampTargets(GameTestHelper helper) {
        FakePlayer player = player(helper, "action-routing");
        try {
            ItemStack main = lamp(), off = lamp(), band = band();
            player.setItemSlot(EquipmentSlot.MAINHAND, main);
            player.setItemSlot(EquipmentSlot.OFFHAND, off);
            head(player).setStackInSlot(0, band);

            helper.assertTrue("main".equals(LampControl.target(player, LampControl.Action.HAND_USE)),
                "Hand use must prefer main-hand flashlight");
            helper.assertTrue("main".equals(LampControl.target(player, LampControl.Action.HAND_KEY)),
                "Hand key must prefer main-hand flashlight");
            helper.assertTrue("headband".equals(LampControl.target(player, LampControl.Action.HEADBAND)),
                "Headband key must target headband independently");

            player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE));
            helper.assertTrue(LampControl.target(player, LampControl.Action.HAND_USE) == null,
                "Use must preserve occupied main-hand interaction instead of falling back to offhand");
            helper.assertTrue("off".equals(LampControl.target(player, LampControl.Action.HAND_KEY)),
                "Dedicated hand key must fall back to offhand with occupied main hand");
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            helper.assertTrue("off".equals(LampControl.target(player, LampControl.Action.HAND_USE)),
                "Use may select offhand only while main hand is empty");

            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            helper.assertTrue(LampControl.target(player, LampControl.Action.HAND_USE) == null
                    && LampControl.target(player, LampControl.Action.HAND_KEY) == null,
                "Hand actions must reject missing inventory targets");
            helper.assertTrue("headband".equals(LampControl.target(player, LampControl.Action.HEADBAND)),
                "Hand inventory changes must not affect headband action");
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pressRevalidatesInventoryAndCreativeEnergyRules(GameTestHelper helper) {
        FakePlayer player = player(helper, "action-press");
        try {
            ItemStack main = lamp(20);
            player.setItemSlot(EquipmentSlot.MAINHAND, main);
            helper.assertTrue(LampControl.press(player, LampControl.Action.HAND_KEY) == 1 && LampData.enabled(main),
                "Accepted hand press must toggle actual equipped stack on");
            helper.assertTrue(LampControl.press(player, LampControl.Action.HAND_KEY) == 1 && !LampData.enabled(main),
                "Second accepted hand press must toggle same stack off");

            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            helper.assertTrue(LampControl.press(player, LampControl.Action.HAND_KEY) == 0,
                "Server must reject stale press after inventory target disappears");

            ItemStack empty = lamp();
            player.setItemSlot(EquipmentSlot.MAINHAND, empty);
            helper.assertTrue(LampControl.press(player, LampControl.Action.HAND_KEY) == 1 && !LampData.enabled(empty),
                "Valid empty survival press must be accepted while leaving lamp disabled");
            player.setGameMode(GameType.CREATIVE);
            helper.assertTrue(LampControl.press(player, LampControl.Action.HAND_KEY) == 1 && LampData.enabled(empty),
                "Creative must accept enabling zero-charge handheld lamp");

            ItemStack band = band();
            head(player).setStackInSlot(0, band);
            helper.assertTrue(LampControl.press(player, LampControl.Action.HEADBAND) == 1 && LampData.enabled(band),
                "Creative headband action must toggle zero-charge mounted lamp");
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(helper.absolutePos(new net.minecraft.core.BlockPos(8, 1, 3)).getCenter());
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static ItemStack lamp() {
        return new ItemStack(FlashlightMod.FLASHLIGHT.get());
    }

    private static ItemStack lamp(int energy) {
        ItemStack lamp = lamp();
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy, false);
        return lamp;
    }

    private static ItemStack band() {
        ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(band, lamp());
        return band;
    }

    private static top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler head(FakePlayer player) {
        return CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head").getStacks();
    }
}
