package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.features.core.RepoPattern;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Odin's Breaker Display (https://github.com/odtheking/Odin, BSD-3-Clause: features/impl/dungeon/BreakerDisplay.kt): the
 * charges left on the Dungeon Breaker in your inventory, from its "Charges: 17/20" lore line.
 */
public final class BreakerHud {
    private static final String ITEM_ID = "DUNGEONBREAKER";
    private static final RepoPattern CHARGES = RepoPattern.of("dungeons.BreakerHud.CHARGES", "Charges: (\\d+)/(\\d+)");

    private static int charges;
    /** 0 when there's no Dungeon Breaker in your inventory. */
    private static int maxCharges;
    private static int ticks;

    private BreakerHud() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.breakerHud;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 != 0) return;
            Config c = config();
            if (c == null || !c.enabled || !Sky2MLocation.inDungeon()) {
                maxCharges = 0;
                return;
            }
            read(mc);
        });
        Sky2MHuds.setting("breaker_hud", () -> config() != null && config().enabled);
        Sky2MHuds.register("breaker_hud", "Dungeon Breaker Charges",
            () -> {
                Config c = config();
                return c != null && c.enabled && Sky2MLocation.inDungeon() && maxCharges > 0 && (c.showFull || charges < maxCharges);
            },
            () -> List.of(line(charges, maxCharges)),
            List.of(line(17, 20)),
            20, 150);
    }

    private static void read(Minecraft mc) {
        maxCharges = 0;
        if (mc.player == null) return;
        for (ItemStack stack : mc.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty() || !ITEM_ID.equals(Compat.neuName(stack))) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component text : lore.lines()) {
                int[] found = parseCharges(text.getString());
                if (found == null) continue;
                charges = found[0];
                maxCharges = found[1];
                return;
            }
        }
    }

    /** {charges, max} from a lore line like "Charges: 17/20⸕", or null. Public for the game test. */
    public static int[] parseCharges(String line) {
        Matcher m = CHARGES.matcher(line == null ? "" : line);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    private static Component line(int left, int max) {
        return Component.literal("§e" + left + "§7/§e" + max + "§c⸕");
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In a dungeon, a HUD with the charges left on the Dungeon Breaker in your inventory. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Show When Full", desc = "Also show it while the Dungeon Breaker is fully charged.")
        @ConfigEditorBoolean
        public boolean showFull = false;
    }
}
