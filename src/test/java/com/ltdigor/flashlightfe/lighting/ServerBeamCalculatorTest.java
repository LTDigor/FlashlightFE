package com.ltdigor.flashlightfe.lighting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerBeamCalculatorTest {
    @Test void handheldSourcesFollowBrightnessAndRadialSoftness() {
        int bright = ServerBeamCalculator.handheldBrightness(3, 12, 15, .35, 0);
        int dim = ServerBeamCalculator.handheldBrightness(3, 12, 5, .35, 0);
        int shoulder = ServerBeamCalculator.handheldBrightness(3, 12, 15, 1, .5);
        int hardShoulder = ServerBeamCalculator.handheldBrightness(3, 12, 15, 0, .5);
        int defaultCore = ServerBeamCalculator.handheldBrightness(3, 12, 15, .35, .4);
        assertTrue(bright > dim && dim > 0);
        assertTrue(hardShoulder > shoulder && shoulder > 0);
        assertTrue(bright > defaultCore, "Fallback core must grade with softness too");
        assertEquals(0, ServerBeamCalculator.handheldBrightness(3, 12, 15, .35, 1.1));
    }

    @Test void headSourcesKeepBackspillSmallWhileFollowingPeak() {
        assertTrue(ServerBeamCalculator.headBrightness(1, 12, 15, .35, 0) <= 4);
        assertTrue(ServerBeamCalculator.headBrightness(9, 12, 15, .35, 0)
            > ServerBeamCalculator.headBrightness(1, 12, 15, .35, 0));
        assertTrue(ServerBeamCalculator.headBrightness(9, 12, 5, .35, 0)
            < ServerBeamCalculator.headBrightness(9, 12, 15, .35, 0));
        assertEquals(0, ServerBeamCalculator.headBrightness(9, 12, 15, .35, 1.1));
    }

    @Test void closeWallBrightnessTracksPeakWithoutBreakingHeadLimit() {
        assertEquals(15, ServerBeamCalculator.closeWallBrightness(false, 15));
        assertEquals(5, ServerBeamCalculator.closeWallBrightness(false, 5));
        assertEquals(4, ServerBeamCalculator.closeWallBrightness(true, 15));
        assertTrue(ServerBeamCalculator.closeWallBrightness(true, 2) <= 2);
    }
}
