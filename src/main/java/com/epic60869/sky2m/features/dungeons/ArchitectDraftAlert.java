package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * When a puzzle fails, tells you (and optionally the party) to get an Architect's First Draft, like NoammAddons'
 * ArchitectDraft (https://github.com/Noamm9/NoammAddons, CC0-1.0) and SkyHanni's notifier. Only an announcement: it never
 * runs /gfs. The fail lines are Hypixel's, read by {@link PuzzleHud#parseChat}.
 */
public final class ArchitectDraftAlert {
    private ArchitectDraftAlert() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.architectDraft;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            Config c = config();
            if (c == null || (!c.enabled && !c.party) || !Sky2MLocation.inDungeon()) return;
            PuzzleHud.ChatLine line = PuzzleHud.parseChat(message.text());
            if (line == null || line.solved()) return;
            if (c.enabled) {
                String puzzle = line.puzzle() == null ? "a puzzle" : line.puzzle();
                Sky2MAlerts.chat(Component.literal("§c" + line.player() + " §7failed " + puzzle + ". §eGet an Architect's First Draft!"));
                Sky2MAlerts.title(Component.literal("§cPuzzle Failed!"), Component.literal("§eGet an Architect's First Draft"));
            }
            if (c.party) {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null && !com.epic60869.sky2m.sim.roomsim.SimState.isActive()) {
                    connection.sendCommand("pc [S2M] " + partyText(c.partyText, line.player()));
                }
            }
        });
    }

    /** The party message with [player] filled in. Public for the game test. */
    public static String partyText(String text, String player) {
        return text.replace("[player]", player);
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Tell Me", desc = "A title and a chat line when a puzzle fails, saying to get an Architect's First Draft. Only an announcement: it doesn't get one for you.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Tell Party", desc = "Also say it in party chat, starting with [S2M]. Teammates with this on will each say it too.")
        @ConfigEditorBoolean
        public boolean party = true;

        @Expose
        @ConfigOption(name = "Party Message", desc = "The party message. [player] is replaced with who failed the puzzle.")
        @ConfigEditorText
        public String partyText = "[player] failed a puzzle, get an Architect's First Draft!";
    }
}
