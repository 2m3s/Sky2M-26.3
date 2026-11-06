package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto Requeue, like NoammAddons': when a dungeon run ends (Hypixel's "> EXTRA STATS <" line), /instancerequeue a few
 * seconds later, so the party goes straight into another run of the same floor. With Cancel If Someone Leaves, it
 * doesn't requeue when someone left, was kicked or disconnected at any point in that run (from "Starting in 1 second."),
 * not only during the delay. /s2 requeue cancel and !dt in party chat skip the next one. /instancerequeue only works
 * for the leader. With Leave Title, a teammate leaving shows their name and class (in the class colour) as a title.
 */
public final class AutoRequeue {
    private static final Pattern MEMBER_GONE = Pattern.compile("(?:has left the party|has been removed from the party|was removed from your party because they disconnected|has disconnected|has been kicked from the party|You left the party|The party was disbanded)");
    private static long requeueAt;
    private static boolean skipNext;
    /** Someone left the party since this run started, so the run's end must not requeue. */
    private static boolean memberLeft;
    /** "[MVP+] Name has left the party." and the like: the name of who's gone. */
    private static final Pattern GONE_NAME = Pattern.compile("^(?:\\[[^\\]]+] )?(?<name>[A-Za-z0-9_]{1,16}) (?:has|was) ");
    /** The last class seen in the dungeon tab for each teammate, kept after they leave the tab list. */
    private static final Map<String, DungeonClass> classes = new HashMap<>();

    private AutoRequeue() {}

    private static FeatureConfigs.AutoRequeue config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.autoRequeue;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            FeatureConfigs.AutoRequeue c = config();
            if (c == null || !c.enabled) return;
            String text = message.text().trim();
            if (text.equals("Starting in 1 second.")) {
                memberLeft = false;
            } else if (text.equals("> EXTRA STATS <") && Sky2MLocation.inDungeon()
                    // The Dungeon Sim has no /instancerequeue; it rebuilds floors from its own menu.
                    && !com.epic60869.sky2m.sim.roomsim.SimState.isActive()) {
                if (skipNext) {
                    skipNext = false;
                    say("Skipped this requeue.");
                    return;
                }
                if (c.cancelOnLeave && memberLeft) {
                    memberLeft = false;
                    say("Not requeueing: someone left the party during the run.");
                    return;
                }
                requeueAt = System.currentTimeMillis() + c.delaySeconds * 1000L;
                if (c.announce) say("Requeueing in " + c.delaySeconds + "s. /s2 requeue cancel to stop.");
            } else if (MEMBER_GONE.matcher(text).find()) {
                // Remembered for the whole run, not only the delay after it: a teammate who left mid-run used to be
                // forgotten by the time the run ended, so it still requeued.
                memberLeft = true;
                if (c.leaveTitle && !text.contains(":")) leaveTitle(text);
                if (requeueAt != 0 && c.cancelOnLeave) {
                    requeueAt = 0;
                    say("Not requeueing: someone left the party.");
                }
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            rememberClasses();
            if (requeueAt == 0 || System.currentTimeMillis() < requeueAt) return;
            requeueAt = 0;
            FeatureConfigs.AutoRequeue c = config();
            if (c == null || !c.enabled || mc.getConnection() == null) return;
            if (c.cancelOnLeave && memberLeft) {
                memberLeft = false;
                say("Not requeueing: someone left the party.");
                return;
            }
            mc.getConnection().sendCommand("instancerequeue");
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("requeue")
                    .then(ClientCommands.literal("cancel").executes(ctx -> {
                        if (requeueAt != 0) {
                            requeueAt = 0;
                            say("Requeue cancelled.");
                        } else {
                            skipNext = true;
                            say("The next run won't requeue.");
                        }
                        return 1;
                    }))));
            }
        });
    }

    /**
     * !dt (downtime): cancels the requeue that's waiting, else skips the next one. Returns the party chat answer, or
     * null when Auto Requeue is off.
     */
    public static String downtime(String asker) {
        FeatureConfigs.AutoRequeue c = config();
        if (c == null || !c.enabled) return null;
        boolean waiting = requeueAt != 0;
        requeueAt = 0;
        if (!waiting) skipNext = true;
        say((waiting ? "Requeue cancelled" : "The next run won't requeue") + " (!dt from " + asker + ").");
        return waiting ? "Downtime: requeue cancelled." : "Downtime: won't requeue after this run.";
    }

    private static void rememberClasses() {
        DungeonPlayerManager.DungeonPlayer[] players = DungeonPlayerManager.getPlayers();
        if (players == null) return;
        for (DungeonPlayerManager.DungeonPlayer p : players) {
            // A ghost reads as Unknown, so keep the class it had.
    /** Who left, from a party leave, kick or disconnect line, or null ("You left the party", "The party was disbanded"). */
    public static String leftName(String text) {
        Matcher m = GONE_NAME.matcher(text);
        if (!m.find() || m.group("name").equals("You") || m.group("name").equals("The")) return null;
        return m.group("name");
    /** The title: "Name Tank", the class in its colour (no class if they weren't seen in a dungeon). */
    public static Component leaveTitleText(String name, DungeonClass dungeonClass) {
        if (dungeonClass != null && dungeonClass != DungeonClass.UNKNOWN) {
            title.append(Component.literal(" " + dungeonClass.displayName()).withColor(dungeonClass.color() & 0xFFFFFF));
        return title;
    private static void leaveTitle(String text) {
        if (name == null) return;
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String what = lower.contains("disconnect") ? "disconnected" : lower.contains("kicked") || lower.contains("removed") ? "was removed" : "left the party";
    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal(text).withStyle(ChatFormatting.YELLOW)));
    }
}
