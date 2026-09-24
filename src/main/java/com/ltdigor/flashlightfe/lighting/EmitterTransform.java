package com.ltdigor.flashlightfe.lighting;

import net.minecraft.world.phys.Vec3;

/**
 * Model-aligned offset of a lamp emitter relative to the eye.
 *
 * <p>Handheld values place the origin at the rendered lens: about half a block ahead of
 * the eye, one third of a block towards the holding arm and slightly below eye height,
 * which is where the hand model sits in first and third person. Headband values place it
 * on the forehead: ahead of the eye and a quarter block above it, following the look
 * pitch instead of world up so crouching and steep angles stay sane.
 */
public record EmitterTransform(double forward, double lateral, double vertical) {
    public static final EmitterTransform HANDHELD = new EmitterTransform(0.55, 0.35, -0.45);
    public static final EmitterTransform HEADBAND = new EmitterTransform(0.45, 0.0, 0.15);

    /**
     * Lateral uses the yaw-horizontal right vector: it never degenerates when the look
     * is vertical and matches the rendered arm side. Vertical is an offset along world up
     * for the hand (negative: the hand model sits below the eye at any pitch) and along
     * the local up vector for the headband (the forehead follows the head pitch).
     */
    public Vec3 origin(Vec3 eye, Vec3 look, double yawRadians, boolean rightSide, boolean headMounted) {
        Vec3 axis = look.lengthSqr() < 1.0E-12 ? new Vec3(0.0, 0.0, 1.0) : look.normalize();
        Vec3 right = new Vec3(-Math.cos(yawRadians), 0.0, -Math.sin(yawRadians));
        Vec3 verticalBasis = headMounted ? localUp(axis, right) : new Vec3(0.0, 1.0, 0.0);
        double side = headMounted ? 0.0 : (rightSide ? lateral : -lateral);
        return eye.add(axis.scale(forward)).add(right.scale(side)).add(verticalBasis.scale(vertical));
    }

    private static Vec3 localUp(Vec3 axis, Vec3 right) {
        Vec3 up = right.cross(axis);
        return up.lengthSqr() < 1.0E-8 ? new Vec3(0.0, 1.0, 0.0) : up.normalize();
    }
}
