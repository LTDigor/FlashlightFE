package com.ltdigor.flashlightfe.lighting;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Immutable world mutation plan between the applied carrier state and the desired aggregate. */
public record ReconciliationPlan(
    Map<BlockPos, DesiredLight> additions,
    Map<BlockPos, DesiredLight> updates,
    Set<BlockPos> removals
) {
    public ReconciliationPlan {
        additions = Map.copyOf(additions);
        updates = Map.copyOf(updates);
        removals = Set.copyOf(removals);
    }

    public static ReconciliationPlan empty() {
        return new ReconciliationPlan(Map.of(), Map.of(), Set.of());
    }

    public boolean isEmpty() {
        return additions.isEmpty() && updates.isEmpty() && removals.isEmpty();
    }

    public int mutations() {
        return additions.size() + updates.size() + removals.size();
    }
}
