package com.ltdigor.flashlightfe.mixin;

import com.ltdigor.flashlightfe.client.ShaderBeamBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void bestflashlight$compiled(CallbackInfo ci) { ShaderBeamBridge.finish(this); }

    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Ljava/lang/Object;<init>()V", shift = At.Shift.AFTER), require = 0)
    private void bestflashlight$compiling(CallbackInfo ci) { ShaderBeamBridge.begin(this); }

    @Inject(method = "beginLevelRendering", at = @At("HEAD"), require = 0)
    private void bestflashlight$frame(CallbackInfo ci) { ShaderBeamBridge.frame(this); }

    @Inject(method = "destroy", at = @At("HEAD"), require = 0)
    private void bestflashlight$destroy(CallbackInfo ci) { ShaderBeamBridge.destroy(this); }
}
