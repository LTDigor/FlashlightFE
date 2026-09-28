package com.ltdigor.bestflashlight;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** One reversible recipe; output preview never mutates either input. */
public final class HeadbandRecipe extends CustomRecipe {
    public HeadbandRecipe(CraftingBookCategory category) { super(category); }

    private record Inputs(ItemStack band, ItemStack lamp, int bandSlot, boolean detach) {}
    private static Inputs inputs(CraftingInput input) {
        ItemStack band = ItemStack.EMPTY, lamp = ItemStack.EMPTY;
        int slot = -1;
        for (int i = 0; i < input.size(); i++) {
            ItemStack item = input.getItem(i);
            if (item.isEmpty()) continue;
            if (item.getItem() instanceof HeadbandItem && band.isEmpty() && item.getCount() == 1) {
                band = item; slot = i;
            } else if (FlashlightMod.isFlashlight(item) && lamp.isEmpty() && item.getCount() == 1) {
                lamp = item;
            } else return null;
        }
        if (band.isEmpty()) return null;
        boolean loaded = !LampData.mounted(band).isEmpty();
        // Do not overwrite malformed or foreign embedded contents.
        if (!loaded && band.has(LampData.MOUNTED.get())) return null;
        return loaded && lamp.isEmpty() ? new Inputs(band, LampData.mounted(band), slot, true)
            : !loaded && !lamp.isEmpty() ? new Inputs(band, lamp, slot, false) : null;
    }
    @Override public boolean matches(CraftingInput input, Level level) { return inputs(input) != null; }
    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider access) {
        Inputs in = inputs(input);
        if (in == null) return ItemStack.EMPTY;
        ItemStack lamp = in.lamp.copyWithCount(1);
        LampData.setEnabled(lamp, false);
        if (in.detach) return lamp;
        ItemStack band = in.band.copyWithCount(1);
        LampData.mount(band, lamp);
        return band;
    }
    @Override public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        var remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        Inputs in = inputs(input);
        if (in != null && in.detach) {
            ItemStack band = in.band.copyWithCount(1);
            band.remove(LampData.MOUNTED.get());
            remaining.set(in.bandSlot, band);
        }
        return remaining;
    }
    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 1; }
    @Override public RecipeSerializer<?> getSerializer() { return FlashlightMod.HEADBAND_RECIPE.get(); }
}
