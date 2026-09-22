package com.ltdigor.bestflashlight;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class WallAndHeadlampRegressionGameTests {
    @GameTest(template = "empty", batch = "beam_near_wall")
    public static void handheldDoesNotSwitchOffWhenEmitterOverlapsNearbyWall(GameTestHelper helper) {
        useLegacyEnergyRate();
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "wall-handheld"));
        try {
            player.setPos(helper.absoluteVec(new Vec3(8.5, 1.48, 3.8)));
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            for (int x = 7; x <= 9; x++) {
                for (int y = 2; y <= 4; y++) helper.setBlock(x, y, 4, Blocks.STONE);
            }

            ItemStack lamp = chargedLamp(20);
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            helper.assertTrue(LampEnergy.stored(lamp) == 19,
                "A nearby wall must clip the beam, not switch the handheld flashlight off");
            helper.assertTrue(countTemporaryLights(helper) > 0,
                "A wall-overlapping handheld emitter must keep at least one temporary light source");
        } finally {
            FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "beam_headlamp_direction")
    public static void headbandPlacesBrightSourcesOnlyAtForwardBeamTerminals(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "headlamp-direction"));
        try {
            player.setPos(helper.absoluteVec(new Vec3(8.5, 1.0, 3.5)));
            player.setYRot(0.0F);
            player.setXRot(0.0F);

            ItemStack lamp = chargedLamp(50);
            LampData.setEnabled(lamp, true);
            ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
            LampData.mount(band, lamp);

            var inventory = CuriosApi.getCuriosInventory(player).orElseThrow();
            var head = inventory.getCurios().get("head");
            helper.assertTrue(head != null && head.getStacks().getSlots() > 0,
                "Player must have a Curios head slot for the headband");
            helper.assertTrue(head.getStacks().insertItem(0, band, false).isEmpty(),
                "Loaded headband must equip into the Curios head slot");

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle().normalize();
            BlockPos eyeCell = BlockPos.containing(eye);
            helper.assertTrue(!level.getBlockState(eyeCell).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                "Headlamp must not place an isotropic source in the player's head cell");
            helper.assertTrue(countTemporaryLights(helper) > 0,
                "Headlamp must still create forward beam sources");
            helper.assertTrue(minForwardDistance(helper, eye, look) >= 9.0,
                "In open space headlamp emitters must stay near the far end of the forward cone");
            helper.assertTrue(maxTemporaryLightLevel(helper) == 15,
                "Far forward headlamp terminals must remain bright enough to illuminate the target");
        } finally {
            FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "beam_near_wall")
    public static void headbandKeepsDimFallbackWhenLookingIntoNearbyWall(GameTestHelper helper) {
        useLegacyEnergyRate();
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "wall-headlamp"));
        try {
            player.setPos(helper.absoluteVec(new Vec3(8.5, 1.0, 3.8)));
            player.setYRot(0.0F);
            player.setXRot(0.0F);
            for (int x = 7; x <= 9; x++) {
                for (int y = 1; y <= 4; y++) helper.setBlock(x, y, 4, Blocks.STONE);
            }

            ItemStack lamp = chargedLamp(20);
            LampData.setEnabled(lamp, true);
            ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
            LampData.mount(band, lamp);

            var inventory = CuriosApi.getCuriosInventory(player).orElseThrow();
            var head = inventory.getCurios().get("head");
            helper.assertTrue(head != null && head.getStacks().insertItem(0, band, false).isEmpty(),
                "Loaded headband must equip into the Curios head slot");
            ItemStack equipped = head.getStacks().getStackInSlot(0);

            FlashlightEvents.onPlayerTick(new PlayerTickEvent.Post(player));

            helper.assertTrue(LampEnergy.stored(equipped) == 19,
                "Looking into a nearby wall must keep the headlamp running");
            helper.assertTrue(countTemporaryLights(helper) > 0,
                "Headlamp must retain a dim fallback source when the wall is inside its near-field cutoff");
            helper.assertTrue(maxTemporaryLightLevel(helper) <= 4,
                "Close-wall fallback must stay dim enough not to recreate a 360-degree halo");
        } finally {
            FlashlightEvents.onPlayerLoggedOut(new PlayerEvent.PlayerLoggedOutEvent(player));
            player.discard();
        }
        helper.succeed();
    }

    private static ItemStack chargedLamp(int energy) {
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy, false);
        return lamp;
    }

    private static void useLegacyEnergyRate() {
        FlashlightConfig.ENERGY_PER_TICK.set(1.0);
        FlashlightConfig.ENERGY_PER_TICK.clearCache();
    }

    private static int countTemporaryLights(GameTestHelper helper) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 15)) {
            if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) count++;
        }
        return count;
    }

    private static int maxTemporaryLightLevel(GameTestHelper helper) {
        int max = 0;
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 15)) {
            var state = helper.getBlockState(pos);
            if (state.is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
                max = Math.max(max, state.getValue(FlashlightLightBlock.LEVEL));
            }
        }
        return max;
    }

    private static double minForwardDistance(GameTestHelper helper, Vec3 origin, Vec3 axis) {
        double min = Double.POSITIVE_INFINITY;
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 7, 15)) {
            if (helper.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get())) {
                Vec3 center = helper.absolutePos(pos).getCenter();
                min = Math.min(min, center.subtract(origin).dot(axis));
            }
        }
        return min;
    }
}
