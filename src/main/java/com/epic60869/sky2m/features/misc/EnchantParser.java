package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MNick;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Enchant Parsing (features/misc/items/enchants, LGPL-2.1, itself modified from SkyblockAddons): colours
 * each enchant in a tooltip by its level (perfect / great / good / poor, from SkyHanni's Enchants.json), ultimate
 * enchants bold, sorts them (ultimate, then stacking, then by name) and lays them out Normal, Compressed (three per
 * line) or Stacked (one per line). Stacking enchants get their progress at the bottom. Perfect enchants are Chroma by
 * default, drawn with Sky2M's moving chroma text.
 */
public final class EnchantParser {
    /** The line contains only enchants (and what comes with them: commas, stacking numbers). */
    private static final Pattern EXCLUSIVE = Pattern.compile(
        "^(?:(?:§.)*[A-Za-z][A-Za-z '-]+ (?:[IVXLCDM]+|[0-9]+)(?:(?:§r)?, |$| (?:§r)?§8\\d{1,3}(?:[,.]\\d{1,3})*)[kKmMbB]?)+$");
    private static final Pattern ENCHANT = Pattern.compile(
        "(?<=^|, )(?:§.)*(?<enchant>[A-Za-z][A-Za-z '-]+) (?<levelNumeral>[IVXLCDM]+|[0-9]+)(?<stacking>(?:§r)?, |$| (?:§r)?§8\\d{1,3}(?:[,.]\\d{1,3})*[kKmMbB]?)");
    private static final Pattern STACKING_NUMBER = Pattern.compile("[\\d,.kKmMbB]+$");

    private static volatile Repo repo = new Repo(Map.of(), Map.of(), Map.of());

    // Cache so a tooltip hovered for a while isn't parsed every frame.
    private static List<Component> cachedBefore = List.of();
    private static List<Component> cachedAfter = List.of();
    private static String cachedConfig = "";

    private EnchantParser() {}

    private static EnchantParsingConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.enchantParsing;
    }

    public static void init() {
        SkyHanniRepo.load("Enchants", json -> repo = Repo.read(json));
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            EnchantParsingConfig config = config();
            if (config == null || (!config.colorParsing && !config.hideEnchantDescriptions)) return;
            // Other mods (REI searching) can ask for tooltips off the render thread, which would break the cache.
            if (!RenderSystem.isOnRenderThread() || !Compat.isOnSkyblock()) return;
            if (!repo.hasData()) return;
            CompoundTag enchants = Compat.customDataView(stack).getCompoundOrEmpty("enchantments");
            if (enchants.isEmpty()) return;
            Map<String, Integer> expected = new HashMap<>();
            for (String key : enchants.keySet()) expected.put(key, enchants.getIntOr(key, 0));
            try {
                parse(lines, expected, stack, config);
            } catch (RuntimeException e) {
                com.epic60869.sky2m.features.sbc.SbcCrashReports.report(e, "enchant parsing");
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ parsing

    private static void parse(List<Component> lore, Map<String, Integer> expected, ItemStack stack, EnchantParsingConfig config) {
        String configKey = configKey(config);
        if (configKey.equals(cachedConfig) && lore.equals(cachedBefore)) {
            lore.clear();
            lore.addAll(cachedAfter);
            return;
        }
        List<Component> before = List.copyOf(lore);
        cachedBefore = before;
        cachedConfig = configKey;
        cachedAfter = before;

        Repo data = repo;
        int start = -1, end = -1;
        for (int i = 0; i < lore.size(); i++) {
            String stripped = lore.get(i).getString();
            if (start == -1) {
                if (data.containsEnchantment(expected, SbcItems.legacy(lore.get(i)))) start = i;
            } else if (stripped.trim().isEmpty()) {
                end = i - 1;
                break;
            }
        }
        if (start == -1 || end == -1) return;

        TreeSet<FormattedEnchant> ordered = new TreeSet<>();
        List<Enchant> stackingEnchants = new ArrayList<>();
        List<Component> descriptionLines = new ArrayList<>();
        boolean singleColumn = false;
        int maxPerLine = 0;
        FormattedEnchant last = null;

        for (int i = start; i <= end; i++) {
            Matcher m = ENCHANT.matcher(SbcItems.legacy(lore.get(i)).replace("\n", ""));
            boolean containsEnchant = false;
            int onThisLine = 0;
            while (m.find()) {
                Enchant enchant = data.fromLore(m.group("enchant"));
                String numeral = m.group("levelNumeral");
                boolean isRoman = !numeral.chars().allMatch(Character::isDigit);
                int level = isRoman ? roman(numeral) : Integer.parseInt(numeral);
                String stacking = m.group("stacking");
                String stackingText = null;
                if (STACKING_NUMBER.matcher(ChatFormatting.stripFormatting(stacking).trim()).matches()) {
                    singleColumn = true;
                    stackingText = ChatFormatting.stripFormatting(stacking);
                }
                if (enchant.kind == Kind.STACKING) stackingEnchants.add(enchant);
                FormattedEnchant formatted = new FormattedEnchant(enchant, level, stackingText, isRoman);
                if (!ordered.add(formatted)) {
                    for (FormattedEnchant existing : ordered) {
                        if (existing.compareTo(formatted) == 0) {
                            formatted = existing;
                            break;
                        }
                    }
                }
                last = formatted;
                containsEnchant = true;
                onThisLine++;
            }
            maxPerLine = Math.max(maxPerLine, onThisLine);
            if (!containsEnchant && last != null) {
                last.description.add(lore.get(i));
                descriptionLines.add(lore.get(i));
            }
        }
        if (ordered.isEmpty() || maxPerLine == 0) return;

        boolean isBook = "ENCHANTED_BOOK".equals(Compat.neuName(stack));
        if (!config.colorParsing) {
            // Only hiding the descriptions.
            if (config.hideEnchantDescriptions && !isBook) {
                lore.removeAll(descriptionLines);
                cachedAfter = List.copyOf(lore);
            }
            return;
        }

        boolean miningTool = isMiningTool(before);
        String id = Compat.neuName(stack);
        List<Component> insert = new ArrayList<>();
        if (config.format == EnchantParsingConfig.EnchantFormat.NORMAL) {
            normalFormatting(insert, ordered, maxPerLine, config, isBook, id, miningTool);
        } else if (config.format == EnchantParsingConfig.EnchantFormat.COMPRESSED && !singleColumn) {
            compressedFormatting(insert, ordered, maxPerLine, isBook, id, miningTool);
        } else {
            for (FormattedEnchant enchant : ordered) {
                insert.add(enchant.component(id, miningTool));
                if (!config.hideEnchantDescriptions || isBook) insert.addAll(enchant.description);
            }
        }

        lore.subList(start, end + 1).clear();
        lore.addAll(start, insert);

        if (config.stackingEnchantProgress) {
            CompoundTag custom = Compat.customDataView(stack);
            for (Enchant stacking : stackingEnchants) {
                String progress = stacking.progressString(custom);
                if (!progress.isEmpty()) lore.add(Math.max(0, lore.size() - 1), SbcItems.parseLegacy(progress));
            }
        }
        cachedAfter = List.copyOf(lore);
    }

    private static void normalFormatting(List<Component> insert, TreeSet<FormattedEnchant> ordered, int maxPerLine,
                                         EnchantParsingConfig config, boolean isBook, String id, boolean miningTool) {
        MutableComponent line = Component.empty();
        FormattedEnchant lastElement = ordered.last();
        int i = 0;
        for (FormattedEnchant enchant : ordered) {
            boolean notLastOnLine = i % maxPerLine != maxPerLine - 1 && enchant != lastElement;
            line.append(enchant.component(id, miningTool));
            if (notLastOnLine) {
                line.append(comma());
            } else {
                insert.add(line);
                // Only adds descriptions if there were any to begin with.
                if (!config.hideEnchantDescriptions || isBook) insert.addAll(enchant.description);
                line = Component.empty();
            }
            i++;
        }
        if (!line.getSiblings().isEmpty()) insert.add(line);
    }

    private static void compressedFormatting(List<Component> insert, TreeSet<FormattedEnchant> ordered, int maxPerLine,
                                             boolean isBook, String id, boolean miningTool) {
        MutableComponent line = Component.empty();
        FormattedEnchant lastElement = ordered.last();
        int i = 0;
        for (FormattedEnchant enchant : ordered) {
            boolean notLastOnLine = i % 3 != 2 && enchant != lastElement;
            line.append(enchant.component(id, miningTool));
            if (isBook && maxPerLine == 1) {
                insert.add(line);
                insert.addAll(enchant.description);
                line = Component.empty();
            } else if (notLastOnLine) {
                line.append(comma());
            } else {
                insert.add(line);
                line = Component.empty();
            }
            i++;
        }
        if (!line.getSiblings().isEmpty()) insert.add(line);
    }

    private static Component comma() {
        return Component.literal(", ").withStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withItalic(false));
    }

    /** SkyHanni's ItemCategory.miningTools: pickaxes, drills and gauntlets, read from the rarity line. */
    private static boolean isMiningTool(List<Component> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            String line = lore.get(i).getString().trim();
            if (line.isEmpty()) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.matches(".*\\b(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|SPECIAL|VERY SPECIAL|ULTIMATE|ADMIN)\\b.*")) {
                return upper.contains("PICKAXE") || upper.contains("DRILL") || upper.contains("GAUNTLET");
            }
        }
        return false;
    }

    private static String configKey(EnchantParsingConfig c) {
        EnchantParsingConfig.Advanced a = c.advancedEnchantColors;
        return c.colorParsing + "|" + c.format + "|" + c.ultimateEnchantColor + "|" + c.perfectEnchantColor + "|" + c.boldPerfectEnchant
            + "|" + c.greatEnchantColor + "|" + c.goodEnchantColor + "|" + c.poorEnchantColor + "|" + c.hideEnchantDescriptions
            + "|" + c.stackingEnchantProgress + "|" + a.useAdvancedUltimateColor + a.advancedUltimateColor + a.useAdvancedPerfectColor
            + a.advancedPerfectColor + a.useAdvancedGreatColor + a.advancedGreatColor + a.useAdvancedGoodColor + a.advancedGoodColor
            + a.useAdvancedPoorColor + a.advancedPoorColor;
    }

    static int roman(String numeral) {
        int total = 0, previous = 0;
        for (int i = numeral.length() - 1; i >= 0; i--) {
            int value = switch (numeral.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }

    static String toRoman(int number) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length && number > 0; i++) {
            while (number >= values[i]) {
                out.append(numerals[i]);
                number -= values[i];
            }
        }
        return out.toString();
    }

    /** SkyHanni's shortFormat: 1,500 -> 1.5k, 25,000,000 -> 25M. */
    static String shortFormat(double value) {
        String[] suffixes = {"", "k", "M", "B", "T"};
        int index = 0;
        while (Math.abs(value) >= 1000 && index < suffixes.length - 1) {
            value /= 1000;
            index++;
        }
        String number = index == 0 ? String.valueOf((long) value)
            : (value >= 100 || value == Math.floor(value) ? String.valueOf((long) Math.floor(value)) : String.format(Locale.US, "%.1f", Math.floor(value * 10) / 10).replace(".0", ""));
        return number + suffixes[index];
    }

    // ------------------------------------------------------------------------------------------------ data

    private enum Kind { NORMAL, ULTIMATE, STACKING, DUMMY }

    private static final class Enchant implements Comparable<Enchant> {
        final Kind kind;
        final String nbtName;
        final String loreName;
        final int goodLevel;
        final int maxLevel;
        final String nbtNum;
        final String statLabel;
        final List<Integer> stackLevel;

        Enchant(Kind kind, String nbtName, String loreName, int goodLevel, int maxLevel, String nbtNum, String statLabel, List<Integer> stackLevel) {
            this.kind = kind;
            this.nbtName = nbtName;
            this.loreName = loreName;
            this.goodLevel = goodLevel;
            this.maxLevel = maxLevel;
            this.nbtNum = nbtNum;
            this.statLabel = statLabel;
            this.stackLevel = stackLevel;
        }

        static Enchant dummy(String name) {
            return new Enchant(Kind.DUMMY, name, name, 0, 0, "", "", List.of());
        }

        @Override
        public int compareTo(Enchant other) {
            boolean ultimate = kind == Kind.ULTIMATE, otherUltimate = other.kind == Kind.ULTIMATE;
            if (ultimate == otherUltimate) {
                boolean stacking = kind == Kind.STACKING, otherStacking = other.kind == Kind.STACKING;
                if (stacking == otherStacking) return loreName.compareTo(other.loreName);
                return stacking ? -1 : 1;
            }
            return ultimate ? -1 : 1;
        }

        MutableComponent component(int level, boolean isRoman, String id, boolean miningTool) {
            String text = loreName + " " + (isRoman ? toRoman(level) : String.valueOf(level));
            // Enchants not in the repo yet stay in Hypixel's blue.
            if (kind == Kind.DUMMY) return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.BLUE).withItalic(false));
            EnchantParsingConfig config = config();
            EnchantParsingConfig.Advanced advanced = config.advancedEnchantColors;
            if (kind == Kind.ULTIMATE) {
                if (advanced.useAdvancedUltimateColor) return styled(text, null, advanced.advancedUltimateColor, true);
                return styled(text, config.ultimateEnchantColor, null, true);
            }

            boolean perfect = level >= maxLevel;
            // Exceptions: a Stonk, or Efficiency V on anything but a mining tool (other than a Promising Shovel), is max.
            if (nbtName.equals("efficiency") && ("STONK_PICKAXE".equals(id) || (level == 5 && !miningTool && !"PROMISING_SHOVEL".equals(id)))) {
                perfect = true;
            }
            if (perfect) {
                if (advanced.useAdvancedPerfectColor) return styled(text, null, advanced.advancedPerfectColor, config.boldPerfectEnchant);
                return styled(text, config.perfectEnchantColor, null, config.boldPerfectEnchant);
            }
            if (level > goodLevel) {
                return advanced.useAdvancedGreatColor ? styled(text, null, advanced.advancedGreatColor, false) : styled(text, config.greatEnchantColor, null, false);
            }
            if (level == goodLevel) {
                return advanced.useAdvancedGoodColor ? styled(text, null, advanced.advancedGoodColor, false) : styled(text, config.goodEnchantColor, null, false);
            }
            return advanced.useAdvancedPoorColor ? styled(text, null, advanced.advancedPoorColor, false) : styled(text, config.poorEnchantColor, null, false);
        }

        String progressString(CompoundTag custom) {
            String label = statLabel.replaceAll("([a-z])([A-Z])", "$1 $2");
            if (!label.isEmpty()) label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
            label = label.replace("Xp", "XP");
            long progress = Math.round(custom.getDoubleOr(nbtNum, custom.getIntOr(nbtNum, 0)));
            if (progress == 0) return "";
            Integer next = null;
            for (int level : stackLevel) {
                if (level > progress && (next == null || level < next)) next = level;
            }
            String tail = next == null ? "(Maxed)" : "/ " + shortFormat(next);
            return "§7" + label + ": §c" + shortFormat(progress) + " §7" + tail;
        }
    }

    /** One enchant line's text in its colour (or chroma, letter by letter), optionally bold. */
    private static MutableComponent styled(String text, EnchantParsingConfig.EnchantColour colour, String customColour, boolean bold) {
        Style base = Style.EMPTY.withItalic(false).withBold(bold);
        if (customColour != null) {
            int rgb;
            try {
                rgb = com.epic60869.sky2m.custom.util.ChromaColours.parse(customColour).getEffectiveColourRGB() & 0xFFFFFF;
            } catch (Exception e) {
                rgb = 0xFFFF55;
            }
            return Component.literal(text).withStyle(base.withColor(rgb));
        }
        if (colour != EnchantParsingConfig.EnchantColour.CHROMA) {
            return Component.literal(text).withStyle(base.withColor(colour.formatting));
        }
        MutableComponent out = Component.empty().withStyle(base);
        int n = Math.max(1, text.length());
        for (int i = 0; i < text.length(); i++) {
            int offset = Math.min(255, Math.round((float) i / n * 256f));
            out.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(base.withColor(Sky2MNick.CHROMA_MARK | offset)));
        }
        return out;
    }

    private static final class FormattedEnchant implements Comparable<FormattedEnchant> {
        final Enchant enchant;
        final int level;
        final String stacking;
        final boolean isRoman;
        final List<Component> description = new ArrayList<>();

        FormattedEnchant(Enchant enchant, int level, String stacking, boolean isRoman) {
            this.enchant = enchant;
            this.level = level;
            this.stacking = stacking;
            this.isRoman = isRoman;
        }

        @Override
        public int compareTo(FormattedEnchant other) {
            return enchant.compareTo(other.enchant);
        }

        Component component(String id, boolean miningTool) {
            MutableComponent component = enchant.component(level, isRoman, id, miningTool);
            if (stacking != null) {
                return Component.empty().append(component)
                    .append(Component.literal(stacking).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false).withBold(false)));
            }
            return component;
        }
    }

    private record Repo(Map<String, Enchant> normal, Map<String, Enchant> ultimate, Map<String, Enchant> stacking) {
        static Repo read(JsonObject json) {
            return new Repo(section(json, "NORMAL", Kind.NORMAL), section(json, "ULTIMATE", Kind.ULTIMATE), section(json, "STACKING", Kind.STACKING));
        }

        private static Map<String, Enchant> section(JsonObject json, String key, Kind kind) {
            Map<String, Enchant> out = new HashMap<>();
            if (!json.has(key)) return out;
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject(key).entrySet()) {
                JsonObject e = entry.getValue().getAsJsonObject();
                List<Integer> levels = new ArrayList<>();
                if (e.has("stackLevel")) e.getAsJsonArray("stackLevel").forEach(v -> levels.add(v.getAsInt()));
                out.put(entry.getKey().toLowerCase(Locale.ROOT), new Enchant(kind,
                    string(e, "nbtName"), string(e, "loreName"), e.has("goodLevel") ? e.get("goodLevel").getAsInt() : 0,
                    e.has("maxLevel") ? e.get("maxLevel").getAsInt() : 0, string(e, "nbtNum"), string(e, "statLabel"), levels));
            }
            return out;
        }

        private static String string(JsonObject e, String key) {
            return e.has(key) ? e.get(key).getAsString() : "";
        }

        boolean hasData() {
            return !normal.isEmpty() && !ultimate.isEmpty() && !stacking.isEmpty();
        }

        Enchant fromLore(String loreName) {
            String key = loreName.toLowerCase(Locale.ROOT);
            Enchant enchant = normal.get(key);
            if (enchant == null) enchant = ultimate.get(key);
            if (enchant == null) enchant = stacking.get(key);
            return enchant == null ? Enchant.dummy(loreName) : enchant;
        }

        boolean containsEnchantment(Map<String, Integer> enchants, String line) {
            if (!EXCLUSIVE.matcher(line).find()) return false;
            Matcher m = ENCHANT.matcher(line);
            while (m.find()) {
                Enchant enchant = fromLore(m.group("enchant"));
                if (!enchants.isEmpty()) {
                    if (enchants.containsKey(enchant.nbtName)) return true;
                } else {
                    String key = enchant.loreName.toLowerCase(Locale.ROOT);
                    if (normal.containsKey(key) || ultimate.containsKey(key) || stacking.containsKey(key)) return true;
                }
            }
            return false;
        }
    }
}
