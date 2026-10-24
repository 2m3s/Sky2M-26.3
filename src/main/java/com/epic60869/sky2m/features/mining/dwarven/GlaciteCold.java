package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Glacite cold helpers. The cold overlay is SkyHanni's ColdOverlay (hannibal002/SkyHanni, LGPL-2.1): the powder snow
 * frost texture, with an opacity that grows from the threshold up to 100 cold. The Ascension Rope reminder is a
 * display-only version of SkyHanni's MiningNotifications rope helper (it never fetches the rope or uses it for you).
 */
public final class GlaciteCold {
    private static final Pattern COLD = Pattern.compile("Cold: (-?\\d+)❄");
    private static final Identifier FROST = Identifier.withDefaultNamespace("textures/misc/powder_snow_outline.png");

    private static int cold;
    /** The cold the overlay is drawn with, easing towards {@link #cold}. */
    private static float shownCold;
    private static boolean alerted;
    private static boolean ropeAlerted;
    private static int ticks;

    private GlaciteCold() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 5 == 0) tick(mc);
        });
        Sky2MLocation.onAreaChange(area -> {
            alerted = false;
            ropeAlerted = false;
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.PLAYER_LIST, Identifier.fromNamespaceAndPath("sky2m", "cold_overlay"), (graphics, delta) -> render(graphics));
    }

    private static boolean inColdArea() {
        return Sky2MLocation.inMineshaft() || Sky2MLocation.inGlaciteTunnels();
    }

    private static void tick(Minecraft mc) {
        DwarvenConfig.Glacite c = DwarvenMining.glacite();
        if (c == null || mc.player == null || !inColdArea()) {
            cold = 0;
            return;
        }
        int value = 0;
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = COLD.matcher(Sky2MLocation.strip(line));
            if (m.find()) value = Math.abs(Integer.parseInt(m.group(1)));
        }
        cold = value;
        if (cold < c.coldAlertLevel - 10) {
            alerted = false;
            ropeAlerted = false;
        }
        if (c.coldAlert && !alerted && cold >= c.coldAlertLevel) {
            alerted = true;
            Sky2MAlerts.title(Component.literal("COLD " + cold + "%").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
                Component.literal("You freeze at 100").withStyle(ChatFormatting.WHITE));
        }
        if (c.ropeReminder && !ropeAlerted && Sky2MLocation.inMineshaft() && cold >= c.coldAlertLevel) {
            ropeAlerted = true;
            int ropes = ropes(mc);
            if (ropes > 0) {
                Sky2MAlerts.title(Component.literal("USE AN ASCENSION ROPE").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                    Component.literal("Cold " + cold + "%, you carry " + ropes).withStyle(ChatFormatting.WHITE));
            } else {
                Sky2MAlerts.title(Component.literal("NO ASCENSION ROPE").withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    Component.literal("Cold " + cold + "%, leave the mineshaft soon").withStyle(ChatFormatting.WHITE));
            }
        }
    }

    private static int ropes(Minecraft mc) {
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && "ASCENSION_ROPE".equals(Compat.neuName(stack))) count += stack.getCount();
        }
        return count;
    }

    private static void render(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
        DwarvenConfig.Glacite c = DwarvenMining.glacite();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.coldOverlay || mc.player == null || !inColdArea()) {
            shownCold = 0;
            return;
        }
        shownCold += (cold - shownCold) * 0.1f;
        float percentage = (shownCold - c.coldThreshold) / (100f - c.coldThreshold);
        float alpha = Math.max(0f, Math.min(1f, percentage)) * (c.coldMaxAlpha / 100f);
        if (alpha <= 0.01f) return;
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        int colour = ((int) (alpha * 255) << 24) | 0xFFFFFF;
        graphics.blit(RenderPipelines.GUI_TEXTURED, FROST, 0, 0, 0f, 0f, w, h, w, h, w, h, colour);
    }
}
