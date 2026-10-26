package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/** /s2 debug: prints what Sky2M detects, to diagnose features that do not show. */
public final class Sky2MDebug {
    private Sky2MDebug() {}

    private static boolean roomDebug;

    /**
     * Whether /s2 debug is on: the dungeon room debug lines (a chat line when the Dungeon Sim room under you changes),
     * and the slayer tracker, RNG HUD and loadout lines in the log.
     */
    public static boolean roomDebug() { return roomDebug; }

    public static void roomLine(String text) { line("Debug", text); }

    public static int run() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        roomDebug = !roomDebug;
        line("Room debug lines", roomDebug ? "on (run /s2 debug again to turn off)" : "off");
        Sky2MConfig config = Sky2MConfig.current();

        line("Server brand", mc.getConnection() == null ? "none" : String.valueOf(mc.getConnection().serverBrand()));
        line("Server address", mc.getCurrentServer() == null ? "none" : mc.getCurrentServer().ip);
        line("Hypixel detected", String.valueOf(Compat.isOnSkyblock()));
        line("SkyBlock scoreboard", String.valueOf(Sky2MLocation.onSkyblock()));
        line("Area", Sky2MLocation.area());
        line("Location", Sky2MLocation.location());
        line("Dungeon floor", Sky2MLocation.dungeonFloor());
        line("Sidebar title", Sky2MLocation.scoreboardTitle());
        String catacombs = "";
        for (String sidebarLine : Sky2MLocation.scoreboard()) {
            if (sidebarLine.contains("Catacombs") || sidebarLine.contains("⏣")) {
                catacombs = sidebarLine;
                break;
            }
        }
        line("Sidebar location line", catacombs);
        if (config != null) {
            line("Item rarity enabled", String.valueOf(config.inventory.itemRarity.enabled));
            line("Calendar enabled", String.valueOf(config.inventory.calendarTimeToRealTime));
        }

        ItemStack held = mc.player.getMainHandItem();
        if (!held.isEmpty()) {
            line("Held item", held.getHoverName().getString());
            line("Held rarity", Sky2MItemBackgrounds.rarity(held).name());
            ItemLore lore = held.get(DataComponents.LORE);
            if (lore != null && !lore.lines().isEmpty()) {
                line("Last lore line", lore.lines().getLast().getString());
            }
        }
        return 1;
    }

    private static void line(String key, String value) {
        Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get()
            .append(Component.literal(key + ": ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(value == null || value.isEmpty() ? "-" : value).withStyle(ChatFormatting.WHITE)));
    }
}
