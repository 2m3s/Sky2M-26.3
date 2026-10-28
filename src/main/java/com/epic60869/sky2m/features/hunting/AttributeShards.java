package com.epic60869.sky2m.features.hunting;

import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.Expose;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Attribute shards, from SkyHanni's AttributeShardsData and FusionData (https://github.com/hannibal002/SkyHanni,
 * features/inventory/attribute/, LGPL-2.1): NEU's constants/attribute_shards.json (names, rarities and levelling),
 * Hypixel's shard chat messages (caught, captured, charmed, fused, syphoned, sent to the Hunting Box), how many of each
 * shard your Hunting Box holds and how many you've syphoned, read from the Hunting Box and the Attribute Menu and kept
 * per profile in sky2m/hunting.json.
 */
public final class AttributeShards {
    public enum Source { SYPHON, FUSE, CHARM, HUNT, FISHING, SENT_TO_HUNTING_BOX, CAPTURED, FLOOR_DROP, GIVEN, BOSS }

    /** One shard from NEU's data. */
    public record Shard(String displayName, String bazaarName, String internalName, String abilityName, String rarity) {
        public String rarityColour() {
            return switch (rarity) {
                case "UNCOMMON" -> "§a";
                case "RARE" -> "§9";
                case "EPIC" -> "§5";
                case "LEGENDARY" -> "§6";
                case "MYTHIC" -> "§d";
                default -> "§f";
            };
        }

        /** The shard's item, made once (after NEU's items have loaded). */
        public ItemStack icon() {
            if (!RepoItems.itemsLoaded()) return new ItemStack(net.minecraft.world.item.Items.PRISMARINE_SHARD);
            return ICONS.computeIfAbsent(internalName, id -> RepoItems.displayName(id) == null
                ? new ItemStack(net.minecraft.world.item.Items.PRISMARINE_SHARD) : RepoItems.itemStack(id));
        }
    }

    /**
     * A shard gained or lost. {@code gain} is SkyHanni's ShardGainEvent: shards you got, for trackers (not ones fused,
     * syphoned or sent to the Hunting Box from your inventory).
     */
    public interface Listener {
        void onShard(Shard shard, int amount, Source source, boolean gain);
    }

    // ------------------------------------------------------------------------------------------------ patterns

    private static final Pattern HUNTING_BOX = Pattern.compile("^(?:\\((?<page>\\d+)/(?<pages>\\d+)\\) )?Hunting Box");
    private static final Pattern ATTRIBUTE_MENU = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Attribute Menu");
    public static final Pattern FUSION_BOX = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Fusion Box");
    public static final Pattern CONFIRM_FUSION = Pattern.compile("^Confirm Fusion");
    private static final Pattern SHARD_FUSION = Pattern.compile("^Shard Fusion");
    private static final Pattern SEARCH_QUERY = Pattern.compile("^Query: .+");
    private static final Pattern MENU_NAME = Pattern.compile("^(?<name>.+?) ?(?<tier>[IVXL]+)?$");
    private static final Pattern STATE = Pattern.compile("^Enabled: (?<state>.+)");
    // "Syphon 3 shards to level up!", or since October 2026 "Syphon 2 more to level up!".
    private static final Pattern SYPHON_AMOUNT = Pattern.compile("Syphon (?<amount>\\d+) (?:shards?|more) to (?:level up|unlock)!");
    private static final Pattern LORE_NAME = Pattern.compile("^(?<name>.+?) ?(?<tier>[IVXL]+)? \\(\\w+\\)$");
    private static final Pattern OWNED = Pattern.compile("Owned: (?<amount>[\\d,]+) Shards?");
    private static final Pattern REQUIRED_TO_FUSE = Pattern.compile("Required to fuse: (?<amount>\\d+)");
    private static final Pattern SYPHONED = Pattern.compile("^\\+(?<amount>\\d+) (?<attributeName>.+) Attribute \\(Level (?<level>\\d+)\\) - (?<untilNext>\\d+) more to upgrade!");
    private static final Pattern SYPHONED_MAXED = Pattern.compile("^\\+(?<amount>\\d+) (?<attributeName>.+) Attribute \\(Level (?<level>\\d+)\\) MAXED");
    private static final Pattern ENABLED = Pattern.compile("^(?<attributeName>.+) is now enabled!");
    private static final Pattern DISABLED = Pattern.compile("^(?<attributeName>.+) is now disabled!");
    private static final Pattern FUSION = Pattern.compile("^FUSION! You obtained(?: an?)? (?<shardName>.+) Shard(?: x(?<amount>\\d+))?!(?: NEW!)?");
    private static final Map<Pattern, Source> GAIN_PATTERNS = new LinkedHashMap<>();

