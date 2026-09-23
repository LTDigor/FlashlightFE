package com.ltdigor.bestflashlight.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeamPublicationTest {
    private static BeamSnapshot beam(double x) {
        return BeamSnapshot.build(new Vec3(x + 0.5, 1.5, 0.5), new Vec3(0, 0, 1),
            12, Math.toRadians(7.5), pos -> true);
    }

    @Test void publicationKeepsOldLightUntilNewTableIsComplete() {
        var state = new OptionalDynamicLights.ConeState();
        state.publish(beam(0));
        state.setActive(true);
        BlockPos oldPoint = new BlockPos(0, 1, 2);
        assertTrue(state.lightAt(oldPoint) > 0);
        assertTrue(state.hasChanged());
        assertFalse(state.hasChanged());
        BeamSnapshot next = BeamSnapshot.build(new Vec3(16.5, 1.5, 0.5), new Vec3(0, 0, 1),
            12, Math.toRadians(7.5), pos -> {
                assertTrue(state.lightAt(oldPoint) > 0);
                assertFalse(state.hasChanged(), "Building must not publish partial changes");
                return true;
            });
        state.publish(next);
        assertTrue(state.hasChanged());
        assertFalse(state.hasChanged());
        assertEquals(0, state.lightAt(oldPoint));
        assertTrue(state.lightAt(new BlockPos(16, 1, 2)) > 0);
        // Bounds must follow the moved beam and no longer include the old lit column.
        assertTrue(state.bounds()[0] > oldPoint.getX(), "minX=" + state.bounds()[0]);
        assertTrue(state.bounds()[3] >= 16);
    }

    @Test void removingOneSourceDoesNotAffectAnotherAndReleasesLight() {
        var first = new OptionalDynamicLights.ConeState();
        var second = new OptionalDynamicLights.ConeState();
        first.publish(beam(0)); second.publish(beam(0));
        first.setActive(true); second.setActive(true);
        first.hasChanged();
        first.setActive(false);
        assertTrue(first.hasChanged());
        assertFalse(first.isActive());
        assertEquals(0, first.lightAt(new BlockPos(0, 1, 2)));
        assertTrue(second.lightAt(new BlockPos(0, 1, 2)) > 0);
        first.setActive(true);
        assertEquals(0, first.lightAt(new BlockPos(0, 1, 2)), "Reactivation cannot resurrect old world data");
    }

    @Test void renderFrameCannotMoveAlreadyPublishedLight() throws Exception {
        var state = new OptionalDynamicLights.ConeState();
        state.publish(beam(0)); state.setActive(true); state.hasChanged();
        Class<?> type = Class.forName(OptionalDynamicLights.class.getName() + "$DynamicCone");
        var constructor = type.getDeclaredConstructor(OptionalDynamicLights.ConeState.class, Object.class);
        constructor.setAccessible(true);
        Object cone = constructor.newInstance(state, new Object());
        var frame = type.getDeclaredMethod("frameUpdate", Vec3.class, Vec3.class, double.class, double.class, double.class);
        frame.setAccessible(true);
        frame.invoke(cone, new Vec3(16.5, 1.5, 0.5), new Vec3(1, 0, 0), 32, Math.toRadians(45), 1.0 / 60);
        assertTrue(state.lightAt(new BlockPos(0, 1, 2)) > 0);
        assertEquals(0, state.lightAt(new BlockPos(16, 1, 2)));
        assertFalse(state.hasChanged(), "Aiming updates must not publish untraced light");
    }

    @Test void identicalStaticRefreshDoesNotRequestChunkRebuild() {
        var state = new OptionalDynamicLights.ConeState();
        state.publish(beam(0)); state.setActive(true); state.hasChanged();
        state.publish(beam(0));
        assertFalse(state.hasChanged());
    }
}
