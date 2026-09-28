package com.ltdigor.flashlightfe;

import com.ltdigor.flashlightfe.mixin.AbstractContainerMenuAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Avoids full menu slot packets for the per-tick FE drain of a held flashlight. */
public final class FlashlightOwnerSync {
    private FlashlightOwnerSync() {}

    public static void syncDrain(ServerPlayer player, LampSource source) {
        if (player instanceof FakePlayer || player.isCreative()
            || FlashlightConfig.ENERGY_PER_TICK.get() <= 0.0) return;

        ItemStack current = source.stack();
        if (source.headMounted()) {
            if (current == player.getItemBySlot(EquipmentSlot.HEAD)
                && player.connection.hasChannel(FlashlightNetwork.HeadlampEnergy.TYPE)) {
                advanceOnlyEnergy(player.containerMenu, current);
                PacketDistributor.sendToPlayer(player,
                    new FlashlightNetwork.HeadlampEnergy(-1, LampEnergy.stored(current)));
            }
            return;
        }
        if (!player.connection.hasChannel(FlashlightNetwork.HandheldEnergy.TYPE)) return;
        advanceOnlyEnergy(player.containerMenu, current);

        int inventorySlot = source.offHand() ? Inventory.SLOT_OFFHAND : player.getInventory().selected;
        PacketDistributor.sendToPlayer(
            player,
            new FlashlightNetwork.HandheldEnergy(inventorySlot, LampEnergy.stored(current))
        );
    }

    static boolean advanceOnlyEnergy(AbstractContainerMenu menu, ItemStack current) {
        var remote = ((AbstractContainerMenuAccessor) menu).bestflashlight$getRemoteSlots();
        int count = Math.min(menu.slots.size(), remote.size());
        boolean advanced = false;
        for (int i = 0; i < count; i++) {
            if (menu.slots.get(i).getItem() != current) continue;
            ItemStack previous = remote.get(i);
            if (!FlashlightEquipmentSync.isEnergyOnlyChange(previous, current)) continue;

            ItemStack normalized = previous.copy();
            normalized.set(LampData.ENERGY.get(), LampEnergy.stored(current));
            remote.set(i, normalized);
            advanced = true;
        }
        return advanced;
    }
}
