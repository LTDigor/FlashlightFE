package com.ltdigor.bestflashlight;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class BeamGameTests {
    @GameTest(template = "empty", batch = "beam_underwater")
    public static void submergedHandEmitterDoesNotDrainWithEyeInAir(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 feet = helper.absoluteVec(new Vec3(8.5, 1.48, 3.5));
        ServerPlayer player = geometryPlayer(level, feet);
        try {
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            helper.setBlock(8, 2, 4, Blocks.WATER);
            ItemStack lamp = chargedLamp(20);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
            FlashlightConfig.WORKS_UNDERWATER.set(false);
            FlashlightConfig.WORKS_UNDERWATER.clearCache();

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            helper.assertTrue(LampEnergy.stored(lamp) == 20, "Submerged hand emitter must not consume energy when the player's eye is in air");
            helper.assertTrue(countTemporaryLights(helper) == 0, "Submerged hand emitter must not create light when underwater use is disabled");
        } finally {
            FlashlightConfig.WORKS_UNDERWATER.set(true);
            FlashlightConfig.WORKS_UNDERWATER.clearCache();
            FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "beam_fluid_surface")
    public static void submergedCheckUsesActualFlowingFluidSurface(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(pos, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 4), 3);
        double surface = pos.getY() + level.getFluidState(pos).getHeight(level, pos);
        Vec3 belowSurface = new Vec3(pos.getX() + 0.5, surface - 0.01, pos.getZ() + 0.5);
        Vec3 aboveSurface = new Vec3(pos.getX() + 0.5, surface + 0.01, pos.getZ() + 0.5);

        helper.assertTrue(invokeSubmerged(level, belowSurface), "Emitter below flowing water surface must count as submerged");
        helper.assertTrue(!invokeSubmerged(level, aboveSurface), "Emitter above flowing water surface must remain dry");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "beam_off_axis")
    public static void offAxisObstacleBlocksOnlyItsOuterRingRay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin);
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
            restoreBeamConfig();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "beam_wall")
    public static void solidWallBlocksAllBeamCandidatesBehindIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin);
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
            restoreBeamConfig();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "beam_range")
    public static void configuredRangeLimitsForwardBeamDistance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin);
        try {
            setBeamConfig(4.0, 15.0);
            Map<BlockPos, Integer> shortBeam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            setBeamConfig(12.0, 15.0);
            Map<BlockPos, Integer> longBeam = computeBeam(player, level, origin, new Vec3(0.0, 0.0, 1.0));
            double shortDistance = maxForwardDistance(shortBeam, origin);
            double longDistance = maxForwardDistance(longBeam, origin);

            helper.assertTrue(shortDistance <= 4.0, "Range 4 must not create candidates beyond four blocks along the beam");
            helper.assertTrue(longDistance >= 11.0, "Range 12 must create candidates near the far end of the beam");
            helper.assertTrue(longDistance >= shortDistance + 7.0, "Increasing range from 4 to 12 must extend the candidate footprint");
        } finally {
            restoreBeamConfig();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "beam_angle")
    public static void largerFullAngleWidensLateralFootprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(8.5, 2.5, 2.5));
        ServerPlayer player = geometryPlayer(level, origin);
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
            restoreBeamConfig();
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void strongestOwnerControlsSharedLight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        UUID dimOwner = UUID.randomUUID();
        UUID brightOwner = UUID.randomUUID();

        acquire(level, pos, dimOwner, 4);
        acquire(level, pos, brightOwner, 12);
        helper.assertTrue(
            level.getBlockState(pos).getValue(FlashlightLightBlock.LEVEL) == 12,
            "Shared light must use its strongest owner's level"
        );

        release(level, pos, brightOwner);
        BlockState remaining = level.getBlockState(pos);
        helper.assertTrue(remaining.is(FlashlightMod.FLASHLIGHT_LIGHT.get()), "One owner's release must preserve another owner's light");
        helper.assertTrue(remaining.getValue(FlashlightLightBlock.LEVEL) == 4, "Shared light must fall back to the remaining owner's level");

        release(level, pos, dimOwner);
        helper.assertTrue(level.getBlockState(pos).isAir(), "Final owner release must remove a dry temporary light");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerBlockReplacementSurvivesOwnerRelease(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        acquire(level, pos, firstOwner, 8);
        acquire(level, pos, secondOwner, 12);
        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);

        release(level, pos, firstOwner);
        release(level, pos, secondOwner);

        helper.assertTrue(level.getBlockState(pos).is(Blocks.STONE), "Owner release must preserve a block that replaced temporary light");
        helper.assertTrue(!FlashlightEvents.isTrackedLight(level.dimension(), pos), "Replacement must clear stale light ownership");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void flowingWaterAndWaterPlantsRefuseTemporaryLight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos flowingPos = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos plantPos = helper.absolutePos(new BlockPos(6, 2, 4));
        level.setBlock(flowingPos, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1), 3);
        level.setBlock(plantPos.below(), Blocks.DIRT.defaultBlockState(), 3);
        level.setBlock(plantPos, Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(plantPos, Blocks.SEAGRASS.defaultBlockState(), 3);

        acquire(level, flowingPos, UUID.randomUUID(), 15);
        acquire(level, plantPos, UUID.randomUUID(), 15);

        BlockState flowing = level.getBlockState(flowingPos);
        helper.assertTrue(flowing.is(Blocks.WATER) && !flowing.getFluidState().isSource(), "Flowing water must not be replaced by temporary light");
        helper.assertTrue(level.getBlockState(plantPos).is(Blocks.SEAGRASS), "Water plants must not be replaced by temporary light");
        helper.assertTrue(!FlashlightEvents.isTrackedLight(level.dimension(), flowingPos), "Refused flowing water must not retain ownership");
        helper.assertTrue(!FlashlightEvents.isTrackedLight(level.dimension(), plantPos), "Refused water plants must not retain ownership");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sourceWaterSurvivesLightLifecycle(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        UUID owner = UUID.randomUUID();
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), 3);

        invokeLifecycle("acquireLight", level, level.dimension(), pos, owner, 15);

        BlockState light = level.getBlockState(pos);
        helper.assertTrue(light.is(FlashlightMod.FLASHLIGHT_LIGHT.get()), "Source water must accept temporary flashlight light");
        helper.assertTrue(
            light.hasProperty(BlockStateProperties.WATERLOGGED) && light.getValue(BlockStateProperties.WATERLOGGED),
            "Temporary light in source water must be waterlogged"
        );

        invokeLifecycle("releaseLight", level, level.dimension(), pos, owner);

        BlockState restored = level.getBlockState(pos);
        helper.assertTrue(restored.is(Blocks.WATER), "Releasing the final owner must restore source water");
        helper.assertTrue(restored.getFluidState().isSource(), "Restored water must remain a source block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void orphanCleanupRestoresSourceWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockState light = FlashlightMod.FLASHLIGHT_LIGHT.get().defaultBlockState();
        helper.assertTrue(light.hasProperty(BlockStateProperties.WATERLOGGED), "Temporary light must support waterlogging");
        level.setBlock(pos, light.setValue(BlockStateProperties.WATERLOGGED, true), 3);

        ((FlashlightLightBlock) FlashlightMod.FLASHLIGHT_LIGHT.get()).tick(level.getBlockState(pos), level, pos, level.random);

        BlockState restored = level.getBlockState(pos);
        helper.assertTrue(restored.is(Blocks.WATER), "Orphan cleanup must restore source water");
        helper.assertTrue(restored.getFluidState().isSource(), "Orphan cleanup must preserve a source block");
        helper.succeed();
    }

    private static void invokeLifecycle(String name, Object... arguments) {
        try {
            Method method = findLifecycleMethod(name, arguments.length);
            method.setAccessible(true);
            method.invoke(null, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not invoke FlashlightEvents." + name, exception);
        }
    }

    private static void acquire(ServerLevel level, BlockPos pos, UUID owner, int lightLevel) {
        invokeLifecycle("acquireLight", level, level.dimension(), pos, owner, lightLevel);
    }

    private static void release(ServerLevel level, BlockPos pos, UUID owner) {
        invokeLifecycle("releaseLight", level, level.dimension(), pos, owner);
    }

    @SuppressWarnings("unchecked")
    private static Map<BlockPos, Integer> computeBeam(ServerPlayer player, ServerLevel level, Vec3 origin, Vec3 look) {
        try {
            Method method = findLifecycleMethod("computeBeam", 4);
            method.setAccessible(true);
            return (Map<BlockPos, Integer>) method.invoke(null, player, level, origin, look);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not invoke FlashlightEvents.computeBeam", exception);
        }
    }

    private static ServerPlayer geometryPlayer(ServerLevel level, Vec3 origin) {
        ServerPlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "beam-geometry"));
        player.setPos(origin.x, origin.y, origin.z);
        return player;
    }

    private static ItemStack chargedLamp(int energy) {
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy, false);
        return lamp;
    }

    private static boolean invokeSubmerged(ServerLevel level, Vec3 origin) {
        try {
            Method method = findLifecycleMethod("isSubmerged", 2);
            method.setAccessible(true);
            return (boolean) method.invoke(null, level, origin);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not invoke FlashlightEvents.isSubmerged", exception);
        }
    }

    private static int countTemporaryLights(GameTestHelper helper) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 15)) {
            if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
                count++;
            }
        }
        return count;
    }

    private static double maxForwardDistance(Map<BlockPos, Integer> beam, Vec3 origin) {
        return beam.keySet().stream().mapToDouble(pos -> pos.getZ() + 0.5 - origin.z).max().orElse(Double.NEGATIVE_INFINITY);
    }

    private static double maxLateralDistanceNearEnd(Map<BlockPos, Integer> beam, Vec3 origin) {
        return beam.keySet().stream()
            .filter(pos -> pos.getZ() + 0.5 - origin.z >= 9.0)
            .mapToDouble(pos -> Math.abs(pos.getX() + 0.5 - origin.x))
            .max()
            .orElse(0.0);
    }

    private static void setBeamConfig(double range, double fullAngleDegrees) {
        FlashlightConfig.BEAM_RANGE.set(range);
        FlashlightConfig.BEAM_RANGE.clearCache();
        FlashlightConfig.CONE_ANGLE_DEGREES.set(fullAngleDegrees);
        FlashlightConfig.CONE_ANGLE_DEGREES.clearCache();
    }

    private static void restoreBeamConfig() {
        setBeamConfig(12.0, 15.0);
    }

    private static Method findLifecycleMethod(String name, int parameterCount) throws NoSuchMethodException {
        for (Method method : FlashlightEvents.class.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }
}
