package com.ltdigor.bestflashlight;

import net.minecraft.world.item.ItemStack;

/** Keeps per-tick FE drain from looking like an equipment swap to remote trackers. */
public final class FlashlightEquipmentSync {
    private FlashlightEquipmentSync() {}

    public static boolean isEnergyOnlyChange(ItemStack before, ItemStack after) {
        if (!FlashlightMod.isFlashlight(before) || !FlashlightMod.isFlashlight(after)) return false;
        if (ItemStack.matches(before, after)) return false;

        ItemStack beforeWithoutEnergy = before.copy();
        ItemStack afterWithoutEnergy = after.copy();
        beforeWithoutEnergy.remove(LampData.ENERGY.get());
        afterWithoutEnergy.remove(LampData.ENERGY.get());
        return ItemStack.matches(beforeWithoutEnergy, afterWithoutEnergy);
    }
}
