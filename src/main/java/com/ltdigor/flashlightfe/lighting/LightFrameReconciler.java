package com.ltdigor.flashlightfe.lighting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Pure diff between the applied carrier state and the desired aggregate frame. */
public final class LightFrameReconciler {
    private LightFrameReconciler() {}

    public static ReconciliationPlan plan(Map<BlockPos, AppliedLight> applied, Map<BlockPos, DesiredLight> desired) {
        Map<BlockPos, DesiredLight> additions = new HashMap<>();
        Map<BlockPos, DesiredLight> updates = new HashMap<>();
        Set<BlockPos> removals = new HashSet<>();
        applied.forEach((pos, current) -> {
            DesiredLight want = desired.get(pos);
            if (want == null) {
                removals.add(pos);
            } else if (want.level() != current.level()) {
                updates.put(pos, want);
            }
        });
        desired.forEach((pos, want) -> {
            if (!applied.containsKey(pos)) additions.put(pos, want);
        });
        return new ReconciliationPlan(additions, updates, removals);
    }
}
