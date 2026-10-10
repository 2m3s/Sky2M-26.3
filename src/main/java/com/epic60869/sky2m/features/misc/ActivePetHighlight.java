package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MNopoFeatures;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Highlights your summoned pet in the Pets menu. The pet's petInfo data says {@code "active":true}, and its lore
 * says "Click to despawn!" instead of "Click to summon!".
 */
public final class ActivePetHighlight {
    private static final Pattern INVENTORY = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Pets\\b");
    private static final Pattern ACTIVE_INFO = Pattern.compile("\"active\"\\s*:\\s*true");
    /** "[Lvl 87] Ender Dragon", or "⭐ [Lvl 87] Ender Dragon" when favourited. */
    private static final Pattern LEVEL = Pattern.compile("\\[Lvl (\\d+)]");
    private static final float LABEL_SCALE = 0.75f;

    private ActivePetHighlight() {}

    /** Drawn behind the slot's item, from Sky2MSlotBackgroundMixin. */
    public static void renderSlot(GuiGraphicsExtractor graphics, Slot slot) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.pets.highlightActive) return;
        if (!Compat.isOnSkyblock() || slot.container instanceof Inventory) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return;
        if (!INVENTORY.matcher(Sky2MLocation.strip(screen.getTitle().getString())).find()) return;
        if (!isActivePet(slot.getItem())) return;
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, colour(config.misc.pets.activeColor));
    }

    /** The pet's level over its item (bottom right, like a stack count), from Sky2MSlotBackgroundMixin. */
    public static void renderSlotLabel(GuiGraphicsExtractor graphics, Slot slot) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.pets.showLevel || !slot.hasItem()) return;
        if (!Compat.isOnSkyblock() || slot.container instanceof Inventory) return;
        var screen = Minecraft.getInstance().gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return;
        if (!INVENTORY.matcher(Sky2MLocation.strip(screen.getTitle().getString())).find()) return;
        Matcher m = LEVEL.matcher(Sky2MLocation.strip(slot.getItem().getHoverName().getString()));
        if (!m.find()) return;
        int level = Integer.parseInt(m.group(1));
        // Golden Dragons and other level 200 pets max at 200, the rest at 100.
        boolean maxed = level == 100 || level == 200;
        if (maxed && config.misc.pets.showOverflowLevel) level = Math.max(level, overflowLevel(slot.getItem()));
        String text = String.valueOf(level);
        var font = Minecraft.getInstance().font;
        graphics.pose().pushMatrix();
        graphics.pose().translate(slot.x + 17, slot.y + 17);
        graphics.pose().scale(LABEL_SCALE, LABEL_SCALE);
        graphics.text(font, text, -font.width(text), -8, maxed ? 0xFFFFAA00 : 0xFFFFFFFF, true);
        graphics.pose().popMatrix();
    }

    /** The pet's level from its petInfo exp, past 100/200; -1 if it has no petInfo. */
    private static int overflowLevel(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return -1;
        try {
            String petInfo = custom.copyTag().getStringOr("petInfo", "");
            if (petInfo.isBlank()) return -1;
            JsonObject json = JsonParser.parseString(petInfo).getAsJsonObject();
            if (!json.has("exp")) return -1;
            String tier = json.has("tier") ? json.get("tier").getAsString() : "LEGENDARY";
            return Sky2MNopoFeatures.petLevel(json.get("exp").getAsFloat(), tier);
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isActivePet(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom != null) {
            String petInfo = custom.copyTag().getStringOr("petInfo", "");
            if (!petInfo.isBlank()) return ACTIVE_INFO.matcher(petInfo).find();
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            if (line.getString().contains("Click to despawn")) return true;
        }
        return false;
    }

    private static int colour(String value) {
        try {
            return com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
        } catch (Exception e) {
            return 0xAA55FF55;
        }
    }
}
