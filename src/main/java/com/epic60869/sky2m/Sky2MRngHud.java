package com.epic60869.sky2m;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
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
    /** The settings passed at startup; {@link #config()} prefers the current ones (they are replaced when settings are imported). */
    private static Sky2MConfig registered;

    private static Sky2MConfig config() {
        Sky2MConfig current = Sky2MConfig.current();
        return current != null ? current : registered;
    }

    private Sky2MRngHud() {}

    public static void register(Sky2MConfig cfg) {
        registered = cfg;
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, ID, Sky2MRngHud::extract);
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
        if (config() != null && config().misc.rngHud.enabled) {
            for (FarmingRngTracker.Drop drop : FarmingRngTracker.get().active()) {
                out.add(rngLine(drop.amount(), Component.literal(drop.name()).withColor(rarityColour(drop)),
                    drop.unitPrice() * drop.amount()));
                names.add(drop.name().toLowerCase(Locale.ROOT));
            }
        }
        var notify = config() == null ? null : config().inventory.itemNotification;
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

    /** An RNG drop like SkyHanni's title: the name in its rarity colour, then "(+851.0k)" ("2x " first when more than one). */
    private static Line rngLine(long amount, Component name, long value) {
        MutableComponent text = Component.empty();
        if (amount > 1) text.append(Component.literal(amount + "x ").withStyle(ChatFormatting.GRAY));
        text.append(name);
        if (value > 0) {
            text.append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("+" + skyHanniCoins(value)).withColor(priceColour()))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }
        return new Line(text);
    }

    private static void extract(GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker deltaTracker) {
        if (config() == null || Minecraft.getInstance().player == null) return;
        List<Line> drops = lines();
        if (drops.isEmpty()) return;
        render(graphics, drops, com.epic60869.sky2m.features.core.Sky2MHuds.mapX(positionX(), width()), com.epic60869.sky2m.features.core.Sky2MHuds.mapY(positionY(), height()));
    }

    private static List<Line> preview() {
        return List.of(
            rngLine(1, Component.literal("Lucky Clover Core").withStyle(ChatFormatting.DARK_PURPLE), 851_000L),
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

    /**
     * The drop's rarity colour, as SkyHanni colours it: slug pets in their rarity (Epic purple, Legendary gold) if
     * turned on, then the item's own rarity from the item repo (Shriveled Wasp is Legendary gold, Lucky Clover Core
     * Epic purple), then the colour its name had in the chat message (pets, which the repo lists per rarity), then the
     * Drop Colour.
     */
    private static int rarityColour(FarmingRngTracker.Drop drop) {
        boolean pets = config() == null || config().misc.rngHud.petRarityColours;
        if (pets && drop.rarity().equals("LEGENDARY")) return 0xFFFFAA00;
        if (pets && drop.rarity().equals("EPIC")) return 0xFFAA00AA;
        Integer tier = ITEM_COLOURS.computeIfAbsent(drop.name().toLowerCase(Locale.ROOT), name -> {
            String id = com.epic60869.sky2m.custom.RepoItems.idByName(drop.name());
            String rarity = id == null ? null : com.epic60869.sky2m.custom.RepoItems.tier(id);
            var rgb = rarity == null ? null : net.minecraft.network.chat.TextColor.fromLegacyFormat(com.epic60869.sky2m.custom.RepoItems.tierColour(rarity));
            return rgb == null ? null : 0xFF000000 | rgb.getValue();
        });
        if (tier != null) return tier;
        if (drop.colour() != -1) return drop.colour();
        return colour(config() == null ? null : config().misc.rngHud.dropColour, 0xFF55FFFF);
    }

    /** Item name -> rarity colour, looked up once per item (unknown items aren't stored, so they're tried again). */
    private static final java.util.Map<String, Integer> ITEM_COLOURS = new java.util.concurrent.ConcurrentHashMap<>();

    private static int priceColour() {
        return colour(config() == null ? null : config().misc.rngHud.valueColour, 0xFFFFAA00);
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
        return config() == null ? 1.0f : config().misc.rngHud.scale;
    }

    public static int x() { return positionX(); }
    public static int y() { return positionY(); }

    private static int positionX() {
        return config() == null ? 8 : config().misc.rngHud.x;
    }

    private static int positionY() {
        return config() == null ? 8 : config().misc.rngHud.y;
    }

    public static void setPosition(int x, int y) {
        if (config() == null) return;
        config().misc.rngHud.x = Math.max(0, x);
        config().misc.rngHud.y = Math.max(0, y);
        save();
    }

    public static void setScale(float value) {
        if (config() == null) return;
        config().misc.rngHud.scale = Math.max(0.5f, Math.min(3.0f,
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
        if (config() != null && config().misc.rngHud.background) {
            graphics.fill(0, 0, w, h, 0xA8000000);
            graphics.fill(0, 0, w, 1, 0x55FFFFFF);
        }

        // Each line centred in the HUD, like SkyHanni's.
        var font = Minecraft.getInstance().font;
        int yOffset = PADDING;
        for (Line drop : drops) {
            graphics.text(font, drop.text(), (w - font.width(drop.text())) / 2, yOffset, 0xFFFFFFFF, true);
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

    /** SkyHanni's title numbers: "851.0k", "25.7M", "1.2B". */
    private static String skyHanniCoins(long value) {
        if (value >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return Long.toString(value);
    }

    private static String compactNumber(double value, String suffix) {
        return String.format(Locale.ROOT, "%.1f", value).replaceAll("\\.0$", "") + suffix;
    }

    private static void save() {
        Minecraft mc = Minecraft.getInstance();
        config().save(mc.gameDirectory.toPath().resolve("config").resolve("sky2m-mod.json"));
    }
}
