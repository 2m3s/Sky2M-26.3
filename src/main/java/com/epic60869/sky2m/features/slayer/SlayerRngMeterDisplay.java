package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slayer RNG meter HUD, ported from SkyHanni's SlayerRngMeterDisplay (https://github.com/hannibal002/SkyHanni,
 * LGPL-2.1): the drop picked in each slayer's RNG Meter menu (or the Selected Drop on Maddox's Slayer menu), the XP it
 * needs, and the meter's progress from the "RNG Meter - 12,345 Stored XP" line after each boss. The XP one boss adds is
 * the difference between two of those lines, as in SkyHanni, else the tier's slayer XP. When the meter goes down (or
 * Hypixel says "RNG METER!") the drop came and it starts again from 0.
 * Unlike SkyHanni, the needed XP is read from the drop's own "x/y" progress in the menu, not NEU's rngscore table.
 * Saved per slayer in config/sky2m/slayer-rng-meter.json.
 */
public final class SlayerRngMeterDisplay {
    /** "   RNG Meter - 12,345 Stored XP" (SkyHanni's slayer.rngmeter.update, without colours). */
    private static final Pattern UPDATE = Pattern.compile("RNG Meter - ([\\d,.]+[kKmM]?) Stored XP");
    /** "Revenant Horror RNG Meter" (SkyHanni's slayer.rngmeter.inventoryname). */
    private static final Pattern INVENTORY = Pattern.compile("^(.*) RNG Meter$");
    /** The "1,234/100k" progress on an RNG meter drop. */
    private static final Pattern PROGRESS = Pattern.compile("([\\d,.]+[kKmMbB]?)/([\\d,.]+[kKmMbB]?)\\s*$");
    /** "Enchanted Book (Duplex I)" (SkyHanni's book.format) -> "Duplex I". */
    private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\((.*)\\)$");
    /** The quest's tier on the scoreboard, "Revenant Horror IV". */
    private static final Pattern TIER = Pattern.compile("(Revenant Horror|Atoned Horror|Tarantula Broodfather|Conjoined Brood"
        + "|Sven Packmaster|Voidgloom Seraph|Inferno Demonlord|Riftstalker Bloodfiend) ([IVX]+)");
    private static final Set<String> SLAYERS = Set.of("Revenant Horror", "Tarantula Broodfather", "Sven Packmaster",
        "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend");
    /** Slayer XP per boss for tiers I-V (Hypixel's values), used until two meter updates give the real gain. */
    private static final long[] TIER_XP = {5, 25, 100, 500, 1500};
    private static final long[] VAMPIRE_TIER_XP = {10, 25, 60, 120, 150};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One slayer's meter, as SkyHanni's SlayerRngMeterStorage. -1 is "not known yet". */
    private static final class Meter {
        String item = "";
        long required = -1;
        long current = -1;
        long gainPerBoss = -1;
    }

    private static final Map<String, Meter> METERS = new LinkedHashMap<>();
    private static Path file;
    /** The slayer whose quest you have, kept after the quest ends so the closing "Stored XP" line still lands. */
    private static String lastSlayer;
    private static long droppedAt;
    private static List<Component> lines = List.of();
    private static int ticks;

    private SlayerRngMeterDisplay() {}

    /** One slayer's known meter, for {@link SlayerRngAdvisor}. {@code required} and {@code current} are -1 when not read yet. */
    record MeterInfo(String slayer, String item, long current, long required, long gainPerBoss) {}

    /** The meters this class has read from the menus and chat (the same data its HUD uses). */
    static List<MeterInfo> meters() {
        List<MeterInfo> out = new ArrayList<>();
        METERS.forEach((slayer, m) -> out.add(new MeterInfo(slayer, m.item, m.current, m.required, m.gainPerBoss)));
        return out;
    }

