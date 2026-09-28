package com.ltdigor.flashlightfe.client;

import net.minecraft.world.phys.Vec3;

/** Immutable per-frame input; no world access from a shader uniform supplier. */
public record ShaderBeamState(boolean active, Vec3 origin, Vec3 direction,
                              double range, double halfAngle, int brightness, double softness) {
    public static final ShaderBeamState OFF = new ShaderBeamState(false, Vec3.ZERO, new Vec3(0, 0, 1), 0, 0, 0, 0);
}
