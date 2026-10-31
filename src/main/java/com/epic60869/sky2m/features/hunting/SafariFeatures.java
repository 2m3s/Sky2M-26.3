package com.epic60869.sky2m.features.hunting;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * SkyHanni's Critter Safari features (https://github.com/hannibal002/SkyHanni, features/hunting/safari/, LGPL-2.1),
 * including the 9.1 beta's Critter Capsule hiding and Shard Checklist: the four biome names in the centre, the Hideyho
 * Finder (a route past every spot Hideyho can hide), hiding thrown and dropped Critter Capsules, and a checklist of
 * the Safari's shards caught this run.
 */
public final class SafariFeatures {
    private static final String CHECKLIST_HUD = "hunting_safari_checklist";
    /** The skin texture hash of Hideyho (SkyHanni-REPO's Skulls.json HIDEYHO). */
    private static final String HIDEYHO_SKIN = "3504f1f2327a5110e643bb8667082512815fa434a29ed37f4ca83bb16d2db533";
    private static final String FOUND_ME = "[MOB] Hideyho: Hehe, you found me!";
    private static final String NO_PEEKING = "[MOB] Hideyho: No peeking!";
    private static final String FOUND_AFTER_HIDING = "[MOB] Hideyho: Aah! You found me!";

    public enum Biome {
        CAVERN("Cavern", "§6"), FOREST("Forest", "§2"), HAUNTED("Haunted", "§5"), ICY("Icy", "§9");

        final String displayName;
        final String formatted;

        Biome(String displayName, String colour) {
            this.displayName = displayName;
            this.formatted = colour + displayName;
        }

        static Biome ofArea(String area) {
            for (Biome biome : values()) if (area.equals(biome.displayName + " Biome")) return biome;
            return null;
        }
    }

    /** The Safari's shards per biome (SkyHanni's SafariShard). */
    private static final Map<Biome, List<String>> SHARDS = new EnumMap<>(Biome.class);

    static {
        SHARDS.put(Biome.CAVERN, List.of("Cavernfish", "Flitter", "Shyworm", "Driftling", "Chuckwalla", "Rockmite", "Scrappy", "Snoozle", "Gemzie"));
        SHARDS.put(Biome.FOREST, List.of("Foxtrot", "Honeybug", "Hideonfloor", "Treefrog", "Woodchucker", "Fluffling", "Bluebird", "Parakeet", "Macaw"));
        SHARDS.put(Biome.HAUNTED, List.of("Solsnatcher", "Bloodbat", "Litterbug", "Areita", "Duplico", "Gazer", "Hideonwall", "Gimmiegold", "Hideyho", "Doomspiral"));
        SHARDS.put(Biome.ICY, List.of("Strongarm", "Polaris", "Billygoat", "Tepid", "Nozzlenose", "Mantis Shrimp", "Shuddersquid", "Troodon", "Wumpa"));
    }

    private static final Map<Vec3, Biome> CENTRE_NAMES = Map.of(
        new Vec3(-27.1, 66.0, 22.8), Biome.FOREST, new Vec3(-25.5, 66.0, -23.2), Biome.HAUNTED,
        new Vec3(-73.3, 65.0, -23.4), Biome.ICY, new Vec3(-72.6, 65.5, 23.9), Biome.CAVERN);

    /** Shards caught this run (this world). */
    private static final Map<String, Integer> caught = new java.util.HashMap<>();
    private static String area = IslandGraph.NO_AREA;
    private static Biome biome;
    /** Checklist rows: text, and the shard for the icon (null for biome headers). */
    private record Row(Component text, AttributeShards.Shard shard) {}
    private static List<Row> rows = List.of();
    private static Vec3 hideyhoStart;
    private static boolean hideyhoNavigating;
    private static List<Integer> hideyhoRoute;
    private static int hideyhoIndex;
    private static long beginNavigationAt;
    private static int ticks;

    private SafariFeatures() {}

