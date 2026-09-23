package com.ltdigor.flashlightfe.lighting;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What occupied a cell before the transient carrier replaced it. Only air and water are
 * representable on purpose: restoring an arbitrary captured BlockState could destroy a
 * change another system made while the carrier existed.
 */
public sealed interface RestorableEnvironment {
    BlockState restored();

    record Air() implements RestorableEnvironment {
        @Override
        public BlockState restored() {
            return Blocks.AIR.defaultBlockState();
        }
    }

    record Water(int level) implements RestorableEnvironment {
        public Water {
            level = Math.clamp(level, 0, 15);
        }

        @Override
        public BlockState restored() {
            return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
        }
    }

    static RestorableEnvironment of(BlockState state) {
        if (state.is(Blocks.WATER)) return new Water(state.getValue(LiquidBlock.LEVEL));
        return new Air();
    }
}
