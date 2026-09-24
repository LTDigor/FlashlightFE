package com.ltdigor.flashlightfe;

import com.ltdigor.flashlightfe.lighting.ServerBeamLightingManager;
import com.ltdigor.flashlightfe.lighting.RestorableEnvironment;
import com.ltdigor.flashlightfe.lighting.ServerBeamCalculator;
import com.ltdigor.flashlightfe.lighting.ServerBeamLightingManager;
import com.ltdigor.flashlightfe.lighting.ServerBeamLightingManager;
import com.ltdigor.flashlightfe.lighting.TransientLightBlock;
import com.mojang.authlib.GameProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

/** Behavioral coverage of the aggregate beam-frame lighting pipeline. */
@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class BeamGameTests {
    @GameTest(template = "empty")
    public static void submergedHandEmitterDoesNotDrainWithEyeInAir(GameTestHelper helper) {
        boolean originalWorksUnderwater = FlashlightConfig.WORKS_UNDERWATER.get();
        ServerLevel level = helper.getLevel();
        Vec3 feet = helper.absoluteVec(new Vec3(8.5, 1.48, 3.5));
        ServerPlayer player = geometryPlayer(level, feet, GameType.CREATIVE);
        try {
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            helper.setBlock(8, 2, 4, Blocks.WATER);
            ItemStack lamp = chargedLamp(20);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
            FlashlightConfig.WORKS_UNDERWATER.set(false);
            FlashlightConfig.WORKS_UNDERWATER.clearCache();

            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());

            helper.assertTrue(LampEnergy.stored(lamp) == 20, "Submerged hand emitter must not consume energy when the player's eye is in air");
            helper.assertTrue(countTemporaryLights(helper) == 0, "Submerged hand emitter must not create light when underwater use is disabled");
        } finally {
            FlashlightConfig.WORKS_UNDERWATER.set(originalWorksUnderwater);
            FlashlightConfig.WORKS_UNDERWATER.clearCache();
            FlashlightServerEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void submergedCheckUsesActualFlowingFluidSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4), 3);
        double surface = pos.getY() + level.getFluidState(pos).getHeight(level, pos);
        Vec3 belowSurface = new Vec3(pos.getX() + 0.5, surface - 0.01, pos.getZ() + 0.5);
        Vec3 aboveSurface = new Vec3(pos.getX() + 0.5, surface + 0.01, pos.getZ() + 0.5);

        helper.assertTrue(ServerBeamCalculator.isSubmerged(level, belowSurface), "Emitter below flowing water surface must count as submerged");
        helper.assertTrue(!ServerBeamCalculator.isSubmerged(level, aboveSurface), "Emitter above flowing water surface must remain dry");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void offAxisObstacleBlocksOnlyItsOuterRingRay(GameTestHelper helper) {
        double originalRange = FlashlightConfig.BEAM_RANGE.get();
        double originalAngle = FlashlightConfig.CONE_ANGLE_DEGREES.get();
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin, GameType.CREATIVE);
        try {
            setBeamConfig(12.0, 30.0);
            helper.setBlock(7, 2, 8, Blocks.STONE);

            Map<BlockPos, Integer> beam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            BlockPos blockedOuter = helper.absolutePos(new BlockPos(5, 2, 14));
            BlockPos farCenter = helper.absolutePos(new BlockPos(8, 2, 14));
            BlockPos openOuter = helper.absolutePos(new BlockPos(11, 2, 14));

            helper.assertTrue(!beam.containsKey(blockedOuter), "Off-axis obstacle must block the outer-ring candidate behind it");
            helper.assertTrue(beam.containsKey(farCenter), "Off-axis obstacle must leave the far center ray open");
            helper.assertTrue(beam.containsKey(openOuter), "Off-axis obstacle must leave the opposite outer-ring ray open");
        } finally {
            setBeamConfig(originalRange, originalAngle);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void solidWallBlocksAllBeamCandidatesBehindIt(GameTestHelper helper) {
        double originalRange = FlashlightConfig.BEAM_RANGE.get();
        double originalAngle = FlashlightConfig.CONE_ANGLE_DEGREES.get();
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin, GameType.CREATIVE);
        try {
            setBeamConfig(12.0, 30.0);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 8; y++) {
                    helper.setBlock(x, y, 8, Blocks.STONE);
                }
            }

            Map<BlockPos, Integer> beam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            int wallZ = helper.absolutePos(new BlockPos(0, 0, 8)).getZ();

            helper.assertTrue(
                beam.keySet().stream().anyMatch(pos -> pos.getZ() + 0.5 > origin.z && pos.getZ() < wallZ),
                "Beam must create candidates between its origin and the wall"
            );
            helper.assertTrue(
                beam.keySet().stream().noneMatch(pos -> pos.getZ() > wallZ),
                "Solid wall must block every center and ring candidate behind it"
            );
        } finally {
            setBeamConfig(originalRange, originalAngle);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void configuredRangeLimitsForwardBeamDistance(GameTestHelper helper) {
        double originalRange = FlashlightConfig.BEAM_RANGE.get();
        double originalAngle = FlashlightConfig.CONE_ANGLE_DEGREES.get();
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin, GameType.CREATIVE);
        try {
            setBeamConfig(4.0, 15.0);
            Map<BlockPos, Integer> shortBeam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            setBeamConfig(12.0, 15.0);
            Map<BlockPos, Integer> longBeam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            double shortDistance = maxForwardDistance(shortBeam, origin);
            double longDistance = maxForwardDistance(longBeam, origin);

            helper.assertTrue(!shortBeam.isEmpty(), "Range 4 must still create beam candidates");
            helper.assertTrue(shortDistance <= 4.0, "Range 4 must not create candidates beyond four blocks along the beam");
            helper.assertTrue(longDistance >= 11.0, "Range 12 must create candidates near the far end of the beam");
            helper.assertTrue(longDistance >= shortDistance + 7.0, "Increasing range from 4 to 12 must extend the candidate footprint");
        } finally {
            setBeamConfig(originalRange, originalAngle);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void largerFullAngleWidensLateralFootprint(GameTestHelper helper) {
        double originalRange = FlashlightConfig.BEAM_RANGE.get();
        double originalAngle = FlashlightConfig.CONE_ANGLE_DEGREES.get();
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin, GameType.CREATIVE);
        try {
            setBeamConfig(12.0, 15.0);
            Map<BlockPos, Integer> narrowBeam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            setBeamConfig(12.0, 30.0);
            Map<BlockPos, Integer> wideBeam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            double narrowWidth = maxLateralDistanceNearEnd(narrowBeam, origin);
            double wideWidth = maxLateralDistanceNearEnd(wideBeam, origin);

            helper.assertTrue(narrowWidth > 0.0, "15-degree full angle must produce a nonzero cone footprint");
            helper.assertTrue(wideWidth >= narrowWidth + 1.0, "30-degree full angle must produce a wider lateral footprint than 15 degrees");
        } finally {
            setBeamConfig(originalRange, originalAngle);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overlappingBeamsResolveByMaximumAndDowngradeOnLeave(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer bright = geometryPlayer(level, helper.absoluteVec(new Vec3(8.5, 2.5, 6.5)), GameType.SURVIVAL);
        ServerPlayer dim = geometryPlayer(level, helper.absoluteVec(new Vec3(8.5, 2.5, 2.5)), GameType.SURVIVAL);
        BlockPos target = helper.absolutePos(new BlockPos(8, 4, 9));
        try {
            ItemStack brightLamp = chargedLamp(60);
            ItemStack dimLamp = chargedLamp(60);
            LampData.setEnabled(brightLamp, true);
            LampData.setEnabled(dimLamp, true);
            bright.setItemSlot(EquipmentSlot.MAINHAND, brightLamp);
            dim.setItemSlot(EquipmentSlot.MAINHAND, dimLamp);
            bright.setYRot(0.0F);
            bright.setXRot(0.0F);
            dim.setYRot(0.0F);
            dim.setXRot(0.0F);

            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(bright));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(dim));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            helper.assertTrue(level.getBlockState(target).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                "Overlapping beams must share one carrier");
            int shared = level.getBlockState(target).getValue(TransientLightBlock.LEVEL);
            helper.assertTrue(shared >= 12, "Shared carrier must use the brighter frame, got " + shared);

            LampData.setEnabled(brightLamp, false);
            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(bright));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            helper.assertTrue(level.getBlockState(target).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                "Losing the brighter source must keep the carrier for the remaining beam");
            int downgraded = level.getBlockState(target).getValue(TransientLightBlock.LEVEL);
            helper.assertTrue(downgraded < shared && downgraded > 0,
                "Remaining source must downgrade the shared carrier in place, got " + downgraded);

            LampData.setEnabled(dimLamp, false);
            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(dim));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            helper.assertTrue(!level.getBlockState(target).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                "Losing the last source must remove the shared carrier");
        } finally {
            FlashlightServerEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(bright));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            FlashlightServerEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(dim));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            bright.discard();
            dim.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void externalSolidReplacementIsNeverOverwritten(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = geometryPlayer(level, helper.absoluteVec(new Vec3(8.5, 2.5, 2.5)), GameType.SURVIVAL);
        BlockPos target = helper.absolutePos(new BlockPos(8, 4, 6));
        try {
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            ItemStack lamp = chargedLamp(60);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            helper.assertTrue(level.getBlockState(target).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                "Beam must place a carrier in front of the player");

            level.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
            reconcile(level);
            helper.assertTrue(level.getBlockState(target).is(Blocks.STONE),
                "A solid block placed into the beam must survive reconciliation");

            LampData.setEnabled(lamp, false);
            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            helper.assertTrue(level.getBlockState(target).is(Blocks.STONE),
                "Beam shutdown must not restore air over an externally placed block");
        } finally {
            FlashlightServerEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void temporaryCarrierCannotBeMovedByPistons(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, TransientLightBlock.carrier(9, new RestorableEnvironment.Air()), 3);
        helper.assertTrue(level.getBlockState(pos).getPistonPushReaction() == PushReaction.DESTROY,
            "Transient carrier must yield by destruction, never block or move with a piston");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nonWaterFluidReplacesDryCarrierAndStaysReplaced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, TransientLightBlock.carrier(9, new RestorableEnvironment.Air()), 3);
        level.setBlock(pos, Blocks.LAVA.defaultBlockState(), 3);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.LAVA), "Lava must replace a dry carrier");
        recoverOrphans(level, pos);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.LAVA),
            "Orphan recovery must not restore a carrier over a real fluid");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void temporaryWaterCarrierPreservesVanillaBucketPickup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(pos, TransientLightBlock.carrier(9, new RestorableEnvironment.Water(0)), 3);
        BlockState carrier = level.getBlockState(pos);
        helper.assertTrue(carrier.getValue(TransientLightBlock.WATERLOGGED), "Carrier in source water must be waterlogged");

        ItemStack bucket = ((BucketPickup) FlashlightMod.FLASHLIGHT_LIGHT.get())
            .pickupBlock(null, level, pos, carrier);
        helper.assertTrue(bucket.getItem() == net.minecraft.world.item.Items.WATER_BUCKET,
            "Source water inside a carrier must remain bucket-pickable");
        helper.assertTrue(!level.getBlockState(pos).getValue(TransientLightBlock.WATERLOGGED),
            "Bucket pickup must dry the carrier");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sourceWaterSurvivesLightLifecycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(pos, TransientLightBlock.carrier(15, new RestorableEnvironment.Water(0)), 3);

        BlockState light = level.getBlockState(pos);
        helper.assertTrue(light.is(FlashlightMod.FLASHLIGHT_LIGHT.get()), "Source water must accept temporary flashlight light");
        helper.assertTrue(light.getValue(TransientLightBlock.WATERLOGGED), "Temporary light in source water must be waterlogged");

        recoverOrphans(level, pos);
        BlockState restored = level.getBlockState(pos);
        helper.assertTrue(restored.is(Blocks.WATER), "Removing the last contributor must restore source water");
        helper.assertTrue(restored.getFluidState().isSource(), "Restored water must remain a source block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void flowingWaterSurvivesTemporaryLightAndPlantsStayUntouched(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos flowingPos = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos plantPos = flowingPos.above();
        level.setBlock(flowingPos, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 5), 3);
        level.setBlock(plantPos, Blocks.KELP.defaultBlockState(), 3);
        level.setBlock(flowingPos, TransientLightBlock.carrier(15, new RestorableEnvironment.Water(5)), 3);

        BlockState filledCarrier = level.getBlockState(flowingPos);
        helper.assertTrue(filledCarrier.is(FlashlightMod.FLASHLIGHT_LIGHT.get())
                && filledCarrier.getValue(TransientLightBlock.WATERLOGGED)
                && filledCarrier.getValue(TransientLightBlock.WATER_LEVEL) == 5,
            "Flowing water level must be preserved inside the carrier");
        helper.assertTrue(level.getBlockState(plantPos).is(Blocks.KELP),
            "Plants around a carrier must never be replaced");

        recoverOrphans(level, flowingPos);
        BlockState restored = level.getBlockState(flowingPos);
        helper.assertTrue(restored.is(Blocks.WATER) && restored.getValue(LiquidBlock.LEVEL) == 5,
            "Restoration must return the exact flowing-water level");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void flowingWaterTickPreservesCarrierAndContinuesSimulation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, TransientLightBlock.carrier(9, new RestorableEnvironment.Water(4)), 3);

        boolean preserved = TransientLightBlock.preserveCarrierDuringFluidTick(
            level, pos, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2), 3);
        helper.assertTrue(preserved, "A flowing-water tick must fold into the carrier instead of replacing it");
        BlockState carrier = level.getBlockState(pos);
        helper.assertTrue(carrier.is(FlashlightMod.FLASHLIGHT_LIGHT.get())
                && carrier.getValue(TransientLightBlock.WATER_LEVEL) == 2,
            "Carrier must track the newest flowing-water level");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void dryCarrierReleaseRearmsNeighboringWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos source = pos.relative(net.minecraft.core.Direction.NORTH);
        level.setBlock(source, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(pos, TransientLightBlock.carrier(9, new RestorableEnvironment.Air()), 3);

        recoverOrphans(level, pos);
        helper.assertTrue(level.getBlockState(pos).isAir(), "Dry carrier must restore air");
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(level.getBlockState(pos).is(Blocks.WATER),
                "Neighbouring source water must flow into the restored air cell");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void diagonalCornerCollisionBlocksOrientationIndependentRay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = geometryPlayer(level, helper.absoluteVec(new Vec3(8.5, 2.5, 8.5)), GameType.CREATIVE);
        try {
            BlockPos startCell = helper.absolutePos(new BlockPos(8, 2, 8));
            Vec3 origin = player.position();
            Vec3 end = origin.add(4.0, 4.0, 0.0);
            Vec3 axis = end.subtract(origin).normalize();

            // At the first X/Y corner crossing the traversal must test the side cell too,
            // otherwise a solid touching an exact voxel corner only blocks one orientation.
            level.setBlock(startCell.above(), Blocks.STONE.defaultBlockState(), 3);

            Map<BlockPos, Integer> beam = computeBeam(player, level, origin, axis);
            helper.assertTrue(!beam.containsKey(startCell.offset(1, 1, 0)),
                "A solid touching an exact voxel corner from either side must block diagonal continuation");
        } finally {
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void orphanCleanupRestoresFlowingWaterLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, TransientLightBlock.carrier(9, new RestorableEnvironment.Water(5)), 3);

        recoverOrphans(level, pos);

        BlockState restored = level.getBlockState(pos);
        helper.assertTrue(restored.is(Blocks.WATER) && restored.getValue(LiquidBlock.LEVEL) == 5,
            "Orphan cleanup must restore the exact flowing-water level");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void passiveCarrierHasNoScheduledTickAndChunkLoadRecoversOrphan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, FlashlightMod.FLASHLIGHT_LIGHT.get().defaultBlockState(), 3);

        helper.assertTrue(!level.getBlockTicks().hasScheduledTick(pos, FlashlightMod.FLASHLIGHT_LIGHT.get()),
            "A passive carrier must not schedule its own validation tick");

        recoverOrphans(level, pos);
        helper.assertTrue(level.getBlockState(pos).isAir(),
            "Chunk-load recovery must restore a persisted orphan carrier");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void orphanCleanupRestoresSourceWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, TransientLightBlock.carrier(9, new RestorableEnvironment.Water(0)), 3);

        recoverOrphans(level, pos);

        BlockState restored = level.getBlockState(pos);
        helper.assertTrue(restored.is(Blocks.WATER), "Orphan cleanup must restore source water");
        helper.assertTrue(restored.getFluidState().isSource(), "Orphan cleanup must preserve a source block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void staticBeamStopsMutatingWorldWhileDrainingEnergy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = geometryPlayer(level, helper.absoluteVec(new Vec3(8.5, 2.5, 2.5)), GameType.SURVIVAL);
        try {
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            ItemStack lamp = chargedLamp(80);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);

            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            helper.assertTrue(countTemporaryLights(helper) > 0, "First emitting tick must place carriers");
            int afterPlacement = LampEnergy.stored(lamp);
            helper.assertTrue(afterPlacement < 80, "First emitting tick must consume FE");

            ServerBeamLightingManager.resetWorldMutationCounters();
            for (int tick = 0; tick < 20; tick++) {
                FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
                ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
                reconcile(level);
            }
            helper.assertTrue(ServerBeamLightingManager.worldMutations() == 0,
                "A static cached beam must not mutate the world again, got "
                    + ServerBeamLightingManager.worldMutations());
            helper.assertTrue(LampEnergy.stored(lamp) == afterPlacement - 20,
                "Cached geometry must still drain FE every tick");
        } finally {
            FlashlightServerEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void headMountedBeamKeepsLightAwayFromTheWearer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = geometryPlayer(level, helper.absoluteVec(new Vec3(8.5, 2.5, 2.5)), GameType.SURVIVAL);
        try {
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            var head = CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head");
            helper.assertTrue(head != null, "Dev fixture must provide the head slot");
            ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
            LampData.mount(band, chargedLamp(60));
            LampData.setEnabled(band, true);
            head.getStacks().setStackInSlot(0, band);

            FlashlightServerEvents.onPlayerTick(new PlayerTickEvent.Post(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);

            BlockPos eyeCell = BlockPos.containing(player.getEyePosition());
            for (BlockPos pos : BlockPos.betweenClosed(eyeCell.offset(-1, -1, -1), eyeCell.offset(1, 1, 1))) {
                helper.assertTrue(!level.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                    "Headlamp must not place emitters in the wearer's own cells");
            }
            helper.assertTrue(countTemporaryLights(helper) > 0, "Headlamp must still light cells ahead");
        } finally {
            FlashlightServerEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            reconcile(level);
            player.discard();
        }
        helper.succeed();
    }

    private static void reconcile(ServerLevel level) {
        ServerBeamLightingManager.get().endServerTick(level.getServer());
    }

    private static void recoverOrphans(ServerLevel level, BlockPos pos) {
        ServerBeamLightingManager.get().chunkLoaded(level, level.getChunkAt(pos));
        reconcile(level);
    }

    private static ServerPlayer geometryPlayer(ServerLevel level, Vec3 position, GameType mode) {
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "beam-geometry"));
        player.setGameMode(mode);
        player.setPos(position);
        return player;
    }

    private static ItemStack chargedLamp(int energy) {
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy, false);
        return lamp;
    }

    private static void setBeamConfig(double range, double angle) {
        FlashlightConfig.BEAM_RANGE.set(range);
        FlashlightConfig.BEAM_RANGE.clearCache();
        FlashlightConfig.CONE_ANGLE_DEGREES.set(angle);
        FlashlightConfig.CONE_ANGLE_DEGREES.clearCache();
    }

    private static Map<BlockPos, Integer> computeBeam(ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 look) {
        Map<BlockPos, Integer> raw = new HashMap<>();
        ServerBeamCalculator.handheld(player, level, origin, look)
            .lights().forEach((pos, light) -> raw.put(pos, light.level()));
        return raw;
    }

    private static int countTemporaryLights(GameTestHelper helper) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 15)) {
            if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) count++;
        }
        return count;
    }

    private static double maxForwardDistance(Map<BlockPos, Integer> beam, Vec3 origin) {
        double max = 0.0;
        for (BlockPos pos : beam.keySet()) {
            max = Math.max(max, pos.getCenter().subtract(origin).z);
        }
        return max;
    }

    private static double maxLateralDistanceNearEnd(Map<BlockPos, Integer> beam, Vec3 origin) {
        double max = 0.0;
        for (Map.Entry<BlockPos, Integer> entry : beam.entrySet()) {
            Vec3 delta = entry.getKey().getCenter().subtract(origin);
            if (delta.z < 8.0) continue;
            max = Math.max(max, Math.abs(delta.x));
        }
        return max;
    }
}
