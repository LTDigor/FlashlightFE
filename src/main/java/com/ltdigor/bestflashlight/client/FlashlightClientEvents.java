package com.ltdigor.bestflashlight.client;

import com.ltdigor.bestflashlight.FlashlightConfig;
import com.ltdigor.bestflashlight.FlashlightMod;
import com.ltdigor.bestflashlight.LampData;
import com.ltdigor.bestflashlight.LampEnergy;
import com.ltdigor.bestflashlight.LampControl;
import com.ltdigor.bestflashlight.FlashlightNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.lwjgl.glfw.GLFW;
import top.theillusivec4.curios.api.client.CuriosRendererRegistry;

@EventBusSubscriber(modid = FlashlightMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FlashlightClientEvents {
    public static final KeyMapping HANDHELD = new KeyMapping("key.bestflashlight.handheld", KeyConflictContext.IN_GAME,
        InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT, "key.categories.bestflashlight");
    // Retaining this ID preserves the user's existing options.txt binding.
    public static final KeyMapping HEADBAND = new KeyMapping("key.bestflashlight.toggle", KeyConflictContext.IN_GAME,
        InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, "key.categories.bestflashlight");

    private FlashlightClientEvents() {}

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(HANDHELD);
        event.register(HEADBAND);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(FlashlightMod.FLASHLIGHT.get(), FlashlightMod.resource("enabled"),
                (stack, level, entity, seed) -> LampData.enabled(stack) ? 1 : 0);
            ItemProperties.register(FlashlightMod.HEADBAND.get(), FlashlightMod.resource("mounted"),
                (stack, level, entity, seed) -> LampData.mounted(stack).isEmpty() ? 0 : 1);
            CuriosRendererRegistry.register(FlashlightMod.HEADBAND.get(), HeadbandRenderer::new);
        });
        NeoForge.EVENT_BUS.addListener(FlashlightClientEvents::tick);
        NeoForge.EVENT_BUS.addListener(FlashlightClientEvents::key);
        NeoForge.EVENT_BUS.addListener(FlashlightClientEvents::mouse);
        NeoForge.EVENT_BUS.addListener(FlashlightClientEvents::interaction);
        NeoForge.EVENT_BUS.addListener(FlashlightClientEvents::tooltip);
        OptionalDynamicLights.register();
    }

    private static boolean inGame() {
        Minecraft client = Minecraft.getInstance();
        return client.screen == null && client.getOverlay() == null && client.player != null
            && !client.player.isSpectator() && client.player.isAlive();
    }

    private static LampControl.Action handAction() {
        return HANDHELD.matchesMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT) ? LampControl.Action.HAND_USE : LampControl.Action.HAND_KEY;
    }

    private static boolean send(LampControl.Action action) {
        if (LampControl.target(Minecraft.getInstance().player, action) == null) return false;
        PacketDistributor.sendToServer(new FlashlightNetwork.Toggle(action));
        return true;
    }

    private static void key(InputEvent.Key event) {
        if (!inGame() || event.getAction() != GLFW.GLFW_PRESS) return;
        if (HANDHELD.matches(event.getKey(), event.getScanCode()) && HANDHELD.isConflictContextAndModifierActive())
            send(handAction());
        if (HEADBAND.matches(event.getKey(), event.getScanCode()) && HEADBAND.isConflictContextAndModifierActive())
            send(LampControl.Action.HEADBAND);
    }

    private static void mouse(InputEvent.MouseButton.Pre event) {
        if (!inGame() || event.getAction() != GLFW.GLFW_PRESS) return;
        boolean handled = false;
        if (HANDHELD.matchesMouse(event.getButton()) && HANDHELD.isConflictContextAndModifierActive())
            handled = send(handAction());
        if (HEADBAND.matchesMouse(event.getButton()) && HEADBAND.isConflictContextAndModifierActive())
            handled |= send(LampControl.Action.HEADBAND);
        // Pre runs before vanilla marks use/attack down, so a held button cannot repeat use.
        if (handled) event.setCanceled(true);
    }

    private static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || !inGame()) return;
        var use = Minecraft.getInstance().options.keyUse;
        // Also suppress vanilla use when a keyboard binding is shared with Use Item.
        boolean hand = HANDHELD.getKey().equals(use.getKey()) && HANDHELD.isConflictContextAndModifierActive()
            && LampControl.target(Minecraft.getInstance().player, handAction()) != null;
        boolean head = HEADBAND.getKey().equals(use.getKey()) && HEADBAND.isConflictContextAndModifierActive()
            && LampControl.target(Minecraft.getInstance().player, LampControl.Action.HEADBAND) != null;
        if (hand || head) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    private static void tick(ClientTickEvent.Post event) {
        // Actions come only from physical press events, never from repeatable click queues.
        while (HANDHELD.consumeClick()) {}
        while (HEADBAND.consumeClick()) {}
        OptionalDynamicLights.clientTick();
    }

    private static void tooltip(ItemTooltipEvent event) {
        var stack = event.getItemStack();
        if (!LampData.isLamp(stack)) return;
        event.getToolTip().add(Component.translatable("tooltip.bestflashlight.energy", LampEnergy.stored(stack),
            FlashlightConfig.ENERGY_CAPACITY.get()).withStyle(ChatFormatting.AQUA));
        event.getToolTip().add(Component.translatable(LampData.enabled(stack) ? "tooltip.bestflashlight.on" : "tooltip.bestflashlight.off")
            .withStyle(LampData.enabled(stack) ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
    }
}
