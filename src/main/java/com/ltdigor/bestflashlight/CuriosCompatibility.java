package com.ltdigor.bestflashlight;

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

/** Keeps all Curios-linked classes dormant when Curios is not installed. */
public final class CuriosCompatibility {
    private static final String MOD_ID = "curios";

    private CuriosCompatibility() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static void setup(FMLCommonSetupEvent event) {
        if (isLoaded()) event.enqueueWork(CuriosCompatibility::registerCurios);
    }

    public static void returnInvalidFlashlights(LivingEntity entity) {
        if (!entity.level().isClientSide() && isLoaded())
            curiosInventory(entity).ifPresent(CuriosCompatibility::returnInvalidFlashlights);
    }

    /** Returns loaded headbands in Curios' head slots, or the vanilla head slot without Curios. */
    public static List<ItemStack> headbands(LivingEntity entity) {
        if (!isLoaded()) {
            ItemStack stack = entity.getItemBySlot(EquipmentSlot.HEAD);
            return isLoadedHeadband(stack) ? List.of(stack) : List.of();
        }
        return curiosInventory(entity).map(CuriosCompatibility::curiosHeadbands).orElseGet(List::of);
    }

    public static ItemStack headband(LivingEntity entity, int slotIndex) {
        if (!isLoaded()) return slotIndex == 0 ? headbands(entity).stream().findFirst().orElse(ItemStack.EMPTY) : ItemStack.EMPTY;
        return curiosInventory(entity).map(inventory -> curiosHeadband(inventory, slotIndex)).orElse(ItemStack.EMPTY);
    }

    public static InteractionResultHolder<ItemStack> equipHeadbandFromUse(Level level, Player player, InteractionHand hand) {
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

    private static void registerCurios() {
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Class<?> curioItem = Class.forName("top.theillusivec4.curios.api.type.capability.ICurioItem");
            Method register = api.getMethod("registerCurio", net.minecraft.world.item.Item.class, curioItem);
            register.invoke(null, FlashlightMod.FLASHLIGHT.get(), curio(curioItem, false));
            register.invoke(null, FlashlightMod.HEADBAND.get(), curio(curioItem, true));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios is installed but Flashlight FE compatibility could not initialize", exception);
        }
    }

    private static Object curio(Class<?> curioItem, boolean headband) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (method.getName().equals("canEquip")) return headband && "head".equals(call(arguments[0], "identifier"));
            if (method.getName().equals("canEquipFromUse")) return headband;
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
            throw new IllegalStateException("Curios is installed but Flashlight FE compatibility could not initialize", exception);
        }
    }

    private static Object headHandler(Object inventory) {
        @SuppressWarnings("unchecked")
        Map<String, Object> curios = (Map<String, Object>) call(inventory, "getCurios");
        return curios.get("head");
    }

    private static List<ItemStack> curiosHeadbands(Object inventory) {
        Object head = headHandler(inventory);
        if (head == null) return List.of();
        Object stacks = call(head, "getStacks");
        List<ItemStack> result = new ArrayList<>();
        for (int index = 0; index < (int) call(stacks, "getSlots"); index++) {
            ItemStack stack = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[]{int.class}, index);
            if (isLoadedHeadband(stack)) result.add(stack);
        }
        return result;
    }

    private static ItemStack curiosHeadband(Object inventory, int slotIndex) {
        Object head = headHandler(inventory);
        if (head == null) return ItemStack.EMPTY;
        Object stacks = call(head, "getStacks");
        if (slotIndex < 0 || slotIndex >= (int) call(stacks, "getSlots")) return ItemStack.EMPTY;
        ItemStack stack = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[]{int.class}, slotIndex);
        return isLoadedHeadband(stack) ? stack : ItemStack.EMPTY;
    }

    private static boolean isLoadedHeadband(ItemStack stack) {
        return stack.getItem() instanceof HeadbandItem && !LampData.mounted(stack).isEmpty();
    }

    private static void returnInvalidFlashlights(Object inventory) {
        @SuppressWarnings("unchecked")
        Map<String, Object> curios = (Map<String, Object>) call(inventory, "getCurios");
        boolean changed = false;
        for (Object slot : curios.values()) {
            for (String stacksMethod : new String[]{"getStacks", "getCosmeticStacks"}) {
                Object stacks = call(slot, stacksMethod);
                for (int index = 0; index < (int) call(stacks, "getSlots"); index++) {
                    ItemStack stack = (ItemStack) call(stacks, "getStackInSlot", new Class<?>[]{int.class}, index);
                    if (!FlashlightMod.isFlashlight(stack)) continue;
                    call(stacks, "setStackInSlot", new Class<?>[]{int.class, ItemStack.class}, index, ItemStack.EMPTY);
                    call(inventory, "loseInvalidStack", new Class<?>[]{ItemStack.class}, stack);
                    changed = true;
                }
            }
        }
        if (changed) call(inventory, "handleInvalidStacks");
    }

    private static Object call(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return target.getClass().getMethod(methodName, parameterTypes).invoke(target, arguments);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Curios compatibility call failed: " + methodName, exception);
        }
    }

    private static Object call(Object target, String methodName) {
        return call(target, methodName, new Class<?>[0]);
    }
}
