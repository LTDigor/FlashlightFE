package com.ltdigor.flashlightfe.mixin;

import com.ltdigor.flashlightfe.client.ShaderBeamBridge;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.programs.ProgramSource", remap = false)
public abstract class IrisProgramSourceMixin {
    @Shadow @Final private String name;

    @Inject(method = "getFragmentSource", at = @At("RETURN"), cancellable = true, require = 0)
    private void bestflashlight$surfaceShader(CallbackInfoReturnable<Optional<String>> cir) {
        cir.getReturnValue().ifPresent(source -> ShaderBeamBridge.patch(name, source)
            .ifPresent(patched -> cir.setReturnValue(Optional.of(patched))));
    }
}
