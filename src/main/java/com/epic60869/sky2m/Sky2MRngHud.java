package com.epic60869.sky2m;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

public final class Sky2MRngHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("sky2m-mod", "farming_rng");
    private static final int PADDING = 4;
    private static final int LINE_HEIGHT = 14;
    private static Sky2MConfig config;

    private Sky2MRngHud() {}

    public static void register(Sky2MConfig cfg) {
        config = cfg;
        HudElementRegistry.addLast(ID, Sky2MRngHud::extract);
    }

    /** One line: the item text (may carry colour codes), its colour, its price. */
    private record Line(String item, int colour, String price) {}

    /**
     * Everything the RNG HUD shows: farming RNG drops, then Item Notification items (one HUD for both). An item that
     * is already on the HUD as an RNG drop isn't listed a second time.
     */
    private static List<Line> lines() {
        List<Line> out = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        if (config != null && config.farming.rng.enabled) {
            for (FarmingRngTracker.Drop drop : FarmingRngTracker.get().active()) {
                out.add(new Line(itemText(drop), rarityColour(drop), priceText(drop)));
                names.add(drop.name().toLowerCase(Locale.ROOT));
            }
        }
        var notify = config == null ? null : config.misc.itemNotification;
        boolean rarity = notify == null || notify.rarityColour;
        int nameColour = colour(notify == null ? null : notify.nameColour, 0xFFFFFFFF);
        for (com.epic60869.sky2m.features.misc.ItemNotification.Row row : com.epic60869.sky2m.features.misc.ItemNotification.rows()) {
            if (names.contains(row.plain().toLowerCase(Locale.ROOT))) continue;
            String item = rarity ? row.item() : net.minecraft.ChatFormatting.stripFormatting(row.item());
            out.add(new Line(item, rarity ? 0xFFFFFFFF : nameColour, row.price()));
        }
        return out;
    }

    private static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        if (config == null || Minecraft.getInstance().player == null) return;
        List<Line> drops = lines();
        if (drops.isEmpty()) return;
        render(graphics, drops, com.epic60869.sky2m.features.core.Sky2MHuds.mapX(positionX(), width()), com.epic60869.sky2m.features.core.Sky2MHuds.mapY(positionY(), height()));
    }

    private static final List<Line> PREVIEW = List.of(
        new Line("1x Crystalized Moonlight", 0xFF55FFFF, "500k"),
        new Line("2x Designer Coffee Beans", 0xFF55FFFF, "1m"),
        new Line("1x Legendary Slug Pet", 0xFFFFAA00, "5m"),
        new Line("5x §9Enchanted Diamond", 0xFFFFFFFF, "8.5k")
    );

    private static List<Line> shown() {
        List<Line> active = lines();
        return active.isEmpty() ? PREVIEW : active;
    }

    /** Scaled on-screen width, matching exactly what is drawn. */
    public static int width() {
        return Math.max(1, Math.round(contentWidth(shown()) * scale()));
    }

    /** Scaled on-screen height, matching exactly what is drawn. */
    public static int height() {
        return Math.max(1, Math.round(contentHeight(shown()) * scale()));
    }

    private static int contentWidth(List<Line> drops) {
        var font = Minecraft.getInstance().font;
        int w = 0;
        for (Line drop : drops) {
            w = Math.max(w, PADDING + font.width(drop.item()) + 8 + font.width(drop.price()) + PADDING);
        }
        return Math.max(40, w);
    }

    private static int contentHeight(List<Line> drops) {
        return PADDING + drops.size() * LINE_HEIGHT;
    }

    /** Slug pets in their rarity colour (Epic purple, Legendary gold) if turned on; everything else the Drop Colour. */
    private static int rarityColour(FarmingRngTracker.Drop drop) {
        boolean pets = config == null || config.farming.rng.petRarityColours;
        if (pets && drop.rarity().equals("LEGENDARY")) return 0xFFFFAA00;
        if (pets && drop.rarity().equals("EPIC")) return 0xFFAA00AA;
        return colour(config == null ? null : config.farming.rng.dropColour, 0xFF55FFFF);
    }

    private static int colour(String special, int fallback) {
        if (special == null || special.isEmpty()) return fallback;
        try {
            return 0xFF000000 | com.epic60869.sky2m.custom.util.ChromaColours.parse(special).getEffectiveColourRGB();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String itemText(FarmingRngTracker.Drop drop) {
        return drop.amount() + "x " + drop.name();
    }

    private static String priceText(FarmingRngTracker.Drop drop) {
        return drop.unitPrice() < 0 ? "—" : formatCoins(drop.unitPrice() * drop.amount());
    }

    public static float scale() {
        return config == null ? 1.0f : config.farming.rng.scale;
    }

    public static int x() { return positionX(); }
    public static int y() { return positionY(); }

    private static int positionX() {
        return config == null ? 8 : config.farming.rng.x;
    }

    private static int positionY() {
        return config == null ? 8 : config.farming.rng.y;
    }

    public static void setPosition(int x, int y) {
        if (config == null) return;
        config.farming.rng.x = Math.max(0, x);
        config.farming.rng.y = Math.max(0, y);
        save();
    }

    public static void setScale(float value) {
        if (config == null) return;
        config.farming.rng.scale = Math.max(0.5f, Math.min(3.0f,
            Math.round(value * 10.0f) / 10.0f));
        save();
    }

    public static void changeScale(float amount) {
        setScale(scale() + amount);
    }

    public static String scaleText() {
        return String.format(Locale.ROOT, "%.1fx", scale());
    }

    public static void renderPreview(GuiGraphicsExtractor graphics, int x, int y) {
        render(graphics, shown(), x, y);
    }

    private static void render(GuiGraphicsExtractor graphics,
                               List<Line> drops,
                               int x,
                               int y) {
        float s = scale();
        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale(s, s);

        // The background fills exactly the HUD bounds so it can sit flush against a screen edge.
        int w = contentWidth(drops);
        int h = contentHeight(drops);
        if (config != null && config.farming.rng.background) {
            graphics.fill(0, 0, w, h, 0xA8000000);
            graphics.fill(0, 0, w, 1, 0x55FFFFFF);
        }

        int yOffset = PADDING;
        for (Line drop : drops) {
            String item = drop.item();
            String price = drop.price();

            // One complete drop per line: amount, item name, then total value. Names with colour codes (Item
            // Notification) use the normal shadow so the codes don't tint the shadow.
            if (item.indexOf('\u00a7') >= 0) graphics.text(Minecraft.getInstance().font, item, PADDING, yOffset, drop.colour(), true);
            else drawShadowed(graphics, item, PADDING, yOffset, drop.colour(), true);
            drawShadowed(graphics, price, w - PADDING - Minecraft.getInstance().font.width(price), yOffset,
                colour(config == null ? null : config.farming.rng.priceColour, 0xFFB8B8B8), false);
            yOffset += LINE_HEIGHT;
        }

        graphics.pose().popMatrix();
    }

    private static void drawShadowed(GuiGraphicsExtractor graphics,
                                     String text,
                                     int x,
                                     int y,
                                     int color,
                                     boolean bold) {
        var font = Minecraft.getInstance().font;
        graphics.text(font, text, x + 1, y + 1, 0xAA000000, false);
        graphics.text(font, text, x, y, color, bold);
    }

    private static String formatCoins(long value) {
        if (value >= 1_000_000_000L) {
            return compactNumber(value / 1_000_000_000.0, "b");
        }
        if (value >= 1_000_000L) {
            return compactNumber(value / 1_000_000.0, "m");
        }
        if (value >= 1_000L) {
            return compactNumber(value / 1_000.0, "k");
        }
        return Long.toString(value);
    }

    private static String compactNumber(double value, String suffix) {
        String formatted = String.format(Locale.ROOT, "%.2f", value)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
        return formatted + suffix;
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        config.save(mc.gameDirectory.toPath().resolve("config").resolve("sky2m-mod.json"));
    }
}
