package com.ltdigor.flashlightfe.lighting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class LightFrameReconcilerTest {
    private static final BlockPos X = BlockPos.containing(1, 2, 3);
    private static final AppliedLight APPLIED_10 = new AppliedLight(10, new RestorableEnvironment.Air());

    @Test
    void missingAppliedCellBecomesAddition() {
        ReconciliationPlan plan = LightFrameReconciler.plan(Map.of(), Map.of(X, DesiredLight.of(10)));
        assertEquals(new ReconciliationPlan(Map.of(X, DesiredLight.of(10)), Map.of(), Set.of()), plan);
    }

    @Test
    void missingDesiredCellBecomesRemoval() {
        ReconciliationPlan plan = LightFrameReconciler.plan(Map.of(X, APPLIED_10), Map.of());
        assertEquals(new ReconciliationPlan(Map.of(), Map.of(), Set.of(X)), plan);
    }

    @Test
    void changedBrightnessBecomesUpdate() {
        ReconciliationPlan plan = LightFrameReconciler.plan(Map.of(X, APPLIED_10), Map.of(X, DesiredLight.of(15)));
        assertEquals(new ReconciliationPlan(Map.of(), Map.of(X, DesiredLight.of(15)), Set.of()), plan);
    }

    @Test
    void unchangedCellProducesNoMutation() {
        ReconciliationPlan plan = LightFrameReconciler.plan(Map.of(X, APPLIED_10), Map.of(X, DesiredLight.of(10)));
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.mutations());
    }

    @Test
    void losingTheBrightestSourceDowngradesInsteadOfRecreating() {
        ReconciliationPlan plan = LightFrameReconciler.plan(Map.of(X, new AppliedLight(15, new RestorableEnvironment.Air())),
            Map.of(X, DesiredLight.of(7)));
        assertEquals(new ReconciliationPlan(Map.of(), Map.of(X, DesiredLight.of(7)), Set.of()), plan);
    }

    @Test
    void losingTheLastSourceRemovesTheCarrier() {
        ReconciliationPlan plan = LightFrameReconciler.plan(Map.of(X, new AppliedLight(7, new RestorableEnvironment.Air())),
            Map.of());
        assertEquals(Set.of(X), plan.removals());
        assertTrue(plan.additions().isEmpty() && plan.updates().isEmpty());
    }
}
