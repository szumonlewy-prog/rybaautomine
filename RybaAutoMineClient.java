package pl.rybaautomine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RybaAutoMineClient implements ClientModInitializer {
    public static boolean enabled = false;
    /** Szybkość kopania w blokach na sekundę: 0.1 - 10.0. */
    public static double speed = 2.0;
    /** Rodzaj narzędzia ("axe","pickaxe"...), puste = trzymany przedmiot. */
    public static String toolId = "";
    /** "none" | "bottles" | "command" - sposób naprawy przy kowadle. */
    public static String repairMode = "none";
    /** Procent POZOSTAŁEJ wytrzymałości, poniżej którego mod szuka kowadła. */
    public static int repairThreshold = 25;
    public static final Set<Identifier> selected = new LinkedHashSet<>();

    // Ustawienia wyglądu GUI (tylko po stronie klienta).
    public static int guiRows = 8;
    public static int guiWidth = 240;
    public static int accentColor = 0x2E9E3E;

    private static KeyBinding openGui, toggleKey, pickBlockKey;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("rybaautomine.txt");

    @Override
    public void onInitializeClient() {
        load();
        openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rybaautomine.gui",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.rybaautomine"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rybaautomine.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.rybaautomine"));
        // Środkowy przycisk myszy (scroll-click) - dodaje blok, na który patrzysz, do listy.
        pickBlockKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rybaautomine.pick",
                InputUtil.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "category.rybaautomine"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openGui.wasPressed()) mc.setScreen(new RybaAutoMineScreen());
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                update();
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal("RybaAutoMine 25x25x25: " + (enabled ? "WŁĄCZONY" : "WYŁĄCZONY")), true);
            }
            while (pickBlockKey.wasPressed()) {
                if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && mc.world != null) {
                    Block block = mc.world.getBlockState(hit.getBlockPos()).getBlock();
                    Identifier id = Registries.BLOCK.getId(block);
                    if (selected.add(id)) {
                        update();
                        if (mc.player != null)
                            mc.player.sendMessage(Text.literal("§aDodano do listy: §f" + block.getName().getString()), true);
                    }
                }
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sync());
    }

    /** Przelicza bloki/s na opóźnienie w tickach (20 ticków = 1 sekunda). */
    public static int delayTicks() {
        return Math.max(1, (int) Math.round(20.0 / Math.max(0.1, speed)));
    }

    public static void sync() {
        if (!ClientPlayNetworking.canSend(ConfigPayload.ID)) return;
        String spec = repairMode.equals("none") ? "none" : (repairMode + ":" + repairThreshold);
        ClientPlayNetworking.send(new ConfigPayload(enabled, delayTicks(), toolId, spec, new ArrayList<>(selected)));
    }

    public static void update() {
        sync();
        save();
    }

    /** Zeruje wszystkie ustawienia i usuwa plik konfiguracji. */
    public static void resetAll() {
        enabled = false;
        speed = 2.0;
        toolId = "";
        repairMode = "none";
        repairThreshold = 25;
        selected.clear();
        guiRows = 8;
        guiWidth = 240;
        accentColor = 0x2E9E3E;
        sync();
        try { Files.deleteIfExists(FILE); } catch (Exception ignored) {}
    }

    private static void save() {
        try {
            List<String> lines = new ArrayList<>();
            lines.add(String.valueOf(enabled));
            lines.add("speed=" + speed);
            lines.add("tool=" + toolId);
            lines.add("repair=" + repairMode);
            lines.add("threshold=" + repairThreshold);
            lines.add("rows=" + guiRows);
            lines.add("width=" + guiWidth);
            lines.add("accent=" + Integer.toHexString(accentColor));
            selected.forEach(id -> lines.add(id.toString()));
            Files.write(FILE, lines);
        } catch (Exception ignored) {}
    }

    private static void load() {
        try {
            if (!Files.exists(FILE)) return;
            List<String> lines = Files.readAllLines(FILE);
            if (lines.isEmpty()) return;
            enabled = Boolean.parseBoolean(lines.get(0).trim());
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("speed=")) {
                    try { speed = Math.max(0.1, Math.min(10.0, Double.parseDouble(line.substring(6)))); } catch (Exception ignored) {}
                    continue;
                }
                if (line.startsWith("tool=")) { toolId = line.substring(5); continue; }
                if (line.startsWith("repair=")) { repairMode = line.substring(7); continue; }
                if (line.startsWith("threshold=")) {
                    try { repairThreshold = Math.max(5, Math.min(90, Integer.parseInt(line.substring(10)))); } catch (Exception ignored) {}
                    continue;
                }
                if (line.startsWith("rows=")) {
                    try { guiRows = Math.max(3, Math.min(12, Integer.parseInt(line.substring(5)))); } catch (Exception ignored) {}
                    continue;
                }
                if (line.startsWith("width=")) {
                    try { guiWidth = Math.max(200, Math.min(420, Integer.parseInt(line.substring(6)))); } catch (Exception ignored) {}
                    continue;
                }
                if (line.startsWith("accent=")) {
                    try { accentColor = (int) Long.parseLong(line.substring(7), 16) & 0xFFFFFF; } catch (Exception ignored) {}
                    continue;
                }
                Identifier id = Identifier.tryParse(line);
                if (id != null) selected.add(id);
            }
        } catch (Exception ignored) {}
    }
}
