package com.epic60869.sky2m;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;

public final class Sky2MRngHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("sky2m-mod", "farming_rng");
    private static final int PADDING = 3;
    private static final int LINE_HEIGHT = 11;
    private static Sky2MConfig config;

    private Sky2MRngHud() {}

    public static void register(Sky2MConfig cfg) {
        config = cfg;
        HudElementRegistry.addLast(ID, Sky2MRngHud::extract);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> clientTicks++);
    }

    private static long clientTicks;
    private static long linesTick = -1;
    private static List<Line> tickLines = List.of();

    /**
     * {@link #computeLines} once per client tick: extract, width() and height() each asked for it every frame, and
     * it copies/prunes the RNG drops and builds new Components every time.
     */
    private static List<Line> lines() {
        if (linesTick != clientTicks) {
            tickLines = computeLines();
            linesTick = clientTicks;
        }
        return tickLines;
    }

    /** One line, SkyHanni Item Pickup Log style: "+ 2x Chimera I (25.7M)". */
    private record Line(Component text) {}

    /**
     * Everything the RNG HUD shows: farming RNG drops, then Item Notification items (one HUD for both). An item that
     * is already on the HUD as an RNG drop isn't listed a second time.
     */
    private static List<Line> computeLines() {
        List<Line> out = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        if (config != null && config.farming.rng.enabled) {
            for (FarmingRngTracker.Drop drop : FarmingRngTracker.get().active()) {
                out.add(line(drop.amount(), Component.literal(drop.name()).withColor(rarityColour(drop)),
                    drop.unitPrice() * drop.amount()));
                names.add(drop.name().toLowerCase(Locale.ROOT));
            }
        }
        var notify = config == null ? null : config.misc.itemNotification;
        boolean rarity = notify == null || notify.rarityColour;
        int nameColour = colour(notify == null ? null : notify.nameColour, 0xFFFFFFFF);
        for (com.epic60869.sky2m.features.misc.ItemNotification.Row row : com.epic60869.sky2m.features.misc.ItemNotification.rows()) {
            if (names.contains(row.plain().toLowerCase(Locale.ROOT))) continue;
            Component name = rarity ? Component.literal(row.name())
                : Component.literal(row.plain()).withColor(nameColour);
            out.add(line(row.amount(), name, Math.round(row.value())));
        }
        return out;
    }

    /** "+ " in green, "2x " in grey when more than one, the name, then "(25.7M)" when the value is known. */
    private static Line line(long amount, Component name, long value) {
        MutableComponent text = Component.literal("+ ").withStyle(ChatFormatting.GREEN);
        if (amount > 1) text.append(Component.literal(amount + "x ").withStyle(ChatFormatting.GRAY));
        text.append(name);
        if (value > 0) {
            text.append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatCoins(value)).withColor(priceColour()))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }
        return new Line(text);
    }

    private static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        if (config == null || Minecraft.getInstance().player == null) return;
        List<Line> drops = lines();
        if (drops.isEmpty()) return;
        render(graphics, drops, com.epic60869.sky2m.features.core.Sky2MHuds.mapX(positionX(), width()), com.epic60869.sky2m.features.core.Sky2MHuds.mapY(positionY(), height()));
    }

    private static List<Line> preview() {
        return List.of(
            line(1, Component.literal("Chimera I").withStyle(ChatFormatting.LIGHT_PURPLE), 25_700_000L),
            line(2, Component.literal("Designer Coffee Beans").withColor(0xFF55FFFF), 1_000_000L),
            line(1, Component.literal("Legendary Slug Pet").withColor(0xFFFFAA00), 5_000_000L),
            line(5, Component.literal("Enchanted Diamond").withStyle(ChatFormatting.BLUE), 8_500L)
        );
    }

    private static List<Line> shown() {
        List<Line> active = lines();
        return active.isEmpty() ? preview() : active;
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
        for (Line drop : drops) w = Math.max(w, PADDING + font.width(drop.text()) + PADDING);
        return Math.max(40, w);
    }

    private static int contentHeight(List<Line> drops) {
        return PADDING + drops.size() * LINE_HEIGHT;
    }

    /** Big drops in their chat colour; slug pets in their rarity colour (Epic purple, Legendary gold) if turned on; everything else the Drop Colour. */
    private static int rarityColour(FarmingRngTracker.Drop drop) {
        if (drop.colour() != -1) return drop.colour();
        boolean pets = config == null || config.farming.rng.petRarityColours;
        if (pets && drop.rarity().equals("LEGENDARY")) return 0xFFFFAA00;
        if (pets && drop.rarity().equals("EPIC")) return 0xFFAA00AA;
        return colour(config == null ? null : config.farming.rng.dropColour, 0xFF55FFFF);
    }

    private static int priceColour() {
        return colour(config == null ? null : config.farming.rng.valueColour, 0xFFFFAA00);
    }

    private static int colour(String special, int fallback) {
        if (special == null || special.isEmpty()) return fallback;
        try {
            return 0xFF000000 | com.epic60869.sky2m.custom.util.ChromaColours.parse(special).getEffectiveColourRGB();
        } catch (Exception e) {
            return fallback;
        }
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
            graphics.text(Minecraft.getInstance().font, drop.text(), PADDING, yOffset, 0xFFFFFFFF, true);
            yOffset += LINE_HEIGHT;
        }

        graphics.pose().popMatrix();
    }

    /** SkyHanni style: "25.7M", "1.2B", "850K". */
    private static String formatCoins(long value) {
        if (value >= 1_000_000_000L) return compactNumber(value / 1_000_000_000.0, "B");
        if (value >= 1_000_000L) return compactNumber(value / 1_000_000.0, "M");
        if (value >= 1_000L) return compactNumber(value / 1_000.0, "K");
        return Long.toString(value);
    }

    private static String compactNumber(double value, String suffix) {
        return String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", "") + suffix;
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        config.save(mc.gameDirectory.toPath().resolve("config").resolve("sky2m-mod.json"));
    }
}
