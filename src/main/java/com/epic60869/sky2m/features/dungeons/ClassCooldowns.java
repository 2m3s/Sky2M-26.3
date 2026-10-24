package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dungeons > Run Recap > Class Cooldowns: a HUD with your dungeon class and the cooldowns that can be read reliably.
 * <ul>
 *   <li>Bonzo's Mask (3s invulnerability, 180s cooldown), Spirit Mask (3s, 30s) and Phoenix Pet (60s cooldown): started
 *   by the chat line each prints when it saves you ("Your Bonzo's Mask saved your life!", "Second Wind Activated!
 *   Your Spirit Mask saved your life!", "Your Phoenix Pet saved you from certain death!").</li>
 *   <li>Your class ultimate (Wish, Thunderstorm, Throwing Axe, Rapid Fire, Castle of Stone): only how long ago you used
 *   it, because its cooldown changes with your upgrades. Detected from its name in the action bar or in a chat line
 *   starting with "Used" or "You used".</li>
 * </ul>
 * Wither Impact and Implosion have no cooldown (only mana), so they aren't listed.
 */
final class ClassCooldowns {
    private record Item(String label, String chat, long cooldownMs, long invulnMs) {}

    private static final List<Item> ITEMS = List.of(
        new Item("Bonzo's Mask", "Bonzo's Mask saved your life", 180_000, 3_000),
        new Item("Spirit Mask", "Spirit Mask saved your life", 30_000, 3_000),
        new Item("Phoenix Pet", "Phoenix Pet saved you from certain death", 60_000, 0));

    private static final Map<String, Long> usedAt = new LinkedHashMap<>();
    private static final Map<String, Boolean> alerted = new LinkedHashMap<>();
    private static long ultimateAt;
    private static String ultimateName;

    private ClassCooldowns() {}

    private static String ultimateFor(DungeonClass dc) {
        return switch (dc) {
            case HEALER -> "Wish";
            case MAGE -> "Thunderstorm";
            case BERSERK -> "Throwing Axe";
            case ARCHER -> "Rapid Fire";
            case TANK -> "Castle of Stone";
            default -> null;
        };
    }

    static void init() {
        Sky2MChat.onChat(message -> onText(message.text(), false));
        Sky2MChat.onActionBar(message -> onText(message.text(), true));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            DungeonRecapExtras.Config c = DungeonRecapExtras.config();
            if (c == null || !c.classCooldowns.enabled || !c.classCooldowns.readyAlert || usedAt.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Item item : ITEMS) {
                Long at = usedAt.get(item.label());
                if (at == null || now - at < item.cooldownMs() || Boolean.TRUE.equals(alerted.get(item.label()))) continue;
                alerted.put(item.label(), true);
                Sky2MAlerts.ding();
                Sky2MAlerts.chat(Component.literal(item.label() + " is ready.").withStyle(ChatFormatting.GREEN));
            }
        });
        Sky2MHuds.register("class_cooldowns", "Class Cooldowns", () -> {
                DungeonRecapExtras.Config c = DungeonRecapExtras.config();
                return c != null && c.classCooldowns.enabled && Sky2MLocation.inDungeon();
            }, ClassCooldowns::lines,
            List.of(Component.literal("Mage cooldowns").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.literal("Bonzo's Mask: ").withStyle(ChatFormatting.GRAY).append(Component.literal("2m 31s").withStyle(ChatFormatting.RED)),
                Component.literal("Spirit Mask: ").withStyle(ChatFormatting.GRAY).append(Component.literal("Ready").withStyle(ChatFormatting.GREEN)),
                Component.literal("Thunderstorm: ").withStyle(ChatFormatting.GRAY).append(Component.literal("used 45s ago").withStyle(ChatFormatting.WHITE))),
            10, 260);
        Sky2MHuds.setting("class_cooldowns", () -> {
            DungeonRecapExtras.Config c = DungeonRecapExtras.config();
            return c != null && c.classCooldowns.enabled;
        });
    }

    private static void reset() {
        usedAt.clear();
        alerted.clear();
        ultimateAt = 0;
        ultimateName = null;
    }

    private static void onText(String text, boolean actionBar) {
        DungeonRecapExtras.Config c = DungeonRecapExtras.config();
        if (c == null || !c.classCooldowns.enabled || !Sky2MLocation.inDungeon()) return;
        if (text.equals("Starting in 1 second.")) {
            reset();
            return;
        }
        if (!actionBar && text.contains(": ")) return; // player chat, never a system line
        long now = System.currentTimeMillis();
        if (!actionBar) {
            for (Item item : ITEMS) {
                if (!text.contains(item.chat())) continue;
                usedAt.put(item.label(), now);
                alerted.put(item.label(), false);
                return;
            }
        }
        String ult = ultimateFor(SelfClass.get());
        if (ult != null && text.contains(ult) && (actionBar || text.startsWith("Used ") || text.startsWith("You used "))) {
            ultimateAt = now;
            ultimateName = ult;
        }
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        DungeonRecapExtras.Config c = DungeonRecapExtras.config();
        if (c == null) return out;
        DungeonClass dc = SelfClass.get();
        long now = System.currentTimeMillis();
        out.add(Component.literal(dc == DungeonClass.UNKNOWN ? "Cooldowns" : dc.displayName() + " cooldowns").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        for (Item item : ITEMS) {
            Long at = usedAt.get(item.label());
            if (at == null) continue;
            long since = now - at;
            Component value;
            if (since < item.invulnMs()) value = Component.literal("Invulnerable " + seconds(item.invulnMs() - since)).withStyle(ChatFormatting.GOLD);
            else if (since < item.cooldownMs()) value = Component.literal(clock(item.cooldownMs() - since)).withStyle(ChatFormatting.RED);
            else if (c.classCooldowns.showReady) value = Component.literal("Ready").withStyle(ChatFormatting.GREEN);
            else continue;
            out.add(Component.literal(item.label() + ": ").withStyle(ChatFormatting.GRAY).append(value));
        }
        String ult = ultimateFor(dc);
        if (ult != null && ultimateName != null && ult.equals(ultimateName)) {
            out.add(Component.literal(ult + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("used " + clock(now - ultimateAt) + " ago").withStyle(ChatFormatting.WHITE)));
        }
        return out.size() == 1 ? new ArrayList<>() : out;
    }

    private static String seconds(long ms) {
        return String.format(Locale.US, "%.1fs", Math.max(0, ms) / 1000.0);
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }
}
