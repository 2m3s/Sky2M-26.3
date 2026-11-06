package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One Hypixel item as a player has it built: its SkyBlock id and every modifier on it - dungeon stars and Master
 * Stars (or Crimson Isle stars), reforge, gemstones, Hot/Fuming Potato Books, Recombobulator, "fragged" (the
 * {@code STARRED_} upgrade), the Skeleton Master floor tier and quality roll, enchantments and a power scroll.
 * Immutable; the {@code with...} methods return a changed copy.
 *
 * <h2>API for the rest of the sim</h2>
 * <ul>
 * <li>{@link #held(ServerPlayer)}, {@link #armour(ServerPlayer)} (helmet..boots), {@link #equipment(ServerPlayer)}
 *     (necklace, cloak, belt, gloves): a player's builds right now. Never null: an empty slot is {@link #EMPTY}.</li>
 * <li>{@link #fromStack} / {@link #applyTo}: a build is stored on its {@link ItemStack} the way Hypixel stores it in
 *     ExtraAttributes ({@code modifier}, {@code upgrade_level}, {@code rarity_upgrades}, {@code hot_potato_count},
 *     {@code gems}, {@code enchantments}, {@code power_ability_scroll}, {@code item_tier},
 *     {@code baseStatBoostPercentage}), so a weapon given with a build keeps it in the hotbar.</li>
 * <li>{@link #stats(Context)}: what the item adds to the player's stats, every modifier included, with the dungeon
 *     multiplier applied. {@link #enchantments()} is stored for the damage-formula enchants (applied elsewhere,
 *     {@code SimEnchants}); only the plain-stat enchants are counted here ({@link SimItemData#statEnchant}).</li>
 * </ul>
 * The maths lives in {@link SimItemData}, which also holds the offline fallback stats.
 */
public record SimItemBuild(String id, int stars, int masterStars, int crimsonStars, String reforge, List<Gem> gems,
                           int hotPotato, int fuming, boolean recombobulated, boolean fragged, int skeletonFloor,
                           int skeletonQuality, Map<String, Integer> enchantments, String powerScroll) {

    /** Hypixel's item rarities, lowest first; {@link #next} is a Recombobulator's step. */
    public enum Rarity {
        COMMON('f'), UNCOMMON('a'), RARE('9'), EPIC('5'), LEGENDARY('6'), MYTHIC('d'), DIVINE('b'), SPECIAL('c'),
        VERY_SPECIAL('c');

        public final char colour;

        Rarity(char colour) {
            this.colour = colour;
        }

        public Rarity next() {
            return switch (this) {
                case DIVINE, VERY_SPECIAL -> this;
                case SPECIAL -> VERY_SPECIAL;
                default -> values()[ordinal() + 1];
            };
        }

        public String label() {
            return name().replace('_', ' ');
        }

        public static Rarity parse(String s) {
            if (s == null) {
                return null;
            }
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** The gemstones the sim's builds use, and the stat each gives. Opal (True Defense) is not a sim stat. */
    public enum GemType {
        RUBY(SimStats.Stat.HEALTH), AMETHYST(SimStats.Stat.DEFENSE), JASPER(SimStats.Stat.STRENGTH),
        SAPPHIRE(SimStats.Stat.INTELLIGENCE), ONYX(SimStats.Stat.CRIT_DAMAGE);

        public final SimStats.Stat stat;

        GemType(SimStats.Stat stat) {
            this.stat = stat;
        }
    }

    public enum GemQuality { ROUGH, FLAWED, FINE, FLAWLESS, PERFECT }

    /** One gemstone in one slot (slots in the item's own order, see {@link SimItemData#gemSlots}). */
    public record Gem(GemType type, GemQuality quality) {
    }

    /**
     * Where the item is used: the floor ("M7", "F7", or null outside a run - no dungeon boost then), the player's
     * Catacombs level, his selected class (the Adaptive Belt's bonus) and his pet's id (a Blaze doubles potato
     * books), which may be null.
     */
    public record Context(String floor, int catacombs, SimStats.DungeonClass dungeonClass, String petId) {
        public Context(String floor, int catacombs, SimStats.DungeonClass dungeonClass) {
            this(floor, catacombs, dungeonClass, null);
        }
    }

    /**
     * What a build adds.
     *
     * @param stats      every stat, boosted where Hypixel boosts it
     * @param damage     a weapon's own Damage stat (boosted likewise)
     * @param rarity     the item's rarity after a Recombobulator / quality upgrade
     * @param multiplier the dungeon multiplier its boostable stats got (1 outside a dungeon or for a non-dungeon item)
     */
    public record Contribution(Map<SimStats.Stat, Double> stats, double damage, Rarity rarity, double multiplier,
                               boolean real) {
        public double get(SimStats.Stat s) {
            Double v = stats.get(s);
            return v == null ? 0 : v;
        }

        public static final Contribution NONE = new Contribution(Map.of(), 0, Rarity.COMMON, 1, true);
    }

    /** Custom-data key on a stack carrying a build (its lore is generated from the build). */
    public static final String BUILD_MARK = "sky2m_build";

    /** No item. */
    public static final SimItemBuild EMPTY = new SimItemBuild("", 0, 0, 0, null, List.of(), 0, 0, false, false, 0, 0,
            Map.of(), null);

    public SimItemBuild {
        id = id == null ? "" : id.toUpperCase(Locale.ROOT);
        stars = clamp(stars, 0, 5);
        masterStars = clamp(masterStars, 0, 5);
        crimsonStars = clamp(crimsonStars, 0, 10);
        reforge = reforge == null || reforge.isBlank() ? null : reforge.trim().toLowerCase(Locale.ROOT);
        gems = gems == null ? List.of() : List.copyOf(gems.stream().filter(g -> g != null && g.type() != null
                && g.quality() != null).toList());
        hotPotato = clamp(hotPotato, 0, 10);
        fuming = clamp(fuming, 0, 5);
        skeletonFloor = clamp(skeletonFloor, 0, 10);
        skeletonQuality = clamp(skeletonQuality, 0, 50);
        Map<String, Integer> ench = new LinkedHashMap<>();
        if (enchantments != null) {
            for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                    ench.put(e.getKey().toLowerCase(Locale.ROOT), Math.min(10, e.getValue()));
                }
            }
        }
        enchantments = Map.copyOf(ench);
        powerScroll = powerScroll == null || powerScroll.isBlank() ? null : powerScroll.toUpperCase(Locale.ROOT);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ building

    /** A plain item: no stars, reforge or anything else. */
    public static SimItemBuild of(String id) {
        return new SimItemBuild(id, 0, 0, 0, null, List.of(), 0, 0, false, false, 0, 0, Map.of(), null);
    }

    public boolean isEmpty() {
        return id.isEmpty();
    }

    /** Total stars as Hypixel shows them: 0-10 (5 dungeon + 5 master), or the Crimson stars. */
    public int totalStars() {
        return SimItemData.crimson(id) ? crimsonStars : stars + masterStars;
    }

    /**
     * Sets the stars the way a preset says them: "10 stars" on a dungeon item is 5 stars + 5 Master Stars; on a
     * Crimson Isle item (Ragnarock, Implosion Belt) it is 10 Crimson stars.
     */
    public SimItemBuild withStars(int total) {
        if (SimItemData.crimson(id)) {
            return new SimItemBuild(id, 0, 0, total, reforge, gems, hotPotato, fuming, recombobulated, fragged,
                    skeletonFloor, skeletonQuality, enchantments, powerScroll);
        }
        int t = clamp(total, 0, 10);
        return new SimItemBuild(id, Math.min(5, t), Math.max(0, t - 5), 0, reforge, gems, hotPotato, fuming,
                recombobulated, fragged, skeletonFloor, skeletonQuality, enchantments, powerScroll);
    }

    public SimItemBuild withReforge(String r) {
        return new SimItemBuild(id, stars, masterStars, crimsonStars, r, gems, hotPotato, fuming, recombobulated,
                fragged, skeletonFloor, skeletonQuality, enchantments, powerScroll);
    }

    public SimItemBuild withGems(List<Gem> g) {
        return new SimItemBuild(id, stars, masterStars, crimsonStars, reforge, g, hotPotato, fuming, recombobulated,
                fragged, skeletonFloor, skeletonQuality, enchantments, powerScroll);
    }

    /** {@code n} Gems of one type and quality (fills the first {@code n} slots). */
    public SimItemBuild withGems(GemType type, GemQuality quality, int n) {
        List<Gem> g = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            g.add(new Gem(type, quality));
        }
        return withGems(g);
    }

    /** Potato books: 0-10 Hot, then 0-5 Fuming on top ("Fuming" in a preset = 15 books). */
    public SimItemBuild withPotatoBooks(int total) {
        int t = clamp(total, 0, 15);
        return new SimItemBuild(id, stars, masterStars, crimsonStars, reforge, gems, Math.min(10, t),
                Math.max(0, t - 10), recombobulated, fragged, skeletonFloor, skeletonQuality, enchantments,
                powerScroll);
    }

    public SimItemBuild withRecombobulator(boolean on) {
        return new SimItemBuild(id, stars, masterStars, crimsonStars, reforge, gems, hotPotato, fuming, on, fragged,
                skeletonFloor, skeletonQuality, enchantments, powerScroll);
    }

    /** The fragged ({@code STARRED_}) version; ignored by {@link #stats} for items that have none. */
    public SimItemBuild withFragged(boolean on) {
        return new SimItemBuild(id, stars, masterStars, crimsonStars, reforge, gems, hotPotato, fuming,
                recombobulated, on, skeletonFloor, skeletonQuality, enchantments, powerScroll);
    }

    /** Skeleton Master (and other randomized dungeon drops): floor tier 1-10 (10 = M7) and quality 0-50%. */
    public SimItemBuild withSkeletonRoll(int floorTier, int qualityPercent) {
        return new SimItemBuild(id, stars, masterStars, crimsonStars, reforge, gems, hotPotato, fuming,
                recombobulated, fragged, floorTier, qualityPercent, enchantments, powerScroll);
    }

    public SimItemBuild withEnchantments(Map<String, Integer> e) {
        return new SimItemBuild(id, stars, masterStars, crimsonStars, reforge, gems, hotPotato, fuming,
                recombobulated, fragged, skeletonFloor, skeletonQuality, e, powerScroll);
    }

    public SimItemBuild withEnchant(String name, int level) {
        Map<String, Integer> e = new LinkedHashMap<>(enchantments);
        e.put(name.toLowerCase(Locale.ROOT), level);
        return withEnchantments(e);
    }

    /** "JASPER" or "JASPER_POWER_SCROLL" - stored as Hypixel's id. */
    public SimItemBuild withPowerScroll(String scroll) {
        String s = scroll == null ? null : scroll.toUpperCase(Locale.ROOT);
        if (s != null && !s.endsWith("_POWER_SCROLL")) {
            s = s + "_POWER_SCROLL";
        }
        return new SimItemBuild(id, stars, masterStars, crimsonStars, reforge, gems, hotPotato, fuming,
                recombobulated, fragged, skeletonFloor, skeletonQuality, enchantments, s);
    }

    public SimItemBuild withId(String newId) {
        return new SimItemBuild(newId, stars, masterStars, crimsonStars, reforge, gems, hotPotato, fuming,
                recombobulated, fragged, skeletonFloor, skeletonQuality, enchantments, powerScroll);
    }

    /** An enchantment's level, 0 when absent. Names are Hypixel's lower-case ids ("ultimate_legion", "growth"). */
    public int enchant(String name) {
        Integer v = enchantments.get(name.toLowerCase(Locale.ROOT));
        return v == null ? 0 : v;
    }

    /** The id the item has in Hypixel's data: {@code STARRED_X} when fragged and such an item exists. */
    public String dataId() {
        return fragged && SimItemData.hasFragged(id) ? "STARRED_" + id : id;
    }

    // ------------------------------------------------------------------ stats

    /** What the item adds to its wearer's stats here. See {@link SimItemData#contribution}. */
    public Contribution stats(Context context) {
        return isEmpty() ? Contribution.NONE : SimItemData.contribution(this, context);
    }

    /** The rarity after the Recombobulator (and the Skeleton Master's 50% roll). */
    public Rarity rarity() {
        return SimItemData.rarity(this);
    }

    /** The display name: "Necron's Leggings", "Withered Ragnarock"... with the reforge in front. */
    public String displayName() {
        return SimItemData.displayName(this);
    }

    // ------------------------------------------------------------------ players

    private static final EquipmentSlot[] ARMOUR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    /** The build of the item in a player's main hand ({@link #EMPTY} for nothing). Server thread. */
    public static SimItemBuild held(ServerPlayer sp) {
        return sp == null ? EMPTY : fromStack(sp.getMainHandItem());
    }

    /**
     * A player's four armour builds, helmet to boots: what is really in his armour slots (a mask swapped on by hand
     * counts). An empty slot, or one holding something that is not a sim item, is {@link #EMPTY}: the wardrobe's
     * build only counts once it is worn, which {@code SimGear.dressIfBare} sees to at the start of a session and of
     * each floor.
     */
    public static List<SimItemBuild> armour(ServerPlayer sp) {
        if (sp == null) {
            return List.of(EMPTY, EMPTY, EMPTY, EMPTY);
        }
        List<SimItemBuild> out = new ArrayList<>(4);
        for (EquipmentSlot slot : ARMOUR_SLOTS) {
            out.add(fromStack(sp.getItemBySlot(slot)));
        }
        return List.copyOf(out);
    }

    /** A player's four equipment builds (necklace, cloak, belt, gloves) - from his wardrobe, there is no slot. */
    public static List<SimItemBuild> equipment(ServerPlayer sp) {
        return SimParty.member(sp).gear().equipment();
    }

    // ------------------------------------------------------------------ on a stack

    /** Reads a build off a stack's custom data (Hypixel's ExtraAttributes keys). {@link #EMPTY} for a non-sim item. */
    public static SimItemBuild fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return EMPTY;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return EMPTY;
        }
        return fromTag(data.copyTag());
    }

    /** {@link #fromStack} on a tag. */
    public static SimItemBuild fromTag(CompoundTag tag) {
        String id = tag.getStringOr("id", "");
        if (id.isEmpty()) {
            return EMPTY;
        }
        boolean fragged = false;
        if (id.startsWith("STARRED_") && SimItemData.hasFragged(id.substring(8))) {
            id = id.substring(8);
            fragged = true;
        }
        int upgrade = tag.getIntOr("upgrade_level", tag.getIntOr("dungeon_item_level", 0));
        boolean crimson = SimItemData.crimson(id);
        List<Gem> gems = new ArrayList<>();
        CompoundTag gt = tag.getCompoundOrEmpty("gems");
        for (int i = 0; i < 8; i++) {
            String type = gt.getStringOr("sim_" + i + "_type", "");
            String quality = gt.getStringOr("sim_" + i, "");
            if (type.isEmpty() || quality.isEmpty()) {
                continue;
            }
            try {
                gems.add(new Gem(GemType.valueOf(type), GemQuality.valueOf(quality)));
            } catch (IllegalArgumentException ignored) {
                // an unknown gem in a hand-edited stack
            }
        }
        Map<String, Integer> ench = new LinkedHashMap<>();
        CompoundTag et = tag.getCompoundOrEmpty("enchantments");
        for (String k : et.keySet()) {
            ench.put(k, et.getIntOr(k, 0));
        }
        int books = tag.getIntOr("hot_potato_count", 0);
        String scroll = tag.getStringOr("power_ability_scroll", "");
        return new SimItemBuild(id, crimson ? 0 : Math.min(5, upgrade), crimson ? 0 : Math.max(0, upgrade - 5),
                crimson ? upgrade : 0, tag.getStringOr("modifier", ""), gems, Math.min(10, books),
                Math.max(0, books - 10), tag.getIntOr("rarity_upgrades", 0) > 0, fragged, tag.getIntOr("item_tier", 0),
                tag.getIntOr("baseStatBoostPercentage", 0), ench, scroll.isEmpty() ? null : scroll);
    }

    /** Writes this build into a tag (and the id - fragged items get their {@code STARRED_} id). */
    public CompoundTag writeTo(CompoundTag tag) {
        tag.putString("id", dataId());
        // Marks a stack whose tooltip is the build's own (SimItemData.applyLore), not the fixed SimItemLore text.
        tag.putInt(BUILD_MARK, 1);
        if (totalStars() > 0) {
            tag.putInt("upgrade_level", totalStars());
        }
        if (reforge != null) {
            tag.putString("modifier", reforge);
        }
        if (recombobulated) {
            tag.putInt("rarity_upgrades", 1);
        }
        if (hotPotato + fuming > 0) {
            tag.putInt("hot_potato_count", hotPotato + fuming);
        }
        if (skeletonFloor > 0) {
            tag.putInt("item_tier", skeletonFloor);
            tag.putInt("baseStatBoostPercentage", skeletonQuality);
        }
        if (!gems.isEmpty()) {
            CompoundTag gt = new CompoundTag();
            for (int i = 0; i < gems.size(); i++) {
                gt.putString("sim_" + i + "_type", gems.get(i).type().name());
                gt.putString("sim_" + i, gems.get(i).quality().name());
            }
            tag.put("gems", gt);
        }
        if (!enchantments.isEmpty()) {
            CompoundTag et = new CompoundTag();
            enchantments.forEach(et::putInt);
            tag.put("enchantments", et);
        }
        if (powerScroll != null) {
            tag.putString("power_ability_scroll", powerScroll);
        }
        return tag;
    }

    /**
     * Puts this build on a stack (merged into its custom data, keeping its other keys) and rewrites its tooltip
     * ({@link SimItemData#applyLore}).
     */
    public ItemStack applyTo(ItemStack stack) {
        if (stack == null || stack.isEmpty() || isEmpty()) {
            return stack;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data == null ? new CompoundTag() : data.copyTag();
        for (String k : List.of("upgrade_level", "modifier", "rarity_upgrades", "hot_potato_count", "item_tier",
                "baseStatBoostPercentage", "gems", "enchantments", "power_ability_scroll", BUILD_MARK)) {
            tag.remove(k);
        }
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(writeTo(tag)));
        SimItemData.applyLore(stack, this);
        return stack;
    }

    // ------------------------------------------------------------------ json (wardrobe file, party sync)

    public com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("id", id);
        if (stars > 0) o.addProperty("stars", stars);
        if (masterStars > 0) o.addProperty("masterStars", masterStars);
        if (crimsonStars > 0) o.addProperty("crimsonStars", crimsonStars);
        if (reforge != null) o.addProperty("reforge", reforge);
        if (!gems.isEmpty()) {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (Gem g : gems) {
                a.add(g.quality().name() + " " + g.type().name());
            }
            o.add("gems", a);
        }
        if (hotPotato + fuming > 0) o.addProperty("potatoBooks", hotPotato + fuming);
        if (recombobulated) o.addProperty("recombobulated", true);
        if (fragged) o.addProperty("fragged", true);
        if (skeletonFloor > 0) {
            o.addProperty("floorTier", skeletonFloor);
            o.addProperty("quality", skeletonQuality);
        }
        if (!enchantments.isEmpty()) {
            com.google.gson.JsonObject e = new com.google.gson.JsonObject();
            enchantments.forEach(e::addProperty);
            o.add("enchantments", e);
        }
        if (powerScroll != null) o.addProperty("powerScroll", powerScroll);
        return o;
    }

    /** {@link #toJson}'s inverse; also reads a preset's "stars": 10 as 5 + 5 master. Null-safe. */
    public static SimItemBuild fromJson(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return EMPTY;
        }
        if (el.isJsonPrimitive()) {
            return of(el.getAsString());
        }
        com.google.gson.JsonObject o = el.getAsJsonObject();
        String id = o.has("id") ? o.get("id").getAsString() : "";
        if (id.isEmpty()) {
            return EMPTY;
        }
        List<Gem> gems = new ArrayList<>();
        if (o.get("gems") instanceof com.google.gson.JsonArray a) {
            for (var g : a) {
                String[] parts = g.getAsString().trim().toUpperCase(Locale.ROOT).split("\\s+");
                if (parts.length == 2) {
                    try {
                        gems.add(new Gem(GemType.valueOf(parts[1]), GemQuality.valueOf(parts[0])));
                    } catch (IllegalArgumentException ignored) {
                        // skip an unknown gem
                    }
                }
            }
        }
        Map<String, Integer> ench = new LinkedHashMap<>();
        if (o.get("enchantments") instanceof com.google.gson.JsonObject e) {
            for (var en : e.entrySet()) {
                ench.put(en.getKey(), en.getValue().getAsInt());
            }
        }
        int books = o.has("potatoBooks") ? o.get("potatoBooks").getAsInt() : 0;
        SimItemBuild b = new SimItemBuild(id, intOf(o, "stars"), intOf(o, "masterStars"), intOf(o, "crimsonStars"),
                o.has("reforge") ? o.get("reforge").getAsString() : null, gems, Math.min(10, books),
                Math.max(0, books - 10), o.has("recombobulated") && o.get("recombobulated").getAsBoolean(),
                o.has("fragged") && o.get("fragged").getAsBoolean(), intOf(o, "floorTier"), intOf(o, "quality"),
                ench, o.has("powerScroll") ? o.get("powerScroll").getAsString() : null);
        if (o.has("totalStars")) {
            b = b.withStars(o.get("totalStars").getAsInt());
        }
        return b;
    }

    private static int intOf(com.google.gson.JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsInt() : 0;
    }

    /** Adds every stat of {@code from} into {@code into}. */
    static void addAll(Map<SimStats.Stat, Double> into, Map<SimStats.Stat, Double> from) {
        for (Map.Entry<SimStats.Stat, Double> e : from.entrySet()) {
            into.merge(e.getKey(), e.getValue(), Double::sum);
        }
    }

    static Map<SimStats.Stat, Double> newStats() {
        return new EnumMap<>(SimStats.Stat.class);
    }
}
