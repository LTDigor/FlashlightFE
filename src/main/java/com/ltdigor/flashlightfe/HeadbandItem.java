package com.ltdigor.flashlightfe;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public final class HeadbandItem extends Item implements Equipable {
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

    @Override public EquipmentSlot getEquipmentSlot() { return EquipmentSlot.HEAD; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (CuriosCompatibility.hasHeadSlot(player)) return CuriosCompatibility.equipHeadbandFromUse(level, player, hand);
        return swapWithEquipmentSlot(this, level, player, hand);
    }

    @Override public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }
}