    private static FeatureConfigs.SlayerRngMeterDisplay config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.rngMeterDisplay;
    }

    private static boolean enabled() {
        FeatureConfigs.SlayerRngMeterDisplay c = config();
        return c != null && c.enabled && Sky2MLocation.onSkyblock();
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("slayer-rng-meter.json");
        load();
        Sky2MHuds.register("slayer_rng_meter", "Slayer RNG Meter", () -> config() != null && config().enabled,
            () -> lines,
            List.of(Component.literal("Scythe Blade: ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("63.2%").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" (1,264,000 / 2,000,000)").withStyle(ChatFormatting.GRAY)),
                Component.literal("~491 bosses left").withStyle(ChatFormatting.GRAY)), 8, 260);
        Sky2MHuds.setting("slayer_rng_meter", () -> config() != null && config().enabled);
        Sky2MChat.onChat(message -> {
            if (enabled()) onChat(message.text().trim());
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 != 0) return;
            if (!enabled()) {
                lines = List.of();
                return;
            }
            String quest = SlayerArea.questSlayer();
            if (quest != null) lastSlayer = quest;
            lines = quest == null ? List.of() : buildLines(quest);
        });
        // SkyHanni reads the menu once it's fully open; here it's read twice a second while open, as its items arrive late.
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            String title = ChatFormatting.stripFormatting(container.getTitle().getString());
            if (title == null) return;
            String trimmed = title.trim();
            Matcher m = INVENTORY.matcher(trimmed);
            String rngSlayer = m.matches() && SLAYERS.contains(m.group(1).trim()) ? m.group(1).trim() : null;
            boolean maddox = trimmed.equals("Slayer");
            if (rngSlayer == null && !maddox) return;
            int[] tick = {0};
            ScreenEvents.afterTick(screen).register(s -> {
                if (tick[0]++ % 10 != 5 || !enabled()) return;
                if (rngSlayer != null) readRngMeterMenu(container, rngSlayer);
                else readSlayerMenu(container);
            });
        });
    }

    // ---------------------------------------------------------------- chat

    private static void onChat(String text) {
        if (text.startsWith("RNG METER!")) {
            // Hypixel's own drop message: the meter is back at 0 (the drop stays selected, "Reselected").
            String slayer = currentSlayer();
            Meter meter = slayer == null ? null : METERS.get(slayer);
            if (meter != null && meter.current > 0) dropped(meter, meter.current);
            return;
        }
        Matcher m = UPDATE.matcher(text);
        if (!m.find()) return;
        String slayer = currentSlayer();
        if (slayer == null) return;
        long now = parse(m.group(1));
        if (now < 0) return;
        Meter meter = METERS.computeIfAbsent(slayer, k -> new Meter());
        long old = meter.current;
        meter.current = now;
        if (old >= 0) {
            long diff = now - old;
            if (diff > 0) {
                // A gain right after a drop is measured from 0, which is still one boss's worth.
                meter.gainPerBoss = diff;
            } else if (diff < 0 && System.currentTimeMillis() - droppedAt > 10_000) {
                dropped(meter, old);
                meter.current = now;
            }
        }
        FeatureConfigs.SlayerRngMeterDisplay c = config();
        if (c != null && c.warnEmpty && meter.item.isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No Slayer RNG Meter drop selected for " + slayer + "!").withStyle(ChatFormatting.RED));
        }
        save();
    }

    /** SkyHanni's "RNG Meter dropped at 98.1% XP (1,962/2,000)" message, and the meter back to 0. */
    private static void dropped(Meter meter, long old) {
        droppedAt = System.currentTimeMillis();
        meter.current = 0;
        if (meter.required > 0) {
            double share = Math.min(1, old / (double) meter.required);
            Sky2MAlerts.chat(Component.literal("RNG Meter ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("dropped at ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(String.format(Locale.US, "%.1f%% ", share * 100)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("XP (" + String.format(Locale.US, "%,d/%,d", old, meter.required) + ")").withStyle(ChatFormatting.GRAY)));
        }
        save();
    }

    private static String currentSlayer() {
        String quest = SlayerArea.questSlayer();
        return quest != null ? quest : lastSlayer;
    }

    // ---------------------------------------------------------------- menus

    /** Maddox's RNG Meter menu for one slayer: the drop marked SELECTED, and its "x/y" progress. */
    private static void readRngMeterMenu(AbstractContainerScreen<?> screen, String slayer) {
        List<Slot> slots = screen.getMenu().slots;
        boolean any = false;
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            any = true;
            List<String> lore = lore(stack);
            boolean selected = false;
            long current = -1, required = -1;
            for (String line : lore) {
                if (line.equals("SELECTED") || line.startsWith("SELECTED ")) selected = true;
                Matcher m = PROGRESS.matcher(line);
                if (m.find()) {
                    current = parse(m.group(1));
                    required = parse(m.group(2));
                }
            }
            if (!selected) continue;
            setGoal(slayer, itemName(stack), required, current);
            return;
        }
        // The menu loaded and nothing is selected.
        if (any) setGoal(slayer, "", -1, -1);
    }

    /** Maddox's Slayer menu: slot 35 is the open quest's slayer, with its "Selected Drop" line (SkyHanni's readSlayerInventory). */
    private static void readSlayerMenu(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= 35) return;
        ItemStack stack = slots.get(35).getItem();
        if (stack.isEmpty()) return;
        List<String> lore = lore(stack);
        if (lore.isEmpty() || !SLAYERS.contains(lore.getFirst())) return;
        String slayer = lore.getFirst();
        for (int i = 0; i + 1 < lore.size(); i++) {
            if (!lore.get(i).equals("Selected Drop")) continue;
            String name = lore.get(i + 1);
            Matcher book = BOOK.matcher(name);
            if (book.matches()) name = book.group(1);
            Meter meter = METERS.get(slayer);
            // The XP needed is only on the RNG Meter menu: keep it when the drop is the same.
            boolean same = meter != null && meter.item.equals(name);
            setGoal(slayer, name, same ? meter.required : -1, -1);
            return;
        }
    }

    private static void setGoal(String slayer, String item, long required, long current) {
        Meter meter = METERS.computeIfAbsent(slayer, k -> new Meter());
        boolean changed = !meter.item.equals(item) || required != meter.required || current >= 0 && current != meter.current;
        if (!changed) return;
        meter.item = item;
        meter.required = required;
        if (current >= 0) meter.current = current;
        save();
    }

    private static String itemName(ItemStack stack) {
        String name = ChatFormatting.stripFormatting(Compat.realName(stack).getString());
        if (name == null) return "?";
        Matcher book = BOOK.matcher(name.trim());
        return book.matches() ? book.group(1) : name.trim();
    }

    private static List<String> lore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return out;
        for (Component line : lore.lines()) {
            String text = ChatFormatting.stripFormatting(line.getString());
            if (text != null) out.add(text.trim());
        }
        return out;
    }

    // ---------------------------------------------------------------- HUD

    private static List<Component> buildLines(String slayer) {
        int tier = questTier(slayer);
        // As SkyHanni: tiers I and II don't fill the meter enough to be worth a line.
        if (tier == 1 || tier == 2) return List.of();
        Meter meter = METERS.get(slayer);
        List<Component> out = new ArrayList<>();
        if (meter == null || meter.item.isEmpty()) {
            out.add(System.currentTimeMillis() - droppedAt < 60_000
                ? Component.literal("RNG drop came!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal("No RNG Meter drop selected").withStyle(ChatFormatting.YELLOW));
            return out;
        }
        if (meter.required <= 0) {
            out.add(Component.literal(meter.item + ": ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("open the RNG Meter menu").withStyle(ChatFormatting.RED)));
            return out;
        }
        if (meter.current < 0) {
            out.add(Component.literal(meter.item + ": ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("kill a boss to read the meter").withStyle(ChatFormatting.RED)));
            return out;
        }
        double share = Math.min(1, meter.current / (double) meter.required);
        out.add(Component.literal(meter.item + ": ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(String.format(Locale.US, "%.1f%%", share * 100)).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(String.format(Locale.US, " (%,d / %,d)", meter.current, meter.required)).withStyle(ChatFormatting.GRAY)));
        FeatureConfigs.SlayerRngMeterDisplay c = config();
        if (c == null || c.bossesLeft) {
            long perBoss = meter.gainPerBoss > 0 ? meter.gainPerBoss : tierXp(slayer, tier);
            if (perBoss > 0) {
                long missing = Math.max(0, meter.required - meter.current);
                long bosses = Math.max(1, (missing + perBoss - 1) / perBoss);
                out.add(Component.literal("~" + String.format(Locale.US, "%,d", bosses) + " bosses left").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(meter.gainPerBoss > 0 ? "" : " (tier XP)").withStyle(ChatFormatting.DARK_GRAY)));
            }
        }
        return out;
    }

    /** The quest's tier from the scoreboard (1-5), 5 for Atoned Horror / Conjoined Brood, 0 if unknown. */
    private static int questTier(String slayer) {
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = TIER.matcher(line);
            if (!m.find()) continue;
            String name = m.group(1);
            if (name.equals("Atoned Horror") || name.equals("Conjoined Brood")) return 5;
            if (!name.equals(slayer)) continue;
            return switch (m.group(2)) {
                case "I" -> 1;
                case "II" -> 2;
                case "III" -> 3;
                case "IV" -> 4;
                case "V" -> 5;
                default -> 0;
            };
        }
        return 0;
    }

    private static long tierXp(String slayer, int tier) {
        if (tier < 1 || tier > 5) return -1;
        return (slayer.equals("Riftstalker Bloodfiend") ? VAMPIRE_TIER_XP : TIER_XP)[tier - 1];
    }

    /** Parses "1,234", "100k", "1.3M". Returns -1 when invalid. */
    private static long parse(String text) {
        String t = text.replace(",", "").toLowerCase(Locale.ROOT);
        double multiplier = 1;
        if (t.endsWith("k")) multiplier = 1_000;
        else if (t.endsWith("m")) multiplier = 1_000_000;
        else if (t.endsWith("b")) multiplier = 1_000_000_000;
        if (multiplier != 1) t = t.substring(0, t.length() - 1);
        try {
            return Math.round(Double.parseDouble(t) * multiplier);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- saving

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, Meter> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Meter>>() {}.getType());
            if (loaded == null) return;
            loaded.forEach((slayer, meter) -> {
                if (meter == null) return;
                if (meter.item == null) meter.item = "";
                METERS.put(slayer, meter);
            });
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read slayer-rng-meter.json: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(METERS), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save slayer-rng-meter.json: " + e.getMessage());
        }
    }
}
