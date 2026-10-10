package com.epic60869.sky2m;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.epic60869.sky2m.mixin.Sky2MPlayerTabOverlayAccessor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class Sky2MCommissionHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("sky2m", "commissions");
    private static final Pattern COMM_PATTERN =
        Pattern.compile("(?<name>.+?):\\s*(?<progress>DONE|[0-9,.]+\\s*/\\s*[0-9,.]+|[0-9]+(?:\\.[0-9]+)?%?)",
            Pattern.CASE_INSENSITIVE);

    private static Sky2MConfig config;
    private static List<Commission> commissions = List.of();

    private Sky2MCommissionHud() {}

    public static void register(Sky2MConfig cfg) {
        config = cfg;
        HudElementRegistry.addLast(ID, Sky2MCommissionHud::extract);
    }

    public static int x() { return config == null ? 8 : config.mining.commissions.x; }
    public static int y() { return config == null ? 80 : config.mining.commissions.y; }
    public static float scale() { return config == null ? 1.0f : config.mining.commissions.scale; }
    /** Scaled on-screen width of the HUD, matching exactly what is drawn. */
    public static int width() { return Math.round(frameWidth(shown()) * scale()); }
    /** Scaled on-screen height of the HUD, matching exactly what is drawn. */
    public static int height() { return Math.round(frameHeight(shown()) * scale()); }

    private static List<Commission> shown() {
        return commissions.isEmpty() ? PREVIEW : commissions;
    }

    public static void setPosition(int x, int y) {
        if (config == null) return;
        config.mining.commissions.x = Math.max(0, x);
        config.mining.commissions.y = Math.max(0, y);
        save();
    }

    // Same sample content as Skyblocker's commissions widget preview.
    private static final List<Commission> PREVIEW = List.of(
        new Commission("Commission 1", "0%", 0),
        new Commission("Commission 2", "50%", 50),
        new Commission("Commission 3", "DONE", 100)
    );

    public static void renderPreview(GuiGraphicsExtractor g, int x, int y) {
        render(g, shown(), x, y);
    }

    private static void extract(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (config == null || !config.mining.commissions.enabled || mc.player == null) return;
        if (config.mining.features.commissionsAreaOnly
            && !com.epic60869.sky2m.features.core.Sky2MLocation.inMiningIsland()) return;
        updateFromTab();
        if (!commissions.isEmpty()) render(g, commissions, com.epic60869.sky2m.features.core.Sky2MHuds.mapX(x(), width()), com.epic60869.sky2m.features.core.Sky2MHuds.mapY(y(), height()));
    }

    private static void updateFromTab() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            commissions = List.of();
            return;
        }

        List<PlayerInfo> entries = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        try {
            entries.sort(Sky2MPlayerTabOverlayAccessor.getOrdering());
        } catch (Throwable ignored) {}

        /*
         * Do not infer commissions from the generic TAB widget names. Hypixel's
         * current SkyBlock TAB format can put resource widgets such as
         * "Gemstone:" immediately beside/above the real Commissions widget.
         * Skyblocker's CommsWidget consumes ONLY the lines belonging to the
         * explicit "Commissions" header, so do the same here.
         */
        List<Commission> found = new ArrayList<>();
        boolean inCommissions = false;
        boolean sawHeader = false;

        for (PlayerInfo entry : entries) {
            Component display = com.epic60869.sky2m.custom.util.Compat.rawTabName(entry);
            if (display == null && entry.getProfile() != null) {
                display = Component.literal(entry.getProfile().name());
            }
            if (display == null) continue;

            String rawLine = display.getString();
            String line = rawLine.strip();
            if (line.isBlank()) continue;

            String lower = line.toLowerCase(java.util.Locale.ROOT);

            if (!inCommissions) {
                if (lower.equals("commissions") || lower.equals("commissions:")) {
                    inCommissions = true;
                    sawHeader = true;
                    continue;
                }
                if (lower.startsWith("commissions:")) {
                    inCommissions = true;
                    sawHeader = true;
                    line = line.substring("commissions:".length()).strip();
                    if (line.isBlank()) continue;
                } else {
                    continue;
                }
            }

            // A new top-level TAB widget ends the Commissions block.
            if (!rawLine.startsWith(" ") && line.contains(":")) {
                Matcher possible = COMM_PATTERN.matcher(line);
                if (!possible.matches()) break;
            }

            Matcher matcher = COMM_PATTERN.matcher(line);
            if (!matcher.matches()) continue;

            String name = matcher.group("name").strip();
            String progress = matcher.group("progress").strip();
            if (name.isBlank() || progress.isBlank()) continue;

            String lowerName = name.toLowerCase(java.util.Locale.ROOT);
            if (lowerName.equals("gemstone") || lowerName.equals("mithril")
                || lowerName.equals("glacite") || lowerName.equals("powder")
                || lowerName.equals("bank") || lowerName.equals("purse")
                || lowerName.equals("fairy souls") || lowerName.equals("skills")
                || lowerName.equals("slayer") || lowerName.equals("pets")
                || lowerName.equals("profile")) {
                continue;
            }

            if (progress.equalsIgnoreCase("DONE")) {
                found.add(new Commission(name, "DONE", 100f));
            } else {
                float percent;
                String shown = progress;
                try {
                    if (progress.contains("/")) {
                        String[] parts = progress.split("/", 2);
                        double current = Double.parseDouble(parts[0].replace(",", "").trim());
                        double total = Double.parseDouble(parts[1].replace(",", "").trim());
                        if (total <= 0) continue;
                        percent = (float) Math.max(0, Math.min(100, current * 100.0 / total));
                    } else {
                        String numeric = progress.endsWith("%")
                            ? progress.substring(0, progress.length() - 1)
                            : progress;
                        percent = Math.max(0f, Math.min(100f, Float.parseFloat(numeric)));
                        if (!progress.endsWith("%")) shown = progress + "%";
                    }
                } catch (NumberFormatException ignored) {
                    continue;
                }
                found.add(new Commission(name, shown, percent));
            }

            if (found.size() >= 4) break;
        }

        commissions = sawHeader ? found : List.of();
    }

    /*
     * Rendering follows Skyblocker's "Fancy" tab HUD style for its CommsWidget
     * (ElementBasedWidget + ProgressElement): a bordered frame with the title in
     * the top border, and per commission a book icon, the name and a coloured bar.
     */
    private static final Component TITLE = Component.literal("Commissions").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD);
    private static final int FRAME_COLOR = 0xFF000000 | 0x00AAAA;
    private static final int BORDER_N = 9 + 2, BORDER_S = 4, BORDER_W = 4, BORDER_E = 4;
    private static final int PAD_S = 2, PAD_L = 2, ICON = 16, BAR_WIDTH = 100, BAR_HEIGHT = 9 + 3;
    private static final int ELEMENT_HEIGHT = 9 + PAD_S + 2 + 9 + 2;
    // Created lazily: this class loads during mod init, before item components are bound.
    private static ItemStack book;

    private static int elementWidth(Commission c) {
        return ICON + PAD_L + Math.max(BAR_WIDTH, Minecraft.getInstance().font.width(c.name));
    }

    private static int frameWidth(List<Commission> list) {
        int w = 0;
        for (Commission c : list) w = Math.max(w, elementWidth(c) + PAD_S);
        w += BORDER_E + BORDER_W;
        return Math.max(w, BORDER_W + BORDER_E + Minecraft.getInstance().font.width(TITLE) + 4 + 4 + 1);
    }

    private static int frameHeight(List<Commission> list) {
        int h = list.size() * (ELEMENT_HEIGHT + PAD_L);
        h -= PAD_L / 2;
        return h + BORDER_N + BORDER_S - 2;
    }

    private static void render(GuiGraphicsExtractor g, List<Commission> list, int x, int y) {
        var font = Minecraft.getInstance().font;
        int w = frameWidth(list);
        int h = frameHeight(list);
        float scale = scale();
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().scale(scale, scale);

        if (config == null || config.mining.commissions.background) {
            int bg = Minecraft.getInstance().options.getBackgroundColor(ARGB.black(0.75f));
            // Rounded corners
            g.fill(1, 0, w - 1, h, bg);
            g.fill(0, 1, 1, h - 1, bg);
            g.fill(w - 1, 1, w, h - 1, bg);
        }

        int titleWidth = font.width(TITLE);
        int titleHalf = font.lineHeight / 2;
        g.text(font, TITLE, 8, 2, FRAME_COLOR, false);
        g.fill(2, 1 + titleHalf, 6, 2 + titleHalf, FRAME_COLOR);
        g.fill(2 + titleWidth + 8, 1 + titleHalf, w - 2, 2 + titleHalf, FRAME_COLOR);
        g.fill(2, h - 2, w - 2, h - 1, FRAME_COLOR);
        g.fill(1, 2 + titleHalf, 2, h - 2, FRAME_COLOR);
        g.fill(w - 2, 2 + titleHalf, w - 1, h - 2, FRAME_COLOR);

        int rowY = BORDER_N;
        for (Commission c : list) {
            renderProgress(g, c, BORDER_W, rowY);
            rowY += ELEMENT_HEIGHT + PAD_L;
        }

        g.pose().popMatrix();
    }

    private static void renderProgress(GuiGraphicsExtractor g, Commission c, int x, int y) {
        var font = Minecraft.getInstance().font;
        boolean done = c.progress.equalsIgnoreCase("DONE");
        float percent = Math.max(0f, Math.min(100f, c.percent));
        int color = 0xFF000000 | Mth.hsvToRgb(percent / 300f, 1f, 1f);
        String barText = done ? "DONE" : String.format(java.util.Locale.ROOT, "%.2f%%", percent);

        g.pose().pushMatrix();
        g.pose().translate((float) (x + PAD_L), (float) (y + 4));
        // Item components are only bound once a world has loaded (e.g. not when /s2 gui is opened from the title screen).
        if (book == null && Minecraft.getInstance().level != null) book = new ItemStack(Items.BOOK);
        if (book != null) g.item(book, 0, 0);
        g.pose().popMatrix();

        int textX = x + PAD_L + ICON;
        g.text(font, Component.literal(c.name), textX, y, 0xFFFFFFFF, false);

        int barY = y + font.lineHeight + PAD_S;
        int filled = (int) (BAR_WIDTH * (percent / 100f));
        g.fill(textX + filled, barY, textX + BAR_WIDTH, barY + BAR_HEIGHT, 0xF0101010);
        g.fill(textX, barY, textX + filled, barY + BAR_HEIGHT, color);

        // Dark text only when it sits entirely on a bright filled bar.
        boolean textDark = filled >= font.width(barText) + 4 && isBright(color);
        g.text(font, barText, textX + 3, barY + 2, textDark ? 0xFF000000 : 0xFFFFFFFF, !textDark);
    }

    /** WCAG contrast check, as in Skyblocker's ColorUtils.isBright. */
    private static boolean isBright(int color) {
        double r = linear(ARGB.red(color) / 255.0), gr = linear(ARGB.green(color) / 255.0), b = linear(ARGB.blue(color) / 255.0);
        double luminance = 0.2126 * r + 0.7152 * gr + 0.0722 * b;
        double whiteContrast = (1.0 + 0.05) / (luminance + 0.05);
        double blackContrast = (luminance + 0.05) / 0.05;
        return whiteContrast < blackContrast;
    }

    private static double linear(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static void save() {
        if (config != null) {
            config.save(Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("sky2m-mod.json"));
        }
    }

    private record Commission(String name, String progress, float percent) {}
}
