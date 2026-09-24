package com.ltdigor.flashlightfe.client;

/** One transient mechanical press, measured in client ticks and model pixels. */
public record ButtonMotion(double startedAt, double initialOffset, boolean enabled) {
    public static final double DURATION_TICKS = 5;
    private static final double PRESS_TICKS = 2;
    private static final double DEEP_OFFSET = -0.72;

    public static double rest(boolean enabled) { return enabled ? -0.30 : 0; }

    public double sample(double now) {
        double elapsed = now - startedAt;
        if (elapsed <= 0) return initialOffset;
        if (elapsed >= DURATION_TICKS) return rest(enabled);
        if (elapsed < PRESS_TICKS) {
            return interpolate(initialOffset, DEEP_OFFSET, elapsed / PRESS_TICKS);
        }
        return interpolate(DEEP_OFFSET, rest(enabled),
            (elapsed - PRESS_TICKS) / (DURATION_TICKS - PRESS_TICKS));
    }

    private static double interpolate(double from, double to, double progress) {
        double eased = progress * progress * (3 - 2 * progress);
        return from + (to - from) * eased;
    }
}
