package com.ltdigor.bestflashlight;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(FlashlightMod.MOD_ID)
public final class FlashlightMod {
    public static final String MOD_ID = "bestflashlight";
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, MOD_ID);
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, MOD_ID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPES = DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD_ID);
    public static final DeferredHolder<Item, Item> FLASHLIGHT = ITEMS.register("flashlight", () -> new FlashlightItem(new Item.Properties()));
    public static final DeferredHolder<Item, Item> HEADBAND = ITEMS.register("headband", () -> new HeadbandItem(new Item.Properties()));
    public static final DeferredHolder<Block, Block> FLASHLIGHT_LIGHT = BLOCKS.register("flashlight_light", () ->
        new FlashlightLightBlock(BlockBehaviour.Properties.of().replaceable().noCollission().noOcclusion()
            .mapColor(state -> state.getValue(FlashlightLightBlock.WATERLOGGED) ? MapColor.WATER : MapColor.NONE)
            .noLootTable().pushReaction(PushReaction.BLOCK)
            .lightLevel(state -> state.getValue(FlashlightLightBlock.LEVEL))));
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<?>> HEADBAND_RECIPE = RECIPES.register(
        "headband_mount", () -> new SimpleCraftingRecipeSerializer<>(HeadbandRecipe::new));

    public FlashlightMod(IEventBus bus, ModContainer container) {
        ITEMS.register(bus);
        BLOCKS.register(bus);
        RECIPES.register(bus);
        LampData.COMPONENTS.register(bus);
        container.registerConfig(ModConfig.Type.SERVER, FlashlightConfig.SPEC);
        bus.addListener(LampEnergy::register);
        bus.addListener(CuriosCompatibility::setup);
        bus.addListener(FlashlightNetwork::register);
        bus.addListener(FlashlightMod::creativeItems);
        NeoForge.EVENT_BUS.addListener(FlashlightMod::commands);
        FlashlightEvents.register();
    }

    public static ResourceLocation resource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static boolean isFlashlight(ItemStack stack) {
        return stack != null && stack.is(FLASHLIGHT.get());
    }

    public static boolean toggleFlashlight(ItemStack stack) {
        return isFlashlight(stack) && LampEnergy.toggle(stack);
    }

    private static void creativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(FLASHLIGHT.get());
            event.accept(HEADBAND.get());
        }
    }

    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bestflashlighttoggle")
            .then(Commands.argument("target", StringArgumentType.word()).executes(context ->
                toggle(context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "target")))));
    }

    private static int toggle(ServerPlayer player, String target) {
        LampControl.Action action = target.equals("headband") ? LampControl.Action.HEADBAND : LampControl.Action.HAND_KEY;
        return target.equals(LampControl.target(player, action)) ? LampControl.press(player, action) : 0;
    }
}
