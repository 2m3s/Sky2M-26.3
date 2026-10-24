package com.epic60869.sky2m.features.mining.extras;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glacite Mineshaft helpers, ported from SkyHanni's glacitemineshaft package (hannibal002/SkyHanni, LGPL-2.1):
 * <ul>
 *     <li>Corpse finder: Lapis, Tungsten, Umber and Vanguard corpses (armor stands wearing their helmet) are marked
 *     once you have seen them (a clear line of sight for a few ticks, never through walls), and turn green once the
 *     tab list's Frozen Corpses widget or your loot says they are looted.</li>
 *     <li>Corpse profit: "LAPIS CORPSE LOOT!" in chat, valued minus the key that was consumed.</li>
 *     <li>Entrance and ladder waypoints, from where you arrive, and the 60 second cave-in countdown.</li>
 *     <li>Pity HUD from the tab list's Pity widget, and the pity in the mineshaft spawn message.</li>
 * </ul>
 */
public final class GlaciteMineshaft {
    private static final long CAVE_IN_MS = 60_000;
    private static final int SEEN_SCANS = 3;
    private static final Pattern CORPSE_TAB = Pattern.compile("^\\s*(Lapis|Tungsten|Umber|Vanguard): (NOT )?LOOTED\\s*$");
    private static final Pattern PITY_TAB = Pattern.compile("Glacite Mineshafts: ([\\d,]+)/([\\d,]+)");
    private static final Pattern LOOT_START = Pattern.compile("^\\s*(LAPIS|TUNGSTEN|UMBER|VANGUARD) CORPSE LOOT!\\s*$");
    private static final String RESOURCEFUL = "LUCKY! Your Resourceful perk saved your key from being consumed!";

    private enum Corpse {
        LAPIS("Lapis", "LAPIS_ARMOR_HELMET", null, ChatFormatting.BLUE, 0x5555FF),
        TUNGSTEN("Tungsten", "MINERAL_HELMET", "TUNGSTEN_KEY", ChatFormatting.GRAY, 0xAAAAAA),
        UMBER("Umber", "ARMOR_OF_YOG_HELMET", "UMBER_KEY", ChatFormatting.GOLD, 0xFFAA00),
        VANGUARD("Vanguard", "VANGUARD_HELMET", "SKELETON_KEY", ChatFormatting.WHITE, 0xFFFFFF);

        final String label, helmet, key;
        final ChatFormatting chat;
        final int colour;

        Corpse(String label, String helmet, String key, ChatFormatting chat, int colour) {
            this.label = label;
            this.helmet = helmet;
            this.key = key;
            this.chat = chat;
            this.colour = colour;
        }

        static Corpse ofHelmet(String id) {
            for (Corpse c : values()) if (c.helmet.equals(id)) return c;
            return null;
        }

        static Corpse ofLabel(String label) {
            for (Corpse c : values()) if (c.label.equalsIgnoreCase(label)) return c;
            return null;
        }
    }

    private static final class Seen {
        final Corpse type;
        BlockPos pos;
        int scans;
        boolean found, looted;

        Seen(Corpse type, BlockPos pos) {
            this.type = type;
            this.pos = pos;
        }
    }

    private static final Map<UUID, Seen> CORPSES = new LinkedHashMap<>();
    private static BlockPos entrance, ladder;
    private static long enteredAt;
    private static int ticks;
    private static int pity = -1, pityMax = 2000;
    private static long pitySeen;
    private static int lastPity = -1;
    private static Corpse lootCorpse;
    private static boolean inLoot, keyConsumed = true;
    private static final List<ExtrasUtil.Loot> LOOT = new ArrayList<>();
    private static double sessionProfit;
    private static int sessionCorpses;

    private GlaciteMineshaft() {}

