package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.skills.SkillProgress;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyblockAddons' Secrets Display (https://github.com/BiscuitDevelopment/SkyblockAddons, MIT:
 * listeners/RenderListener.java and core/dungeons/DungeonManager.java): the current room's secrets from the action bar
 * ("2/5 Secrets") under a "Secrets" title, with a chest icon on the left. The count fades from red through yellow to
 * green as you find them; rooms without secrets say "None".
 */
public final class SecretsDisplay {
    private static final String ID = "secrets_display";
    private static final Pattern SECRETS = Pattern.compile("(\\d+)/(\\d+) Secrets");
    /** Made on first draw: items can't be made while the mod loads ("Components not bound yet"). */
    private static ItemStack chest;
    private static final String TITLE = "Secrets";

    /** Secrets found in the room, or -1 when the action bar doesn't show any. */
    private static int secrets = -1;
    private static int maxSecrets;

    private SecretsDisplay() {}

    private static FeatureConfigs.Secrets config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.secrets;
    }

    private static boolean turnedOn() {
        FeatureConfigs.Secrets c = config();
        return c != null && c.secretsDisplay;
    }

    public static void init() {
        Sky2MChat.onActionBar(message -> {
            Matcher m = SECRETS.matcher(message.text());
            if (!m.find()) {
                secrets = -1;
                return;
            }
            secrets = Integer.parseInt(m.group(1));
            maxSecrets = Integer.parseInt(m.group(2));
        });
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> {
            FeatureConfigs.Secrets c = config();
            if (!overlay || c == null || !c.secretsDisplay || !c.hideSecretsInActionBar || !Sky2MLocation.inDungeon()) return message;
            Matcher m = SECRETS.matcher(Sky2MLocation.strip(message.getString()));
            return m.find() ? SkillProgress.removeText(message, m.group()) : message;
        });
        Sky2MHuds.setting(ID, SecretsDisplay::turnedOn);
        Sky2MHuds.registerCustom(ID, "Secrets Display", () -> turnedOn() && Sky2MLocation.inDungeon(), new Hud(), 20, 120);
    }

    private static final class Hud implements Sky2MHuds.CustomHud {
        @Override
        public int width() {
            return 16 + 2 + Minecraft.getInstance().font.width(TITLE);
        }

        @Override
        public int height() {
            return 20;
        }

        @Override
        public boolean visible() {
            return true;
        }

        @Override
        public void render(GuiGraphicsExtractor g, boolean preview) {
            var font = Minecraft.getInstance().font;
            int found = secrets, max = maxSecrets;
            if (found == -1 && preview) {
                found = 5;
                max = 10;
            }
            int textX = 16 + 2;
            float centre = textX + font.width(TITLE) / 2f;
            g.text(font, TITLE, textX, 0, 0xFFFFFFFF, true);
            if (found == -1 || max == 0) {
                String none = "None";
                g.text(font, none, Math.round(centre - font.width(none) / 2f), 10, 0xFFFFFFFF, true);
            } else {
                // More found than the room has: SkyblockAddons takes the found count as the room's.
                if (found > max) max = found;
                int colour = colour(found / (float) max);
                String foundText = String.valueOf(found), maxText = String.valueOf(max);
                int total = font.width(foundText) + font.width("/") + font.width(maxText);
                int x = Math.round(centre - total / 2f);
                g.text(font, foundText, x, 11, colour, true);
                g.text(font, "/", x + font.width(foundText), 11, 0xFFFFFFFF, true);
                g.text(font, maxText, x + font.width(foundText) + font.width("/"), 11, colour, true);
            }
            if (chest == null) chest = new ItemStack(Items.CHEST);
            g.item(chest, 0, 0);
        }
    }

    /** Red to yellow over the first half, yellow to green over the second, like SkyblockAddons. */
    private static int colour(float percent) {
        percent = Math.clamp(percent, 0f, 1f);
        float r, g;
        if (percent <= 0.5f) {
            r = 1;
            g = percent * 2 * 0.66f + 0.33f;
        } else {
            r = (1 - percent) * 0.66f + 0.33f;
            g = 1;
        }
        return 0xFF000000 | Math.round(Math.min(1, r) * 255) << 16 | Math.round(g * 255) << 8 | Math.round(0.33f * 255);
    }
}
