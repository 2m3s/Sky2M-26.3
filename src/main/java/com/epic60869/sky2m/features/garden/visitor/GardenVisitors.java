package com.epic60869.sky2m.features.garden.visitor;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MCraftHelper;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MTheme;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.epic60869.sky2m.features.misc.items.SackTracker;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.sb.mixins.accessors.AbstractContainerScreenAccessor;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's garden visitor features (features/garden/visitor, LGPL-2.1), in one place: visitors are read from the
 * tab list's Visitors widget, and their offers when you open them. From that come the Visitor Shopping List (with
 * prices, sack counts, "Craftable!", item icons and the item preview for new visitors), the Visitor Timer (with the
 * queue size from Max Occupancy and the next-visitor estimate while the queue is full), prices, copper and garden exp value in the offer's tooltip, the Reward Warning (rare rewards, and
 * blocking refusing or accepting by copper price or coin loss), status highlights on the visitors, arrival
 * notifications, hidden visitor chat, compact reward messages, the Drops Statistics, Charmed Visitors, the Supercraft
 * button, the Jacob/Anita NPC fix, and highlighting / blocking visitors outside the Garden.
 */
public final class GardenVisitors {
    private static final int INFO_SLOT = 13;
    private static final int ACCEPT_SLOT = 29;
    private static final int SUPERCRAFT_SLOT = 31;
    private static final int REFUSE_SLOT = 33;
    private static final int VINYL_SLOT = 48;

