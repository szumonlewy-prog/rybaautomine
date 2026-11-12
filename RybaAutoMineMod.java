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

    /** Jeden blok na 0.5s = 10 ticków - stałe tempo usuwania przeszkód w drodze. */
    public static final int OBSTACLE_BREAK_TICKS = 10;
    /** Rozmiar mapy stref (50x50), zakotwiczonej w originX/originZ wysłanych z GUI. */
    public static final int MAP_SIZE = 50;

    private record Cfg(boolean enabled, int delay, String tool, String repairMode, Set<Identifier> blocks) {}

    /** Mapa stref gracza: green=gdzie kopać, red=gdzie nie wchodzić, orange=droga do kowadła. */
    private record MapCfg(int originX, int originZ, Set<Integer> green, Set<Integer> red, Set<Integer> orange) {
        boolean isEmpty() { return green.isEmpty() && red.isEmpty() && orange.isEmpty(); }
    }

    /** Stan ruchu/naprawy danego gracza, nie wysyłany z GUI - liczony tylko na serwerze. */
    private static class State {
        int breakCooldown = 0;
        int repairCooldown = 0;
        int obstacleCooldown = 0;
        List<BlockPos> path = null; // wyznaczona trasa pomarańczowymi komórkami do kowadła
        int pathIndex = 0;
    }

    private static final Map<UUID, Cfg> CONFIGS = new HashMap<>();
    private static final Map<UUID, MapCfg> MAPS = new HashMap<>();
    private static final Map<UUID, State> STATES = new HashMap<>();
    private int tick = 0;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(ConfigPayload.ID, ConfigPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(MapPayload.ID, MapPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MapPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (!player.server.isSingleplayer() && !player.hasPermissionLevel(2)) return;
            MAPS.put(player.getUuid(), new MapCfg(
                    payload.originX(), payload.originZ(),
                    new HashSet<>(payload.green()), new HashSet<>(payload.red()), new HashSet<>(payload.orange())));
            State st = STATES.get(player.getUuid());
            if (st != null) { st.path = null; st.pathIndex = 0; } // trasa się zmieniła, policz ją od nowa
        });

        ServerPlayNetworking.registerGlobalReceiver(ConfigPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            // Na serwerze dedykowanym tylko operatorzy (poziom 2+); w singleplayerze każdy.
            if (!player.server.isSingleplayer() && !player.hasPermissionLevel(2)) return;
            String tool = payload.tool() == null ? "" : payload.tool();
            String repair = payload.repairMode() == null ? "none" : payload.repairMode();
            CONFIGS.put(player.getUuid(), new Cfg(
                    payload.enabled(),
                    Math.max(1, Math.min(MAX_DELAY, payload.delay())),
                    tool, repair,
                    new HashSet<>(payload.blocks())));
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            CONFIGS.remove(handler.getPlayer().getUuid());
            MAPS.remove(handler.getPlayer().getUuid());
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
                MapCfg map = MAPS.get(player.getUuid());
                tickPlayer(player, cfg, map, st);
            }
        });
    }

    private static void tickPlayer(ServerPlayerEntity player, Cfg cfg, MapCfg map, State st) {
        if (st.breakCooldown > 0) st.breakCooldown -= MOVE_INTERVAL;
        if (st.repairCooldown > 0) st.repairCooldown -= MOVE_INTERVAL;
        if (st.obstacleCooldown > 0) st.obstacleCooldown -= MOVE_INTERVAL;

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
                        st.path = null;
                    } else {
                        walkTowardAnvil(player, anvil, map, st);
                    }
                    return; // dopóki naprawia - nie kopie
                }
                st.path = null;
                // brak kowadła w promieniu ANVIL_RADIUS - kop dalej mimo niskiej wytrzymałości
            }
        }

        ServerWorld world = player.getServerWorld();
        BlockPos target = findNextTarget(world, player, cfg, map);
        if (target == null) return;

        Vec3d center = Vec3d.ofCenter(target);
        if (player.getPos().squaredDistanceTo(center) <= REACH * REACH) {
            if (st.breakCooldown <= 0) {
                selectTool(player, cfg.tool());
                player.interactionManager.tryBreakBlock(target);
                st.breakCooldown = cfg.delay();
            }
        } else {
            walkToward(player, center, st);
        }
    }

    /**
     * Niszczy JEDEN blok na raz, po kolei: najpierw kolumna nad graczem (max COLUMN_UP),
     * potem reszta obszaru 13x13x13 od najbliższego bloku do najdalszego.
     */
    private static BlockPos findNextTarget(ServerWorld world, ServerPlayerEntity player, Cfg cfg, MapCfg map) {
        BlockPos c = player.getBlockPos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        boolean useMap = map != null && !map.isEmpty();

        for (BlockPos pos : BlockPos.iterate(c.add(-RADIUS, -RADIUS, -RADIUS), c.add(RADIUS, RADIUS, RADIUS))) {
            var state = world.getBlockState(pos);
            if (state.isAir()) continue;
            if (!cfg.blocks().contains(Registries.BLOCK.getId(state.getBlock()))) continue;

            int dx = pos.getX() - c.getX();
            int dy = pos.getY() - c.getY();
            int dz = pos.getZ() - c.getZ();
            boolean column = dx == 0 && dz == 0 && dy >= 1 && dy <= COLUMN_UP + 1;

            if (useMap) {
                Integer cell = cellIndex(map, pos.getX(), pos.getZ());
                boolean isRed = cell != null && map.red().contains(cell);
                if (isRed) continue; // czerwone - nigdy nie wchodź/kop tam
                if (!column) {
                    boolean isGreen = cell != null && map.green().contains(cell);
                    if (!map.green().isEmpty() && !isGreen) continue; // są zielone strefy - trzymaj się ich
                }
            }

            double score = column ? dy : 100 + dx * dx + dy * dy + dz * dz;
            if (score < bestScore) {
                bestScore = score;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    /** Zwraca indeks komórki mapy (0..2499) dla danego X/Z, albo null, jeśli poza mapą 50x50. */
    private static Integer cellIndex(MapCfg map, int worldX, int worldZ) {
        int lx = worldX - map.originX() + MAP_SIZE / 2;
        int lz = worldZ - map.originZ() + MAP_SIZE / 2;
        if (lx < 0 || lx >= MAP_SIZE || lz < 0 || lz >= MAP_SIZE) return null;
        return lz * MAP_SIZE + lx;
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
     * a gdy droga jest zablokowana - najpierw próba skoku. Jeśli to nie wystarczy
     * (np. ściana 2 bloki), po OBSTACLE_BREAK_TICKS (0.5s) niszczy blok, który stoi
     * na drodze - w stałym tempie, niezależnie od ustawionej szybkości kopania.
     * To nie jest pełny pathfinding: po bardzo złożonym terenie (przepaść, labirynt)
     * gracz nadal może utknąć. Sprawdzone w praktyce może wymagać dalszych poprawek.
     */
    private static void walkToward(ServerPlayerEntity player, Vec3d target, State st) {
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
            if (st != null && st.obstacleCooldown <= 0) {
                BlockPos obstacle = findObstacleBlock(player, sx, sz);
                if (obstacle != null) {
                    player.interactionManager.tryBreakBlock(obstacle);
                    st.obstacleCooldown = OBSTACLE_BREAK_TICKS;
                }
            }
            player.networkHandler.requestTeleport(pos.x, pos.y, pos.z, yaw, player.getPitch());
        } else {
            player.networkHandler.requestTeleport(pos.x + sx, pos.y, pos.z + sz, yaw, player.getPitch());
        }
    }

    /** Szuka bloku blokującego drogę (na wysokości stóp albo głowy) w kierunku ruchu. */
    private static BlockPos findObstacleBlock(ServerPlayerEntity player, double sx, double sz) {
        BlockPos feet = player.getBlockPos();
        BlockPos ahead = new BlockPos(
                (int) Math.floor(player.getX() + sx * 2),
                feet.getY(),
                (int) Math.floor(player.getZ() + sz * 2));
        var world = player.getWorld();
        if (!world.getBlockState(ahead).isAir()) return ahead;
        BlockPos aheadUp = ahead.up();
        if (!world.getBlockState(aheadUp).isAir()) return aheadUp;
        return null;
    }

    /**
     * Chodzenie do kowadła: jeśli na mapie są narysowane pomarańczowe komórki,
     * wyznacza trasę przez nie (najbliższy nieodwiedzony punkt po kolei) i idzie
     * nią krok po kroku, zamiast iść do kowadła na wprost.
     */
    private static void walkTowardAnvil(ServerPlayerEntity player, BlockPos anvil, MapCfg map, State st) {
        if (map == null || map.orange().isEmpty()) {
            walkToward(player, Vec3d.ofCenter(anvil), st);
            return;
        }
        if (st.path == null) {
            st.path = buildOrangePath(player.getBlockPos(), anvil, map);
            st.pathIndex = 0;
        }
        if (st.path.isEmpty()) {
            walkToward(player, Vec3d.ofCenter(anvil), st);
            return;
        }
        if (st.pathIndex >= st.path.size()) {
            walkToward(player, Vec3d.ofCenter(anvil), st);
            return;
        }
        BlockPos waypoint = st.path.get(st.pathIndex);
        Vec3d center = Vec3d.ofCenter(waypoint);
        if (player.getPos().squaredDistanceTo(new Vec3d(center.x, player.getY(), center.z)) <= 1.0) {
            st.pathIndex++;
        }
        walkToward(player, center, st);
    }

    /** Łańcuch "najbliższy nieodwiedzony punkt" przez pomarańczowe komórki, od gracza do kowadła. */
    private static List<BlockPos> buildOrangePath(BlockPos from, BlockPos anvil, MapCfg map) {
        List<int[]> remaining = new ArrayList<>();
        for (int idx : map.orange()) {
            int lx = idx % MAP_SIZE, lz = idx / MAP_SIZE;
            remaining.add(new int[]{map.originX() - MAP_SIZE / 2 + lx, map.originZ() - MAP_SIZE / 2 + lz});
        }
        List<BlockPos> path = new ArrayList<>();
        int curX = from.getX(), curZ = from.getZ();
        while (!remaining.isEmpty()) {
            int bestI = -1;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                long dx = remaining.get(i)[0] - curX, dz = remaining.get(i)[1] - curZ;
                long d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; bestI = i; }
            }
            int[] p = remaining.remove(bestI);
            path.add(new BlockPos(p[0], from.getY(), p[1]));
            curX = p[0];
            curZ = p[1];
        }
        return path;
    }

    /** Dopasowuje po RODZAJU narzędzia (np. "axe" pasuje do każdej siekiery, drewnianej po netherytową). */
    private static boolean matchesKind(Identifier id, String kind) {
        String path = id.getPath();
        if (kind.equals("shears")) return path.equals("shears");
        return path.endsWith("_" + kind);
    }

    private static ItemStack currentTool(ServerPlayerEntity player, String toolKind) {
        if (toolKind == null || toolKind.isEmpty()) return player.getMainHandStack();
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && matchesKind(Registries.ITEM.getId(s.getItem()), toolKind)) return s;
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
    private static void selectTool(ServerPlayerEntity player, String toolKind) {
        if (toolKind == null || toolKind.isEmpty()) return;
        var inv = player.getInventory();
        int slot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && matchesKind(Registries.ITEM.getId(stack.getItem()), toolKind)) {
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