    static {
        GAIN_PATTERNS.put(Pattern.compile("^You caught(?: [an]+)?(?: x(?<amount>\\d+))? (?<shardName>.+) Shards?!"), Source.HUNT);
        GAIN_PATTERNS.put(Pattern.compile("^. .+ CATCH! You caught(?: [an]+)? (?<shardName>.+) Shard(?: x(?<amount>\\d+))?!"), Source.FISHING);
        GAIN_PATTERNS.put(Pattern.compile("^LOOT SHARE!? You received (?:an?|(?<amount>\\d+)x?) (?<shardName>.+) Shards? (?:for assisting .*|from .*)!"), Source.HUNT);
        GAIN_PATTERNS.put(Pattern.compile("^CHARM! You charmed the .+ and received (?<amount>\\d+) (?<shardName>.+) Shards?!"), Source.CHARM);
        GAIN_PATTERNS.put(Pattern.compile("^You sent (?:an?|(?<amount>\\d+)) (?<shardName>.+) Shards? to your Hunting Box\\."), Source.SENT_TO_HUNTING_BOX);
        GAIN_PATTERNS.put(Pattern.compile("^CAPTURE! You (?:caught an?|found) .+ and (?:gained|as a reward (?:he|she|they|it) gave you) (?:an?|(?<amount>\\d+)x) (?<shardName>.+) Shard!"), Source.CAPTURED);
        GAIN_PATTERNS.put(Pattern.compile("^FLOOR DROP! You found (?<shardName>.+) Shard on the ground!"), Source.FLOOR_DROP);
        GAIN_PATTERNS.put(Pattern.compile("^You have been given a (?<shardName>.+)!"), Source.GIVEN);
        GAIN_PATTERNS.put(Pattern.compile("^SHARD! Your contribution earned you the (?<shardName>.+) Shard!"), Source.BOSS);
    }

    // ------------------------------------------------------------------------------------------------ data

    private static final Map<String, List<Integer>> LEVELLING = new HashMap<>();
    private static final Set<String> UNCONSUMABLE = new HashSet<>();
    private static final Map<String, Shard> BY_INTERNAL = new HashMap<>();
    private static final Map<String, Shard> BY_NAME = new HashMap<>();
    private static final Map<String, Shard> BY_ABILITY = new HashMap<>();
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<String, ItemStack> ICONS = new HashMap<>();

    static final class ShardData {
        @Expose int amountInBox;
        @Expose int amountSyphoned;
        @Expose boolean enabled;
    }

    static final class ProfileData {
        /** Shard display name -> data. */
        @Expose Map<String, ShardData> shards = new LinkedHashMap<>();
        /** Shard display name -> amount to collect, or -1 for "until maxed" (the Shard Tracker). */
        @Expose Map<String, Integer> tracked = new LinkedHashMap<>();
    }

