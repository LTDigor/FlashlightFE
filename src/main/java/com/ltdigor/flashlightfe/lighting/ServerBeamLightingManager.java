package com.ltdigor.flashlightfe.lighting;

import com.ltdigor.flashlightfe.DynamicLightCoordination;
import com.ltdigor.flashlightfe.FlashlightConfig;
import com.ltdigor.flashlightfe.FlashlightMod;
import com.ltdigor.flashlightfe.LampData;
import com.ltdigor.flashlightfe.LampEnergy;
import com.ltdigor.flashlightfe.LampSource;
import com.ltdigor.flashlightfe.FlashlightOwnerSync;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;

/**
 * Single owner of the server fallback lighting lifecycle. Players contribute immutable
 * beam frames; per dimension the frames are aggregated, diffed against the applied
 * carrier state and reconciled once per server tick. No block knows who lit it.
 */
public final class ServerBeamLightingManager {
    private static final ServerBeamLightingManager INSTANCE = new ServerBeamLightingManager();
    private static final int STATIC_BEAM_REFRESH_TICKS = 4;
    private static final int WORLD_VALIDATION_INTERVAL_TICKS = 4;
    private static final double CACHE_POSITION_EPSILON_SQR = 0.01 * 0.01;
    private static final double CACHE_DIRECTION_DOT = Math.cos(Math.toRadians(0.20));
    private static final int HEAD_MOUNTED_CLOSE_WALL_LEVEL = 4;
    private static final int HANDHELD_CLOSE_WALL_LEVEL = 15;

    private final Map<UUID, PlayerBeamState> players = new HashMap<>();
    private final Map<ResourceKey<Level>, AppliedDimensionState> applied = new HashMap<>();
    private final Set<ResourceKey<Level>> dirtyDimensions = new HashSet<>();
    private final ArrayDeque<OrphanCandidate> orphanCandidates = new ArrayDeque<>();
    private int lastValidationTick = -1;

    /** Geometry cache plus the frame it produced; owns no world positions per player. */
    public record PlayerBeamState(BeamFrame frame, Vec3 eye, Vec3 emitter, Vec3 look,
                                  ResourceKey<Level> dimension, boolean headMounted, boolean offHand,
                                  double range, double angle, long computedAtTick) {}

    private record OrphanCandidate(ResourceKey<Level> dimension, BlockPos pos) {}

    public static ServerBeamLightingManager get() {
        return INSTANCE;
    }

    private ServerBeamLightingManager() {}

    public boolean hasPlayerFrame(UUID player) {
        return players.containsKey(player);
    }

    public PlayerBeamState playerFrame(UUID player) {
        return players.get(player);
    }



    /** Source validation, geometry (cached when static), FE drain and dirty marking. */
    public void updatePlayer(ServerPlayer player) {
        UUID owner = player.getUUID();
        LampSource.returnInvalidFlashlights(player);
        if (!player.isAlive() || player.isSpectator()) {
            removePlayer(player);
            return;
        }

        ServerLevel level = player.serverLevel();
        long gameTick = level.getGameTime();
        int energyCost = LampEnergy.costForTick(gameTick);
        Vec3 look = player.getLookAngle().normalize();
        Vec3 eye = player.getEyePosition();
        LampSource source = LampSource.select(player, candidate -> {
            if (!LampEnergy.hasPower(candidate.stack(), player)
                || !player.isCreative() && LampEnergy.stored(candidate.stack()) < energyCost) {
                LampData.setEnabled(candidate.stack(), false);
                return false;
            }
            Vec3 candidateEmitter = emitterOrigin(player, candidate, look);
            return FlashlightConfig.WORKS_UNDERWATER.get() || !ServerBeamCalculator.isSubmerged(level, candidateEmitter);
        });
        if (source == null) {
            removePlayer(player);
            return;
        }

        // In an all-LDL session every client renders every tracked player's cone.
        // Keep FE and source validation authoritative on the server, but skip the
        // temporary block-light beam entirely.
        if (!DynamicLightCoordination.useServerFallback(player)) {
            removePlayer(player);
            if (LampEnergy.consume(source.stack(), player, energyCost) && energyCost > 0) {
                FlashlightOwnerSync.syncDrain(player, source);
            }
            return;
        }

        Vec3 emitter = emitterOrigin(player, source, look);
        Vec3 origin = ServerBeamCalculator.emitterPathClear(player, level, eye, emitter) ? emitter : eye;
        double range = ServerBeamCalculator.configuredRange();
        double angle = ServerBeamCalculator.configuredFullAngleDegrees();
        PlayerBeamState previous = players.get(owner);
        boolean reuse = previous != null
            && previous.dimension().equals(level.dimension())
            && previous.headMounted() == source.headMounted()
            && previous.offHand() == source.offHand()
            && previous.eye().distanceToSqr(eye) <= CACHE_POSITION_EPSILON_SQR
            && previous.emitter().distanceToSqr(emitter) <= CACHE_POSITION_EPSILON_SQR
            && previous.look().dot(look) >= CACHE_DIRECTION_DOT
            && Double.compare(previous.range(), range) == 0
            && Double.compare(previous.angle(), angle) == 0
            && gameTick - previous.computedAtTick() < STATIC_BEAM_REFRESH_TICKS;

        PlayerBeamState state = previous;
        if (!reuse) {
            BeamFrame frame = computeFrame(player, level, origin, look, source.headMounted());
            state = new PlayerBeamState(frame, eye, emitter, look, level.dimension(),
                source.headMounted(), source.offHand(), range, angle, gameTick);
            players.put(owner, state);
            if (previous == null || !previous.frame().equals(frame) || !previous.dimension().equals(level.dimension())) {
                dirtyDimensions.add(level.dimension());
            }
        }

        if (!LampEnergy.consume(source.stack(), player, energyCost)) {
            // The lamp dies this tick: its frame must not survive into the next aggregate.
            removePlayer(player);
            return;
        }
        if (energyCost > 0) FlashlightOwnerSync.syncDrain(player, source);
    }

