package com.ltdigor.flashlightfe;

import com.ltdigor.flashlightfe.lighting.ServerBeamLightingManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Thin event wiring; all lighting logic lives in the lighting package. */
public final class FlashlightServerEvents {
    private FlashlightServerEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onPlayerChangedDimension);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onLivingDeath);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onServerTick);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onServerStopping);
        NeoForge.EVENT_BUS.addListener(FlashlightServerEvents::onServerStopped);
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerBeamLightingManager.get().updatePlayer(player);
        }
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) DynamicLightCoordination.joined(player);
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerBeamLightingManager.get().removePlayer(player);
            DynamicLightCoordination.left(player);
            LampSource.resetLegacyCheck(player.getUUID());
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerBeamLightingManager.get().playerChangedDimension(player);
            DynamicLightCoordination.changedDimension(player, event.getFrom(), event.getTo());
        }
    }

    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerBeamLightingManager.get().removePlayer(player);
        }
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && !event.isNewChunk()) {
            ServerBeamLightingManager.get().chunkLoaded(level, event.getChunk());
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerBeamLightingManager.get().endServerTick(event.getServer());
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ServerBeamLightingManager.get().levelUnloaded(level);
            DynamicLightCoordination.levelUnloaded(level.dimension());
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        ServerBeamLightingManager.get().serverStopping(event.getServer());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        ServerBeamLightingManager.get().serverStopped(event.getServer());
        DynamicLightCoordination.reset();
        LampSource.clearLegacyChecks();
    }
}
