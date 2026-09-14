package com.ltdigor.bestflashlight;

import com.mojang.logging.LogUtils;
import com.ltdigor.bestflashlight.client.ButtonAnimation;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid="bestflashlight",value=Dist.CLIENT)
public final class MultiplayerClientSmoke {
    private static boolean opened, synchronizedBand;
    private static final Set<UUID> PRESS_OWNERS = new HashSet<>();
    private static final Set<UUID> MOVING_OWNERS = new HashSet<>();
    private static boolean localPress, remotePress, logged;
    @SubscribeEvent public static void press(FlashlightNetwork.PressEvent event) {
        var mc = Minecraft.getInstance();
        if (!Boolean.getBoolean("bestflashlight.multiplayer.client") || mc.player == null) return;
        if (event.hand() != InteractionHand.MAIN_HAND || event.previousEnabled() || !event.enabled())
            throw new AssertionError("Unexpected accepted handheld press payload");
        PRESS_OWNERS.add(event.owner());
        if (event.owner().equals(mc.player.getUUID())) localPress = true;
        else remotePress = true;
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(!Boolean.getBoolean("bestflashlight.multiplayer.client")) return;
        var mc=Minecraft.getInstance();
        if(!opened && mc.level==null && mc.getOverlay()==null) {
            opened=true; mc.options.renderDistance().set(2); mc.options.simulationDistance().set(5); mc.options.pauseOnLostFocus=false;
            ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25586"),
                new ServerData("Flashlight test","127.0.0.1:25586",ServerData.Type.OTHER),false,null);
        }
        if(mc.player!=null && !synchronizedBand && !LampSource.headband(mc.player).isEmpty()) {
            if(LampEnergy.stored(LampSource.headband(mc.player))==0) throw new AssertionError("Remote Curios battery not synchronized");
            synchronizedBand=true;
            LogUtils.getLogger().info("FLASHLIGHT_REMOTE_CLIENT_PASS: {} Curios battery synchronized",mc.player.getName().getString());
        }
        if (mc.player != null && PRESS_OWNERS.size() == 2) {
            PRESS_OWNERS.stream().filter(owner -> ButtonAnimation.offset(owner, InteractionHand.MAIN_HAND, true) < -.35)
                .forEach(MOVING_OWNERS::add);
            if (!logged && localPress && remotePress && MOVING_OWNERS.containsAll(PRESS_OWNERS)) {
                logged = true;
                LogUtils.getLogger().info("FLASHLIGHT_PRESS_CLIENT_PASS: {} received sender and tracking press animations", mc.player.getName().getString());
            }
        }
        if(opened && mc.screen instanceof DisconnectedScreen) {
            if(!synchronizedBand) throw new AssertionError("Disconnected before remote Curios synchronization");
            if(!localPress || !remotePress || !MOVING_OWNERS.containsAll(PRESS_OWNERS) || PRESS_OWNERS.size() != 2)
                throw new AssertionError("Client did not receive sender and tracking handheld press visual state");
            mc.stop();
        }
    }
}
