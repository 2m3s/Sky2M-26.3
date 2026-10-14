package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dungeons > Duplicate Class Warning. In the Party Finder, each party lists its members as " Name: Archer (42)" under
 * "Members:"; parties with your class get a red slot and a tooltip line. When someone joins your group ("Party Finder
 * > Name joined the dungeon group! (Mage Level 30)") you're warned if that class is already yours or a teammate's.
 * Your class comes from the setting, else the class you play in dungeons, pick in the Catacombs menus or join with.
 */
public final class DupeClass {
    private static final Pattern MEMBER = Pattern.compile("^ ?(\\w{1,16}): (Healer|Mage|Berserk|Archer|Tank) \\(\\d+\\)$");
    private static final Pattern JOINED = Pattern.compile("^Party Finder > (?:\\[[^]]{1,7}] ?)?(\\w{1,16}) joined the dungeon group! \\((Healer|Mage|Berserk|Archer|Tank) Level \\d+\\)$");
    private static final Pattern SELECTED = Pattern.compile("(?:Selected Class|Currently Selected|Selected): (Healer|Mage|Berserk|Archer|Tank)\\b");
    private static final Pattern LEFT = Pattern.compile("^(?:\\[[^]]{1,7}] ?)?(\\w{1,16}) (?:has left the party|has been removed from the party|was removed from your party)\\.?.*$");

    private static String lastClass = "";
    /** Classes of the players who joined your group through the Party Finder, by lower-case name. */
    private static final Map<String, String> group = new LinkedHashMap<>();
    private static int ticks;

    private DupeClass() {}

    private static FeatureConfigs.DupeClass config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.dupeClass;
    }

    public static void init() {
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            String dupe = partyDupe(stack);
            if (dupe != null) lines.add(1, Component.literal("Already has a " + dupe + "!").withStyle(ChatFormatting.RED));
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 != 0) return;
            if (Sky2MLocation.inDungeon()) {
                DungeonClass self = SelfClass.get();
                if (self != null && self != DungeonClass.UNKNOWN) lastClass = title(self.name());
            }
            if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen && screen.getTitle().getString().contains("Catacombs")) readSelected(screen);
        });
    }

    /** Your class, as "Archer", or "" when it isn't known. */
    public static String myClass() {
        FeatureConfigs.DupeClass c = config();
        if (c != null && c.myClass != FeatureConfigs.ClassOverride.AUTO) return c.myClass.toString();
        return lastClass;
    }

    private static String title(String name) {
        return name.isEmpty() ? name : name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT);
    }

    private static void readSelected(AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            ItemLore lore = slot.getItem().get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                Matcher m = SELECTED.matcher(Sky2MLocation.strip(line.getString()));
                if (m.find()) {
                    lastClass = m.group(1);
                    return;
                }
            }
        }
    }

    private static void onChat(String text) {
        Matcher m = JOINED.matcher(text);
        if (m.matches()) {
            onJoin(m.group(1), m.group(2));
            return;
        }
        if (text.equals("You left the party.") || text.startsWith("The party was disbanded") || text.startsWith("You have been kicked from the party")
            || text.contains("has disbanded the party")) {
            group.clear();
            return;
        }
        if ((m = LEFT.matcher(text)).matches()) group.remove(m.group(1).toLowerCase(Locale.ROOT));
    }

    private static void onJoin(String name, String cls) {
        Minecraft mc = Minecraft.getInstance();
        boolean self = mc.player != null && name.equalsIgnoreCase(mc.player.getGameProfile().name());
        if (self) {
            lastClass = cls;
            return;
        }
        FeatureConfigs.DupeClass c = config();
        String clash = null;
        if (cls.equals(myClass())) clash = "you";
        else for (Map.Entry<String, String> e : group.entrySet()) if (e.getValue().equals(cls)) clash = e.getKey();
        group.put(name.toLowerCase(Locale.ROOT), cls);
        if (c == null || !c.enabled || clash == null) return;
        Sky2MAlerts.chat(Component.literal(name + " joined as " + cls + ", and " + (clash.equals("you") ? "you're" : clash + " is") + " already " + cls + "!").withStyle(ChatFormatting.RED));
        if (c.sound) Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6f);
    }

    /** The class a Party Finder party already has that's yours, or null. */
    private static String partyDupe(ItemStack stack) {
        FeatureConfigs.DupeClass c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.enabled || stack.isEmpty() || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
            || !screen.getTitle().getString().contains("Party Finder")) return null;
        String mine = myClass();
        if (mine.isEmpty()) return null;
        return PARTY_CLASSES.computeIfAbsent(stack, DupeClass::partyClasses).contains(mine) ? mine : null;
    }

    /** Per stack, the classes its lore lists, so the lore isn't stripped and matched for every slot every frame. */
    private static final Map<ItemStack, java.util.Set<String>> PARTY_CLASSES = new java.util.WeakHashMap<>();

    private static java.util.Set<String> partyClasses(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return java.util.Set.of();
        java.util.Set<String> classes = new java.util.HashSet<>();
        for (Component line : lore.lines()) {
            Matcher m = MEMBER.matcher(Sky2MLocation.strip(line.getString()));
            if (m.matches()) classes.add(m.group(2));
        }
        return classes;
    }

    /** From the slot background mixin: a red slot for parties that already have your class. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        if (partyDupe(slot.getItem()) == null) return;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0x80FF3030);
    }
}
