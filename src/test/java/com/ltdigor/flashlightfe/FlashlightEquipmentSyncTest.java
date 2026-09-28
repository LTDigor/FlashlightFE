package com.ltdigor.flashlightfe;

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

    @Test void directHeadlampEnergyOnlyChangeIsIgnoredForCuriosTracking() {
        ItemStack before = new ItemStack(FlashlightMod.HEADLAMP.get());
        before.set(LampData.ENERGY.get(), 100);
        ItemStack after = before.copy();
        after.set(LampData.ENERGY.get(), 99);

        assertTrue(FlashlightEquipmentSync.isEnergyOnlyChange(before, after));
    }

    @Test void directHeadlampEnabledChangeStillSyncs() {
        ItemStack before = new ItemStack(FlashlightMod.HEADLAMP.get());
        before.set(LampData.ENERGY.get(), 100);
        ItemStack after = before.copy();
        after.set(LampData.ENERGY.get(), 99);
        LampData.setEnabled(after, true);

        assertFalse(FlashlightEquipmentSync.isEnergyOnlyChange(before, after));
    }

    @Test void advancingDirectEnergySnapshotChangesOnlyEnergy() {
        ItemStack snapshot = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        snapshot.set(LampData.ENERGY.get(), 100);
        snapshot.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));

        ItemStack current = snapshot.copy();
        current.set(LampData.ENERGY.get(), 42);

        FlashlightEquipmentSync.advanceDirectEnergySnapshot(snapshot, current);

        assertEquals(42, snapshot.getOrDefault(LampData.ENERGY.get(), 0));
        assertEquals(Component.literal("Named"), snapshot.get(DataComponents.CUSTOM_NAME));
        assertFalse(LampData.enabled(snapshot));

        LampData.setEnabled(current, true);
        assertFalse(FlashlightEquipmentSync.isEnergyOnlyChange(snapshot, current),
            "A later enabled-state change must still be visible after the FE snapshot advance");
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
