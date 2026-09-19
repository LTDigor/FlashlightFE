package dev.lambdaurora.lambdynlights.api.behavior;

import net.minecraft.core.BlockPos;

public interface DynamicLightBehavior {
    double lightAtPos(BlockPos pos, double falloffRatio);
    BoundingBox getBoundingBox();
    boolean hasChanged();
    default boolean isRemoved() { return false; }

    record BoundingBox(int startX, int startY, int startZ, int endX, int endY, int endZ) {}
}
