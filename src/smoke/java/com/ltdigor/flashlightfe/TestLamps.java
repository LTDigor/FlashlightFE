package com.ltdigor.flashlightfe;

import net.minecraft.world.item.ItemStack;

/** Fixture conversion only: standalone lamps keep their state directly. */
public final class TestLamps {
    private TestLamps() {}
    public static void copyState(ItemStack target, ItemStack source) {
        target.applyComponents(source.getComponentsPatch());
    }
}
