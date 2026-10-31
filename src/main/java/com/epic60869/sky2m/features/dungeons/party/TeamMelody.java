package com.epic60869.sky2m.features.dungeons.party;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Melody;
import com.epic60869.sky2m.features.dungeons.party.PartyDungeonState.Source;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Team Melody HUD, from Killer560's Mod (MIT, https://github.com/Killer560-OG/killer560s-mod, melody/): during F7/M7
 * P3, each teammate's Melody terminal progress, from their Sky2M (Party Dungeon Data) or the "Melody 2/4" lines Odin and NoammAddons users send in party chat. Your own progress is read
 * off your own open Melody terminal: which row's button is lit (green terracotta). That's only reading it; nothing
 * here ever clicks. Optionally your progress goes to party chat in the same "Melody 2/4 - 50%" form, like Odin.
 */
public final class TeamMelody {
    private static final String TITLE = "Click the button on time!";
    private static final Pattern PARTY_MELODY = Pattern.compile("^Party > (?:\\[[^\\]]{1,24}] )?(\\w{1,16}): Melody \\(?(\\d)/(\\d)\\)?.*$");
    private static final String GOLDOR = "[BOSS] Goldor: Who dares trespass into my domain?";
    private static final String CORE = "The Core entrance is opening!";

    private static boolean inP3, open;
    private static int lastRow = -1, lastSentDone, target = -1, current = -1;

    private TeamMelody() {}

