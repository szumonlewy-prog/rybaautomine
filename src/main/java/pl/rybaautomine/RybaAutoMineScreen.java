package pl.rybaautomine;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public class RybaAutoMineScreen extends Screen {
    private static final int COLS = 9, CELL = 22, PAD = 12, TOOL_ROWS = 4;
    private static final String[] TOOL_SUFFIXES = {"_pickaxe", "_axe", "_shovel", "_hoe", "_sword"};

    private enum Tab { BLOKI, USTAWIENIA }

    /** Jeden wpis siatki: id (bloku albo przedmiotu) + ikona + nazwa. */
    private record Entry(Identifier id, Item item, String name) {}

    private Tab tab;

    private final List<Entry> blockEntries = new ArrayList<>();
    private List<Entry> blockFiltered;
    private String blockQuery = "";
    private int blockScroll = 0;

    private final List<Entry> toolEntries = new ArrayList<>();
    private List<Entry> toolFiltered;
    private String toolQuery = "";
    private int toolScroll = 0;

    private int px, py, ph, pw, rows, gridX, gridY;
    private ButtonWidget toggleBtn;

    public RybaAutoMineScreen() { this(Tab.BLOKI); }

    private RybaAutoMineScreen(Tab initialTab) {
        super(Text.literal("RybaAutoMine"));
        this.tab = initialTab;
        for (Block b : Registries.BLOCK) {
            if (b.asItem() == Items.AIR) continue; // tylko bloki z ikoną
            Identifier id = Registries.BLOCK.getId(b);
            blockEntries.add(new Entry(id, b.asItem(), b.getName().getString()));
        }
        blockEntries.sort(Comparator.comparing(Entry::name));
        blockFiltered = new ArrayList<>(blockEntries);

        for (Item it : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(it);
            String path = id.getPath();
            boolean isTool = path.equals("shears");
            for (String suf : TOOL_SUFFIXES) if (path.endsWith(suf)) isTool = true;
            if (isTool) toolEntries.add(new Entry(id, it, it.getName().getString()));
        }
        toolEntries.sort(Comparator.comparing(Entry::name));
        toolFiltered = new ArrayList<>(toolEntries);
    }

    @Override
    protected void init() {
        pw = MathHelper.clamp(RybaAutoMineClient.guiWidth, 200, Math.max(200, width - 40));
        rows = tab == Tab.BLOKI
                ? MathHelper.clamp(Math.min(RybaAutoMineClient.guiRows, (height - 200) / CELL), 3, 12)
                : TOOL_ROWS;
        ph = tab == Tab.BLOKI ? 178 + rows * CELL : 408;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        gridX = px + PAD;
        gridY = tab == Tab.BLOKI ? py + 138 : py + 132;

        // ramka reset - zawsze w prawym górnym rogu panelu
        addDrawableChild(ButtonWidget.builder(Text.literal("§c⟲ Reset"), b -> {
            RybaAutoMineClient.resetAll();
            reopen();
        }).dimensions(px + pw - PAD - 70, py + 7, 70, 14).build());

        // zakładki
        int tabW = (pw - 2 * PAD - 4) / 2;
        addDrawableChild(ButtonWidget.builder(tabLabel("Bloki", tab == Tab.BLOKI), b -> {
            tab = Tab.BLOKI;
            reopen();
        }).dimensions(px + PAD, py + 34, tabW, 18).build());
        addDrawableChild(ButtonWidget.builder(tabLabel("Ustawienia", tab == Tab.USTAWIENIA), b -> {
            tab = Tab.USTAWIENIA;
            reopen();
        }).dimensions(px + PAD + tabW + 4, py + 34, tabW, 18).build());

        if (tab == Tab.BLOKI) initBloki(); else initUstawienia();
    }

    /** Zamyka i od razu otwiera to samo GUI od nowa, zachowując aktualną zakładkę. */
    private void reopen() {
        if (this.client != null) this.client.setScreen(new RybaAutoMineScreen(tab));
    }

    private Text tabLabel(String s, boolean active) {
        return Text.literal(active ? "§l" + s : "§7" + s);
    }

    private void initBloki() {
        toggleBtn = addDrawableChild(ButtonWidget.builder(toggleText(), b -> {
            RybaAutoMineClient.enabled = !RybaAutoMineClient.enabled;
            b.setMessage(toggleText());
            RybaAutoMineClient.update();
        }).dimensions(px + PAD, py + 58, 84, 20).build());

        TextFieldWidget search = new TextFieldWidget(textRenderer, px + PAD + 88, py + 58, pw - 2 * PAD - 88, 20, Text.literal("Szukaj"));
        search.setPlaceholder(Text.literal("Szukaj bloku..."));
        search.setText(blockQuery);
        search.setChangedListener(s -> {
            blockQuery = s.toLowerCase();
            blockFiltered = new ArrayList<>();
            for (Entry e : blockEntries)
                if (e.name().toLowerCase().contains(blockQuery) || e.id().getPath().contains(blockQuery)) blockFiltered.add(e);
            blockScroll = 0;
        });
        addDrawableChild(search);

        int bw = (pw - 2 * PAD - 3 * 3) / 4;
        int by = py + 82;
        addPreset("Rudy", bw, 0, by, e -> {
            String p = e.id().getPath();
            return p.endsWith("_ore") || p.equals("ancient_debris") || p.equals("nether_quartz_ore");
        });
        addPreset("Kamień", bw, 1, by, e -> {
            String p = e.id().getPath();
            return p.equals("stone") || p.equals("cobblestone") || p.equals("deepslate") || p.equals("granite")
                    || p.equals("diorite") || p.equals("andesite") || p.equals("tuff") || p.equals("netherrack")
                    || p.equals("dirt") || p.equals("gravel") || p.equals("sand");
        });
        addDrawableChild(ButtonWidget.builder(Text.literal("Wyniki"), b -> {
            if (blockFiltered.size() > 300) return; // limit pakietu sieciowego
            blockFiltered.forEach(e -> RybaAutoMineClient.selected.add(e.id()));
            RybaAutoMineClient.update();
        }).dimensions(px + PAD + 2 * (bw + 3), by, bw, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Czyść"), b -> {
            RybaAutoMineClient.selected.clear();
            RybaAutoMineClient.update();
        }).dimensions(px + PAD + 3 * (bw + 3), by, bw, 16).build());

        addDrawableChild(new SpeedSlider(px + PAD, py + 104, pw - 2 * PAD, 20));
    }

    private void initUstawienia() {
        int y = py + 58;
        boolean usingHeld = RybaAutoMineClient.toolId.isEmpty();
        addDrawableChild(ButtonWidget.builder(
                Text.literal(usingHeld ? "§a✓ Używaj trzymanego przedmiotu" : "Używaj trzymanego przedmiotu"),
                b -> {
                    RybaAutoMineClient.toolId = "";
                    RybaAutoMineClient.update();
                    reopen();
                }).dimensions(px + PAD, y, pw - 2 * PAD, 16).build());

        TextFieldWidget search = new TextFieldWidget(textRenderer, px + PAD, y + 20, pw - 2 * PAD, 20, Text.literal("Szukaj"));
        search.setPlaceholder(Text.literal("Szukaj narzędzia..."));
        search.setText(toolQuery);
        search.setChangedListener(s -> {
            toolQuery = s.toLowerCase();
            toolFiltered = new ArrayList<>();
            for (Entry e : toolEntries)
                if (e.name().toLowerCase().contains(toolQuery) || e.id().getPath().contains(toolQuery)) toolFiltered.add(e);
            toolScroll = 0;
        });
        addDrawableChild(search);

        int sy = gridY + TOOL_ROWS * CELL + 14;
        addDrawableChild(ButtonWidget.builder(repairLabel(), b -> {
            RybaAutoMineClient.repairMode = switch (RybaAutoMineClient.repairMode) {
                case "none" -> "bottles";
                case "bottles" -> "command";
                default -> "none";
            };
            b.setMessage(repairLabel());
            RybaAutoMineClient.update();
        }).dimensions(px + PAD, sy, pw - 2 * PAD, 18).build());

        addDrawableChild(new RowsSlider(px + PAD, sy + 22, pw - 2 * PAD, 20));
        addDrawableChild(new WidthSlider(px + PAD, sy + 46, pw - 2 * PAD, 20));

        int cy = sy + 70;
        int cbw = (pw - 2 * PAD - 4 * 4) / 5;
        int[] colors = {0x2E9E3E, 0x2E6FDB, 0x8E3EDB, 0xDB8A2E, 0xDB3E3E};
        for (int i = 0; i < colors.length; i++) {
            int col = colors[i];
            addDrawableChild(ButtonWidget.builder(Text.literal("§f⬤"), b -> {
                RybaAutoMineClient.accentColor = col;
                RybaAutoMineClient.update();
            }).dimensions(px + PAD + i * (cbw + 4), cy, cbw, 20).build());
        }
    }

    private Text repairLabel() {
        String s = switch (RybaAutoMineClient.repairMode) {
            case "bottles" -> "§bNaprawa: butelki expa (przy kowadle w zasięgu 20 bl.)";
            case "command" -> "§bNaprawa: komenda /repair (przy kowadle w zasięgu 20 bl.)";
            default -> "§7Naprawa: wyłączona";
        };
        return Text.literal(s);
    }

    private void addPreset(String name, int bw, int i, int y, Predicate<Entry> p) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> {
            for (Entry e : blockEntries) if (p.test(e)) RybaAutoMineClient.selected.add(e.id());
            RybaAutoMineClient.update();
        }).dimensions(px + PAD + i * (bw + 3), y, bw, 16).build());
    }

    private Text toggleText() {
        return Text.literal(RybaAutoMineClient.enabled ? "§l§aWŁĄCZONY" : "§l§cWYŁĄCZONY");
    }

    private List<Entry> currentFiltered() { return tab == Tab.BLOKI ? blockFiltered : toolFiltered; }
    private int currentScroll() { return tab == Tab.BLOKI ? blockScroll : toolScroll; }
    private void setScroll(int v) { if (tab == Tab.BLOKI) blockScroll = v; else toolScroll = v; }

    private int maxScroll() {
        int totalRows = (currentFiltered().size() + COLS - 1) / COLS;
        return Math.max(0, totalRows - rows);
    }

    /** Suwak szybkości kopania: 0.1 - 10.0 bloków/s (skok co 0.1). */
    private static class SpeedSlider extends SliderWidget {
        SpeedSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), (RybaAutoMineClient.speed - 0.1) / 9.9);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Szybkość: §e%.1f bloków/s", RybaAutoMineClient.speed)));
        }

        @Override
        protected void applyValue() {
            double raw = 0.1 + this.value * 9.9;
            double s = Math.round(raw * 10) / 10.0;
            this.value = (s - 0.1) / 9.9;
            if (s != RybaAutoMineClient.speed) {
                RybaAutoMineClient.speed = s;
                RybaAutoMineClient.update();
            }
            updateMessage();
        }
    }

    /** Suwak wysokości listy bloków: 3-12 wierszy. */
    private static class RowsSlider extends SliderWidget {
        RowsSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), (RybaAutoMineClient.guiRows - 3) / 9.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Wysokość listy bloków: §e" + RybaAutoMineClient.guiRows + " wierszy"));
        }

        @Override
        protected void applyValue() {
            int r = 3 + (int) Math.round(this.value * 9);
            this.value = (r - 3) / 9.0;
            if (r != RybaAutoMineClient.guiRows) {
                RybaAutoMineClient.guiRows = r;
                RybaAutoMineClient.update();
            }
            updateMessage();
        }
    }

    /** Suwak szerokości okna: 200-420. */
    private static class WidthSlider extends SliderWidget {
        WidthSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), (RybaAutoMineClient.guiWidth - 200) / 220.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("Szerokość okna: §e" + RybaAutoMineClient.guiWidth + "px"));
        }

        @Override
        protected void applyValue() {
            int w = 200 + (int) Math.round(this.value * 220 / 20) * 20;
            this.value = (w - 200) / 220.0;
            if (w != RybaAutoMineClient.guiWidth) {
                RybaAutoMineClient.guiWidth = w;
                RybaAutoMineClient.update();
            }
            updateMessage();
        }
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        boolean on = RybaAutoMineClient.enabled;
        float pulse = (float) (Math.sin(System.currentTimeMillis() / 350.0) * 0.5 + 0.5);
        int base = 0xFF000000 | RybaAutoMineClient.accentColor;
        int light = lerp(base, 0xFFFFFFFF, 0.55f);
        int dark = lerp(base, 0xFF000000, 0.55f);
        int accent = on ? lerp(base, light, pulse) : 0xFFB03030;

        ctx.fill(px - 3, py - 3, px + pw + 3, py + ph + 3, 0x66000000);
        ctx.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, accent);
        ctx.fillGradient(px, py, px + pw, py + ph, 0xF0161A22, 0xF00B0D12);
        ctx.fillGradient(px, py, px + pw, py + 34, on ? (0xFF000000 | dark) : 0xFF5A1F1F, 0xFF161A22);
        ctx.fill(px, py + 34, px + pw, py + 35, accent);
        ctx.fill(gridX - 2, gridY - 2, gridX + COLS * CELL + 2, gridY + rows * CELL + 2, 0xFF05070A);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);

        ctx.drawText(textRenderer, Text.literal("§l⛏ RybaAutoMine"), px + PAD, py + 7, 0xFFFFFFFF, true);
        String sub = tab == Tab.BLOKI ? "§7Obszar §f13×13×13 §7wokół gracza" : "§7Narzędzie i wygląd GUI";
        ctx.drawText(textRenderer, Text.literal(sub), px + PAD, py + 19, 0xFFFFFFFF, false);

        if (tab == Tab.BLOKI) {
            String st = RybaAutoMineClient.selected.size() + " wybr.";
            ctx.drawText(textRenderer, Text.literal("§e" + st), px + pw - PAD - 70 - 4 - textRenderer.getWidth(st), py + 7, 0xFFFFFFFF, true);
        }

        List<Entry> list = currentFiltered();
        int sc = currentScroll();
        Entry hovered = null;
        int start = sc * COLS;
        for (int i = 0; i < rows * COLS && start + i < list.size(); i++) {
            Entry e = list.get(start + i);
            int x = gridX + (i % COLS) * CELL, y = gridY + (i / COLS) * CELL;
            boolean sel = tab == Tab.BLOKI
                    ? RybaAutoMineClient.selected.contains(e.id())
                    : e.id().toString().equals(RybaAutoMineClient.toolId);
            boolean hov = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
            ctx.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, sel ? 0xAA1F8A32 : 0xFF1B1F27);
            if (sel) ctx.drawBorder(x, y, CELL, CELL, 0xFF55FF55);
            ctx.drawItem(new ItemStack(e.item()), x + 3, y + 3);
            if (hov) {
                ctx.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x55FFFFFF);
                hovered = e;
            }
        }
        if (list.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§7Brak wyników"), px + pw / 2, gridY + rows * CELL / 2 - 4, 0xFFFFFFFF);

        int sbX = gridX + COLS * CELL + 5, sbH = rows * CELL;
        ctx.fill(sbX, gridY, sbX + 5, gridY + sbH, 0xFF05070A);
        if (maxScroll() > 0) {
            int total = (list.size() + COLS - 1) / COLS;
            int thumb = Math.max(14, sbH * rows / total);
            int ty = gridY + (sbH - thumb) * sc / maxScroll();
            ctx.fill(sbX, ty, sbX + 5, ty + thumb, 0xFF8FA0B8);
        }

        if (tab == Tab.BLOKI) {
            int fy = gridY + rows * CELL + 8;
            ctx.fill(px + PAD - 2, fy - 2, px + pw - PAD + 2, fy + 20, 0xFF05070A);
            if (RybaAutoMineClient.selected.isEmpty()) {
                ctx.drawText(textRenderer, Text.literal("§8Kliknij bloki, aby je zaznaczyć"), px + PAD + 2, fy + 5, 0xFFFFFFFF, false);
            } else {
                int shown = 0, maxIcons = (pw - 2 * PAD) / 18;
                for (Identifier id : RybaAutoMineClient.selected) {
                    if (shown >= maxIcons) break;
                    ctx.drawItem(new ItemStack(Registries.BLOCK.get(id).asItem()), px + PAD + shown * 18, fy);
                    shown++;
                }
                int rest = RybaAutoMineClient.selected.size() - shown;
                if (rest > 0)
                    ctx.drawText(textRenderer, Text.literal("§e+" + rest), px + PAD + shown * 18 + 2, fy + 5, 0xFFFFFFFF, false);
            }
        } else {
            int fy = gridY + rows * CELL + 8;
            String cur = RybaAutoMineClient.toolId.isEmpty() ? "§7(trzymany przedmiot)" : "§f" + RybaAutoMineClient.toolId;
            ctx.drawText(textRenderer, Text.literal("§7Wybrane narzędzie: " + cur), px + PAD, fy, 0xFFFFFFFF, false);
        }

        if (hovered != null) {
            Entry e = hovered;
            boolean sel = tab == Tab.BLOKI
                    ? RybaAutoMineClient.selected.contains(e.id())
                    : e.id().toString().equals(RybaAutoMineClient.toolId);
            ctx.drawTooltip(textRenderer, List.of(
                    Text.literal(e.name()),
                    Text.literal("§8" + e.id()),
                    Text.literal(sel ? "§aWybrany §7- kliknij, by odznaczyć" : "§7Kliknij, by wybrać")
            ), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        int col = (int) Math.floor((mx - gridX) / CELL), row = (int) Math.floor((my - gridY) / CELL);
        if (mx >= gridX && my >= gridY && col < COLS && row < rows) {
            List<Entry> list = currentFiltered();
            int idx = (currentScroll() + row) * COLS + col;
            if (idx < list.size()) {
                Identifier id = list.get(idx).id();
                if (tab == Tab.BLOKI) {
                    if (!RybaAutoMineClient.selected.remove(id)) RybaAutoMineClient.selected.add(id);
                } else {
                    String s = id.toString();
                    RybaAutoMineClient.toolId = s.equals(RybaAutoMineClient.toolId) ? "" : s;
                }
                RybaAutoMineClient.update();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        setScroll(MathHelper.clamp(currentScroll() - (int) Math.signum(v), 0, maxScroll()));
        return true;
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
