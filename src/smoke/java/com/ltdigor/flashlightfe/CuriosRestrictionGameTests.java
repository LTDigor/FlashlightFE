package com.ltdigor.flashlightfe;

import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class CuriosRestrictionGameTests {
    @GameTest(template = "empty")
    public static void ordinaryFlashlightCannotEquipToCuriosHead(GameTestHelper helper) {
        FakePlayer player = player(helper, "curio-flashlight");
        try {
            var inventory = CuriosApi.getCuriosInventory(player).orElseThrow();
            var head = inventory.getCurios().get("head");
            helper.assertTrue(head != null && head.getStacks().getSlots() >= 1,
                "Existing Curios head slot must be available");
            helper.assertTrue(!inventory.getCurios().containsKey("curio"),
                "Mod must not create a generic Curios slot");

            ItemStack flashlight = new ItemStack(FlashlightMod.FLASHLIGHT.get());
            helper.assertTrue(!head.getStacks().isItemValid(0, flashlight),
                "Ordinary flashlight must fail actual Curios head inventory validation");
            helper.assertTrue(!CuriosApi.getCurio(flashlight).map(curio ->
                curio.canEquip(new SlotContext("head", player, 0, false, true))).orElse(false),
                "Ordinary flashlight must not claim Curios head equip permission");
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void headlampCanEquipOnlyInHeadSlot(GameTestHelper helper) {
        FakePlayer player = player(helper, "curio-headlamp");
        try {
            var inventory = CuriosApi.getCuriosInventory(player).orElseThrow();
            var head = inventory.getCurios().get("head");
            helper.assertTrue(head != null && head.getStacks().getSlots() >= 1,
                "Existing Curios head slot must be available");

            ItemStack lamp = new ItemStack(FlashlightMod.HEADLAMP.get());
            var curio = CuriosApi.getCurio(lamp).orElseThrow();
            helper.assertTrue(head.getStacks().isItemValid(0, lamp),
                "Standalone headlamp must pass actual Curios head inventory validation");
            helper.assertTrue(curio.canEquip(new SlotContext("head", player, 0, false, true)),
                "Standalone headlamp must permit head slot");
            for (String other : new String[]{"curio", "charm", "back"}) {
                helper.assertTrue(!curio.canEquip(new SlotContext(other, player, 0, false, true)),
                    "Standalone headlamp must reject non-head slot: " + other);
            }
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void equipFromUseLeavesOccupiedHeadAndCosmeticSlotsUntouched(GameTestHelper helper) {
        FakePlayer player = player(helper, "curio-occupied");
        try {
            var inventory = CuriosApi.getCuriosInventory(player).orElseThrow();
            var curios = inventory.getCurios();
            var head = curios.get("head");
            helper.assertTrue(head != null && head.getStacks().getSlots() >= 1,
                "Existing Curios head slot must be available");
            Map<String, Integer> slotCounts = new HashMap<>();
            curios.forEach((name, handler) -> slotCounts.put(name, handler.getStacks().getSlots()));

            ItemStack occupied = namedHeadlamp("Occupied headlamp");
            head.getStacks().setStackInSlot(0, occupied);
            head.getCosmeticStacks().setStackInSlot(0, new ItemStack(Items.STONE, 7));
            ItemStack held = namedHeadlamp("Held headlamp");
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));

            FlashlightMod.HEADLAMP.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

            helper.assertTrue(ItemStack.isSameItemSameComponents(head.getStacks().getStackInSlot(0), occupied),
                "Equip from use must not replace occupied functional head slot");
            helper.assertTrue(head.getCosmeticStacks().getStackInSlot(0).is(Items.STONE)
                    && head.getCosmeticStacks().getStackInSlot(0).getCount() == 7,
                "Equip from use must not modify cosmetic stack");
            helper.assertTrue(ItemStack.isSameItemSameComponents(player.getItemInHand(InteractionHand.MAIN_HAND), held),
                "Rejected equip must leave held headlamp intact");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                "Rejected Curios equip must leave vanilla head slot intact");
            helper.assertTrue(curios.keySet().equals(slotCounts.keySet()),
                "Equip from use must not create or remove Curios slot types");
            curios.forEach((name, handler) -> helper.assertTrue(handler.getStacks().getSlots() == slotCounts.get(name),
                "Equip from use must not resize Curios slot type: " + name));
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper, String name) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(helper.absolutePos(new BlockPos(8, 1, 3)).getCenter());
        return player;
    }

    private static ItemStack namedHeadlamp(String name) {
        ItemStack lamp = new ItemStack(FlashlightMod.HEADLAMP.get());
        lamp.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return lamp;
    }
}
