package com.ltdigor.flashlightfe;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** Input intentions are resolved again against the server's actual equipment. */
public final class LampControl {
    public enum Action { HAND_USE, HAND_KEY, HEADBAND }

    private LampControl() {}

    public static String target(LivingEntity player, Action action) {
        if (action == Action.HEADBAND) return LampSource.headband(player).isEmpty() ? null : "headband";
        if (FlashlightMod.isFlashlight(player.getMainHandItem())) return "main";
        if (action == Action.HAND_USE && !player.getMainHandItem().isEmpty()) return null;
        return FlashlightMod.isFlashlight(player.getOffhandItem()) ? "off" : null;
    }

    public static int press(ServerPlayer player, Action action) {
        if (!player.isAlive() || player.isSpectator()) return 0;
        String target = target(player, action);
        if (target == null) return 0;
        ItemStack stack = switch (target) {
            case "main" -> player.getMainHandItem();
            case "off" -> player.getOffhandItem();
            default -> LampSource.headband(player);
        };
        boolean before = LampData.enabled(stack);
        boolean enabled = LampEnergy.toggle(stack, player);
        if (!target.equals("headband")) {
            InteractionHand hand = target.equals("main") ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new FlashlightNetwork.Press(player.getUUID(), hand, before, enabled));
        }
        player.sendSystemMessage(Component.translatable(enabled ? "message.bestflashlight.on" : "message.bestflashlight.off"), true);
        player.serverLevel().playSound(null, player.blockPosition(), SoundEvents.LEVER_CLICK,
            SoundSource.PLAYERS, 0.4F, enabled ? 0.7F : 0.5F);
        return 1;
    }
}
