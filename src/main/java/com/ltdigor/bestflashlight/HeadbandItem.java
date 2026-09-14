package com.ltdigor.bestflashlight;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

public final class HeadbandItem extends Item implements ICurioItem {
    @Override public boolean isBarVisible(ItemStack stack) { return LampEnergy.isBarVisible(stack); }
    @Override public int getBarWidth(ItemStack stack) { return LampEnergy.barWidth(stack); }
    @Override public int getBarColor(ItemStack stack) { return LampEnergy.BAR_COLOR; }

    public HeadbandItem(Properties properties) { super(properties.stacksTo(1)); }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (LampData.mounted(stack).isEmpty()) {
            tooltip.add(Component.translatable("tooltip.bestflashlight.headband.mount").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.bestflashlight.headband.loaded").withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("tooltip.bestflashlight.headband.unmount").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override public boolean canEquip(SlotContext context, ItemStack stack) { return context.identifier().equals("head"); }
    @Override public boolean canEquipFromUse(SlotContext context, ItemStack stack) { return true; }
    @Override public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }
}
