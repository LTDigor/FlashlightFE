package com.ltdigor.bestflashlight;

import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class LampData {
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(net.minecraft.core.registries.Registries.DATA_COMPONENT_TYPE, "bestflashlight");
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY = COMPONENTS.registerComponentType(
        "energy", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.INT));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> ENABLED = COMPONENTS.registerComponentType(
        "enabled", b -> b.persistent(Codec.BOOL).networkSynchronized(ByteBufCodecs.BOOL));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemContainerContents>> MOUNTED = COMPONENTS.registerComponentType(
        "mounted_flashlight", b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC));

    private LampData() {}

    /** Returns a copy; every mutation must be written back to the containing headband. */
    public static ItemStack mounted(ItemStack band) {
        if (!(band.getItem() instanceof HeadbandItem)) return ItemStack.EMPTY;
        var contents = band.getOrDefault(MOUNTED.get(), ItemContainerContents.EMPTY);
        if (contents.getSlots() != 1) return ItemStack.EMPTY;
        ItemStack lamp = contents.getStackInSlot(0).copy();
        return lamp.getCount() == 1 && FlashlightMod.isFlashlight(lamp) ? lamp : ItemStack.EMPTY;
    }

    public static void mount(ItemStack band, ItemStack lamp) {
        if (!(band.getItem() instanceof HeadbandItem) || !FlashlightMod.isFlashlight(lamp)) {
            throw new IllegalArgumentException("Only a flashlight can be mounted in a headband");
        }
        band.set(MOUNTED.get(), ItemContainerContents.fromItems(List.of(lamp.copyWithCount(1))));
    }

    public static boolean isLamp(ItemStack stack) {
        return FlashlightMod.isFlashlight(stack)
            || stack.getItem() instanceof HeadbandItem && !mounted(stack).isEmpty();
    }

    public static boolean enabled(ItemStack stack) {
        if (stack.getItem() instanceof HeadbandItem) return enabled(mounted(stack));
        return isLampWithoutBand(stack) && stack.getOrDefault(ENABLED.get(), false);
    }

    public static void setEnabled(ItemStack stack, boolean enabled) {
        if (stack.getItem() instanceof HeadbandItem) {
            ItemStack lamp = mounted(stack);
            if (!lamp.isEmpty()) { setEnabled(lamp, enabled); mount(stack, lamp); }
        } else if (isLampWithoutBand(stack)) {
            stack.set(ENABLED.get(), enabled);
        }
    }

    private static boolean isLampWithoutBand(ItemStack stack) {
        return !stack.isEmpty() && (FlashlightMod.isFlashlight(stack));
    }
}
