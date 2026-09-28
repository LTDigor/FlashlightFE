package com.ltdigor.flashlightfe;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Independent rechargeable headlamp. */
public final class HeadlampItem extends Item implements Equipable {
    public HeadlampItem(Properties properties) { super(properties.stacksTo(1)); }

    @Override public boolean isBarVisible(ItemStack stack) { return LampEnergy.isBarVisible(stack); }
    @Override public int getBarWidth(ItemStack stack) { return LampEnergy.barWidth(stack); }
    @Override public int getBarColor(ItemStack stack) { return LampEnergy.BAR_COLOR; }
    @Override public EquipmentSlot getEquipmentSlot() { return EquipmentSlot.HEAD; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (CuriosCompatibility.isLoaded() && CuriosCompatibility.hasFunctionalHeadSlot(player))
            return CuriosCompatibility.equipHeadlampFromUse(level, player, hand);
        return swapWithEquipmentSlot(this, level, player, hand);
    }

    @Override public boolean shouldCauseReequipAnimation(ItemStack before, ItemStack after, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(before, after);
    }
}
