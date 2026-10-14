package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.Align;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.Element;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.Event;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.HorizontalPosition;
import com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard.VerticalPosition;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.misc.gif.GifDecoder;
import com.epic60869.sky2m.features.misc.gif.GifTexture;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom Scoreboard, written for Sky2M to work like j10a1n15's Custom Scoreboard (LGPL-2.1,
 * https://github.com/meowdding/CustomScoreboard, itself grown from SkyHanni's): the sidebar redrawn from the lines
 * ("elements") you pick, in your order, each finding its part of Hypixel's sidebar or tab list. The Events element
 * shows what's going on (a dungeon run, Kuudra, the Garden, a contest, the Mining events...) from its own ordered list,
 * matching the same lines the mod's events do; anything Sky2M doesn't know goes under Other so nothing is lost.
 *
 * <p>Like the mod, it's placed by screen alignment (right and centred by default, as vanilla's is) and grows from
 * there as lines come and go, or moved freely in /s2 gui; the box is centred on the lines, with a rounded background,
 * a four-colour border and an optional picture. With chat open, lines can be hovered and clicked. Hypixel's sidebar
 * is hidden while it's on.
 */
public final class CustomScoreboard {
    private static final String HUD = "custom_scoreboard";
    private static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("scoreboard");
    private static final Pattern PADDING_EMOJI = Pattern.compile("[\\x{10000}-\\x{10FFFF}]");
    private static final Pattern LOBBY = Pattern.compile("\\d{2}/\\d{2}/\\d{2,4} .+");
    private static final Pattern DATE = Pattern.compile("(?:Early |Late )?(?:Spring|Summer|Autumn|Winter) \\d{1,2}(?:st|nd|rd|th)");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(am|pm)\\b.*");
    private static final Pattern AREA = Pattern.compile("(?:[⏣ф] .+|Plot -.+|✌ \\(\\d+/\\d+\\))");
    private static final Pattern FOOTER = Pattern.compile("(?:www|alpha)\\.hypixel\\.net");
    private static final Pattern OBJECTIVE = Pattern.compile("(?:Objective|Quest)\\b.*");
    private static final Pattern SLAYER = Pattern.compile("Slayer(?::| Quest).*");
    private static final Pattern NUMBER = Pattern.compile("(Purse|Piggy|Motes|Bits|Copper|Sowdust|Heat|Cold|North Stars): ([\\d,.]+)(.*)");
    private static final Pattern TAB_VALUE = Pattern.compile("(Gems|Bank|Soulflow): (.+)");
    private static final Pattern POWDER = Pattern.compile("(Mithril|Gemstone|Glacite): ([\\d,.]+)");
    private static final Pattern PLAYERS = Pattern.compile("(?:Players|Island|Coop|Guests) \\((\\d+)\\)");
    private static final DecimalFormat COMMAS = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat SHORT = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.US));

    /** A line to draw: its text, where it sits, whether it's a separator, and its click command and tip (with chat open). */
    private record Line(Component text, Align align, boolean blank, String command, String hover) {
        static Line of(String legacy, Align align) {
            return new Line(SbcItems.parseLegacy(legacy), align, false, null, null);
        }

        static Line blankLine(Align align) {
            return new Line(Component.empty(), align, true, null, null);
        }

        Line withAction(String command, String hover) {
            return new Line(text, align, blank, command, hover);
        }
    }

    /** A sidebar or tab line: with its colours (legacy § codes) and without. */
    private record Raw(String legacy, String plain) {}

    /** A number line's last value and the change being shown, until 5 seconds after the last change. */
    private static final class Tracked {
        double last = Double.NaN, change;
        long until;
    }

    private static final Map<String, Tracked> TRACKED = new HashMap<>();
    /** The purse from the last sidebar that had it, for when Hypixel leaves the line off. */
    private static double lastPurse = Double.NaN;
    private static final Map<Element, String> COLOURS = new EnumMap<>(Element.class);
    private static List<Line> lines = List.of();
    private static boolean hasSidebar;
    private static int ticks;

    // Where the board was last drawn on screen, for clicks and tips with chat open.
    private static int drawnX, drawnY;
    private static float drawnScale = 1;
    private static final List<int[]> LINE_BOXES = new ArrayList<>();

    private static String imageName = "";
    private static GifTexture image;

    static {
        COLOURS.put(Element.PURSE, "§6");
        COLOURS.put(Element.BANK, "§6");
        COLOURS.put(Element.MOTES, "§d");
        COLOURS.put(Element.BITS, "§b");
        COLOURS.put(Element.COPPER, "§c");
        COLOURS.put(Element.SOWDUST, "§6");
        COLOURS.put(Element.HEAT, "§c");
        COLOURS.put(Element.COLD, "§b");
        COLOURS.put(Element.NORTH_STARS, "§d");
        COLOURS.put(Element.GEMS, "§a");
        COLOURS.put(Element.SOULFLOW, "§3");
    }

    private CustomScoreboard() {}

    static FeatureConfigs.CustomScoreboard config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.customScoreboard;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Four times a second, like the mod.
            if (++ticks % 5 == 0) update(mc);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> TRACKED.clear());
        // Only for /s2 gui (a Free position); it's drawn in the sidebar's place below, never by the HUD list itself.
        Sky2MHuds.registerCustom(HUD, "Custom Scoreboard", () -> config() != null && config().enabled && free(), new Sky2MHuds.CustomHud() {
            @Override
            public int width() {
                return boxSize(true)[0];
            }

            @Override
            public int height() {
                return boxSize(true)[1];
            }

            @Override
            public boolean visible() {
                return false;
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean preview) {
                drawBoard(graphics, true);
            }
        }, 10000, 60);
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, vanilla -> (graphics, delta) -> {
            if (!hidesVanilla()) vanilla.extractRenderState(graphics, delta);
            if (showing()) drawOnScreen(graphics);
        });
        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal(root)
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("scoreboard").executes(ctx -> {
                        Compat.queueOpenScreen(new CustomScoreboardEditor(null));
                        return 1;
                    })));
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(event.x(), event.y()));
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> hover(g, mouseX, mouseY));
        });
    }

    /** Reads the sidebar again now (the editor calls this after each change). */
    public static void refresh() {
        update(Minecraft.getInstance());
    }

    /** Opens the editor (from the settings button or /s2 scoreboard). */
    public static void openEditor() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new CustomScoreboardEditor(mc.gui.screen())));
    }

    /** The editor preview's size, unscaled. */
    public static int[] previewSize() {
        return boxSize(true);
    }

    /** Draws the board (or a sample when there's no sidebar) at the current origin, for the editor's preview. */
    public static void drawPreview(GuiGraphicsExtractor graphics) {
        drawBoard(graphics, true);
    }

    public static void openFolder() {
        try {
            Files.createDirectories(FOLDER);
        } catch (Exception ignored) {}
        com.mojang.blaze3d.Blaze3D.openPath(FOLDER);
    }

    private static boolean free() {
        FeatureConfigs.CustomScoreboard c = config();
        return c != null && (c.horizontal == HorizontalPosition.FREE || c.vertical == VerticalPosition.FREE);
    }

    private static boolean allowedHere() {
        FeatureConfigs.CustomScoreboard c = config();
        return c != null && c.enabled && (Sky2MLocation.onSkyblock() || c.outsideSkyblock);
    }

    /** Hypixel's sidebar is hidden whenever the custom one is on, like the mod (even while it's hidden by Tab or chat). */
    private static boolean hidesVanilla() {
        FeatureConfigs.CustomScoreboard c = config();
        return allowedHere() && c.hideHypixel && hasSidebar;
    }

    private static boolean showing() {
        FeatureConfigs.CustomScoreboard c = config();
        if (!allowedHere() || !hasSidebar || lines.isEmpty()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (c.hideWhenTab && mc.options.keyPlayerList.isDown()) return false;
        if (c.hideWhenChat && mc.gui.screen() instanceof ChatScreen) return false;
        return !c.hideWhenF3 || !mc.getDebugOverlay().showDebugScreen();
    }

    // ------------------------------------------------------------------------------------------------ reading

    private static void update(Minecraft mc) {
        FeatureConfigs.CustomScoreboard c = config();
        if (c != null && c.layoutVersion < 1) {
            // Once: the Custom Scoreboard mod's own line and event order (this version's default).
            FeatureConfigs.CustomScoreboard fresh = new FeatureConfigs.CustomScoreboard();
            c.lines = fresh.lines;
            c.events = fresh.events;
            c.layoutVersion = 1;
            Sky2MConfig.saveCurrent(Sky2MConfig.current());
        }
        if (c == null || !c.enabled || mc.level == null) {
            hasSidebar = false;
            lines = List.of();
            return;
        }
        Scoreboard board = mc.level.getScoreboard();
        Objective objective = board.getDisplayObjective(DisplaySlot.SIDEBAR);
        hasSidebar = objective != null;
        if (objective == null) {
            lines = List.of();
            return;
        }
        List<PlayerScoreEntry> entries = new ArrayList<>(board.listPlayerScores(objective));
        entries.removeIf(PlayerScoreEntry::isHidden);
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed().thenComparing(PlayerScoreEntry::owner));
        List<Raw> sidebar = new ArrayList<>();
        for (PlayerScoreEntry entry : entries) {
            PlayerTeam team = board.getPlayersTeam(entry.owner());
            // Each line is its team's prefix and suffix. The entry's own name between them is Hypixel's padding (an
            // emoji, sometimes an ordinary character like ⚽, or "§r"), so it's left out, as Skyblocker reads them;
            // keeping it put stray characters into lines (even mid-word) and made blank lines not blank.
            Component line = team == null ? entry.ownerName()
                : Component.empty().append(team.getPlayerPrefix()).append(team.getPlayerSuffix());
            sidebar.add(raw(line));
        }
        List<Raw> tab = new ArrayList<>();
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = Compat.rawTabName(info);
            if (name != null) tab.add(raw(name));
        }
        Raw title = raw(objective.getDisplayName());
        List<Line> built = c.customLines && Sky2MLocation.onSkyblock() ? build(c, title, sidebar, tab) : vanilla(c, title, sidebar);
        lines = tidySeparators(c, built);
    }

    /** Hypixel's padding emoji and the spaces around a line removed, keeping its colours. */
    private static Raw raw(Component component) {
        String legacy = PADDING_EMOJI.matcher(SbcItems.legacy(component)).replaceAll("");
        legacy = trimLegacy(legacy);
        return new Raw(legacy, ChatFormatting.stripFormatting(legacy).trim());
    }

    /** Trims spaces at either end of a line with § codes, keeping the codes. */
    private static String trimLegacy(String s) {
        StringBuilder codes = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '§' && i + 1 < s.length()) {
                codes.append(ch).append(s.charAt(i + 1));
                i += 2;
            } else if (ch == ' ') {
                i++;
            } else {
                break;
            }
        }
        String rest = s.substring(i);
        int end = rest.length();
        while (end > 0 && rest.charAt(end - 1) == ' ') end--;
        return codes + rest.substring(0, end);
    }

    /** Hypixel's own lines, in the custom box (Custom Lines off, or outside SkyBlock). */
    private static List<Line> vanilla(FeatureConfigs.CustomScoreboard c, Raw title, List<Raw> sidebar) {
        List<Line> out = new ArrayList<>();
        out.add(Line.of(title.legacy(), c.title.titleAlignment));
        for (Raw r : sidebar) {
            if (r.plain().isEmpty()) out.add(Line.blankLine(c.lineAlignment));
            else out.add(Line.of(r.legacy(), FOOTER.matcher(r.plain()).matches() ? c.title.footerAlignment : c.lineAlignment));
        }
        return out;
    }

    private static List<Line> build(FeatureConfigs.CustomScoreboard c, Raw title, List<Raw> sidebar, List<Raw> tab) {
        FeatureConfigs.ScoreboardLines o = c.lineOptions;
        Set<Integer> used = new HashSet<>();
        Align align = c.lineAlignment;
        String indent = o.indentLocationTime ? " " : "";
        // Blank lines and the footer are never events.
        for (int i = 0; i < sidebar.size(); i++) {
            if (sidebar.get(i).plain().isEmpty() || FOOTER.matcher(sidebar.get(i).plain()).matches()) used.add(i);
        }
        List<Line> out = new ArrayList<>();
        int eventsAt = -1;
        for (Element element : c.lines) {
            if (element == null) continue;
            switch (element) {
                case TITLE -> {
                    if (c.title.hypixelTitle) out.add(Line.of(title.legacy(), c.title.titleAlignment));
                    else for (String part : c.title.titleText.split("\\\\n")) out.add(Line.of(part.replace('&', '§'), c.title.titleAlignment));
                }
                case FOOTER -> {
                    if (c.title.hypixelFooter) {
                        int i = find(sidebar, FOOTER, null);
                        out.add(Line.of(i >= 0 ? sidebar.get(i).legacy() : "§ewww.hypixel.net", c.title.footerAlignment));
                    } else {
                        for (String part : c.title.footerText.split("\\\\n")) out.add(Line.of(part.replace('&', '§'), c.title.footerAlignment));
                    }
                }
                case SEPARATOR, SEPARATOR_2, SEPARATOR_3, SEPARATOR_4 -> out.add(Line.blankLine(align));
                case LOBBY -> single(out, sidebar, used, LOBBY, align, "");
                case DATE -> single(out, sidebar, used, DATE, align, indent);
                case TIME -> {
                    int i = find(sidebar, TIME, used);
                    if (i < 0) break;
                    used.add(i);
                    String legacy = sidebar.get(i).legacy();
                    Matcher m = TIME.matcher(sidebar.get(i).plain());
                    if (o.time24h && m.matches()) {
                        int hour = Integer.parseInt(m.group(1)) % 12 + (m.group(3).equals("pm") ? 12 : 0);
                        legacy = legacy.replace(m.group(1) + ":" + m.group(2) + m.group(3), String.format(Locale.ROOT, "%02d:%s", hour, m.group(2)));
                    }
                    out.add(Line.of(indent + legacy, align));
                }
                case IRL_TIME -> out.add(Line.of("§7" + LocalTime.now().format(DateTimeFormatter.ofPattern(o.time24h ? "HH:mm" : "h:mm a", Locale.US)), align));
                case ISLAND -> {
                    String island = Sky2MLocation.area();
                    if (island != null && !island.isEmpty()) {
                        out.add(Line.of(indent + "§7㋖ §a" + island, align).withAction("/warp", "Click to open the warp menu."));
                    }
                }
                case AREA -> {
                    for (int i = 0; i < sidebar.size(); i++) {
                        if (used.contains(i) || !AREA.matcher(sidebar.get(i).plain()).matches()) continue;
                        used.add(i);
                        out.add(Line.of(indent + sidebar.get(i).legacy(), align).withAction("/warp", "Click to open the warp menu."));
                    }
                }
                case PURSE, MOTES, BITS, COPPER, SOWDUST, HEAT, COLD, NORTH_STARS -> number(c, out, sidebar, used, element, align);
                case GEMS, BANK, SOULFLOW -> tabValue(c, out, tab, element, align);
                case PROFILE -> tabLine(out, tab, "Profile:", align, "/profiles", "Click to see your profiles.");
                case SKYBLOCK_LEVEL -> tabLine(out, tab, "SB Level:", align, "/sblevels", "Click to see your SkyBlock levels.");
                case PLAYER_COUNT -> {
                    for (Raw r : tab) {
                        Matcher m = PLAYERS.matcher(r.plain());
                        if (m.matches()) {
                            out.add(new Line(numberLine(c, "Players", m.group(1), Component.empty()), align, false, null, null));
                            break;
                        }
                    }
                }
                case COOKIE_BUFF -> {
                    for (int i = 0; i + 1 < tab.size(); i++) {
                        if (!tab.get(i).plain().equals("Cookie Buff")) continue;
                        String value = tab.get(i + 1).plain();
                        boolean on = !value.contains("Not active");
                        if (o.hideEmpty && !on) break;
                        out.add(Line.of("§dCookie Buff: " + (on ? "§a" : "§c") + value, align)
                            .withAction("/sbmenu", "Click to open the SkyBlock menu."));
                        break;
                    }
                }
                case POWDER -> {
                    if (o.powderOnlyMining && !Sky2MLocation.inMiningIsland()) break;
                    List<Line> powders = new ArrayList<>();
                    for (Raw r : tab) {
                        Matcher m = POWDER.matcher(r.plain());
                        if (!m.matches()) continue;
                        String colour = switch (m.group(1)) {
                            case "Mithril" -> "§2";
                            case "Gemstone" -> "§d";
                            default -> "§b";
                        };
                        powders.add(Line.of(" §7- " + colour + m.group(1) + ": §f" + formatNumber(c, parse(m.group(2))), align));
                    }
                    if (!powders.isEmpty()) {
                        out.add(Line.of("§9§lPowder", align).withAction("/hotm", "Click to open your Heart of the Mountain."));
                        out.addAll(powders);
                    }
                }
                case PET -> {
                    Sky2MTabWidgetManager.Widget pet = Sky2MTabWidgetManager.get("Pet");
                    if (!pet.lines().isEmpty()) {
                        out.add(new Line(Component.empty().append(Component.literal("§7Pet: ")).append(pet.lines().getFirst()), align, false,
                            "/pets", "Click to open your pets."));
                    }
                }
                case PARTY -> {
                    List<String> members = PartyCommands.members();
                    if (members.isEmpty() || (Sky2MLocation.inDungeon() && !o.partyInDungeons)) break;
                    String leader = PartyCommands.leader();
                    out.add(Line.of("§9§lParty §7(" + (members.size() + 1) + ")", align).withAction("/p list", "Click to list your party."));
                    for (int i = 0; i < members.size() && i < o.maxParty; i++) {
                        String name = members.get(i);
                        out.add(Line.of(" §7- §f" + name + (name.equalsIgnoreCase(leader) ? " §e♚" : ""), align)
                            .withAction("/p list", "Click to list your party."));
                    }
                }
                case OBJECTIVE -> {
                    int i = find(sidebar, OBJECTIVE, used);
                    if (i < 0) break;
                    for (int j = i; j < sidebar.size(); j++) {
                        Raw r = sidebar.get(j);
                        if (j > i && (r.plain().isEmpty() || FOOTER.matcher(r.plain()).matches())) break;
                        used.add(j);
                        out.add(Line.of(r.legacy(), align).withAction("/quests", "Click to view quest details."));
                    }
                }
                case SLAYER -> {
                    // The quest line and the two under it (the boss and its progress), like the mod.
                    int i = find(sidebar, SLAYER, used);
                    if (i < 0) break;
                    for (int j = i; j < sidebar.size() && j < i + 3; j++) {
                        if (j > i && sidebar.get(j).plain().isEmpty()) break;
                        used.add(j);
                        out.add(Line.of(sidebar.get(j).legacy(), align));
                    }
                }
                case EVENTS -> {
                    eventsAt = out.size();
                    out.add(null); // filled in once every other line has taken its part of the sidebar
                }
            }
        }
        if (eventsAt >= 0) {
            out.remove(eventsAt);
            out.addAll(eventsAt, events(c, sidebar, tab, used, align));
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    private static int find(List<Raw> lines, Pattern pattern, Set<Integer> used) {
        for (int i = 0; i < lines.size(); i++) {
            if ((used == null || !used.contains(i)) && pattern.matcher(lines.get(i).plain()).matches()) return i;
        }
        return -1;
    }

    private static void single(List<Line> out, List<Raw> sidebar, Set<Integer> used, Pattern pattern, Align align, String indent) {
        int i = find(sidebar, pattern, used);
        if (i < 0) return;
        used.add(i);
        out.add(Line.of(indent + sidebar.get(i).legacy(), align));
    }

    private static String label(Element element) {
        return switch (element) {
            case PURSE -> "Purse";
            case MOTES -> "Motes";
            case BITS -> "Bits";
            case COPPER -> "Copper";
            case SOWDUST -> "Sowdust";
            case HEAT -> "Heat";
            case COLD -> "Cold";
            case NORTH_STARS -> "North Stars";
            case GEMS -> "Gems";
            case BANK -> "Bank";
            case SOULFLOW -> "Soulflow";
            default -> element.name();
        };
    }

    private static void number(FeatureConfigs.CustomScoreboard c, List<Line> out, List<Raw> sidebar, Set<Integer> used, Element element, Align align) {
        String label = label(element);
        int index = -1;
        // find(), not matches(): a stray padding character in front of the line (Hypixel adds some) mustn't hide it.
        for (int i = 0; i < sidebar.size() && index < 0; i++) {
            Matcher m = NUMBER.matcher(sidebar.get(i).plain());
            if (m.find() && (m.group(1).equals(label) || element == Element.PURSE && m.group(1).equals("Piggy"))) index = i;
        }
        if (element == Element.PURSE && c.lineOptions.hidePurseInDungeons && Sky2MLocation.inDungeon()) {
            if (index >= 0) used.add(index);
            return;
        }
        double value;
        if (index >= 0) {
            used.add(index);
            Matcher m = NUMBER.matcher(sidebar.get(index).plain());
            if (!m.find()) return;
            if (element == Element.PURSE && m.group(1).equals("Piggy") && c.lineOptions.showPiggy) label = "Piggy";
            value = parse(m.group(2));
            if (element == Element.PURSE) lastPurse = value;
        } else if (element == Element.PURSE && !Double.isNaN(lastPurse)) {
            // Hypixel leaves Purse off a full sidebar (the Mining islands' events and powder): show the last one seen.
            value = lastPurse;
        } else {
            return;
        }
        if (c.lineOptions.hideEmpty && value == 0) return;
        Line line = new Line(numberLine(c, label, formatNumber(c, value), change(c, element.name(), value, style(c, label)[1])), align, false, null, null);
        if (element == Element.BITS) line = line.withAction("/sbmenu", "Click to open the SkyBlock menu to resync your bits.");
        if (element == Element.COPPER) line = line.withAction("/desk", "Click to open the Garden desk.");
        out.add(line);
    }

    /** Gems, Bank and Soulflow, from the tab list. */
    private static void tabValue(FeatureConfigs.CustomScoreboard c, List<Line> out, List<Raw> tab, Element element, Align align) {
        for (Raw r : tab) {
            Matcher m = TAB_VALUE.matcher(r.plain());
            if (!m.matches() || !m.group(1).equals(label(element))) continue;
            String value = m.group(2).trim();
            if (c.lineOptions.hideEmpty && value.matches("0(?:\\.0+)?")) return;
            Component gain = Component.empty();
            if (element == Element.GEMS) {
                double gems = parse(value);
                value = formatNumber(c, gems);
                gain = change(c, element.name(), gems, style(c, "Gems")[1]);
            }
            out.add(new Line(numberLine(c, label(element), value, gain), align, false, null, null));
            return;
        }
    }

    /** A number line's name and colour (Colours and Labels): {name with & codes, chroma colour}. */
    private static String[] style(FeatureConfigs.CustomScoreboard c, String key) {
        FeatureConfigs.ScoreboardColours k = c.colours;
        return switch (key) {
            case "Purse" -> new String[]{k.purseLabel, k.purse};
            case "Piggy" -> new String[]{k.piggyLabel, k.piggy};
            case "Bank" -> new String[]{k.bankLabel, k.bank};
            case "Motes" -> new String[]{k.motesLabel, k.motes};
            case "Bits" -> new String[]{k.bitsLabel, k.bits};
            case "Copper" -> new String[]{k.copperLabel, k.copper};
            case "Sowdust" -> new String[]{k.sowdustLabel, k.sowdust};
            case "Gems" -> new String[]{k.gemsLabel, k.gems};
            case "Heat" -> new String[]{k.heatLabel, k.heat};
            case "Cold" -> new String[]{k.coldLabel, k.cold};
            case "North Stars" -> new String[]{k.northStarsLabel, k.northStars};
            case "Soulflow" -> new String[]{k.soulflowLabel, k.soulflow};
            case "Players" -> new String[]{k.playersLabel, k.players};
            default -> new String[]{key, "0:255:255:255:255"};
        };
    }

    /**
     * A number line laid out as Number Display says, like the mod's NumberDisplayFormat, in the line's own colour
     * (any colour, not only Minecraft's 16) and with its own name.
     */
    private static Component numberLine(FeatureConfigs.CustomScoreboard c, String key, String number, Component gain) {
        String[] style = style(c, key);
        String name = style[0] == null || style[0].isBlank() ? key : style[0].replace('&', '§');
        int colour = colour(style[1]) & 0xFFFFFF, nameColour = colour(c.colours.label) & 0xFFFFFF;
        Component value = Component.empty().append(Component.literal(number).withColor(colour)).append(gain);
        Component label = Component.empty().withColor(nameColour).append(SbcItems.parseLegacy(name));
        Component colouredLabel = Component.empty().withColor(colour).append(SbcItems.parseLegacy(name));
        return switch (c.lineOptions.numberDisplay) {
            case TEXT_NUMBER -> Component.empty().append(label).append(Component.literal(": ").withColor(nameColour)).append(value);
            case COLOR_TEXT_NUMBER -> Component.empty().append(colouredLabel).append(Component.literal(": ").withColor(colour)).append(value);
            case COLOR_NUMBER_TEXT -> Component.empty().append(value).append(Component.literal(" ")).append(colouredLabel);
            case NUMBER_TEXT -> Component.empty().append(value).append(Component.literal(" ")).append(label);
            case NUMBER_ONLY -> value;
        };
    }

    /** " (+12,000)" while the value has changed in the last 5 seconds (added up while it keeps changing), else "". */
    private static Component change(FeatureConfigs.CustomScoreboard c, String key, double value, String colour) {
        Tracked t = TRACKED.computeIfAbsent(key, k -> new Tracked());
        long now = System.currentTimeMillis();
        if (!Double.isNaN(t.last) && value != t.last && c.lineOptions.showChanges) {
            t.change = (now < t.until ? t.change : 0) + (value - t.last);
            t.until = now + 5000;
        }
        t.last = value;
        if (now >= t.until || t.change == 0) return Component.empty();
        return Component.literal(" (").withColor(0xAAAAAA)
            .append(Component.literal((t.change > 0 ? "+" : "") + formatNumber(c, t.change)).withColor(colour(colour) & 0xFFFFFF))
            .append(Component.literal(")"));
    }

    private static void tabLine(List<Line> out, List<Raw> tab, String prefix, Align align, String command, String hover) {
        for (Raw r : tab) {
            if (r.plain().startsWith(prefix)) {
                out.add(Line.of(r.legacy(), align).withAction(command, hover));
                return;
            }
        }
    }

    private static double parse(String number) {
        try {
            return Double.parseDouble(number.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatNumber(FeatureConfigs.CustomScoreboard c, double value) {
        if (c.lineOptions.numberFormat == FeatureConfigs.CustomScoreboard.NumberFormat.SHORT) {
            double abs = Math.abs(value);
            if (abs >= 1e9) return SHORT.format(value / 1e9) + "B";
            if (abs >= 1e6) return SHORT.format(value / 1e6) + "M";
            if (abs >= 1e3) return SHORT.format(value / 1e3) + "k";
        }
        return value == Math.floor(value) ? COMMAS.format(value) : SHORT.format(value);
    }

    // ------------------------------------------------------------------------------------------------ events

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    // The Custom Scoreboard mod's event patterns (its events/ folder), matched against whole lines without colours.
    private static final List<Pattern> DUNGEONS = List.of(p("No Alive Dragons|- [\\w\\s]+Dragon [\\w,.]+."), p("Auto-closing in: [\\d+:,.]+"),
        p("Starting in: [\\d+:,.]+"), p("Keys: ■ [✗✓] ■ .x"), p("Time Elapsed: (?:\\w+[ydhms] ?)+"), p("Cleared: [\\w,.]+% \\([\\w,.]+\\)"),
        p("Solo"), p("\\[\\w] \\w{2,16} (?:\\[Lvl?[\\w,.]*]?|[\\w,.]+.?)"), p("- (?:Healthy|Reinforced|Laser|Chaos) [\\w,.]*."));
    private static final List<Pattern> KUUDRA = List.of(p("Auto-closing in: [\\d+:,.]+"), p("Starting in: [\\d+:,.]+"),
        p("Time Elapsed: (?:\\w+[ydhms] ?)+"), p("Instance Shutdown In: (?:\\w+[ydhms] ?)+"), p("Wave: \\d+(?: - \\d+:\\d+)?"),
        p("Tokens: [\\w,]+"), p("Submerges In: [\\w\\s?]+"));
    private static final List<Pattern> DOJO = List.of(p("Challenge: .+"), p("Difficulty: .+"), p("Points: -?[\\w.]+.*"), p("Time: [\\w.]+.*"));
    private static final List<Pattern> GARDEN = List.of(p("(?:Barn )?Pasting: [\\d,.]+%?"), p("Cleanup: [\\d,.]*%?"));
    private static final List<Pattern> WINTER = List.of(p("Event Start: [\\d:,.]+"), p("Next Wave: (?:[\\d:,.]+|Soon!)"), p("Wave \\d+"),
        p("Magma Cubes Left: -?\\d+"), p("Your Total Damage: [\\d+,.]+.*"), p("Your Cube Damage: [\\d+,.]+"));
    private static final List<Pattern> SPOOKY = List.of(p("Spooky Festival.*"), p("Your Candy:"), p(".*Green, .*Purple.*"));
    private static final List<Pattern> CARNIVAL = List.of(p("Carnival [\\d:,.]+"), p("Carnival Tokens: [\\d,.]+"),
        p("Catch a Fish|Fruit Digging|Zombie Shootout"), p("Time Left: [\\w:,.\\s]+"), p("Fruits: \\d+/\\d+"), p("Score: \\d+.*"),
        p("Catch Streak: \\d+"), p("Accuracy: [\\d.,]+%"), p("Kills: \\d+"));
    private static final List<Pattern> MINING = List.of(p("᠅ (?:Gemstone|Mithril|Glacite)(?: Powder)?.*"), p("Event: .*"), p("Zone: .*"),
        p("Find tickets on the|ground and bring them|to the raffle box"), p("Tickets: \\d+ \\([\\d.,]+%\\)"), p("Pool: [\\d.,]+"),
        p("Give Tasty Mithril to Don!"), p("Remaining: (?:\\d+ Tasty Mithril|FULL)"), p("Your Tasty Mithril: \\d+.*"),
        p("Nearby Players: .*"), p("Kill goblins!"), p("Remaining: \\d+ goblins?"), p("Your kills: \\d+ ☠.*"), p("Not started.*"),
        p("Event Bonus: \\+\\d+."), p("Fossil Dust: [\\d.,]+.*"), p("Wind Compass"), p("[⋖⋗≈]+\\s*[⋖⋗≈]*"));
    private static final List<Pattern> FORAGING = List.of(p(".*Whispers: [\\w,.]+.*"), p("HOTF: [\\w,.]+.*"), p("Captured Mobs: [\\d,.]+"));
    private static final Pattern FORAGING_CONTEST = p("(?:Agatha|Miria)'s Contest.*");
    private static final List<Pattern> DAMAGE = List.of(p("(?:Protector|Dragon) HP: [\\d,.]* ."), p("Your Damage: [\\d,.]+"));
    private static final List<Pattern> MAGMA_BOSS = List.of(p("Boss: \\d+%"), p("Damage Soaked:"), p("Kill the Magmas:"),
        p("The boss is (?:re)?forming!"), p("Boss Health:"), p("(?:\\d+(?:\\.\\d)?M|\\d+k)/10M."), p("▎+"));
    private static final List<Pattern> RIFT = List.of(p("Effigies: ⧯*"), p("Hot Dog Contest|Eaten: \\d+/\\d+"),
        p("Time spent sitting|with Ävaeìkx: .*"), p("Clues: \\d+/\\d+"), p("First Up|Find and talk with Barry"), p("Protestors handled: \\d+/\\d+"));
    private static final List<Pattern> QUEUE = List.of(p("Queued:.*"), p("Tier: .*"), p("Position: #\\d+ Since: .*"));
    private static final List<Pattern> VOTING = List.of(p("Year \\d+ Votes"), p("Waiting for|your vote\\.\\.\\."), p("\\|{15}.+"));
    private static final Pattern ENDS_IN = p("Ends In: (.*)");
    private static final List<String> TAB_EVENT_BLOCKED = List.of("Spooky Festival", "Carnival", "th SkyBlock Anniversary", "New Year Celebration");

    /** Every event that's going on, in the Events order; Other (lines nobody took) is worked out last. */
    private static List<Line> events(FeatureConfigs.CustomScoreboard c, List<Raw> sidebar, List<Raw> tab, Set<Integer> used, Align align) {
        Map<Event, List<Line>> found = new EnumMap<>(Event.class);
        for (Event event : c.events) {
            if (event == null || event == Event.OTHER) continue;
            List<Line> lines = event(c, event, sidebar, tab, used, align);
            if (!lines.isEmpty()) found.put(event, lines);
        }
        if (c.events.contains(Event.OTHER)) {
            List<Line> other = new ArrayList<>();
            for (int i = 0; i < sidebar.size(); i++) {
                if (used.contains(i) || knownLine(sidebar.get(i).plain())) continue;
                used.add(i);
                other.add(Line.of(sidebar.get(i).legacy(), align));
            }
            if (!other.isEmpty()) found.put(Event.OTHER, other);
        }
        List<Line> out = new ArrayList<>();
        for (Event event : c.events) {
            List<Line> lines = event == null ? null : found.get(event);
            if (lines == null) continue;
            if (!out.isEmpty() && c.lineOptions.separatorBetweenEvents) out.add(Line.blankLine(align));
            out.addAll(lines);
            if (!c.lineOptions.showAllEvents) break;
        }
        return out;
    }

    /** Lines one of the elements shows, so they don't turn up under Other when that element is left out. */
    private static boolean knownLine(String plain) {
        return LOBBY.matcher(plain).matches() || DATE.matcher(plain).matches() || TIME.matcher(plain).matches()
            || AREA.matcher(plain).matches() || NUMBER.matcher(plain).matches() || FOOTER.matcher(plain).matches()
            || OBJECTIVE.matcher(plain).matches() || SLAYER.matcher(plain).matches();
    }

    private static List<Line> event(FeatureConfigs.CustomScoreboard c, Event event, List<Raw> sidebar, List<Raw> tab, Set<Integer> used, Align align) {
        String island = Sky2MLocation.area();
        return switch (event) {
            case SERVER_RESTART -> matching(sidebar, used, align, false, List.of(p("Server closing.*")));
            case DUNGEONS -> Sky2MLocation.inDungeon() ? matching(sidebar, used, align, true, DUNGEONS) : List.of();
            case KUUDRA -> matching(sidebar, used, align, false, KUUDRA);
            case DOJO -> "Crimson Isle".equals(island) ? matching(sidebar, used, align, false, DOJO) : List.of();
            case DARK_AUCTION -> {
                if (!"Dark Auction".equals(island)) yield List.of();
                List<Line> out = matching(sidebar, used, align, false, List.of(p("Starting in: [\\w:,.\\s]+"), p("Time Left: [\\w:,.\\s]+")));
                out.addAll(block(sidebar, used, align, p("Current Item:"), 2));
                yield out;
            }
            case JACOBS_CONTEST -> block(sidebar, used, align, p("Jacob's Contest"), 4);
            case JACOB_MEDALS -> matching(sidebar, used, align, false, List.of(p(".* medals: \\d+")));
            case TRAPPER -> {
                List<Line> out = matching(sidebar, used, align, false, List.of(p("Pelts: [\\d,.]+.*")));
                out.addAll(block(sidebar, used, align, p("Tracker Mob Location:"), 2));
                yield out;
            }
            case GARDEN -> matching(sidebar, used, align, false, GARDEN);
            case FLIGHT_DURATION -> matching(sidebar, used, align, false, List.of(p("Flight Duration: [\\d:]+")));
            case WINTER -> matching(sidebar, used, align, false, WINTER);
            case NEW_YEAR -> matching(sidebar, used, align, false, List.of(p("New Year Event! [\\d:]*")));
            case ANNIVERSARY -> matching(sidebar, used, align, false, List.of(p("\\d+th Anniversary [\\d:]+")));
            case SPOOKY -> matching(sidebar, used, align, false, SPOOKY);
            case CARNIVAL -> "Hub".equals(island) ? matching(sidebar, used, align, false, CARNIVAL) : List.of();
            case BROODMOTHER -> {
                for (Raw r : tab) {
                    if (r.plain().startsWith("Broodmother:")) yield List.of(Line.of(r.legacy(), align));
                }
                yield List.of();
            }
            case MINING -> {
                List<Line> out = matching(sidebar, used, align, false, MINING);
                // The Wind Compass's arrow sits in the middle, as on Hypixel.
                out.replaceAll(l -> l.text().getString().trim().matches("[⋖⋗≈\\s]+") ? new Line(l.text(), Align.CENTER, false, null, null) : l);
                yield out;
            }
            case FORAGING -> {
                List<Line> out = matching(sidebar, used, align, false, FORAGING);
                out.addAll(block(sidebar, used, align, FORAGING_CONTEST, 3));
                yield out;
            }
            case DAMAGE -> matching(sidebar, used, align, false, DAMAGE);
            case MAGMA_BOSS -> "Crimson Isle".equals(island) ? matching(sidebar, used, align, false, MAGMA_BOSS) : List.of();
            case RIFT -> matching(sidebar, used, align, false, RIFT);
            case ESSENCE -> matching(sidebar, used, align, false, List.of(p(".*Essence: [\\d,.]+")));
            case QUEUE -> matching(sidebar, used, align, false, QUEUE);
            case REDSTONE -> matching(sidebar, used, align, false, List.of(p("⚡ Redstone: [\\d.,]+%")));
            case VOTING -> matching(sidebar, used, align, false, VOTING);
            case ACTIVE_TABLIST -> tabEvent(c, align);
            case OTHER -> List.of();
        };
    }

    /**
     * The unused sidebar lines matching any of the patterns, in Hypixel's order. {@code blanksBetween}: also the blank
     * lines between the first and last match (a dungeon's sections), never at either end.
     */
    private static List<Line> matching(List<Raw> sidebar, Set<Integer> used, Align align, boolean blanksBetween, List<Pattern> patterns) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < sidebar.size(); i++) {
            if (used.contains(i)) continue;
            String plain = sidebar.get(i).plain();
            for (Pattern pattern : patterns) {
                if (pattern.matcher(plain).matches()) {
                    hits.add(i);
                    break;
                }
            }
        }
        List<Line> out = new ArrayList<>();
        if (hits.isEmpty()) return out;
        int first = hits.getFirst(), last = hits.getLast();
        for (int i = first; i <= last; i++) {
            if (hits.contains(i)) {
                used.add(i);
                out.add(Line.of(sidebar.get(i).legacy(), align));
            } else if (blanksBetween && sidebar.get(i).plain().isEmpty()) {
                out.add(Line.blankLine(align));
            }
        }
        return out;
    }

    /** A header line and up to {@code count - 1} lines under it (stopping at a blank line or the footer). */
    private static List<Line> block(List<Raw> sidebar, Set<Integer> used, Align align, Pattern header, int count) {
        List<Line> out = new ArrayList<>();
        int i = find(sidebar, header, used);
        if (i < 0) return out;
        for (int j = i; j < sidebar.size() && j < i + count; j++) {
            String plain = sidebar.get(j).plain();
            if (j > i && (plain.isEmpty() || FOOTER.matcher(plain).matches())) break;
            used.add(j);
            out.add(Line.of(sidebar.get(j).legacy(), align));
        }
        return out;
    }

    /** The tab list's Event widget (Mining Fiesta, Fishing Festival...) and when it ends, like the mod. */
    private static List<Line> tabEvent(FeatureConfigs.CustomScoreboard c, Align align) {
        if (c.lineOptions.tabEventsInDungeon && (Sky2MLocation.inDungeon() || "Kuudra".equals(Sky2MLocation.area()))) return List.of();
        Sky2MTabWidgetManager.Widget widget = Sky2MTabWidgetManager.get("Event");
        String name = widget.detail().getString().trim();
        if (name.isEmpty() || TAB_EVENT_BLOCKED.stream().anyMatch(name::contains)) return List.of();
        String ends = null;
        for (Component line : widget.lines()) {
            Matcher m = ENDS_IN.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
            if (m.matches()) ends = m.group(1);
        }
        if (ends == null) return List.of();
        return List.of(new Line(widget.detail(), align, false, "/calendar", "Click to open the calendar."),
            Line.of(" §fEnds In: §e" + ends, align).withAction("/calendar", "Click to open the calendar."));
    }

    /** Hide Separators at Top and Bottom, and Condense Separators, like the mod. */
    private static List<Line> tidySeparators(FeatureConfigs.CustomScoreboard c, List<Line> in) {
        List<Line> out = new ArrayList<>();
        for (Line line : in) {
            boolean blank = line.blank() || line.text().getString().isBlank();
            if (blank && c.lineOptions.condenseSeparators && !out.isEmpty() && out.getLast().blank()) continue;
            out.add(blank && !line.blank() ? Line.blankLine(line.align()) : line);
        }
        if (c.lineOptions.hideSeparatorsAtEnds) {
            while (!out.isEmpty() && out.getFirst().blank()) out.removeFirst();
            while (!out.isEmpty() && out.getLast().blank()) out.removeLast();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static List<Line> shown(boolean preview) {
        if (!lines.isEmpty() || !preview) return lines;
        FeatureConfigs.CustomScoreboard c = config();
        Align a = c == null ? Align.LEFT : c.lineAlignment;
        Align t = c == null ? Align.CENTER : c.title.titleAlignment;
        Align f = c == null ? Align.CENTER : c.title.footerAlignment;
        return List.of(Line.of("§e§lSKYBLOCK", t), Line.of("§710/11/26 §8m12AB", a), Line.blankLine(a), Line.of(" Late Winter 4th", a),
            Line.of(" §79:40pm §b☽", a), Line.of(" §7⏣ §bVillage", a), Line.blankLine(a), Line.of("§fPurse: §61,234,567 §7(§6+12,000§7)", a),
            Line.of("§fBits: §b4,820", a), Line.blankLine(a), Line.of("§ewww.hypixel.net", f));
    }

    private static int contentWidth(List<Line> shown) {
        Font font = Minecraft.getInstance().font;
        int w = 0;
        for (Line line : shown) w = Math.max(w, font.width(line.text()));
        return w;
    }

    private static int contentHeight(List<Line> shown) {
        FeatureConfigs.CustomScoreboard c = config();
        int spacing = c == null ? 0 : c.lineSpacing;
        return shown.isEmpty() ? 0 : shown.size() * (9 + spacing) - spacing;
    }

    private static int padding(FeatureConfigs.CustomScoreboard c) {
        return c.background.enabled ? c.background.padding : 1;
    }

    private static int border(FeatureConfigs.CustomScoreboard c) {
        return c.background.enabled && c.background.outline ? c.background.outlineWidth : 0;
    }

    /** The box's size, unscaled: the lines plus padding and border, at least the minimum size. */
    private static int[] boxSize(boolean preview) {
        FeatureConfigs.CustomScoreboard c = config();
        if (c == null) return new int[]{0, 0};
        List<Line> shown = shown(preview);
        int edge = 2 * (padding(c) + border(c));
        int w = contentWidth(shown) + edge, h = contentHeight(shown) + edge;
        if (c.background.enabled) {
            w = Math.max(w, c.background.minWidth);
            h = Math.max(h, c.background.minHeight);
        }
        return new int[]{w, h};
    }

    private static float scale(FeatureConfigs.CustomScoreboard c) {
        return free() ? Sky2MHuds.placement(HUD).scale : Math.clamp(c.scale, 0.25f, 4f);
    }

    /** Where the mod would put it: by alignment from the screen's edges (plus the margin), or where it was dragged. */
    private static void drawOnScreen(GuiGraphicsExtractor g) {
        FeatureConfigs.CustomScoreboard c = config();
        Minecraft mc = Minecraft.getInstance();
        float s = scale(c);
        int[] box = boxSize(false);
        int bw = Math.round(box[0] * s), bh = Math.round(box[1] * s);
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int margin = c.background.margin;
        Sky2MHuds.Placement p = Sky2MHuds.placement(HUD);
        int x = switch (c.horizontal) {
            case LEFT -> margin;
            case CENTER -> (sw - bw) / 2;
            case RIGHT -> sw - bw - margin;
            case FREE -> Sky2MHuds.mapX(p.x, bw);
        };
        int y = switch (c.vertical) {
            case TOP -> margin;
            case CENTER -> (sh - bh) / 2;
            case BOTTOM -> sh - bh - margin;
            case FREE -> Sky2MHuds.mapY(p.y, bh);
        };
        drawnX = x;
        drawnY = y;
        drawnScale = s;
        g.pose().pushMatrix();
        g.pose().translate((float) x, (float) y);
        g.pose().scale(s, s);
        drawBoard(g, false);
        g.pose().popMatrix();
    }

    /** The box and its lines at the current origin, unscaled. */
    private static void drawBoard(GuiGraphicsExtractor g, boolean preview) {
        FeatureConfigs.CustomScoreboard c = config();
        if (c == null) return;
        Font font = Minecraft.getInstance().font;
        List<Line> shown = shown(preview);
        int[] box = boxSize(preview);
        int w = box[0], h = box[1];
        if (c.background.enabled) background(g, c.background, w, h);
        int cw = contentWidth(shown), ch = contentHeight(shown);
        // The lines sit in the middle of the box, as in the mod (it can be bigger than them with a minimum size).
        int ox = (w - cw) / 2, oy = (h - ch) / 2;
        int step = 9 + c.lineSpacing;
        if (!preview) LINE_BOXES.clear();
        int y = oy;
        for (Line line : shown) {
            int lw = font.width(line.text());
            int x = switch (line.align()) {
                case LEFT -> ox;
                case CENTER -> ox + (cw - lw) / 2;
                case RIGHT -> ox + cw - lw;
            };
            if (!line.blank()) g.text(font, line.text(), x, y, 0xFFFFFFFF, c.textShadow);
            if (!preview) LINE_BOXES.add(new int[]{0, y - c.lineSpacing / 2, w, step});
            y += step;
        }
    }

    private static int colour(String chroma) {
        return ChromaColours.parse(chroma).getEffectiveColourRGB();
    }

    /** A rounded box in the background colour, with the four-corner border and picture, like the mod's. */
    private static void background(GuiGraphicsExtractor g, FeatureConfigs.ScoreboardBackground b, int w, int h) {
        int r = Math.clamp(b.radius, 0, Math.min(w, h) / 2);
        int bs = b.outline ? Math.min(b.outlineWidth, Math.min(w, h) / 2) : 0;
        String name = b.image == null ? "" : b.image.trim();
        if (!name.equals(imageName)) loadImage(name);
        if (image == null) {
            roundedRect(g, bs, bs, w - 2 * bs, h - 2 * bs, Math.max(0, r - bs), colour(b.colour));
        } else {
            image.advance(1f);
            int alpha = Math.clamp(b.imageOpacity, 0, 100) * 255 / 100;
            image.draw(g, 0, 0, w, h, (alpha << 24) | 0xFFFFFF);
        }
        if (bs > 0) border(g, b, w, h, r, bs);
    }

    /** A rectangle with its corners rounded to radius {@code r}, one row of pixels at a time near the corners. */
    private static void roundedRect(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int colour) {
        if (w <= 0 || h <= 0) return;
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, colour);
            return;
        }
        g.fill(x, y + r, x + w, y + h - r, colour);
        for (int i = 0; i < r; i++) {
            int inset = inset(r, i);
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, colour);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, colour);
        }
    }

    /** How far in a rounded corner of radius r starts on row i from its edge. */
    private static int inset(int r, int i) {
        return r - (int) Math.round(Math.sqrt((double) r * r - (double) (r - i) * (r - i)));
    }

    /**
     * The border: bs pixels wide following the rounded outline, each pixel's colour mixed from the four corner colours
     * by where it is, so it fades round the box like the mod's. Drawn in short runs, not one pixel at a time.
     */
    private static void border(GuiGraphicsExtractor g, FeatureConfigs.ScoreboardBackground b, int w, int h, int r, int bs) {
        int tl = colour(b.outlineTop), tr = colour(b.outlineTopRight), bl = colour(b.outlineBottom), br = colour(b.outlineBottomRight);
        int ri = Math.max(0, r - bs);
        for (int y = 0; y < h; y++) {
            float fy = h <= 1 ? 0 : (float) y / (h - 1);
            int left = lerp(tl, bl, fy), right = lerp(tr, br, fy);
            // Outer edge and inner edge of this row (the inner box is inset by bs with radius r - bs).
            int outer = y < r ? inset(r, y) : y >= h - r ? inset(r, h - 1 - y) : 0;
            boolean innerRow = y >= bs && y < h - bs;
            if (!innerRow) {
                // A top or bottom band row: the whole row, faded left to right in runs.
                run(g, outer, w - outer, y, w, left, right);
                continue;
            }
            int iy = y - bs, ih = h - 2 * bs;
            int inner = bs + (iy < ri ? inset(ri, iy) : iy >= ih - ri ? inset(ri, ih - 1 - iy) : 0);
            g.fill(outer, y, Math.max(outer, inner), y + 1, left);
            g.fill(Math.min(w - outer, w - inner), y, w - outer, y + 1, right);
        }
    }

    /** One row from x0 to x1, its colour going from {@code from} to {@code to} across the box's width, in 4-pixel runs. */
    private static void run(GuiGraphicsExtractor g, int x0, int x1, int y, int w, int from, int to) {
        for (int x = x0; x < x1; x += 4) {
            int end = Math.min(x1, x + 4);
            g.fill(x, y, end, y + 1, lerp(from, to, w <= 1 ? 0 : (float) x / (w - 1)));
        }
    }

    private static int lerp(int a, int b, float t) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return out;
    }

    /** The background picture: a PNG or GIF from config/sky2m/scoreboard. */
    private static void loadImage(String name) {
        imageName = name;
        if (image != null) {
            image.close();
            image = null;
        }
        if (name.isEmpty()) return;
        Path file = FOLDER.resolve(name);
        if (!Files.isRegularFile(file)) return;
        try {
            GifDecoder.Gif gif;
            if (GifDecoder.isGif(file)) {
                gif = GifDecoder.decode(file);
            } else {
                BufferedImage img = ImageIO.read(file.toFile());
                if (img == null) return;
                int[] argb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
                gif = new GifDecoder.Gif(img.getWidth(), img.getHeight(), List.of(new GifDecoder.Frame(argb, 100)));
            }
            image = new GifTexture("scoreboard " + name, gif);
        } catch (Exception e) {
            System.err.println("[Sky2M] Custom Scoreboard couldn't load " + name + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ chat actions

    /** The line under the mouse (screen coordinates), or null. */
    private static Line lineAt(double mouseX, double mouseY) {
        FeatureConfigs.CustomScoreboard c = config();
        if (c == null || !c.actions || !showing()) return null;
        double lx = (mouseX - drawnX) / drawnScale, ly = (mouseY - drawnY) / drawnScale;
        List<Line> shown = lines;
        for (int i = 0; i < LINE_BOXES.size() && i < shown.size(); i++) {
            int[] b = LINE_BOXES.get(i);
            if (lx >= b[0] && lx < b[0] + b[2] && ly >= b[1] && ly < b[1] + b[3]) return shown.get(i);
        }
        return null;
    }

    private static boolean click(double mouseX, double mouseY) {
        Line line = lineAt(mouseX, mouseY);
        if (line == null || line.command() == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getConnection().sendCommand(line.command().substring(1));
        mc.gui.setScreen(null);
        return true;
    }

    private static void hover(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        Line line = lineAt(mouseX, mouseY);
        if (line == null || line.hover() == null) return;
        g.setTooltipForNextFrame(Minecraft.getInstance().font, Component.literal(line.hover()).withStyle(ChatFormatting.GRAY), mouseX, mouseY);
    }
}
