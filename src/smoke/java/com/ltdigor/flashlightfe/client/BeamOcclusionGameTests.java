package com.ltdigor.flashlightfe.client;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class BeamOcclusionGameTests {
    @GameTest(template = "empty", batch = "exact_beam_visibility")
    public static void wallReceivesLightButAirBehindItDoesNot(GameTestHelper helper) {
        helper.setBlock(3, 2, 5, Blocks.STONE);
        Vec3 origin = helper.absoluteVec(new Vec3(3.5, 2.5, 1.5));
        var visibility = new BeamVisibility(helper.getLevel(), net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel()), origin, pos -> true);
        helper.assertTrue(visibility.test(helper.absolutePos(new BlockPos(3, 2, 4))), "Air before wall must be visible");
        helper.assertTrue(visibility.test(helper.absolutePos(new BlockPos(3, 2, 5))), "Hit wall itself must be visible");
        helper.assertTrue(!visibility.test(helper.absolutePos(new BlockPos(3, 2, 6))), "Air behind wall must be dark");
        helper.assertTrue(visibility.test(helper.absolutePos(new BlockPos(5, 2, 6))), "Neighbor ray must not inherit wall occlusion");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "exact_beam_visibility")
    public static void slabShapeDoesNotOccludeAirAboveIt(GameTestHelper helper) {
        helper.setBlock(3, 2, 5, Blocks.STONE_SLAB);
        var high = new BeamVisibility(helper.getLevel(), net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel()),
            helper.absoluteVec(new Vec3(3.5, 2.99, 1.5)), pos -> true);
        helper.assertTrue(high.test(helper.absolutePos(new BlockPos(3, 2, 6))), "Ray over bottom slab must remain visible");
        var low = new BeamVisibility(helper.getLevel(), net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel()),
            helper.absoluteVec(new Vec3(3.5, 2.01, 1.5)), pos -> true);
        helper.assertTrue(!low.test(helper.absolutePos(new BlockPos(3, 2, 6))), "Ray through bottom slab must be blocked");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "exact_beam_visibility")
    public static void unavailablePathIsOpaqueAndNextBuildSeesRemovedObstacle(GameTestHelper helper) {
        Vec3 origin = helper.absoluteVec(new Vec3(3.5, 2.5, 1.5));
        BlockPos target = helper.absolutePos(new BlockPos(3, 2, 6));
        BlockPos obstacle = helper.absolutePos(new BlockPos(3, 2, 4));
        var missing = new BeamVisibility(helper.getLevel(), net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel()), origin, pos -> !pos.equals(obstacle));
        helper.assertTrue(!missing.test(target), "Unavailable middle cell must not transmit light");
        helper.setBlock(3, 2, 4, Blocks.STONE);
        helper.assertTrue(!new BeamVisibility(helper.getLevel(), net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel()), origin, pos -> true).test(target), "Obstacle blocks light");
        helper.setBlock(3, 2, 4, Blocks.AIR);
        helper.assertTrue(new BeamVisibility(helper.getLevel(), net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel()), origin, pos -> true).test(target), "Fresh build sees removed obstacle");
        helper.succeed();
    }
}
