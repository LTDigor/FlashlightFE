package com.ltdigor.flashlightfe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LampEnergyTest {
    @Test void fractionalRateChargesOneFeAfterFortyTicks() {
        int charged = 0;
        for (long tick = 0; tick < 40; tick++) {
            int cost = LampEnergy.costForTick(0.025, tick);
            if (tick < 39) assertEquals(0, cost, "0.025 FE/t must not charge before the fortieth tick");
            charged += cost;
        }

        assertEquals(1, charged, "0.025 FE/t must total 1 FE in forty ticks");
    }

    @Test void wholeRateChargesEveryTick() {
        for (long tick = 0; tick < 40; tick++) {
            assertEquals(1, LampEnergy.costForTick(1.0, tick));
        }
    }
}
