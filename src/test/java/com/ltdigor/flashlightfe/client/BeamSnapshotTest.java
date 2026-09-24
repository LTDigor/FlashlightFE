package com.ltdigor.flashlightfe.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeamSnapshotTest {
    private static final double ANGLE = Math.toRadians(7.5);

    @Test void downwardFloorAndAirSamplesRemainLitAcrossGrid() {
        for (double x : new double[]{-1, -0.75, 0, 0.25, 0.5}) {
            BeamSnapshot snapshot = BeamSnapshot.build(new Vec3(x, 2.62, 0),
                new Vec3(0, -1, 0), 12, ANGLE, pos -> pos.getY() >= 0);
            assertTrue(snapshot.lightAt(BlockPos.containing(x, 1, 0)) > 0,
                "Air next to the floor is the sample LDL actually renders");
            assertTrue(snapshot.lightAt(BlockPos.containing(x, 0, 0)) > 0);
            assertEquals(0, snapshot.lightAt(BlockPos.containing(x, -1, 0)));
        }
    }

    @Test void obstacleCutsOnlyItsOwnTargetsAndOldSnapshotIsUntouched() {
        Vec3 origin = new Vec3(0.5, 1.5, 0.5);
        Vec3 axis = new Vec3(0, 0, 1);
        BeamSnapshot before = BeamSnapshot.build(origin, axis, 12, ANGLE, pos -> true);
        BlockPos blocked = new BlockPos(0, 1, 4);
        BlockPos open = new BlockPos(1, 1, 10);
        BeamSnapshot after = BeamSnapshot.build(origin, axis, 12, ANGLE, pos -> {
            assertTrue(before.lightAt(blocked) > 0, "Old snapshot remains available during build");
            return !pos.equals(blocked);
        });
        assertTrue(before.lightAt(blocked) > 0);
        assertEquals(0, after.lightAt(blocked));
        assertTrue(after.lightAt(open) > 0);
        assertEquals(before.lightAt(open), after.lightAt(open));
    }

    @Test void noVisibilityWorkOutsideConeAndReadsDoNotCallVisibility() {
        int[] calls = {0};
        BeamSnapshot snapshot = BeamSnapshot.build(Vec3.ZERO, new Vec3(0, 0, 1), 12, ANGLE, pos -> {
            calls[0]++;
            assertTrue(pos.getZ() >= 0 && pos.getZ() < 12);
            return true;
        });
        assertTrue(calls[0] > 0);
        int built = calls[0];
        for (int i = 0; i < 100; i++) snapshot.lightAt(new BlockPos(0, 0, 1));
        assertEquals(built, calls[0]);
        assertEquals(0, snapshot.lightAt(new BlockPos(0, 0, -1)));
        assertEquals(0, snapshot.lightAt(new BlockPos(0, 0, 12)));
    }

    @Test void boundsContainEveryPositiveSampleForObliqueAndVerticalCones() {
        for (Vec3 direction : new Vec3[]{new Vec3(0, -1, 0), new Vec3(1, 2, 3).normalize()}) {
            Vec3 origin = new Vec3(-0.2, 2.62, -0.3);
            BeamSnapshot snapshot = BeamSnapshot.build(origin, direction, 12, ANGLE, pos -> true);
            int[] bounds = snapshot.bounds();
            for (int x = -14; x <= 14; x++) for (int y = -14; y <= 16; y++) for (int z = -14; z <= 14; z++) {
                BlockPos pos = new BlockPos(x, y, z);
                double expected = FlashlightBeamMath.coneLuminance(origin, direction, Vec3.atCenterOf(pos), 12, ANGLE);
                assertEquals(expected, snapshot.lightAt(pos), 1e-8, pos.toString());
                if (expected > 0) assertTrue(x >= bounds[0] && x <= bounds[3]
                    && y >= bounds[1] && y <= bounds[4] && z >= bounds[2] && z <= bounds[5]);
            }
        }
    }
}