    private static MiningExtrasConfig.GlaciteMineshaft config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.glaciteMineshaft;
    }

    public static void init() {
        Sky2MLocation.onAreaChange(area -> {
            CORPSES.clear();
            entrance = null;
            ladder = null;
            enteredAt = 0;
            inLoot = false;
            keyConsumed = true;
            if (area.equals("Mineshaft")) arrived();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.player == null || mc.level == null) return;
            ticks++;
            if (Sky2MLocation.inMineshaft() && ticks % 5 == 0) scanCorpses(mc);
            if (Sky2MLocation.inMineshaft() && ticks % 20 == 0) readCorpseTab();
            if (ticks % 20 == 0 && (Sky2MLocation.inDwarvenMines() || Sky2MLocation.inMineshaft())) readPity();
        });
        Sky2MChat.onChat(message -> onChat(message.text()));
        Sky2MWorldRender.register(collector -> {
            MiningExtrasConfig.GlaciteMineshaft c = config();
            Minecraft mc = Minecraft.getInstance();
            if (c == null || mc.player == null || !Sky2MLocation.inMineshaft()) return;
            if (c.corpseFinder) {
                for (Seen s : CORPSES.values()) {
                    if (!s.found) continue;
                    int colour = s.looted ? 0x55FF55 : s.type.colour;
                    label(collector, s.pos, s.type.label + " Corpse" + (s.looted ? " (looted)" : ""), colour, mc);
                }
            }
            if (c.entranceWaypoint && entrance != null) label(collector, entrance, "Entrance", 0xFFFF55, mc);
            if (c.ladderWaypoint && ladder != null) label(collector, ladder, "Ladder", 0xFFFF55, mc);
        });

        Sky2MHuds.setting("mineshaft_cave_in", () -> config() != null && config().caveInTimer);
        Sky2MHuds.register("mineshaft_cave_in", "Mineshaft Cave-In Timer",
            () -> config() != null && config().caveInTimer && enteredAt > 0 && Sky2MLocation.inMineshaft(),
            GlaciteMineshaft::caveInLines,
            List.of(kv("Entrance caves in: ", "0:42", ChatFormatting.GREEN)),
            150, 440);
        Sky2MHuds.setting("mineshaft_pity", () -> config() != null && config().pityHud);
        Sky2MHuds.register("mineshaft_pity", "Mineshaft Pity",
            () -> config() != null && config().pityHud && pity >= 0 && System.currentTimeMillis() - pitySeen < 15_000,
            GlaciteMineshaft::pityLines,
            List.of(kv("Mineshaft Pity: ", "124/2,000", ChatFormatting.AQUA), kv("Left: ", "1,876", ChatFormatting.GRAY)),
            150, 400);
    }

    // ------------------------------------------------------------------------------------------ arrival waypoints

    private static void arrived() {
        Minecraft mc = Minecraft.getInstance();
        enteredAt = System.currentTimeMillis();
        if (mc.player == null) return;
        // The block you stand on, and the ladder shaft 7 blocks ahead, 2 to the right of it and 15 down (SkyHanni).
        entrance = mc.player.blockPosition().below();
        Direction facing = mc.player.getDirection();
        int dx = facing.getStepX(), dz = facing.getStepZ();
        ladder = entrance.offset(dx * 7 - dz * 2, -15, dz * 7 + dx * 2);
    }

    private static void label(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector, BlockPos pos, String name, int colour, Minecraft mc) {
        float[] rgb = {(colour >> 16 & 255) / 255f, (colour >> 8 & 255) / 255f, (colour & 255) / 255f};
        collector.submitFilledBox(pos, rgb, 0.45f, true);
        Vec3 centre = Vec3.atCenterOf(pos).add(0, 1.2, 0);
        collector.submitText(Component.literal(name).withColor(colour), centre, 1.6f, true);
        double distance = mc.player.position().distanceTo(Vec3.atCenterOf(pos));
        collector.submitText(Component.literal(Math.round(distance) + "m").withStyle(ChatFormatting.YELLOW), centre.add(0, -0.5, 0), 1.2f, true);
    }

    // ------------------------------------------------------------------------------------------ corpse finder

    private static void scanCorpses(Minecraft mc) {
        MiningExtrasConfig.GlaciteMineshaft c = config();
        if (c == null || !c.corpseFinder) return;
        Vec3 eye = mc.player.getEyePosition();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand)) continue;
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty()) continue;
            Corpse type = Corpse.ofHelmet(Compat.neuName(head));
            if (type == null) continue;
            Seen seen = CORPSES.computeIfAbsent(stand.getUUID(), id -> new Seen(type, stand.blockPosition().above()));
            if (seen.found) continue;
            // Only corpses you have a clear view of count as found: the stands load through walls.
            Vec3 target = stand.position().add(0, 1.2, 0);
            var hit = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS && eye.distanceTo(target) < 60) seen.scans++;
            else seen.scans = 0;
            if (seen.scans >= SEEN_SCANS) {
                seen.found = true;
                seen.pos = stand.blockPosition().above();
                Sky2MAlerts.chat(Component.literal("Found a ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(type.label + " Corpse").withStyle(type.chat))
                    .append(Component.literal(" and marked its location.").withStyle(ChatFormatting.YELLOW)));
            }
        }
    }

    /** The Frozen Corpses tab widget lists every corpse of the shaft as "Lapis: NOT LOOTED" / "Lapis: LOOTED". */
    private static void readCorpseTab() {
        MiningExtrasConfig.GlaciteMineshaft c = config();
        if (c == null || !c.corpseFinder) return;
        for (String line : ExtrasUtil.tabLines()) {
            Matcher m = CORPSE_TAB.matcher(line);
            if (!m.matches() || m.group(2) != null) continue;
            Corpse type = Corpse.ofLabel(m.group(1));
            if (type == null) continue;
            for (Seen s : CORPSES.values()) if (s.type == type) s.looted = true;
        }
    }

    // ------------------------------------------------------------------------------------------ pity

    private static void readPity() {
        MiningExtrasConfig.GlaciteMineshaft c = config();
        if (c == null || !(c.pityHud || c.pityChat)) return;
        for (String line : ExtrasUtil.tabLines()) {
            Matcher m = PITY_TAB.matcher(line);
            if (!m.find()) continue;
            try {
                pity = Integer.parseInt(m.group(1).replace(",", ""));
                pityMax = Integer.parseInt(m.group(2).replace(",", ""));
                pitySeen = System.currentTimeMillis();
                lastPity = pity;
            } catch (NumberFormatException ignored) {}
            return;
        }
    }

    private static List<Component> pityLines() {
        return List.of(kv("Mineshaft Pity: ", String.format(Locale.US, "%,d/%,d", pity, pityMax), ChatFormatting.AQUA),
            kv("Left: ", String.format(Locale.US, "%,d", Math.max(0, pityMax - pity)), ChatFormatting.GRAY));
    }

    private static List<Component> caveInLines() {
        long left = CAVE_IN_MS - (System.currentTimeMillis() - enteredAt);
        if (left <= 0) return List.of(Component.literal("Entrance caved in!").withStyle(ChatFormatting.RED));
        long s = (left + 999) / 1000;
        ChatFormatting colour = s > 30 ? ChatFormatting.GREEN : s > 10 ? ChatFormatting.YELLOW : ChatFormatting.RED;
        return List.of(kv("Entrance caves in: ", String.format(Locale.US, "%d:%02d", s / 60, s % 60), colour));
    }

    private static Component kv(String key, String value, ChatFormatting colour) {
        return Component.literal(key).withStyle(ChatFormatting.WHITE).append(Component.literal(value).withStyle(colour));
    }

    // ------------------------------------------------------------------------------------------ chat

    private static void onChat(String text) {
        MiningExtrasConfig.GlaciteMineshaft c = config();
        if (c == null) return;
        if (c.pityChat && text.contains("Glacite Mineshaft portal") && lastPity >= 0 && Sky2MLocation.inDwarvenMines()) {
            Sky2MAlerts.chat(Component.literal("Mineshaft spawned at pity ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(String.format(Locale.US, "%,d/%,d", lastPity, pityMax)).withStyle(ChatFormatting.AQUA)));
            return;
        }
        if (!c.corpseProfit && !c.corpseFinder) return;
        if (!Sky2MLocation.inMineshaft()) return;
        if (text.trim().equals(RESOURCEFUL)) {
            keyConsumed = false;
            return;
        }
        Matcher start = LOOT_START.matcher(text);
        if (start.matches()) {
            inLoot = true;
            lootCorpse = Corpse.ofLabel(start.group(1));
            LOOT.clear();
            return;
        }
        if (!inLoot) return;
        if (ExtrasUtil.DIVIDER.matcher(text).matches()) {
            inLoot = false;
            finishLoot(c);
            return;
        }
        ExtrasUtil.Loot item = ExtrasUtil.parseLoot(text);
        if (item != null) LOOT.add(item);
    }

    private static void finishLoot(MiningExtrasConfig.GlaciteMineshaft c) {
        Corpse type = lootCorpse;
        boolean consumed = keyConsumed;
        keyConsumed = true;
        lootCorpse = null;
        if (type == null) return;
        Minecraft mc = Minecraft.getInstance();
        // The corpse you just looted is the nearest marked one.
        Seen nearest = null;
        double best = 36;
        for (Seen s : CORPSES.values()) {
            if (s.type != type || mc.player == null) continue;
            double d = mc.player.position().distanceToSqr(Vec3.atCenterOf(s.pos));
            if (d < best) {
                best = d;
                nearest = s;
            }
        }
        if (nearest != null) nearest.looted = true;
        if (!c.corpseProfit) return;

        double total = 0;
        List<Component> hover = new ArrayList<>();
        for (ExtrasUtil.Loot item : LOOT) {
            double price = ExtrasUtil.priceByName(item.name());
            if (price < 0) {
                hover.add(Component.literal(item.amount() + "x " + item.name() + " (no price)").withStyle(ChatFormatting.GRAY));
                continue;
            }
            total += price * item.amount();
            hover.add(Component.literal(item.amount() + "x " + item.name() + ": ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(ExtrasUtil.coins(price * item.amount())).withStyle(ChatFormatting.GOLD)));
        }
        if (type.key != null && consumed) {
            double cost = ExtrasUtil.costById(type.key);
            total -= cost;
            hover.add(Component.literal("Key cost: -" + ExtrasUtil.coins(cost)).withStyle(ChatFormatting.RED));
        } else if (type.key != null) {
            hover.add(Component.literal("Key saved by Resourceful").withStyle(ChatFormatting.GREEN));
        }
        sessionProfit += total;
        sessionCorpses++;
        hover.add(Component.empty());
        hover.add(Component.literal("Session: " + ExtrasUtil.coins(sessionProfit) + " from " + sessionCorpses + " corpses").withStyle(ChatFormatting.GRAY));

        MutableComponent hoverText = Component.empty();
        for (int i = 0; i < hover.size(); i++) {
            if (i > 0) hoverText.append(Component.literal("\n"));
            hoverText.append(hover.get(i));
        }
        MutableComponent message = Component.literal("Profit for ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(type.label + " Corpse").withStyle(type.chat))
            .append(Component.literal(": ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(ExtrasUtil.coins(total)).withStyle(total < 0 ? ChatFormatting.RED : ChatFormatting.GOLD))
            .withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(hoverText)));
        Sky2MAlerts.chat(message);
    }
}
