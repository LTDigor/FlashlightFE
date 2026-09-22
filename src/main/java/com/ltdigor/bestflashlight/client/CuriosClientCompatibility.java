package com.ltdigor.bestflashlight.client;

import com.ltdigor.bestflashlight.FlashlightMod;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;
import net.minecraft.world.item.Item;

/** Reflective client bridge keeps Curios absent from this mod's class signatures. */
final class CuriosClientCompatibility {
    private CuriosClientCompatibility() {}

    static void registerRenderer() {
        try {
            Class<?> registry = Class.forName("top.theillusivec4.curios.api.client.CuriosRendererRegistry");
            registry.getMethod("register", Item.class, Supplier.class)
                .invoke(null, FlashlightMod.HEADBAND.get(), (Supplier<Object>) HeadbandRenderer::createProxy);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios is installed but Flashlight FE renderer could not initialize", exception);
        }
    }
}
