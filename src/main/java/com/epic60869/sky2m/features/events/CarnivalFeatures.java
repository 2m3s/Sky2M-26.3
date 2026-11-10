package com.epic60869.sky2m.features.events;

import com.epic60869.sky2m.tools.Sky2MSkyblockTime;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.misc.visual.MayorDisplay;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.epic60869.sky2m.sb.events.WorldEvents;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
 * Carnival helpers: the daily ticket reminder, the goals display, Quick Start and the Zombie Shootout hitboxes, timers
 * and lamp timer. Ported from SkyHanni's CarnivalReminder, CarnivalGoal, CarnivalQuickStart and CarnivalZombieShootout
 * (https://github.com/hannibal002/SkyHanni, features/event/carnival/, LGPL-2.1). Fruit Digging is in
 * {@link CarnivalFruitDigging}.
 */
public final class CarnivalFeatures {
    private static final String GOALS_HUD = "events_carnival_goals";
    private static final long REMINDER_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long FIRST_REMINDER_DELAY_MS = 30_000L;
    private static final String DAY_KEY = "carnivalLastClaimedDay";
    private static final String YEAR_KEY = "carnivalYear";
    private static final String GOALS_KEY = "carnivalGoals";

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

    /** Ids of the goals reached this Carnival (the ids in Carnival.json). */
    private static Set<String> reached;
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

    private static final String LAMP_HUD = "events_carnival_lamp";
    private static final long LAMP_LIFETIME_MS = 6000L;
    private static volatile BlockPos lampPos;
    private static volatile long lampLitAt;

    private static final long QUICK_START_WINDOW_MS = 5000L;
    private static long optionsShownAt;
    private static long lastQuickStart;

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
                && Sky2MLocation.strip(container.getTitle().getString()).contains(CarnivalData.get().menuTitle())) {
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

        WorldEvents.BLOCK_STATE_UPDATE.register(CarnivalFeatures::onBlockUpdate);
        Sky2MHuds.register(LAMP_HUD, "Zombie Shootout Lamp", () -> {
            EventsConfig.Carnival c = config();
            return c != null && c.zombieShootout.enabled && c.zombieShootout.lampTimer && shootoutStarted && lampPos != null;
        }, CarnivalFeatures::lampLines, List.of(
            Component.literal("Lamp goes out in ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("4.2s").withStyle(ChatFormatting.GREEN))), 10, 260);
        Sky2MHuds.setSettingsSearch(LAMP_HUD, "Lamp Timer");
        Sky2MHuds.setting(LAMP_HUD, () -> config() != null && config().zombieShootout.enabled && config().zombieShootout.lampTimer);

        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> quickStart(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> quickStart(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    // ------------------------------------------------------------------------------------------------ quick start

    /** Clicking a game NPC again while its options are in chat picks "Sure thing, partner!" (Carnival.json's quickStartOption). */
    private static boolean quickStart(Entity entity) {
        EventsConfig.Carnival c = config();
        long now = System.currentTimeMillis();
        if (c == null || !c.quickStart || !Sky2MLocation.onSkyblock() || !atCarnival()) return false;
        if (now - optionsShownAt > QUICK_START_WINDOW_MS || now - lastQuickStart < 1000L) return false;
        String npc = gameNpcAt(entity);
        if (npc == null) return false;
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return false;
        lastQuickStart = now;
        optionsShownAt = 0;
        connection.sendCommand("selectnpcoption " + npc + " " + CarnivalData.get().quickStartOption());
        return true;
    }

    /** The /selectnpcoption id of the game NPC this entity is (its name tag floats just above it), or null. */
    static String gameNpcAt(Entity entity) {
        if (entity == null || entity.level() == null) return null;
        String own = npcId(entity);
        if (own != null) return own;
        for (ArmorStand stand : entity.level().getEntitiesOfClass(ArmorStand.class, entity.getBoundingBox().inflate(0.6, 2.5, 0.6))) {
            String id = npcId(stand);
            if (id != null) return id;
        }
        return null;
    }

    private static String npcId(Entity entity) {
        Component name = entity.getCustomName();
        if (name == null) name = entity.getName();
        String text = Sky2MLocation.strip(name.getString()).trim();
        return CarnivalData.get().npcs().get(text);
    }

    /** For the game test: "Select an option:" was just shown. */
    public static void testOptionsShown() {
        optionsShownAt = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------------------------------------ lamp

    /** A Redstone Lamp lighting up during Zombie Shootout is worth shooting for 6 seconds; it going out ends that. */
    public static void onBlockUpdate(BlockPos pos, BlockState old, BlockState updated) {
        if (!shootoutStarted || old == null || !old.is(Blocks.REDSTONE_LAMP) || !updated.is(Blocks.REDSTONE_LAMP)) return;
        boolean wasLit = old.getValue(BlockStateProperties.LIT), lit = updated.getValue(BlockStateProperties.LIT);
        if (!wasLit && lit) {
            lampPos = pos.immutable();
            lampLitAt = System.currentTimeMillis();
        } else if (wasLit && !lit && pos.equals(lampPos)) {
            lampPos = null;
        }
    }

    public static List<Component> lampLines() {
        if (lampPos == null) return List.of();
        long left = LAMP_LIFETIME_MS - (System.currentTimeMillis() - lampLitAt);
        if (left <= 0) return List.of();
        ChatFormatting colour = left > 4000 ? ChatFormatting.GREEN : left > 2000 ? ChatFormatting.YELLOW : ChatFormatting.RED;
        return List.of(Component.literal("Lamp goes out in ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(String.format(java.util.Locale.US, "%.1fs", left / 1000.0)).withStyle(colour)));
    }

    /** For the game test: pretend a Zombie Shootout game started. */
    public static void testStartShootout() {
        shootoutStarted = true;
        shootoutStartedAt = System.currentTimeMillis();
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
        CarnivalData.Snapshot data = CarnivalData.get();
        if (c.goals) {
            String sentence = text.startsWith("- ") ? text.substring(2).trim() : text;
            for (CarnivalData.Goal goal : data.goals()) {
                if (goal.sentence().equals(sentence)) {
                    setReached(goal, true);
                    break;
                }
            }
        }
        if (c.quickStart && text.startsWith(data.npcOptions()) && atCarnival()) optionsShownAt = System.currentTimeMillis();
        if (c.zombieShootout.enabled) {
            if (text.equals(data.shootoutStart())) {
                shootoutStarted = true;
                shootoutStartedAt = System.currentTimeMillis();
                lampPos = null;
            } else if (text.equals(data.shootoutEnd()) && shootoutStarted) {
                shootoutStarted = false;
                zombies = List.of();
                FIRST_SEEN.clear();
                lampPos = null;
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

    private static Set<String> reached() {
        int year = (int) ((System.currentTimeMillis() - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli()) / Sky2MSkyblockTime.YEAR_MILLIS) + 1;
        if (reached == null || EventsFeatures.number(YEAR_KEY, -1) != year) {
            reached = new HashSet<>();
            if (EventsFeatures.number(YEAR_KEY, -1) == year) {
                for (JsonElement e : EventsFeatures.array(GOALS_KEY)) {
                    reached.add(e.getAsString());
                }
            } else {
                EventsFeatures.put(YEAR_KEY, year);
                EventsFeatures.remove(GOALS_KEY);
                EventsFeatures.save();
            }
        }
        return reached;
    }

    private static void setReached(CarnivalData.Goal goal, boolean value) {
        Set<String> set = reached();
        if (value ? !set.add(goal.id()) : !set.remove(goal.id())) return;
        JsonArray array = new JsonArray();
        for (String id : set) array.add(id);
        EventsFeatures.remove(GOALS_KEY);
        EventsFeatures.array(GOALS_KEY).addAll(array);
        EventsFeatures.save();
    }

    /**
     * Reads the goals from a Carnival menu. A goal is found by its sentence anywhere in an item's lore (lines joined, so a
     * sentence wrapped over two lines still matches), whatever the item's icon or header lines are; the sentence names
     * its game, so the three "Play 3 games" goals stay apart. A goal is reached when a lore line says COMPLETE.
     */
    private static void readGoalsMenu(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        EventsConfig.Carnival c = config();
        if (c == null || !c.goals) return;
        List<Slot> slots = screen.getMenu().slots;
        int found = 0;
        List<String> unknown = new ArrayList<>();
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null || lore.lines().isEmpty()) continue;
            List<String> lines = new ArrayList<>();
            for (Component line : lore.lines()) lines.add(Sky2MLocation.strip(line.getString()).trim());
            CarnivalData.Goal goal = goalIn(lines);
            if (goal == null) {
                if (unknown.size() < 6) unknown.add(Sky2MLocation.strip(stack.getHoverName().getString()) + " " + lines);
                continue;
            }
            found++;
            setReached(goal, completeIn(lines));
        }
        if (found == 0 && !loggedUnknownMenu && Sky2MLocation.strip(screen.getTitle().getString()).contains("Goals") && !unknown.isEmpty()) {
            loggedUnknownMenu = true;
            com.mojang.logging.LogUtils.getLogger().info("[Sky2M] Carnival goals not recognised in '{}': {}", screen.getTitle().getString(), unknown);
        }
    }

    private static boolean loggedUnknownMenu;

    static CarnivalData.Goal goalIn(List<String> lines) {
        String joined = String.join(" ", lines).replaceAll("\\s+", " ").replaceAll("(^| )- ", "$1");
        List<CarnivalData.Goal> goals = CarnivalData.get().goals();
        for (CarnivalData.Goal goal : goals) if (joined.contains(goal.sentence())) return goal;
        for (CarnivalData.Goal goal : goals) {
            String sentence = goal.sentence();
            if (sentence.endsWith(".") && joined.contains(sentence.substring(0, sentence.length() - 1))) return goal;
        }
        return null;
    }

    static boolean completeIn(List<String> lines) {
        java.util.regex.Pattern complete = CarnivalData.get().completeLine();
        for (String line : lines) if (complete.matcher(line).matches()) return true;
        return false;
    }

    /** For the game test: the goals that would show (not reached). */
    public static List<String> openGoals() {
        List<String> open = new ArrayList<>();
        for (CarnivalData.Goal goal : CarnivalData.get().goals()) if (!reached().contains(goal.id())) open.add(goal.id());
        return open;
    }

    private static List<Component> goalLines() {
        Set<String> done = reached();
        CarnivalData.Snapshot data = CarnivalData.get();
        List<Component> lines = new ArrayList<>();
        for (CarnivalData.Game game : data.games()) {
            List<Component> open = new ArrayList<>();
            for (CarnivalData.Goal goal : data.goals()) {
                if (goal.game().id().equals(game.id()) && !done.contains(goal.id())) open.add(Component.literal(" " + goal.display()).withStyle(ChatFormatting.GRAY));
            }
            if (open.isEmpty()) continue;
            lines.add(Component.literal(game.label()).withStyle(game.colour()));
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
        BlockPos lamp = lampPos;
        if (lamp != null && now - lampLitAt < LAMP_LIFETIME_MS) {
            if (z.lampHighlight) collector.submitFilledBox(lamp, new float[]{1f, 0f, 0f}, 0.5f, true);
            if (z.lampLine) collector.submitLineFromCursor(Vec3.atCenterOf(lamp), new float[]{1f, 0f, 0f}, 1f, 3f);
        }
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
