package com.ltdigor.flashlightfe.lighting;

/** A transient carrier the manager really placed into the world; knows no player ids. */
public record AppliedLight(int level, RestorableEnvironment environment) {
    public AppliedLight {
        level = Math.clamp(level, DesiredLight.MIN_LEVEL, DesiredLight.MAX_LEVEL);
    }
}
