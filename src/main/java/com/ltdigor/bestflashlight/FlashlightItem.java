package com.ltdigor.bestflashlight;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Item state persists in components; the client renderer owns transient button motion. */
public final class FlashlightItem extends Item implements top.theillusivec4.curios.api.type.capability.ICurioItem {
    @Override public boolean canEquip(top.theillusivec4.curios.api.SlotContext context, ItemStack stack) { return false; }
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
