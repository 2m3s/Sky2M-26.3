package com.epic60869.sky2m.features.hunting;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.BindKeys;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.Slot;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * SkyHanni's Attribute Shard Tracker (https://github.com/hannibal002/SkyHanni, features/hunting/ShardTrackerDisplay.kt,
 * LGPL-2.1): the shards you track, each with how many your Hunting Box holds and how many you need (to max it, or what
 * a SkyShards recipe needs). Track the hovered shard with the Select Shard Key in a shard menu; click a line in a menu to
 * stop tracking it. Recipes copied from SkyShards are imported with the config button or /s2 importskyshards.
 */
public final class ShardTrackerDisplay {
    private static final String HUD_ID = "hunting_shard_tracker";
    private static List<Component> lines = List.of();
    /** The shard on each line (index into {@link #lines}), or null for the title and buttons. */
    private static List<String> lineShards = List.of();
    private static boolean keyWasDown;
    private static int ticks;

    private ShardTrackerDisplay() {}

    private static HuntingConfig.ShardTracker config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.hunting.shardTracker;
    }

    private static boolean enabled() {
        HuntingConfig.ShardTracker c = config();
        return c != null && c.enabled && Sky2MLocation.onSkyblock();
    }

    public static void init() {
        Sky2MHuds.register(HUD_ID, "Shard Tracker", ShardTrackerDisplay::enabled,
            () -> ScreenHud.inMenu() ? List.of() : lines,
            List.of(Component.literal("Attribute Shard Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
                Component.literal(" Grove: ").withStyle(ChatFormatting.GRAY).append(Component.literal("12").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("/").withStyle(ChatFormatting.GRAY)).append(Component.literal("64").withStyle(ChatFormatting.GREEN))),
            80, 180);
        Sky2MHuds.setSettingsSearch(HUD_ID, "Shard Tracker");
        Sky2MHuds.setting(HUD_ID, () -> config() != null && config().enabled);
        ScreenHud.register(HUD_ID, () -> enabled() ? lines : List.of(), ShardTrackerDisplay::clickLine);
        ClientTickEvents.END_CLIENT_TICK.register(ShardTrackerDisplay::tick);
        ItemTooltipCallback.EVENT.register((stack, context, type, tooltip) -> {
            HuntingConfig.ShardTracker c = config();
            if (!enabled() || !BindKeys.isSet(c.selectShardKeybind) || !AttributeShards.inShardMenu()) return;
            if (AttributeShards.of(stack) == null) return;
            tooltip.add(Component.literal("Press " + keyName(c.selectShardKeybind) + " to track this shard.").withStyle(ChatFormatting.YELLOW));
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("importskyshards").executes(c -> {
                    Minecraft.getInstance().execute(ShardTrackerDisplay::importFromSkyShards);
                    return 1;
                })));
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("resethuntingbox").executes(c -> {
                    Minecraft.getInstance().execute(() -> {
                        AttributeShards.resetHuntingBox();
                        Sky2MAlerts.chat(Component.literal("Reset Hunting Box shard data. Open your Hunting Box to update it.").withStyle(ChatFormatting.YELLOW));
                    });
                    return 1;
                })));
            }
        });
    }

    private static String keyName(int key) {
        return com.epic60869.sky2m.custom.util.Input.bindName(key);
    }

    private static void tick(Minecraft mc) {
        if (!enabled()) {
            lines = List.of();
            return;
        }
        // The Select Shard Key, pressed over a shard in a shard menu.
        boolean down = BindKeys.down(config().selectShardKeybind);
        if (down && !keyWasDown && mc.gui.screen() instanceof AbstractContainerScreen<?> screen && AttributeShards.inShardMenu()) {
            Slot hovered = ((com.epic60869.sky2m.mixin.gui.Sky2MContainerScreenAccessor) screen).sky2m$getHoveredSlot();
            AttributeShards.Shard shard = hovered == null ? null : AttributeShards.of(hovered.getItem());
            if (shard != null) toggle(shard.displayName());
        }
        keyWasDown = down;
        if (++ticks % 10 == 0) rebuild();
    }

    private static void toggle(String shard) {
        Map<String, Integer> tracked = AttributeShards.tracked();
        if (tracked.remove(shard) == null) tracked.put(shard, -1);
        AttributeShards.markDirty();
        rebuild();
    }

    private static void rebuild() {
        Map<String, Integer> tracked = AttributeShards.tracked();
        if (tracked.isEmpty() || !AttributeShards.loaded()) {
            lines = List.of();
            lineShards = List.of();
            return;
        }
        List<Component> out = new ArrayList<>();
        List<String> shards = new ArrayList<>();
        out.add(Component.literal("Attribute Shard Tracker").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
        shards.add(null);
        for (Map.Entry<String, Integer> e : tracked.entrySet()) {
            AttributeShards.Shard shard = AttributeShards.byName(e.getKey());
            if (shard == null) continue;
            int have = AttributeShards.amountInBox(shard);
            int need = e.getValue() == -1 ? AttributeShards.amountUntilMax(shard) : e.getValue();
            int colour = need == 0 ? 0x55FF55 : have <= 0 ? 0xFF5555 : blend(0xFFFF55, 0x55FF55, Math.min(1f, have / (float) need));
            MutableComponent line = Component.literal(" " + shard.displayName() + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(have)).withColor(colour));
            if (need > 0) {
                line.append(Component.literal("/").withStyle(ChatFormatting.GRAY)).append(Component.literal(String.valueOf(need)).withStyle(ChatFormatting.GREEN));
            }
            out.add(line);
            shards.add(shard.displayName());
        }
        if (ScreenHud.inMenu()) {
            out.add(Component.literal("[Import From SkyShards]").withStyle(ChatFormatting.YELLOW));
            shards.add("#import");
            out.add(Component.literal("[Reset Display]").withStyle(ChatFormatting.RED));
            shards.add("#reset");
        }
        lines = out;
        lineShards = shards;
    }

    private static int blend(int from, int to, float t) {
        int r = (int) ((from >> 16 & 0xFF) + ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int g = (int) ((from >> 8 & 0xFF) + ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return r << 16 | g << 8 | b;
    }

    private static void clickLine(int index) {
        if (index >= lineShards.size() || lineShards.get(index) == null) return;
        String what = lineShards.get(index);
        switch (what) {
            case "#import" -> importFromSkyShards();
            case "#reset" -> {
                AttributeShards.tracked().clear();
                AttributeShards.markDirty();
                rebuild();
            }
            default -> toggle(what);
        }
    }

    /** Reads a SkyShards (or NoFrills) recipe export from the clipboard: "<SkyHanniRecipe>(V1):base64(gzip(json))". */
    public static void importFromSkyShards() {
        HuntingConfig.ShardTracker c = config();
        if (c != null && !c.enabled) {
            Sky2MAlerts.chat(Component.literal("Attribute Shard Tracker is disabled. Turn it on in /s2 → Hunting → Shard Tracker.").withStyle(ChatFormatting.RED));
        }
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
        String[] split = clipboard == null ? new String[0] : clipboard.split(":", 2);
        if (split.length < 2 || !(split[0].startsWith("<NoFrillsRecipe>(V") || split[0].startsWith("<SkyHanniRecipe>(V"))) {
            Sky2MAlerts.chat(Component.literal("Import from SkyShards failed, make sure you have a valid recipe copied.").withStyle(ChatFormatting.RED));
            return;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(split[1].trim())))) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Integer> tracked = AttributeShards.tracked();
            tracked.clear();
            int count = 0;
            for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
                var o = element.getAsJsonObject();
                String source = o.has("source") && !o.get("source").isJsonNull() ? o.get("source").getAsString() : null;
                if (source != null && !source.equals("Direct")) continue;
                AttributeShards.Shard shard = AttributeShards.byName(o.get("name").getAsString());
                if (shard == null) continue;
                tracked.put(shard.displayName(), o.get("needed").getAsInt());
                count++;
            }
            AttributeShards.markDirty();
            rebuild();
            Sky2MAlerts.chat(Component.literal("Imported " + count + " shards from SkyShards.").withStyle(ChatFormatting.GREEN));
        } catch (Exception e) {
            Sky2MAlerts.chat(Component.literal("Import from SkyShards failed, make sure you have a valid recipe copied.").withStyle(ChatFormatting.RED));
        }
    }
}
