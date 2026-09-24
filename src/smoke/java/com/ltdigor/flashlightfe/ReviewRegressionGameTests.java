package com.ltdigor.flashlightfe;

import com.ltdigor.flashlightfe.lighting.RestorableEnvironment;
import com.ltdigor.flashlightfe.lighting.ServerBeamLightingManager;
import com.ltdigor.flashlightfe.lighting.TransientLightBlock;
import com.mojang.authlib.GameProfile;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import top.theillusivec4.curios.api.CuriosApi;

/** Regression tests operate on actual levels and elapsed game ticks, not a tight tick-call loop. */
@GameTestHolder("bestflashlight")
@PrefixGameTestTemplate(false)
public class ReviewRegressionGameTests {
    @GameTest(template = "empty", batch = "review_recovery")
    public static void removedCarrierReappearsWithoutPlayerMotion(GameTestHelper helper) {
        BeamFixture fixture = new BeamFixture(helper);
        BlockPos pos = fixture.carrier();
        helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        helper.onEachTick(fixture::tick);
        helper.runAfterDelay(12, () -> {
            try {
                helper.assertTrue(helper.getLevel().getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
                    "Externally removed carrier must be restored even when desired beam geometry is unchanged");
                helper.succeed();
            } finally { fixture.close(); }
        });
    }

    @GameTest(template = "empty", batch = "review_recovery")
    public static void externallyChangedBrightnessIsReconciled(GameTestHelper helper) {
        BeamFixture fixture = new BeamFixture(helper);
        BlockPos pos = fixture.carrier();
        BlockState original = helper.getLevel().getBlockState(pos);
        int expected = original.getValue(TransientLightBlock.LEVEL);
        helper.getLevel().setBlock(pos, original.setValue(TransientLightBlock.LEVEL, 1), 2);
        helper.onEachTick(fixture::tick);
        helper.runAfterDelay(12, () -> {
            try {
                BlockState actual = helper.getLevel().getBlockState(pos);
                helper.assertTrue(actual.is(FlashlightMod.FLASHLIGHT_LIGHT.get())
                        && actual.getValue(TransientLightBlock.LEVEL) == expected,
                    "Applied-state cache must not hide an external brightness change");
                helper.succeed();
            } finally { fixture.close(); }
        });
    }

