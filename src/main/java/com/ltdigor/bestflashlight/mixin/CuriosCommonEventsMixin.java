package com.ltdigor.bestflashlight.mixin;

import com.ltdigor.bestflashlight.FlashlightEquipmentSync;
import com.ltdigor.bestflashlight.FlashlightNetwork;
import com.ltdigor.bestflashlight.LampData;
import com.ltdigor.bestflashlight.LampEnergy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.theillusivec4.curios.common.CuriosCommonEvents;

@Mixin(CuriosCommonEvents.class)
abstract class CuriosCommonEventsMixin {
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;matches(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"
        ),
        require = 0
    )
    private boolean bestflashlight$syncEnergyWithoutCuriosStateChurn(
        ItemStack current,
        ItemStack previous,
        EntityTickEvent.Post event
    ) {
        if (FlashlightEquipmentSync.isEnergyOnlyChange(current, previous)
            && event.getEntity() instanceof ServerPlayer player
            && player.connection.hasChannel(FlashlightNetwork.HeadbandEnergy.TYPE)) {
            PacketDistributor.sendToPlayer(
                player,
                new FlashlightNetwork.HeadbandEnergy(LampEnergy.stored(current))
            );

            ItemStack currentLamp = LampData.mounted(current);
            if (!currentLamp.isEmpty()) {
                // Advance Curios' previous snapshot so this ENERGY-only change does not
                // trigger a State event, modifier rebuild, or tracking SPacketSyncStack.
                LampData.mount(previous, currentLamp);
            }
            return true;
        }

        return ItemStack.matches(current, previous);
    }
}
