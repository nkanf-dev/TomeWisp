package dev.openallay.fabric.network;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class FabricBridgePayloads {
    private static boolean registered;

    private FabricBridgePayloads() {}

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PayloadTypeRegistry.serverboundPlay().register(Packet.TYPE, Packet.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Packet.TYPE, Packet.CODEC);
    }

    public record Packet(String kind, String json) implements CustomPacketPayload {
        public static final Type<Packet> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("openallay", "bridge_v1"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Packet> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8,
                Packet::kind,
                ByteBufCodecs.STRING_UTF8,
                Packet::json,
                Packet::new);

        public Packet {
            if (kind == null || kind.isBlank() || json == null || json.isBlank()) {
                throw new IllegalArgumentException("Bridge packet kind and JSON are required");
            }
        }

        @Override
        public Type<Packet> type() {
            return TYPE;
        }
    }
}
