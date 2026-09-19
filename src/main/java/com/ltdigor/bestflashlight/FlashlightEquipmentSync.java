package com.ltdigor.bestflashlight;

import net.minecraft.world.item.ItemStack;

/** Keeps per-tick FE drain from looking like an equipment swap to remote trackers. */
public final class FlashlightEquipmentSync {
    private FlashlightEquipmentSync() {}

    public static boolean isEnergyOnlyChange(ItemStack before, ItemStack after) {
        if (ItemStack.matches(before, after)) return false;

        if (FlashlightMod.isFlashlight(before) && FlashlightMod.isFlashlight(after)) {
            return matchesIgnoringDirectEnergy(before, after);
        }

        if (before.getItem() instanceof HeadbandItem && after.getItem() instanceof HeadbandItem) {
            ItemStack beforeLamp = LampData.mounted(before);
            ItemStack afterLamp = LampData.mounted(after);
            if (beforeLamp.isEmpty() || afterLamp.isEmpty()) return false;
            if (!matchesIgnoringDirectEnergy(beforeLamp, afterLamp)) return false;

            ItemStack beforeNormalized = before.copy();
            ItemStack afterNormalized = after.copy();
            beforeLamp.remove(LampData.ENERGY.get());
            afterLamp.remove(LampData.ENERGY.get());
            LampData.mount(beforeNormalized, beforeLamp);
            LampData.mount(afterNormalized, afterLamp);
            return ItemStack.matches(beforeNormalized, afterNormalized);
        }

        return false;
    }

    public static void advanceDirectEnergySnapshot(ItemStack snapshot, ItemStack current) {
        if (FlashlightMod.isFlashlight(snapshot) && FlashlightMod.isFlashlight(current)) {
            snapshot.set(LampData.ENERGY.get(), current.getOrDefault(LampData.ENERGY.get(), 0));
        }
    }

    public static boolean matchesIgnoringEnergy(ItemStack first, ItemStack second) {
        return ItemStack.matches(first, second) || isEnergyOnlyChange(first, second);
    }

    private static boolean matchesIgnoringDirectEnergy(ItemStack before, ItemStack after) {
        ItemStack beforeWithoutEnergy = before.copy();
        ItemStack afterWithoutEnergy = after.copy();
        beforeWithoutEnergy.remove(LampData.ENERGY.get());
        afterWithoutEnergy.remove(LampData.ENERGY.get());
        return ItemStack.matches(beforeWithoutEnergy, afterWithoutEnergy);
    }
}
