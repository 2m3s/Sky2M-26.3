package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.Sky2MConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.charset.StandardCharsets;

/**
 * Settings cloud sync: /s2 settings upload|download|list|delete [slot] and the buttons in the config. The whole
 * Sky2M config (sky2m-mod.json) is saved to a slot on the mod server (up to 5 slots, 64 KB each) and can be
 * loaded back on any computer. Needs the server login.
 */
public final class SbcSettingsSync {
    private static final int MAX_BYTES = 64 * 1024;

    private SbcSettingsSync() {}

    private static String slot(String slot) {
        String s = slot == null || slot.isBlank() ? Sbc.config().settingsSync.slot : slot;
        s = s == null ? "" : s.trim();
        return s.isEmpty() ? "default" : s.substring(0, Math.min(32, s.length()));
    }

    public static void upload(String slotName) {
        if (!Flags.check("settingsSync")) return;
        String slot = slot(slotName);
        JsonObject settings = Sky2MConfig.exportJson();
        if (settings == null) {
            Sbc.error("Couldn't read your settings.");
            return;
        }
        if (settings.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            Sbc.error("Your settings are too big to upload (over 64 KB).");
            return;
        }
        JsonObject p = Sbc.packet("settingsSave");
        p.addProperty("slot", slot);
        p.add("settings", settings);
        if (SbcNet.sendAuthed(p)) Sbc.say(Component.literal("Uploading your settings to \"" + slot + "\"...").withStyle(ChatFormatting.GRAY));
    }

    public static void download(String slotName) {
        if (!Flags.check("settingsSync")) return;
        String slot = slot(slotName);
        JsonObject p = Sbc.packet("settingsLoad");
        p.addProperty("slot", slot);
        if (SbcNet.sendAuthed(p)) Sbc.say(Component.literal("Downloading your settings from \"" + slot + "\"...").withStyle(ChatFormatting.GRAY));
    }

    public static void list() {
        if (!Flags.check("settingsSync")) return;
        SbcNet.sendAuthed(Sbc.packet("settingsList"));
    }

    public static void delete(String slotName) {
        if (!Flags.check("settingsSync")) return;
        JsonObject p = Sbc.packet("settingsDelete");
        p.addProperty("slot", slot(slotName));
        SbcNet.sendAuthed(p);
    }

    static void handle(String type, JsonObject packet) {
        switch (type) {
            case "settingsSaved" -> Sbc.say(Component.literal("Settings saved to \"" + Sbc.str(packet, "slot") + "\".").withStyle(ChatFormatting.GREEN));
            case "settings" -> {
                String slot = Sbc.str(packet, "slot");
                if (!Sbc.hasObj(packet, "settings")) {
                    Sbc.error("There are no settings saved in \"" + slot + "\".");
                    return;
                }
                if (Sky2MConfig.importJson(packet.getAsJsonObject("settings"))) {
                    String from = Sbc.str(packet, "modVersion");
                    Sbc.say(Component.literal("Loaded your settings from \"" + slot + "\"" + (from.isEmpty() ? "" : " (saved with Sky2M " + from + ")")
                        + ". Your old settings are in sky2m-mod.json.bak.").withStyle(ChatFormatting.GREEN));
                } else {
                    Sbc.error("Those settings couldn't be loaded; nothing was changed.");
                }
            }
            case "settingsSlots" -> showSlots(packet);
            case "settingsDeleted" -> Sbc.say(Component.literal("Deleted the settings in \"" + Sbc.str(packet, "slot") + "\".").withStyle(ChatFormatting.YELLOW));
            case "settingsError" -> Sbc.error(Sbc.str(packet, "message").isEmpty() ? "Settings sync failed (" + Sbc.str(packet, "code") + ")." : Sbc.str(packet, "message"));
            default -> {}
        }
    }

    private static void showSlots(JsonObject packet) {
        var slots = Sbc.arr(packet, "slots");
        if (slots.isEmpty()) {
            Sbc.say(Component.literal("You have no saved settings. Save them with /s2 settings upload [slot].").withStyle(ChatFormatting.GRAY));
            return;
        }
        MutableComponent out = Component.literal("Your saved settings:").withStyle(ChatFormatting.YELLOW);
        for (JsonElement e : slots) {
            String slot;
            long at = 0;
            long size = 0;
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                slot = Sbc.str(o, "slot");
                at = Sbc.lng(o, "updatedAt", 0);
                size = Sbc.lng(o, "size", 0);
            } else {
                slot = e.getAsString();
            }
            out.append(Component.literal("\n  " + slot).withStyle(ChatFormatting.WHITE));
            if (at > 0) out.append(Component.literal(" (" + Sbc.ago(at) + (size > 0 ? ", " + size / 1024 + " KB" : "") + ")").withStyle(ChatFormatting.DARK_GRAY));
            out.append(button(" [Download]", ChatFormatting.GREEN, "/s2 settings download " + slot, "Replace your settings with this slot"));
            out.append(button(" [Delete]", ChatFormatting.RED, "/s2 settings delete " + slot, "Delete this slot"));
        }
        Sbc.say(out);
    }

    static MutableComponent button(String text, ChatFormatting colour, String command, String hover) {
        return Component.literal(text).withStyle(s -> s.withColor(colour)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
    }
}
