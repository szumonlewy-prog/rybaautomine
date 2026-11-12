package pl.rybaautomine;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

public record ConfigPayload(boolean enabled, List<Identifier> blocks) implements CustomPayload {
    public static final Id<ConfigPayload> ID = new Id<>(Identifier.of("rybaautomine", "config"));
    public static final PacketCodec<RegistryByteBuf, ConfigPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, ConfigPayload::enabled,
            Identifier.PACKET_CODEC.collect(PacketCodecs.toList()), ConfigPayload::blocks,
            ConfigPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