    private static final Pattern VISITORS_HEADER = Pattern.compile("^\\s*Visitors: \\((\\d+)\\)\\s*$");
    private static final Pattern NEXT_VISITOR = Pattern.compile("^\\s*Next Visitor: (.*)$");
    private static final Pattern OFFERS_ACCEPTED = Pattern.compile("^Offers Accepted: (\\d+)");
    private static final Pattern COPPER = Pattern.compile("^\\s*\\+([\\d,]+) Copper(?: .*)?$");
    private static final Pattern GARDEN_EXP = Pattern.compile("^\\s*\\+([\\d,]+) Garden Experience$");
    private static final Pattern ITEM_LINE = Pattern.compile("^(?:([\\d,]+)x )?(.+?)(?: x([\\d,]+))?$");
    private static final Pattern ARRIVED = Pattern.compile("^.* has arrived on your Garden!$");
    private static final Pattern NPC_CHAT = Pattern.compile("^§e\\[NPC] (?:§r)?(?<color>§[0-9a-f])?(?<name>[^§]*)(?:§r)?§f: .*");
    private static final Pattern BARN_SKIN = Pattern.compile("^Changing Barn skin to .*");
    private static final Pattern CHARMED = Pattern.compile("^This Visitor has been Charmed!.*");
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)([dhms])");
    // Compact chat (on the § text with the resets removed).
    private static final Pattern FULLY_ACCEPTED = Pattern.compile("§6§lOFFER ACCEPTED §8with (?<color>§.)?(?<name>.*) §8\\((?<rarity>.*)\\)");
    private static final Pattern VISITOR_REWARD = Pattern.compile(
        "^ {4}(?:(?:§.)+\\+)?(?:(?<amountcolor>§.)(?<amount>[\\d,]+(?:\\.?(?:\\d)?k)?)x? )?(?:(?<rewardcolor>(?:§.)+)?(?<reward>.*?))(?: (?:(?:§.)?)?x(?<altamount>\\d+))?$");
    private static final Pattern DISCARD_REWARD_NAME = Pattern.compile("^(?:Copper|Farming XP|Farming Experience|Garden Experience|Bits)$");
    private static final Pattern REWARDS_TEXT = Pattern.compile("^ {2}§a§lREWARDS");
    // Drop statistics (on the plain text).
    private static final Pattern STAT_ACCEPT = Pattern.compile("^OFFER ACCEPTED with (?<visitor>.*) \\((?<rarity>.*)\\)$");
    private static final Map<Pattern, String> STAT_PATTERNS = new LinkedHashMap<>();

    static {
        STAT_PATTERNS.put(Pattern.compile("^[+](.*) Copper$"), "copper");
        STAT_PATTERNS.put(Pattern.compile("^[+](.*) Garden Experience$"), "gardenExp");
        STAT_PATTERNS.put(Pattern.compile("^[+](.*) Farming XP$"), "farmingExp");
        STAT_PATTERNS.put(Pattern.compile("^[+](.*) Bits$"), "bits");
        STAT_PATTERNS.put(Pattern.compile("^[+](.*) Mithril Powder$"), "mithrilPowder");
        STAT_PATTERNS.put(Pattern.compile("^[+](.*) Gemstone Powder$"), "gemstonePowder");
        // Ghost Visitors (Spooky Festival, October 2026 update) pay in Ectoplasm; the exact reward line isn't known yet,
        // so "+12 Ectoplasm", "+12x Ectoplasm" and "Ectoplasm x12" are all read.
        STAT_PATTERNS.put(Pattern.compile("^[+]([\\d,.]+[kKmM]?)x? Ectoplasm$"), "ectoplasm");
        STAT_PATTERNS.put(Pattern.compile("^[+]?Ectoplasm x([\\d,]+)$"), "ectoplasm");
    }

    private static final Set<String> NOT_ITEMS = Set.of("Copper", "Garden Experience", "Farming XP", "Farming Experience", "Bits",
        "Mithril Powder", "Gemstone Powder", "Glacite Powder");
    private static final List<String> STATIC_VISITORS = List.of("Jacob", "Anita");
    /** The visitor queue without upgrades, and how many Max Occupancy tiers SkyMart sells (one visitor each). */
    private static final int BASE_QUEUE_SIZE = 5, MAX_OCCUPANCY_TIERS = 5;
    private static final String[] RARITIES = {"UNCOMMON", "RARE", "LEGENDARY", "MYTHIC", "SPECIAL"};
    private static final ChatFormatting[] RARITY_COLOURS = {ChatFormatting.GREEN, ChatFormatting.BLUE, ChatFormatting.GOLD, ChatFormatting.LIGHT_PURPLE, ChatFormatting.RED};
    private static final Set<String> CROPS = Set.of("Wheat", "Carrot", "Potato", "Pumpkin", "Melon Slice", "Melon", "Sugar Cane", "Cactus",
        "Cocoa Beans", "Nether Wart", "Red Mushroom", "Brown Mushroom", "Seeds", "Sunflower", "Moonflower", "Wild Rose");
    /** SkyHanni's GardenApi.barnArea. */
    private static final AABB BARN_AREA = new AABB(-32.5, 70.0, -46.5, 35.5, 100.0, -4.5);
    private static final Gson GSON = new Gson();

    enum Status {
        NEW("§eNew", 0xFFFF55), WAITING("Waiting", -1), READY("§aItems Ready", 0x55FF55),
        ACCEPTED("§7Accepted", 0x555555), REFUSED("§cRefused", 0xFF5555);

        final String displayName;
        final int colour;

        Status(String displayName, int colour) {
            this.displayName = displayName;
            this.colour = colour;
        }
    }

    enum BlockReason {
        NEVER_ACCEPTED("§cNever accepted", true), RARE_REWARD("§aRare visitor reward found", true), CHEAP_COPPER("§aCheap copper", true),
        EXPENSIVE_COPPER("§cExpensive copper", false), LOW_LOSS("§aLow Loss", true), HIGH_LOSS("§cHigh Loss", false);

        final String description;
        final boolean blockRefusing;

        BlockReason(String description, boolean blockRefusing) {
            this.description = description;
            this.blockRefusing = blockRefusing;
        }
    }

    static final class Visitor {
        /** Plain name ("Emissary Carlton"). */
        final String name;
        /** The colour code the tab list shows the name in ("§a"). */
        String colour;
        int entityId = -1;
        int nameTagEntityId = -1;
        Status status = Status.NEW;
        final Map<String, Integer> shoppingList = new LinkedHashMap<>();
        Integer offersAccepted;
        Integer pricePerCopper;
        Double totalPrice;
        Double totalReward;
        List<String> allRewards = List.of();
        List<Component> lastLore = List.of();
        List<Component> blockedLore = List.of();
        BlockReason blockReason;
        ItemStack offerItem = ItemStack.EMPTY;

        Visitor(String name, String colour) {
            this.name = name;
            this.colour = colour;
        }

        String displayName() {
            return coloredName(name, colour);
        }

        List<VisitorReward> rewardWarningAwards() {
            VisitorConfig config = config();
            List<VisitorReward> out = new ArrayList<>();
            if (config == null) return out;
            for (String id : allRewards) {
                VisitorReward reward = VisitorReward.byInternalName(id);
                if (reward != null && config.rewardWarning.drops.contains(reward)) out.add(reward);
            }
            return out;
        }

        boolean ignoreShoppingList() {
            return storage().ignoredVisitors.contains(name);
        }
    }

    /** What SkyHanni keeps in its profile storage for the garden visitors. */
    static final class ProfileData {
        /** The visitor cooldown, learned from the tab list's countdown when a visitor arrives (Better Advertising lowers it). */
        Long visitorInterval;
        /** When the next visitor is due while the queue is full (the "Next Visitor When Full" estimate). */
        long nextFullQueueVisitor;
        /** How many visitors the queue holds, learned from the visitor count when the tab list says "Queue Full!". */
        Integer queueSize;
        Set<String> ignoredVisitors = new LinkedHashSet<>();
        Map<String, double[]> npcVisitorLocations = new HashMap<>();
        Set<String> charmedVisitors = new LinkedHashSet<>();
        Drops drops = new Drops();
    }

    static final class Drops {
        long acceptedVisitors, deniedVisitors, copper, farmingExp, gardenExp, bits, mithrilPowder, gemstonePowder, ectoplasm, coinsSpent;
        Map<String, Long> rewardsCount = new HashMap<>();
        Map<String, Long> acceptedRarities = new HashMap<>();
    }

    private record GardenVisitorData(String rarity, List<String> needItems, String mode, double[] position, String skinOrType,
                                     boolean showChatMessage, boolean unknownRewards) {}

    private static volatile Map<String, GardenVisitorData> repoVisitors = Map.of();
    private static final Map<String, Visitor> visitors = new LinkedHashMap<>();
    private static final Map<String, ProfileData> profiles = new ConcurrentHashMap<>();
    private static Path file;
    private static boolean dirty;

    private static boolean inInventory;
    private static int lastClickedNpc = -1;
    private static Visitor openVisitor;
    private static int openMenuContainer = -1;
    private static long ticks;
    private static long lastWorldSwitch;
    private static long lastVisitorOpen;
    private static long lastAccept;
    private static double lastFullPrice;
    private static boolean lastBypassState;
    private static final List<Long> cropBreaks = new ArrayList<>();
    private static final List<Object[]> delayed = new ArrayList<>();

    // Timer state.
    private static List<Component> timerLines = List.of();
    private static long lastMillis;
    private static long fullQueueArrivalTime;
    private static boolean visitorJustArrived;
    private static long lastFullQueueWarning;
    private static String lastTimerValue = "";
    private static long lastTimerUpdate;
    private static int lastVisitors = -1;
    private static long lastPing;

    // Shopping list, statistics and charmed HUD lines.
    private static final String SHOPPING_HUD = "visitor_shopping_list";
    /** A shopping list row: the item's icon (null for a text row), the text after it and the item's id (null when not an item). */
    private record ShopRow(ItemStack icon, Component text, String id) {}
    /** The shopping list's rows, with its size worked out once (it changes once a second at most). */
    private record ShopList(List<ShopRow> rows, List<Component> texts, int width, int height) {}
    private static final ShopList EMPTY_SHOP_LIST = new ShopList(List.of(), List.of(), 0, 0);
    private static ShopList shoppingList = EMPTY_SHOP_LIST;
    private static ShopList previewList;
    private static String previewKey = "";
    private static final Map<String, ItemStack> ICONS = new ConcurrentHashMap<>();
    private static List<Component> statsLines = List.of();
    private static List<Component> charmedLines = List.of();
    // Click to Open Bazaar: the item searched for from the shopping list, highlighted in the Bazaar menus until they close.
    private static String bazaarSearchName;
    private static long bazaarSearchSince;
    private static boolean bazaarSearchSeen;

    // Compact chat state.
    private static final List<Component> compactOriginal = new ArrayList<>();
    private static String compactVisitorName = "";
    private static final List<String> compactRewards = new ArrayList<>();

    // Supercraft: the item to open the recipe of, when the sacks have what it takes.
    private static String superCraftItem;
    private static final Map<String, Optional<long[]>> superCraftCache = new ConcurrentHashMap<>();
    /** Item id -> {base item display name, multiplier} for crops; empty when not a crop. */
    private static final Map<String, Optional<Object[]>> primitiveCache = new ConcurrentHashMap<>();

    private GardenVisitors() {}

    static VisitorConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.visitors;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("garden-visitors.json");
        load();
        SkyHanniRepo.loadAsync("Garden", GardenVisitors::readRepo);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            visitors.clear();
            lastWorldSwitch = System.currentTimeMillis();
            lastVisitors = -1;
            long next = storage().nextFullQueueVisitor;
            if (next > 0) fullQueueArrivalTime = next;
            lastMillis = fullQueueArrivalTime - System.currentTimeMillis();
        });
        ClientTickEvents.END_CLIENT_TICK.register(GardenVisitors::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> save());
        SackTracker.onUpdate(GardenVisitors::update);

        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> onClickEntity(level.isClientSide(), entity));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> onClickEntity(level.isClientSide(), entity));
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            if (!Sky2MLocation.inGarden()) return;
            cropBreaks.add(System.currentTimeMillis());
            // SkyHanni's onCropClick: farming makes the next visitor come sooner.
            fullQueueArrivalTime -= 100;
            if (lastMillis > 5 * 60_000L) lastTimerUpdate -= 100;
        });

        Sky2MChat.onChat(message -> onChat(message.component(), message.text()));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !blockMessage(message));

        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> onTooltip(stack, lines));
        EntityGlow.register(GardenVisitors::glowColour);
        Sky2MWorldRender.register(collector -> {
            VisitorConfig config = config();
            if (config == null || !config.highlightStatus.showName() || !Sky2MLocation.inGarden() || !onBarnPlot()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
            for (Visitor visitor : visitors.values()) {
                Entity tag = mc.level.getEntity(visitor.nameTagEntityId);
                if (tag == null || tag.distanceTo(mc.player) > 15) continue;
                Vec3 at = tag.getPosition(partial);
                collector.submitText(SbcItems.parseLegacy(visitor.status.displayName), at.add(0, 2.23, 0), true);
                if (config.rewardWarning.showOverName) {
                    int counter = 0;
                    for (VisitorReward reward : visitor.rewardWarningAwards()) {
                        collector.submitText(SbcItems.parseLegacy("§c§l! " + reward.displayName + " §c§l!"), at.add(0, 2.73 + counter * 0.25, 0), true);
                        counter++;
                    }
                }
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> afterScreen(s, graphics, mouseX, mouseY));
            // Click to Open Bazaar: a click on a shopping list item drawn over the menu.
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> event.button() != 0 || !clickShopList(s, event.x(), event.y()));
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                VisitorConfig config = config();
                if (!inInventory || config == null || config.acceptHotkey <= 0 || event.key() != config.acceptHotkey) return true;
                clickSlot(container, ACCEPT_SLOT);
                return false;
            });
        });

        Sky2MHuds.setting("visitor_shopping_list", () -> config() != null && config().shoppingList.enabled);
        Sky2MHuds.registerCustom(SHOPPING_HUD, "Visitor Shopping List",
            () -> config() != null && config().shoppingList.enabled && showShoppingList() && Minecraft.getInstance().gui.screen() == null,
            new Sky2MHuds.CustomHud() {
                @Override public int width() { return shownList(true).width(); }
                @Override public int height() { return shownList(true).height(); }
                @Override public boolean visible() { return !shoppingList.rows().isEmpty(); }

                @Override
                public void render(GuiGraphicsExtractor graphics, boolean preview) {
                    drawShopList(graphics, shownList(preview), Sky2MHuds.placement(SHOPPING_HUD).background, -1);
                }
            }, 180, 170);
        Sky2MHuds.setting("visitor_timer", () -> config() != null && config().timer.enabled);
        Sky2MHuds.register("visitor_timer", "Garden Visitor Timer",
            () -> config() != null && config().timer.enabled && Sky2MLocation.inGarden(),
            () -> timerLines,
            List.of(SbcItems.parseLegacy("§b3§7/§b7 visitors §7(Next in §e5m 3s§7)")),
            200, 40);
        Sky2MHuds.setting("visitor_stats", () -> config() != null && config().dropsStatistics.enabled);
        Sky2MHuds.register("visitor_stats", "Visitor Stats",
            () -> {
                VisitorConfig c = config();
                return c != null && c.dropsStatistics.enabled && Sky2MLocation.inGarden() && (!c.dropsStatistics.onlyOnBarn || onBarnPlot());
            },
            () -> statsLines,
            List.of(SbcItems.parseLegacy("§e§lVisitor Statistics"), SbcItems.parseLegacy("§e1,636 Total"), SbcItems.parseLegacy("§21,382 Accepted")),
            5, 20);
        Sky2MHuds.setting("charmed_visitors", () -> config() != null && config().charmed.enabled);
        Sky2MHuds.register("charmed_visitors", "Charmed Visitors",
            () -> config() != null && config().charmed.enabled && Sky2MLocation.inGarden(),
            () -> charmedLines,
            List.of(SbcItems.parseLegacy("§dCharmed Visitors §7(1):"), SbcItems.parseLegacy(" §7- §9Lazy Miner")),
            180, 200);
    }

    // ------------------------------------------------------------------------------------------------ repo + storage

    private static void readRepo(JsonObject json) {
        Map<String, GardenVisitorData> out = new HashMap<>();
        if (!json.has("visitors")) return;
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("visitors").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject v = entry.getValue().getAsJsonObject();
            List<String> needs = new ArrayList<>();
            if (v.has("need_items") && v.get("need_items").isJsonArray()) {
                v.getAsJsonArray("need_items").forEach(e -> { if (!e.isJsonNull()) needs.add(e.getAsString()); });
            }
            double[] position = null;
            // SkyHanni's file has null for some visitors' fields (e.g. no fixed position); a null used to abort the
            // whole read, leaving no visitor data at all.
            String pos = repoString(v, "position");
            if (pos != null) {
                String[] parts = pos.split(":");
                if (parts.length == 3) position = new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
            }
            String skin = repoString(v, "skinOrType");
            if (skin != null) skin = skin.replace("\\n", "").replace("\n", "");
            // The file spells these "show_chat_message" and "unknown_rewards"; only the camel case ones used to be read.
            out.put(entry.getKey(), new GardenVisitorData(rarityOverride(entry.getKey(), repoString(v, "rarity")), needs, repoString(v, "mode"),
                position, skin, repoBool(v, "showChatMessage") || repoBool(v, "show_chat_message"),
                repoBool(v, "unknownRewards") || repoBool(v, "unknown_rewards")));
        }
        repoVisitors = out;
    }

    /** Hypixel's October 2026 update made Taylor a SPECIAL visitor; the repo file may still say mythic. */
    private static String rarityOverride(String name, String rarity) {
        return "Taylor".equals(name) ? "special" : rarity;
    }

    private static String repoString(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean repoBool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    /** SkyHanni's GardenVisitorColorNames: the name in its rarity colour from the repo. */
    static String coloredName(String name, String fallbackColour) {
        GardenVisitorData data = repoVisitors.get(name);
        if (data != null && data.rarity() != null) {
            String colour = switch (data.rarity().toLowerCase(Locale.ROOT)) {
                case "uncommon" -> "§a";
                case "rare" -> "§9";
                case "legendary" -> "§6";
                case "mythic" -> "§d";
                case "special" -> "§c";
                default -> null;
            };
            if (colour != null) return colour + name;
        }
        return (fallbackColour == null ? "§f" : fallbackColour) + name;
    }

    static ProfileData storage() {
        String id = Sky2MStorageSearch.currentProfile();
        return profiles.computeIfAbsent(id.isEmpty() ? "default" : id, k -> new ProfileData());
    }

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            Map<String, ProfileData> data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                new TypeToken<Map<String, ProfileData>>() {}.getType());
            if (data != null) profiles.putAll(data);
        } catch (Exception ignored) {}
    }

    private static void markDirty() {
        dirty = true;
    }

    private static void save() {
        if (!dirty || file == null) return;
        dirty = false;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(profiles), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        ticks++;
        runDelayed();
        tickBazaarSearch(mc.gui.screen());
        if (dirty && ticks % 200 == 0) save();
        if (mc.level == null || mc.player == null) return;
        VisitorConfig config = config();
        if (config == null) return;

        Screen screen = mc.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            if (inInventory || openVisitor != null) onInventoryClose();
        } else if (container.getMenu().containerId != openMenuContainer) {
            if (inInventory) onInventoryClose();
            if (Sky2MLocation.inGarden()) tryOpenVisitor(container);
        } else if (inInventory && openVisitor != null) {
            checkCharmed(container, openVisitor);
        }
        if (screen instanceof AbstractContainerScreen<?> container && ticks % 4 == 0 && Sky2MLocation.inGarden()) npcVisitorFixOnOpen(container);

        if (ticks % 20 == 0 && Sky2MLocation.inGarden()) readTabList(config);
        if (Sky2MLocation.inGarden() && ticks % 10 == 2 && onBarnPlot()
            && (config.shoppingList.enabled || config.highlightStatus != VisitorConfig.HighlightMode.DISABLED)) {
            checkVisitorsReady();
        }
        if (config.highlightVisitors && ticks % 20 == 5) updateOutsideHighlights(mc);
        if (ticks % 20 == 0) updateShoppingList();
    }

    private static void runDelayed() {
        if (delayed.isEmpty()) return;
        List<Object[]> due = new ArrayList<>();
        delayed.removeIf(entry -> {
            if ((long) entry[0] <= ticks) {
                due.add(entry);
                return true;
            }
            return false;
        });
        for (Object[] entry : due) ((Runnable) entry[1]).run();
    }

    private static void runLater(int inTicks, Runnable runnable) {
        delayed.add(new Object[]{ticks + inTicks, runnable});
    }

    // ------------------------------------------------------------------------------------------------ tab list

    private static void readTabList(VisitorConfig config) {
        List<Component> lines = new ArrayList<>();
        for (PlayerInfo info : Sky2MTabWidgetManager.players()) {
            Component name = Compat.rawTabName(info);
            if (name != null) lines.add(name);
        }
        Map<String, String> inTab = visitorsInTab(lines);
        if (System.currentTimeMillis() - lastWorldSwitch > 2000) {
            for (String name : new ArrayList<>(visitors.keySet())) {
                if (!inTab.containsKey(name)) visitors.remove(name);
            }
        }
        for (Map.Entry<String, String> e : inTab.entrySet()) addVisitor(e.getKey(), e.getValue());
        if (config.timer.enabled) updateTimer(lines, inTab.size(), config.timer);
    }

    /** Visitor name -> its colour code, from the Visitors widget. */
    private static Map<String, String> visitorsInTab(List<Component> tab) {
        Map<String, String> out = new LinkedHashMap<>();
        int remaining = 0;
        boolean found = false;
        for (Component line : tab) {
            String text = ChatFormatting.stripFormatting(line.getString());
            Matcher m = VISITORS_HEADER.matcher(text);
            if (m.matches()) {
                found = true;
                remaining = Integer.parseInt(m.group(1));
                continue;
            }
            if (!found) continue;
            if (remaining <= 0) {
                found = false;
                continue;
            }
            String name = text.trim();
            if (!name.isEmpty()) out.put(name, firstColour(SbcItems.legacy(line)));
            remaining--;
        }
        return out;
    }

    private static final Pattern FIRST_COLOUR = Pattern.compile("§([0-9a-f])");

    private static String firstColour(String legacy) {
        Matcher m = FIRST_COLOUR.matcher(legacy);
        return m.find() ? "§" + m.group(1) : "§f";
    }

    private static Visitor addVisitor(String name, String colour) {
        Visitor existing = visitors.get(name);
        if (existing != null) return existing;
        Visitor visitor = new Visitor(name, colour);
        visitors.put(name, visitor);
        onVisitorArrival(visitor);
        return visitor;
    }

    private static void onVisitorArrival(Visitor visitor) {
        visitorJustArrived = true;
        update();
        if (System.currentTimeMillis() - lastWorldSwitch < 3000) return;
        VisitorConfig config = config();
        if (config == null) return;
        if (config.notificationTitle) Sky2MAlerts.title(SbcItems.parseLegacy("§eNew Visitor"), Component.empty(), false);
        if (config.notificationChat) Sky2MAlerts.chat(SbcItems.parseLegacy(visitor.displayName() + " §eis visiting your garden!"));
    }

    // ------------------------------------------------------------------------------------------------ visitor timer

    /**
     * SkyHanni's GardenVisitorTimer, for the October 2026 visitor rules: the queue holds 5 visitors plus your Max
     * Occupancy tier (up to 10), and the cooldown (lowered by Better Advertising) is learned from the tab list's countdown
     * each time a visitor arrives. While the queue is full the countdown to the next visitor is estimated.
     */
    private static void updateTimer(List<Component> tab, int visitorsAmount, VisitorConfig.Timer config) {
        ProfileData storage = storage();
        String info = null;
        for (Component line : tab) {
            Matcher m = NEXT_VISITOR.matcher(ChatFormatting.stripFormatting(line.getString()));
            if (m.matches()) {
                info = m.group(1).trim();
                break;
            }
        }
        if (info == null) {
            timerLines = List.of(SbcItems.parseLegacy("§cVisitor time info not in tab list"));
            return;
        }
        if (info.equals("Not Unlocked!")) {
            timerLines = List.of(SbcItems.parseLegacy("§cVisitors not unlocked!"));
            return;
        }
        long now = System.currentTimeMillis();
        Long interval = storage.visitorInterval;
        long millis = interval == null ? 0 : interval;
        boolean queueFull = info.startsWith("Queue Full");
        if (queueFull) {
            // The queue is as big as the number of visitors when it says it's full.
            if (visitorsAmount > 0 && !Integer.valueOf(visitorsAmount).equals(storage.queueSize)) {
                storage.queueSize = visitorsAmount;
                markDirty();
            }
        } else {
            // More visitors than the learned size without a full queue: Max Occupancy was upgraded since.
            if (storage.queueSize != null && visitorsAmount >= storage.queueSize) {
                storage.queueSize = null;
                markDirty();
            }
            if (!lastTimerValue.equals(info)) {
                lastTimerUpdate = now;
                lastTimerValue = info;
            }
            millis = parseDuration(info);
        }

        boolean arrived = lastVisitors != -1 && visitorsAmount - lastVisitors == 1;
        if (arrived) {
            if (!queueFull) {
                storage.visitorInterval = millis;
                markDirty();
            } else if (interval != null) {
                fullQueueArrivalTime = now + interval;
            }
        }
        boolean estimateKnown = false;
        if (queueFull) {
            if (visitorJustArrived && arrived && interval != null) {
                fullQueueArrivalTime = now + interval;
                visitorJustArrived = false;
            }
            estimateKnown = interval != null && fullQueueArrivalTime > 0;
            millis = fullQueueArrivalTime - now;
            if (estimateKnown && storage.nextFullQueueVisitor != fullQueueArrivalTime) {
                storage.nextFullQueueVisitor = fullQueueArrivalTime;
                markDirty();
            }
            if (estimateKnown && millis < 0 && config.fullQueueEstimate && config.fullQueueWarning) warnNextVisitorReady();
        }
        long sinceLastTimerUpdate = now - lastTimerUpdate - 100;
        if (!queueFull && sinceLastTimerUpdate >= 500 && sinceLastTimerUpdate <= 60_000) millis -= sinceLastTimerUpdate;

        if (lastMillis == millis && visitorsAmount == lastVisitors) return;
        lastMillis = millis;
        lastVisitors = visitorsAmount;

        String formatColour = queueFull ? "6" : "e";
        boolean farming = isCurrentlyFarming();
        long adjusted = farming ? millis / 3 : millis;
        String extraSpeed = "";
        if (farming) {
            long duration = (long) (adjusted * (recentBps() / 20.0));
            extraSpeed = "§7/§" + formatColour + formatDuration(duration);
        }
        // No visitor can arrive while the queue is full, so there is nothing to ping for then.
        if (config.newVisitorPing && !queueFull && adjusted < 10_000 && now - lastPing > 900) {
            lastPing = now;
            Sky2MAlerts.ding();
        }
        String next;
        if (!queueFull) next = "Next in §e" + formatDuration(millis) + extraSpeed;
        else if (!config.fullQueueEstimate || !estimateKnown) next = "§cQueue Full!";
        else if (millis < 0) next = "§cQueue Full§7, §anext ready";
        else next = "§cQueue Full§7, next in §6" + formatDuration(millis) + extraSpeed;
        String label = visitorsAmount == 1 ? "visitor" : "visitors";
        String count = "§b" + visitorsAmount;
        if (storage.queueSize != null || config.maxOccupancy > 0) count += "§7/§b" + queueSize(config, visitorsAmount);
        timerLines = List.of(SbcItems.parseLegacy(count + " " + label + " §7(" + next + "§7)"));
    }

    /**
     * How many visitors the queue holds: 5 plus the Max Occupancy tier (Hypixel's October 2026 update; it used to be 5,
     * with a bug that let a hidden 6th one wait), or what the tab list showed when it was last full, if that's more.
     */
    static int queueSize(VisitorConfig.Timer config, int visitorsAmount) {
        return queueSize(config.maxOccupancy, storage().queueSize, visitorsAmount);
    }

    /** {@link #queueSize(VisitorConfig.Timer, int)} from its parts (public for the game tests). */
    public static int queueSize(int maxOccupancy, Integer learned, int visitorsAmount) {
        int size = BASE_QUEUE_SIZE + Math.max(0, Math.min(MAX_OCCUPANCY_TIERS, maxOccupancy));
        if (learned != null) size = Math.max(size, learned);
        return Math.max(size, visitorsAmount);
    }

    /** Once per estimate: the countdown ran out while the queue was full. */
    private static void warnNextVisitorReady() {
        if (System.currentTimeMillis() - lastWorldSwitch < 3000) return;
        if (lastFullQueueWarning == fullQueueArrivalTime) return;
        lastFullQueueWarning = fullQueueArrivalTime;
        Sky2MAlerts.title(SbcItems.parseLegacy("§aNext Visitor Ready"), SbcItems.parseLegacy("§7Serve a visitor to make room"), true);
    }

    private static boolean isCurrentlyFarming() {
        long now = System.currentTimeMillis();
        cropBreaks.removeIf(t -> now - t > 1000);
        return !cropBreaks.isEmpty();
    }

    private static double recentBps() {
        long now = System.currentTimeMillis();
        cropBreaks.removeIf(t -> now - t > 1000);
        return cropBreaks.size();
    }

    static long parseDuration(String text) {
        long total = 0;
        Matcher m = DURATION_PART.matcher(text);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "d" -> n * 86_400_000L;
                case "h" -> n * 3_600_000L;
                case "m" -> n * 60_000L;
                default -> n * 1000L;
            };
        }
        return total;
    }

    static String formatDuration(long millis) {
        if (millis < 0) millis = 0;
        long seconds = millis / 1000;
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (h > 0) out.append(h).append("h ");
        if (m > 0 || h > 0) out.append(m).append("m ");
        out.append(s).append("s");
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------ visitor inventory

    private static InteractionResult onClickEntity(boolean clientSide, Entity entity) {
        if (!clientSide) return InteractionResult.PASS;
        if (Sky2MLocation.inGarden()) lastClickedNpc = entity.getId();
        // HighlightVisitorsOutsideOfGarden: block interacting with visitors (for Dedication cycling).
        VisitorConfig config = config();
        if (config == null || !Compat.isOnSkyblock() || Sky2MLocation.inGarden()) return InteractionResult.PASS;
        boolean shouldBlock = switch (config.blockInteracting) {
            case DONT -> false;
            case ALWAYS -> true;
            case ONLY_ON_BINGO -> isBingoProfile();
        };
        if (!shouldBlock) return InteractionResult.PASS;
        Player player = Minecraft.getInstance().player;
        if (player != null && player.isShiftKeyDown()) return InteractionResult.PASS;
        if (isVisitorOutside(entity) || (entity instanceof ArmorStand && visitorNearby(entity))) {
            Sky2MAlerts.chat(Component.literal("Blocked you from interacting with a visitor. Sneak to bypass or change it in /s2 (Garden > Visitors).").withStyle(ChatFormatting.YELLOW));
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    /** SkyHanni's InventoryFullyOpenedEvent check: the visitor's info item and the Accept Offer button have arrived. */
    private static void tryOpenVisitor(AbstractContainerScreen<?> screen) {
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= REFUSE_SLOT + 36) return;
        ItemStack npcItem = slots.get(INFO_SLOT).getItem();
        ItemStack offerItem = slots.get(ACCEPT_SLOT).getItem();
        if (npcItem.isEmpty() || offerItem.isEmpty()) return;
        List<String> lore = lore(npcItem);
        if (lore.size() != 4 || !lore.get(3).startsWith("Offers Accepted: ")) return;
        if (!ChatFormatting.stripFormatting(offerItem.getHoverName().getString()).equals("Accept Offer")) return;

        openMenuContainer = screen.getMenu().containerId;
        inInventory = true;
        String name = ChatFormatting.stripFormatting(npcItem.getHoverName().getString()).trim();
        Visitor visitor = visitors.get(name);
        if (visitor == null) {
            // The tab list hasn't updated yet when opening the visitor.
            visitor = addVisitor(name, firstColour(SbcItems.legacy(npcItem.getHoverName())));
        }
        Matcher m = OFFERS_ACCEPTED.matcher(lore.get(3));
        visitor.offersAccepted = m.find() ? Integer.parseInt(m.group(1)) : null;
        visitor.entityId = lastClickedNpc;
        visitor.offerItem = offerItem.copy();
        openVisitor = visitor;
        lastVisitorOpen = System.currentTimeMillis();
        readVisitorOffer(visitor);
        checkCharmed(screen, visitor);
        checkSuperCraft(visitor);
    }

    private static void onInventoryClose() {
        inInventory = false;
        openVisitor = null;
        openMenuContainer = -1;
        superCraftItem = null;
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Component line : lore.lines()) out.add(ChatFormatting.stripFormatting(line.getString()));
        return out;
    }

    private static void readVisitorOffer(Visitor visitor) {
        List<String> lore = lore(visitor.offerItem);
        // The shopping list: the lines after "Items Required:" up to the first empty one.
        visitor.shoppingList.clear();
        boolean started = false;
        for (String raw : lore) {
            String line = raw.trim();
            if (line.equals("Items Required:")) {
                started = true;
                continue;
            }
            if (!started) continue;
            if (line.isEmpty()) break;
            // Items the item list doesn't know (such as the mutations Ghost Visitors ask for, if they're missing from
            // it) are kept by name, so the visitor doesn't look like it needs nothing (and show as Items Ready).
            ParsedItem item = readItemLine(line, true, true);
            if (item != null) visitor.shoppingList.put(item.id, item.amount);
        }
        visitor.lastLore = List.of();
        visitor.blockedLore = List.of();
        readToolTip(visitor, tooltipOf(visitor.offerItem));
        if (lore.stream().anyMatch(l -> l.trim().equals("Click to give!"))) changeStatus(visitor, Status.READY);
        else changeStatus(visitor, Status.WAITING);
        update();
    }

    private static List<Component> tooltipOf(ItemStack stack) {
        List<Component> lines = new ArrayList<>();
        lines.add(stack.getHoverName());
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) lines.addAll(lore.lines());
        return lines;
    }

    private record ParsedItem(String id, String name, int amount) {}

    /** A tooltip line as an item and amount ("Enchanted Hay Bale x2", "+2x Gold Essence", "Dedication IV"). */
    private static ParsedItem readItemLine(String line, boolean shoppingList) {
        return readItemLine(line, shoppingList, false);
    }

    /** @param keepUnknown give an item the item list doesn't know a {@link #NAMED_ITEM} id instead of skipping it */
    private static ParsedItem readItemLine(String line, boolean shoppingList, boolean keepUnknown) {
        String text = ChatFormatting.stripFormatting(line).trim();
        if (!shoppingList) text = text.replace(" ❤", "").trim();
        if (text.startsWith("+")) text = text.substring(1).trim();
        Matcher m = ITEM_LINE.matcher(text);
        if (!m.matches()) return null;
        String name = m.group(2).replace("◆ ", "").trim();
        if (NOT_ITEMS.contains(name) || name.endsWith(" Powder") || name.endsWith(":")) return null;
        int amount = 1;
        try {
            if (m.group(1) != null) amount = Integer.parseInt(m.group(1).replace(",", ""));
            else if (m.group(3) != null) amount = Integer.parseInt(m.group(3).replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        VisitorReward reward = VisitorReward.byName(name);
        String id = reward != null ? reward.internalName : RepoItems.idByName(name);
        if (id == null && keepUnknown && !name.isBlank()) id = NAMED_ITEM + name;
        if (id == null || id.startsWith("SKYBLOCK_")) return null;
        return new ParsedItem(id, name, amount);
    }

    /** The id prefix of a shopping list item the item list doesn't know, followed by its plain name. */
    private static final String NAMED_ITEM = "name:";
    private static final Map<String, String> RESOLVED = new ConcurrentHashMap<>();

    static boolean isNamedItem(String id) {
        return id.startsWith(NAMED_ITEM);
    }

    /** A named item's real id once the item list (which may still have been loading) knows it, else the id itself. */
    static String resolveItem(String id) {
        if (!isNamedItem(id)) return id;
        String known = RESOLVED.get(id);
        if (known != null) return known;
        if (!RepoItems.itemsLoaded()) return id;
        String found = RepoItems.idByName(id.substring(NAMED_ITEM.length()));
        String resolved = found == null ? id : found;
        RESOLVED.put(id, resolved);
        return resolved;
    }

    /** The item's coloured name from the item list, or a named item's plain name. */
    private static String itemDisplayName(String id) {
        if (isNamedItem(id)) return "§f" + id.substring(NAMED_ITEM.length());
        String name = RepoItems.displayName(id);
        return name == null ? id : name;
    }

    /** The price key for a NEU id: books as ENCHANTMENT_NAME_LEVEL, runes as NAME_RUNE_LEVEL. */
    static String priceKey(String id) {
        int semicolon = id.indexOf(';');
        if (semicolon < 0) return id;
        String base = id.substring(0, semicolon), level = id.substring(semicolon + 1);
        if (base.endsWith("_RUNE")) return base + "_" + level;
        if (base.startsWith("POTION_")) return id;
        return "ENCHANTMENT_" + base + "_" + level;
    }

    static double price(String id, int amount) {
        if (isNamedItem(id)) return 0;
        return Sky2MPriceTooltip.unitPrice(priceKey(id)) * amount;
    }

    /** SkyHanni's VisitorPriceCalculator: copper is worth Green Thumb I's price / 1500. */
    private static double copperValue() {
        return Sky2MPriceTooltip.unitPrice("ENCHANTMENT_GREEN_THUMB_1") / 1500;
    }

    /** SkyHanni's GardenVisitorTooltip.readToolTip: works out the prices and builds the tooltip with them. */
    private static void readToolTip(Visitor visitor, List<Component> toolTip) {
        VisitorConfig config = config();
        if (config == null) return;
        double totalPrice = 0;
        boolean shopping = true;
        lastFullPrice = 0;
        List<String> foundRewards = new ArrayList<>();
        Map<String, Integer> required = new HashMap<>(), rewards = new HashMap<>();
        for (String line : lore(visitor.offerItem)) {
            if (line.contains("Rewards")) shopping = false;
            ParsedItem item = readItemLine(line, shopping);
            if (item == null) continue;
            double price = price(item.id, item.amount);
            if (shopping) {
                required.put(item.id, item.amount);
                totalPrice += price;
                lastFullPrice += price;
            } else {
                rewards.put(item.id, item.amount);
                foundRewards.add(item.id);
                totalPrice -= price;
            }
        }
        if (totalPrice < 0) totalPrice = 0;
        notifyFoundRewards(visitor, foundRewards, config);

        // Defaults when the offer has no copper line.
        visitor.pricePerCopper = 0;
        visitor.totalPrice = totalPrice;
        visitor.totalReward = 0.0;

        shopping = true;
        long farmingTime = 0;
        List<Component> out = new ArrayList<>();
        for (Component component : toolTip) {
            String line = ChatFormatting.stripFormatting(component.getString());
            MutableComponent edited = component.copy();
            List<Component> after = new ArrayList<>();
            Matcher exp = GARDEN_EXP.matcher(line);
            if (config.inventory.experiencePrice && exp.matches()) {
                int gardenExp = Integer.parseInt(exp.group(1).replace(",", ""));
                if (gardenExp > 0) edited.append(SbcItems.parseLegacy(" §7(paying §6" + shortFormat(totalPrice / gardenExp) + " §7per)"));
            }
            Matcher copper = COPPER.matcher(line);
            if (copper.matches()) {
                int amount = Integer.parseInt(copper.group(1).replace(",", ""));
                int pricePerCopper = amount <= 0 ? 0 : (int) (totalPrice / amount);
                visitor.pricePerCopper = pricePerCopper;
                visitor.totalReward = amount * copperValue();
                if (config.inventory.copperPrice) edited.append(SbcItems.parseLegacy(" §7(paying §6" + shortFormat(pricePerCopper) + " §7per)"));
                if (config.inventory.copperTime) {
                    edited.append(SbcItems.parseLegacy(farmingTime != 0 ? " §7(paying §b" + formatDuration(farmingTime / amount) + " §7per)" : " §7(§cno speed data!§7)"));
                }
            }
            if (line.contains("Rewards")) shopping = false;
            ParsedItem item = component == toolTip.getFirst() ? null : readItemLine(line, shopping);
            if (item != null) {
                Integer known = (shopping ? required : rewards).get(item.id);
                int amount = known != null ? known : item.amount;
                if (config.inventory.showPrice) edited.append(SbcItems.parseLegacy(" §7(§6" + shortFormat(price(item.id, amount)) + "§7)"));
                if (shopping && config.inventory.exactAmountAndTime) {
                    Object[] primitive = primitive(item.id, visitor);
                    if (primitive != null) {
                        long cropAmount = (long) primitive[1] * amount;
                        after.add(SbcItems.parseLegacy("§7- §e" + String.format(Locale.US, "%,d", cropAmount) + "§7x " + primitive[0]));
                    }
                }
            }
            out.add(edited);
            out.addAll(after);
        }
        visitor.lastLore = out;
        visitor.blockReason = blockReason(visitor, config.rewardWarning);
    }

    private static void notifyFoundRewards(Visitor visitor, List<String> found, VisitorConfig config) {
        if (found.isEmpty()) return;
        boolean wasEmpty = visitor.allRewards.isEmpty();
        visitor.allRewards = found;
        if (!wasEmpty || !config.rewardWarning.notifyInChat) return;
        for (VisitorReward reward : visitor.rewardWarningAwards()) {
            Sky2MAlerts.chat(SbcItems.parseLegacy("§eFound Visitor Reward " + reward.displayName + "§e!"));
        }
    }

    /**
     * The base crop and how many of it one {@code id} is made of (Enchanted Hay Bale -> Wheat), from the NEU recipes;
     * null while it's being worked out, or if it isn't a crop.
     */
    private static Object[] primitive(String id, Visitor visitor) {
        Optional<Object[]> cached = primitiveCache.get(id);
        if (cached != null) return cached.orElse(null);
        primitiveCache.put(id, Optional.empty());
        RepoItems.runAsync(() -> {
            String current = id;
            long multiplier = 1;
            for (int depth = 0; depth < 6; depth++) {
                String name = RepoItems.displayName(current);
                String plain = name == null ? "" : ChatFormatting.stripFormatting(name).trim();
                if (CROPS.contains(plain)) {
                    primitiveCache.put(id, Optional.of(new Object[]{plain, multiplier}));
                    Minecraft.getInstance().execute(() -> visitor.lastLore = List.of());
                    return;
                }
                Sky2MCraftHelper.Recipe recipe = Sky2MCraftHelper.recipeOf(current);
                if (recipe == null || recipe.inputs().size() != 1) return;
                Sky2MCraftHelper.Input input = recipe.inputs().getFirst();
                multiplier = multiplier * input.amount() / Math.max(1, recipe.outputCount());
                current = input.id();
            }
        });
        return null;
    }

    private static BlockReason blockReason(Visitor visitor, VisitorConfig.RewardWarning config) {
        int pricePerCopper = visitor.pricePerCopper == null ? 0 : visitor.pricePerCopper;
        double loss = (visitor.totalPrice == null ? 0 : visitor.totalPrice) - (visitor.totalReward == null ? 0 : visitor.totalReward);
        if (config.preventRefusing && !visitor.rewardWarningAwards().isEmpty()) return BlockReason.RARE_REWARD;
        if (config.preventRefusingNew && !isBingoProfile() && visitor.offersAccepted != null && visitor.offersAccepted == 0) return BlockReason.NEVER_ACCEPTED;
        if (config.preventRefusingCopper && pricePerCopper <= config.coinsPerCopperPrice) return BlockReason.CHEAP_COPPER;
        if (config.preventAcceptingCopper && pricePerCopper > config.coinsPerCopperPrice) return BlockReason.EXPENSIVE_COPPER;
        if (config.preventRefusingLowLoss && loss <= config.coinsLossThreshold) return BlockReason.LOW_LOSS;
        if (config.preventAcceptingHighLoss && loss > config.coinsLossThreshold) return BlockReason.HIGH_LOSS;
        return null;
    }

    private static void onTooltip(ItemStack stack, List<Component> lines) {
        if (!inInventory || openVisitor == null || !Sky2MLocation.inGarden()) return;
        VisitorConfig config = config();
        if (config == null) return;
        Visitor visitor = openVisitor;
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        boolean bypassing = keyHeld(config.rewardWarning.bypassKey);
        if (name.equals("Accept Offer")) {
            // The blocked tooltip looks different, so the cache is invalid after toggling the bypass key.
            if (bypassing != lastBypassState) {
                lastBypassState = bypassing;
                visitor.lastLore = List.of();
                visitor.blockedLore = List.of();
            }
            if (visitor.lastLore.isEmpty()) {
                visitor.offerItem = stack.copy();
                readToolTip(visitor, new ArrayList<>(lines));
            }
            lines.clear();
            lines.addAll(visitor.lastLore);
        }
        // VisitorRewardWarning: the blocked button's tooltip.
        if (bypassing) return;
        BlockReason reason = visitor.blockReason;
        if (reason == null) return;
        boolean isRefuse = name.equals("Refuse Offer"), isAccept = name.equals("Accept Offer");
        if (reason.blockRefusing ? !isRefuse : !isAccept) return;
        if (visitor.blockedLore.isEmpty()) visitor.blockedLore = blockedLore(lines, visitor, reason, config.rewardWarning);
        lines.clear();
        lines.addAll(visitor.blockedLore);
    }

    private static List<Component> blockedLore(List<Component> tooltip, Visitor visitor, BlockReason reason, VisitorConfig.RewardWarning config) {
        List<Component> out = new ArrayList<>();
        for (Component tip : tooltip) {
            String line = tip.getString();
            if (line.contains("Accept Offer")) out.add(SbcItems.parseLegacy("§aAccept Offer"));
            else if (line.contains("Refuse Offer")) out.add(SbcItems.parseLegacy("§cRefuse Offer"));
            else out.add(SbcItems.parseLegacy("§8" + ChatFormatting.stripFormatting(line)));
        }
        out.add(Component.empty());
        double loss = (visitor.totalPrice == null ? 0 : visitor.totalPrice) - (visitor.totalReward == null ? 0 : visitor.totalReward);
        String extra = switch (reason) {
            case CHEAP_COPPER, EXPENSIVE_COPPER -> " §7(paying §6" + shortFormat(visitor.pricePerCopper == null ? 0 : visitor.pricePerCopper) + " §7per)";
            case LOW_LOSS, HIGH_LOSS -> " §7(§6" + shortFormat(Math.abs(loss)) + " §7" + (loss > 0 ? "loss" : "profit") + " selling §9Green Thumb I§7)";
            default -> "";
        };
        out.add(SbcItems.parseLegacy(reason.description + extra));
        out.add(SbcItems.parseLegacy("  §7(Bypass by holding " + keyName(config.bypassKey) + ")"));
        return out;
    }

    /** Called from the container screen's slot click (ContainerSolverScreenMixin). @return true to cancel the click. */
    public static boolean onSlotClicked(AbstractContainerScreen<?> screen, Slot slot, ContainerInput input) {
        if (!inInventory || openVisitor == null || slot == null) return false;
        VisitorConfig config = config();
        if (config == null) return false;
        if (superCraftItem != null && slot.index == SUPERCRAFT_SLOT && config.shoppingList.showSuperCraft) {
            String id = superCraftItem;
            var connection = Minecraft.getInstance().getConnection();
            if (connection != null) connection.sendCommand("viewrecipe " + id);
            return true;
        }
        Visitor visitor = openVisitor;
        String name = ChatFormatting.stripFormatting(slot.getItem().getHoverName().getString());
        boolean isRefuse = name.equals("Refuse Offer"), isAccept = name.equals("Accept Offer");
        if (!isRefuse && !isAccept) return false;
        BlockReason reason = visitor.blockReason;
        boolean shouldBlock = reason != null && (reason.blockRefusing ? isRefuse : isAccept);
        if (shouldBlock && !keyHeld(config.rewardWarning.bypassKey)) return true;
        if (input == ContainerInput.QUICK_MOVE) return false;
        if (isRefuse) {
            changeStatus(visitor, Status.REFUSED);
            // Fallback if the tab list is off.
            runLater(200, () -> visitors.remove(visitor.name));
        } else if (lore(slot.getItem()).stream().anyMatch(l -> l.contains("Click to give!"))) {
            changeStatus(visitor, Status.ACCEPTED);
        }
        return false;
    }

    private static void changeStatus(Visitor visitor, Status status) {
        if (visitor.status == status) return;
        visitor.status = status;
        if (status == Status.ACCEPTED) onVisitorAccepted(visitor);
        else if (status == Status.REFUSED) {
            update();
            storage().drops.deniedVisitors++;
            markDirty();
            updateStats();
        }
    }

    private static void onVisitorAccepted(Visitor visitor) {
        lastAccept = System.currentTimeMillis();
        update();
        Drops drops = storage().drops;
        drops.coinsSpent += Math.round(lastFullPrice);
        if (onBarnPlot()) {
            for (String id : visitor.allRewards) {
                VisitorReward reward = VisitorReward.byInternalName(id);
                if (reward != null) drops.rewardsCount.merge(reward.name(), 1L, Long::sum);
            }
        }
        markDirty();
        updateStats();
    }

    // ------------------------------------------------------------------------------------------------ status

    /** SkyHanni's GardenVisitorStatus.update: the visitors' status and the shopping list. */
    static void update() {
        checkVisitorsReady();
        updateShoppingList();
    }

    private static void checkVisitorsReady() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (Visitor visitor : visitors.values()) {
            if (mc.level.getEntity(visitor.entityId) == null) {
                ArmorStand tag = findNametag(visitor.name);
                if (tag != null) findEntity(tag, visitor);
            }
            if (visitor.status == Status.WAITING || visitor.status == Status.READY) {
                visitor.status = hasItems(visitor) ? Status.READY : Status.WAITING;
            }
        }
    }

    private static boolean hasItems(Visitor visitor) {
        for (Map.Entry<String, Integer> e : visitor.shoppingList.entrySet()) {
            String id = resolveItem(e.getKey());
            if (amountInInventory(id) + amountInSacks(id) < e.getValue()) return false;
        }
        return true;
    }

    private static long amountInInventory(String id) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return 0;
        String named = isNamedItem(id) ? id.substring(NAMED_ITEM.length()) : null;
        long total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            boolean match = named != null
                ? named.equalsIgnoreCase(ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim())
                : id.equals(Compat.neuName(stack));
            if (match) total += stack.getCount();
        }
        return total;
    }

    /** -1 when the item has never been seen in your sacks. */
    private static long amountInSacksOrUnknown(String id) {
        String name = isNamedItem(id) ? id.substring(NAMED_ITEM.length()) : RepoItems.displayName(id);
        return name == null ? -1 : SackTracker.amount(ChatFormatting.stripFormatting(name));
    }

    private static long amountInSacks(String id) {
        return Math.max(0, amountInSacksOrUnknown(id));
    }

    private static void findEntity(ArmorStand nameTag, Visitor visitor) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (LivingEntity entity : mc.level.getEntitiesOfClass(LivingEntity.class, nameTag.getBoundingBox().inflate(5),
                e -> !(e instanceof ArmorStand) && e != mc.player)) {
            double dx = entity.getX() - nameTag.getX(), dz = entity.getZ() - nameTag.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < 0.5) {
                visitor.entityId = entity.getId();
                visitor.nameTagEntityId = nameTag.getId();
            }
        }
    }

    /** SkyHanni's NpcVisitorFix: Jacob and Anita are on the barn twice while visiting, so the static one is skipped. */
    private static ArmorStand findNametag(String visitorName) {
        List<ArmorStand> tags = findNametags(visitorName);
        if (tags.isEmpty()) return null;
        if (!STATIC_VISITORS.contains(visitorName)) return tags.getFirst();
        double[] staticLocation = storage().npcVisitorLocations.get(visitorName);
        if (staticLocation == null) return null;
        tags.removeIf(e -> e.distanceToSqr(staticLocation[0], staticLocation[1], staticLocation[2]) < 9);
        return tags.isEmpty() ? null : tags.getFirst();
    }

    private static List<ArmorStand> findNametags(String visitorName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return new ArrayList<>();
        return new ArrayList<>(mc.level.getEntitiesOfClass(ArmorStand.class, BARN_AREA,
            e -> e.hasCustomName() && ChatFormatting.stripFormatting(e.getName().getString()).equals(visitorName)));
    }

    private static String lastStaticInventory;

    private static void npcVisitorFixOnOpen(AbstractContainerScreen<?> screen) {
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        String name = null;
        for (String s : STATIC_VISITORS) if (title.contains(s)) name = s;
        if (name == null) {
            lastStaticInventory = null;
            return;
        }
        String key = name + screen.getMenu().containerId;
        if (key.equals(lastStaticInventory)) return;
        lastStaticInventory = key;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String visitorName = name;
        ArmorStand nearest = findNametags(name).stream().filter(e -> e.distanceTo(mc.player) < 3).findFirst().orElse(null);
        if (nearest == null) return;
        runLater(4, () -> {
            if (System.currentTimeMillis() - lastVisitorOpen < 1000) return;
            double[] old = storage().npcVisitorLocations.get(visitorName);
            if (old != null && nearest.distanceToSqr(old[0], old[1], old[2]) < 1) return;
            storage().npcVisitorLocations.put(visitorName, new double[]{nearest.getX(), nearest.getY(), nearest.getZ()});
            markDirty();
            Sky2MAlerts.chat(Component.literal("Saved " + visitorName + " NPC location. Real " + visitorName + " visitors are now getting detected correctly.").withStyle(ChatFormatting.YELLOW));
        });
    }

    // ------------------------------------------------------------------------------------------------ highlights

    private static final Map<Integer, Integer> outsideHighlights = new ConcurrentHashMap<>();

    private static int glowColour(Entity entity) {
        VisitorConfig config = config();
        if (config == null) return -1;
        if (Sky2MLocation.inGarden() && config.highlightStatus.color()) {
            for (Visitor visitor : visitors.values()) {
                if (visitor.entityId == entity.getId()) return visitor.status.colour;
            }
        }
        if (config.highlightVisitors) {
            Integer colour = outsideHighlights.get(entity.getId());
            if (colour != null) return colour;
        }
        return -1;
    }

    /** SkyHanni's HighlightVisitorsOutsideOfGarden: visitors standing on their own islands, in dark red. */
    private static void updateOutsideHighlights(Minecraft mc) {
        outsideHighlights.clear();
        if (mc.level == null || !Compat.isOnSkyblock() || Sky2MLocation.inGarden()) return;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof LivingEntity && !(entity instanceof ArmorStand) && isVisitorOutside(entity)) {
                outsideHighlights.put(entity.getId(), 0xAA0000);
            }
        }
    }

    private static boolean isVisitorOutside(Entity entity) {
        String mode = islandMode();
        if (mode == null) return false;
        String skinOrType = entity instanceof Player player ? skin(player) : entityType(entity);
        for (GardenVisitorData data : repoVisitors.values()) {
            if (!mode.equals(data.mode())) continue;
            if (data.position() != null) {
                double dx = entity.getBlockX() - data.position()[0], dy = entity.getBlockY() - data.position()[1], dz = entity.getBlockZ() - data.position()[2];
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) >= 1) continue;
            }
            if (data.skinOrType() != null && data.skinOrType().equals(skinOrType)) return true;
        }
        return false;
    }

    private static boolean visitorNearby(Entity tag) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        return mc.level.getEntitiesOfClass(LivingEntity.class, tag.getBoundingBox().inflate(2), GardenVisitors::isVisitorOutside).size() > 0;
    }

    private static String skin(Player player) {
        try {
            var profile = player.getGameProfile();
            for (var property : profile.properties().get("textures")) return property.value().replace("\n", "");
        } catch (Exception ignored) {}
        return "no skin";
    }

    /** The 1.8 entity class name the repo uses ("EntityWitch"). */
    private static String entityType(Entity entity) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        StringBuilder out = new StringBuilder("Entity");
        for (String part : path.split("_")) {
            if (!part.isEmpty()) out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        String type = out.toString();
        return switch (type) {
            case "EntityMooshroom" -> "EntityMooshroom";
            case "EntityIronGolem" -> "EntityIronGolem";
            default -> type;
        };
    }

    /** The repo's island "mode" for where you are ("hub", "farming_1", ...). */
    private static String islandMode() {
        String area = Sky2MLocation.area();
        if (area == null) return null;
        return switch (area) {
            case "Hub" -> "hub";
            case "The Farming Islands" -> "farming_1";
            case "Gold Mine" -> "mining_1";
            case "Deep Caverns" -> "mining_2";
            case "Dwarven Mines" -> "mining_3";
            case "Crystal Hollows" -> "crystal_hollows";
            case "Spider's Den" -> "combat_1";
            case "Crimson Isle" -> "crimson_isle";
            case "The End" -> "combat_3";
            case "The Park" -> "foraging_1";
            case "Private Island" -> "dynamic";
            case "Dungeon Hub" -> "dungeon_hub";
            case "Jerry's Workshop" -> "winter";
            case "The Rift" -> "rift";
            case "Backwater Bayou" -> "fishing_1";
            case "Galatea" -> "foraging_2";
            default -> null;
        };
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(Component component, String text) {
        VisitorConfig config = config();
        if (config == null) return;
        if (config.shoppingList.enabled && text.equals("You gave some of the required items!")) {
            Sky2MAlerts.chat(Component.literal("Talk to the visitor again to update the number of items needed!").withStyle(ChatFormatting.YELLOW));
        }
        if (BARN_SKIN.matcher(text).matches()) {
            storage().npcVisitorLocations.clear();
            markDirty();
        }
        // Drop statistics: the accepted offer's rewards come in the second after it.
        if (onBarnPlot() && System.currentTimeMillis() - lastAccept <= 1000) {
            String trimmed = text.trim();
            Drops drops = storage().drops;
            for (Map.Entry<Pattern, String> e : STAT_PATTERNS.entrySet()) {
                Matcher m = e.getKey().matcher(trimmed);
                if (!m.matches()) continue;
                long amount = parseShortNumber(m.group(1));
                switch (e.getValue()) {
                    case "copper" -> drops.copper += amount;
                    case "gardenExp" -> drops.gardenExp += amount;
                    case "farmingExp" -> drops.farmingExp += amount;
                    case "bits" -> drops.bits += amount;
                    case "mithrilPowder" -> drops.mithrilPowder += amount;
                    case "gemstonePowder" -> drops.gemstonePowder += amount;
                    case "ectoplasm" -> drops.ectoplasm += amount;
                    default -> {}
                }
                markDirty();
            }
            Matcher accept = STAT_ACCEPT.matcher(trimmed);
            if (accept.matches()) {
                drops.acceptedVisitors++;
                drops.acceptedRarities.merge(accept.group("rarity").trim().toUpperCase(Locale.ROOT), 1L, Long::sum);
                markDirty();
            }
            updateStats();
        }
    }

    /** @return true to hide the message. */
    private static boolean blockMessage(Component component) {
        VisitorConfig config = config();
        if (config == null) return false;
        // Every chat line comes through here: the text is only read when one of the three options can use it.
        boolean inGarden = Sky2MLocation.inGarden();
        boolean gardenChat = inGarden && (config.hideChat || config.compactRewardChat);
        if (!config.hypixelArrivedMessage && !gardenChat) return false;
        String text = ChatFormatting.stripFormatting(component.getString());
        if (config.hypixelArrivedMessage && ARRIVED.matcher(text).matches()) return true;
        if (!gardenChat) return false;
        String legacy = SbcItems.legacy(component);
        if (config.hideChat && hideVisitorMessage(legacy)) return true;
        return config.compactRewardChat && compactChat(component, legacy.replace("§r", ""));
    }

    private static boolean hideVisitorMessage(String legacy) {
        Matcher m = NPC_CHAT.matcher(legacy);
        if (!m.matches()) return false;
        String colour = m.group("color");
        if (colour == null || colour.equals("§e")) return false; // Not a visitor, probably Jacob.
        String name = m.group("name").trim();
        GardenVisitorData data = repoVisitors.get(name);
        if (data != null && data.showChatMessage()) return false;
        if (visitors.containsKey(name)) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        return !mc.level.getEntitiesOfClass(Player.class, BARN_AREA,
            p -> ChatFormatting.stripFormatting(p.getName().getString()).trim().equalsIgnoreCase(name)).isEmpty();
    }

    /** SkyHanni's GardenVisitorCompactChat. @return true to hide the message (it goes into the compact one). */
    private static boolean compactChat(Component component, String message) {
        boolean accepted = FULLY_ACCEPTED.matcher(message).matches();
        Matcher reward = VISITOR_REWARD.matcher(message);
        boolean isReward = reward.matches();
        if (!accepted && !isReward && !REWARDS_TEXT.matcher(message).find()) return false;
        if (accepted) {
            Matcher m = FULLY_ACCEPTED.matcher(message);
            if (m.matches()) {
                compactOriginal.clear();
                compactRewards.clear();
                compactVisitorName = (m.group("color") == null ? "§7" : m.group("color")) + m.group("name");
            }
        }
        if (compactVisitorName.isBlank()) return false;
        if (isReward && !accepted) {
            String rewardColour = reward.group("rewardcolor"), amountColour = reward.group("amountcolor");
            String amount = reward.group("amount"), altAmount = reward.group("altamount"), name = reward.group("reward");
            String colour = rewardColour == null || rewardColour.isBlank() || rewardColour.equals("§7")
                ? (amountColour == null || amountColour.isBlank() ? "§f" : amountColour) : rewardColour;
            boolean discard = DISCARD_REWARD_NAME.matcher(name).matches();
            String amountString = amount != null ? (discard ? amount : amount + " ") : (altAmount == null ? "" : altAmount + " ");
            compactRewards.add(colour + amountString + (discard ? "" : name));
        }
        compactOriginal.add(component);
        if (compactOriginal.size() == 3) runLater(4, GardenVisitors::sendCompact);
        return true;
    }

    private static void sendCompact() {
        if (compactVisitorName.isBlank()) return;
        if (!compactOriginal.isEmpty()) {
            MutableComponent hover = Component.empty();
            for (int i = 0; i < compactOriginal.size(); i++) {
                if (i > 0) hover.append("\n");
                hover.append(compactOriginal.get(i));
            }
            MutableComponent message = SbcItems.parseLegacy("§6§lOFFER ACCEPTED §7w/§r" + compactVisitorName + "§6§l!§r " + String.join("§7, ", compactRewards));
            message.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hover)));
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(message);
        }
        compactVisitorName = "";
        compactRewards.clear();
        compactOriginal.clear();
    }

    // ------------------------------------------------------------------------------------------------ shopping list

    /** SkyHanni's GardenVisitorShoppingList, with each item shown as its icon (Item Icons) or its name. */
    private static void updateShoppingList() {
        VisitorConfig config = config();
        if (config == null || !config.shoppingList.enabled) {
            shoppingList = EMPTY_SHOP_LIST;
            return;
        }
        VisitorConfig.ShoppingList list = config.shoppingList;
        Map<String, Integer> shopping = new LinkedHashMap<>();
        List<Visitor> newVisitors = new ArrayList<>(), knownVisitors = new ArrayList<>();
        for (Visitor visitor : visitors.values()) {
            if (visitor.status == Status.ACCEPTED || visitor.status == Status.REFUSED) continue;
            (visitor.shoppingList.isEmpty() ? newVisitors : knownVisitors).add(visitor);
            if (visitor.ignoreShoppingList()) continue;
            visitor.shoppingList.forEach((id, amount) -> shopping.merge(resolveItem(id), amount, Integer::sum));
        }
        List<ShopRow> out = new ArrayList<>();
        if (!shopping.isEmpty()) {
            double totalPrice = 0;
            out.add(null);
            for (Map.Entry<String, Integer> e : shopping.entrySet()) {
                String id = e.getKey();
                int amount = e.getValue();
                ItemStack icon = list.itemIcons ? itemIcon(id) : null;
                StringBuilder line = new StringBuilder();
                if (icon == null) line.append(" §7- ").append(itemDisplayName(id)).append(" ");
                line.append("§ex").append(String.format(Locale.US, "%,d", amount));
                if (list.showPrice) {
                    double price = price(id, amount);
                    totalPrice += price;
                    if (price > 0) line.append(" §7(§6").append(shortFormat(price)).append("§7)");
                }
                if (list.showSackCount) line.append(sackData(id, amount));
                out.add(new ShopRow(icon, SbcItems.parseLegacy(line.toString()), id));
            }
            out.set(0, textRow(totalPrice > 0 ? "§7Visitor Shopping List: §7(§6" + shortFormat(totalPrice) + "§7)" : "§7Visitor Shopping List:"));
        }
        visitorSection(out, newVisitors, "New Visitor", true, list);
        visitorSection(out, knownVisitors, "Visitor", false, list);
        shoppingList = shopList(out);
    }

    private static void visitorSection(List<ShopRow> out, List<Visitor> section, String header, boolean isNew, VisitorConfig.ShoppingList config) {
        if (section.isEmpty()) return;
        if (!out.isEmpty()) out.add(textRow(""));
        out.add(textRow("§e" + section.size() + " §7" + header + (section.size() == 1 ? "" : "s") + ":"));
        for (Visitor visitor : section) {
            if (!isNew) {
                out.add(textRow(" §7- " + (visitor.ignoreShoppingList() ? "§7§m" + visitor.name : visitor.displayName())));
                continue;
            }
            StringBuilder line = new StringBuilder(" §7- ").append(visitor.displayName());
            if (config.itemPreview) {
                GardenVisitorData data = repoVisitors.get(visitor.name);
                if (data == null) line.append(" §7(§c?§7)");
                else if (data.needItems().isEmpty()) line.append(data.unknownRewards() ? " §7(§fUnknown§7)" : " §7(§fAny§7)");
                else line.append(" §7(§f").append(String.join("§7, §f", data.needItems())).append("§7)");
            }
            out.add(textRow(line.toString()));
        }
    }

    private static ShopRow textRow(String legacy) {
        return new ShopRow(null, SbcItems.parseLegacy(legacy), null);
    }

    // Icon rows: the item drawn at 12 pixels (3/4 size) with its amount next to it.
    private static final int ICON_SIZE = 12, ICON_ROW_HEIGHT = 12;

    private static ShopList shopList(List<ShopRow> rows) {
        if (rows.isEmpty()) return EMPTY_SHOP_LIST;
        var font = Minecraft.getInstance().font;
        List<Component> texts = new ArrayList<>();
        int width = 0, height = 0;
        for (ShopRow row : rows) {
            texts.add(row.text());
            width = Math.max(width, (row.icon() == null ? 0 : ICON_SIZE + 3) + font.width(row.text()));
            height += row.icon() == null ? Sky2MHuds.LINE_HEIGHT : ICON_ROW_HEIGHT;
        }
        return new ShopList(List.copyOf(rows), List.copyOf(texts), width + Sky2MHuds.PADDING * 2, height + Sky2MHuds.PADDING * 2 - 2);
    }

    /** The live list, or in the /s2 gui editor (when there is no live list) a sample one. */
    private static ShopList shownList(boolean preview) {
        if (!preview || !shoppingList.rows().isEmpty()) return shoppingList;
        VisitorConfig config = config();
        boolean icons = config == null || config.shoppingList.itemIcons;
        String key = icons + ":" + RepoItems.itemsLoaded();
        if (previewList == null || !key.equals(previewKey)) {
            previewKey = key;
            List<ShopRow> rows = new ArrayList<>();
            rows.add(textRow("§7Visitor Shopping List: §7(§6125k§7)"));
            if (icons) {
                rows.add(new ShopRow(iconOr("ENCHANTED_HAY_BALE", Items.HAY_BLOCK), SbcItems.parseLegacy("§ex2 §7(§6110k§7)"), null));
                rows.add(new ShopRow(iconOr("ENCHANTED_POTATO", Items.POTATO), SbcItems.parseLegacy("§ex96 §7(§615k§7)"), null));
            } else {
                rows.add(textRow(" §7- §aEnchanted Hay Bale §ex2 §7(§6110k§7)"));
                rows.add(textRow(" §7- §aEnchanted Potato §ex96 §7(§615k§7)"));
            }
            rows.add(textRow(""));
            rows.add(textRow("§e1 §7New Visitor:"));
            rows.add(textRow(" §7- §9Lazy Miner §7(Enchanted Cobblestone)"));
            previewList = shopList(rows);
        }
        return previewList;
    }

    /** Draws the list at 0, 0; {@code hovered} is the row under the mouse (Click to Open Bazaar), or -1. */
    private static void drawShopList(GuiGraphicsExtractor graphics, ShopList list, boolean background, int hovered) {
        if (list.rows().isEmpty()) return;
        var font = Minecraft.getInstance().font;
        if (background) graphics.fill(0, 0, list.width(), list.height(), Sky2MTheme.background(0x80000000));
        if (hovered >= 0 && hovered < list.rows().size()) {
            int top = rowTop(list, hovered), bottom = top + rowHeight(list.rows().get(hovered));
            graphics.fill(0, top, list.width(), bottom, 0x3055FF55);
            graphics.outline(0, top, list.width(), bottom - top, 0xC055FF55);
        }
        // HUD theme (colour-blind palettes, high contrast), as on the text HUDs.
        List<Component> themed = Sky2MTheme.lines(list.texts());
        int colour = Sky2MTheme.argb(0xFFFFFFFF);
        int y = Sky2MHuds.PADDING;
        for (int i = 0; i < list.rows().size(); i++) {
            ShopRow row = list.rows().get(i);
            Component text = i < themed.size() ? themed.get(i) : row.text();
            if (row.icon() == null) {
                graphics.text(font, text, Sky2MHuds.PADDING, y, colour, true);
                y += Sky2MHuds.LINE_HEIGHT;
                continue;
            }
            graphics.pose().pushMatrix();
            graphics.pose().translate((float) Sky2MHuds.PADDING, (float) (y - 1));
            graphics.pose().scale(ICON_SIZE / 16f, ICON_SIZE / 16f);
            graphics.item(row.icon(), 0, 0);
            graphics.pose().popMatrix();
            graphics.text(font, text, Sky2MHuds.PADDING + ICON_SIZE + 3, y + 1, colour, true);
            y += ICON_ROW_HEIGHT;
        }
    }

    private static int rowHeight(ShopRow row) {
        return row.icon() == null ? Sky2MHuds.LINE_HEIGHT : ICON_ROW_HEIGHT;
    }

    /** The top of a row's hover box (one pixel above its text, where the icon starts), in the list's own coordinates. */
    private static int rowTop(ShopList list, int index) {
        int y = Sky2MHuds.PADDING;
        for (int i = 0; i < index; i++) y += rowHeight(list.rows().get(i));
        return y - 1;
    }

    /** The item's SkyBlock look from the item list, or null when it isn't known (then its name is shown instead). */
    private static ItemStack itemIcon(String id) {
        if (isNamedItem(id)) return null;
        ItemStack cached = ICONS.get(id);
        if (cached != null) return cached.isEmpty() ? null : cached;
        if (!RepoItems.itemsLoaded()) return null;
        ItemStack stack = RepoItems.itemStack(id);
        if (stack.is(Items.BARRIER)) stack = ItemStack.EMPTY;
        ICONS.put(id, stack);
        return stack.isEmpty() ? null : stack;
    }

    private static ItemStack iconOr(String id, net.minecraft.world.item.Item fallback) {
        ItemStack icon = itemIcon(id);
        return icon != null ? icon : new ItemStack(fallback);
    }

    /** "(x1,234 in sacks)" and "(Craftable!)" when your sacks have what it takes to craft the rest. */
    private static String sackData(String id, int amount) {
        long inSacks = amountInSacksOrUnknown(id);
        StringBuilder out = new StringBuilder();
        long have = Math.max(0, inSacks);
        if (inSacks >= 0) out.append(" §7(§").append(inSacks >= amount ? "a" : "e").append("x").append(String.format(Locale.US, "%,d", inSacks)).append(" §7in sacks)");
        long left = amount - have;
        if (left > 0 && canCraftFromSacks(id, left)) out.append(" §7(§aCraftable!§7)");
        return out.toString();
    }

    /** Whether the sacks hold the ingredients of {@code id}'s crafting recipe, {@code count} times over. Cached, worked out in the background. */
    private static boolean canCraftFromSacks(String id, long count) {
        if (isNamedItem(id)) return false;
        Optional<long[]> cached = superCraftCache.get(id);
        if (cached == null) {
            superCraftCache.put(id, Optional.empty());
            RepoItems.runAsync(() -> {
                Sky2MCraftHelper.Recipe recipe = Sky2MCraftHelper.recipeOf(id);
                if (recipe == null || recipe.inputs().isEmpty() || recipe.inputs().getFirst().id().contains("PEST")) return;
                superCraftCache.put(id, Optional.of(new long[]{1}));
                Minecraft.getInstance().execute(GardenVisitors::updateShoppingList);
            });
            return false;
        }
        if (cached.isEmpty()) return false;
        Sky2MCraftHelper.Recipe recipe = Sky2MCraftHelper.recipeOf(id);
        if (recipe == null) return false;
        Map<String, Long> needed = new HashMap<>();
        for (Sky2MCraftHelper.Input input : recipe.inputs()) needed.merge(input.id(), (long) input.amount(), Long::sum);
        for (Map.Entry<String, Long> e : needed.entrySet()) {
            if (amountInSacks(e.getKey()) < e.getValue() * count) return false;
        }
        return true;
    }

    /** SkyHanni's GardenVisitorSupercraft: a Supercraft button on the offer when the sacks can craft what's missing. */
    private static void checkSuperCraft(Visitor visitor) {
        superCraftItem = null;
        VisitorConfig config = config();
        if (config == null || !config.shoppingList.showSuperCraft) return;
        for (Map.Entry<String, Integer> e : visitor.shoppingList.entrySet()) {
            String id = resolveItem(e.getKey());
            long inSacks = amountInSacks(id);
            if (inSacks >= e.getValue()) continue;
            if (canCraftFromSacks(id, e.getValue() - inSacks)) superCraftItem = id;
        }
    }

    private static boolean showShoppingList() {
        VisitorConfig config = config();
        if (config == null) return false;
        String area = Sky2MLocation.area(), location = Sky2MLocation.location();
        if ("Hub".equals(area)) {
            if (config.shoppingList.inBazaarAlley && location != null && location.contains("Bazaar Alley")) return true;
            if (config.shoppingList.inFarmingAreas && location != null && location.trim().equals("Farm")) return true;
        }
        if (config.shoppingList.inFarmingAreas && "The Farming Islands".equals(area)) return true;
        return Sky2MLocation.inGarden() && (onBarnPlot() || !config.shoppingList.onlyWhenClose);
    }

    /** The shopping list shows in the visitor's menu, the Bazaar, your own inventory and number signs. */
    private static boolean showInScreen(Screen screen) {
        if (inInventory) return true;
        if (screen instanceof InventoryScreen || screen instanceof AbstractSignEditScreen) return true;
        String title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        return title.startsWith("Bazaar") || title.contains("Bazaar ➜") || title.startsWith("How many") || title.startsWith("Confirm");
    }

    /** Whether the shopping list is drawn over this screen. */
    private static boolean shopListInScreen(Screen screen, VisitorConfig config) {
        return config.shoppingList.enabled && showShoppingList() && !shoppingList.rows().isEmpty() && showInScreen(screen)
            && !com.epic60869.sky2m.features.misc.storage.StorageOverlay.hidesOtherHuds();
    }

    /** The clickable item row under the mouse in the shopping list drawn over a menu, or -1. */
    private static int hoveredShopRow(ShopList list, Sky2MHuds.Placement p, double mouseX, double mouseY) {
        VisitorConfig config = config();
        if (config == null || !config.shoppingList.clickToOpenBazaar || p.scale <= 0) return -1;
        int x = Sky2MHuds.mapX(p.x, Math.round(list.width() * p.scale)), y = Sky2MHuds.mapY(p.y, Math.round(list.height() * p.scale));
        double localX = (mouseX - x) / p.scale, localY = (mouseY - y) / p.scale;
        if (localX < 0 || localX >= list.width() || localY < 0 || localY >= list.height()) return -1;
        int top = Sky2MHuds.PADDING - 1;
        for (int i = 0; i < list.rows().size(); i++) {
            ShopRow row = list.rows().get(i);
            int bottom = top + rowHeight(row);
            if (localY >= top && localY < bottom) return onBazaar(row.id()) ? i : -1;
            top = bottom;
        }
        return -1;
    }

    /** Whether an item can be searched for on the Bazaar: on it, or the Bazaar prices aren't known (then the search is tried). */
    private static boolean onBazaar(String id) {
        if (id == null) return false;
        if (isNamedItem(id)) return true;
        var bazaar = com.epic60869.sky2m.price.PriceService.bazaar();
        if (bazaar.buy().isEmpty() && bazaar.sell().isEmpty()) return true;
        String key = priceKey(id);
        return bazaar.buy().containsKey(key) || bazaar.sell().containsKey(key);
    }

    /** The item's plain name, as typed into the Bazaar search. */
    private static String bazaarName(String id) {
        return ChatFormatting.stripFormatting(itemDisplayName(id)).trim();
    }

    /** Click to Open Bazaar: a click on a shopping list item runs /bz with its name. @return true when the click was used. */
    private static boolean clickShopList(Screen screen, double mouseX, double mouseY) {
        VisitorConfig config = config();
        if (config == null || !config.shoppingList.clickToOpenBazaar || !shopListInScreen(screen, config)) return false;
        ShopList list = shoppingList;
        int row = hoveredShopRow(list, Sky2MHuds.placement(SHOPPING_HUD), mouseX, mouseY);
        if (row < 0) return false;
        String name = bazaarName(list.rows().get(row).id());
        if (name.isEmpty()) return false;
        startBazaarSearch(name);
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) connection.sendCommand("bz " + name);
        return true;
    }

    private static void startBazaarSearch(String name) {
        bazaarSearchName = name;
        bazaarSearchSince = System.currentTimeMillis();
        bazaarSearchSeen = false;
    }

    private static boolean isBazaarTitle(String title) {
        return title.startsWith("Bazaar") || title.contains("➜");
    }

    /** Keeps the searched item highlighted while the Bazaar menus are open; clears it once they close (or none opened in 10s). */
    private static void tickBazaarSearch(Screen screen) {
        if (bazaarSearchName == null) return;
        if (screen instanceof AbstractContainerScreen<?> && isBazaarTitle(ChatFormatting.stripFormatting(screen.getTitle().getString()))) {
            bazaarSearchSeen = true;
        } else if (bazaarSearchSeen ? screen == null : System.currentTimeMillis() - bazaarSearchSince > 10_000) {
            bazaarSearchName = null;
            bazaarSearchSeen = false;
        }
    }

    /** Whether this slot holds the item searched for from the shopping list, in a Bazaar menu. */
    private static boolean isBazaarSearchSlot(Screen screen, Slot slot) {
        String name = bazaarSearchName;
        if (name == null || slot == null || !slot.hasItem() || slot.container instanceof net.minecraft.world.entity.player.Inventory) return false;
        if (!(screen instanceof AbstractContainerScreen<?>) || !isBazaarTitle(ChatFormatting.stripFormatting(screen.getTitle().getString()))) return false;
        String item = ChatFormatting.stripFormatting(slot.getItem().getHoverName().getString());
        return item != null && item.trim().equalsIgnoreCase(name);
    }

    /** The searched item's green highlight behind it (Sky2MSlotBackgroundMixin). */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        if (bazaarSearchName == null || !isBazaarSearchSlot(Minecraft.getInstance().gui.screen(), slot)) return;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x9055FF55);
        graphics.outline(slot.x - 1, slot.y - 1, 18, 18, 0xFF55FF55);
    }

    private static void afterScreen(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (com.epic60869.sky2m.features.misc.storage.StorageOverlay.hidesOtherHuds()) return;
        VisitorConfig config = config();
        if (config == null) return;
        ShopList list = shoppingList;
        if (shopListInScreen(screen, config)) {
            Sky2MHuds.Placement p = Sky2MHuds.placement(SHOPPING_HUD);
            int x = Sky2MHuds.mapX(p.x, Math.round(list.width() * p.scale)), y = Sky2MHuds.mapY(p.y, Math.round(list.height() * p.scale));
            int hovered = hoveredShopRow(list, p, mouseX, mouseY);
            graphics.pose().pushMatrix();
            graphics.pose().translate((float) x, (float) y);
            graphics.pose().scale(p.scale, p.scale);
            try {
                drawShopList(graphics, list, p.background, hovered);
            } finally {
                graphics.pose().popMatrix();
            }
            if (hovered >= 0) {
                String id = list.rows().get(hovered).id();
                graphics.setTooltipForNextFrame(Minecraft.getInstance().font,
                    List.of(SbcItems.parseLegacy(itemDisplayName(id)), SbcItems.parseLegacy("§eClick to search the Bazaar")),
                    Optional.empty(), mouseX, mouseY);
            }
        }
        if (!inInventory || openVisitor == null || !(screen instanceof AbstractContainerScreen<?> container)) return;
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) container;
        List<Slot> slots = container.getMenu().slots;
        if (slots.size() <= REFUSE_SLOT) return;
        // VisitorRewardWarning: the blocked button greyed out, the other outlined.
        BlockReason reason = openVisitor.blockReason;
        if (reason != null) {
            Slot accept = slots.get(ACCEPT_SLOT), refuse = slots.get(REFUSE_SLOT);
            Slot background = reason.blockRefusing ? refuse : accept, outline = reason.blockRefusing ? accept : refuse;
            int outlineColour = reason.blockRefusing ? 0xC855FF55 : 0xC8FF5555;
            if (!keyHeld(config.rewardWarning.bypassKey)) {
                int alpha = Math.max(0, Math.min(255, config.rewardWarning.transparency));
                fillSlot(graphics, accessor, background, (alpha << 24) | 0x555555);
            }
            if (config.rewardWarning.optionOutline) {
                graphics.outline(accessor.getX() + outline.x - 1, accessor.getY() + outline.y - 1, 18, 18, outlineColour);
            }
        }
        if (superCraftItem != null && config.shoppingList.showSuperCraft) {
            Slot slot = slots.get(SUPERCRAFT_SLOT);
            fillSlot(graphics, accessor, slot, 0xFF262626);
            graphics.item(new ItemStack(Items.GOLDEN_PICKAXE), accessor.getX() + slot.x, accessor.getY() + slot.y);
            graphics.outline(accessor.getX() + slot.x - 1, accessor.getY() + slot.y - 1, 18, 18, 0xFF55FFFF);
        }
    }

    private static void fillSlot(GuiGraphicsExtractor graphics, AbstractContainerScreenAccessor accessor, Slot slot, int colour) {
        int x = accessor.getX() + slot.x, y = accessor.getY() + slot.y;
        graphics.fill(x, y, x + 16, y + 16, colour);
    }

    // ------------------------------------------------------------------------------------------------ charmed + statistics

    private static void checkCharmed(AbstractContainerScreen<?> screen, Visitor visitor) {
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= VINYL_SLOT) return;
        ItemStack vinyl = slots.get(VINYL_SLOT).getItem();
        boolean charmed = !vinyl.isEmpty() && CHARMED.matcher(ChatFormatting.stripFormatting(vinyl.getHoverName().getString())).matches();
        Set<String> list = storage().charmedVisitors;
        boolean changed = charmed ? list.add(visitor.displayName()) : list.remove(visitor.displayName());
        if (changed) {
            markDirty();
            updateCharmed();
        }
    }

    private static void updateCharmed() {
        Set<String> charmed = storage().charmedVisitors;
        if (charmed.isEmpty()) {
            charmedLines = List.of();
            return;
        }
        List<Component> out = new ArrayList<>();
        out.add(SbcItems.parseLegacy("§dCharmed Visitors §7(" + charmed.size() + "):"));
        for (String name : charmed) out.add(SbcItems.parseLegacy(" §7- " + name));
        charmedLines = out;
    }

    /** SkyHanni's GardenVisitorDropStatistics display. */
    private static void updateStats() {
        VisitorConfig config = config();
        if (config == null) return;
        updateCharmed();
        Drops drops = storage().drops;
        boolean numbersFirst = config.dropsStatistics.displayNumbersFirst;
        List<Component> out = new ArrayList<>();
        for (VisitorConfig.DropsStatistics.StatsEntry entry : config.dropsStatistics.textFormat) {
            String line = switch (entry) {
                case TITLE -> "§e§lVisitor Statistics";
                case SPACER_1, SPACER_2 -> "";
                case TOTAL_VISITORS -> format(drops.acceptedVisitors + drops.deniedVisitors, "Total", "§e", "", numbersFirst);
                case ACCEPTED -> format(drops.acceptedVisitors, "Accepted", "§2", "", numbersFirst);
                case DENIED -> format(drops.deniedVisitors, "Denied", "§c", "", numbersFirst);
                case COPPER -> format(drops.copper, "Copper", "§c", "", numbersFirst);
                case FARMING_EXP -> formatShort(drops.farmingExp, "Farming EXP", "§3", "§7", numbersFirst);
                case GARDEN_EXP -> format(drops.gardenExp, "Garden EXP", "§2", "§7", numbersFirst);
                case COINS_SPENT -> formatShort(drops.coinsSpent, "Coins Spent", "§6", "", numbersFirst);
                case BITS -> format(drops.bits, "Bits", "§b", "§b", numbersFirst);
                case MITHRIL_POWDER -> format(drops.mithrilPowder, "Mithril Powder", "§2", "§2", numbersFirst);
                case GEMSTONE_POWDER -> format(drops.gemstonePowder, "Gemstone Powder", "§d", "§d", numbersFirst);
                case ECTOPLASM -> format(drops.ectoplasm, "Ectoplasm", "§5", "§5", numbersFirst);
                case VISITORS_BY_RARITY -> {
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < RARITIES.length; i++) {
                        if (i > 0) b.append("§f-");
                        b.append(RARITY_COLOURS[i].toString()).append(String.format(Locale.US, "%,d", drops.acceptedRarities.getOrDefault(RARITIES[i], 0L)));
                    }
                    yield b.toString();
                }
                default -> {
                    VisitorReward reward = rewardFor(entry);
                    yield reward == null ? null : format(drops.rewardsCount.getOrDefault(reward.name(), 0L), reward.displayName, "§b", "§b", numbersFirst);
                }
            };
            if (line != null) out.add(SbcItems.parseLegacy(line));
        }
        statsLines = out;
    }

    private static VisitorReward rewardFor(VisitorConfig.DropsStatistics.StatsEntry entry) {
        for (VisitorReward reward : VisitorReward.values()) if (reward.statsEntry() == entry) return reward;
        return null;
    }

    private static String format(long amount, String name, String colour, String amountColour, boolean numbersFirst) {
        String number = String.format(Locale.US, "%,d", amount);
        return numbersFirst ? colour + number + " " + name : colour + name + ": " + amountColour + number;
    }

    private static String formatShort(long amount, String name, String colour, String amountColour, boolean numbersFirst) {
        String number = shortFormat(amount);
        return numbersFirst ? colour + number + " " + name : colour + name + ": " + amountColour + number;
    }

    /** The Reset Statistics button (SkyHanni's /shresetvisitordrops). */
    public static void resetDropStatistics() {
        storage().drops = new Drops();
        markDirty();
        updateStats();
        Sky2MAlerts.chat(Component.literal("Visitor Drop Statistics reset!").withStyle(ChatFormatting.YELLOW));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The Barn plot: the middle 96x96 plot of the Garden. */
    static boolean onBarnPlot() {
        Player player = Minecraft.getInstance().player;
        return player != null && Sky2MLocation.inGarden() && Math.abs(player.getX()) <= 48 && Math.abs(player.getZ()) <= 48;
    }

    private static boolean isBingoProfile() {
        for (String line : Sky2MLocation.scoreboard()) if (line.contains("Ⓑ")) return true;
        return false;
    }

    private static boolean keyHeld(int key) {
        if (key <= 0) return false;
        return InputConstants.isKeyDown(key);
    }

    private static String keyName(int key) {
        return com.epic60869.sky2m.custom.util.Input.bindName(key);
    }

    private static void clickSlot(AbstractContainerScreen<?> screen, int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    private static long parseShortNumber(String text) {
        String t = text.replace(",", "").trim().toLowerCase(Locale.ROOT);
        double multiplier = 1;
        if (t.endsWith("k")) multiplier = 1_000;
        else if (t.endsWith("m")) multiplier = 1_000_000;
        else if (t.endsWith("b")) multiplier = 1_000_000_000;
        if (multiplier != 1) t = t.substring(0, t.length() - 1);
        try {
            return Math.round(Double.parseDouble(t) * multiplier);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String shortFormat(double value) {
        String[] suffixes = {"", "k", "M", "B", "T"};
        int index = 0;
        double v = value;
        while (Math.abs(v) >= 1000 && index < suffixes.length - 1) {
            v /= 1000;
            index++;
        }
        if (index == 0) return String.valueOf(Math.round(v));
        String number = Math.abs(v) >= 100 ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
        if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
        return number + suffixes[index];
    }

    /** For the game tests: as if this item had just been clicked in the shopping list (without running /bz). */
    public static void testBazaarSearch(String itemName) {
        startBazaarSearch(itemName);
    }

    /** For the game tests: the index of the slot highlighted by Click to Open Bazaar in the open menu, or -1. */
    public static int testBazaarHighlightedSlot() {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (bazaarSearchName == null || !(screen instanceof AbstractContainerScreen<?> container)) return -1;
        for (Slot slot : container.getMenu().slots) {
            if (isBazaarSearchSlot(screen, slot)) return slot.index;
        }
        return -1;
    }

    /** For the game tests: whether the shopping list item row with this id can be clicked to search the Bazaar. */
    public static boolean testShopRowClickable(String id) {
        return onBazaar(id);
    }

    /**
     * For the game tests: a visitor (not from the tab list) wanting these items, then the shopping list's rows as
     * "icon|text" ("-|text" for a row without an icon). The next tab list read in the Garden removes the visitor.
     */
    public static List<String> testShoppingList(String visitorName, Map<String, Integer> items) {
        Visitor visitor = new Visitor(visitorName, "§a");
        visitor.status = Status.WAITING;
        visitor.shoppingList.putAll(items);
        visitors.put(visitorName, visitor);
        updateShoppingList();
        List<String> out = new ArrayList<>();
        for (ShopRow row : shoppingList.rows()) out.add((row.icon() == null ? "-" : "icon") + "|" + row.text().getString());
        return out;
    }

    /** All the known visitors (for the debug command). */
    static List<String> debugLines() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Visitor visitor : visitors.values()) {
            if (!seen.add(visitor.name)) continue;
            out.add(visitor.displayName() + " §7" + visitor.status + " " + visitor.shoppingList);
        }
        return out;
    }
}
