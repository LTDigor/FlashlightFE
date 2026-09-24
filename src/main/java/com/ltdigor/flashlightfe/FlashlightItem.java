package com.ltdigor.flashlightfe;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Item state persists in components; the client renderer owns transient button motion. */
public final class FlashlightItem extends Item {
    public FlashlightItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override public boolean isBarVisible(ItemStack stack) { return LampEnergy.isBarVisible(stack); }
    @Override public int getBarWidth(ItemStack stack) { return LampEnergy.barWidth(stack); }
    @Override public int getBarColor(ItemStack stack) { return LampEnergy.BAR_COLOR; }
    @Override public boolean shouldCauseReequipAnimation(ItemStack before, ItemStack after, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(before, after);
    }
}
