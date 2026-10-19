package com.epic60869.sky2m.features.mining;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MTabWidgetManager;
import com.epic60869.sky2m.custom.util.ChromaColours;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mining Commissions "Highlight Event": outlines the targets of the Dwarven Mines event that is running.
 * <ul>
 *     <li>Goblin Raid: the raid goblins (Goblin, Weakling, Knifethrower, Fireslinger, Pitfighter, Murderlover,
 *     Creeperlobber, Superprotectron, Golden/Diamond Goblin), found by their nametag stands, and the golem defenders
 *     (iron golems, as in SkyHanni's HideFarEntities).</li>
 *     <li>Raffle: the Raffle Tickets lying on the ground ("Find tickets on the ground and bring them to the raffle
 *     box"). The ticket item is a name tag (NEU repo MINING_RAFFLE_TICKET), so dropped name tags and stands named or
 *     holding one are tickets.</li>
 * </ul>
 * The event comes from the scoreboard's "Event:" line, the TAB "Mining Event:" widget, or the event's own scoreboard
 * lines, the same sources SkyHanni's MiningEventsApi and CustomScoreboard read.
 */
public final class MiningEventHighlight {
    private enum Event { NONE, GOBLIN_RAID, RAFFLE }

    private static final String[] RAID_MOBS = {"Goblin", "Weakling", "Knifethrower", "Fireslinger", "Pitfighter",
        "Murderlover", "Creeperlobber", "Superprotectron", "Golem"};

    /** Entity id -> glow colour, rebuilt twice a second; the ticket boxes come from the same scan. */
    private static Map<Integer, Integer> glow = Map.of();
    private static List<Entity> tickets = List.of();
    private static int ticks;

    private MiningEventHighlight() {}

    private static Sky2MConfig.MiningCommissions config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.mining.commissions;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(MiningEventHighlight::tick);
        EntityGlow.register(entity -> glow.isEmpty() ? -1 : glow.getOrDefault(entity.getId(), -1));
        // Tickets are small and often behind blocks, so they also get a box drawn through walls.
        Sky2MWorldRender.register(collector -> {
            Sky2MConfig.MiningCommissions c = config();
            if (c == null || tickets.isEmpty()) return;
            int rgb = ChromaColours.parse(c.raffleTicketColour).getEffectiveColourRGB();
            float[] colour = {(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
            for (Entity ticket : tickets) {
                if (ticket.isRemoved()) continue;
                collector.submitFilledBox(ticket.getBoundingBox().inflate(0.15), colour, 0.4f, true);
            }
        });
    }

    private static void tick(Minecraft mc) {
        if (++ticks % 10 != 0) return;
        Sky2MConfig.MiningCommissions c = config();
        Event event = c != null && c.highlightEvent && mc.player != null && mc.level != null && Sky2MLocation.inDwarvenMines()
            ? currentEvent() : Event.NONE;
        if (event == Event.NONE) {
            glow = Map.of();
            tickets = List.of();
            return;
        }
        Map<Integer, Integer> out = new HashMap<>();
        List<Entity> found = new ArrayList<>();
        if (event == Event.GOBLIN_RAID) {
            raid(mc, out, ChromaColours.parse(c.goblinRaidColour).getEffectiveColourRGB() & 0xFFFFFF);
        } else {
            raffle(mc, out, found, ChromaColours.parse(c.raffleTicketColour).getEffectiveColourRGB() & 0xFFFFFF);
        }
        glow = out.isEmpty() ? Map.of() : out;
        tickets = found.isEmpty() ? List.of() : found;
    }

    /** The running Dwarven Mines event, if it is a Goblin Raid or a Raffle. */
    private static Event currentEvent() {
        StringBuilder text = new StringBuilder();
        String scoreboard = Sky2MLocation.scoreboardValue("Event: ");
        if (scoreboard != null) text.append(Sky2MLocation.strip(scoreboard)).append('\n');
        text.append(Sky2MTabWidgetManager.get("Mining Event").detail().getString()).append('\n');
        // The event's own sidebar lines, for when the "Event:" line is hidden or cut off.
        for (String line : Sky2MLocation.scoreboard()) text.append(Sky2MLocation.strip(line)).append('\n');
        String all = text.toString().toLowerCase(Locale.ROOT);
        if (all.contains("goblin raid") || all.contains("kill goblins!")) return Event.GOBLIN_RAID;
        if (all.contains("raffle")) return Event.RAFFLE;
        return Event.NONE;
    }

    private static void raid(Minecraft mc, Map<Integer, Integer> out, int colour) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof IronGolem) {
                out.put(entity.getId(), colour);
                continue;
            }
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            if (!isRaidMob(name)) continue;
            LivingEntity mob = mobUnder(mc, stand);
            if (mob != null) out.put(mob.getId(), colour);
        }
    }

    /** Raid mob nametags read like "[Lv50] Knifethrower 1.2k❤"; NPCs and players don't have the health heart. */
    private static boolean isRaidMob(String name) {
        if (!name.contains("❤")) return false;
        for (String mob : RAID_MOBS) if (name.contains(mob)) return true;
        return false;
    }

    /**
     * The mob a nametag stand belongs to: the nearest living entity just below it. Goblins are player-shaped NPCs,
     * so other players count too, but never you.
     */
    private static LivingEntity mobUnder(Minecraft mc, ArmorStand stand) {
        AABB box = stand.getBoundingBox().inflate(1.0, 0, 1.0).expandTowards(0, -3.5, 0);
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, box, e -> !(e instanceof ArmorStand) && e != mc.player)) {
            // Hypixel's NPC players have version 2 UUIDs; real players (version 4) are never raid mobs.
            if (e instanceof Player && e.getUUID().version() != 2) continue;
            double d = e.distanceToSqr(stand);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private static void raffle(Minecraft mc, Map<Integer, Integer> out, List<Entity> found, int colour) {
        for (Entity entity : mc.level.entitiesForRendering()) {
            boolean ticket;
            if (entity instanceof ItemEntity item) {
                ticket = isTicket(item.getItem());
            } else if (entity instanceof ArmorStand stand) {
                ticket = (stand.hasCustomName() && Sky2MLocation.strip(stand.getCustomName().getString()).contains("Raffle Ticket"))
                    || isTicket(stand.getItemBySlot(EquipmentSlot.HEAD)) || isTicket(stand.getItemBySlot(EquipmentSlot.MAINHAND));
            } else {
                ticket = false;
            }
            if (!ticket) continue;
            out.put(entity.getId(), colour);
            found.add(entity);
        }
    }

    private static boolean isTicket(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.NAME_TAG) || stack.getHoverName().getString().contains("Raffle Ticket"));
    }
}
