package pl.rybaautomine;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * tool: id przedmiotu-narzędzia (np. "minecraft:diamond_pickaxe"), puste = trzymany przedmiot.
 * repairMode: "none" | "bottles" | "command".
 */
public record ConfigPayload(boolean enabled, int delay, String tool, String repairMode, List<Identifier> blocks)
        implements CustomPayload {
    public static final Id<ConfigPayload> ID = new Id<>(Identifier.of("rybaautomine", "config"));
    public static final PacketCodec<RegistryByteBuf, ConfigPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOLEAN, ConfigPayload::enabled,
            PacketCodecs.VAR_INT, ConfigPayload::delay,
            PacketCodecs.STRING, ConfigPayload::tool,
            PacketCodecs.STRING, ConfigPayload::repairMode,
            Identifier.PACKET_CODEC.collect(PacketCodecs.toList()), ConfigPayload::blocks,
            ConfigPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
