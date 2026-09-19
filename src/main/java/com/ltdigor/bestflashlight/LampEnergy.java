package com.ltdigor.bestflashlight;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** Item-bound receiving-only FE adapter. Nested changes replace the immutable component. */
public final class LampEnergy implements IEnergyStorage {
    public static final int BAR_COLOR = 0x00FF00;

    public static boolean isBarVisible(ItemStack stack) {
        return LampData.isLamp(stack) && stored(stack) < FlashlightConfig.ENERGY_CAPACITY.get();
    }

    /** Vanilla item icons provide thirteen pixels for the charge indicator. */
    public static int barWidth(ItemStack stack) {
        return (int) Math.round(13.0 * stored(stack) / FlashlightConfig.ENERGY_CAPACITY.get());
    }

    private final ItemStack stack;
    private LampEnergy(ItemStack stack) { this.stack = stack; }

    public static void register(RegisterCapabilitiesEvent event) {
        for (var entry : FlashlightMod.ITEMS.getEntries()) {
            event.registerItem(Capabilities.EnergyStorage.ITEM,
                (stack, context) -> LampData.isLamp(stack) ? new LampEnergy(stack) : null, entry.get());
        }
    }

    public static int stored(ItemStack stack) {
        if (stack.getItem() instanceof HeadbandItem) return stored(LampData.mounted(stack));
        return Math.clamp(stack.getOrDefault(LampData.ENERGY.get(), 0), 0, FlashlightConfig.ENERGY_CAPACITY.get());
    }

    private static void setStored(ItemStack stack, int amount) {
        if (stack.getItem() instanceof HeadbandItem) {
            ItemStack lamp = LampData.mounted(stack);
            if (!lamp.isEmpty()) { setStored(lamp, amount); LampData.mount(stack, lamp); }
        } else {
            stack.set(LampData.ENERGY.get(), Math.clamp(amount, 0, FlashlightConfig.ENERGY_CAPACITY.get()));
        }
    }

    static void setSyncedStored(ItemStack stack, int amount) {
        setStored(stack, amount);
    }

    private static boolean creative(LivingEntity actor) {
        return actor instanceof Player player && player.isCreative();
    }

    public static boolean hasPower(ItemStack stack, LivingEntity actor) {
        return creative(actor) || stored(stack) >= FlashlightConfig.ENERGY_PER_TICK.get();
    }

    /** Context-free FE operations retain survival semantics. */
    public static boolean consume(ItemStack stack) { return consume(stack, null); }

    /** Called once per emitting server tick. Creative never writes the stored charge. */
    public static boolean consume(ItemStack stack, LivingEntity actor) {
        if (!LampData.enabled(stack)) return false;
        if (!hasPower(stack, actor)) { LampData.setEnabled(stack, false); return false; }
        if (!creative(actor)) {
            int cost = FlashlightConfig.ENERGY_PER_TICK.get();
            if (cost > 0) {
                int remaining = stored(stack) - cost;
                setStored(stack, remaining);
                if (remaining < cost) LampData.setEnabled(stack, false);
            }
        }
        return true;
    }

    public static boolean toggle(ItemStack stack) { return toggle(stack, null); }

    public static boolean toggle(ItemStack stack, LivingEntity actor) {
        boolean on = !LampData.enabled(stack) && hasPower(stack, actor);
        LampData.setEnabled(stack, on);
        return on;
    }

    @Override public int receiveEnergy(int requested, boolean simulate) {
        if (requested <= 0 || !canReceive()) return 0;
        int current = getEnergyStored();
        int received = Math.min(requested, getMaxEnergyStored() - current);
        if (!simulate && received > 0) setStored(stack, current + received);
        return received;
    }
    @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
    @Override public int getEnergyStored() { return stored(stack); }
    @Override public int getMaxEnergyStored() { return FlashlightConfig.ENERGY_CAPACITY.get(); }
    @Override public boolean canExtract() { return false; }
    @Override public boolean canReceive() { return LampData.isLamp(stack); }
}
