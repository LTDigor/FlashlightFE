package com.ltdigor.flashlightfe;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/** Uses only an already available Curios head slot; vanilla HEAD always remains valid. */
public final class CuriosCompatibility {
    private CuriosCompatibility() {}

    public static boolean isLoaded() { return ModList.get().isLoaded("curios"); }

    public static void setup(FMLCommonSetupEvent event) {
        if (isLoaded()) event.enqueueWork(CuriosCompatibility::registerCurios);
    }

    public static boolean hasFunctionalHeadSlot(LivingEntity entity) {
        return isLoaded() && curiosInventory(entity).map(inventory -> {
            Object head = headHandler(inventory);
            return head != null && (int) call(call(head, "getStacks"), "getSlots") > 0;
        }).orElse(false);
    }

    /** Curios head lamps first, then a vanilla helmet-slot headlamp if present. */
    public static List<ItemStack> headlamps(LivingEntity entity) {
        List<ItemStack> result = new ArrayList<>();
        if (isLoaded()) curiosInventory(entity).ifPresent(inventory -> {
            Object head = headHandler(inventory);
            if (head == null) return;
            Object stacks = call(head, "getStacks");
            for (int index = 0; index < (int) call(stacks, "getSlots"); index++) {
                ItemStack stack = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[]{int.class}, index);
                if (isHeadlamp(stack)) result.add(stack);
            }
        });
        ItemStack vanilla = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (isHeadlamp(vanilla)) result.add(vanilla);
        return result;
    }

    /** Slot -1 denotes vanilla HEAD in owner-only energy packets. */
    public static ItemStack headlamp(LivingEntity entity, int slotIndex) {
        if (slotIndex == -1) {
            ItemStack vanilla = entity.getItemBySlot(EquipmentSlot.HEAD);
            return isHeadlamp(vanilla) ? vanilla : ItemStack.EMPTY;
        }
        if (!isLoaded() || slotIndex < 0) return ItemStack.EMPTY;
        return curiosInventory(entity).map(inventory -> {
            Object head = headHandler(inventory);
            if (head == null) return ItemStack.EMPTY;
            Object stacks = call(head, "getStacks");
            if (slotIndex >= (int) call(stacks, "getSlots")) return ItemStack.EMPTY;
            ItemStack stack = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[]{int.class}, slotIndex);
            return isHeadlamp(stack) ? stack : ItemStack.EMPTY;
        }).orElse(ItemStack.EMPTY);
    }

    public static InteractionResultHolder<ItemStack> equipHeadlampFromUse(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        Object head = curiosInventory(player).map(CuriosCompatibility::headHandler).orElse(null);
        if (head == null) return InteractionResultHolder.fail(held);
        Object stacks = call(head, "getStacks");
        for (int index = 0; index < (int) call(stacks, "getSlots"); index++) {
            if (!((ItemStack) call(stacks, "getStackInSlot", new Class<?>[]{int.class}, index)).isEmpty()) continue;
            call(stacks, "setStackInSlot", new Class<?>[]{int.class, ItemStack.class}, index,
                player.isCreative() ? held.copy() : held.split(1));
            return InteractionResultHolder.sidedSuccess(held, level.isClientSide());
        }
        return InteractionResultHolder.fail(held);
    }

    private static boolean isHeadlamp(ItemStack stack) { return stack.getItem() instanceof HeadlampItem; }

    private static void registerCurios() {
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> curioItem = Class.forName("top.theillusivec4.curios.api.type.capability.ICurioItem");
            Method register = api.getMethod("registerCurio", net.minecraft.world.item.Item.class, curioItem);
            register.invoke(null, FlashlightMod.HEADLAMP.get(), curio(curioItem));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios is installed but headlamp compatibility could not initialize", exception);
        }
    }

    private static Object curio(Class<?> curioItem) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("canEquip")) return "head".equals(call(arguments[0], "identifier"));
            if (method.getName().equals("canEquipFromUse")) return true;
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments == null ? new Object[0] : arguments);
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            return null;
        };
        return Proxy.newProxyInstance(curioItem.getClassLoader(), new Class<?>[]{curioItem}, handler);
    }

    private static Optional<Object> curiosInventory(LivingEntity entity) {
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            @SuppressWarnings("unchecked")
            Optional<Object> inventory = (Optional<Object>) api.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, entity);
            return inventory;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios is installed but headlamp compatibility could not initialize", exception);
        }
    }

    private static Object headHandler(Object inventory) {
        @SuppressWarnings("unchecked")
        Map<String, Object> curios = (Map<String, Object>) call(inventory, "getCurios");
        return curios.get("head");
    }

    private static Object call(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return target.getClass().getMethod(methodName, parameterTypes).invoke(target, arguments);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios compatibility call failed: " + methodName, exception);
        }
    }

    private static Object call(Object target, String methodName) { return call(target, methodName, new Class<?>[0]); }
}
