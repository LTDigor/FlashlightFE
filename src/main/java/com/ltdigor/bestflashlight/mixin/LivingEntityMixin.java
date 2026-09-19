package com.ltdigor.bestflashlight.mixin;

import com.ltdigor.bestflashlight.FlashlightEquipmentSync;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class LivingEntityMixin {
    @Inject(method = "equipmentHasChanged", at = @At("HEAD"), cancellable = true)
    private void bestflashlight$ignoreEnergyOnlyEquipmentDiff(ItemStack before, ItemStack after,
                                                               CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.level().isClientSide && FlashlightEquipmentSync.isEnergyOnlyChange(before, after)) {
            cir.setReturnValue(false);
        }
    }
}
