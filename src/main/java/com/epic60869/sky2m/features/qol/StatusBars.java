package com.epic60869.sky2m.features.qol;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.skills.SkillProgress;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Status bars (health, mana, defense, XP, speed) on movable HUDs, after Skyblocker's StatusBarTracker and SkyHanni's
 * action bar stat bars (https://github.com/SkyblockerMod/Skyblocker, LGPL-3.0; https://github.com/hannibal002/SkyHanni,
 * LGPL-3.0). The values are read from the action bar, which can optionally lose those parts. Display only.
 */
public final class StatusBars {
    private static final Pattern HEALTH = Pattern.compile("([\\d,]+)/([\\d,]+)❤(?:\\+[\\d,]+❤)?");
    private static final Pattern DEFENSE = Pattern.compile("([\\d,]+)❈ Defense");
    private static final Pattern MANA = Pattern.compile("([\\d,]+)/([\\d,]+)✎ Mana");
    private static final Pattern OVERFLOW = Pattern.compile("([\\d,]+)ʬ");

    private static final int HEIGHT = 10;
    private static final int BACK = 0xB0000000;

    private static long health = -1, maxHealth, mana = -1, maxMana, overflow, defense = -1;

    private StatusBars() {}

    private static QolConfigs.StatusBars config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.gui.statusBars;
    }

    private static boolean on() {
        QolConfigs.StatusBars c = config();
        return c != null && c.enabled;
    }

    public static void init() {
        Sky2MChat.onActionBar(message -> read(message.text()));
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            QolConfigs.StatusBars c = config();
            if (!overlay || c == null || !c.enabled || !c.hideActionBar || !Sky2MLocation.onSkyblock()) return message;
            String plain = Sky2MLocation.strip(message.getString());
            Component result = message;
            for (Pattern pattern : new Pattern[] {HEALTH, DEFENSE, MANA, OVERFLOW}) {
                Matcher m = pattern.matcher(plain);
                if (m.find()) result = SkillProgress.removeText(result, m.group());
            }
            return result;
        });
        register("health", "Health Bar", () -> config().health, 0xFFFF5555, StatusBars::healthFraction, StatusBars::healthText, 20, 200);
        register("mana", "Mana Bar", () -> config().mana, 0xFF55AAFF, StatusBars::manaFraction, StatusBars::manaText, 20, 214);
        register("defense", "Defense Bar", () -> config().defense, 0xFF55FF55, StatusBars::defenseFraction, StatusBars::defenseText, 20, 228);
        register("xp", "XP Bar", () -> config().xp, 0xFF7CFC00, StatusBars::xpFraction, StatusBars::xpText, 20, 242);
        register("speed", "Speed Bar", () -> config().speed, 0xFFFFFF55, StatusBars::speedFraction, StatusBars::speedText, 20, 256);
    }

    private static void read(String text) {
        if (!on() || !Sky2MLocation.onSkyblock()) return;
        Matcher m = HEALTH.matcher(text);
        if (m.find()) {
            health = number(m.group(1));
            maxHealth = number(m.group(2));
        }
        m = DEFENSE.matcher(text);
        if (m.find()) defense = number(m.group(1));
        m = MANA.matcher(text);
        if (m.find()) {
            mana = number(m.group(1));
            maxMana = number(m.group(2));
        }
        m = OVERFLOW.matcher(text);
        overflow = m.find() ? number(m.group(1)) : 0;
    }

    private static long number(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static float healthFraction() {
        return maxHealth <= 0 ? 0f : health / (float) maxHealth;
    }

    private static String healthText() {
        return health < 0 ? "" : health + "/" + maxHealth;
    }

    private static float manaFraction() {
        return maxMana <= 0 ? 0f : mana / (float) maxMana;
    }

    private static String manaText() {
        if (mana < 0) return "";
        return mana + "/" + maxMana + (overflow > 0 ? " +" + overflow + "ʬ" : "");
    }

    private static float defenseFraction() {
        return defense < 0 ? 0f : defense / (float) (defense + 100);
    }

    private static String defenseText() {
        return defense < 0 ? "" : defense + " Defense";
    }

    private static float xpFraction() {
        var player = Minecraft.getInstance().player;
        return player == null ? 0f : player.experienceProgress;
    }

    private static String xpText() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : "Level " + player.experienceLevel;
    }

    private static float speedFraction() {
        return Math.clamp(speed() / 400f, 0f, 1f);
    }

    private static int speed() {
        var player = Minecraft.getInstance().player;
        return player == null ? 0 : (int) Math.floor(player.getAbilities().getWalkingSpeed() * 1000 + 1e-3);
    }

    private static String speedText() {
        return speed() + "%";
    }

    private interface Fraction {
        float get();
    }

    private interface Label {
        String get();
    }

    private static void register(String id, String name, BooleanSupplier barOn, int colour, Fraction fraction, Label label, int x, int y) {
        String hudId = "status_bar_" + id;
        BooleanSupplier turnedOn = () -> on() && barOn.getAsBoolean();
        Sky2MHuds.setting(hudId, turnedOn);
        Sky2MHuds.setSettingsSearch(hudId, "Status Bars");
        Sky2MHuds.registerCustom(hudId, name, () -> turnedOn.getAsBoolean() && Sky2MLocation.onSkyblock(), new Sky2MHuds.CustomHud() {
            @Override
            public int width() {
                QolConfigs.StatusBars c = config();
                return c == null ? 90 : c.width;
            }

            @Override
            public int height() {
                return HEIGHT;
            }

            @Override
            public boolean visible() {
                return true;
            }

            @Override
            public void render(GuiGraphicsExtractor g, boolean preview) {
                QolConfigs.StatusBars c = config();
                if (c == null) return;
                int w = c.width;
                float f = preview ? 0.7f : Math.clamp(fraction.get(), 0f, 1f);
                String text = preview ? name : label.get();
                if (!preview && text.isEmpty()) return;
                g.fill(0, 0, w, HEIGHT, BACK);
                g.fill(1, 1, 1 + Math.round((w - 2) * f), HEIGHT - 1, colour);
                if (c.numbers) {
                    var font = Minecraft.getInstance().font;
                    g.text(font, text, (w - font.width(text)) / 2, 1, 0xFFFFFFFF, true);
                }
            }
        }, x, y);
    }
}
