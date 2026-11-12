package pl.rybaautomine;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class RybaAutoMineMod implements ModInitializer {
    /** Promień: 6 => obszar 13x13x13 (6 + środek + 6). */
    public static final int RADIUS = 6;
    /** Co ile ticków skanować obszar i ile bloków max zniszczyć na skan. */
    public static final int SCAN_INTERVAL = 4;
    public static final int MAX_BREAKS_PER_SCAN = 12;

    private record Cfg(boolean enabled, Set<Identifier> blocks) {}
    private static final Map<UUID, Cfg> CONFIGS = new HashMap<>();
    private int tick = 0;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(ConfigPayload.ID, ConfigPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            // Na serwerze dedykowanym tylko operatorzy (poziom 2+); w singleplayerze każdy.
            if (!player.server.isSingleplayer() && !player.hasPermissionLevel(2)) return;
            CONFIGS.put(player.getUuid(), new Cfg(payload.enabled(), new HashSet<>(payload.blocks())));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CONFIGS.remove(handler.getPlayer().getUuid()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++tick % SCAN_INTERVAL != 0) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Cfg cfg = CONFIGS.get(player.getUuid());
                if (cfg == null || !cfg.enabled() || cfg.blocks().isEmpty()) continue;
                if (player.isSpectator()) continue;
                scan(player, cfg);
            }
        });
    }

    private static void scan(ServerPlayerEntity player, Cfg cfg) {
        ServerWorld world = player.getServerWorld();
        BlockPos c = player.getBlockPos();
        int broken = 0;
        for (BlockPos pos : BlockPos.iterate(c.add(-RADIUS, -RADIUS, -RADIUS), c.add(RADIUS, RADIUS, RADIUS))) {
            var state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (!cfg.blocks().contains(Registries.BLOCK.getId(state.getBlock()))) continue;
            // tryBreakBlock respektuje tryb gry, narzędzie, drop i ochronę spawnu
            if (player.interactionManager.tryBreakBlock(pos.toImmutable()) && ++broken >= MAX_BREAKS_PER_SCAN) break;
        }
    }
}
