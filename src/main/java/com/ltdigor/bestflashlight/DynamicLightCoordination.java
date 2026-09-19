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
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Coordinates the handoff between the compatibility block-light beam and optional LDL cones.
 *
 * The handoff is two-phase. Clients first report compatible/enabled LDL support. Once every
 * real player in a dimension supports it, the server asks those clients to enter dynamic mode
 * but deliberately keeps block-light fallback active. Only after every client acknowledges
 * that its dynamic renderer is ready does the server stop building temporary block lights.
 */
final class DynamicLightCoordination {
    private static final Set<UUID> ACTIVE_CLIENTS = new HashSet<>();
    private static final Map<ResourceKey<Level>, Set<UUID>> READY_BY_DIMENSION = new HashMap<>();
    private static final Map<ResourceKey<Level>, Boolean> FALLBACK_BY_DIMENSION = new HashMap<>();
    private static final Set<ResourceKey<Level>> DYNAMIC_REQUESTED = new HashSet<>();

    private DynamicLightCoordination() {}

    static void report(ServerPlayer player, boolean active) {
        if (player instanceof FakePlayer) return;
        UUID id = player.getUUID();
        if (active) ACTIVE_CLIENTS.add(id);
        else {
            ACTIVE_CLIENTS.remove(id);
            READY_BY_DIMENSION.values().forEach(ready -> ready.remove(id));
        }
        recompute(player.serverLevel());
    }

    static void ready(ServerPlayer player) {
        if (player instanceof FakePlayer || !supportsCoordinatedMode(player)
            || !ACTIVE_CLIENTS.contains(player.getUUID())) {
            return;
        }
        ResourceKey<Level> dimension = player.level().dimension();
        if (!DYNAMIC_REQUESTED.contains(dimension)) return;
        READY_BY_DIMENSION.computeIfAbsent(dimension, ignored -> new HashSet<>()).add(player.getUUID());
        recompute(player.serverLevel());
    }

    static void joined(ServerPlayer player) {
        if (player instanceof FakePlayer) return;
        UUID id = player.getUUID();
        ACTIVE_CLIENTS.remove(id);
        READY_BY_DIMENSION.values().forEach(ready -> ready.remove(id));
        recompute(player.serverLevel());
    }

    static void left(ServerPlayer player) {
        if (player instanceof FakePlayer) return;
        UUID id = player.getUUID();
        ACTIVE_CLIENTS.remove(id);
        READY_BY_DIMENSION.values().forEach(ready -> ready.remove(id));
        recompute(player.serverLevel(), id);
    }

    static void changedDimension(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
        if (player instanceof FakePlayer) return;
        MinecraftServer server = player.getServer();
        UUID id = player.getUUID();
        // Support/ready was reported for the old client level. Require a fresh
        // DynamicSupport report after the destination ClientLevel is installed so
        // a delayed old DynamicReady packet cannot satisfy the new dimension handoff.
        ACTIVE_CLIENTS.remove(id);
        READY_BY_DIMENSION.values().forEach(ready -> ready.remove(id));

        ServerLevel oldLevel = server.getLevel(from);
        if (oldLevel != null) {
            Set<UUID> oldReady = READY_BY_DIMENSION.get(from);
            if (oldReady != null) oldReady.remove(id);
            recompute(oldLevel, id);
        }

        ServerLevel newLevel = server.getLevel(to);
        if (newLevel != null) {
            forceFallbackForTopologyChange(newLevel);
            // Establish the fail-safe fallback state for the entering client first.
            // recompute() may then request dynamic mode for everyone. Sending the
            // fallback snapshot afterwards would overwrite that request and prevent
            // this player from ever ACKing DynamicReady.
            sendCurrentMode(player, to);
            recompute(newLevel);
        }
    }

    private static void sendCurrentMode(ServerPlayer player, ResourceKey<Level> dimension) {
        if (!player.connection.hasChannel(FlashlightNetwork.FallbackMode.TYPE)) return;
        boolean fallback = FALLBACK_BY_DIMENSION.getOrDefault(dimension, true);
        player.connection.send(new FlashlightNetwork.FallbackMode(fallback));
    }

