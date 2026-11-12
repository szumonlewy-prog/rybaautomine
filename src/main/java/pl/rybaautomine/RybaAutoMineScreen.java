package pl.rybaautomine;

import net.minecraft.block.Block;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
    private static final int COLS = 9, CELL = 22, PW = 240, PAD = 12;

    private final List<Block> allBlocks = new ArrayList<>();
    private List<Block> filtered = new ArrayList<>();
    private String query = "";
    private int scroll = 0;
    private int px, py, ph, rows, gridX, gridY;
    private ButtonWidget toggleBtn;

    public RybaAutoMineScreen() {
        super(Text.literal("RybaAutoMine"));
        for (Block b : Registries.BLOCK)
            if (b.asItem() != Items.AIR) allBlocks.add(b); // tylko bloki z ikoną
        allBlocks.sort(Comparator.comparing(b -> b.getName().getString()));
        filtered = new ArrayList<>(allBlocks);
    }

    @Override
    protected void init() {
        rows = MathHelper.clamp((height - 150) / CELL, 3, 8);
        ph = 130 + rows * CELL;
        px = (width - PW) / 2;
        py = (height - ph) / 2;
        gridX = px + PAD;
        gridY = py + 90;

        toggleBtn = addDrawableChild(ButtonWidget.builder(toggleText(), b -> {
            RybaAutoMineClient.enabled = !RybaAutoMineClient.enabled;
            b.setMessage(toggleText());
            RybaAutoMineClient.update();
        }).dimensions(px + PAD, py + 40, 84, 20).build());

        TextFieldWidget search = new TextFieldWidget(textRenderer, px + PAD + 88, py + 40, PW - 2 * PAD - 88, 20, Text.literal("Szukaj"));
        search.setPlaceholder(Text.literal("Szukaj bloku..."));
        search.setText(query);
        search.setChangedListener(s -> {
            query = s.toLowerCase();
            filtered = new ArrayList<>();
            for (Block b : allBlocks)
                if (b.getName().getString().toLowerCase().contains(query)
                        || Registries.BLOCK.getId(b).getPath().contains(query)) filtered.add(b);
            scroll = 0;
        });
        addDrawableChild(search);

        int bw = (PW - 2 * PAD - 3 * 3) / 4;
        int by = py + 64;
        addPreset("Rudy", bw, 0, by, b -> {
            String p = Registries.BLOCK.getId(b).getPath();
            return p.endsWith("_ore") || p.equals("ancient_debris") || p.equals("nether_quartz_ore");
        });
        addPreset("Kamień", bw, 1, by, b -> {
            String p = Registries.BLOCK.getId(b).getPath();
            return p.equals("stone") || p.equals("cobblestone") || p.equals("deepslate") || p.equals("granite")
                    || p.equals("diorite") || p.equals("andesite") || p.equals("tuff") || p.equals("netherrack")
                    || p.equals("dirt") || p.equals("gravel") || p.equals("sand");
        });
        addDrawableChild(ButtonWidget.builder(Text.literal("Wyniki"), b -> {
            if (filtered.size() > 300) return; // limit pakietu sieciowego
            filtered.forEach(bl -> RybaAutoMineClient.selected.add(Registries.BLOCK.getId(bl)));
            RybaAutoMineClient.update();
        }).dimensions(px + PAD + 2 * (bw + 3), by, bw, 16).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Czyść"), b -> {
            RybaAutoMineClient.selected.clear();
            RybaAutoMineClient.update();
        }).dimensions(px + PAD + 3 * (bw + 3), by, bw, 16).build());
    }

    private void addPreset(String name, int bw, int i, int y, Predicate<Block> p) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> {
            for (Block bl : allBlocks) if (p.test(bl)) RybaAutoMineClient.selected.add(Registries.BLOCK.getId(bl));
            RybaAutoMineClient.update();
        }).dimensions(px + PAD + i * (bw + 3), y, bw, 16).build());
    }

    private Text toggleText() {
        return Text.literal(RybaAutoMineClient.enabled ? "§l§aWŁĄCZONY" : "§l§cWYŁĄCZONY");
    }

    private int maxScroll() {
        int totalRows = (filtered.size() + COLS - 1) / COLS;
        return Math.max(0, totalRows - rows);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        boolean on = RybaAutoMineClient.enabled;
        float pulse = (float) (Math.sin(System.currentTimeMillis() / 350.0) * 0.5 + 0.5);
        int accent = on ? lerp(0xFF2E9E3E, 0xFF6BFF7A, pulse) : 0xFFB03030;

        // cień + ramka + tło panelu
        ctx.fill(px - 3, py - 3, px + PW + 3, py + ph + 3, 0x66000000);
        ctx.fill(px - 1, py - 1, px + PW + 1, py + ph + 1, accent);
        ctx.fillGradient(px, py, px + PW, py + ph, 0xF0161A22, 0xF00B0D12);
        // nagłówek
        ctx.fillGradient(px, py, px + PW, py + 34, on ? 0xFF1F5A2B : 0xFF5A1F1F, 0xFF161A22);
        ctx.fill(px, py + 34, px + PW, py + 35, accent);
        // siatka (tło)
        ctx.fill(gridX - 2, gridY - 2, gridX + COLS * CELL + 2, gridY + rows * CELL + 2, 0xFF05070A);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);

        ctx.drawText(textRenderer, Text.literal("§l⛏ RybaAutoMine"), px + PAD, py + 9, 0xFFFFFFFF, true);
        ctx.drawText(textRenderer, Text.literal("§7Obszar §f13×13×13 §7wokół gracza"), px + PAD, py + 21, 0xFFFFFFFF, false);
        String st = RybaAutoMineClient.selected.size() + " wybr.";
        ctx.drawText(textRenderer, Text.literal("§e" + st), px + PW - PAD - textRenderer.getWidth(st), py + 9, 0xFFFFFFFF, true);

        // sloty
        Block hovered = null;
        int start = scroll * COLS;
        for (int i = 0; i < rows * COLS && start + i < filtered.size(); i++) {
            Block b = filtered.get(start + i);
            int x = gridX + (i % COLS) * CELL, y = gridY + (i / COLS) * CELL;
            boolean sel = RybaAutoMineClient.selected.contains(Registries.BLOCK.getId(b));
            boolean hov = mx >= x && mx < x + CELL && my >= y && my < y + CELL;
            ctx.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, sel ? 0xAA1F8A32 : 0xFF1B1F27);
            if (sel) ctx.drawBorder(x, y, CELL, CELL, 0xFF55FF55);
            ctx.drawItem(new ItemStack(b.asItem()), x + 3, y + 3);
            if (hov) {
                ctx.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0x55FFFFFF);
                hovered = b;
            }
        }
        if (filtered.isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§7Brak wyników"), px + PW / 2, gridY + rows * CELL / 2 - 4, 0xFFFFFFFF);

        // pasek przewijania
        int sbX = gridX + COLS * CELL + 5, sbH = rows * CELL;
        ctx.fill(sbX, gridY, sbX + 5, gridY + sbH, 0xFF05070A);
        if (maxScroll() > 0) {
            int total = (filtered.size() + COLS - 1) / COLS;
            int thumb = Math.max(14, sbH * rows / total);
            int ty = gridY + (sbH - thumb) * scroll / maxScroll();
            ctx.fill(sbX, ty, sbX + 5, ty + thumb, 0xFF8FA0B8);
        }

        // stopka: ikony wybranych bloków
        int fy = gridY + rows * CELL + 8;
        ctx.fill(px + PAD - 2, fy - 2, px + PW - PAD + 2, fy + 20, 0xFF05070A);
        if (RybaAutoMineClient.selected.isEmpty()) {
            ctx.drawText(textRenderer, Text.literal("§8Kliknij bloki, aby je zaznaczyć"), px + PAD + 2, fy + 5, 0xFFFFFFFF, false);
        } else {
            int shown = 0, maxIcons = 11;
            for (Identifier id : RybaAutoMineClient.selected) {
                if (shown >= maxIcons) break;
                ctx.drawItem(new ItemStack(Registries.BLOCK.get(id).asItem()), px + PAD + shown * 18, fy);
                shown++;
            }
            int rest = RybaAutoMineClient.selected.size() - shown;
            if (rest > 0)
                ctx.drawText(textRenderer, Text.literal("§e+" + rest), px + PAD + shown * 18 + 2, fy + 5, 0xFFFFFFFF, false);
        }

        if (hovered != null) {
            Identifier id = Registries.BLOCK.getId(hovered);
            boolean sel = RybaAutoMineClient.selected.contains(id);
            ctx.drawTooltip(textRenderer, List.of(
                    hovered.getName(),
                    Text.literal("§8" + id),
                    Text.literal(sel ? "§aZaznaczony §7- kliknij, by odznaczyć" : "§7Kliknij, by zaznaczyć")
            ), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button != 0) return false;
        int col = (int) Math.floor((mx - gridX) / CELL), row = (int) Math.floor((my - gridY) / CELL);
        if (mx >= gridX && my >= gridY && col < COLS && row < rows) {
            int idx = (scroll + row) * COLS + col;
            if (idx < filtered.size()) {
                Identifier id = Registries.BLOCK.getId(filtered.get(idx));
                if (!RybaAutoMineClient.selected.remove(id)) RybaAutoMineClient.selected.add(id);
                RybaAutoMineClient.update();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = MathHelper.clamp(scroll - (int) Math.signum(v), 0, maxScroll());
        return true;
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
