package com.ltdigor.bestflashlight;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlashlightEquipmentSyncTest {
    @Test void energyOnlyChangeIsIgnoredForRemoteEquipmentTracking() {
        ItemStack before = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        ItemStack after = before.copy();
        before.set(LampData.ENERGY.get(), 100);
        after.set(LampData.ENERGY.get(), 99);

        assertTrue(FlashlightEquipmentSync.isEnergyOnlyChange(before, after));
    }

    @Test void enabledChangeStillCountsAsEquipmentChange() {
        ItemStack before = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        ItemStack after = before.copy();
        before.set(LampData.ENERGY.get(), 100);
        after.set(LampData.ENERGY.get(), 99);
        LampData.setEnabled(after, true);

        assertFalse(FlashlightEquipmentSync.isEnergyOnlyChange(before, after));
    }

    @Test void customNameChangeStillCountsAsEquipmentChange() {
        ItemStack before = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        ItemStack after = before.copy();
        before.set(LampData.ENERGY.get(), 100);
        after.set(LampData.ENERGY.get(), 99);
        after.set(DataComponents.CUSTOM_NAME, Component.literal("Renamed"));

        assertFalse(FlashlightEquipmentSync.isEnergyOnlyChange(before, after));
    }

    @Test void otherItemsAreNeverSpecialCased() {
        ItemStack before = new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
        ItemStack after = before.copy();
        after.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));

        assertFalse(FlashlightEquipmentSync.isEnergyOnlyChange(before, after));
    }
}
