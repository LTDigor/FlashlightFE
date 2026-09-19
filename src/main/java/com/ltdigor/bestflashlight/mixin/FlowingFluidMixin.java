package com.ltdigor.bestflashlight.mixin;

import com.ltdigor.bestflashlight.FlashlightLightBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FlowingFluid.class)
abstract class FlowingFluidMixin {
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
        )
    )
    private boolean bestflashlight$preserveLightCarrier(
        Level level, BlockPos pos, BlockState replacement, int flags
    ) {
        return FlashlightLightBlock.preserveCarrierDuringFluidTick(level, pos, replacement, flags);
    }
}