    static FeatureConfigs.TeamMelody config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.teamMelody;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc));
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        Sky2MHuds.register("team_melody", "Team Melody", () -> config() != null && config().hud, TeamMelody::lines,
            List.of(Component.literal("Team Melody").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                Component.literal("§fSteve §7██████§8████ §a2/4"), Component.literal("§fAlex §a✔ Done")), 10, 300);
    }

    private static void onChat(String text) {
        if (text.equals(GOLDOR)) inP3 = true;
        else if (text.equals(CORE)) inP3 = false;
        Matcher m = PARTY_MELODY.matcher(text);
        if (m.matches() && Sky2MLocation.inDungeon()) {
            String name = m.group(1);
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && name.equalsIgnoreCase(mc.player.getGameProfile().name())) return;
            int done = Integer.parseInt(m.group(2)), rows = Integer.parseInt(m.group(3));
            if (rows >= 3 && rows <= 4 && done <= rows) {
                PartyDungeonState.offerMelody(name, done + 1, rows, done >= rows, Source.CHAT);
                // NoammAddons' Melody Display: "Steve has 2/3 melody" on screen with a sound.
                FeatureConfigs.TeamMelody c = config();
                if (c != null && c.alert) {
                    com.epic60869.sky2m.features.core.Sky2MAlerts.title(Component.empty(), Component.literal(name).withStyle(ChatFormatting.YELLOW)
                        .append(Component.literal(" has ").withStyle(ChatFormatting.GRAY))
                        .append(Component.literal(done + "/" + rows).withStyle(ChatFormatting.LIGHT_PURPLE))
                        .append(Component.literal(" melody").withStyle(ChatFormatting.GRAY)), false);
                    com.epic60869.sky2m.features.core.Sky2MAlerts.play(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1f);
                }
            }
        }
        // Your own Melody is done when you complete the terminal you have open.
        Minecraft mc = Minecraft.getInstance();
        if (open && mc.player != null && text.startsWith(mc.player.getGameProfile().name() + " completed a terminal!")) {
            PartyDungeonState.offerMelody(mc.player.getGameProfile().name(), lastRow < 0 ? 4 : lastRow, Math.max(3, lastRow), true, Source.SELF);
        }
    }

    private static void tick(Minecraft mc) {
        if (!Sky2MLocation.inDungeon()) inP3 = false;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || !Sky2MLocation.strip(screen.getTitle().getString()).trim().equals(TITLE)) {
            if (open) {
                open = false;
                lastRow = -1;
                lastSentDone = 0;
            }
            return;
        }
        if (!open) {
            // Odin's Melody Send Coords: where you are, so the party knows which Melody is being done.
            FeatureConfigs.TeamMelody c = config();
            if (c != null && c.sendCoords && inP3 && mc.player != null && mc.getConnection() != null) {
                mc.getConnection().sendCommand("pc x: " + mc.player.getBlockX() + ", y: " + mc.player.getBlockY() + ", z: " + mc.player.getBlockZ());
            }
        }
        open = true;
        List<ItemStack> items = screen.getMenu().getItems();
        int size = Math.max(0, items.size() - 36);
        // The buttons are the terracotta in column 7 of the band (rows 1 to 4, or 1 to 3 since the 2026 update).
        // The moving green pane and the purple target, as columns 0 to 4 of the track (Odin's numbering).
        target = current = -1;
        for (int i = 0; i < size; i++) {
            ItemStack stack = items.get(i);
            if (stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.LIME))) current = i % 9 - 1;
            else if (stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.MAGENTA)) || stack.is(Items.STAINED_GLASS_PANE.pick(DyeColor.PURPLE))) target = i % 9 - 1;
        }
        int rows = 0, lit = -1;
        for (int row = 1; row * 9 + 7 < size && row <= 4; row++) {
            ItemStack button = items.get(row * 9 + 7);
            if (!isTerracotta(button)) continue;
            rows++;
            if (button.is(Items.DYED_TERRACOTTA.pick(DyeColor.LIME))) lit = row;
        }
        if (rows < 3 || lit < 0 || lit == lastRow || mc.player == null) return;
        lastRow = lit;
        PartyDungeonState.offerMelody(mc.player.getGameProfile().name(), lit, rows, false, Source.SELF);
        FeatureConfigs.TeamMelody c = config();
        int done = lit - 1;
        if (c != null && c.sendProgress && inP3 && done > lastSentDone && done < rows && mc.getConnection() != null) {
            lastSentDone = done;
            // No trailing ")": NoammAddons ignores progress lines ending in one.
            mc.getConnection().sendCommand("pc Melody " + done + "/" + rows + " - " + Math.round(done * 100f / rows) + "%");
        }
    }

    private static boolean isTerracotta(ItemStack stack) {
        for (DyeColor colour : DyeColor.values()) if (stack.is(Items.DYED_TERRACOTTA.pick(colour))) return true;
        return stack.is(Items.TERRACOTTA);
    }

    /** Whether the HUD should show: in F7/M7 P3 (or with your own Melody open). */
    private static boolean active() {
        String floor = Sky2MLocation.dungeonFloor();
        return (floor.equals("F7") || floor.equals("M7")) && (inP3 || open);
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        FeatureConfigs.TeamMelody c = config();
        if (c == null || !active()) return out;
        Minecraft mc = Minecraft.getInstance();
        String self = mc.player == null ? "" : mc.player.getGameProfile().name().toLowerCase(Locale.ROOT);
        Map<String, PartyDungeonState.Fact<Melody>> all = PartyDungeonState.melody();
        for (Map.Entry<String, PartyDungeonState.Fact<Melody>> e : all.entrySet()) {
            if (e.getKey().equals(self) && !c.showSelf) continue;
            if (!e.getKey().equals(self) && !PartyDungeonData.inParty(e.getValue().reporter())) continue;
            Melody m = e.getValue().value();
            String name = e.getValue().reporter() == null ? e.getKey() : e.getValue().reporter();
            if (m.done()) {
                out.add(Component.literal("§f" + name + " §a✔ Done"));
                continue;
            }
            int done = Math.max(0, m.row() - 1), rows = m.rows();
            int filled = Math.round(10f * done / rows);
            out.add(Component.literal("§f" + name + " §d" + "█".repeat(filled) + "§8" + "█".repeat(10 - filled) + " §a" + done + "/" + rows));
        }
        if (out.isEmpty()) return out;
        out.addFirst(Component.literal("Team Melody").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        return out;
    }
}
