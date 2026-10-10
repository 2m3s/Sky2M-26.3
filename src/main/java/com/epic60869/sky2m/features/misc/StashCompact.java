package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.mixin.Sky2MChatComponentAccessor;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Stash Compact (features/chat/StashCompact, LGPL-2.1): Hypixel's five-line "You have 226 materials stashed
 * away! ... CLICK HERE to pick them up!" block becomes one clickable line, the "One or more items didn't fit..."
 * message is hidden, and the same counts aren't reported twice.
 */
public final class StashCompact {
    private static final Pattern MATERIAL_COUNT = Pattern.compile("§f *§7You have §.(?<count>[\\d,]+) (?:§.)+(?<type>item|material)s? stashed away!.*");
    private static final Pattern DIFFERING_COUNT = Pattern.compile("§f *§8\\(This totals (?<count>[\\d,]+) types? of (?<type>item|material)s? stashed!\\).*");
    private static final Pattern PICKUP = Pattern.compile("§f *§.§l>>> §.§lCLICK HERE§. to pick (?:them|it) up! §.§l<<<.*");
    private static final Pattern GENERIC_ADDED = Pattern.compile(
        "§eOne or more (?:item|material)s? didn't fit in your inventory and were added to your (?:item|material) stash! §6Click here §eto pick them up!");

    private enum StashType {
        ITEM("item", "§e", "§6"), MATERIAL("material", "§b", "§3");

        final String displayName, mainColour, accentColour;

        StashType(String displayName, String mainColour, String accentColour) {
            this.displayName = displayName;
            this.mainColour = mainColour;
            this.accentColour = accentColour;
        }

        static StashType of(String name) {
            for (StashType type : values()) if (type.displayName.equals(name)) return type;
            return null;
        }
    }

    private static final class StashMessage {
        final int materialCount;
        final String type;
        Integer differingMaterialsCount;

        StashMessage(int materialCount, String type) {
            this.materialCount = materialCount;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StashMessage other && other.materialCount == materialCount && other.type.equals(type)
                && Objects.equals(other.differingMaterialsCount, differingMaterialsCount);
        }

        @Override
        public int hashCode() {
            return Objects.hash(materialCount, type, differingMaterialsCount);
        }
    }

    private static StashType currentType;
    private static final Map<StashType, StashMessage> currentMessages = new EnumMap<>(StashType.class);
    private static final Map<StashType, StashMessage> lastMessages = new EnumMap<>(StashType.class);
    /** Hide the next message if it's empty (Hypixel's padding after the block). */
    private static boolean hideNextEmpty;

