package com.ltdigor.flashlightfe.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComplementaryBeamPatchTest {
    private static final String PACK = "ComplementaryReimagined_r5.9.3.zip";
    // The two integration points, not a redistributed copy of the shader pack.
    private static final String SOURCE = "#version 330 compatibility\n"
        + "void DoLighting(inout vec4 color, vec3 playerPos) {\n"
        + "vec3 finalDiffuse = pow2(directionShade * vanillaAO) * (blockLighting + pow2(sceneLighting) + minLighting) + pow2(emission);\n"
        + "finalDiffuse = sqrt(max(finalDiffuse, vec3(0.0)));\n}\n";

    @Test void addsSurfaceLightBeforeToneMappingWithoutChangingVersionDirective() {
        String result = ComplementaryBeamPatch.patch(PACK, "gbuffers_terrain", SOURCE).orElseThrow();
        assertTrue(result.startsWith("#version 330 compatibility"));
        assertTrue(result.contains("uniform vec4 bestflashlightBeamParams"));
        assertTrue(result.indexOf("finalDiffuse +=") < result.indexOf("finalDiffuse = sqrt"));
        assertTrue(result.contains("fwidth("), "Sub-pixel edge must be antialiased");
        assertFalse(result.contains("0.875"), "Pixel lighting must have no block-grid radius floor");
        assertFalse(result.contains("XLIGHT_I"), "Iris has already expanded pack macros before this hook");
        assertEquals(result, ComplementaryBeamPatch.patch(PACK, "gbuffers_terrain", result).orElseThrow());
    }

    @Test void unsupportedOrAmbiguousShadersStayUntouched() {
        assertTrue(ComplementaryBeamPatch.patch("Other.zip", "gbuffers_terrain", SOURCE).isEmpty());
        assertTrue(ComplementaryBeamPatch.patch("ComplementaryReimagined_r5.9.4.zip", "gbuffers_terrain", SOURCE).isEmpty());
        assertTrue(ComplementaryBeamPatch.patch(PACK, "gbuffers_terrain", SOURCE.replace("finalDiffuse = sqrt", "other = sqrt")).isEmpty());
        assertTrue(ComplementaryBeamPatch.patch(PACK, "gbuffers_terrain", SOURCE + SOURCE).isEmpty());
        assertTrue(ComplementaryBeamPatch.patch(PACK, "gbuffers_hand", SOURCE).isEmpty());
        assertTrue(ComplementaryBeamPatch.patch(PACK, "shadow", SOURCE).isEmpty());
    }

    @Test void whitespaceFromPreprocessingDoesNotChangeTheIntegrationPoints() {
        assertTrue(ComplementaryBeamPatch.patch(PACK, "gbuffers_terrain",
            SOURCE.replace(" = ", "=").replace(" += ", "+=")).isPresent());
    }
}