    private BeamFrame computeFrame(ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 look,
                                   boolean headMounted) {
        BeamFrame frame = headMounted
            ? ServerBeamCalculator.headMounted(player, level, origin, look)
            : ServerBeamCalculator.handheld(player, level, origin, look);
        if (!frame.isEmpty()) return frame;
        Vec3 eye = player.getEyePosition();
        return ServerBeamCalculator.closeWall(player, level, eye, look,
            headMounted ? HEAD_MOUNTED_CLOSE_WALL_LEVEL : HANDHELD_CLOSE_WALL_LEVEL);
    }

    private static Vec3 emitterOrigin(ServerPlayer player, LampSource source, Vec3 look) {
        Vec3 eye = player.getEyePosition();
        double yaw = Math.toRadians(player.getYRot());
        boolean rightSide = (player.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) != source.offHand();
        EmitterTransform transform = source.headMounted() ? EmitterTransform.HEADBAND : EmitterTransform.HANDHELD;
        return transform.origin(eye, look, yaw, rightSide, source.headMounted());
    }

    /** Logout, death and LDL-only sessions drop the frame; reconciliation cleans the world. */
    public void removePlayer(ServerPlayer player) {
        PlayerBeamState state = players.remove(player.getUUID());
        if (state != null) dirtyDimensions.add(state.dimension());
    }

    public void playerChangedDimension(ServerPlayer player) {
        removePlayer(player);
    }

    /** Persisted or externally placed carriers are queued here and resolved on reconcile. */
    public void chunkLoaded(ServerLevel level, ChunkAccess chunk) {
        List<BlockPos> found = new ArrayList<>();
        chunk.findBlocks(state -> state.is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
            (pos, state) -> found.add(pos.immutable()));
        if (found.isEmpty()) return;
        for (BlockPos pos : found) orphanCandidates.addLast(new OrphanCandidate(level.dimension(), pos));
        dirtyDimensions.add(level.dimension());
    }

    public void levelUnloaded(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        applied.remove(dimension);
        players.values().removeIf(state -> state.dimension().equals(dimension));
        orphanCandidates.removeIf(candidate -> candidate.dimension().equals(dimension));
        dirtyDimensions.remove(dimension);
    }

    /** One aggregate + one reconciliation per dirty dimension per server tick. */
    public void endServerTick(MinecraftServer server) {
        validateAppliedWorld(server);
        if (dirtyDimensions.isEmpty() && orphanCandidates.isEmpty()) return;
        for (ResourceKey<Level> dimension : List.copyOf(dirtyDimensions)) {
            ServerLevel level = server.getLevel(dimension);
            if (level == null) continue;
            reconcile(level, dimension);
        }
        dirtyDimensions.clear();
    }

