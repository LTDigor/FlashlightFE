package com.ltdigor.bestflashlight;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;

/** Shared source selection for input, beam origin and energy consumption. */
public record LampSource(ItemStack stack, boolean headMounted, boolean offHand) {
    private static final Set<UUID> LEGACY_CHECKED = new HashSet<>();

    /** Recover legacy handheld Curios through Curios' own inventory/drop path once per login. */
    public static void returnInvalidFlashlights(LivingEntity entity) {
        if (entity.level().isClientSide() || LEGACY_CHECKED.contains(entity.getUUID())) return;
        CuriosApi.getCuriosInventory(entity).ifPresent(inventory -> {
            LEGACY_CHECKED.add(entity.getUUID());
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
        return select(entity, source -> true);
    }

    /**
     * Returns the first enabled source in normal priority order that can actually
     * be used by the caller. A temporarily unusable higher-priority lamp must not
     * starve another enabled source (for example a submerged handheld blocking a
     * dry headlamp when underwater operation is disabled).
     */
    public static LampSource select(LivingEntity entity, Predicate<LampSource> usable) {
        ItemStack main = entity.getMainHandItem();
        if (FlashlightMod.isFlashlight(main) && LampData.enabled(main)) {
            LampSource source = new LampSource(main, false, false);
            if (usable.test(source)) return source;
        }
        ItemStack off = entity.getOffhandItem();
        if (FlashlightMod.isFlashlight(off) && LampData.enabled(off)) {
            LampSource source = new LampSource(off, false, true);
            if (usable.test(source)) return source;
        }
        LampSource headband = selectHeadband(entity, usable);
        if (headband != null) return headband;
        return null;
    }

    private static LampSource selectHeadband(LivingEntity entity, Predicate<LampSource> usable) {
        return CuriosApi.getCuriosInventory(entity).map(inventory -> {
            var head = inventory.getCurios().get("head");
            if (head == null) return null;
            var items = head.getStacks();
            for (int i = 0; i < items.getSlots(); i++) {
                ItemStack band = items.getStackInSlot(i);
                if (!(band.getItem() instanceof HeadbandItem) || LampData.mounted(band).isEmpty()
                    || !LampData.enabled(band)) {
                    continue;
                }
                LampSource source = new LampSource(band, true, false);
                if (usable.test(source)) return source;
            }
            return null;
        }).orElse(null);
    }

    static void resetLegacyCheck(UUID player) {
        LEGACY_CHECKED.remove(player);
    }

    static void clearLegacyChecks() {
        LEGACY_CHECKED.clear();
    }

    public static String toggleTarget(LivingEntity entity) {
        if (FlashlightMod.isFlashlight(entity.getMainHandItem())) return "main";
        if (FlashlightMod.isFlashlight(entity.getOffhandItem())) return "off";
        if (!headband(entity).isEmpty()) return "headband";
        return null;
    }
}
