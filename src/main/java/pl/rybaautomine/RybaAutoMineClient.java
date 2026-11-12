package pl.rybaautomine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RybaAutoMineClient implements ClientModInitializer {
    public static boolean enabled = false;
    public static final Set<Identifier> selected = new LinkedHashSet<>();
    private static KeyBinding openGui, toggleKey;
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("rybaautomine.txt");

    @Override
    public void onInitializeClient() {
        load();
        openGui = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rybaautomine.gui",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_O, "category.rybaautomine"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.rybaautomine.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.rybaautomine"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openGui.wasPressed()) mc.setScreen(new RybaAutoMineScreen());
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                update();
                if (mc.player != null)
                    mc.player.sendMessage(Text.literal("RybaAutoMine 13x13: " + (enabled ? "WŁĄCZONY" : "WYŁĄCZONY")), true);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> sync());
    }

    /** Wysyła ustawienia na serwer. */
    public static void sync() {
        if (ClientPlayNetworking.canSend(ConfigPayload.ID))
            ClientPlayNetworking.send(new ConfigPayload(enabled, new ArrayList<>(selected)));
    }

    /** Sync + zapis do pliku. */
    public static void update() {
        sync();
        save();
    }

    private static void save() {
        try {
            List<String> lines = new ArrayList<>();
            lines.add(String.valueOf(enabled));
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
                Identifier id = Identifier.tryParse(lines.get(i).trim());
                if (id != null) selected.add(id);
            }
        } catch (Exception ignored) {}
    }
}
