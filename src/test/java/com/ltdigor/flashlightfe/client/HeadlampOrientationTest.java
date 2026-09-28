package com.ltdigor.flashlightfe.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class HeadlampOrientationTest {
    @Test void strapSitsAboveHeadPivotAndLensFacesForward() {
        PoseStack pose = new PoseStack();
        HeadlampOrientation.apply(pose);

        // Model pixels: strap around (8,14,3), lens at (8,14,1.9).
        Vector3f strap = pose.last().pose().transformPosition(new Vector3f(8f / 16f, 14f / 16f, 3f / 16f));
        Vector3f lens = pose.last().pose().transformPosition(new Vector3f(8f / 16f, 14f / 16f, 1.9f / 16f));
        assertEquals(0f, strap.x, 0.0001f);
        assertTrue(strap.y < 0f, "strap belongs above the head pivot");
        assertTrue(lens.z < strap.z && lens.z < 0f, "lens must face the local -Z front");
        assertTrue(pose.last().pose().determinant() > 0f, "orientation must preserve face winding");
    }
}
