package com.ltdigor.flashlightfe.lighting;

/** Requested brightness of one transient light cell, validated at construction. */
public record DesiredLight(int level) {
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 15;

    public DesiredLight {
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new IllegalArgumentException("Transient light level out of range: " + level);
        }
    }

    public static DesiredLight of(int level) {
        return new DesiredLight(Math.clamp(level, MIN_LEVEL, MAX_LEVEL));
    }

    public DesiredLight brighter(DesiredLight other) {
        return other.level > level ? other : this;
    }
}