    private StashCompact() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.stashMessages;
    }

    public static void init() {
        Sky2MLocation.onAreaChange(area -> {
            Config config = config();
            if (config == null || !config.hideDuplicateWarning.worldChangeReset) return;
            currentMessages.clear();
            lastMessages.clear();
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !onChat(message));
    }

    /** @return true to hide the message. */
    private static boolean onChat(Component component) {
        Config config = config();
        if (config == null || !config.enabled || !Compat.isOnSkyblock()) return false;
        if (hideNextEmpty) {
            hideNextEmpty = false;
            if (component.getString().isBlank()) return true;
        }
        String message = SbcItems.legacy(component).replace("§r", "");

        Matcher m = MATERIAL_COUNT.matcher(message);
        if (m.matches()) {
            StashType type = StashType.of(m.group("type"));
            if (type == null) return false;
            currentType = type;
            currentMessages.put(type, new StashMessage(Integer.parseInt(m.group("count").replace(",", "")), m.group("type")));
            deleteRecentEmptyLines(2);
            return true;
        }
        m = DIFFERING_COUNT.matcher(message);
        if (m.matches()) {
            StashType type = StashType.of(m.group("type"));
            if (type == null) return false;
            currentType = type;
            StashMessage current = currentMessages.get(type);
            if (current != null) current.differingMaterialsCount = Integer.parseInt(m.group("count").replace(",", ""));
            return true;
        }
        if (PICKUP.matcher(message).matches()) {
            hideNextEmpty = true;
            StashType type = currentType;
            StashMessage current = type == null ? null : currentMessages.get(type);
            if (current == null || current.materialCount <= config.hideLowWarningsThreshold) return true;
            if (config.hideDuplicateWarning.enabled && current.equals(lastMessages.get(type))) return true;
            send(type, current, config);
            return true;
        }
        if (config.hideAddedMessages && GENERIC_ADDED.matcher(message).matches()) {
            hideNextEmpty = true;
            return true;
        }
        return false;
    }

    /** Hypixel's empty lines just before the block, already in chat. */
    private static void deleteRecentEmptyLines(int max) {
        Minecraft mc = Minecraft.getInstance();
        Sky2MChatComponentAccessor chat = (Sky2MChatComponentAccessor) mc.gui.hud.getChat();
        List<GuiMessage> all = chat.sky2m$allMessages();
        int now = mc.gui.hud.getGuiTicks();
        int removed = 0;
        for (int i = 0; i < Math.min(all.size(), max) && removed < max; ) {
            GuiMessage line = all.get(i);
            if (line.content().getString().isBlank() && now - line.addedTime() <= 10) {
                all.remove(i);
                removed++;
            } else {
                break;
            }
        }
        if (removed > 0) chat.sky2m$refreshTrimmedMessages();
    }

    private static void send(StashType type, StashMessage message, Config config) {
        String typeName = message.materialCount == 1 ? type.displayName : type.displayName + "s";
        String extra = "";
        if (message.differingMaterialsCount != null) {
            int n = message.differingMaterialsCount;
            extra = ", " + type.mainColour + "totalling " + type.accentColour + n + " " + (n == 1 ? "type" : "types") + type.mainColour;
        }
        String action = config.useViewStash ? "view" : "pickup";
        String command = config.useViewStash ? "/viewstash " + message.type : "/pickupstash";
        MutableComponent line = SbcItems.parseLegacy(type.mainColour + "You have " + type.accentColour + shortFormat(message.materialCount) + " "
            + type.mainColour + typeName + " in stash" + extra + ". " + type.mainColour + "Click to " + type.accentColour + action + " "
            + type.mainColour + "your stash!");
        line.withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(SbcItems.parseLegacy("§eClick to " + action + " your " + message.type + " stash!"))));
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(line));
        });
        currentMessages.remove(type);
        lastMessages.put(type, message);
    }

    private static String shortFormat(int value) {
        if (value < 1000) return String.valueOf(value);
        String[] suffixes = {"k", "M", "B"};
        double v = value;
        int index = -1;
        while (v >= 1000 && index < suffixes.length - 1) {
            v /= 1000;
            index++;
        }
        String number = v >= 100 ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
        if (number.endsWith(".0")) number = number.substring(0, number.length() - 2);
        return number + suffixes[index];
    }

    /** SkyHanni's StashConfig (LGPL-2.1). */
    public static final class Config {
        @Expose
        @ConfigOption(name = "Stash Warnings", desc = "Compact warnings relating to items/materials in your stash.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Hide Dupe Warnings", desc = "")
        @Accordion
        public HideDuplicateWarning hideDuplicateWarning = new HideDuplicateWarning();

        public static final class HideDuplicateWarning {
            @Expose
            @ConfigOption(name = "Enabled", desc = "Hide duplicate warnings for previously reported stash counts.")
            @ConfigEditorBoolean
            public boolean enabled = true;

            @Expose
            @ConfigOption(name = "Once Per World", desc = "Show warnings even if the counts are previously reported, once per world change.")
            @ConfigEditorBoolean
            public boolean worldChangeReset = true;
        }

        @Expose
        @ConfigOption(name = "Hide Added Messages", desc = "Hide the messages when something is added to your stash.")
        @ConfigEditorBoolean
        public boolean hideAddedMessages = true;

        @Expose
        @ConfigOption(name = "Hide Low Warnings", desc = "Hide warnings with a total count below this number.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1_000_000f, minStep = 100f)
        public int hideLowWarningsThreshold = 0;

        @Expose
        @ConfigOption(name = "Use /ViewStash", desc = "Use /viewstash [type] instead of /pickupstash.")
        @ConfigEditorBoolean
        public boolean useViewStash = false;
    }
}
