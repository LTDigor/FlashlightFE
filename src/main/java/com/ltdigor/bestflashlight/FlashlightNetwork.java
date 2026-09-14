package com.ltdigor.bestflashlight;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
    }

    public record Toggle(LampControl.Action action) implements CustomPacketPayload {
        public static final Type<Toggle> TYPE = new Type<>(FlashlightMod.resource("toggle"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Toggle> CODEC = StreamCodec.of(
            (buffer, value) -> buffer.writeEnum(value.action()), buffer -> new Toggle(buffer.readEnum(LampControl.Action.class)));
        @Override public Type<Toggle> type() { return TYPE; }
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