    @GameTest(template = "empty", batch = "review_recovery")
    public static void reconciliationNeverOverwritesForeignBlock(GameTestHelper helper) {
        BeamFixture fixture = new BeamFixture(helper);
        BlockPos pos = fixture.carrier();
        helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        helper.onEachTick(fixture::tick);
        helper.runAfterDelay(12, () -> {
            try {
                helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.STONE),
                    "Reconciliation must preserve a block placed by another system");
                fixture.close();
                helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.STONE),
                    "Beam cleanup must also preserve the foreign block");
                helper.succeed();
            } finally { fixture.close(); }
        });
    }

    @GameTest(template = "empty", batch = "review_static")
    public static void staticBeamRemainsWriteFreeAcrossRealCacheRefreshes(GameTestHelper helper) {
        BeamFixture fixture = new BeamFixture(helper);
        long started = helper.getLevel().getGameTime();
        ServerBeamLightingManager.resetWorldMutationCounters();
        helper.onEachTick(fixture::tick);
        helper.runAfterDelay(20, () -> {
            try {
                helper.assertTrue(helper.getLevel().getGameTime() >= started + 20,
                    "This test must actually advance world time");
                helper.assertTrue(ServerBeamLightingManager.worldMutations() == 0,
                    "Validation and expired geometry caches must not rewrite unchanged blocks");
                helper.succeed();
            } finally { fixture.close(); }
        });
    }

    @GameTest(template = "empty", batch = "review_shutdown")
    public static void stoppingHandlerRestoresBeforeBookkeepingIsDiscarded(GameTestHelper helper) throws Exception {
        BeamFixture fixture = new BeamFixture(helper);
        BlockPos pos = fixture.carrier();
        try {
            // Invoke only this mod's stopping callback, not all mods' shutdown hooks on a live test server.
            for (Method method : FlashlightServerEvents.class.getDeclaredMethods()) {
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ServerStoppingEvent.class) {
                    method.setAccessible(true);
                    method.invoke(null, new ServerStoppingEvent(helper.getLevel().getServer()));
                }
            }
            helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(),
                "Normal shutdown must restore carriers during ServerStoppingEvent, before level unload/save");
            helper.assertTrue(!ServerBeamLightingManager.get().hasPlayerFrame(fixture.player.getUUID()),
                "Stopping must also discard the player's frame");
            helper.succeed();
        } finally { fixture.close(); }
    }

    @GameTest(template = "empty", batch = "review_curios")
    public static void missingCuriosHeadSlotUsesVanillaHeadForEquipAndSelection(GameTestHelper helper) {
        ServerPlayer player = fakePlayer(helper, "missing-head");
        try {
            CuriosApi.getCuriosInventory(player).orElseThrow().setCurios(new HashMap<>());
            ItemStack band = loadedBand();
            player.setItemSlot(EquipmentSlot.MAINHAND, band);
            var result = band.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(result.getResult().consumesAction(),
                "Installed Curios without a head slot must not disable vanilla head equipment");
            ItemStack worn = player.getItemBySlot(EquipmentSlot.HEAD);
            helper.assertTrue(worn.getItem() instanceof HeadbandItem, "Headband must equip in vanilla HEAD");
            LampData.setEnabled(worn, true);
            helper.assertTrue(LampSource.select(player) != null && LampSource.select(player).stack() == worn,
                "Beam source selection must use the same fallback as equipping");
            helper.assertTrue(LampSource.headband(player, 0) == worn,
                "Owner synchronization must resolve the same vanilla headband");
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "review_curios")
    public static void occupiedCuriosHeadDoesNotUnexpectedlyReplaceHelmet(GameTestHelper helper) {
        ServerPlayer player = fakePlayer(helper, "occupied-head");
        try {
            var head = CuriosApi.getCuriosInventory(player).orElseThrow().getCurios().get("head");
            head.getStacks().setStackInSlot(0, loadedBand());
            player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            ItemStack held = loadedBand();
            player.setItemSlot(EquipmentSlot.MAINHAND, held);
            var result = held.getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
            helper.assertTrue(!result.getResult().consumesAction(), "An occupied functional head slot must remain occupied");
            helper.assertTrue(player.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET),
                "Do not fall back to replacing a helmet merely because an existing Curios slot is full");
            helper.assertTrue(player.getMainHandItem() == held, "Failed equip must not consume the held headband");
        } finally { player.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "review_piston")
    public static void pistonTreatsDryCarrierLikeAir(GameTestHelper helper) {
        pistonComparison(helper, new RestorableEnvironment.Air());
    }

    @GameTest(template = "empty", batch = "review_piston")
    public static void pistonTreatsWetCarrierLikeSourceWater(GameTestHelper helper) {
        pistonComparison(helper, new RestorableEnvironment.Water(0));
    }

    @GameTest(template = "empty", batch = "review_piston")
    public static void pistonTreatsFlowingCarrierLikeFlowingWater(GameTestHelper helper) {
        pistonComparison(helper, new RestorableEnvironment.Water(4));
    }

    private static void pistonComparison(GameTestHelper helper, RestorableEnvironment environment) {
        // Symmetric independent lanes: vanilla environment at z=4, carrier at z=10.
        for (int z : new int[]{4, 10}) {
            for (int x = 2; x <= 8; x++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
                helper.setBlock(x, 2, z - 1, Blocks.STONE);
                helper.setBlock(x, 2, z + 1, Blocks.STONE);
            }
            helper.setBlock(8, 2, z, Blocks.STONE);
            helper.setBlock(4, 2, z, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
            helper.setBlock(5, 2, z, z == 4 ? environment.restored() : TransientLightBlock.carrier(10, environment));
            helper.setBlock(3, 2, z, Blocks.REDSTONE_BLOCK);
        }
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(helper.getBlockState(new BlockPos(4, 2, 10)).getValue(PistonBaseBlock.EXTENDED),
                "Invisible light must not block piston extension");
            for (int x = 4; x <= 7; x++) {
                helper.assertTrue(helper.getBlockState(new BlockPos(x, 2, 4))
                        .equals(helper.getBlockState(new BlockPos(x, 2, 10))),
                    "Piston result must match the vanilla environment; no carrier may be transported");
            }
            helper.setBlock(3, 2, 4, Blocks.AIR);
            helper.setBlock(3, 2, 10, Blocks.AIR);
            helper.runAfterDelay(12, () -> {
                helper.assertTrue(!helper.getBlockState(new BlockPos(4, 2, 10)).getValue(PistonBaseBlock.EXTENDED),
                    "Piston must retract normally after destroying the transient carrier");
                for (int x = 4; x <= 7; x++) {
                    helper.assertTrue(helper.getBlockState(new BlockPos(x, 2, 4))
                            .equals(helper.getBlockState(new BlockPos(x, 2, 10))),
                        "Retracted lane must match vanilla, including water and lack of orphan lights");
                }
                helper.succeed();
            });
        });
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper, String name) {
        ServerPlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.setPos(helper.absoluteVec(new Vec3(8.5, 1, 2.5)));
        player.setYRot(0);
        player.setXRot(0);
        return player;
    }

    private static ItemStack loadedBand() {
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.set(LampData.ENERGY.get(), 1000);
        ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(band, lamp);
        return band;
    }

    private static final class BeamFixture {
        private final GameTestHelper helper;
        private final ServerPlayer player;
        private boolean closed;

        private BeamFixture(GameTestHelper helper) {
            this.helper = helper;
            player = fakePlayer(helper, "review-beam");
            player.setGameMode(GameType.CREATIVE);
            ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
            LampData.setEnabled(lamp, true);
            player.setItemSlot(EquipmentSlot.MAINHAND, lamp);
            tick();
        }

        private void tick() {
            if (closed) return;
            ServerBeamLightingManager.get().updatePlayer(player);
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
        }

        private BlockPos carrier() {
            var state = ServerBeamLightingManager.get().playerFrame(player.getUUID());
            helper.assertTrue(state != null && !state.frame().isEmpty(), "Fixture must establish a real fallback beam");
            return state.frame().lights().keySet().iterator().next();
        }

        private void close() {
            if (closed) return;
            closed = true;
            ServerBeamLightingManager.get().removePlayer(player);
            ServerBeamLightingManager.get().endServerTick(helper.getLevel().getServer());
            player.discard();
        }
    }
}
