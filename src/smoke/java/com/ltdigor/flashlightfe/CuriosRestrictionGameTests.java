package com.ltdigor.flashlightfe;

import com.ltdigor.flashlightfe.lighting.ServerBeamLightingManager;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class CuriosRestrictionGameTests {
    @GameTest(template = "empty")
    public static void serverTickReturnsFlashlightsFromFunctionalAndCosmeticSlots(GameTestHelper helper) {
        var player = player(helper, "curio-return");
        try {
            var head = CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head");
            helper.assertTrue(head != null && head.getStacks().getSlots() >= 1, "Curios head slot must exist");
            helper.assertTrue(!CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().containsKey("curio"),
                "Mod must not provide the former generic curio slot");
            ItemStack rejected = namedLamp("Rejected", 1);
            var curio = CuriosApi.getCurio(rejected).orElseThrow();
            helper.assertTrue(!curio.canEquip(new SlotContext("head", player, 0, false, true))
                    && !curio.canEquip(new SlotContext("curio", player, 0, false, true)),
                "Ordinary flashlight capability must reject head and generic Curios slots");
            head.getStacks().setStackInSlot(0, namedLamp("Functional survivor", 123));
            head.getCosmeticStacks().setStackInSlot(0, namedLamp("Cosmetic survivor", 456));

            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());

            helper.assertTrue(head.getStacks().getStackInSlot(0).isEmpty(), "Ordinary flashlight must leave functional Curios slot");
            helper.assertTrue(head.getCosmeticStacks().getStackInSlot(0).isEmpty(), "Ordinary flashlight must leave cosmetic Curios slot");
            assertInventoryLamp(helper, player, "Functional survivor", 123);
            assertInventoryLamp(helper, player, "Cosmetic survivor", 456);
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullInventoryUsesDropFallbackAndLeavesOtherCuriosUntouched(GameTestHelper helper) {
        var player = player(helper, "curio-full");
        try {
            for (int i = 0; i < player.getInventory().items.size(); i++)
                player.getInventory().items.set(i, new ItemStack(Items.STONE, 64));
            var head = CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head");
            head.getStacks().setStackInSlot(0, namedLamp("Dropped survivor", 789));
            head.getCosmeticStacks().setStackInSlot(0, new ItemStack(Items.STONE, 7));

            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());

            helper.assertTrue(head.getStacks().getStackInSlot(0).isEmpty(), "Invalid flashlight must be cleared before fallback");
            ItemStack retained = head.getCosmeticStacks().getStackInSlot(0);
            helper.assertTrue(retained.is(Items.STONE) && retained.getCount() == 7,
                "Other Curios items and slots must remain untouched");
            var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3));
            helper.assertTrue(drops.stream().anyMatch(entity -> entity.getItem().getHoverName().getString().equals("Dropped survivor")
                    && LampEnergy.stored(entity.getItem()) == 789),
                "Full inventory must use native dropped-item fallback without losing components");
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        var player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(helper.absolutePos(new net.minecraft.core.BlockPos(8, 1, 3)).getCenter());
        return player;
    }

    private static ItemStack namedLamp(String name, int energy) {
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy, false);
        return lamp;
    }

    private static void assertInventoryLamp(GameTestHelper helper, FakePlayer player, String name, int energy) {
        helper.assertTrue(player.getInventory().items.stream().anyMatch(stack -> stack.getHoverName().getString().equals(name)
                && LampEnergy.stored(stack) == energy),
            "Returned flashlight must preserve name, charge, and components: " + name);
    }
}
