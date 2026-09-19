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

    @Test void energyIndependentSignatureIgnoresChargeButNotState() {
        ItemStack lampA = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lampA.set(LampData.ENERGY.get(), 100);
        ItemStack lampB = lampA.copy();
        lampB.set(LampData.ENERGY.get(), 7);

        ItemStack bandA = new ItemStack(FlashlightMod.HEADBAND.get());
        ItemStack bandB = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(bandA, lampA);
        LampData.mount(bandB, lampB);

        assertEquals(
            FlashlightEquipmentSync.signatureIgnoringEnergy(bandA),
            FlashlightEquipmentSync.signatureIgnoringEnergy(bandB),
            "Headband identity signature must ignore nested FE charge"
        );

        LampData.setEnabled(bandB, true);
        assertNotEquals(
            FlashlightEquipmentSync.signatureIgnoringEnergy(bandA),
            FlashlightEquipmentSync.signatureIgnoringEnergy(bandB),
            "Enabled state must remain part of the identity signature"
        );

        LampData.setEnabled(bandB, false);
        bandB.set(DataComponents.CUSTOM_NAME, Component.literal("Other"));
        assertNotEquals(
            FlashlightEquipmentSync.signatureIgnoringEnergy(bandA),
            FlashlightEquipmentSync.signatureIgnoringEnergy(bandB),
            "Custom name must remain part of the identity signature"
        );
    }

    @Test void otherItemsAreNeverSpecialCased() {
        ItemStack before = new ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
        ItemStack after = before.copy();
        after.set(DataComponents.CUSTOM_NAME, Component.literal("Changed"));

        assertFalse(FlashlightEquipmentSync.isEnergyOnlyChange(before, after));
    }
}
