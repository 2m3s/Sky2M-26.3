package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.Sky2MSkyblockTime;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.misc.MayorDisplay;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carnival helpers: the daily ticket reminder, the goals display and the Zombie Shootout hitboxes and timers. Ported
 * from SkyHanni's CarnivalReminder, CarnivalGoal and CarnivalZombieShootout (https://github.com/hannibal002/SkyHanni,
 * features/event/carnival/, LGPL-2.1). The Fruit Digging solver and the Zombie Shootout lantern timer are not ported.
 */
public final class CarnivalFeatures {
    private static final String GOALS_HUD = "events_carnival_goals";
    private static final long REMINDER_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long FIRST_REMINDER_DELAY_MS = 30_000L;
    private static final String DAY_KEY = "carnivalLastClaimedDay";
    private static final String YEAR_KEY = "carnivalYear";
    private static final String GOALS_KEY = "carnivalGoals";

    private enum Game {
        FRUIT_DIGGING("Fruit Digging", ChatFormatting.GOLD, Items.SAND),
        CATCH_A_FISH("Catch a Fish", ChatFormatting.DARK_AQUA, Items.COD),
        ZOMBIE_SHOOTOUT("Zombie Shootout", ChatFormatting.RED, Items.ARROW);

        final String label;
        final ChatFormatting colour;
        final Item icon;

        Game(String label, ChatFormatting colour, Item icon) {
            this.label = label;
            this.colour = colour;
            this.icon = icon;
        }
    }

    private enum Goal {
        FRUIT_PLAY(Game.FRUIT_DIGGING, "Play 3 games of Fruit Digging.", "Play 3 games"),
        FRUIT_SCORE(Game.FRUIT_DIGGING, "Reach 3,000 score in a single game of Fruit Digging.", "Reach 3,000 score"),
        DIG_APPLE(Game.FRUIT_DIGGING, "Dig up 3 Apples in a single game of Fruit Digging.", "Dig up 3 Apples"),
        UNIQUE_FRUIT(Game.FRUIT_DIGGING, "Dig up 5 unique Fruits in a single game of Fruit Digging.", "Dig up 5 unique Fruits"),
        DRAGONFRUIT(Game.FRUIT_DIGGING, "Dig up a Dragonfruit.", "Dig up a Dragonfruit"),
        FISH_PLAY(Game.CATCH_A_FISH, "Play 3 games of Catch a Fish.", "Play 3 games"),
        FISH_SCORE(Game.CATCH_A_FISH, "Reach 3,000 score in a single game of Catch a Fish.", "Reach 3,000 score"),
        CATCH_FISH(Game.CATCH_A_FISH, "Catch 30 Fish in a single game of Catch a Fish.", "Catch 30 Fish"),
        CATCH_YELLOW(Game.CATCH_A_FISH, "Catch 5 Yellow Fish in a single game of Catch a Fish.", "Catch 5 Yellow Fish"),
        CATCH_STREAK(Game.CATCH_A_FISH, "Reach a 5 Catch Streak in a single game of Catch a Fish.", "Reach a 5 Catch Streak"),
        SHOOT_PLAY(Game.ZOMBIE_SHOOTOUT, "Play 3 games of Zombie Shootout.", "Play 3 games"),
        SHOOT_SCORE(Game.ZOMBIE_SHOOTOUT, "Reach 3,000 score in a single game of Zombie Shootout.", "Reach 3,000 score"),
        SHOOT_ZOMBIES(Game.ZOMBIE_SHOOTOUT, "Shoot 60 Zombies in a single game of Zombie Shootout.", "Shoot 60 Zombies"),
        SHOOT_DIAMOND(Game.ZOMBIE_SHOOTOUT, "Shoot 5 Diamond Zombies in a single game of Zombie Shootout.", "Shoot 5 Diamond Zombies"),
        SHOOT_LAMPS(Game.ZOMBIE_SHOOTOUT, "Shoot 5 Redstone Lamps in a single game of Zombie Shootout.", "Shoot 5 Redstone Lamps");

        final Game game;
        final String sentence;
        final String display;

        Goal(Game game, String sentence, String display) {
            this.game = game;
            this.sentence = sentence;
            this.display = display;
        }
    }

    private enum ZombieType {
        LEATHER(30, Items.LEATHER_HELMET, new float[]{0.65f, 0.16f, 0.16f}, 8000),
        IRON(50, Items.IRON_HELMET, new float[]{0.75f, 0.75f, 0.75f}, 7000),
        GOLD(80, Items.GOLDEN_HELMET, new float[]{1f, 0.84f, 0f}, 6000),
        DIAMOND(120, Items.DIAMOND_HELMET, new float[]{0.17f, 0.84f, 0.98f}, 5000);

        final int points;
        final Item helmet;
        final float[] colour;
        final long lifetimeMs;

        ZombieType(int points, Item helmet, float[] colour, long lifetimeMs) {
            this.points = points;
            this.helmet = helmet;
            this.colour = colour;
            this.lifetimeMs = lifetimeMs;
        }
    }

    private record ShootoutZombie(Zombie entity, ZombieType type) {}

