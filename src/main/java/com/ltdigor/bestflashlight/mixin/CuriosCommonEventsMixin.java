package com.ltdigor.bestflashlight.mixin;

import com.ltdigor.bestflashlight.FlashlightEquipmentSync;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import top.theillusivec4.curios.common.CuriosCommonEvents;

@Mixin(CuriosCommonEvents.class)
abstract class CuriosCommonEventsMixin {
    private static final ThreadLocal<Boolean> BESTFLASHLIGHT_ENERGY_ONLY =
        ThreadLocal.withInitial(() -> false);

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;matches(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z"
        ),
        require = 0
    )
    private boolean bestflashlight$markEnergyOnlyCurioDiff(ItemStack current, ItemStack previous) {
        BESTFLASHLIGHT_ENERGY_ONLY.set(
            FlashlightEquipmentSync.isEnergyOnlyChange(current, previous)
        );
        return ItemStack.matches(current, previous);
    }

    @Redirect(
        method = "syncCurios",
        at = @At(
            value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToPlayersTrackingEntityAndSelf(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"
        ),
        require = 0
    )
    private static void bestflashlight$routeEnergyOnlySyncToOwner(
        Entity entity,
        CustomPacketPayload payload,
        CustomPacketPayload[] payloads
    ) {
        boolean energyOnly = BESTFLASHLIGHT_ENERGY_ONLY.get();
        BESTFLASHLIGHT_ENERGY_ONLY.set(false);
        if (energyOnly && entity instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, payload, payloads);
        } else {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload, payloads);
        }
    }
}
