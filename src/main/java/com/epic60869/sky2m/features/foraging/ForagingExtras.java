package com.epic60869.sky2m.features.foraging;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.Command;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foraging extras ported from SkyHanni (hannibal002/SkyHanni, LGPL-2.1): Foraging Tracker (trees felled, tree gift
 * rewards and per hour), Tree Progress Display, Clean Tree View (ClearTreeLogs), Foraging Tutorial Quest waypoints,
 * Birries and Hideonleaf highlighters, Mute Phantom, Starlyn sister (Agatha and Miria) coupon profit, and the Honeyhive
 * reminder. Display only: nothing here acts for you.
 */
public final class ForagingExtras {
    // Tracker
    private static final Pattern GIFT_HEADER = Pattern.compile("^TREE GIFT$");
    private static final Pattern CUT = Pattern.compile("You helped cut ([\\d.]+)% of the (.+) Tree\\.");
    private static final Pattern REWARDS = Pattern.compile("\\+([\\d,]+) rewards gained!.*");
    private static final Pattern HOVER_STAT = Pattern.compile("^(Foraging Experience|HOTF Experience|Forest Whispers)\\D*([\\d,]+)");
    private static boolean inGift;
    private static boolean sawRewards;
    private static int trees;
    private static double wholeTrees;
    private static int gifts;
    private static long rewards;
    private static long foragingXp;
    private static long hotfXp;
    private static long whispers;
    private static long firstGift;
    private static long lastGift;
    private static long activeMs;

    // Tree progress
    private static final Pattern TREE_PROGRESS = Pattern.compile("(\\w+) TREE (\\d+)%");
    private static Component progress;

    // Clean tree view
    private static final Set<BlockState> TREE_BLOCKS = Set.of(Blocks.STRIPPED_SPRUCE_WOOD.defaultBlockState(), Blocks.MANGROVE_WOOD.defaultBlockState(),
        Blocks.MANGROVE_LEAVES.defaultBlockState(), Blocks.AZALEA_LEAVES.defaultBlockState(), Blocks.STRIPPED_BIRCH_WOOD.defaultBlockState(),
        Blocks.STRIPPED_MANGROVE_WOOD.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState());

    // Quest helper
    private enum Quest {
        FIRST("Foraging Tutorial", "Lumber Jack", new Vec3(-123.5, 74, -30)),
        SECOND("Into the Woods", "Charlie", new Vec3(-275.9, 80, -17.1)),
        THIRD("A Helping Hand", "Kelly", new Vec3(-350.8, 94, 31.7)),
        FOURTH("The Campfire Cult", "Ryan", new Vec3(-362.7, 102, -90.5)),
        FIFTH("The Rebuild", "Melody", new Vec3(-412.3, 109, 70.2));
        final String quest, npc;
        final Vec3 pos;
        Quest(String quest, String npc, Vec3 pos) {
            this.quest = quest;
            this.npc = npc;
            this.pos = pos;
        }
    }
    private record Portal(String message, Vec3 pos) {}
    private static final List<Portal> PORTALS = List.of(
        new Portal("Can you maybe look for her in the Spruce Woods?", new Vec3(-312.1, 81, -9)),
        new Portal("Cult Meeting there RIGHT NOW!", new Vec3(-361.2, 90, -14.8)),
        new Portal("You completed each Trial of Fire! Congratulations!", new Vec3(-397.3, 98, -37.5)),
        new Portal("Talk to me again if you ever want to give my Harp a try!", new Vec3(-436.4, 110.5, -14.4)));
    private static final Pattern QUEST_REQUIRED = Pattern.compile("You must complete the (.+) Quest to use this!");
    private static Vec3 questTarget;
    private static String questLabel = "";

    // Highlights
    private static final Map<Integer, Integer> glow = new HashMap<>();

    // Agatha / Miria coupon profit
    private static final Pattern COST_LINE = Pattern.compile("^(?:([\\d,]+)x )?(.+?)(?: x([\\d,]+))?$");

    // Honeyhive
    private static long honeyhiveReadyAt;
    private static boolean honeyhiveReminded;

