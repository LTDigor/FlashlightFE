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

    public static int signatureIgnoringEnergy(ItemStack stack) {
        if (FlashlightMod.isFlashlight(stack)) {
            ItemStack normalized = stack.copy();
            normalized.remove(LampData.ENERGY.get());
            return ItemStack.hashItemAndComponents(normalized);
        }
        if (stack.getItem() instanceof HeadbandItem) {
            ItemStack mounted = LampData.mounted(stack);
            if (mounted.isEmpty()) return ItemStack.hashItemAndComponents(stack);
            mounted.remove(LampData.ENERGY.get());
            ItemStack normalized = stack.copy();
            LampData.mount(normalized, mounted);
            return ItemStack.hashItemAndComponents(normalized);
        }
        return ItemStack.hashItemAndComponents(stack);
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
