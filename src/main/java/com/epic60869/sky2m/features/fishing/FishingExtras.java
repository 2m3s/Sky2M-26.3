package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Riccio Fishing Utils (https://github.com/Ricciow/RiccioFishingUtils-Modern) style extras: failed cast alert, no fishing
 * armor alert, Jawbus death sound, hotspot highlight, time to kill with a personal best per sea creature, and the time
 * and number of catches since the previous catch of the same sea creature.
 */
public final class FishingExtras {
    private static final String[] FISHING_STATS = {"Sea Creature Chance", "Fishing Speed", "Double Hook Chance", "Trophy Fish Chance", "Treasure Chance"};
    private static final long FAILED_CAST_MS = 2500;
    private static final long PENDING_UNSEEN_MS = 30_000;
    private static final long PENDING_MAX_MS = 5 * 60_000;

    // Casts
    private static int hookId = -1;
    private static long castTime;
    private static boolean castChecked;
    private static boolean failedAlerted;

    // Time to kill
    private static final class Pending {
        final String creature;
        final long caughtAt;
        int standId = -1;
        double x, y, z;
        Pending(String creature, long caughtAt) {
            this.creature = creature;
            this.caughtAt = caughtAt;
        }
    }
    private static final List<Pending> pending = new ArrayList<>();

    // Hotspot highlight
    private static List<AABB> hotspots = List.of();
    private static ClientLevel lastLevel;
    private static int ticks;

    // Catch history (persisted)
    private static final class Seen {
        long time;
        long total;
    }
    private static final Map<String, Seen> seen = new HashMap<>();
    private static final Map<String, Long> bestKill = new HashMap<>();
    private static long totalCatches;
    private static String sinceTime = "";
    private static String sinceCount = "";
    private static boolean loaded;

    private FishingExtras() {}

    static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.fishing == null ? null : c.fishing.extras;
    }

    public static void init() {
        load();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            pending.clear();
            hotspots = List.of();
            hookId = -1;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FishingExtras::tick);
        Sky2MWorldRender.register(collector -> {
            Config c = config();
            if (c == null || !c.highlightHotspots || hotspots.isEmpty()) return;
            float[] colour = colour(c.hotspotColour);
            for (AABB box : hotspots) {
                collector.submitFilledBox(box, colour, 0.25f * colour[3], true);
                collector.submitOutlinedBox(box, colour, colour[3], 2f, true);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        Player player = mc.player;
        ClientLevel level = mc.level;
        Config c = config();
        if (c == null || player == null || level == null || !Compat.isOnSkyblock()) {
            hookId = -1;
            hotspots = List.of();
            return;
        }
        if (level != lastLevel) {
            lastLevel = level;
            pending.clear();
            hotspots = List.of();
        }
        ticks++;
        tickCast(mc, player, c);
        if (!pending.isEmpty()) {
            if (!player.isAlive()) pending.clear();
            else if (ticks % 5 == 0) tickKills(player, level, c);
        }
        if (c.highlightHotspots && ticks % 10 == 0) findHotspots(player, level);
        else if (!c.highlightHotspots && !hotspots.isEmpty()) hotspots = List.of();
    }

    private static void tickCast(Minecraft mc, Player player, Config c) {
        FishingHook hook = player.fishing;
        if (hook == null) {
            hookId = -1;
            return;
        }
        long now = System.currentTimeMillis();
        if (hook.getId() != hookId) {
            hookId = hook.getId();
            castTime = now;
            castChecked = false;
            failedAlerted = false;
        }
        if (!(player.getMainHandItem().getItem() instanceof FishingRodItem)) return;
        if (!castChecked && c.noArmorAlert) {
            castChecked = true;
            if (!wearingFishingArmor(player)) {
                Sky2MAlerts.title(Component.literal("No Fishing Armor").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal("You are not wearing any fishing armor"));
            }
        }
        if (c.failedCastAlert && !failedAlerted && now - castTime >= FAILED_CAST_MS && !hook.isInWater() && !hook.isInLava()) {
            failedAlerted = true;
            Sky2MAlerts.title(Component.literal("Failed Cast").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.literal("Your bobber is not in water or lava"));
        }
    }

    private static boolean wearingFishingArmor(Player player) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = player.getItemBySlot(slot);
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                String text = Sky2MLocation.strip(line.getString());
                for (String stat : FISHING_STATS) if (text.contains(stat)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ hotspots

    private static void findHotspots(Player player, ClientLevel level) {
        List<AABB> found = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            if (stand.distanceToSqr(player) > 160 * 160) continue;
            if (!Sky2MLocation.strip(stand.getCustomName().getString()).equals("HOTSPOT")) continue;
            found.add(new AABB(stand.getX() - 2.5, stand.getY() - 1.5, stand.getZ() - 2.5, stand.getX() + 2.5, stand.getY() + 0.5, stand.getZ() + 2.5));
        }
        hotspots = found;
    }

    // ------------------------------------------------------------------------------------------------ time to kill

    private static void tickKills(Player player, ClientLevel level, Config c) {
        long now = System.currentTimeMillis();
        List<Pending> done = new ArrayList<>();
        for (Pending p : pending) {
            if (now - p.caughtAt > PENDING_MAX_MS) {
                done.add(p);
                continue;
            }
            if (p.standId == -1) {
                if (now - p.caughtAt > PENDING_UNSEEN_MS) {
                    done.add(p);
                    continue;
                }
                ArmorStand stand = findStand(player, level, p.creature);
                if (stand != null) {
                    p.standId = stand.getId();
                    p.x = stand.getX();
                    p.y = stand.getY();
                    p.z = stand.getZ();
                }
                continue;
            }
            Entity entity = level.getEntity(p.standId);
            if (entity != null && !entity.isRemoved()) {
                p.x = entity.getX();
                p.y = entity.getY();
                p.z = entity.getZ();
                continue;
            }
            done.add(p);
            // Gone while you were nearby: killed. Gone while you were far away (teleported, unloaded): ignored.
            if (player.distanceToSqr(p.x, p.y, p.z) <= 50 * 50) killed(p, now, c);
        }
        pending.removeAll(done);
    }

    private static ArmorStand findStand(Player player, ClientLevel level, String creature) {
        ArmorStand best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ArmorStand stand : level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(40), Entity::hasCustomName)) {
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            if (!name.contains(creature) || !name.contains("❤")) continue;
            boolean taken = false;
            for (Pending other : pending) if (other.standId == stand.getId()) taken = true;
            if (taken) continue;
            double d = stand.distanceToSqr(player);
            if (d < bestDistance) {
                bestDistance = d;
                best = stand;
            }
        }
        return best;
    }

    private static void killed(Pending p, long now, Config c) {
        long millis = now - p.caughtAt;
        if (c.jawbusDeathSound && p.creature.equals("Lord Jawbus")) {
            Sky2MAlerts.play(SoundEvents.ENDER_DRAGON_DEATH, 1.2f, 0.8f);
        }
        if (!c.timeToKill) return;
        Component line = Component.literal("Killed ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(p.creature).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(" in ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(duration(millis)).withStyle(ChatFormatting.GREEN));
        if (c.killTimePb) {
            Long best = bestKill.get(p.creature);
            if (best == null) {
                bestKill.put(p.creature, millis);
                save();
                line = line.copy().append(Component.literal(" (first time recorded)").withStyle(ChatFormatting.GRAY));
            } else if (millis < best) {
                bestKill.put(p.creature, millis);
                save();
                line = line.copy().append(Component.literal(" NEW PERSONAL BEST! ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD))
                    .append(Component.literal("(was " + duration(best) + ")").withStyle(ChatFormatting.GRAY));
                Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1f);
            } else {
                line = line.copy().append(Component.literal(" (best " + duration(best) + ")").withStyle(ChatFormatting.GRAY));
            }
        }
        Sky2MAlerts.chat(line);
    }

    // ------------------------------------------------------------------------------------------------ catch history

    /** Called for each sea creature you catch, before its catch message is built. */
    static void onCatch(FishingData.SeaCreature creature, boolean doubleHook) {
        Config c = config();
        if (c == null) return;
        long now = System.currentTimeMillis();
        totalCatches += doubleHook ? 2 : 1;
        Seen previous = seen.get(creature.name());
        sinceTime = "";
        sinceCount = "";
        if (previous != null && c.sinceLast && creature.rare()) {
            sinceTime = duration(now - previous.time);
            sinceCount = String.valueOf(totalCatches - previous.total);
        }
        Seen current = new Seen();
        current.time = now;
        current.total = totalCatches;
        seen.put(creature.name(), current);
        save();

        if (c.timeToKill && creature.rare() || c.jawbusDeathSound && creature.name().equals("Lord Jawbus")) {
            for (int i = 0; i < (doubleHook ? 2 : 1); i++) pending.add(new Pending(creature.name(), now));
        }
    }

    /** True when the catch message should carry the since-last text (nothing in the template placed it). */
    static boolean sinceEnabled() {
        Config c = config();
        return c != null && c.sinceLast;
    }

    static String sinceTime() {
        return sinceTime;
    }

    static String sinceCount() {
        return sinceCount;
    }

    /** "(after 123 catches, 12m 3s)" or empty. */
    static String sinceSuffix() {
        if (sinceTime.isEmpty()) return "";
        return " §8(§7" + sinceCount + " catches§8, §7" + sinceTime + "§8)";
    }

    /** For the Send Test Message button: pretend the previous one was 87 catches and 41 minutes ago. */
    static void useSample() {
        sinceTime = sinceEnabled() ? "41m 12s" : "";
        sinceCount = sinceEnabled() ? "87" : "";
    }

    /** Shown after the vanilla catch message when compact catch messages are off. */
    static void sendSinceLine(FishingData.SeaCreature creature) {
        if (sinceTime.isEmpty()) return;
        Sky2MAlerts.chat(SbcItems.parseLegacy("§7" + creature.name() + "§7 last caught " + sinceCount + " catches and " + sinceTime + " ago"));
    }

    // ------------------------------------------------------------------------------------------------ storage

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("fishing_extras.json");
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path file = file();
            if (!Files.exists(file)) return;
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            totalCatches = json.has("totalCatches") ? json.get("totalCatches").getAsLong() : 0;
            if (json.has("bestKill")) {
                for (var e : json.getAsJsonObject("bestKill").entrySet()) bestKill.put(e.getKey(), e.getValue().getAsLong());
            }
            if (json.has("seen")) {
                for (var e : json.getAsJsonObject("seen").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    Seen s = new Seen();
                    s.time = o.get("time").getAsLong();
                    s.total = o.get("total").getAsLong();
                    seen.put(e.getKey(), s);
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read fishing extras: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("totalCatches", totalCatches);
            Gson gson = new Gson();
            json.add("bestKill", gson.toJsonTree(bestKill));
            json.add("seen", gson.toJsonTree(seen));
            Files.createDirectories(file().getParent());
            Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save fishing extras: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String duration(long millis) {
        if (millis < 60_000) return String.format(Locale.US, "%.1fs", millis / 1000.0);
        long minutes = millis / 60_000;
        if (minutes < 60) return minutes + "m " + (millis % 60_000) / 1000 + "s";
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    private static float[] colour(String value) {
        try {
            int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f};
        } catch (Exception e) {
            return new float[]{1f, 0.4f, 1f, 1f};
        }
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Failed Cast Alert", desc = "A title when your bobber hasn't reached water or lava a moment after you cast it (Riccio Fishing Utils).")
        @ConfigEditorBoolean
        public boolean failedCastAlert = false;

        @Expose
        @ConfigOption(name = "No Fishing Armor Alert", desc = "A title when you cast without wearing any armor piece with fishing stats (Sea Creature Chance, Fishing Speed, Double Hook Chance, Trophy Fish Chance or Treasure Chance).")
        @ConfigEditorBoolean
        public boolean noArmorAlert = false;

        @Expose
        @ConfigOption(name = "Jawbus Death Sound", desc = "Plays a sound when the Lord Jawbus you caught dies. Needs you to be near it.")
        @ConfigEditorBoolean
        public boolean jawbusDeathSound = false;

        @Expose
        @ConfigOption(name = "Highlight Hotspots", desc = "A coloured box around fishing hotspots, visible through walls.")
        @ConfigEditorBoolean
        public boolean highlightHotspots = false;

        @Expose
        @ConfigOption(name = "Guess Hotspot Radar", desc = "Right-click a Hotspot Radar and the flame trail it shows is followed to the hotspot: a HOTSPOT label and its distance appear there, like SkyHanni's.")
        @ConfigEditorBoolean
        public boolean guessHotspotRadar = true;

        @Expose
        @ConfigOption(name = "Line to Hotspot", desc = "Draw a line from your crosshair to the guessed hotspot. Needs Guess Hotspot Radar.")
        @ConfigEditorBoolean
        public boolean lineToHotspot = true;

        @Expose
        @ConfigOption(name = "Hotspot Colour", desc = "Colour of the hotspot highlight.")
        @ConfigEditorColour
        public String hotspotColour = "0:255:255:102:255";

        @Expose
        @ConfigOption(name = "Time to Kill", desc = "Says in chat how long it took you to kill a rare sea creature you caught, from its catch message until it dies. Ignored if you die or get far away.")
        @ConfigEditorBoolean
        public boolean timeToKill = false;

        @Expose
        @ConfigOption(name = "Kill Time Personal Best", desc = "Remembers your fastest kill of each sea creature and announces new personal bests. Needs Time to Kill.")
        @ConfigEditorBoolean
        public boolean killTimePb = true;

        @Expose
        @ConfigOption(name = "Time & Catches Since Last", desc = "On a rare sea creature catch, shows how much time and how many sea creature catches it took since you caught the same one before. Use {time} and {count} in the Catch Message Template to place them, otherwise they're added to the end of the message.")
        @ConfigEditorBoolean
        public boolean sinceLast = false;
    }
}
