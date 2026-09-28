package com.ltdigor.flashlightfe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/** Maps item-model pixels into the Curios head pose (front is local -Z). */
final class HeadlampOrientation {
    private HeadlampOrientation() {}

    static void apply(PoseStack pose) {
        // A half turn around Z moves the model's high Y strap above the head
        // pivot while preserving its -Z front and face winding.
        pose.mulPose(Axis.ZP.rotationDegrees(180));
        pose.translate(-0.5, -0.5, -0.5);
    }
}
