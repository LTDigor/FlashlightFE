package com.ltdigor.bestflashlight;

import com.ltdigor.bestflashlight.mixin.AbstractContainerMenuAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Avoids full menu slot packets for the per-tick FE drain of a held flashlight. */
final class FlashlightOwnerSync {
    private FlashlightOwnerSync() {}

    static void syncDrain(ServerPlayer player, LampSource source) {
        if (source.headMounted() || player.isCreative() || FlashlightConfig.ENERGY_PER_TICK.get() <= 0) return;
        if (!player.connection.hasChannel(FlashlightNetwork.HandheldEnergy.TYPE)) return;

        ItemStack current = source.stack();
        int afterEnergy = LampEnergy.stored(current);
        int beforeEnergy = Math.min(
            FlashlightConfig.ENERGY_CAPACITY.get(),
            afterEnergy + FlashlightConfig.ENERGY_PER_TICK.get()
        );
        int inventorySlot = source.offHand() ? Inventory.SLOT_OFFHAND : player.getInventory().selected;
        PacketDistributor.sendToPlayer(
            player,
            new FlashlightNetwork.HandheldEnergy(inventorySlot, beforeEnergy, afterEnergy)
        );
        advanceOnlyEnergy(player.containerMenu, current);
    }

    static void advanceOnlyEnergy(AbstractContainerMenu menu, ItemStack current) {
        var remote = ((AbstractContainerMenuAccessor) menu).bestflashlight$getRemoteSlots();
        int count = Math.min(menu.slots.size(), remote.size());
        for (int i = 0; i < count; i++) {
            if (menu.slots.get(i).getItem() != current) continue;
            ItemStack previous = remote.get(i);
            if (!FlashlightEquipmentSync.isEnergyOnlyChange(previous, current)) continue;

            ItemStack normalized = previous.copy();
            normalized.set(LampData.ENERGY.get(), LampEnergy.stored(current));
            remote.set(i, normalized);
        }
    }
}
