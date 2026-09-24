package com.ltdigor.flashlightfe.runtimecheck;

import com.ltdigor.flashlightfe.CuriosCompatibility;
import com.ltdigor.flashlightfe.FlashlightMod;
import com.ltdigor.flashlightfe.LampData;
import com.ltdigor.flashlightfe.LampEnergy;
import com.ltdigor.flashlightfe.LampSource;
import com.ltdigor.flashlightfe.lighting.ServerBeamLightingManager;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Development-only mod, packaged separately. Runs against the production JAR in a real server. */
@Mod("bestflashlight_runtime_check")
public final class FlashlightRuntimeCheck {
    private ServerPlayer wearer;
    private BlockPos probe;
    private int ticks;
    private boolean stoppingChecked;
    private final boolean expectedCurios = Boolean.getBoolean("bestflashlight.runtimeCheck.expectedCurios");

    public FlashlightRuntimeCheck() {
        NeoForge.EVENT_BUS.addListener(this::started);
        NeoForge.EVENT_BUS.addListener(this::playerTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::tick);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::stopping);
        NeoForge.EVENT_BUS.addListener(this::stopped);
    }

    private void started(ServerStartedEvent event) {
        require(CuriosCompatibility.isLoaded() == expectedCurios, "Wrong optional-dependency test environment");
        var level = event.getServer().overworld();
        wearer = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "runtime-check"));
        wearer.setPos(0.5, 80, 0.5);
        wearer.setYRot(0);
        wearer.setXRot(0);
        // Loading is intentional in the fixture, never in production light validation.
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.getChunk(x, z);
        require(!CuriosCompatibility.hasHeadSlot(wearer), "Production JAR must not secretly add a head slot");
        ItemStack lamp = new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.set(LampData.ENERGY.get(), 100);
        ItemStack band = new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(band, lamp);
        wearer.setItemSlot(EquipmentSlot.MAINHAND, band);
        require(band.getItem().use(level, wearer, InteractionHand.MAIN_HAND).getResult().consumesAction(),
            "Headband cannot equip without a Curios head slot");
        ItemStack worn = wearer.getItemBySlot(EquipmentSlot.HEAD);
        require(worn.getItem() == FlashlightMod.HEADBAND.get(), "Headband did not equip in vanilla HEAD");
        LampData.setEnabled(worn, true);
        require(LampSource.headband(wearer, 0) == worn, "Owner packet resolution disagrees with vanilla fallback");
        LogUtils.getLogger().info("RUNTIME_CHECK_STARTED curios={}", expectedCurios);
    }

    private void playerTick(ServerTickEvent.Pre event) {
        if (wearer == null) return;
        // The fixture's FakePlayer is not in the server's entity tick list. Emit its
        // standard player event, rather than bypassing the production event wiring.
        NeoForge.EVENT_BUS.post(new PlayerTickEvent.Post(wearer));
    }

    private void tick(ServerTickEvent.Post event) {
        if (wearer == null) return;
        // LOWEST observes the real server Post event after the production manager
        // listener has reconciled the world; no direct update/reconcile calls here.
        var state = ServerBeamLightingManager.get().playerFrame(wearer.getUUID());
        require(state != null && !state.frame().isEmpty(), "Equipped headband produced no fallback beam");
        probe = state.frame().lights().keySet().iterator().next();
        require(wearer.serverLevel().getBlockState(probe).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
            "Beam frame was not applied to the real world");
        if (++ticks == 80) {
            require(LampEnergy.stored(wearer.getItemBySlot(EquipmentSlot.HEAD)) < 100,
                "Powered fallback headband did not consume FE across real ticks");
            event.getServer().halt(false);
        }
    }

    private void stopping(ServerStoppingEvent event) {
        require(probe != null, "Server stopped before runtime checks ran");
        require(!wearer.serverLevel().getBlockState(probe).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),
            "Production stopping listener failed to restore light before world save");
        require(!ServerBeamLightingManager.get().hasPlayerFrame(wearer.getUUID()),
            "Production stopping listener retained the emitting frame");
        stoppingChecked = true;
        LogUtils.getLogger().info("RUNTIME_CHECK_STOPPING_OK curios={}", expectedCurios);
    }

    private void stopped(ServerStoppedEvent event) {
        require(stoppingChecked, "Shutdown checks never completed");
        LogUtils.getLogger().info("RUNTIME_CHECK_PASS curios={}", expectedCurios);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("RUNTIME_CHECK_FAILURE: " + message);
    }
}
