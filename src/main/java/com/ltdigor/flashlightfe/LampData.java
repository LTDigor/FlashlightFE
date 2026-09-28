package com.ltdigor.flashlightfe;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LampData {
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(net.minecraft.core.registries.Registries.DATA_COMPONENT_TYPE, "bestflashlight");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY = COMPONENTS.registerComponentType(
        "energy", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.INT));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> ENABLED = COMPONENTS.registerComponentType(
        "enabled", b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));

    private LampData() {}

    public static boolean isLamp(ItemStack stack) {
        return stack != null && !stack.isEmpty()
            && (FlashlightMod.isFlashlight(stack) || stack.getItem() instanceof HeadlampItem);
    }

    public static boolean enabled(ItemStack stack) {
        return isLamp(stack) && stack.getOrDefault(ENABLED.get(), false);
    }

    public static void setEnabled(ItemStack stack, boolean enabled) {
        if (isLamp(stack)) stack.set(ENABLED.get(), enabled);
    }
}
