package dev.lambdaurora.lambdynlights;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;

public final class LambDynLights {
    private static final LambDynLights INSTANCE = new LambDynLights();
    private final BehaviorManager manager = new BehaviorManager();

    public static LambDynLights get() {
        return INSTANCE;
    }

    public BehaviorManager dynamicLightBehaviorManager() {
        return manager;
    }

    public static final class BehaviorManager {
        private DynamicLightBehavior source;

        public void add(DynamicLightBehavior source) {
            this.source = source;
        }

        public boolean remove(DynamicLightBehavior source) {
            if (this.source != source) return false;
            this.source = null;
            return true;
        }

        public DynamicLightBehavior source() {
            return source;
        }
    }
}
