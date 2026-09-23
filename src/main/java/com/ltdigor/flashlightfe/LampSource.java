package com.ltdigor.flashlightfe;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Shared source selection for input, beam origin and energy consumption. */
public record LampSource(ItemStack stack, boolean headMounted, boolean offHand) {
    private static final Set<UUID> LEGACY_CHECKED = new HashSet<>();

    /** Recover legacy handheld Curios through Curios' own inventory/drop path once per login. */
    public static void returnInvalidFlashlights(LivingEntity entity) {
        if (entity.level().isClientSide() || !LEGACY_CHECKED.add(entity.getUUID())) return;
        CuriosCompatibility.returnInvalidFlashlights(entity);
    }

    public static ItemStack headband(LivingEntity entity) {
        var headbands = CuriosCompatibility.headbands(entity);
        for (ItemStack stack : headbands) {
            if (LampData.enabled(stack)) return stack;
        }
        return headbands.stream().findFirst().orElse(ItemStack.EMPTY);
    }

    public static ItemStack headband(LivingEntity entity, int slotIndex) {
        return CuriosCompatibility.headband(entity, slotIndex);
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
        for (ItemStack band : CuriosCompatibility.headbands(entity)) {
            if (!LampData.enabled(band)) continue;
            LampSource source = new LampSource(band, true, false);
            if (usable.test(source)) return source;
        }
        return null;
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
