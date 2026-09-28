package com.ltdigor.flashlightfe;

import net.minecraft.world.item.ItemStack;

/** Keeps per-tick FE drain from looking like an equipment swap to remote trackers. */
public final class FlashlightEquipmentSync {
    private FlashlightEquipmentSync() {}

    public static boolean isEnergyOnlyChange(ItemStack before, ItemStack after) {
        if (ItemStack.matches(before, after)) return false;

        if (LampData.isLamp(before) && ItemStack.isSameItem(before, after)) {
            return matchesIgnoringDirectEnergy(before, after);
        }

        return false;
    }

    public static void advanceDirectEnergySnapshot(ItemStack snapshot, ItemStack current) {
        if (LampData.isLamp(snapshot) && ItemStack.isSameItem(snapshot, current)) {
            snapshot.set(LampData.ENERGY.get(), current.getOrDefault(LampData.ENERGY.get(), 0));
        }
    }

    private static boolean matchesIgnoringDirectEnergy(ItemStack before, ItemStack after) {
        ItemStack beforeWithoutEnergy = before.copy();
        ItemStack afterWithoutEnergy = after.copy();
        beforeWithoutEnergy.remove(LampData.ENERGY.get());
        afterWithoutEnergy.remove(LampData.ENERGY.get());
        return ItemStack.matches(beforeWithoutEnergy, afterWithoutEnergy);
    }
}
