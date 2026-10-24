package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.google.gson.annotations.Expose;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Dungeons > Run Recap: the config and start-up of Death Recap ({@link DeathRecap}), the Class Cooldown HUD
 * ({@link ClassCooldowns}), the Run Summary card ({@link RunSummary}, {@link RunSummaryScreen}) and Boss Phase Notes
 * ({@link BossNotes}). All display only and off by default.
 */
public final class DungeonRecapExtras {
    private DungeonRecapExtras() {}

    /** The config, or null before it has loaded. */
    public static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.recapExtras;
    }

    public static void init() {
        DeathRecap.init();
        ClassCooldowns.init();
        BossNotes.init();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("deathrecap").executes(c -> {
                        Minecraft.getInstance().execute(DeathRecap::printLast);
                        return 1;
                    }))
                    .then(ClientCommands.literal("runsummary")
                        .executes(c -> {
                            Minecraft.getInstance().execute(RunSummary::open);
                            return 1;
                        })
                        // The [View] button on a run summary in S2C (not meant to be typed).
                        .then(ClientCommands.literal("view").then(ClientCommands.argument("id", StringArgumentType.word())
                            .executes(c -> {
                                String id = StringArgumentType.getString(c, "id");
                                Minecraft.getInstance().execute(() -> RunSummary.view(id));
                                return 1;
                            })))));
            }
        });
    }

    /** From RunHistory when a run has been saved: attach the death log, then print the recap and post the summary to /s2c if that is on. */
    static void onRunFinished(RunHistory.Run run) {
        try {
            DeathRecap.onRunFinished(run);
            RunSummary.onRunFinished(run);
        } catch (Exception e) {
            Sky2MAlerts.chat(Component.literal("Run recap failed: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    public static final class Config {
        @Expose
        @Accordion
        @ConfigOption(name = "Death Recap", desc = "Who died in the run, to what, in which room and when, listed at the end of the run and with /s2 deathrecap. Also saved with the run in Run History.")
        public DeathRecapConfig deathRecap = new DeathRecapConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Class Cooldowns", desc = "A HUD with your dungeon class and the cooldowns that can be read from chat: Bonzo's Mask, Spirit Mask and Phoenix Pet, and how long ago you used your class ultimate.")
        public ClassCooldownConfig classCooldowns = new ClassCooldownConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Run Summary Card", desc = "A card for the run that just ended (floor, time, score, secrets, deaths, splits, party, chest profit): open it with /s2 runsummary. It can be posted to /s2c with a [View] button anyone there can click. Nothing is sent to party or guild chat. Needs Run History (Timers and Alerts) to be on.")
        public SummaryConfig runSummary = new SummaryConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Boss Phase Notes", desc = "A short note for each boss phase, shown when the phase changes. Edit the notes for each floor below. Needs Boss Phases to be on for the floor.")
        public NotesConfig bossNotes = new NotesConfig();
    }

    public static final class DeathRecapConfig {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Record every death in the run (from Hypixel's death lines) with its cause, the room and the run time.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Chat Recap At End", desc = "List the deaths in chat when the run ends (needs Run History on). /s2 deathrecap shows them again.")
        @ConfigEditorBoolean
        public boolean chatAtEnd = true;
    }

    public static final class ClassCooldownConfig {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the cooldown HUD in dungeons (move it in /s2 gui). Timers start from the chat line each mask or pet prints when it saves you, so it only knows about ones you have used this run.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show Ready", desc = "Keep a mask or pet on the HUD as Ready once its cooldown is over.")
        @ConfigEditorBoolean
        public boolean showReady = true;

        @Expose
        @ConfigOption(name = "Ready Alert", desc = "A beep and a chat line when a mask or pet comes off cooldown.")
        @ConfigEditorBoolean
        public boolean readyAlert = false;
    }

    public static final class SummaryConfig {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Build a summary card for each run (/s2 runsummary).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Post Run Summary to /s2c", desc = "When you clear a run, post one message to /s2c (Sky2M chat) like 'Name cleared F7 in 4:21 (score 305)' with a [View] button that opens the full summary for everyone who clicks it. This is public, so it is off by default. You can also post a run by hand from /s2 runsummary.")
        @ConfigEditorBoolean
        public boolean postToS2c = false;
    }

    public static final class NotesConfig {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the note for a boss phase when the phase starts.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Chat Tips", desc = "Also print the note in chat.")
        @ConfigEditorBoolean
        public boolean chatTips = true;

        @Expose
        @ConfigOption(name = "HUD", desc = "Show the note in a HUD (move it in /s2 gui).")
        @ConfigEditorBoolean
        public boolean hud = true;

        @Expose
        @ConfigOption(name = "HUD Seconds", desc = "How long the note stays on the HUD after the phase starts.")
        @ConfigEditorSlider(minValue = 3, maxValue = 60, minStep = 1)
        public float hudSeconds = 15;

        @Expose
        @ConfigOption(name = "F1 / M1 Notes", desc = "Phase: note | Phase: note. Phases: Bonzo 1, Bonzo 2.")
        @ConfigEditorText
        public String f1 = "Bonzo 1: Kill the adds around Bonzo and burst him down before he revives. | Bonzo 2: He comes back for a second health bar, finish him quickly.";

        @Expose
        @ConfigOption(name = "F2 / M2 Notes", desc = "Phase: note | Phase: note. Phases: Minions, Scarf.")
        @ConfigEditorText
        public String f2 = "Minions: Clear Scarf's minions first, he can't be hurt properly while they are alive. | Scarf: Focus Scarf himself and keep killing any minions he calls.";

        @Expose
        @ConfigOption(name = "F3 / M3 Notes", desc = "Phase: note | Phase: note. Phases: Guardians, Professor, Professor 2.")
        @ConfigEditorText
        public String f3 = "Guardians: Kill the Guardians, the Professor can't be damaged yet. | Professor: Damage the Professor, dodge his projectiles. | Professor 2: Final stand, the Guardians come back, keep killing them and him.";

        @Expose
        @ConfigOption(name = "F4 / M4 Notes", desc = "Phase: note | Phase: note. Phases: Thorn.")
        @ConfigEditorText
        public String f4 = "Thorn: Kill the Spirit animals Thorn sends and keep hitting Thorn, stay near your team.";

        @Expose
        @ConfigOption(name = "F5 / M5 Notes", desc = "Phase: note | Phase: note. Phases: Livid.")
        @ConfigEditorText
        public String f5 = "Livid: Only the Livid matching the colour of the glass is real, kill that one and ignore the clones.";

        @Expose
        @ConfigOption(name = "F6 / M6 Notes", desc = "Phase: note | Phase: note. Phases: Terracottas, Giants, Sadan.")
        @ConfigEditorText
        public String f6 = "Terracottas: Kill the terracottas that rise and keep moving. | Giants: Kill the Giants, mind their stomps and swings. | Sadan: Final phase, burst Sadan down.";

        @Expose
        @ConfigOption(name = "F7 / M7 Notes", desc = "Phase: note | Phase: note. Phases: Maxor, Storm, Terminals 1-4, Goldor, Necron, Wither King. 'Terminals' alone covers all four terminal phases.")
        @ConfigEditorText
        public String f7 = "Maxor: Place the Energy Crystals to expose him, then damage him. | Storm: Break the pillars, watch for the lightning (PY) and stay out of it. | Terminals: Finish this section's terminals, levers and devices and avoid Goldor's death tick. | Goldor: Damage Goldor at the core while it is open. | Necron: Burst him down before the phase ends. | Wither King: Kill the dragons by colour, then the Wither King.";
    }
}
