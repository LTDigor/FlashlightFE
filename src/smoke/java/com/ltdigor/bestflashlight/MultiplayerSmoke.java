package com.ltdigor.bestflashlight;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

/** Two real network peers on a loopback-only dedicated development server. Never packaged. */
@EventBusSubscriber(modid="bestflashlight")
public final class MultiplayerSmoke {
    private static int ticks;
    private static ServerPlayer first, second;
    private static ItemStack removed;
    private static int removedEnergy;
    private static Set<BlockPos> waterLights, netherLights;
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("bestflashlight.multiplayer.server")) return;
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        if (first == null) {
            first = server.getPlayerList().getPlayerByName("FlashlightA");
            second = server.getPlayerList().getPlayerByName("FlashlightB");
            if (first == null || second == null) { first = null; return; }
            for (int x=-4;x<=4;x++) for (int z=-3;z<=18;z++) for (int y=0;y<=6;y++)
                level.setBlock(new BlockPos(x,y,z), (x==-4||x==4||z==-3||z==18||y==0||y==6 ? Blocks.STONE : Blocks.WATER).defaultBlockState(),3);
            first.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            second.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            for (var p : List.of(first,second)) {
                p.teleportTo(level,.5,1,.5,Set.of(),0,0);
                p.setNoGravity(true);
                p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WATER_BREATHING,12000));
                equip(p);
            }
            equipHand(first, 0);
            equipHand(second, 100);
            LogUtils.getLogger().info("MULTIPLAYER_READY: simultaneous creative and survival network players, submerged forehead lamps");
            return;
        }
        ticks++;
        if (ticks==20) {
            require(LampControl.press(first, LampControl.Action.HAND_KEY)==1, "Creative zero-charge handheld press rejected");
            require(LampControl.press(second, LampControl.Action.HAND_KEY)==1, "Survival partial-charge handheld press rejected");
            require(LampData.enabled(first.getMainHandItem()) && LampEnergy.stored(first.getMainHandItem())==0,
                "Creative handheld press must enable zero-charge lamp without changing FE");
            require(LampData.enabled(second.getMainHandItem()) && LampEnergy.stored(second.getMainHandItem())>0,
                "Survival handheld press must enable partial-charge lamp");
        }
        if (ticks==40) {
            var owners=owners(level);
            require(owners.values().stream().anyMatch(v -> v.size()==2), "Real players did not share light ownership");
            waterLights=new HashSet<>(owners.keySet());
            require(LampEnergy.stored(first.getMainHandItem())==0,"Creative remote player's handheld changed FE");
            require(LampEnergy.stored(second.getMainHandItem())<100,"Survival remote player's handheld did not drain");
            require(LampEnergy.stored(LampSource.headband(first))==10000,"Creative remote player's headband changed FE");
            LampData.setEnabled(first.getMainHandItem(), false);
            LampData.setEnabled(second.getMainHandItem(), false);
            removed=LampSource.headband(first); removedEnergy=LampEnergy.stored(removed);
            slot(first).setStackInSlot(0,ItemStack.EMPTY);
        }
        if (ticks==60) {
            require(!owners(level).isEmpty() && owners(level).values().stream().allMatch(v -> v.size()==1), "Unequip removed another player's beam or left stale owner");
            require(LampEnergy.stored(removed)==removedEnergy,"Unequipped remote headband consumed energy");
            slot(first).setStackInSlot(0,removed);
        }
        if (ticks==80) {
            require(owners(level).values().stream().anyMatch(v -> v.size()==2), "Re-equipped headband did not restore shared beam");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"kill FlashlightA");
        }
        if (ticks==100) {
            require(!first.isAlive(),"Death fixture failed");
            require(owners(level).values().stream().allMatch(v -> v.size()==1),"Death retained a light owner");
            ServerLevel nether = server.getLevel(Level.NETHER);
            for (int x = -3; x <= 3; x++) for (int y = 99; y <= 105; y++) for (int z = -2; z <= 15; z++)
                nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            nether.setBlock(new BlockPos(0, 98, 0), Blocks.STONE.defaultBlockState(), 3);
            second.teleportTo(nether,.5,99,.5,Set.of(),0,0);
        }
        if (ticks==120) {
            require(owners(level).isEmpty(),"Dimension change left overworld light owners");
            for (var pos : waterLights) require(level.getBlockState(pos).is(Blocks.WATER) && level.getFluidState(pos).isSource(),"Water lost after two players left: "+pos);
            require(!owners(second.serverLevel()).isEmpty(),"Lamp did not resume in destination dimension");
            netherLights=new HashSet<>(owners(second.serverLevel()).keySet());
            second.connection.disconnect(Component.literal("Flashlight multiplayer logout probe"));
        }
        if (ticks==150) {
            var nether=server.getLevel(Level.NETHER);
            require(owners(nether).isEmpty(),"Logout left nether owners");
            for (var pos : netherLights) require(!nether.getBlockState(pos).is(FlashlightMod.FLASHLIGHT_LIGHT.get()),"Logout left temporary light");
            LogUtils.getLogger().info("FLASHLIGHT_MULTIPLAYER_SMOKE_PASS: dedicated server, accepted handheld press events, simultaneous creative/survival FE, shared underwater beams, unequip, death, dimension change, logout, water restored");
            server.halt(false);
        }
    }
    private static top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler slot(ServerPlayer p) {
        return CuriosApi.getCuriosInventory(p).orElseThrow().getCurios().get("head").getStacks();
    }
    private static void equip(ServerPlayer p) {
        ItemStack lamp=new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(10000,false);
        ItemStack band=new ItemStack(FlashlightMod.HEADBAND.get());
        LampData.mount(band,lamp); LampData.setEnabled(band,true); slot(p).setStackInSlot(0,band);
    }
    private static void equipHand(ServerPlayer p, int energy) {
        ItemStack lamp=new ItemStack(FlashlightMod.FLASHLIGHT.get());
        lamp.getCapability(Capabilities.EnergyStorage.ITEM).receiveEnergy(energy,false);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,lamp);
    }
    @SuppressWarnings("unchecked") private static Map<BlockPos,Map<UUID,Integer>> owners(ServerLevel level) {
        try {
            Field field=FlashlightEvents.class.getDeclaredField("LIGHT_OWNERS"); field.setAccessible(true);
            var all=(Map<Object,Map<BlockPos,Map<UUID,Integer>>>)field.get(null);
            return all.getOrDefault(level.dimension(),Map.of());
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
