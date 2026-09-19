package com.ltdigor.bestflashlight;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Negotiates whether vanilla temporary block lighting is still required.
 *
 * New clients report whether the optional LDL bridge is both compatible and enabled.
 * Fallback is decided independently per dimension: an old/non-LDL player elsewhere
 * must not force expensive temporary block lighting into an all-LDL dimension.
 */
final class DynamicLightCoordination {
    private static final Set<UUID> ACTIVE_CLIENTS = new HashSet<>();
    private static final Map<ResourceKey<Level>, Boolean> FALLBACK_BY_DIMENSION = new HashMap<>();

    private DynamicLightCoordination() {}

    static void report(ServerPlayer player, boolean active) {
        if (active) ACTIVE_CLIENTS.add(player.getUUID());
        else ACTIVE_CLIENTS.remove(player.getUUID());
        recompute(player.serverLevel());
    }

    static void joined(ServerPlayer player) {
        ACTIVE_CLIENTS.remove(player.getUUID());
        recompute(player.serverLevel());
    }

    static void left(ServerPlayer player) {
        ACTIVE_CLIENTS.remove(player.getUUID());
        recompute(player.serverLevel(), player.getUUID());
    }

    static void changedDimension(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
        MinecraftServer server = player.getServer();
        ServerLevel oldLevel = server.getLevel(from);
        ServerLevel newLevel = server.getLevel(to);
        if (oldLevel != null) recompute(oldLevel, player.getUUID());
        if (newLevel != null) {
            recompute(newLevel);
            sendCurrentMode(player);
        }
    }

    static boolean useServerFallback(ServerPlayer player) {
        if (!supportsCoordinatedMode(player)) return true;
        return FALLBACK_BY_DIMENSION.getOrDefault(player.level().dimension(), true);
    }

    private static void sendCurrentMode(ServerPlayer player) {
        if (!player.connection.hasChannel(FlashlightNetwork.FallbackMode.TYPE)) return;
        boolean fallback = FALLBACK_BY_DIMENSION.getOrDefault(player.level().dimension(), true);
        player.connection.send(new FlashlightNetwork.FallbackMode(fallback));
    }

    static void levelUnloaded(ResourceKey<Level> dimension) {
        FALLBACK_BY_DIMENSION.remove(dimension);
    }

    static void reset() {
        ACTIVE_CLIENTS.clear();
        FALLBACK_BY_DIMENSION.clear();
    }

    private static boolean supportsCoordinatedMode(ServerPlayer player) {
        return player.connection.hasChannel(FlashlightNetwork.DynamicSupport.TYPE)
            && player.connection.hasChannel(FlashlightNetwork.FallbackMode.TYPE);
    }

    private static void recompute(ServerLevel level) {
        recompute(level, null);
    }

    private static void recompute(ServerLevel level, UUID excludedPlayer) {
        var players = level.players().stream()
            .filter(player -> excludedPlayer == null || !player.getUUID().equals(excludedPlayer))
            .toList();

        boolean nextFallback = players.isEmpty() || players.stream().anyMatch(player ->
            !supportsCoordinatedMode(player) || !ACTIVE_CLIENTS.contains(player.getUUID()));

        ResourceKey<Level> dimension = level.dimension();
        boolean previous = FALLBACK_BY_DIMENSION.getOrDefault(dimension, true);
        FALLBACK_BY_DIMENSION.put(dimension, nextFallback);
        if (previous == nextFallback) return;

        var packet = new FlashlightNetwork.FallbackMode(nextFallback);
        for (ServerPlayer player : players) {
            if (player.connection.hasChannel(FlashlightNetwork.FallbackMode.TYPE)) {
                player.connection.send(packet);
            }
        }
    }
}
