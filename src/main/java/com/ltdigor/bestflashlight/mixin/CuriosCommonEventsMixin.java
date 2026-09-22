package com.ltdigor.bestflashlight.mixin;

import com.ltdigor.bestflashlight.FlashlightEquipmentSync;
import com.ltdigor.bestflashlight.FlashlightNetwork;
import com.ltdigor.bestflashlight.LampData;
import com.ltdigor.bestflashlight.LampEnergy;
import com.ltdigor.bestflashlight.LampSource;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "top.theillusivec4.curios.common.event.CuriosEventHandler", remap = false)
abstract class CuriosCommonEventsMixin {
    private static final ThreadLocal<Integer> BESTFLASHLIGHT_SLOT_INDEX =
        ThreadLocal.withInitial(() -> -1);

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Ltop/theillusivec4/curios/api/type/inventory/IDynamicStackHandler;getPreviousStackInSlot(I)Lnet/minecraft/world/item/ItemStack;",
            remap = false
        ),
        require = 0,
        remap = false
    )
    private ItemStack bestflashlight$captureCurioSlot(
        Object handler,
        int slotIndex,
        EntityTickEvent.Post event
    ) {
        BESTFLASHLIGHT_SLOT_INDEX.set(slotIndex);
        try {
            return (ItemStack) handler.getClass().getMethod("getPreviousStackInSlot", int.class).invoke(handler, slotIndex);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios stack handler changed", exception);
        }
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;matches(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)Z",
            remap = true
        ),
        require = 0,
        remap = false
    )
    private boolean bestflashlight$syncEnergyWithoutCuriosStateChurn(
        ItemStack current,
        ItemStack previous,
        EntityTickEvent.Post event
    ) {
        int slotIndex = BESTFLASHLIGHT_SLOT_INDEX.get();
        BESTFLASHLIGHT_SLOT_INDEX.set(-1);
        if (FlashlightEquipmentSync.isEnergyOnlyChange(current, previous)
            && event.getEntity() instanceof ServerPlayer player
            && slotIndex >= 0
            && LampSource.headband(player, slotIndex) == current
            && player.connection.hasChannel(FlashlightNetwork.HeadbandEnergy.TYPE)) {
            PacketDistributor.sendToPlayer(
                player,
                new FlashlightNetwork.HeadbandEnergy(
                    slotIndex,
                    LampEnergy.stored(current)
                )
            );

            ItemStack currentLamp = LampData.mounted(current);
            if (!currentLamp.isEmpty()) {
                // getPreviousStackInSlot returns Curios' mutable previous snapshot.
                // Advancing only the mounted ENERGY state avoids a CurioChangeEvent,
                // modifier rebuild, and tracking SPacketSyncStack for every FE drain tick.
                LampData.mount(previous, currentLamp);
            }
            return true;
        }

        return ItemStack.matches(current, previous);
    }
}
