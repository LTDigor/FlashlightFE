package dev.lambdaurora.lambdynlights;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import java.util.LinkedHashSet;
import java.util.Set;

public final class LambDynLights {
    private static final LambDynLights INSTANCE = new LambDynLights();
    private final BehaviorManager manager = new BehaviorManager();
    public final DynamicLightsConfig config = new DynamicLightsConfig();

    public static LambDynLights get() {
        return INSTANCE;
    }

    public BehaviorManager dynamicLightBehaviorManager() {
        return manager;
    }

    public static final class DynamicLightsConfig {
        private final DynamicLightsMode mode = new DynamicLightsMode();
        public DynamicLightsMode getDynamicLightsMode() { return mode; }
    }

    public static final class DynamicLightsMode {
        public boolean isEnabled() { return true; }
    }

    public static final class BehaviorManager {
        private final Set<DynamicLightBehavior> sources = new LinkedHashSet<>();

        public void add(DynamicLightBehavior source) {
            sources.add(source);
        }

        public boolean remove(DynamicLightBehavior source) {
            return sources.remove(source);
        }

        public Set<DynamicLightBehavior> sources() {
            return Set.copyOf(sources);
        }
    }
}
