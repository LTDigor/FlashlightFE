package com.ltdigor.flashlightfe.client;

import com.ltdigor.flashlightfe.FlashlightMod;
import com.ltdigor.flashlightfe.FlashlightNetwork;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Render-only state: no animation timestamps or phases are stored in item data. */
@EventBusSubscriber(modid = FlashlightMod.MOD_ID, value = Dist.CLIENT)
public final class ButtonAnimation {
    private static final int MAX_ACTIVE_PRESSES = 512;
    private static final double NANOS_PER_MODEL_TICK = 50_000_000.0;
    private static final Map<HandKey, ButtonMotion> MOTIONS = new LinkedHashMap<>();
    private static ClientLevel level;

    private ButtonAnimation() {}

    @SubscribeEvent
    public static void pressed(FlashlightNetwork.PressEvent event) {
        press(event.owner(), event.hand(), event.previousEnabled(), event.enabled());
    }

    public static void press(UUID owner, InteractionHand hand, boolean enabled) {
        press(owner, hand, !enabled, enabled);
    }

    public static void press(UUID owner, InteractionHand hand, boolean previousEnabled, boolean enabled) {
        double now = clock();
        prune(now);
        HandKey key = new HandKey(owner, hand);
        ButtonMotion previous = MOTIONS.get(key);
        double initial = previous == null ? ButtonMotion.rest(previousEnabled) : previous.sample(now);
        MOTIONS.remove(key);
        if (MOTIONS.size() >= MAX_ACTIVE_PRESSES) MOTIONS.remove(MOTIONS.keySet().iterator().next());
        MOTIONS.put(key, new ButtonMotion(now, initial, enabled));
    }

    /** Y translation in model pixels, where negative values depress the button. */
    public static double offset(UUID owner, InteractionHand hand, boolean enabled) {
        double now = clock();
        HandKey key = new HandKey(owner, hand);
        ButtonMotion motion = MOTIONS.get(key);
        if (motion == null) return ButtonMotion.rest(enabled);
        if (now >= motion.startedAt() + ButtonMotion.DURATION_TICKS) {
            MOTIONS.remove(key);
            return ButtonMotion.rest(enabled);
        }
        return motion.sample(now);
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        prune(clock());
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        MOTIONS.clear();
        level = null;
    }

    private static double clock() {
        synchronizeLevel();
        // Packets run before ClientTick.Post; the render timer can wrap between them.
        // One monotonic source keeps interrupted presses continuous across that boundary.
        return System.nanoTime() / NANOS_PER_MODEL_TICK;
    }

    private static void synchronizeLevel() {
        ClientLevel current = Minecraft.getInstance().level;
        if (current != level) {
            MOTIONS.clear();
            level = current;
        }
    }

    private static void prune(double now) {
        MOTIONS.values().removeIf(motion -> now >= motion.startedAt() + ButtonMotion.DURATION_TICKS);
    }

    private record HandKey(UUID owner, InteractionHand hand) {}
}
