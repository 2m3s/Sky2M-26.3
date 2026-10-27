package com.epic60869.sky2m.features.slayer;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Profit per slayer boss: "T5 Tara Profit: -10k" in chat after each kill, with a hover listing each drop and its value, the
 * drops' total, the quest's cost and what's left, and the same in the Boss Profit HUD. It counts by the same rules as
 * the slayer profit trackers (SkyHanni's SlayerProfitTracker), so the two agree: only items on that boss's own slayer
 * drop list (SkyHanni's SlayerProfitTrackerItems; nothing counts until the lists have loaded), "[Sacks]" changes within
 * 10 seconds of a Sack menu are you moving items, not drops, and the cost is the wiki's price for the slayer and
 * tier ({@link SlayerCosts}), whatever was paid. Drops are what came into your inventory since your boss spawned, what went straight into your sacks
 * (Hypixel's "[Sacks]" messages) and what it showed on the ground when it died (whichever is more for each item). The
 * chat line goes out once the drops on the ground have landed (or the "[Sacks]" message with them comes), at most
 * 30 seconds after the kill; the HUD shows from the moment the boss dies and fills in as later drops arrive, for up to
 * 30 seconds.
 */
public final class SlayerBossProfit {
    /**
     * The chat line waits for the "[Sacks]" message with the boss's drops (Hypixel sends those every so often, not
     * at the kill); without one it goes out this long after the kill.
     */
    private static final long CHAT_WAIT_MS = 30_000L;
    /** How long after the kill late drops (sacks, pickups) still count. */
    private static final long TRACK_MS = 30_000L;
    /** "+64 Revenant Flesh (Combat Sack)" in a "[Sacks]" message's hover. */
    private static final Pattern SACK_CHANGE = Pattern.compile("^ *([+-])([0-9,]+) (.+?) [(].*[)] *$");

    /** Inventory when your boss spawned: item price id -> count. */
    private static Map<String, Integer> baseline;
    /** Inventory snapshot and cumulative gains observed while the boss is alive. */
    private static Map<String, Integer> observedInventory;
    private static final Map<String, Long> observedGains = new LinkedHashMap<>();
    /** The kill still being totted up (drops can still arrive), or null. */
    private static Kill kill;
    /** The latest result, for the HUD, and when its boss died. */
    private static Result last;

    private static int ticks;
    /** When a Sack menu was last open: SkyHanni's ItemAddManager treats sack changes for 10s after it as moved items. */
    private static long lastSackMenuAt;
    private static final long SACK_MENU_MS = 10_000L;

    private static final class Kill {
        final String boss;
        final long at;
        final Map<String, Integer> baseline;
        /** Gains seen during the fight, including drops consumed or moved before the kill message. */
        final Map<String, Long> observed;
        final Map<String, Long> sacks = new LinkedHashMap<>();
        /** The boss's drops shown on the ground (item id -> amount), and the item entities already read. */
        final Map<String, Long> ground = new LinkedHashMap<>();
        final java.util.Set<Integer> seenItems = new java.util.HashSet<>();
        /** When the first of the boss's drops showed on the ground, or 0. */
        long groundAt;
        /** The boss's quest ({@link SlayerQuestCost#questOfLastBoss}): its cost is looked up each time, since the fee
         *  can be measured a few seconds after a quick kill or an instant restart. */
        final int quest;
        boolean posted;

        Kill(String boss, long at, Map<String, Integer> baseline, Map<String, Long> observed, int quest) {
            this.boss = boss;
            this.at = at;
            this.baseline = baseline;
            this.observed = observed;
            this.quest = quest;
        }

        /** The quest's cost: the wiki's price for the slayer and tier ({@link SlayerCosts}). */
        long cost() {
            return SlayerCosts.price(boss, SlayerQuestCost.costOf(quest));
        }
    }

    private record Line(String name, long amount, double value) {}

    /** One boss's drops (most valuable first), their total, the quest's cost (-1 if unknown) and the profit. */
    private record Result(String boss, long at, List<Line> lines, double drops, long cost, boolean counting, boolean listLoaded) {
        double total() {
            return drops - Math.max(0, cost);
        }
    }

    private SlayerBossProfit() {}

    // ---------------------------------------------------------------- which items a boss drops

    /** SkyHanni's list of each slayer's drops (the one its slayer profit tracker uses). */
    private static final String DROPS_URL = "https://raw.githubusercontent.com/hannibal002/SkyHanni-REPO/main/constants/SlayerProfitTrackerItems.json";
    /** Slayer ("Revenant Horror") -> the keys its drops can show up as (see {@link #keys}). Empty until loaded. */
    private static volatile Map<String, java.util.Set<String>> drops = Map.of();

    /** Whether SkyHanni's drop lists have loaded, for /s2 selftest. */
    public static boolean dropListsLoaded() { return !drops.isEmpty(); }

    /** Downloads the drop lists (kept on disk for when GitHub can't be reached). */
    private static void loadDrops(java.nio.file.Path configDir) {
        java.nio.file.Path cache = configDir.resolve("sky2m").resolve("slayer-drops.json");
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            String json = null;
            try {
                // A copy under 12 hours old is used as it is: no download at every launch.
                if (java.nio.file.Files.isRegularFile(cache)
                    && System.currentTimeMillis() - java.nio.file.Files.getLastModifiedTime(cache).toMillis() < 12L * 60 * 60 * 1000) {
                    json = java.nio.file.Files.readString(cache, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
            try {
                if (json == null) {
                    var client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(DROPS_URL)).timeout(java.time.Duration.ofSeconds(15))
                        .header("User-Agent", "Sky2M").GET().build();
                    var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        json = response.body();
                        java.nio.file.Files.createDirectories(cache.getParent());
                        java.nio.file.Files.writeString(cache, json, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Could not download slayer drops: " + e.getMessage());
            }
            try {
                if (json == null && java.nio.file.Files.exists(cache)) json = java.nio.file.Files.readString(cache, java.nio.charset.StandardCharsets.UTF_8);
                if (json == null) return;
                com.google.gson.JsonObject slayers = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("slayers");
                Map<String, java.util.Set<String>> loaded = new HashMap<>();
                slayers.entrySet().forEach(e -> {
                    java.util.Set<String> keys = new java.util.HashSet<>();
                    for (var item : e.getValue().getAsJsonArray()) keys.addAll(keys(item.getAsString()));
                    loaded.put(e.getKey(), keys);
                });
                drops = loaded;
            } catch (Exception e) {
                System.err.println("[Sky2M] Could not read slayer drops: " + e.getMessage());
            }
        });
    }

    /**
     * The ids a listed drop can have here: NEU's "SMITE;6" is the book ENCHANTMENT_SMITE_6, "BITE_RUNE;1" the rune
     * BITE_RUNE_1, "GHOUL;3" a Ghoul pet (LVL_..._GHOUL); plain ids are themselves.
     */
    private static List<String> keys(String neuId) {
        int semi = neuId.indexOf(';');
        if (semi < 0) return List.of(neuId);
        String base = neuId.substring(0, semi), level = neuId.substring(semi + 1);
        return List.of(neuId, base, "ENCHANTMENT_" + base + "_" + level, base + "_" + level, "PET:" + base);
    }

    /** "Revenant Horror V" / "Atoned Horror" -> "Revenant Horror", the slayer the drop list is under. */
    private static String slayer(String boss) {
        String name = boss.replaceFirst(" [IVX]+$", "");
        return switch (name) {
            case "Atoned Horror" -> "Revenant Horror";
            case "Conjoined Brood" -> "Tarantula Broodfather";
            default -> name;
        };
    }

    /** "Tarantula Broodfather V" -> "T5 Tara", "Atoned Horror" -> "T5 Rev", for the chat line. */
    private static String shortName(String boss) {
        String tier = boss.matches(".* [IVX]+$") ? boss.substring(boss.lastIndexOf(' ') + 1) : "";
        if (boss.startsWith("Atoned Horror") || boss.startsWith("Conjoined Brood")) tier = "V";
        String slayer = switch (slayer(boss)) {
            case "Revenant Horror" -> "Rev";
            case "Tarantula Broodfather" -> "Tara";
            case "Sven Packmaster" -> "Sven";
            case "Voidgloom Seraph" -> "Eman";
            case "Inferno Demonlord" -> "Blaze";
            case "Riftstalker Bloodfiend" -> "Vamp";
            default -> slayer(boss);
        };
        int n = switch (tier) {
            case "I" -> 1; case "II" -> 2; case "III" -> 3; case "IV" -> 4; case "V" -> 5;
            default -> 0;
        };
        return n > 0 ? "T" + n + " " + slayer : slayer;
    }

    /**
     * Whether {@code id} (a price id, or a pet's LVL_n_TIER_TYPE) is on the boss's own slayer's drop list, the same
     * rule as the slayer profit trackers; nothing counts before the lists load.
     */
    private static boolean isBossDrop(String boss, String id) {
        return isDropOf(slayer(boss), id);
    }

    /** Whether the boss's slayer drop list has loaded (else nothing can count, and the hover says so). */
    private static boolean listLoaded(String boss) {
        java.util.Set<String> keys = drops.get(slayer(boss));
        return keys != null && !keys.isEmpty();
    }

    /**
     * Whether a rare drop (by its name in chat) is a slayer drop: an item on any slayer's drop list, or, before the
     * lists have loaded, anything that drops during a slayer quest.
     */
    public static boolean isSlayerDrop(String itemName) {
        return isSlayerDropName(itemName);
    }

    /** Whether an item price id is on any slayer boss's drop list (SkyHanni's), for the slayer profit trackers. */
    public static boolean isDropId(String id) {
        if (id == null) return false;
        for (java.util.Set<String> keys : drops.values()) if (keys.contains(id)) return true;
        return false;
    }

    /**
     * Whether an item price id (or a pet's LVL_n_TIER_TYPE) is on {@code slayer}'s own drop list ("Revenant Horror"...),
     * as SkyHanni's slayer profit tracker allows. False until the lists have loaded.
     */
    public static boolean isDropOf(String slayer, String id) {
        java.util.Set<String> keys = drops.get(slayer);
        if (keys == null || id == null) return false;
        if (keys.contains(id)) return true;
        Matcher pet = PET_ID.matcher(id);
        return pet.matches() && keys.contains("PET:" + pet.group(1));
    }

    private static boolean isSlayerDropName(String itemName) {
        Map<String, java.util.Set<String>> lists = drops;
        if (lists.isEmpty()) return SlayerFeatures.onSlayerQuest();
        String id = com.epic60869.sky2m.custom.RepoItems.idByName(itemName);
        if (id == null) return false;
        for (java.util.Set<String> keys : lists.values()) if (keys.contains(id)) return true;
        return false;
    }

    private static final Pattern PET_ID = Pattern.compile("^LVL_\\d+_[A-Z]+_(.+)$");

    public static void init(java.nio.file.Path configDir) {
        loadDrops(configDir);
        SlayerQuestCost.init();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.gui.screen() != null && mc.gui.screen().getTitle().getString().contains("Sack")) lastSackMenuAt = System.currentTimeMillis();
            if (++ticks % 5 == 0) tick();
            // Right after a kill, every tick: the boss's drops land on the ground all at once, and the chat line goes
            // out as soon as they have.
            else if (kill != null && System.currentTimeMillis() - kill.at < FAST_MS) checkGround();
        });
        Sky2MChat.onGameMessage((component, overlay) -> {
            if (!overlay) onMessage(component);
        });
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("slayer_boss_profit", () -> config() != null && config().hud);
        Sky2MHuds.register("slayer_boss_profit", "Slayer Boss Profit", SlayerBossProfit::hudVisible, SlayerBossProfit::hudLines,
            List.of(
                Component.literal("Revenant Horror V ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("+84.2k").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))),
            8, 320);
    }

    private static Sky2MConfig.BossProfit config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.slayers.bossProfit;
    }

    static boolean enabled() {
        Sky2MConfig.BossProfit c = config();
        return c != null && (c.chat || c.hud);
    }

    // ---------------------------------------------------------------- the quest's cost

    private static void onMessage(Component component) {
        String text = Sky2MLocation.strip(component.getString()).trim();
        if (text.equals("SLAYER QUEST STARTED!")) {
            // Prices load on first use: get them now so they're there when the boss dies.
            Sky2MPriceTooltip.warmup();
        } else if (text.startsWith("[Sacks]") && kill != null) {
            // As the slayer trackers (SkyHanni's ItemAddManager): changes while or just after a Sack menu is open are
            // you moving items.
            if (System.currentTimeMillis() - lastSackMenuAt < SACK_MENU_MS) return;
            boolean bossDrops = false;
            for (Map.Entry<String, Long> e : sackGains(component).entrySet()) {
                if (e.getValue() <= 0) continue;
                kill.sacks.merge(e.getKey(), e.getValue(), Long::sum);
                bossDrops |= isBossDrop(kill.boss, RepoItems.idByName(e.getKey()));
            }
            refresh();
            // The boss's sack drops are in: the chat line can go. (A "[Sacks]" message with only other items, e.g.
            // from before the kill, doesn't count: the boss's come in a later one.)
            if (bossDrops) postChat();
        }
    }

    // ---------------------------------------------------------------- the fight

    /** Your boss appeared ({@link SlayerTimes}): remember the inventory, so the drops are what's new after. */
    static void onSpawn() {
        Sky2MPriceTooltip.warmup();
        if (!enabled()) return;
        baseline = inventory();
        observedInventory = new HashMap<>(baseline);
        observedGains.clear();
        groundAtSpawn.clear();
        for (net.minecraft.world.entity.item.ItemEntity item : groundItems()) groundAtSpawn.add(item.getId());
    }

    /** Item entities near you when your boss spawned. */
    private static final java.util.Set<Integer> groundAtSpawn = new java.util.HashSet<>();

    /** Hypixel confirmed the kill ({@link SlayerTimes}): the HUD shows it straight away. */
    static void onKill(String boss) {
        if (!enabled() || baseline == null) return;
        if (kill != null) close();
        kill = new Kill(boss, System.currentTimeMillis(), baseline, new LinkedHashMap<>(observedGains), SlayerQuestCost.questOfLastBoss());
        hudUntil = Long.MAX_VALUE;
        // Items that were lying around when the boss spawned aren't its drops (ones dropped since are, even if they
        // landed a moment before Hypixel's kill message).
        kill.seenItems.addAll(groundAtSpawn);
        baseline = null;
        observedInventory = null;
        observedGains.clear();
        // The drops may already be on the ground when Hypixel's kill message comes.
        checkGround();
    }

    /** How long after a kill the ground is read every tick. */
    private static final long FAST_MS = 3_000L;

    /** Reads the boss's drops off the ground; once they've landed, the chat line goes out. */
    private static void checkGround() {
        if (kill == null) return;
        readGround();
        refresh();
        if (kill.groundAt > 0 && System.currentTimeMillis() - kill.groundAt >= GROUND_SETTLE_MS) postChat();
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        // Sample inventory throughout the fight, not just after the boss dies. This catches drops that are
        // consumed, moved to sacks, or otherwise disappear before the kill snapshot is taken.
        if (baseline != null) observeInventory();
        if (kill == null) return;
        long since = System.currentTimeMillis() - kill.at;
        readGround();
        refresh();
        // The drops on the ground are the boss's whole drop: once they've all landed the chat line can go.
        if (kill.groundAt > 0 && now - kill.groundAt >= GROUND_SETTLE_MS) postChat();
        if (since >= CHAT_WAIT_MS) postChat();
        if (since >= TRACK_MS) close();
    }

    private static void observeInventory() {
        if (baseline == null || observedInventory == null) return;
        Map<String, Integer> current = inventory();
        java.util.Set<String> ids = new java.util.HashSet<>(current.keySet());
        ids.addAll(observedInventory.keySet());
        String activeBoss = SlayerTimes.lastBoss();
        if (activeBoss != null) {
            for (String id : ids) {
                int delta = current.getOrDefault(id, 0) - observedInventory.getOrDefault(id, 0);
                if (delta > 0 && isBossDrop(activeBoss, id)) observedGains.merge(id, (long) delta, Long::sum);
            }
        }
        observedInventory = current;
    }

    /** How far from you the boss's drops on the ground are looked for. */
    private static final double GROUND_RANGE = 15;
    /** After the first drop shows on the ground, the rest land within this (they come together, within a few ticks). */
    private static final long GROUND_SETTLE_MS = 250L;

    private static List<net.minecraft.world.entity.item.ItemEntity> groundItems() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return List.of();
        return mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
            mc.player.getBoundingBox().inflate(GROUND_RANGE), e -> true);
    }

    /**
     * The boss drops its loot on the ground to show it (SkyHanni reads the same for its Slayer Items On Ground):
     * each new item entity near you after the kill that is one of the boss's drops counts, once.
     */
    private static void readGround() {
        Kill k = kill;
        if (k == null || System.currentTimeMillis() - k.at > CHAT_WAIT_MS) return;
        for (net.minecraft.world.entity.item.ItemEntity entity : groundItems()) {
            if (!k.seenItems.add(entity.getId())) continue;
            ItemStack stack = entity.getItem();
            String id = Sky2MPriceTooltip.marketId(stack);
            if (id.isEmpty() || !isBossDrop(k.boss, id)) continue;
            k.ground.merge(id, (long) stack.getCount(), Long::sum);
            if (k.groundAt == 0) k.groundAt = System.currentTimeMillis();
        }
    }

    /** The last boss's {drops value, quest cost (-1 if unknown), profit}, or null before any boss. */
    public static double[] lastKill() {
        Result r = last;
        return r == null ? null : new double[]{r.drops(), r.cost(), r.total()};
    }

    /** Stops counting drops for the kill; its last result stays on the HUD. */
    private static void close() {
        refresh();
        postChat(true);
        if (last != null) last = new Result(last.boss(), last.at(), last.lines(), last.drops(), last.cost(), false, last.listLoaded());
        kill = null;
    }

    private static Map<String, Integer> inventory() {
        Minecraft mc = Minecraft.getInstance();
        Map<String, Integer> counts = new HashMap<>();
        if (mc.player == null) return counts;
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String id = Sky2MPriceTooltip.marketId(stack);
            if (!id.isEmpty()) counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** Works out the kill's result from the inventory now and the sack drops so far. */
    private static void refresh() {
        Kill k = kill;
        if (k == null) return;
        // Per item: what came into your inventory and sacks, or what showed on the ground if that's more (the
        // same drop shows on the ground and then goes to your sacks, so the two aren't added up).
        Map<String, Long> received = new LinkedHashMap<>();
        Map<String, String> names = new HashMap<>();
        // Use the larger of the observed in-fight gains and the final snapshot delta. Do not add them: they
        // can describe the same stack, and the same drop may also appear in sacks or on the ground.
        for (Map.Entry<String, Integer> e : inventory().entrySet()) {
            int gained = e.getValue() - k.baseline.getOrDefault(e.getKey(), 0);
            if (gained > 0 && isBossDrop(k.boss, e.getKey())) received.merge(e.getKey(), (long) gained, Math::max);
        }
        k.observed.forEach((id, amount) -> {
            if (isBossDrop(k.boss, id)) received.merge(id, amount, Math::max);
        });
        for (Map.Entry<String, Long> e : k.sacks.entrySet()) {
            String id = RepoItems.idByName(e.getKey());
            if (id == null || !isBossDrop(k.boss, id)) continue;
            // Inventory, sacks and ground can all report the same drop; count the strongest signal once.
            received.merge(id, e.getValue(), Math::max);
            names.put(id, e.getKey());
        }
        Map<String, Long> amounts = new LinkedHashMap<>(received);
        k.ground.forEach((id, amount) -> amounts.merge(id, amount, Math::max));
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            String id = e.getKey();
            String name = names.get(id);
            if (name == null) {
                String display = RepoItems.displayName(id);
                name = display == null ? id : ChatFormatting.stripFormatting(display);
            }
            lines.add(new Line(name, e.getValue(), Sky2MPriceTooltip.unitPrice(id) * e.getValue()));
        }
        lines.sort((a, b) -> Double.compare(b.value(), a.value()));
        double drops = lines.stream().mapToDouble(Line::value).sum();
        last = new Result(k.boss, k.at, List.copyOf(lines), drops, k.cost(), true, listLoaded(k.boss));
    }

    // ---------------------------------------------------------------- chat

    private static void postChat() {
        postChat(false);
    }

    /** @param closing the kill is being closed: post now, without waiting for prices or the fee any more */
    private static void postChat(boolean closing) {
        Kill k = kill;
        Sky2MConfig.BossProfit c = config();
        if (k == null || k.posted || last == null) return;
        // Without prices every drop would show "no price": wait for them (they're asked for at the quest start), up
        // to 10 seconds after the kill.
        if (!closing && !Sky2MPriceTooltip.pricesLoaded() && System.currentTimeMillis() - k.at < 10_000L) {
            Sky2MPriceTooltip.warmup();
            return;
        }
        // The quest's fee is still being read off the purse (a quick kill or an instant restart): wait for it, up to 5
        // seconds after the kill, so the profit has the cost taken out instead of counting it as 0.
        if (!closing && SlayerQuestCost.costOf(k.quest) < 0 && SlayerQuestCost.measuring(k.quest) && System.currentTimeMillis() - k.at < 5_000L) return;
        if (last.cost() != k.cost()) {
            last = new Result(last.boss(), last.at(), last.lines(), last.drops(), k.cost(), last.counting(), last.listLoaded());
        }
        k.posted = true;
        settleHud();
        if (c == null || !c.chat) return;
        Result r = last;
        MutableComponent hover = Component.literal(r.boss() + " drops").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (!r.listLoaded()) hover.append(Component.literal("\nDrop list not loaded").withStyle(ChatFormatting.RED));
        else if (r.lines().isEmpty()) hover.append(Component.literal("\nNo drops seen").withStyle(ChatFormatting.GRAY));
        for (Line line : r.lines()) {
            hover.append(Component.literal("\n" + line.amount() + "x " + line.name() + "  ").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(line.value() > 0 ? CombatFeatures.formatCoins(line.value()) : "no price").withStyle(ChatFormatting.GOLD));
        }
        hover.append(Component.literal("\n\nDrops: ").withStyle(ChatFormatting.GRAY)).append(Component.literal(CombatFeatures.formatCoins(r.drops())).withStyle(ChatFormatting.GOLD))
            .append(Component.literal("\nSlayer cost: ").withStyle(ChatFormatting.GRAY)).append(costText(r.cost()))
            .append(Component.literal("\nTotal: ").withStyle(ChatFormatting.GRAY)).append(coins(r.total()));
        Sky2MAlerts.chat(Component.literal(shortName(r.boss()) + " Profit: ").withStyle(style -> style.withColor(ChatFormatting.YELLOW)
                .withHoverEvent(new HoverEvent.ShowText(hover)))
            .append(coins(r.total()).copy().withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover))))
            .append(Component.literal(" (hover for the drops)").withStyle(style -> style.withColor(ChatFormatting.DARK_GRAY)
                .withHoverEvent(new HoverEvent.ShowText(hover)))));
    }

    // ---------------------------------------------------------------- HUD

    /** Until when the HUD shows the last boss: while its drops land, then a few seconds more ({@code HUD Time}). */
    private static long hudUntil;

    private static boolean hudVisible() {
        Sky2MConfig.BossProfit c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.hud || last == null || mc.level == null || mc.gui.hud.isHidden()) return false;
        return System.currentTimeMillis() < hudUntil;
    }

    /** The boss's drops are all in (the chat line went out): the HUD stays up {@code HUD Time} more, then hides. */
    private static void settleHud() {
        Sky2MConfig.BossProfit c = config();
        hudUntil = System.currentTimeMillis() + (c == null ? 2 : c.hudHideSeconds) * 1000L;
    }

    /** One line per boss, "Tarantula Broodfather V +84.2k", with its best drops under it when HUD Drops is above 0. */
    private static List<Component> hudLines() {
        Result r = last;
        if (r == null) return List.of();
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(r.boss() + " ").withStyle(ChatFormatting.GOLD).append(coins(r.total()).withStyle(ChatFormatting.BOLD)));
        Sky2MConfig.BossProfit c = config();
        int rows = c == null ? 0 : c.hudDrops;
        if (rows <= 0) return lines;
        for (int i = 0; i < Math.min(rows, r.lines().size()); i++) {
            Line line = r.lines().get(i);
            lines.add(Component.literal(line.amount() + "x " + line.name() + " ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(line.value() > 0 ? CombatFeatures.formatCoins(line.value()) : "no price").withStyle(ChatFormatting.GOLD)));
        }
        if (r.lines().size() > rows) lines.add(Component.literal("+" + (r.lines().size() - rows) + " more").withStyle(ChatFormatting.DARK_GRAY));
        lines.add(Component.literal("Slayer cost: ").withStyle(ChatFormatting.GRAY)
            .append(costText(r.cost())));
        return lines;
    }

    /** "-100k", or "unknown" when the spawn cost wasn't seen (it counts as 0 then). */
    private static MutableComponent costText(long cost) {
        if (cost < 0) return Component.literal("unknown").withStyle(ChatFormatting.DARK_GRAY);
        return Component.literal("-" + CombatFeatures.formatCoins(cost)).withStyle(ChatFormatting.RED);
    }

    private static MutableComponent coins(double value) {
        String text = (value < 0 ? "-" : "+") + CombatFeatures.formatCoins(Math.abs(value));
        return Component.literal(text).withStyle(value < 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    /** What a "[Sacks]" message added, per item name (each hover once; Hypixel repeats it on several parts). */
    public static Map<String, Long> sackGains(Component component) {
        Map<String, Long> net = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Component> parts = new ArrayList<>();
        collect(component, parts);
        for (Component part : parts) {
            if (!(part.getStyle().getHoverEvent() instanceof HoverEvent.ShowText(Component hover))) continue;
            String text = ChatFormatting.stripFormatting(hover.getString());
            if (text == null || !seen.add(text)) continue;
            for (String line : text.split("\n")) {
                Matcher m = SACK_CHANGE.matcher(line);
                if (!m.matches()) continue;
                long amount = Long.parseLong(m.group(2).replace(",", ""));
                net.merge(m.group(3).trim(), m.group(1).equals("-") ? -amount : amount, Long::sum);
            }
        }
        return net;
    }

    private static void collect(Component component, List<Component> out) {
        out.add(component);
        for (Component sibling : component.getSiblings()) collect(sibling, out);
    }
}
