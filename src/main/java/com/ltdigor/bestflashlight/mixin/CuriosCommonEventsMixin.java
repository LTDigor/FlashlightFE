package com.ltdigor.bestflashlight.mixin;

import com.ltdigor.bestflashlight.FlashlightEquipmentSync;
import net.minecraft.world.item.ItemStack;
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
    private boolean bestflashlight$ignoreEnergyOnlyCurioDiff(ItemStack current, ItemStack previous) {
        return FlashlightEquipmentSync.matchesIgnoringEnergy(current, previous);
    }
}
