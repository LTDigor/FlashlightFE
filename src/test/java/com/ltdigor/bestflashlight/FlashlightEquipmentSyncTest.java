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

    @Test void nestedHeadbandEnergyOnlyChangeIsIgnoredForCuriosTracking() {
        ItemStack beforeLamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        beforeLamp.set(LampData.ENERGY.get(), 100);
        ItemStack afterLamp = beforeLamp.copy();
        afterLamp.set(LampData.ENERGY.get(), 99);

        ItemStack beforeBand = new ItemStack(FlashlightMod.HEADBAND.get());
        ItemStack afterBand = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(beforeBand, beforeLamp);
        LampData.mount(afterBand, afterLamp);

        assertTrue(FlashlightEquipmentSync.isEnergyOnlyChange(beforeBand, afterBand));
        assertTrue(FlashlightEquipmentSync.matchesIgnoringEnergy(beforeBand, afterBand));
    }

    @Test void nestedHeadbandEnabledChangeStillSyncs() {
        ItemStack beforeLamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        beforeLamp.set(LampData.ENERGY.get(), 100);
        ItemStack afterLamp = beforeLamp.copy();
        afterLamp.set(LampData.ENERGY.get(), 99);
        LampData.setEnabled(afterLamp, true);

        ItemStack beforeBand = new ItemStack(FlashlightMod.HEADBAND.get());
        ItemStack afterBand = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(beforeBand, beforeLamp);
        LampData.mount(afterBand, afterLamp);

        assertFalse(FlashlightEquipmentSync.isEnergyOnlyChange(beforeBand, afterBand));
        assertFalse(FlashlightEquipmentSync.matchesIgnoringEnergy(beforeBand, afterBand));
    }

    @Test void advancingDirectEnergySnapshotChangesOnlyEnergy() {
        ItemStack snapshot = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        snapshot.set(LampData.ENERGY.get(), 100);
        snapshot.set(DataComponents.CUSTOM_NAME, Component.literal("Named"));

        ItemStack current = snapshot.copy();
        current.set(LampData.ENERGY.get(), 42);

        FlashlightEquipmentSync.advanceDirectEnergySnapshot(snapshot, current);

        assertEquals(42, LampEnergy.stored(snapshot));
        assertEquals(Component.literal("Named"), snapshot.get(DataComponents.CUSTOM_NAME));
        assertFalse(LampData.enabled(snapshot));

        LampData.setEnabled(current, true);
        assertTrue(FlashlightEquipmentSync.isEnergyOnlyChange(snapshot, current) == false,
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