    private static Set<Goal> reached;
    private static boolean claimedToday;
    private static boolean claimedLoaded;
    private static long onSkyblockSince;
    private static long lastReminder;
    private static int ticks;
    private static int menuFrames;

    private static boolean shootoutStarted;
    private static long shootoutStartedAt;
    private static volatile List<ShootoutZombie> zombies = List.of();
    private static volatile ZombieType maxType = ZombieType.LEATHER;
    private static final Map<Integer, Long> FIRST_SEEN = new HashMap<>();

    private CarnivalFeatures() {}

    private static EventsConfig.Carnival config() {
        EventsConfig c = EventsFeatures.config();
        return c == null ? null : c.carnival;
    }

    static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(CarnivalFeatures::tick);
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MWorldRender.register(CarnivalFeatures::render);
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container
                && Sky2MLocation.strip(container.getTitle().getString()).trim().equals("Carnival Goals")) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                    if (++menuFrames % 10 == 1) readGoalsMenu(container);
                });
            }
        });
        Sky2MHuds.register(GOALS_HUD, "Carnival Goals", () -> {
            EventsConfig.Carnival c = config();
            return c != null && c.goals && Sky2MLocation.onSkyblock() && atCarnival();
        }, CarnivalFeatures::goalLines, List.of(
            Component.literal("Fruit Digging").withStyle(ChatFormatting.GOLD),
            Component.literal(" Play 3 games").withStyle(ChatFormatting.GRAY),
            Component.literal("Zombie Shootout").withStyle(ChatFormatting.RED),
            Component.literal(" Shoot 60 Zombies").withStyle(ChatFormatting.GRAY)), 10, 160);
        Sky2MHuds.setSettingsSearch(GOALS_HUD, "Show Goals");
        Sky2MHuds.setting(GOALS_HUD, () -> config() != null && config().goals);
    }

    private static boolean atCarnival() {
        return Sky2MLocation.location().contains("Carnival");
    }

    private static boolean carnivalPerkActive() {
        for (String perk : MayorDisplay.activePerks()) {
            if (perk.equalsIgnoreCase("Chivalrous Carnival")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        EventsConfig.Carnival c = config();
        if (c == null) return;
        if (text.startsWith("You claimed Carnival Ticket") || text.contains("already claimed your Carnival Tickets")) {
            claimedToday = true;
            claimedLoaded = true;
            EventsFeatures.put(DAY_KEY, LocalDate.now(ZoneOffset.UTC).toString());
            EventsFeatures.save();
        }
        if (c.goals) {
            String sentence = text.startsWith("- ") ? text.substring(2).trim() : text;
            for (Goal goal : Goal.values()) {
                if (goal.sentence.equals(sentence)) {
                    setReached(goal, true);
                    break;
                }
            }
        }
        if (c.zombieShootout.enabled) {
            if (text.equals("[NPC] Carnival Cowboy: Good luck, pal!")) {
                shootoutStarted = true;
                shootoutStartedAt = System.currentTimeMillis();
            } else if (text.equals("Zombie Shootout") && shootoutStarted) {
                shootoutStarted = false;
                zombies = List.of();
                FIRST_SEEN.clear();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ reminder

    private static boolean claimedToday() {
        if (!claimedLoaded) {
            claimedLoaded = true;
            claimedToday = LocalDate.now(ZoneOffset.UTC).toString().equals(EventsFeatures.string(DAY_KEY, ""));
        }
        return claimedToday;
    }

    private static void checkReminder(EventsConfig.Carnival c) {
        long now = System.currentTimeMillis();
        if (!c.ticketReminder || !Sky2MLocation.onSkyblock()) {
            onSkyblockSince = 0;
            return;
        }
        if (onSkyblockSince == 0) onSkyblockSince = now;
        // A new UTC day: the tickets can be claimed again.
        if (claimedToday && !LocalDate.now(ZoneOffset.UTC).toString().equals(EventsFeatures.string(DAY_KEY, ""))) claimedToday = false;
        if (claimedToday() || now - onSkyblockSince < FIRST_REMINDER_DELAY_MS || now - lastReminder < REMINDER_INTERVAL_MS) return;
        if (Sky2MLocation.inDungeon() || !carnivalPerkActive()) return;
        lastReminder = now;
        EventsFeatures.sayClickable("Carnival Tickets are ready to be claimed! Click to warp to the Carnival.", "/warp carnival", "Click to run /warp carnival");
    }

    // ------------------------------------------------------------------------------------------------ goals

    private static Set<Goal> reached() {
        int year = (int) ((System.currentTimeMillis() - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli()) / Sky2MSkyblockTime.YEAR_MILLIS) + 1;
        if (reached == null || EventsFeatures.number(YEAR_KEY, -1) != year) {
            reached = new HashSet<>();
            if (EventsFeatures.number(YEAR_KEY, -1) == year) {
                for (JsonElement e : EventsFeatures.array(GOALS_KEY)) {
                    try {
                        reached.add(Goal.valueOf(e.getAsString()));
                    } catch (IllegalArgumentException ignored) {}
                }
            } else {
                EventsFeatures.put(YEAR_KEY, year);
                EventsFeatures.remove(GOALS_KEY);
                EventsFeatures.save();
            }
        }
        return reached;
    }

    private static void setReached(Goal goal, boolean value) {
        Set<Goal> set = reached();
        if (value ? !set.add(goal) : !set.remove(goal)) return;
        JsonArray array = new JsonArray();
        for (Goal g : set) array.add(g.name());
        EventsFeatures.remove(GOALS_KEY);
        EventsFeatures.array(GOALS_KEY).addAll(array);
        EventsFeatures.save();
    }

    private static void readGoalsMenu(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        EventsConfig.Carnival c = config();
        if (c == null || !c.goals) return;
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            Game game = null;
            for (Game g : Game.values()) if (stack.is(g.icon)) game = g;
            if (game == null) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            String first = Sky2MLocation.strip(lore.lines().get(0).getString()).trim();
            if (first.startsWith("- ")) first = first.substring(2).trim();
            String last = Sky2MLocation.strip(lore.lines().get(lore.lines().size() - 1).getString()).trim();
            if (first.length() < 8) continue;
            for (Goal goal : Goal.values()) {
                if (goal.game == game && goal.sentence.startsWith(first)) {
                    setReached(goal, last.equals("COMPLETE"));
                    break;
                }
            }
        }
    }

    private static List<Component> goalLines() {
        Set<Goal> done = reached();
        List<Component> lines = new ArrayList<>();
        for (Game game : Game.values()) {
            List<Component> open = new ArrayList<>();
            for (Goal goal : Goal.values()) {
                if (goal.game == game && !done.contains(goal)) open.add(Component.literal(" " + goal.display).withStyle(ChatFormatting.GRAY));
            }
            if (open.isEmpty()) continue;
            lines.add(Component.literal(game.label).withStyle(game.colour));
            lines.addAll(open);
        }
        return lines;
    }

    // ------------------------------------------------------------------------------------------------ zombie shootout

    private static void tick(Minecraft mc) {
        EventsConfig.Carnival c = config();
        if (c == null || mc.player == null || mc.level == null) {
            zombies = List.of();
            return;
        }
        if (++ticks % 20 == 0) checkReminder(c);
        EventsConfig.ZombieShootout z = c.zombieShootout;
        if (!z.enabled || !shootoutStarted || !Sky2MLocation.onSkyblock()) {
            if (!zombies.isEmpty()) zombies = List.of();
            return;
        }
        if (System.currentTimeMillis() - shootoutStartedAt > 5 * 60 * 1000L) {
            shootoutStarted = false;
            return;
        }
        if (ticks % 2 != 0) return;
        List<ShootoutZombie> found = new ArrayList<>();
        for (Zombie zombie : mc.level.getEntitiesOfClass(Zombie.class, mc.player.getBoundingBox().inflate(50))) {
            if (zombie.getHealth() <= 0) continue;
            ItemStack helmet = zombie.getItemBySlot(EquipmentSlot.HEAD);
            if (helmet.isEmpty()) continue;
            for (ZombieType type : ZombieType.values()) {
                if (helmet.is(type.helmet)) {
                    found.add(new ShootoutZombie(zombie, type));
                    break;
                }
            }
        }
        ZombieType max = ZombieType.LEATHER;
        for (ShootoutZombie s : found) if (s.type.points > max.points) max = s.type;
        maxType = max;
        long now = System.currentTimeMillis();
        Set<Integer> present = new HashSet<>();
        for (ShootoutZombie s : found) {
            present.add(s.entity.getId());
            FIRST_SEEN.putIfAbsent(s.entity.getId(), now);
        }
        FIRST_SEEN.keySet().retainAll(present);
        zombies = found;
    }

    private static void render(PrimitiveCollector collector) {
        EventsConfig.Carnival c = config();
        if (c == null || !c.zombieShootout.enabled || !shootoutStarted) return;
        EventsConfig.ZombieShootout z = c.zombieShootout;
        long now = System.currentTimeMillis();
        for (ShootoutZombie s : zombies) {
            if (z.highestOnly && s.type != maxType) continue;
            if (s.entity.isRemoved()) continue;
            AABB box = s.entity.getBoundingBox();
            if (z.hitboxes) collector.submitOutlinedBox(box.inflate(0.1, 0.05, 0.0).move(0.0, 0.05, 0.0), s.type.colour, 3f, true);
            if (z.timer) {
                long left = s.type.lifetimeMs - (now - FIRST_SEEN.getOrDefault(s.entity.getId(), now));
                if (left <= 0) continue;
                ChatFormatting colour = left > s.type.lifetimeMs * 2 / 3 ? ChatFormatting.GREEN : left > s.type.lifetimeMs / 3 ? ChatFormatting.YELLOW : ChatFormatting.RED;
                Vec3 at = new Vec3(s.entity.getX(), box.maxY + 0.5, s.entity.getZ());
                collector.submitText(Component.literal(String.format(java.util.Locale.US, "%.1fs", left / 1000.0)).withStyle(colour), at, 1.25f, true);
            }
        }
    }
}