    static boolean useServerFallback(ServerPlayer player) {
        if (player instanceof FakePlayer || !supportsCoordinatedMode(player)) return true;
        return FALLBACK_BY_DIMENSION.getOrDefault(player.level().dimension(), true);
    }

    static void levelUnloaded(ResourceKey<Level> dimension) {
        FALLBACK_BY_DIMENSION.remove(dimension);
        READY_BY_DIMENSION.remove(dimension);
        DYNAMIC_REQUESTED.remove(dimension);
    }

    static void reset() {
        ACTIVE_CLIENTS.clear();
        READY_BY_DIMENSION.clear();
        FALLBACK_BY_DIMENSION.clear();
        DYNAMIC_REQUESTED.clear();
    }

    private static boolean supportsCoordinatedMode(ServerPlayer player) {
        return player.connection.hasChannel(FlashlightNetwork.DynamicSupport.TYPE)
            && player.connection.hasChannel(FlashlightNetwork.DynamicReady.TYPE)
            && player.connection.hasChannel(FlashlightNetwork.FallbackMode.TYPE);
    }

    private static void forceFallbackForTopologyChange(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        boolean wasDynamicOrPending = !FALLBACK_BY_DIMENSION.getOrDefault(dimension, true)
            || DYNAMIC_REQUESTED.contains(dimension);

        FALLBACK_BY_DIMENSION.put(dimension, true);
        DYNAMIC_REQUESTED.remove(dimension);
        READY_BY_DIMENSION.remove(dimension);

        if (wasDynamicOrPending) sendMode(realPlayers(level, null), true);
    }

    private static void recompute(ServerLevel level) {
        recompute(level, null);
    }

    private static void recompute(ServerLevel level, UUID excludedPlayer) {
        var players = realPlayers(level, excludedPlayer);
        ResourceKey<Level> dimension = level.dimension();

        if (players.isEmpty()) {
            FALLBACK_BY_DIMENSION.put(dimension, true);
            DYNAMIC_REQUESTED.remove(dimension);
            READY_BY_DIMENSION.remove(dimension);
            return;
        }

        boolean allActive = players.stream().allMatch(player ->
            supportsCoordinatedMode(player) && ACTIVE_CLIENTS.contains(player.getUUID()));

        if (!allActive) {
            boolean wasDynamicOrPending = !FALLBACK_BY_DIMENSION.getOrDefault(dimension, true)
                || DYNAMIC_REQUESTED.contains(dimension);
            FALLBACK_BY_DIMENSION.put(dimension, true);
            DYNAMIC_REQUESTED.remove(dimension);
            READY_BY_DIMENSION.remove(dimension);
            if (wasDynamicOrPending) sendMode(players, true);
            return;
        }

        if (!FALLBACK_BY_DIMENSION.getOrDefault(dimension, true)) {
            // Already handed off; clients remain in dynamic mode.
            DYNAMIC_REQUESTED.add(dimension);
            return;
        }

        if (!DYNAMIC_REQUESTED.contains(dimension)) {
            DYNAMIC_REQUESTED.add(dimension);
            READY_BY_DIMENSION.put(dimension, new HashSet<>());
            sendMode(players, false);
            return;
        }

        Set<UUID> ready = READY_BY_DIMENSION.getOrDefault(dimension, Set.of());
        if (players.stream().allMatch(player -> ready.contains(player.getUUID()))) {
            // Every client already received dynamic mode, built its bridge, and ACKed.
            // From this point the server can safely stop the block-light fallback.
            FALLBACK_BY_DIMENSION.put(dimension, false);
        }
    }

    private static java.util.List<ServerPlayer> realPlayers(ServerLevel level, UUID excludedPlayer) {
        return level.players().stream()
            .filter(player -> !(player instanceof FakePlayer))
            .filter(player -> excludedPlayer == null || !player.getUUID().equals(excludedPlayer))
            .toList();
    }

    private static void sendMode(java.util.List<ServerPlayer> players, boolean fallbackEnabled) {
        var packet = new FlashlightNetwork.FallbackMode(fallbackEnabled);
        for (ServerPlayer player : players) {
            if (player.connection.hasChannel(FlashlightNetwork.FallbackMode.TYPE)) {
                player.connection.send(packet);
            }
        }
    }
}