    static final class Saved {
        @Expose Map<String, ProfileData> profiles = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private static Path file;
    private static Saved saved = new Saved();
    private static boolean dirty;
    private static int ticks;
    /** The Confirm Fusion menu's two input shards and amounts, and its output. */
    private static Shard fuseFirst, fuseSecond;
    private static int fuseFirstAmount, fuseSecondAmount;
    private static final Set<String> boxSeenShards = new HashSet<>();
    private static final Set<Integer> boxSeenPages = new HashSet<>();
    private static int lastBoxHash;

    private AttributeShards() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("hunting.json");
        load();
        RepoItems.runAsync(() -> {
            try {
                parse(JsonParser.parseString(RepoItems.neuRepoFile("constants/attribute_shards.json")).getAsJsonObject());
            } catch (Exception e) {
                System.err.println("[Sky2M] Couldn't load NEU's attribute_shards.json: " + e);
            }
        });
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ClientTickEvents.END_CLIENT_TICK.register(AttributeShards::tick);
    }

    private static synchronized void parse(JsonObject json) {
        Map<String, List<Integer>> levelling = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("attribute_levelling").entrySet()) {
            List<Integer> amounts = new ArrayList<>();
            e.getValue().getAsJsonArray().forEach(v -> amounts.add(v.getAsInt()));
            levelling.put(e.getKey(), amounts);
        }
        Set<String> unconsumable = new HashSet<>();
        json.getAsJsonArray("unconsumable_attributes").forEach(v -> unconsumable.add(v.getAsString()));
        List<Shard> shards = new ArrayList<>();
        json.getAsJsonArray("attributes").forEach(v -> {
            JsonObject o = v.getAsJsonObject();
            shards.add(new Shard(o.get("displayName").getAsString(), o.get("bazaarName").getAsString(),
                o.get("internalName").getAsString(), o.get("abilityName").getAsString(), o.get("rarity").getAsString()));
        });
        Minecraft.getInstance().execute(() -> {
            LEVELLING.clear();
            LEVELLING.putAll(levelling);
            UNCONSUMABLE.clear();
            UNCONSUMABLE.addAll(unconsumable);
            BY_INTERNAL.clear();
            BY_NAME.clear();
            BY_ABILITY.clear();
            for (Shard shard : shards) {
                BY_INTERNAL.put(shard.internalName(), shard);
                BY_NAME.put(shard.displayName().toLowerCase(Locale.ROOT), shard);
                BY_ABILITY.put(shard.abilityName().toLowerCase(Locale.ROOT), shard);
            }
        });
    }

    public static void onShard(Listener listener) {
        LISTENERS.add(listener);
    }

    public static boolean loaded() {
        return !BY_NAME.isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ lookups

    public static Shard byName(String name) {
        if (name == null) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (key.endsWith(" shard")) key = key.substring(0, key.length() - 6);
        return BY_NAME.get(key);
    }

    public static Shard byInternalName(String internalName) {
        return internalName == null ? null : BY_INTERNAL.get(internalName);
    }

    public static Shard byAbility(String ability) {
        return ability == null ? null : BY_ABILITY.get(ability.trim().toLowerCase(Locale.ROOT));
    }

    /** The shard an item is: by its attribute data, else its name ("Grove Shard"), else its lore's attribute line. */
    public static Shard of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String id = Compat.neuName(stack);
        if (id.startsWith("ATTRIBUTE_SHARD")) {
            CompoundTag attributes = Compat.customDataView(stack).getCompoundOrEmpty("attributes");
            for (String key : attributes.keySet()) {
                Shard shard = BY_INTERNAL.get("ATTRIBUTE_SHARD_" + key.toUpperCase(Locale.ROOT) + ";1");
                if (shard != null) return shard;
            }
        }
        if (id.startsWith("SHARD_") || id.startsWith("ATTRIBUTE_SHARD")) {
            for (Shard shard : BY_INTERNAL.values()) if (shard.bazaarName().equals(id)) return shard;
        }
        String name = Sky2MLocation.strip(stack.getHoverName().getString()).trim();
        if (name.endsWith(" Shard") || name.endsWith(" Shards")) {
            Shard shard = byName(name.replaceAll(" Shards?$", "").replaceFirst("^\\d+x ", ""));
            if (shard != null) return shard;
        }
        if (!id.isEmpty() && !id.startsWith("ATTRIBUTE_SHARD") && !id.startsWith("SHARD_")) return null;
        for (String line : lore(stack)) {
            Matcher m = LORE_NAME.matcher(line);
            if (m.matches()) {
                Shard shard = byAbility(m.group("name"));
                if (shard != null) return shard;
            }
        }
        Matcher m = MENU_NAME.matcher(name);
        return m.matches() ? byAbility(m.group("name")) : null;
    }

    // ------------------------------------------------------------------------------------------------ amounts

    private static ProfileData profile() {
        String id = Sky2MStorageSearch.currentProfile();
        return saved.profiles.computeIfAbsent(id == null || id.isEmpty() ? "default" : id, k -> new ProfileData());
    }

    private static ShardData data(Shard shard) {
        return profile().shards.computeIfAbsent(shard.displayName(), k -> new ShardData());
    }

    public static int amountInBox(Shard shard) {
        ShardData d = profile().shards.get(shard.displayName());
        return d == null ? 0 : d.amountInBox;
    }

    /** Shards still needed to max the attribute. */
    public static int amountUntilMax(Shard shard) {
        List<Integer> levels = LEVELLING.get(shard.rarity());
        if (levels == null) return 0;
        ShardData d = profile().shards.get(shard.displayName());
        int syphoned = d == null ? 0 : d.amountSyphoned;
        int total = 0;
        for (int amount : levels) total += amount;
        return Math.max(0, total - syphoned);
    }

    /** The Shard Tracker's shards: display name -> amount wanted (-1 to max). */
    static Map<String, Integer> tracked() {
        return profile().tracked;
    }

    static void markDirty() {
        dirty = true;
    }

    private static void syphoned(Shard shard, int level, int untilNext) {
        if (UNCONSUMABLE.contains(shard.bazaarName())) return;
        List<Integer> levels = LEVELLING.get(shard.rarity());
        if (levels == null || level > levels.size()) return;
        int total = 0;
        for (int i = 0; i < Math.min(level + 1, levels.size()); i++) total += levels.get(i);
        if (level >= levels.size()) untilNext = 0;
        data(shard).amountSyphoned = total - untilNext;
        dirty = true;
    }

    private static void changeBox(Shard shard, int amount) {
        ShardData d = data(shard);
        d.amountInBox = Math.max(0, d.amountInBox + amount);
        dirty = true;
    }

    private static void post(Shard shard, int amount, Source source, boolean gain) {
        // Shards you get go to your Hunting Box (except fusion inputs and syphons, which come out of it).
        changeBox(shard, amount);
        for (Listener listener : LISTENERS) {
            try {
                listener.onShard(shard, amount, source, gain);
            } catch (Exception e) {
                com.epic60869.sky2m.features.sbc.SbcCrashReports.report(e, "shard listener");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        if (!loaded() || !Sky2MLocation.onSkyblock()) return;
        Matcher m;
        if ((m = SYPHONED.matcher(text)).find()) {
            Shard shard = byAbility(m.group("attributeName"));
            if (shard == null) return;
            syphoned(shard, Integer.parseInt(m.group("level")), Integer.parseInt(m.group("untilNext")));
            post(shard, -Integer.parseInt(m.group("amount")), Source.SYPHON, false);
            return;
        }
        if ((m = SYPHONED_MAXED.matcher(text)).find()) {
            Shard shard = byAbility(m.group("attributeName"));
            if (shard == null) return;
            syphoned(shard, 10, 0);
            post(shard, -Integer.parseInt(m.group("amount")), Source.SYPHON, false);
            return;
        }
        if ((m = ENABLED.matcher(text)).find() || (m = DISABLED.matcher(text)).find()) {
            Shard shard = byAbility(m.group("attributeName"));
            if (shard != null && !UNCONSUMABLE.contains(shard.bazaarName())) {
                data(shard).enabled = text.endsWith("enabled!");
                dirty = true;
            }
            return;
        }
        for (Map.Entry<Pattern, Source> e : GAIN_PATTERNS.entrySet()) {
            m = e.getKey().matcher(text);
            if (!m.find()) continue;
            Shard shard = byName(m.group("shardName"));
            if (shard == null) return;
            int amount = m.group("amount") == null ? 1 : Integer.parseInt(m.group("amount"));
            post(shard, amount, e.getValue(), e.getValue() != Source.SENT_TO_HUNTING_BOX);
            return;
        }
        if ((m = FUSION.matcher(text)).find()) {
            Shard output = byName(m.group("shardName"));
            int amount = m.group("amount") == null ? 1 : Integer.parseInt(m.group("amount"));
            if (output != null) post(output, amount, Source.FUSE, false);
            if (fuseFirst != null) post(fuseFirst, -fuseFirstAmount, Source.FUSE, false);
            if (fuseSecond != null) post(fuseSecond, -fuseSecondAmount, Source.FUSE, false);
        }
    }

    // ------------------------------------------------------------------------------------------------ menus

    public static String openTitle() {
        if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)) return "";
        return Sky2MLocation.strip(screen.getTitle().getString());
    }

    public static boolean inHuntingBox() {
        return HUNTING_BOX.matcher(openTitle()).find();
    }

    public static boolean inFusionMachine() {
        String title = openTitle();
        return FUSION_BOX.matcher(title).find() || SHARD_FUSION.matcher(title).find() || CONFIRM_FUSION.matcher(title).find();
    }

    public static boolean inShardMenu() {
        String title = openTitle();
        return HUNTING_BOX.matcher(title).find() || ATTRIBUTE_MENU.matcher(title).find() || inFusionMachine();
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (dirty && ticks % 200 == 0) save();
        if (ticks % 5 != 0) return;
        if (!loaded() || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            lastBoxHash = 0;
            return;
        }
        String title = Sky2MLocation.strip(screen.getTitle().getString());
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) if (!(slot.container instanceof Inventory)) slots.add(slot);
        // Only re-read when something in the menu changed.
        int hash = title.hashCode();
        for (Slot slot : slots) hash = hash * 31 + System.identityHashCode(slot.getItem());
        if (hash == lastBoxHash) return;
        lastBoxHash = hash;
        Matcher box = HUNTING_BOX.matcher(title);
        if (box.find()) readHuntingBox(slots, box);
        else if (ATTRIBUTE_MENU.matcher(title).find()) readAttributeMenu(slots);
        else if (CONFIRM_FUSION.matcher(title).find()) readConfirmFusion(slots);
    }

    private static boolean validSlot(int index) {
        if (index < 9 || index > 44) return false;
        int column = index % 9;
        return column != 0 && column != 8;
    }

    private static void readHuntingBox(List<Slot> slots, Matcher titleMatch) {
        Set<String> inBox = new HashSet<>();
        boolean searching = false;
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!validSlot(slot.index)) {
                for (String line : lore(stack)) if (SEARCH_QUERY.matcher(line).find()) searching = true;
                continue;
            }
            Shard shard = of(stack);
            if (shard == null) continue;
            int tier = 0, toNext = 0, owned = -1;
            for (String line : lore(stack)) {
                Matcher m;
                if ((m = LORE_NAME.matcher(line)).matches() && m.group("tier") != null) tier = roman(m.group("tier"));
                if ((m = SYPHON_AMOUNT.matcher(line)).find()) toNext = Integer.parseInt(m.group("amount"));
                if ((m = OWNED.matcher(line)).find()) owned = Integer.parseInt(m.group("amount").replace(",", ""));
            }
            if (owned >= 0) {
                data(shard).amountInBox = owned;
                inBox.add(shard.displayName());
            }
            syphoned(shard, tier, toNext);
        }
        dirty = true;
        // Shards missing from the box are gone, but only once every page was seen without a search.
        if (searching) return;
        int page = titleMatch.group("page") == null ? 1 : Integer.parseInt(titleMatch.group("page"));
        int pages = titleMatch.group("pages") == null ? 1 : Integer.parseInt(titleMatch.group("pages"));
        if (page == 1) {
            boxSeenShards.clear();
            boxSeenPages.clear();
        }
        boxSeenShards.addAll(inBox);
        boxSeenPages.add(page);
        if (boxSeenPages.size() < pages) return;
        for (Map.Entry<String, ShardData> e : profile().shards.entrySet()) {
            if (!boxSeenShards.contains(e.getKey())) e.getValue().amountInBox = 0;
        }
    }

    private static void readAttributeMenu(List<Slot> slots) {
        for (Slot slot : slots) {
            ItemStack stack = slot.getItem();
            Shard shard = stack.isEmpty() ? null : of(stack);
            if (shard == null) continue;
            Matcher name = MENU_NAME.matcher(Sky2MLocation.strip(stack.getHoverName().getString()).trim());
            int tier = name.matches() && name.group("tier") != null ? roman(name.group("tier")) : 0;
            int toNext = 0;
            for (String line : lore(stack)) {
                Matcher m;
                if ((m = SYPHON_AMOUNT.matcher(line)).find()) toNext = Integer.parseInt(m.group("amount"));
                if ((m = STATE.matcher(line)).find() && !UNCONSUMABLE.contains(shard.bazaarName())) data(shard).enabled = m.group("state").equals("Yes");
            }
            syphoned(shard, tier, toNext);
        }
        dirty = true;
    }

    private static void readConfirmFusion(List<Slot> slots) {
        Shard first = null, second = null;
        int firstAmount = 0, secondAmount = 0;
        for (Slot slot : slots) {
            if (slot.index != 12 && slot.index != 14) continue;
            Shard shard = of(slot.getItem());
            int amount = 0;
            for (String line : lore(slot.getItem())) {
                Matcher m = REQUIRED_TO_FUSE.matcher(line);
                if (m.find()) amount = Integer.parseInt(m.group("amount"));
            }
            if (slot.index == 12) {
                first = shard;
                firstAmount = amount;
            } else {
                second = shard;
                secondAmount = amount;
            }
        }
        if (first == null || second == null) return;
        fuseFirst = first;
        fuseSecond = second;
        fuseFirstAmount = firstAmount;
        fuseSecondAmount = secondAmount;
    }

    static int roman(String numeral) {
        int total = 0;
        for (int i = 0; i < numeral.length(); i++) {
            int value = value(numeral.charAt(i));
            int next = i + 1 < numeral.length() ? value(numeral.charAt(i + 1)) : 0;
            total += value < next ? -value : value;
        }
        return total;
    }

    private static int value(char c) {
        return switch (c) { case 'L' -> 50; case 'X' -> 10; case 'V' -> 5; case 'I' -> 1; default -> 0; };
    }

    public static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> out = new ArrayList<>(lore.lines().size());
        for (Component line : lore.lines()) out.add(Sky2MLocation.strip(line.getString()).trim());
        return out;
    }

    /** /s2 resethuntingbox: forget the Hunting Box amounts until it's opened again. */
    public static void resetHuntingBox() {
        profile().shards.values().forEach(d -> d.amountInBox = 0);
        dirty = true;
    }

    // ------------------------------------------------------------------------------------------------ saving

    private static void load() {
        try {
            if (Files.exists(file)) saved = GSON.fromJson(Files.readString(file), Saved.class);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read hunting.json: " + e.getMessage());
        }
        if (saved == null) saved = new Saved();
    }

    static void save() {
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save hunting.json: " + e.getMessage());
        }
    }
}
