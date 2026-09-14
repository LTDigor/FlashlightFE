package com.ltdigor.bestflashlight.compat;

import com.ltdigor.bestflashlight.FlashlightMod;
import com.ltdigor.bestflashlight.HeadbandItem;
import com.ltdigor.bestflashlight.LampData;
import com.ltdigor.bestflashlight.LampEnergy;
import java.util.ArrayList;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Loaded by JEI only when installed; no JEI dependency on the server. */
@JeiPlugin
public final class FlashlightJeiPlugin implements IModPlugin {
    @Override public ResourceLocation getPluginUid() {
        return FlashlightMod.resource("charged_variants");
    }

    @Override public void registerItemSubtypes(ISubtypeRegistration registration) {
        var interpreter = new ISubtypeInterpreter<ItemStack>() {
            @Override public Object getSubtypeData(ItemStack stack, UidContext context) {
                // Charge must not prevent normal recipe lookup. Names and switch state
                // also do not create extra JEI entries for otherwise identical lamps.
                if (context == UidContext.Recipe) return null;
                if (stack.getItem() instanceof HeadbandItem && LampData.mounted(stack).isEmpty()) return "empty_headband";
                return LampEnergy.stored(stack) > 0 ? "charged" : "empty_battery";
            }
            @Override public String getLegacyStringSubtypeInfo(ItemStack stack, UidContext context) {
                Object subtype = getSubtypeData(stack, context);
                return subtype == null ? "" : subtype.toString();
            }
        };
        for (var entry : FlashlightMod.ITEMS.getEntries()) {
            var item = entry.get();
            if (item instanceof HeadbandItem || LampData.isLamp(new ItemStack(item)))
                registration.registerSubtypeInterpreter(item, interpreter);
        }
    }

    @Override public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        var variants = new ArrayList<ItemStack>();
        // The normal item list already contains the empty handheld item.
        for (var entry : FlashlightMod.ITEMS.getEntries()) {
            ItemStack stack = new ItemStack(entry.get());
            if (LampData.isLamp(stack)) {
                chargeFully(stack);
                variants.add(stack);
            }
        }
        ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(band, new ItemStack(FlashlightMod.FLASHLIGHT.get()));
        variants.add(band.copy());
        chargeFully(band);
        variants.add(band);
        registration.addExtraItemStacks(variants);
    }

    @Override public void registerRecipes(IRecipeRegistration registration) {
        Component mounting = Component.translatable("jei.bestflashlight.headband.mounting");
        registration.addIngredientInfo(FlashlightMod.HEADBAND.get(), mounting);
        registration.addIngredientInfo(FlashlightMod.FLASHLIGHT.get(), mounting);
    }

    private static void chargeFully(ItemStack stack) {
        var energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        if (energy != null) energy.receiveEnergy(energy.getMaxEnergyStored(), false);
    }
}
