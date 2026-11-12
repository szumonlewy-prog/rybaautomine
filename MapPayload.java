package pl.rybaautomine;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Mapa stref 50x50 bloków, zakotwiczona w realnych współrzędnych świata (originX/originZ).
 * green/red/orange to listy indeksów komórek (0..2499, indeks = lokalnyZ*50+lokalnyX),
 * gdzie lokalny 0..49 odpowiada przesunięciu -25..+24 względem punktu zakotwiczenia.
 */
public record MapPayload(int originX, int originZ, List<Integer> green, List<Integer> red, List<Integer> orange)
        implements CustomPayload {
    public static final Id<MapPayload> ID = new Id<>(Identifier.of("rybaautomine", "map"));
    public static final PacketCodec<RegistryByteBuf, MapPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, MapPayload::originX,
            PacketCodecs.VAR_INT, MapPayload::originZ,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), MapPayload::green,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), MapPayload::red,
            PacketCodecs.VAR_INT.collect(PacketCodecs.toList()), MapPayload::orange,
            MapPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
