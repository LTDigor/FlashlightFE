package com.ltdigor.flashlightfe;

import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Shared source selection for input, beam origin and energy consumption. */
public record LampSource(ItemStack stack, boolean headMounted, boolean offHand) {
    public static ItemStack headlamp(LivingEntity entity) {
        var headlamps = CuriosCompatibility.headlamps(entity);
        for (ItemStack stack : headlamps) {
            if (LampData.enabled(stack)) return stack;
        }
        return headlamps.stream().findFirst().orElse(ItemStack.EMPTY);
    }

    public static ItemStack headlamp(LivingEntity entity, int slotIndex) {
        return CuriosCompatibility.headlamp(entity, slotIndex);
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
        for (ItemStack lamp : CuriosCompatibility.headlamps(entity)) {
            if (!LampData.enabled(lamp)) continue;
            LampSource source = new LampSource(lamp, true, false);
            if (usable.test(source)) return source;
        }
        return null;
    }

    public static String toggleTarget(LivingEntity entity) {
        if (FlashlightMod.isFlashlight(entity.getMainHandItem())) return "main";
        if (FlashlightMod.isFlashlight(entity.getOffhandItem())) return "off";
        if (!headlamp(entity).isEmpty()) return "headlamp";
        return null;
    }
}
