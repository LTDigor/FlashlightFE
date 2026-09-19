package com.ltdigor.bestflashlight;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Only server-authorized presses become client animation events. */
public final class FlashlightNetwork {
    private FlashlightNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToServer(Toggle.TYPE, Toggle.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) LampControl.press(player, payload.action());
        });
        registrar.playToClient(Press.TYPE, Press.CODEC, (payload, context) ->
            NeoForge.EVENT_BUS.post(new PressEvent(payload.owner(), payload.hand(), payload.previousEnabled(), payload.enabled())));

        // Optional to keep 1.0.2 peers compatible. Missing support means the server
        // simply keeps its normal temporary block-light fallback.
        var optional = registrar.optional();
        optional.playToServer(DynamicSupport.TYPE, DynamicSupport.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) DynamicLightCoordination.report(player, payload.active());
        });
        optional.playToServer(DynamicReady.TYPE, DynamicReady.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) DynamicLightCoordination.ready(player);
        });
        optional.playToClient(FallbackMode.TYPE, FallbackMode.CODEC, (payload, context) ->
            NeoForge.EVENT_BUS.post(new FallbackModeEvent(payload.enabled())));
        optional.playToClient(HeadbandEnergy.TYPE, HeadbandEnergy.CODEC, (payload, context) -> {
            ItemStack band = LampSource.headband(context.player(), payload.slotIndex());
            if (!band.isEmpty()) LampEnergy.setSyncedStored(band, payload.energy());
        });
        optional.playToClient(HandheldEnergy.TYPE, HandheldEnergy.CODEC, (payload, context) -> {
            ItemStack stack = context.player().getInventory().getItem(payload.inventorySlot());
            if (FlashlightMod.isFlashlight(stack)) LampEnergy.setSyncedStored(stack, payload.energy());
        });
    }

    public record Toggle(LampControl.Action action) implements CustomPacketPayload {
        public static final Type<Toggle> TYPE = new Type<>(FlashlightMod.resource("toggle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Toggle> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeEnum(value.action()), buffer -> new Toggle(buffer.readEnum(LampControl.Action.class)));
        @Override public Type<Toggle> type() { return TYPE; }
    }

    public record DynamicSupport(boolean active) implements CustomPacketPayload {
        public static final Type<DynamicSupport> TYPE = new Type<>(FlashlightMod.resource("dynamic_support"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DynamicSupport> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeBoolean(value.active()),
            buffer -> new DynamicSupport(buffer.readBoolean()));
        @Override public Type<DynamicSupport> type() { return TYPE; }
    }

    public record DynamicReady() implements CustomPacketPayload {
        public static final Type<DynamicReady> TYPE = new Type<>(FlashlightMod.resource("dynamic_ready"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DynamicReady> CODEC = StreamCodec.unit(new DynamicReady());
        @Override public Type<DynamicReady> type() { return TYPE; }
    }

    public record FallbackMode(boolean enabled) implements CustomPacketPayload {
        public static final Type<FallbackMode> TYPE = new Type<>(FlashlightMod.resource("fallback_mode"));
        public static final StreamCodec<RegistryFriendlyByteBuf, FallbackMode> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeBoolean(value.enabled()),
            buffer -> new FallbackMode(buffer.readBoolean()));
        @Override public Type<FallbackMode> type() { return TYPE; }
    }

    public record HeadbandEnergy(int slotIndex, int energy) implements CustomPacketPayload {
        public static final Type<HeadbandEnergy> TYPE = new Type<>(FlashlightMod.resource("headband_energy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HeadbandEnergy> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.slotIndex());
                buffer.writeVarInt(value.energy());
            },
            buffer -> new HeadbandEnergy(buffer.readVarInt(), buffer.readVarInt()));
        @Override public Type<HeadbandEnergy> type() { return TYPE; }
    }

    public record HandheldEnergy(int inventorySlot, int energy) implements CustomPacketPayload {
        public static final Type<HandheldEnergy> TYPE = new Type<>(FlashlightMod.resource("handheld_energy"));
        public static final StreamCodec<RegistryFriendlyByteBuf, HandheldEnergy> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.inventorySlot());
                buffer.writeVarInt(value.energy());
            },
            buffer -> new HandheldEnergy(buffer.readVarInt(), buffer.readVarInt())
        );
        @Override public Type<HandheldEnergy> type() { return TYPE; }
    }


    public record Press(UUID owner, InteractionHand hand, boolean previousEnabled, boolean enabled) implements CustomPacketPayload {
        public static final Type<Press> TYPE = new Type<>(FlashlightMod.resource("press"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Press> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeUUID(value.owner());
            buffer.writeEnum(value.hand());
            buffer.writeBoolean(value.previousEnabled());
            buffer.writeBoolean(value.enabled());
        }, buffer -> new Press(buffer.readUUID(), buffer.readEnum(InteractionHand.class), buffer.readBoolean(), buffer.readBoolean()));
        @Override public Type<Press> type() { return TYPE; }
    }

    public static final class FallbackModeEvent extends Event {
        private final boolean enabled;
        public FallbackModeEvent(boolean enabled) { this.enabled = enabled; }
        public boolean enabled() { return enabled; }
    }

    /** Posted on the client game thread; common code has no client class dependencies. */
    public static final class PressEvent extends Event {
        private final UUID owner;
        private final InteractionHand hand;
        private final boolean previousEnabled;
        private final boolean enabled;
        public PressEvent(UUID owner, InteractionHand hand, boolean previousEnabled, boolean enabled) {
            this.owner = owner;
            this.hand = hand;
            this.previousEnabled = previousEnabled;
            this.enabled = enabled;
        }
        public UUID owner() { return owner; }
        public InteractionHand hand() { return hand; }
        public boolean previousEnabled() { return previousEnabled; }
        public boolean enabled() { return enabled; }
    }
}
