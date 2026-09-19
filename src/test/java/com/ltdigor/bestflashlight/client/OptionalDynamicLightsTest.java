package com.ltdigor.bestflashlight.client;

import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OptionalDynamicLightsTest {
    @Test void optionalBridgeMatchesDynamicLightBehaviorContractWithoutCompileDependency() throws Exception {
        Method initialize = OptionalDynamicLights.class.getDeclaredMethod("ensureInitialized");
        initialize.setAccessible(true);
        initialize.invoke(null);

        Field availableField = OptionalDynamicLights.class.getDeclaredField("available");
        availableField.setAccessible(true);
        assertTrue(availableField.getBoolean(null));

        Field behaviorField = OptionalDynamicLights.class.getDeclaredField("behavior");
        behaviorField.setAccessible(true);
        Object reflected = behaviorField.get(null);
        assertInstanceOf(DynamicLightBehavior.class, reflected);

        DynamicLightBehavior behavior = (DynamicLightBehavior) reflected;
        assertTrue(behavior.isRemoved(), "An initialized but inactive source must report removed");
        assertEquals(0.0, behavior.lightAtPos(BlockPos.ZERO, 1.0), 1e-9);

        DynamicLightBehavior.BoundingBox box = behavior.getBoundingBox();
        assertTrue(box.startX() <= box.endX());
        assertTrue(box.startY() <= box.endY());
        assertTrue(box.startZ() <= box.endZ());

        assertTrue(behavior.hasChanged());
        assertFalse(behavior.hasChanged());
    }
}