    private static HuntingConfig.Safari config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.hunting.safari;
    }

    static boolean inSafari() {
        return Sky2MLocation.areaIs("Critter Safari") || Sky2MLocation.areaIs("Safari");
    }

    public static void init() {
        IslandGraph.load("SAFARI");
        ClientTickEvents.END_CLIENT_TICK.register(SafariFeatures::tick);
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        AttributeShards.onShard((shard, amount, source, gain) -> {
            if (source != AttributeShards.Source.HUNT && source != AttributeShards.Source.CAPTURED || !inSafari()) return;
            if (SHARDS.values().stream().noneMatch(list -> list.contains(shard.displayName()))) return;
            caught.merge(shard.displayName(), amount, Integer::sum);
            rebuild();
        });
        Sky2MWorldRender.register(collector -> {
            HuntingConfig.Safari c = config();
            if (c == null || !c.namesInCenter || !inSafari() || !area.equals(IslandGraph.NO_AREA)) return;
            Minecraft mc = Minecraft.getInstance();
            for (Map.Entry<Vec3, Biome> e : CENTRE_NAMES.entrySet()) {
                if (e.getKey().distanceToSqr(mc.player.position()) > 50) {
                    collector.submitText(Component.literal(e.getValue().formatted + " Biome"), e.getKey(), 2.5f, true);
                }
            }
        });
        Sky2MHuds.registerCustom(CHECKLIST_HUD, "Safari Shard Checklist", () -> {
            HuntingConfig.Safari c = config();
            return c != null && c.checklist.enabled && inSafari();
        }, new Sky2MHuds.CustomHud() {
            @Override
            public int width() {
                Minecraft mc = Minecraft.getInstance();
                int width = 60;
                for (Row row : shownRows()) width = Math.max(width, mc.font.width(row.text()) + (row.shard() != null && icons() ? 18 : 0));
                return width + 2 * Sky2MHuds.PADDING;
            }

            @Override
            public int height() {
                int height = 0;
                for (Row row : shownRows()) height += rowHeight(row);
                return height + 2 * Sky2MHuds.PADDING;
            }

            @Override
            public boolean visible() {
                return !rows.isEmpty();
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, boolean preview) {
                Minecraft mc = Minecraft.getInstance();
                int y = Sky2MHuds.PADDING;
                for (Row row : shownRows()) {
                    int x = Sky2MHuds.PADDING;
                    int h = rowHeight(row);
                    if (row.shard() != null && icons()) {
                        graphics.item(row.shard().icon(), x, y);
                        x += 18;
                    }
                    graphics.text(mc.font, row.text(), x, y + (h - 8) / 2, 0xFFFFFFFF, true);
                    y += h;
                }
            }
        }, 80, 100);
        Sky2MHuds.setSettingsSearch(CHECKLIST_HUD, "Shard Checklist");
        Sky2MHuds.setting(CHECKLIST_HUD, () -> config() != null && config().checklist.enabled);
    }

    private static boolean icons() {
        HuntingConfig.Safari c = config();
        return c != null && c.checklist.showIcons;
    }

    private static int rowHeight(Row row) {
        return row.shard() != null && icons() ? 17 : Sky2MHuds.LINE_HEIGHT;
    }

    /** The rows, or a preview in /s2 gui outside the Safari. */
    private static List<Row> shownRows() {
        if (!rows.isEmpty()) return rows;
        AttributeShards.Shard shard = AttributeShards.byName("Strongarm");
        return List.of(new Row(Component.literal("§9Icy §7- §c3/9"), null),
            new Row(Component.literal("§7- (§c✖§7) " + (shard == null ? "§fStrongarm" : shard.rarityColour() + "Strongarm")), shard));
    }

    // ------------------------------------------------------------------------------------------------ ticking

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !inSafari()) {
            if (!rows.isEmpty()) rows = List.of();
            area = IslandGraph.NO_AREA;
            biome = null;
            return;
        }
        if (++ticks % 10 == 0) {
            IslandGraph graph = IslandGraph.get("SAFARI");
            String now = graph == null ? IslandGraph.NO_AREA : graph.areaAt(mc.player.position());
            Biome nowBiome = Biome.ofArea(now);
            area = now;
            if (nowBiome != biome || rows.isEmpty()) {
                biome = nowBiome;
                rebuild();
            }
        }
        if (beginNavigationAt != 0 && System.currentTimeMillis() >= beginNavigationAt) {
            beginNavigationAt = 0;
            beginHideyhoNavigation(mc);
        }
        // Like SkyHanni's check every second: stop once Hideyho is seen near the spot you're heading to.
        if (hideyhoNavigating && ticks % 20 == 0 && hideyhoRoute != null) {
            IslandGraph graph = IslandGraph.get("SAFARI");
            int goal = hideyhoRoute.get(Math.max(0, hideyhoIndex - 1));
            if (graph != null && hideyhoNear(mc, graph.pos[goal], 5) != null) finishHideyho();
        }
    }

    private static void rebuild() {
        HuntingConfig.Safari c = config();
        if (c == null || !inSafari()) {
            rows = List.of();
            return;
        }
        List<Biome> detailed = switch (c.checklist.runDisplay) {
            case ALL -> List.of(Biome.values());
            case CURRENT_ON_TOP -> {
                List<Biome> list = new ArrayList<>(List.of(Biome.values()));
                if (biome != null) {
                    list.remove(biome);
                    list.addFirst(biome);
                }
                yield list;
            }
            case ONLY_CURRENT -> biome == null ? List.of() : List.of(biome);
        };
        boolean critters = c.checklist.style != HuntingConfig.Checklist.ChecklistStyle.SKYHANNI;
        List<Row> out = new ArrayList<>();
        if (critters) out.add(new Row(Component.literal("§e§lUnique Critters"), null));
        for (Biome b : detailed) {
            out.add(header(b, critters));
            for (String name : SHARDS.get(b)) {
                boolean got = caught.getOrDefault(name, 0) > 0;
                if (got && c.checklist.hideCaught) continue;
                AttributeShards.Shard shard = AttributeShards.byName(name);
                String colour = shard == null ? "§f" : shard.rarityColour();
                String text = critters ? " " + (got ? "§a✔ " : "§c✖ ") + colour + name
                    : "§7- (" + (got ? "§a✔" : "§c✖") + "§7) " + colour + name;
                out.add(new Row(Component.literal(text), shard));
            }
        }
        if (c.checklist.runDisplay == HuntingConfig.Checklist.ChecklistDisplay.ONLY_CURRENT) {
            for (Biome b : Biome.values()) if (!detailed.contains(b)) out.add(header(b, critters));
        }
        rows = out;
    }

    /** "Cavern - 3/9" (SkyHanni), or "Cavern: 3/9" in the Unique Critters style (green once the biome is done). */
    private static Row header(Biome b, boolean critters) {
        List<String> shards = SHARDS.get(b);
        long got = shards.stream().filter(name -> caught.getOrDefault(name, 0) > 0).count();
        String colour = got == shards.size() ? "§a" : "§c";
        if (critters) return new Row(Component.literal(b.formatted + "§7: " + colour + got + "/" + shards.size()), null);
        return new Row(Component.literal(b.formatted + " §7- " + colour + got + "/" + shards.size()), null);
    }

    /** A new world: a new Safari run. */
    public static void onWorldChange() {
        caught.clear();
        rows = List.of();
        biome = null;
        hideyhoNavigating = false;
        hideyhoRoute = null;
        beginNavigationAt = 0;
    }

    // ------------------------------------------------------------------------------------------------ hideyho

    private static void onChat(String text) {
        HuntingConfig.Safari c = config();
        if (c == null || !c.hideyhoFinder || !inSafari()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        switch (text) {
            case FOUND_ME -> {
                RemotePlayer hideyho = hideyhoNear(mc, mc.player.position(), 10);
                hideyhoStart = hideyho == null ? mc.player.position() : hideyho.position();
            }
            // Hideyho teleports first.
            case NO_PEEKING -> beginNavigationAt = System.currentTimeMillis() + 2_000L;
            case FOUND_AFTER_HIDING -> {
                if (hideyhoNavigating) finishHideyho();
            }
            default -> {}
        }
    }

    private static void beginHideyhoNavigation(Minecraft mc) {
        IslandGraph graph = IslandGraph.get("SAFARI");
        if (graph == null || hideyhoStart == null || mc.player == null) return;
        List<Integer> spots = graph.withTag("hideyho");
        // He never hides where he was just found.
        Integer closest = null;
        for (int spot : spots) if (closest == null || graph.pos[spot].distanceToSqr(hideyhoStart) < graph.pos[closest].distanceToSqr(hideyhoStart)) closest = spot;
        spots.remove(closest);
        if (spots.isEmpty()) return;
        hideyhoRoute = graph.route(spots, graph.nearest(mc.player.position()));
        hideyhoIndex = 0;
        hideyhoNavigating = true;
        nextHideyhoSpot(graph);
    }

    private static void nextHideyhoSpot(IslandGraph graph) {
        if (!hideyhoNavigating) return;
        if (hideyhoIndex >= hideyhoRoute.size()) {
            Sky2MAlerts.chat(Component.literal("Could not find any hidden Hideyho, maybe someone else already found it.").withStyle(ChatFormatting.YELLOW));
            hideyhoNavigating = false;
            return;
        }
        int goal = hideyhoRoute.get(hideyhoIndex++);
        IslandGraph.navigate(graph, goal, "§5Hideyho Location", 0xAA00AA, () -> {
            HuntingConfig.Safari c = config();
            return hideyhoNavigating && c != null && c.hideyhoFinder && inSafari();
        }, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (hideyhoNear(mc, graph.pos[goal], 5) != null) finishHideyho();
            else nextHideyhoSpot(graph);
        });
    }

    private static void finishHideyho() {
        Sky2MAlerts.chat(Component.literal("Found Hideyho!").withStyle(ChatFormatting.GREEN));
        hideyhoNavigating = false;
        IslandGraph.stopNavigation();
    }

    /** Hideyho (a player with his skin) within range of a spot, that you can see. */
    private static RemotePlayer hideyhoNear(Minecraft mc, Vec3 at, double range) {
        if (mc.level == null || mc.player == null) return null;
        for (RemotePlayer player : mc.level.getEntitiesOfClass(RemotePlayer.class, new AABB(at, at).inflate(range))) {
            if (player.position().distanceTo(at) > range || !mc.player.hasLineOfSight(player)) continue;
            for (Property property : player.getGameProfile().properties().get("textures")) {
                try {
                    if (new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8).contains(HIDEYHO_SKIN)) return player;
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ critter capsules

    /** Critter Capsule hiding, from Sky2MHideFireballsMixin: true hides the entity. */
    public static boolean hide(Entity entity) {
        if (!(entity instanceof Display.ItemDisplay) && !(entity instanceof ItemEntity)) return false;
        HuntingConfig.Safari c = config();
        if (c == null || !inSafari()) return false;
        HuntingConfig.CritterCapsules capsules = c.critterCapsules;
        if (entity instanceof ItemEntity item) {
            return capsules.hideOnGround && Compat.neuName(item.getItem()).equals("CRITTER_CAPSULE");
        }
        if (capsules.flyingMode == HuntingConfig.CritterCapsules.FlyingMode.NEVER) return false;
        ItemStack stack = ((Display.ItemDisplay) entity).getItemStack();
        String id = Compat.neuName(stack);
        if (!id.equals("CRITTER_CAPSULE") && !id.equals("MASTERFUL_CRITTER_CAPSULE")) return false;
        // The camera, not the player: the capsule blocks the view at eye height.
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        double distance = capsules.closeDistance;
        boolean close = entity.distanceToSqr(camera) <= distance * distance;
        // Masterful capsules are rare enough that players want to watch them, so they're only hidden up close.
        if (id.equals("MASTERFUL_CRITTER_CAPSULE")) return close;
        return close || capsules.flyingMode == HuntingConfig.CritterCapsules.FlyingMode.ALWAYS;
    }
}