    private static int ticks;

    private ForagingExtras() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.foraging == null ? null : c.foraging.extras;
    }

    public static void init() {
        loadHoney();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            progress = null;
            glow.clear();
            inGift = false;
        });
        ClientTickEvents.END_CLIENT_TICK.register(ForagingExtras::tick);
        Sky2MChat.onChat(ForagingExtras::onChat);
        EntityGlow.register(entity -> glow.isEmpty() ? -1 : glow.getOrDefault(entity.getId(), -1));
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> couponTooltip(stack, lines));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
            dispatcher.register(ClientCommands.literal("s2foragingreset").executes(ctx -> {
                trees = 0;
                wholeTrees = 0;
                gifts = 0;
                rewards = 0;
                foragingXp = 0;
                hotfXp = 0;
                whispers = 0;
                firstGift = 0;
                activeMs = 0;
                Sky2MAlerts.chat(Component.literal("Reset the Foraging Tracker.").withStyle(ChatFormatting.GREEN));
                return Command.SINGLE_SUCCESS;
            })));

        Sky2MHuds.setting("foraging_tracker", () -> config() != null && config().tracker);
        Sky2MHuds.register("foraging_tracker", "Foraging Tracker",
            () -> config() != null && config().tracker && trees > 0 && (holdingAxe() || System.currentTimeMillis() - lastGift < 60_000),
            ForagingExtras::trackerLines,
            List.of(SbcItems.parseLegacy("§a§lForaging Tracker"), SbcItems.parseLegacy("§eForaging Experience: §312,400"),
                SbcItems.parseLegacy("§eTrees Felled: 42"), SbcItems.parseLegacy("§eGifts: 40 §7(110/h)")),
            8, 430);
        Sky2MHuds.setting("foraging_tree_progress", () -> config() != null && config().treeProgress);
        Sky2MHuds.register("foraging_tree_progress", "Tree Progress",
            () -> config() != null && config().treeProgress && progress != null && (!config().treeProgressOnlyAxe || holdingAxe()),
            () -> progress == null ? List.of() : List.of(progress),
            List.of(SbcItems.parseLegacy("§aFIG TREE 88%")),
            200, 120);

        Sky2MWorldRender.register(collector -> {
            Config c = config();
            if (c == null || !c.questHelper || questTarget == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.player.position().distanceTo(questTarget) < 4) {
                questTarget = null;
                return;
            }
            collector.submitFilledBoxWithBeaconBeam(BlockPos.containing(questTarget), new float[]{1f, 0.85f, 0.2f}, 0.5f, true);
            double distance = mc.gameRenderer.mainCamera().position().distanceTo(questTarget);
            float scale = (float) Math.max(1, distance / 7);
            collector.submitText(Component.literal(questLabel).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                questTarget.add(0, 2.3 + 0.3 * scale, 0), scale, true);
        });
    }

    private static boolean holdingAxe() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.getMainHandItem().is(ItemTags.AXES);
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(Sky2MChat.Message message) {
        Config c = config();
        if (c == null || !Sky2MLocation.onSkyblock()) return;
        String text = message.text().trim();
        long now = System.currentTimeMillis();

        if (c.tracker) {
            if (GIFT_HEADER.matcher(text).matches()) {
                inGift = true;
                sawRewards = false;
            } else if (inGift) {
                Matcher m = CUT.matcher(text);
                if (m.find()) {
                    trees++;
                    try {
                        wholeTrees += Double.parseDouble(m.group(1)) / 100.0;
                    } catch (NumberFormatException ignored) {}
                } else if ((m = REWARDS.matcher(text)).matches()) {
                    sawRewards = true;
                    rewards += parse(m.group(1));
                    readHover(message.component());
                } else if (text.startsWith("▬") && sawRewards) {
                    finishGift(now);
                }
            }
        }
        if (c.honeyhiveReminder) {
            if (text.startsWith("You stick your hand into the honeyhive and feel around")) {
                honeyhiveReadyAt = now + (long) (c.honeyhiveMinutes * 60_000L);
                honeyhiveReminded = false;
                saveHoney();
            } else if (c.queenBeeTitle && text.startsWith("QUEEN BEE! The Honeyhive instantly refilled with loot!")) {
                Sky2MAlerts.title(Component.literal("Honeyhive Instantly Refilled!").withStyle(ChatFormatting.GOLD), null);
            }
        }
        if (c.questHelper) onQuestMessage(text);
    }

    private static void finishGift(long now) {
        inGift = false;
        gifts++;
        if (firstGift == 0) firstGift = now;
        if (lastGift > 0) activeMs += Math.min(now - lastGift, 2 * 60_000L);
        lastGift = now;
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void readHover(Component component) {
        List<String> lines = new ArrayList<>();
        collectHover(component, lines);
        for (String line : lines) {
            for (String part : line.split("\n")) {
                Matcher m = HOVER_STAT.matcher(Sky2MLocation.strip(part).trim());
                if (!m.find()) continue;
                long amount = parse(m.group(2));
                switch (m.group(1)) {
                    case "Foraging Experience" -> foragingXp += amount;
                    case "HOTF Experience" -> hotfXp += amount;
                    default -> whispers += amount;
                }
            }
        }
    }

    private static void collectHover(Component component, List<String> out) {
        HoverEvent hover = component.getStyle().getHoverEvent();
        if (hover instanceof HoverEvent.ShowText show) out.add(show.value().getString());
        for (Component sibling : component.getSiblings()) collectHover(sibling, out);
    }

    private static List<Component> trackerLines() {
        List<Component> lines = new ArrayList<>();
        lines.add(SbcItems.parseLegacy("§a§lForaging Tracker"));
        if (foragingXp > 0) lines.add(SbcItems.parseLegacy("§eForaging Experience: §3" + n(foragingXp)));
        if (hotfXp > 0) lines.add(SbcItems.parseLegacy("§eHOTF Experience: §a" + n(hotfXp)));
        if (whispers > 0) lines.add(SbcItems.parseLegacy("§eForest Whispers: §b" + n(whispers)));
        lines.add(SbcItems.parseLegacy("§eTrees Felled: " + n(trees)));
        Config c = config();
        if (c != null && c.trackerWholeTrees && wholeTrees > 0) lines.add(SbcItems.parseLegacy("§eWhole Trees Felled: " + String.format(Locale.US, "%.1f", wholeTrees)));
        String perHour = activeMs > 60_000 ? String.format(Locale.US, " §7(%,d/h)", Math.round(gifts * 3_600_000.0 / activeMs)) : "";
        lines.add(SbcItems.parseLegacy("§eGifts: " + n(gifts) + perHour));
        lines.add(SbcItems.parseLegacy("§eRewards: " + n(rewards)));
        return lines;
    }

    private static String n(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    // ------------------------------------------------------------------------------------------------ quest helper

    private static void onQuestMessage(String text) {
        Matcher m = QUEST_REQUIRED.matcher(text);
        if (m.find()) {
            for (Quest quest : Quest.values()) {
                if (quest.quest.equals(m.group(1))) {
                    questTarget = quest.pos;
                    questLabel = "Start " + quest.quest + ": " + quest.npc;
                    Sky2MAlerts.chat(Component.literal("Go to " + quest.npc + " and start the " + quest.quest + " quest!").withStyle(ChatFormatting.YELLOW));
                }
            }
            return;
        }
        for (Portal portal : PORTALS) {
            if (text.endsWith(portal.message())) {
                questTarget = portal.pos();
                questLabel = "Next Quest";
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        Player player = mc.player;
        ClientLevel level = mc.level;
        Config c = config();
        if (c == null || player == null || level == null || !Compat.isOnSkyblock()) {
            if (!glow.isEmpty()) glow.clear();
            return;
        }
        ticks++;
        if (ticks % 5 == 0 && c.treeProgress) tickProgress(player, level, c);
        else if (!c.treeProgress && progress != null) progress = null;
        if (ticks % 10 == 0) tickHighlights(player, level, c);
        if (ticks % 40 == 0 && c.honeyhiveReminder && honeyhiveReadyAt > 0 && !honeyhiveReminded && System.currentTimeMillis() >= honeyhiveReadyAt) {
            honeyhiveReminded = true;
            honeyhiveReadyAt = 0;
            saveHoney();
            Sky2MAlerts.title(Component.literal("Honeyhives are ready!").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                Component.literal("Collect them on the Torrhus Canyon").withStyle(ChatFormatting.YELLOW));
            Sky2MAlerts.chat(Component.literal("Honeyhives are ready to be collected on the Torrhus Canyon.").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void tickProgress(Player player, ClientLevel level, Config c) {
        ArmorStand best = null;
        double bestDistance = Double.MAX_VALUE;
        Matcher bestMatch = null;
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(24), Entity::hasCustomName)) {
            Matcher m = TREE_PROGRESS.matcher(Sky2MLocation.strip(stand.getCustomName().getString()));
            if (!m.matches()) continue;
            double d = stand.distanceToSqr(player);
            if (d < bestDistance) {
                bestDistance = d;
                best = stand;
                bestMatch = m;
            }
        }
        if (best == null) {
            progress = null;
            return;
        }
        if (c.treeProgressCompact) {
            String type = bestMatch.group(1);
            type = type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT);
            progress = Component.empty().append(Component.literal(type + " ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(bestMatch.group(2) + "%").withStyle(ChatFormatting.YELLOW));
        } else {
            progress = best.getCustomName();
        }
    }

    private static void tickHighlights(Player player, ClientLevel level, Config c) {
        if (!c.birriesHighlight && !c.hideonleafHighlight) {
            if (!glow.isEmpty()) glow.clear();
            return;
        }
        Map<Integer, Integer> found = new HashMap<>();
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(22), Entity::hasCustomName)) {
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            int colour;
            double range;
            if (c.birriesHighlight && name.contains("Birries")) {
                colour = rgb(c.birriesColour, 0xFFFF55);
                range = 10;
            } else if (c.hideonleafHighlight && name.contains("Hideonleaf")) {
                colour = rgb(c.hideonleafColour, 0x55FF55);
                range = 20;
            } else continue;
            if (stand.distanceTo(player) > range) continue;
            LivingEntity mob = null;
            double mobDistance = Double.MAX_VALUE;
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, stand.getBoundingBox().inflate(1.2, 3, 1.2),
                e -> !(e instanceof ArmorStand) && e != player)) {
                double d = other.distanceToSqr(stand);
                if (d < mobDistance) {
                    mobDistance = d;
                    mob = other;
                }
            }
            if (mob != null) found.put(mob.getId(), colour);
        }
        glow.clear();
        glow.putAll(found);
    }

    private static int rgb(String value, int fallback) {
        try {
            return ChromaColours.parse(value).getEffectiveColourRGB() & 0xFFFFFF;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------------------------------------ clean tree view

    /** Floating tree blocks of Galatea's custom trees aren't drawn (SkyHanni's Clean Tree View). */
    public static boolean hideEntity(Entity entity) {
        if (!(entity instanceof Display.BlockDisplay display)) return false;
        Config c = config();
        if (c == null || !(c.hideTreeBlocks || c.hideRuneEffects) || !Sky2MLocation.onSkyblock()) return false;
        if (!TREE_BLOCKS.contains(display.getBlockState())) return false;
        if (c.hideTreeBlocks && c.hideRuneEffects) return true;
        Boolean floating = floating(display);
        if (floating == null) return false;
        return floating ? c.hideTreeBlocks : c.hideRuneEffects;
    }

    private static Boolean floating(Display display) {
        if (Math.abs(display.getLookAngle().y) > 1e-6) return false;
        var state = display.renderState();
        if (state == null) return null;
        var scale = state.transformation().get(0f).scale();
        if (scale.x() != scale.y() || scale.x() != scale.z()) return false;
        return scale.x() >= 0.4f && scale.x() <= 1.0f;
    }

    /** Phantom sounds on Galatea are muted. */
    public static boolean muteSound(String name) {
        Config c = config();
        return c != null && c.mutePhantoms && name.contains("entity.phantom.") && Sky2MLocation.areaIs("Galatea");
    }

    // ------------------------------------------------------------------------------------------------ coupon profit

    private static void couponTooltip(ItemStack stack, List<Component> lines) {
        Config c = config();
        if (c == null || !c.couponProfit || !Sky2MLocation.onSkyblock()) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (screen == null) return;
        String title = Sky2MLocation.strip(screen.getTitle().getString());
        String coupon;
        if (title.equals("Agatha's Shop")) coupon = "AGATHA_COUPON";
        else if (title.equals("Miria's Shop")) coupon = "MIRIA_COUPON";
        else return;
        if (lines.stream().anyMatch(l -> l.getString().contains("Profit per coupon"))) return;
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return;
        boolean inCost = false;
        double cost = 0;
        long couponAmount = 0;
        boolean priced = true;
        List<String> costLines = new ArrayList<>();
        for (Component line : lore.lines()) {
            String text = Sky2MLocation.strip(line.getString()).trim();
            if (text.equals("Cost")) {
                inCost = true;
                continue;
            }
            if (!inCost) continue;
            if (text.isEmpty()) break;
            Matcher m = COST_LINE.matcher(text);
            if (!m.matches()) continue;
            long amount = parse(m.group(1) != null ? m.group(1) : m.group(3) != null ? m.group(3) : "1");
            String id = text.contains("Coupon") ? coupon : m.group(2).trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("'", "");
            double price = Sky2MPriceTooltip.costPrice(id);
            if (price <= 0 && !id.equals(coupon)) {
                priced = false;
                continue;
            }
            if (id.equals(coupon)) couponAmount += amount;
            else cost += price * amount;
            costLines.add(text);
        }
        if (costLines.isEmpty() || !priced) return;
        String id = Sky2MPriceTooltip.marketId(stack);
        double sell = id.isEmpty() ? 0 : Sky2MPriceTooltip.unitPrice(id);
        if (sell <= 0) return;
        double profit = sell - cost;
        lines.add(Component.empty());
        lines.add(Component.literal("Sell price: ").withStyle(ChatFormatting.GRAY).append(Component.literal(coins(sell)).withStyle(ChatFormatting.GOLD)));
        lines.add(Component.literal("Item cost: ").withStyle(ChatFormatting.GRAY).append(Component.literal(coins(cost)).withStyle(ChatFormatting.GOLD)));
        lines.add(Component.literal("Profit: ").withStyle(ChatFormatting.GRAY).append(Component.literal(coins(profit)).withStyle(profit >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED)));
        if (couponAmount > 0) {
            lines.add(Component.literal("Profit per coupon: ").withStyle(ChatFormatting.GRAY).append(Component.literal(coins(profit / couponAmount)).withStyle(ChatFormatting.GOLD)));
        }
    }

    private static String coins(double value) {
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        if (abs >= 1_000_000) return sign + String.format(Locale.US, "%.2fM", abs / 1_000_000);
        if (abs >= 1_000) return sign + String.format(Locale.US, "%.1fk", abs / 1_000);
        return sign + String.format(Locale.US, "%.0f", abs);
    }

    // ------------------------------------------------------------------------------------------------ honeyhive storage

    private static Path honeyFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("foraging-extras.json");
    }

    private static void loadHoney() {
        try {
            Path file = honeyFile();
            if (!Files.exists(file)) return;
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (json.has("honeyhiveReadyAt")) honeyhiveReadyAt = json.get("honeyhiveReadyAt").getAsLong();
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the foraging extras: " + e.getMessage());
        }
    }

    private static void saveHoney() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("honeyhiveReadyAt", honeyhiveReadyAt);
            Files.createDirectories(honeyFile().getParent());
            Files.writeString(honeyFile(), json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the foraging extras: " + e.getMessage());
        }
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Foraging Tracker", desc = "HUD for the tree gifts you collect: trees felled, gifts per hour, rewards, Foraging and HOTF experience and Forest Whispers (SkyHanni). Item profit is in the Foraging profit tracker. Reset with /s2foragingreset. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean tracker = false;

        @Expose
        @ConfigOption(name = "Tracker: Whole Trees", desc = "Also show how many whole trees you felled (your share of each tree added up).")
        @ConfigEditorBoolean
        public boolean trackerWholeTrees = true;

        @Expose
        @ConfigOption(name = "Tree Progress", desc = "HUD with the progress of the tree you are cutting (SkyHanni). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean treeProgress = false;

        @Expose
        @ConfigOption(name = "Tree Progress: Compact", desc = "Shorter tree progress text, like \"Fig 88%\".")
        @ConfigEditorBoolean
        public boolean treeProgressCompact = false;

        @Expose
        @ConfigOption(name = "Tree Progress: Only With Axe", desc = "Only show the tree progress while holding an axe.")
        @ConfigEditorBoolean
        public boolean treeProgressOnlyAxe = true;

        @Expose
        @ConfigOption(name = "Clean Tree View: Hide Tree Blocks", desc = "Hides the floating blocks that fly off Galatea trees while you cut them (SkyHanni).")
        @ConfigEditorBoolean
        public boolean hideTreeBlocks = false;

        @Expose
        @ConfigOption(name = "Clean Tree View: Hide Rune Effects", desc = "Hides the tree-shaped block effects of axe runes on Galatea (SkyHanni).")
        @ConfigEditorBoolean
        public boolean hideRuneEffects = false;

        @Expose
        @ConfigOption(name = "Tutorial Quest Helper", desc = "Waypoints for the Foraging Tutorial quest line: to the NPC of the quest you are asked to start, and to the portal after a quest ends (SkyHanni). Covers the first five quests.")
        @ConfigEditorBoolean
        public boolean questHelper = false;

        @Expose
        @ConfigOption(name = "Highlight Birries", desc = "Glows Birries near you on Galatea (SkyHanni).")
        @ConfigEditorBoolean
        public boolean birriesHighlight = false;

        @Expose
        @ConfigOption(name = "Birries Colour", desc = "Colour of the Birries highlight.")
        @ConfigEditorColour
        public String birriesColour = "0:255:255:255:85";

        @Expose
        @ConfigOption(name = "Highlight Hideonleaf", desc = "Glows Hideonleaf near you on Galatea (SkyHanni).")
        @ConfigEditorBoolean
        public boolean hideonleafHighlight = false;

        @Expose
        @ConfigOption(name = "Hideonleaf Colour", desc = "Colour of the Hideonleaf highlight.")
        @ConfigEditorColour
        public String hideonleafColour = "0:255:85:255:85";

        @Expose
        @ConfigOption(name = "Mute Phantoms", desc = "Mutes the sounds of Phantoms on Galatea (SkyHanni).")
        @ConfigEditorBoolean
        public boolean mutePhantoms = false;

        @Expose
        @ConfigOption(name = "Agatha & Miria Coupon Profit", desc = "In Agatha's and Miria's shops, adds the sell price, item cost, profit and profit per coupon to an item's tooltip (SkyHanni's Starlyn sister coupon profit). Cost items without a price are skipped.")
        @ConfigEditorBoolean
        public boolean couponProfit = false;

        @Expose
        @ConfigOption(name = "Honeyhive Reminder", desc = "After you loot a Honeyhive on the Torrhus Canyon, a title and message when the time below has passed (SkyHanni).")
        @ConfigEditorBoolean
        public boolean honeyhiveReminder = false;

        @Expose
        @ConfigOption(name = "Honeyhive Reminder Minutes", desc = "How long after looting a Honeyhive to remind you.")
        @ConfigEditorSlider(minValue = 5, maxValue = 180, minStep = 5)
        public float honeyhiveMinutes = 60;

        @Expose
        @ConfigOption(name = "Queen Bee Title", desc = "A title when a Honeyhive instantly refills with loot (Queen Bee).")
        @ConfigEditorBoolean
        public boolean queenBeeTitle = false;
    }
}
