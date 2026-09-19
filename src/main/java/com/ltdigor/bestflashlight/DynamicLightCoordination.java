package com.ltdigor.bestflashlight;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Negotiates whether vanilla temporary block lighting is still required.
 *
 * New clients report whether the optional LDL bridge is both compatible and enabled.
 * The server disables its expensive block-light beam only when every connected real
 * client supports that mode. Old clients and mixed sessions automatically keep fallback.
 */
final class DynamicLightCoordination {
    private static final Set<UUID> ACTIVE_CLIENTS = new HashSet<>();
    private static boolean fallbackEnabled = true;

    private DynamicLightCoordination() {}

    static void report(ServerPlayer player, boolean active) {
        if (active) ACTIVE_CLIENTS.add(player.getUUID());
        else ACTIVE_CLIENTS.remove(player.getUUID());
        recompute(player.getServer());
    }

    static void joined(ServerPlayer player) {
        ACTIVE_CLIENTS.remove(player.getUUID());
        recompute(player.getServer());
    }

    static void left(ServerPlayer player) {
        ACTIVE_CLIENTS.remove(player.getUUID());
        recompute(player.getServer(), player.getUUID());
    }

    static boolean useServerFallback(ServerPlayer player) {
        // Fake players and older clients never negotiate the optional capability.
        if (!player.connection.hasChannel(FlashlightNetwork.DynamicSupport.TYPE)) return true;
        return fallbackEnabled;
    }

    static void reset() {
        ACTIVE_CLIENTS.clear();
        fallbackEnabled = true;
    }

    private static void recompute(MinecraftServer server) {
        recompute(server, null);
    }

    private static void recompute(MinecraftServer server, UUID leaving) {
        var players = server.getPlayerList().getPlayers().stream()
            .filter(player -> leaving == null || !player.getUUID().equals(leaving))
            .toList();
        boolean next = players.isEmpty() || players.stream().anyMatch(player ->
            !player.connection.hasChannel(FlashlightNetwork.DynamicSupport.TYPE)
                || !ACTIVE_CLIENTS.contains(player.getUUID()));

        if (next == fallbackEnabled) return;
        fallbackEnabled = next;
        var packet = new FlashlightNetwork.FallbackMode(fallbackEnabled);
        for (ServerPlayer player : players) {
            if (player.connection.hasChannel(FlashlightNetwork.FallbackMode.TYPE)) {
                player.connection.send(packet);
            }
        }
    }
}
