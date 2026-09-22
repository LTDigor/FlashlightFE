package com.ltdigor.bestflashlight;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class HeadbandGameTests {
    @GameTest(template = "empty")
    public static void headbandUsesVanillaHeadEquipmentSlotWithoutCurios(GameTestHelper helper) {
        ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
        helper.assertTrue(band.getItem() instanceof Equipable, "Headband must be equipable without Curios");
        helper.assertTrue(((Equipable) band.getItem()).getEquipmentSlot() == net.minecraft.world.entity.EquipmentSlot.HEAD,
            "Headband must use vanilla head equipment slot without Curios");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void assemblyChargingAndDisassemblyConserveLamp(GameTestHelper helper) {
        var level = helper.getLevel();
        ItemStack band = new ItemStack(BuiltInRegistries.ITEM.get(FlashlightMod.resource("headband")));
        helper.assertTrue(!band.isEmpty(), "Headband must be registered");
        helper.assertTrue(band.getCapability(Capabilities.EnergyStorage.ITEM) == null, "Empty band has no battery");
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.set(DataComponents.CUSTOM_NAME, Component.literal("Cave lamp"));
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(2400, false);
        FlashlightMod.toggleFlashlight(lamp);
        CraftingInput input = CraftingInput.of(2, 1, List.of(band, lamp));
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(recipe.isPresent(), "Headband and lamp must assemble");
        ItemStack assembled = recipe.orElseThrow().value().assemble(input, level.registryAccess());
        var energy = assembled.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(energy != null && energy.getEnergyStored() == 2400, "Assembly conserves charge");
        helper.assertTrue(!LampData.enabled(assembled), "Assembly switches lamp off");
        helper.assertTrue(energy.receiveEnergy(600, true) == 600 && energy.getEnergyStored() == 2400, "Nested simulation must not mutate");
        energy.receiveEnergy(600, false);
        var encoded = assembled.save(level.registryAccess());
        assembled = ItemStack.parseOptional(level.registryAccess(), (net.minecraft.nbt.CompoundTag)encoded);
        helper.assertTrue(assembled.getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored() == 3000, "Mounted charge survives serialization");
        CraftingInput detach = CraftingInput.of(1, 1, List.of(assembled));
        var detachRecipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, detach, level);
        helper.assertTrue(detachRecipe.isPresent(), "Assembled band must disassemble");
        ItemStack restored = detachRecipe.orElseThrow().value().assemble(detach, level.registryAccess());
        var remaining = detachRecipe.orElseThrow().value().getRemainingItems(detach);
        helper.assertTrue(restored.is(FlashlightMod.FLASHLIGHT.get()) && restored.getCount() == 1, "Return exactly one flashlight");
        helper.assertTrue(restored.getCapability(Capabilities.EnergyStorage.ITEM).getEnergyStored() == 3000, "Detachment conserves accumulated charge");
        helper.assertTrue(restored.getHoverName().getString().equals("Cave lamp"), "Detachment preserves original name");
        helper.assertTrue(remaining.size() == 1 && remaining.getFirst().getItem() instanceof HeadbandItem, "Return exactly one empty headband");
        helper.assertTrue(remaining.getFirst().getCapability(Capabilities.EnergyStorage.ITEM) == null, "Returned band cannot retain a duplicate battery");
        helper.succeed();
    }
}
