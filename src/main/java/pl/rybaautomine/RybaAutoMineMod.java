package pl.rybaautomine;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class RybaAutoMineMod implements ModInitializer {
    /** Promień szukania bloków do wykopania: 6 => obszar 13x13x13 (6 + środek + 6). */
    public static final int RADIUS = 6;
    /** Ile bloków nad głową gracza ma priorytet (kolumna nad graczem). */
    public static final int COLUMN_UP = 5;
    /** Opóźnienie ustawiane w GUI: w tickach między kolejnymi wybijanymi blokami. */
    public static final int MAX_DELAY = 200;
    /** Dystans, z którego mod uznaje blok/kowadło za "w zasięgu" (bez chodzenia). */
    public static final double REACH = 3.5;
    /** Promień szukania kowadła do naprawy. */
    public static final int ANVIL_RADIUS = 20;
    /** Próg wytrzymałości narzędzia (procent), poniżej którego mod szuka kowadła. */
    public static final double REPAIR_THRESHOLD = 0.50;
    /** Co ile ticków odświeżać cel i próbować kroku ruchu (ruch jest "eksperymentalny"). */
    public static final int MOVE_INTERVAL = 2;

    private record Cfg(boolean enabled, int delay, Identifier tool, String repairMode, Set<Identifier> blocks) {}

    /** Stan ruchu/naprawy danego gracza, nie wysyłany z GUI - liczony tylko na serwerze. */
    private static class State {
        int breakCooldown = 0;
        int repairCooldown = 0;
    }

    private static final Map<UUID, Cfg> CONFIGS = new HashMap<>();
    private static final Map<UUID, State> STATES = new HashMap<>();
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
            String repair = payload.repairMode() == null ? "none" : payload.repairMode();
            CONFIGS.put(player.getUuid(), new Cfg(
                    payload.enabled(),
                    Math.max(1, Math.min(MAX_DELAY, payload.delay())),
                    tool, repair,
                    new HashSet<>(payload.blocks())));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CONFIGS.remove(handler.getPlayer().getUuid());
            STATES.remove(handler.getPlayer().getUuid());
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tick++;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Cfg cfg = CONFIGS.get(player.getUuid());
                if (cfg == null || !cfg.enabled() || cfg.blocks().isEmpty()) continue;
                if (player.isSpectator()) continue;
                if (tick % MOVE_INTERVAL != 0) continue;
                State st = STATES.computeIfAbsent(player.getUuid(), u -> new State());
                tickPlayer(player, cfg, st);
            }
        });
    }

    private static void tickPlayer(ServerPlayerEntity player, Cfg cfg, State st) {
        if (st.breakCooldown > 0) st.breakCooldown -= MOVE_INTERVAL;
        if (st.repairCooldown > 0) st.repairCooldown -= MOVE_INTERVAL;

        if (!cfg.repairMode().equals("none")) {
            ItemStack tool = currentTool(player, cfg.tool());
            if (needsRepair(tool)) {
                ServerWorld world = player.getServerWorld();
                BlockPos anvil = findNearestAnvil(world, player.getBlockPos());
                if (anvil != null) {
                    Vec3d center = Vec3d.ofCenter(anvil);
                    if (player.getPos().squaredDistanceTo(center) <= REACH * REACH) {
                        if (st.repairCooldown <= 0) {
                            tryRepair(player, tool, cfg.repairMode());
                            st.repairCooldown = 40; // nie spamuj co 2 ticki
                        }
                    } else {
                        walkToward(player, center);
                    }
                    return; // dopóki naprawia - nie kopie
                }
                // brak kowadła w promieniu ANVIL_RADIUS - kop dalej mimo niskiej wytrzymałości
            }
        }

        ServerWorld world = player.getServerWorld();
        BlockPos target = findNextTarget(world, player, cfg);
        if (target == null) return;

        Vec3d center = Vec3d.ofCenter(target);
        if (player.getPos().squaredDistanceTo(center) <= REACH * REACH) {
            if (st.breakCooldown <= 0) {
                selectTool(player, cfg.tool());
                player.interactionManager.tryBreakBlock(target);
                st.breakCooldown = cfg.delay();
            }
        } else {
            walkToward(player, center);
        }
    }

    /**
     * Niszczy JEDEN blok na raz, po kolei: najpierw kolumna nad graczem (max COLUMN_UP),
     * potem reszta obszaru 13x13x13 od najbliższego bloku do najdalszego.
     */
    private static BlockPos findNextTarget(ServerWorld world, ServerPlayerEntity player, Cfg cfg) {
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
        return best;
    }

    private static BlockPos findNearestAnvil(ServerWorld world, BlockPos center) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(
                center.add(-ANVIL_RADIUS, -ANVIL_RADIUS, -ANVIL_RADIUS),
                center.add(ANVIL_RADIUS, ANVIL_RADIUS, ANVIL_RADIUS))) {
            String path = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).getPath();
            if (!path.equals("anvil") && !path.equals("chipped_anvil") && !path.equals("damaged_anvil")) continue;
            double d = pos.getSquaredDistance(center);
            if (d < bestDist) { bestDist = d; best = pos.toImmutable(); }
        }
        return best;
    }

    /**
     * Prosty, "eksperymentalny" ruch w stronę celu: krok w poziomie co tick ruchu,
     * a gdy droga jest zablokowana (np. jednoblokowy próg) - skok. To nie jest pełny
     * pathfinding: po bardziej złożonym terenie (np. ściana 2+ bloki, przepaść) gracz
     * może utknąć. Sprawdzone w praktyce może wymagać dalszych poprawek.
     */
    private static void walkToward(ServerPlayerEntity player, Vec3d target) {
        Vec3d pos = player.getPos();
        double dx = target.x - pos.x, dz = target.z - pos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.1) return;
        double stepLen = 0.22 * MOVE_INTERVAL;
        double sx = dx / len * stepLen, sz = dz / len * stepLen;

        Box moved = player.getBoundingBox().offset(sx, 0, sz);
        boolean blocked = !player.getWorld().isSpaceEmpty(player, moved);

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        if (blocked) {
            if (player.isOnGround()) player.jump();
            player.networkHandler.requestTeleport(pos.x, pos.y, pos.z, yaw, player.getPitch());
        } else {
            player.networkHandler.requestTeleport(pos.x + sx, pos.y, pos.z + sz, yaw, player.getPitch());
        }
    }

    private static ItemStack currentTool(ServerPlayerEntity player, Identifier toolId) {
        if (toolId == null) return player.getMainHandStack();
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && Registries.ITEM.getId(s.getItem()).equals(toolId)) return s;
        }
        return player.getMainHandStack();
    }

    private static boolean needsRepair(ItemStack stack) {
        if (stack.isEmpty() || !stack.isDamageable()) return false;
        double remaining = 1.0 - (double) stack.getDamage() / stack.getMaxDamage();
        return remaining <= REPAIR_THRESHOLD;
    }

    /**
     * Naprawia narzędzie wprost w kodzie, bez odtwarzania ręcznych kliknięć na kowadle.
     * Korzysta tylko ze stabilnych, podstawowych metod (setDamage, decrement), więc
     * zawsze się skompiluje i zawsze zadziała - kosztem tego, że nie pokazuje graczowi
     * okna kowadła, tylko "magicznie" naprawia narzędzie, gdy gracz do niego podejdzie.
     */
    private static void tryRepair(ServerPlayerEntity player, ItemStack tool, String mode) {
        if (mode.equals("bottles")) {
            var inv = player.getInventory();
            int bottleSlot = -1;
            for (int i = 0; i < 9; i++) {
                ItemStack s = inv.getStack(i);
                if (!s.isEmpty() && s.getItem() == Items.EXPERIENCE_BOTTLE) { bottleSlot = i; break; }
            }
            if (bottleSlot < 0) return; // brak butelek w hotbarze - spróbuje ponownie później
            inv.getStack(bottleSlot).decrement(1);
            tool.setDamage(0);
        } else if (mode.equals("command")) {
            if (player.getServer() == null) return;
            player.getServer().getCommandManager()
                    .executeWithPrefix(player.getCommandSource(), "repair");
        }
    }

    private static java.lang.reflect.Field selectedSlotField;
    private static boolean selectedSlotFieldFailed = false;

    /**
     * Jeśli wybrane narzędzie jest w hotbarze gracza, przełącza na nie trzymany przedmiot.
     * Używa refleksji, więc kompilacja nie zależy od dokładnej nazwy pola w danej wersji
     * mapowań (Yarn). Jeśli nazwa się nie zgadza, funkcja po cichu nic nie robi.
     */
    private static void selectTool(ServerPlayerEntity player, Identifier toolId) {
        if (toolId == null) return;
        var inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).equals(toolId)) {
                slot = i;
                break;
            }
        }
        if (slot < 0 || selectedSlotFieldFailed) return;
        try {
            if (selectedSlotField == null) selectedSlotField = inv.getClass().getField("selectedSlot");
            selectedSlotField.setInt(inv, slot);
        } catch (ReflectiveOperationException e) {
            selectedSlotFieldFailed = true;
        }
    }
}
