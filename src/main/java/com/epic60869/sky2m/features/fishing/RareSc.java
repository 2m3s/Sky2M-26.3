package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.Expose;
import com.google.gson.reflect.TypeToken;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDraggableList;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rare sea creature extras from Riccio Fishing Utils (https://github.com/Ricciow/RiccioFishingUtils-Modern, GPL-3.0):
 * the rare alert with its own template, title length and sound volume, party messages with templates (catch and cocoon),
 * the rare sea creature display (count, average, total, time since), the lootshare range sphere around rare sea
 * creatures and their boss health bars (blue while shurikened).
 */
public final class RareSc {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_ALERT = "&6&l[&fα&6&l] {color}&l{name} &6&l[&fα&6&l]";
    private static final double LS_RADIUS = 30;
    private static final Pattern NAMETAG = Pattern.compile("^(?:﴾ )?\\[Lv\\d+] \\S+ (.+) ([\\d.,]+[kKmMbB]?)/([\\d.,]+[kKmMbB]?)❤(?: ﴿)?( ✯)?.*$");

    public enum PartyStyle {
        CLASSIC("Sky2M Classic"), RFU("Riccio (RFU)"), CUSTOM("Custom");

        private final String displayName;

        PartyStyle(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum DisplayData {
        COUNT("Count"), AVERAGE("Average"), TOTAL("Total"), TIME_SINCE("Time Since");

        private final String displayName;

        DisplayData(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Rare SC Alert Template", desc = "Use your own title for rare sea creatures you or your party catch, instead of the normal one. Off: the normal alert.")
        @ConfigEditorBoolean
        public boolean customAlert = true;

        @Expose
        @ConfigOption(name = "Rare SC Alert Preset", desc = "The title. {name}, {color} (its rarity colour), {style}, {article}, {article_upper}, {plural}, {mob} (with article), {mobs}. Use & or § for colours.")
        @ConfigEditorText
        public String alertPreset = DEFAULT_ALERT;

        @Expose
        @ConfigOption(name = "Rare SC Title Duration", desc = "Extra time the rare sea creature title stays on screen, in ticks.")
        @ConfigEditorSlider(minValue = 0, maxValue = 200, minStep = 5)
        public int titleExtraTicks = 0;

        @ConfigOption(name = "Preview Alert", desc = "Shows the rare sea creature alert with your settings.")
        @ConfigEditorButton(buttonText = "PREVIEW")
        public Runnable previewAlert = RareSc::previewAlert;

        @Expose
        @ConfigOption(name = "Rare SC Sound", desc = "Play a sound when a rare sea creature alert shows.")
        @ConfigEditorBoolean
        public boolean sound = true;

        @Expose
        @ConfigOption(name = "Sound Volume", desc = "Volume of the rare sea creature sound, in %.")
        @ConfigEditorSlider(minValue = 0, maxValue = 500, minStep = 5)
        public int soundVolume = 100;

        @Expose
        @ConfigOption(name = "Lootshare Range", desc = "A sphere with the 30 block lootshare range around rare sea creatures. Green border while you're inside it.")
        @ConfigEditorBoolean
        public boolean lootshareRange = true;

        @Expose
        @ConfigOption(name = "Filled Lootshare Range", desc = "Fill the lootshare sphere. Off: only rings.")
        @ConfigEditorBoolean
        public boolean filledLootshareRange = true;

        @Expose
        @ConfigOption(name = "Party Message Style", desc = "How your shared sea creature catches look in party chat. Sky2M Classic: \"--> A YETI has spawned <--\" (understood by Sky2M and Feesh users). Riccio Fishing Utils: \"WOAH! A Yeti just surfaced! Catch #12 after 2h 5m!\" (understood by Sky2M and RFU users). Custom Template: the two messages below.")
        @ConfigEditorDropdown
        public PartyStyle partyStyle = PartyStyle.CLASSIC;

        @Expose
        @ConfigOption(name = "Rare SC Party Message", desc = "Used with the Custom Template style, for a shared sea creature you catch. {article} (A/An), {name}, {total}, {count} (catches since the last one, including this), {time} (since the last one), {dh}, {coords}. Empty: the Sky2M Classic message. Which creatures are shared: Sea Creatures > Party Sharing.")
        @ConfigEditorText
        public String partyMessage = "";

        @Expose
        @ConfigOption(name = "Rare Cocoon Message", desc = "Used with the Custom Template style, for a shared sea creature you cocoon. {article}, {name}, {total}, {coords}. Empty: the Sky2M Classic message.")
        @ConfigEditorText
        public String cocoonMessage = "";

        @Expose
        @ConfigOption(name = "Double Hook Text", desc = "What {dh} in the party message turns into on a double hook.")
        @ConfigEditorText
        public String doubleHookText = "(Double Hook) ";

        @ConfigOption(name = "Preview Message", desc = "Shows the party message with your template, in chat only.")
        @ConfigEditorButton(buttonText = "PREVIEW")
        public Runnable previewMessage = RareSc::previewMessage;

        @ConfigOption(name = "Preview Cocoon Message", desc = "Shows the cocoon party message with your template, in chat only.")
        @ConfigEditorButton(buttonText = "PREVIEW")
        public Runnable previewCocoon = RareSc::previewCocoon;

        @Expose
        @ConfigOption(name = "Boss Health Bars", desc = "Health bars for rare sea creatures near you. Move them in /s2 gui.")
        @ConfigEditorBoolean
        public boolean bossHealthBars = true;

        @Expose
        @ConfigOption(name = "Blue Bar on Shuriken", desc = "The health bar turns blue while the sea creature is hit by a Shuriken (✯).")
        @ConfigEditorBoolean
        public boolean shurikenBar = true;

        @Expose
        @ConfigOption(name = "Rare SC Display", desc = "A HUD with your catches of the rare sea creatures of the area you're fishing in. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean display = true;

        @Expose
        @ConfigOption(name = "Only Display When Fishing", desc = "Only show the rare SC display while you hold a fishing rod, or caught something in the last minute.")
        @ConfigEditorBoolean
        public boolean displayOnlyFishing = true;

        @Expose
        @ConfigOption(name = "Display Data Order", desc = "Count: catches since the last one. Average: average of those counts. Total: all catches. Time Since: since the last one. Drag to reorder.")
        @ConfigEditorDraggableList
        public List<DisplayData> displayOrder = new ArrayList<>(List.of(DisplayData.COUNT, DisplayData.AVERAGE, DisplayData.TOTAL, DisplayData.TIME_SINCE));
    }

    // ------------------------------------------------------------------------------------------------ stats

    private static final class Stats {
        int total;
        int count;
        long time;
        String area = "";
        List<Integer> history = new ArrayList<>();
    }

    private static final Map<String, Stats> RECORDS = new LinkedHashMap<>();
    private static long lastCatchAt;

    private record Mob(int standId, String name, FishingData.SeaCreature creature, String health, String max, double fraction, boolean shurikened) {}

    private static volatile List<Mob> mobs = List.of();
    private static int ticks;

    private RareSc() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.fishing.rareSc;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("rare-sc.json");
    }

    public static void init() {
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> mobs = List.of());
        ClientTickEvents.END_CLIENT_TICK.register(RareSc::tick);
        Sky2MWorldRender.register(RareSc::render);

        Sky2MHuds.setting("rare_sc_display", () -> config() != null && config().display);
        Sky2MHuds.register("rare_sc_display", "Rare SC Display", () -> config() != null && config().display,
            RareSc::displayLines,
            List.of(SbcItems.parseLegacy("§6§lYeti: §e42 §7(§e57§7) §6[§e3§6] §f12m 4s"),
                SbcItems.parseLegacy("§c§lThunder: §e8 §7(§e120§7) §c[§e5§c] §f31m 10s")),
            8, 300);
        Sky2MHuds.setting("rare_sc_health_bars", () -> config() != null && config().bossHealthBars);
        Sky2MHuds.registerCustom("rare_sc_health_bars", "Boss Health Bars", () -> config() != null && config().bossHealthBars,
            new HealthBars(), 120, 40);
    }

    private static void load() {
        try {
            Path file = file();
            if (!Files.exists(file)) return;
            Map<String, Stats> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, Stats>>() {}.getType());
            if (loaded != null) RECORDS.putAll(loaded);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read rare sea creature stats: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(RECORDS), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save rare sea creature stats: " + e.getMessage());
        }
    }

    /**
     * Counts a sea creature you caught. Returns the variables for the party message ({name}, {total}, {count}, {time},
     * {dh}, {coords}) as they were before this catch changes them, or null if the creature isn't rare.
     */
    static Map<String, String> onCatch(FishingData.SeaCreature creature, boolean doubleHook) {
        String area = Sky2MLocation.area();
        long now = System.currentTimeMillis();
        lastCatchAt = now;
        Map<String, String> vars = null;
        for (Map.Entry<String, Stats> entry : RECORDS.entrySet()) {
            if (!entry.getKey().equals(creature.name()) && entry.getValue().area.equals(area)) entry.getValue().count++;
        }
        if (creature.rare()) {
            Stats record = RECORDS.computeIfAbsent(creature.name(), k -> new Stats());
            Config c = config();
            vars = new HashMap<>();
            vars.put("name", creature.name());
            vars.put("article", article(creature.name()));
            vars.put("first", record.total > 0 ? "" : "1");
            vars.put("total", String.valueOf(record.total + (doubleHook ? 2 : 1)));
            vars.put("count", String.valueOf(record.count + 1));
            vars.put("time", record.total > 0 ? duration(now - record.time) : "First Catch");
            vars.put("dh", doubleHook && c != null ? c.doubleHookText : "");
            vars.put("coords", coords());
            record.history.add(record.count);
            while (record.history.size() > 200) record.history.removeFirst();
            record.count = 0;
            record.total += doubleHook ? 2 : 1;
            record.time = now;
            record.area = area;
        }
        save();
        return vars;
    }

    static void onOwnCocoon(FishingData.SeaCreature creature) {
        if (!creature.rare()) return;
        RECORDS.computeIfAbsent(creature.name(), k -> new Stats()).total++;
        save();
    }

    /** The party message for a catch from your template, or null for the normal message. */
    static String catchMessage(Map<String, String> vars) {
        Config c = config();
        if (c == null || vars == null) return null;
        if (c.partyStyle == PartyStyle.RFU) {
            return fill("WOAH! {article} {name} just surfaced! {dh}Catch #{count}" + (vars.getOrDefault("first", "").isEmpty() ? " after {time}!" : "!"), vars);
        }
        if (c.partyStyle != PartyStyle.CUSTOM || c.partyMessage == null || c.partyMessage.isBlank()) return null;
        return fill(c.partyMessage, vars);
    }

    /** The party message for a cocoon from your template, or null for the normal message. */
    static String cocoonMessage(FishingData.SeaCreature creature) {
        Config c = config();
        if (c == null || c.partyStyle == PartyStyle.CLASSIC) return null;
        if (c.partyStyle == PartyStyle.CUSTOM && (c.cocoonMessage == null || c.cocoonMessage.isBlank())) return null;
        Stats record = RECORDS.get(creature.name());
        Map<String, String> vars = new HashMap<>();
        vars.put("name", creature.name());
        vars.put("article", article(creature.name()));
        vars.put("total", String.valueOf((record == null ? 0 : record.total) + 1));
        vars.put("coords", coords());
        return fill(c.partyStyle == PartyStyle.RFU ? "WOAH! {article} {name} was cocooned!" : c.cocoonMessage, vars);
    }

    private static String fill(String template, Map<String, String> vars) {
        String name = vars.getOrDefault("name", "");
        // "a {name}" becomes "an Ent" for names starting with a vowel, and just the name for "The ...".
        Matcher article = Pattern.compile("(?i)\\b(a)\\s\\{name}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (article.find()) {
            String replacement = name.regionMatches(true, 0, "The ", 0, 4) ? name
                : article.group(1) + (!name.isEmpty() && "aeiou".indexOf(Character.toLowerCase(name.charAt(0))) >= 0 ? "n " : " ") + name;
            article.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        article.appendTail(out);
        String text = out.toString();
        for (Map.Entry<String, String> var : vars.entrySet()) text = text.replace("{" + var.getKey() + "}", var.getValue());
        return text.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").replaceAll(" {2,}", " ").trim();
    }

    /** "A" or "An", and nothing for names that start with "The". */
    private static String article(String name) {
        if (name.regionMatches(true, 0, "The ", 0, 4)) return "";
        return FishingData.article(name, false);
    }

    private static String coords() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        BlockPos pos = mc.player.blockPosition();
        return "X: " + pos.getX() + ", Y: " + pos.getY() + ", Z: " + pos.getZ();
    }

    /** The two largest units, "18d 17h", "2h 5m" or "45s"; just "18d" when the second unit is 0. */
    private static String duration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long[] values = {seconds / 86400, seconds % 86400 / 3600, seconds % 3600 / 60, seconds % 60};
        String[] units = {"d", "h", "m", "s"};
        for (int i = 0; i < 4; i++) {
            if (values[i] == 0 && i < 3) continue;
            String out = values[i] + units[i];
            if (i < 3 && values[i + 1] > 0) out += " " + values[i + 1] + units[i + 1];
            return out;
        }
        return "0s";
    }

    // ------------------------------------------------------------------------------------------------ previews

    public static void previewMessage() {
        Config c = config();
        if (c == null) return;
        Map<String, String> vars = new HashMap<>(Map.of("name", "Great White Shark", "article", "A", "total", "100", "count", "100",
            "time", "2h 5m", "dh", c.doubleHookText == null ? "" : c.doubleHookText, "coords", coords()));
        String message = catchMessage(vars);
        if (message == null) message = "--> A GREAT WHITE SHARK has spawned <--";
        Sky2MAlerts.chat(Component.literal("Party message: ").withStyle(net.minecraft.ChatFormatting.GRAY)
            .append(Component.literal(message).withStyle(net.minecraft.ChatFormatting.BLUE)));
    }

    public static void previewCocoon() {
        Config c = config();
        if (c == null) return;
        FishingData.SeaCreature shark = FishingData.creature("Great White Shark");
        String message = shark == null ? null : cocoonMessage(shark);
        if (message == null) message = "--> A GREAT WHITE SHARK was cocooned <--";
        Sky2MAlerts.chat(Component.literal("Party message: ").withStyle(net.minecraft.ChatFormatting.GRAY)
            .append(Component.literal(message).withStyle(net.minecraft.ChatFormatting.BLUE)));
    }

    public static void previewAlert() {
        Config c = config();
        FishingData.SeaCreature creature = FishingData.creature("Great White Shark");
        if (c == null || creature == null) return;
        show(c, creature, null);
    }

    // ------------------------------------------------------------------------------------------------ alert

    /** Shows the rare alert from the template; false if it's turned off and the normal alert should show. */
    static boolean alert(FishingData.SeaCreature creature, boolean doubleHook, Component player) {
        Config c = config();
        if (c == null || !c.customAlert) return false;
        Component title = alertTitle(c, creature);
        if (doubleHook) title = title.copy().append(Component.literal(" X2").withStyle(net.minecraft.ChatFormatting.RED, net.minecraft.ChatFormatting.BOLD));
        show(c, title, player);
        return true;
    }

    private static void show(Config c, FishingData.SeaCreature creature, Component player) {
        show(c, alertTitle(c, creature), player);
    }

    private static void show(Config c, Component title, Component player) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            mc.gui.hud.setTimes(5, 40 + Math.max(0, c.titleExtraTicks), 10);
            mc.gui.hud.setTitle(title);
            mc.gui.hud.setSubtitle(player == null ? Component.empty() : player);
        });
        if (c.sound) Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, Math.max(0, c.soundVolume) / 100f);
    }

    private static Component alertTitle(Config c, FishingData.SeaCreature creature) {
        String template = c.alertPreset == null || c.alertPreset.isBlank() ? DEFAULT_ALERT : c.alertPreset;
        String name = creature.name();
        String plural = name.endsWith("s") ? name : name + "s";
        String article = FishingData.article(name, true);
        String text = template.replace("{article_upper}", FishingData.article(name, false))
            .replace("{article}", article)
            .replace("{name}", name)
            .replace("{style}", creature.rarity().colour.toString())
            .replace("{color}", creature.rarity().colour.toString())
            .replace("{plural}", plural)
            .replace("{mobs}", plural)
            .replace("{mob}", article + " " + name);
        return SbcItems.parseLegacy(text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1"));
    }

    // ------------------------------------------------------------------------------------------------ nearby rare mobs

    private static void tick(Minecraft mc) {
        Config c = config();
        ClientLevel level = mc.level;
        if (c == null || level == null || mc.player == null || !Sky2MLocation.onSkyblock() || !(c.bossHealthBars || c.lootshareRange)) {
            if (!mobs.isEmpty()) mobs = List.of();
            return;
        }
        if (++ticks % 5 != 0) return;
        List<Mob> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName() || stand.distanceTo(mc.player) > 100) continue;
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            if (!name.contains("[Lv") || !name.contains("❤")) continue;
            Matcher m = NAMETAG.matcher(name);
            if (!m.matches()) continue;
            String base = m.group(1).replace("Corrupted ", "").trim();
            FishingData.SeaCreature creature = FishingData.creature(base);
            if (creature == null || !creature.rare()) continue;
            double health = parseHealth(m.group(2)), max = parseHealth(m.group(3));
            if (health <= 0 || max <= 0) continue;
            found.add(new Mob(stand.getId(), creature.name(), creature, m.group(2), m.group(3), Math.min(1, health / max), m.group(4) != null));
        }
        mobs = found;
    }

    private static double parseHealth(String text) {
        try {
            text = text.replace(",", "");
            double multiplier = 1;
            char last = Character.toLowerCase(text.charAt(text.length() - 1));
            if (last == 'k') multiplier = 1e3;
            else if (last == 'm') multiplier = 1e6;
            else if (last == 'b') multiplier = 1e9;
            if (multiplier != 1) text = text.substring(0, text.length() - 1);
            return Double.parseDouble(text) * multiplier;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------------------ lootshare range

    private static void render(PrimitiveCollector collector) {
        Config c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.lootshareRange || mc.level == null || mc.player == null) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        for (Mob mob : mobs) {
            Entity stand = mc.level.getEntity(mob.standId());
            if (stand == null) continue;
            // The nametag floats about a block and a half above the creature's feet.
            Vec3 centre = stand.getPosition(partial).add(0, -1.2, 0);
            boolean inside = mc.player.position().distanceTo(centre) < LS_RADIUS;
            int border = inside ? 0xE655FF55 : 0xE6FFFFFF;
            if (c.filledLootshareRange) {
                collector.submitSphere(centre, (float) LS_RADIUS, 32, 32, 0x32FFFFFF);
            } else {
                for (int h = -24; h <= 24; h += 8) {
                    float radius = (float) Math.sqrt(LS_RADIUS * LS_RADIUS - h * h);
                    collector.submitOutlinedCircle(centre.add(0, h, 0), radius, 0.2f, 64, 0x99FFFFFF);
                }
            }
            collector.submitOutlinedCircle(centre, (float) LS_RADIUS, 0.3f, 96, border);
        }
    }

    // ------------------------------------------------------------------------------------------------ boss health bars

    private static final class HealthBars implements Sky2MHuds.CustomHud {
        private static final int WIDTH = 220;

        private List<Mob> shown() {
            Config c = config();
            if (c == null) return List.of();
            Minecraft mc = Minecraft.getInstance();
            List<Mob> list = new ArrayList<>(mobs);
            if (mc.player != null) {
                list.sort(java.util.Comparator.comparingDouble(m -> {
                    Entity e = mc.level == null ? null : mc.level.getEntity(m.standId());
                    return e == null ? 1e9 : e.distanceToSqr(mc.player);
                }));
            }
            return list.size() > 5 ? list.subList(0, 5) : list;
        }

        @Override
        public int width() {
            return WIDTH;
        }

        @Override
        public int height() {
            return Math.max(1, shown().size()) * 12;
        }

        @Override
        public boolean visible() {
            return !shown().isEmpty();
        }

        @Override
        public void render(GuiGraphicsExtractor g, boolean preview) {
            Config c = config();
            if (c == null) return;
            var font = Minecraft.getInstance().font;
            List<Mob> list = shown();
            if (list.isEmpty() && preview) {
                FishingData.SeaCreature sample = FishingData.creature("Thunder");
                list = List.of(new Mob(-1, "Thunder", sample, "12M", "35M", 0.35, c.shurikenBar));
            }
            int y = 0;
            for (Mob mob : list) {
                int colour = mob.shurikened() && c.shurikenBar ? 0xFF55AAFF
                    : mob.fraction() > 0.5 ? 0xFF55FF55 : mob.fraction() > 0.25 ? 0xFFFFFF55 : 0xFFFF5555;
                String label = mob.name() + (mob.shurikened() ? " ✯" : "");
                String hp = mob.health() + "/" + mob.max() + " ❤";
                g.text(font, label, 0, y + 2, colour, true);
                int barX = font.width(label) + 4;
                int barEnd = WIDTH - font.width(hp) - 4;
                if (barEnd - barX > 10) {
                    g.fill(barX, y + 1, barEnd, y + 10, 0xFF000000);
                    g.fill(barX + 1, y + 2, barX + 1 + (int) ((barEnd - barX - 2) * mob.fraction()), y + 9, colour);
                }
                g.text(font, hp, WIDTH - font.width(hp), y + 2, colour, true);
                y += 12;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ rare SC display

    private static List<Component> displayLines() {
        Config c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || mc.player == null) return List.of();
        long now = System.currentTimeMillis();
        if (c.displayOnlyFishing && !(mc.player.getMainHandItem().is(net.minecraft.world.item.Items.FISHING_ROD) || now - lastCatchAt < 60_000)) return List.of();
        String area = Sky2MLocation.area();
        List<Component> lines = new ArrayList<>();
        for (FishingData.SeaCreature creature : FishingData.creatures()) {
            Stats record = RECORDS.get(creature.name());
            if (!creature.rare() || record == null || record.total <= 0 || !record.area.equals(area)) continue;
            String colour = creature.rarity().colour.toString();
            StringBuilder line = new StringBuilder(colour + "§l" + creature.name() + ":");
            for (DisplayData data : c.displayOrder == null ? List.<DisplayData>of() : c.displayOrder) {
                if (data == null) continue;
                switch (data) {
                    case COUNT -> line.append(" §e").append(record.count);
                    case AVERAGE -> {
                        double average = record.history.stream().mapToInt(Integer::intValue).average().orElse(0);
                        line.append(" §7(§e").append((int) Math.ceil(average)).append("§7)");
                    }
                    case TOTAL -> line.append(' ').append(colour).append("[§e").append(record.total).append(colour).append("]");
                    case TIME_SINCE -> line.append(" §f").append(duration(now - record.time));
                }
            }
            lines.add(SbcItems.parseLegacy(line.toString()));
        }
        return lines;
    }
}
