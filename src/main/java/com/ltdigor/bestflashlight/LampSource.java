package com.ltdigor.bestflashlight;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/** Shared source selection for input, beam origin and energy consumption. */
public record LampSource(ItemStack stack, boolean headMounted, boolean offHand) {
    /** Recover legacy handheld Curios through Curios' own inventory/drop path. */
    public static void returnInvalidFlashlights(LivingEntity entity) {
        if (entity.level().isClientSide()) return;
        CuriosApi.getCuriosInventory(entity).ifPresent(inventory -> {
            boolean changed = false;
            for (var slot : inventory.getCurios().values()) {
                for (var items : java.util.List.of(slot.getStacks(), slot.getCosmeticStacks())) {
                    for (int i = 0; i < items.getSlots(); i++) {
                        ItemStack stack = items.getStackInSlot(i);
                        if (!FlashlightMod.isFlashlight(stack)) continue;
                        items.setStackInSlot(i, ItemStack.EMPTY);
                        inventory.loseInvalidStack(stack);
                        changed = true;
                    }
                }
            }
            if (changed) inventory.handleInvalidStacks();
        });
    }

    public static ItemStack headband(LivingEntity entity) {
        return CuriosApi.getCuriosInventory(entity).map(inventory -> {
            var head = inventory.getCurios().get("head");
            if (head != null) {
                var items = head.getStacks();
                for (int i = 0; i < items.getSlots(); i++) {
                    ItemStack stack = items.getStackInSlot(i);
                    if (stack.getItem() instanceof HeadbandItem && !LampData.mounted(stack).isEmpty()) return stack;
                }
            }
            return ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY);
    }

    public static LampSource select(LivingEntity entity) {
        ItemStack main = entity.getMainHandItem();
        if (FlashlightMod.isFlashlight(main) && LampData.enabled(main)) return new LampSource(main, false, false);
        ItemStack off = entity.getOffhandItem();
        if (FlashlightMod.isFlashlight(off) && LampData.enabled(off)) return new LampSource(off, false, true);
        ItemStack band = headband(entity);
        if (LampData.enabled(band)) return new LampSource(band, true, false);
        return null;
    }

    public static String toggleTarget(LivingEntity entity) {
        if (FlashlightMod.isFlashlight(entity.getMainHandItem())) return "main";
        if (FlashlightMod.isFlashlight(entity.getOffhandItem())) return "off";
        if (!headband(entity).isEmpty()) return "headband";
        return null;
    }
}
