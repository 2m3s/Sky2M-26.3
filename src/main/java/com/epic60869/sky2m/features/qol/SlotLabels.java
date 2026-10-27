package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * More slot stack-size labels, after SkyHanni's item number overlays (https://github.com/hannibal002/SkyHanni,
 * LGPL-3.0, ItemDisplayOverlayFeatures): pet level, attribute shard level, minion tier, collection level and bestiary
 * level in the top-left of the slot. Drawn with the item's count (Sky2MItemCooldownMixin). Ability cooldowns on slots
 * are Item Cooldowns' own setting.
 */
public final class SlotLabels {
    private static final Pattern PET = Pattern.compile("^\\[Lvl (\\d+)] ");
    private static final Pattern MINION = Pattern.compile("_GENERATOR_(\\d+)$");
    private static final Pattern ROMAN_NAME = Pattern.compile("^(.+) ([IVXLC]+)$");
    private static final Pattern NUMBER_NAME = Pattern.compile("^(.+?) (\\d{1,3})$");
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final String NONE = "";
    private static final Map<ItemStack, String> CACHE = new WeakHashMap<>();

    private SlotLabels() {}

    public static void drawOverlay(GuiGraphicsExtractor g, ItemStack stack, int x, int y) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || stack.isEmpty()) return;
        QolConfigs.SlotLabels c = config.gui.slotLabels;
        if (!c.petLevel && !c.attributeLevel && !c.minionTier && !c.collectionLevel && !c.bestiaryLevel) return;
        if (!Sky2MLocation.onSkyblock()) return;
        String label = CACHE.computeIfAbsent(stack, s -> label(s, c));
        if (label.isEmpty()) return;
        var font = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75f, 0.75f);
        g.text(font, label, 0, 0, 0xFFFFFF55, true);
        g.pose().popMatrix();
    }

    private static String label(ItemStack stack, QolConfigs.SlotLabels c) {
        String id = Compat.neuName(stack);
        if (c.attributeLevel && "ATTRIBUTE_SHARD".equals(id)) {
            CompoundTag attributes = Compat.customDataView(stack).getCompoundOrEmpty("attributes");
            if (attributes.size() == 1) return Integer.toString(attributes.getIntOr(attributes.keySet().iterator().next(), 0));
            return NONE;
        }
        if (c.minionTier && !id.isEmpty()) {
            Matcher m = MINION.matcher(id);
            if (m.find()) return m.group(1);
        }
        String name = FORMATTING.matcher(Compat.realName(stack).getString()).replaceAll("").trim();
        if (c.petLevel && "PET".equals(id)) {
            Matcher m = PET.matcher(name);
            if (m.find()) return m.group(1);
        }
        var screen = Minecraft.getInstance().gui.screen();
        if (screen == null) return NONE;
        String title = screen.getTitle().getString();
        if (c.collectionLevel && title.contains("Collection")) {
            Matcher m = ROMAN_NAME.matcher(name);
            if (m.matches()) return Integer.toString(roman(m.group(2)));
        }
        if (c.bestiaryLevel && title.contains("Bestiary")) {
            Matcher m = NUMBER_NAME.matcher(name);
            if (m.matches()) return m.group(2);
        }
        return NONE;
    }

    private static int roman(String s) {
        int total = 0, previous = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int value = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                default -> 100;
            };
            total += value < previous ? -value : value;
            previous = Math.max(previous, value);
        }
        return total;
    }
}
