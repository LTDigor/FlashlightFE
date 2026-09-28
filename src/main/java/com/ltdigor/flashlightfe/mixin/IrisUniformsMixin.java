package com.ltdigor.flashlightfe.mixin;

import com.ltdigor.flashlightfe.client.ShaderBeamBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CommonUniforms", remap = false)
public abstract class IrisUniformsMixin {
    @Inject(method = "addNonDynamicUniforms", at = @At("TAIL"), require = 0)
    private static void bestflashlight$uniforms(@Coerce Object holder, @Coerce Object idMap,
                                              @Coerce Object directives, @Coerce Object notifier, CallbackInfo ci) {
        ShaderBeamBridge.registerUniforms(holder);
    }
}
