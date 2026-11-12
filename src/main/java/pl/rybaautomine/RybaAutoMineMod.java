package pl.rybaautomine;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class RybaAutoMineMod implements ModInitializer {
    /** Promień: 6 => obszar 13x13x13 (6 + środek + 6). */
    public static final int RADIUS = 6;
    /** Ile bloków nad głową gracza ma priorytet (kolumna nad graczem). */
    public static final int COLUMN_UP = 5;
    /** Opóźnienie ustawiane w GUI: 0-10 ticków przerwy między blokami (0 = co tick). */
    public static final int MAX_DELAY = 10;

    private record Cfg(boolean enabled, int delay, Identifier tool, Set<Identifier> blocks) {}
    private static final Map<UUID, Cfg> CONFIGS = new HashMap<>();
    private int tick = 0;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(ConfigPayload.ID, ConfigPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ConfigPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            // Na serwerze dedykowanym tylko operatorzy (poziom 2+); w singleplayerze każdy.
            if (!player.server.isSingleplayer() && !player.hasPermissionLevel(2)) return;
            Identifier tool = payload.tool() == null || payload.tool().isEmpty()
                    ? null : Identifier.tryParse(payload.tool());
            CONFIGS.put(player.getUuid(), new Cfg(
                    payload.enabled(),
                    Math.max(0, Math.min(MAX_DELAY, payload.delay())),
                    tool,
                    new HashSet<>(payload.blocks())));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CONFIGS.remove(handler.getPlayer().getUuid()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Cfg cfg = CONFIGS.get(player.getUuid());
                if (cfg == null || !cfg.enabled() || cfg.blocks().isEmpty()) continue;
                if (player.isSpectator()) continue;
                if (tick % (cfg.delay() + 1) != 0) continue;
                breakNext(player, cfg);
            }
        });
    }

    /**
     * Niszczy JEDEN blok na raz, po kolei:
     * najpierw kolumna nad graczem (od najniższego do najwyższego, max COLUMN_UP),
     * potem reszta obszaru 13x13x13 od najbliższego bloku do najdalszego.
     */
    private static void breakNext(ServerPlayerEntity player, Cfg cfg) {
        ServerWorld world = player.getServerWorld();
        BlockPos c = player.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterate(c.add(-RADIUS, -RADIUS, -RADIUS), c.add(RADIUS, RADIUS, RADIUS))) {
            var state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (!cfg.blocks().contains(Registries.BLOCK.getId(state.getBlock()))) continue;

            int dx = pos.getX() - c.getX();
            int dy = pos.getY() - c.getY();
            int dz = pos.getZ() - c.getZ();
            boolean column = dx == 0 && dz == 0 && dy >= 1 && dy <= COLUMN_UP + 1;
            double score = column ? dy : 100 + dx * dx + dy * dy + dz * dz;
            if (score < bestScore) {
                bestScore = score;
                best = pos.toImmutable();
            }
        }
        if (best == null) return;

        if (cfg.tool() != null) selectTool(player, cfg.tool());
        player.interactionManager.tryBreakBlock(best);
    }

    private static java.lang.reflect.Field selectedSlotField;
    private static boolean selectedSlotFieldFailed = false;

    /**
     * Jeśli wybrane narzędzie jest w hotbarze gracza, przełącza na nie trzymany przedmiot.
     * Używa refleksji zamiast bezpośredniego dostępu do pola, żeby kompilacja nie zależała
     * od dokładnej nazwy pola w danej wersji mapowań (Yarn). Jeśli nazwa się nie zgadza,
     * funkcja po cichu nic nie robi i mod dalej działa, tylko bez zmiany trzymanego przedmiotu.
     */
    private static void selectTool(ServerPlayerEntity player, Identifier toolId) {
        var inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).equals(toolId)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) return; // narzędzia brak w hotbarze - zostaje to, co aktualnie trzymane

        if (selectedSlotFieldFailed) return;
        try {
            if (selectedSlotField == null) {
                selectedSlotField = inv.getClass().getField("selectedSlot");
            }
            selectedSlotField.setInt(inv, slot);
        } catch (ReflectiveOperationException e) {
            selectedSlotFieldFailed = true; // nie próbuj ponownie, oszczędza wydajność
        }
    }
}
