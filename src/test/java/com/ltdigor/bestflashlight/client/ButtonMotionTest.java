package com.ltdigor.bestflashlight.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ButtonMotionTest {
    @Test void onPressReachesDeepThenReturnsToLatchedRest() {
        ButtonMotion motion = new ButtonMotion(20, 0, true);
        assertEquals(0, motion.sample(20), 1e-9);
        assertTrue(motion.sample(22) < -0.6);
        assertEquals(-0.30, motion.sample(25), 1e-9);
        assertEquals(-0.30, motion.sample(100), 1e-9);
    }

    @Test void offPressRepressesBeforeFullyRising() {
        ButtonMotion motion = new ButtonMotion(20, -0.30, false);
        assertEquals(-0.30, motion.sample(20), 1e-9);
        assertTrue(motion.sample(22) < -0.6);
        assertEquals(0, motion.sample(25), 1e-9);
    }

    @Test void rejectedEmptyPressStillTravelsDownAndUp() {
        ButtonMotion motion = new ButtonMotion(20, 0, false);
        assertEquals(0, motion.sample(20), 1e-9);
        assertTrue(motion.sample(22) < -0.6);
        assertEquals(0, motion.sample(25), 1e-9);
    }

    @Test void rapidAlternatingPressesRestartFromCurrentOffsetWithoutJumping() {
        ButtonMotion motion = new ButtonMotion(0, 0, true);
        for (int i = 1; i <= 20; i++) {
            double now = i * 0.37;
            double current = motion.sample(now);
            motion = new ButtonMotion(now, current, i % 2 == 0);
            assertEquals(current, motion.sample(now), 1e-9);
            assertTrue(Math.abs(current - motion.sample(now + 0.0001)) < 0.0001);
        }
    }

    @Test void motionStaysWithinMechanicalTravel() {
        for (boolean enabled : new boolean[]{false, true}) {
            ButtonMotion motion = new ButtonMotion(0, enabled ? 0 : -0.30, enabled);
            for (double t = -1; t <= 8; t += 0.01) {
                assertTrue(motion.sample(t) >= -0.72 - 1e-9);
                assertTrue(motion.sample(t) <= 1e-9);
            }
        }
    }
}