    /**
     * The cached frame describes desired light, not proof that a carrier still exists.
     * Periodically reconcile our bookkeeping with loaded-world state. This is a read-only
     * pass over tracked positions, not a block tick, and never acquires unloaded chunks.
     */
    private void validateAppliedWorld(MinecraftServer server) {
        int tick = server.getTickCount();
        if (tick == lastValidationTick || tick % WORLD_VALIDATION_INTERVAL_TICKS != 0) return;
        lastValidationTick = tick;
        for (var dimension : applied.entrySet()) {
            ServerLevel level = server.getLevel(dimension.getKey());
            if (level == null) continue;
            var iterator = dimension.getValue().lights().entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                BlockPos pos = entry.getKey();
                if (!ServerBeamCalculator.isLoaded(level, pos)) continue;
                var actual = level.getBlockState(pos);
                if (!actual.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
                    iterator.remove();
                    dirtyDimensions.add(dimension.getKey());
                } else {
                    AppliedLight observed = new AppliedLight(actual.getValue(TransientLightBlock.LEVEL),
                        TransientLightWorldApplier.environmentOf(actual));
                    if (!observed.equals(entry.getValue())) {
                        entry.setValue(observed);
                        dirtyDimensions.add(dimension.getKey());
                    }
                }
            }
        }
    }

    private void reconcile(ServerLevel level, ResourceKey<Level> dimension) {
        List<BeamFrame> frames = new ArrayList<>();
        for (PlayerBeamState state : players.values()) {
            if (state.dimension().equals(dimension)) frames.add(state.frame());
        }
        Map<BlockPos, DesiredLight> desired = BeamFrameAggregator.aggregateDimension(frames, dimension);
        AppliedDimensionState state = applied.computeIfAbsent(dimension, ignored -> new AppliedDimensionState());

        resolveOrphans(level, state, desired, dimension);

        ReconciliationPlan plan = LightFrameReconciler.plan(state.lights(), desired);
        if (!plan.isEmpty()) TransientLightWorldApplier.apply(level, state, plan);
        if (state.isEmpty()) applied.remove(dimension);
    }

    private void resolveOrphans(ServerLevel level, AppliedDimensionState state,
                                Map<BlockPos, DesiredLight> desired, ResourceKey<Level> dimension) {
        int pending = orphanCandidates.size();
        for (int i = 0; i < pending; i++) {
            OrphanCandidate candidate = orphanCandidates.pollFirst();
            if (candidate == null || !candidate.dimension().equals(dimension)) {
                if (candidate != null) orphanCandidates.addLast(candidate);
                continue;
            }
            BlockPos pos = candidate.pos();
            if (!ServerBeamCalculator.isLoaded(level, pos)) continue; // chunk-load will re-queue it
            boolean carrier = level.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get());
            DesiredLight want = desired.get(pos);
            if (want != null) {
                if (carrier) TransientLightWorldApplier.adopt(level, state, pos, want);
            } else if (carrier) {
                TransientLightWorldApplier.restore(level, state, pos);
            }
        }
    }

    /** Restore before the server performs its final world save. */
    public void serverStopping(MinecraftServer server) {
        for (var entry : new HashMap<>(applied).entrySet()) {
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) continue;
            for (BlockPos pos : List.copyOf(entry.getValue().lights().keySet())) {
                TransientLightWorldApplier.restore(level, entry.getValue(), pos);
            }
        }
        // Also clean loaded persisted carriers which were queued after the last reconcile.
        for (OrphanCandidate orphan : orphanCandidates) {
            ServerLevel level = server.getLevel(orphan.dimension());
            if (level != null) {
                TransientLightWorldApplier.restore(level, new AppliedDimensionState(), orphan.pos());
            }
        }
        clearState();
    }

    /** Worlds have already closed here; only release references, never mutate them. */
    public void serverStopped(MinecraftServer server) {
        clearState();
    }

    private void clearState() {
        players.clear();
        applied.clear();
        dirtyDimensions.clear();
        orphanCandidates.clear();
        lastValidationTick = -1;
    }

    /** Test hook: mutation counters prove a static beam stops touching the world. */
    public static long worldMutations() {
        return TransientLightWorldApplier.additions()
            + TransientLightWorldApplier.updates()
            + TransientLightWorldApplier.removals();
    }

    public static void resetWorldMutationCounters() {
        TransientLightWorldApplier.resetCounters();
    }
}
