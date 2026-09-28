package com.ltdigor.flashlightfe;

import com.ltdigor.flashlightfe.mixin.BestFlashlightMixinPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptionalShaderClassloadingTest {
    @Test void noIrisRuntimeLoadsModAndSkipsEveryIrisMixin() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("net.irisshaders.iris.Iris"));
        for (String name : new String[]{"IrisProgramSourceMixin", "IrisUniformsMixin", "IrisPipelineMixin"}) {
            assertFalse(new BestFlashlightMixinPlugin().shouldApplyMixin("unused", "com.ltdigor.flashlightfe.mixin." + name));
        }
        assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.client.ShaderBeamBridge"));
        assertDoesNotThrow(() -> Class.forName("com.ltdigor.flashlightfe.client.ComplementaryBeamPatch"));
    }
}
