package com.epic60869.sky2m.features.sbc;

import com.google.gson.JsonArray;
import com.epic60869.sky2m.Sky2MGlobalChat;
import com.epic60869.sky2m.Sky2MLogin;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reports the dyes shown in Hypixel's /dyes menu (Vincent) to the Sky2M server so "!dyes" can answer. Any page of the
 * menu counts: pages are merged by dye name and the whole list is re-sent whenever it changes.
 * (26.2 keeps this as Sky2MDyeSync, ticked from Sky2MMod; 26.3 registers its own tick from Sbc.init.)
 */
public final class SbcDyeSync {
    private static final Map<String, JsonObject> DYES = new LinkedHashMap<>();
    private static String lastSent = "";
    private static long lastCheck;
    private static String lastScreenKey = "";
    private static String pending = "";

    private SbcDyeSync() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (Exception e) {
                SbcCrashReports.report(e, "S2C dye sync");
            }
        });
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("§.", "").replaceAll("\\s+", " ").trim();
    }

    private static boolean isDyeMenu(String title) {
        String t = strip(title).toLowerCase(Locale.ROOT);
        return t.contains("dye") && !t.contains("dyed");
    }

    private static boolean isControl(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.isEmpty() || n.equals("close") || n.equals("go back") || n.contains("next page") || n.contains("previous page")
            || n.equals("sort") || n.equals("filter") || n.startsWith("sort") || n.startsWith("filter") || n.startsWith("page ")
            || n.equals("search") || n.equals("back");
    }

    public static void tick(Minecraft mc) {
        if (mc.player == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?> container)) {
            lastScreenKey = "";
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < 400) return;
        lastCheck = now;
        if (!isDyeMenu(container.getTitle().getString())) return;

        List<Slot> slots = container.getMenu().slots;
        int count = slots.size() > 36 ? slots.size() - 36 : 0;
        List<JsonObject> found = new ArrayList<>();
        boolean named = false;
        for (int i = 0; i < count; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String name = strip(stack.getHoverName().getString());
            if (isControl(name)) continue;
            String path = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (path.endsWith("glass_pane") || path.equals("arrow") || path.equals("barrier")) continue;
            JsonObject o = new JsonObject();
            o.addProperty("slot", i);
            o.addProperty("name", name);
            o.addProperty("count", Math.max(1, stack.getCount()));
            JsonArray lore = new JsonArray();
            ItemLore l = stack.get(DataComponents.LORE);
            if (l != null) {
                for (Component line : l.lines()) {
                    String s = strip(line.getString());
                    // 20 lines: a dye's drop chance can be well down its lore, and !dyes only lists dyes with one.
                    if (!s.isEmpty() && lore.size() < 20) lore.add(s);
                }
            }
            o.add("lore", lore);
            found.add(o);
            if (name.toLowerCase(Locale.ROOT).contains("dye")) named = true;
        }
        if (found.isEmpty()) return;
        // Prefer the items that are clearly dyes; otherwise take every non-control item of this menu.
        for (JsonObject o : found) {
            String name = o.get("name").getAsString();
            if (named && !name.toLowerCase(Locale.ROOT).contains("dye")) continue;
            DYES.put(name, o);
        }
        while (DYES.size() > 100) DYES.remove(DYES.keySet().iterator().next());

        JsonArray items = new JsonArray();
        DYES.values().forEach(items::add);
        String signature = items.toString();
        if (signature.equals(lastSent) || signature.equals(pending)) return;
        pending = signature;
        final int total = DYES.size();
        Sky2MLogin.whenLoggedIn(() -> {
            if (pending.equals(signature)) pending = "";
            JsonObject packet = new JsonObject();
            packet.addProperty("type", "dyesSync");
            packet.add("items", items);
            if (Sky2MGlobalChat.send(packet)) {
                lastSent = signature;
                Minecraft m = Minecraft.getInstance();
                m.execute(() -> {
                    if (m.player != null) {
                        m.player.sendSystemMessage(Component.literal("[S2M] Synced " + total + " dyes for !dyes.")
                            .withStyle(ChatFormatting.GREEN));
                    }
                });
            }
        });
    }
}
