package com.ltdigor.flashlightfe;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class HeadlampGameTests {
    @GameTest(template = "empty")
    public static void headlampUsesVanillaHeadEquipmentSlot(GameTestHelper helper) {
        ItemStack lamp = new ItemStack(FlashlightMod.HEADLAMP.get());
        helper.assertTrue(lamp.getItem() instanceof Equipable, "Headlamp must be equipable");
        helper.assertTrue(((Equipable) lamp.getItem()).getEquipmentSlot() == net.minecraft.world.entity.EquipmentSlot.HEAD,
            "Headlamp must use vanilla head equipment slot");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void independentRecipeAndDirectBatterySurviveSerialization(GameTestHelper helper) {
        var level = helper.getLevel();
        var input = CraftingInput.of(3, 3, List.of(new ItemStack(Items.STRING), new ItemStack(Items.RED_WOOL),
            new ItemStack(Items.STRING), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GLASS),
            new ItemStack(Items.IRON_INGOT), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.REDSTONE),
            new ItemStack(Items.COPPER_INGOT)));
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        helper.assertTrue(recipe.isPresent(), "Raw materials must directly craft headlamp");
        ItemStack lamp = recipe.orElseThrow().value().assemble(input, level.registryAccess());
        helper.assertTrue(lamp.is(FlashlightMod.HEADLAMP.get()) && lamp.getCount() == 1, "Exactly one headlamp");
        var energy = lamp.getCapability(Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(energy != null && energy.getEnergyStored() == 0 && !LampData.enabled(lamp), "New headlamp is discharged/off");
        energy.receiveEnergy(2400, false);
        helper.assertTrue(energy.receiveEnergy(600, true) == 600 && energy.getEnergyStored() == 2400, "Simulation does not mutate FE");
        energy.receiveEnergy(600, false);
        lamp.set(DataComponents.CUSTOM_NAME, Component.literal("Cave lamp"));
        LampData.setEnabled(lamp, true);
        lamp = ItemStack.parseOptional(level.registryAccess(), (net.minecraft.nbt.CompoundTag) lamp.save(level.registryAccess()));
        helper.assertTrue(LampEnergy.stored(lamp) == 3000 && LampData.enabled(lamp), "Direct charge/state survives save");
        helper.assertTrue(lamp.getHoverName().getString().equals("Cave lamp"), "Name survives save");
        helper.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
            CraftingInput.of(1, 1, List.of(lamp)), level).isEmpty(), "Headlamp cannot disassemble");
        helper.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
            CraftingInput.of(2, 1, List.of(lamp, new ItemStack(FlashlightMod.FLASHLIGHT.get()))), level).isEmpty(), "No mounting recipe");
        helper.assertTrue(BuiltInRegistries.ITEM.keySet().stream().filter(id -> id.getNamespace().equals("bestflashlight")).count() == 2,
            "Exactly two registered items");
        helper.assertTrue(!BuiltInRegistries.ITEM.containsKey(FlashlightMod.resource("headband")), "No empty headband item");
        helper.succeed();
    }
}
