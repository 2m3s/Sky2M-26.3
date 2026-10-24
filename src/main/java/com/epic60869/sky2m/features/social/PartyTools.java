package com.epic60869.sky2m.features.social;

import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.misc.PartyCommands;
import com.epic60869.sky2m.sim.util.ModChat;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party shortcut commands, filling the gaps next to {@link PartyCommands} (the ! commands): /rp (reparty), /pa (accept
 * the last party invite), /pback (transfer the party back to the previous leader) and /pkick (kick with a reason said
 * in party chat). Ideas from SkyHanni's party commands and Skyblocker's reparty. Turn on in Chat > Social Tools.
 */
public final class PartyTools {
    private static final String NAME = "(?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?";
    private static final Pattern INVITE = Pattern.compile("^" + NAME + "(?<inviter>\\w{1,16}) has invited you to join (?:their|" + NAME + "(?<leader>\\w{1,16})'s?) party!");
    private static final Pattern TRANSFER = Pattern.compile("^The party was transferred to " + NAME + "(?<now>\\w+) by " + NAME + "(?<by>\\w+)\\s*$");
    private static final long INVITE_LIFETIME_MS = 65_000L;
    /** How long /rp waits for /p list to be answered before disbanding. */
    private static final long LIST_WAIT_MS = 1_500L;

    private static String inviteTarget;
    private static long inviteAt;
    /** The leader before the last transfer, and who got the party in it. */
    private static String previousLeader;
    private static String currentLeader;
    private static long repartyAt;

    private PartyTools() {}

    private static boolean enabled() {
        SocialConfig c = SocialConfig.current();
        return c != null && c.partyShortcuts;
    }

    private static String me() {
        return Minecraft.getInstance().getUser().getName();
    }

    private static void say(String text) {
        ModChat.send("S2M", ModChat.text(text));
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            inviteTarget = null;
            previousLeader = null;
            currentLeader = null;
            repartyAt = 0;
        });
        Sky2MChat.onChat(PartyTools::onChat);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (repartyAt != 0 && System.currentTimeMillis() >= repartyAt) {
                repartyAt = 0;
                reparty();
            }
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            dispatcher.register(ClientCommands.literal("rp").executes(c -> run(PartyTools::startReparty)));
            dispatcher.register(ClientCommands.literal("pa").executes(c -> run(PartyTools::acceptLast)));
            dispatcher.register(ClientCommands.literal("pback").executes(c -> run(PartyTools::transferBack)));
            dispatcher.register(ClientCommands.literal("pkick")
                .then(ClientCommands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(PartyCommands.members(), builder))
                    .executes(c -> run(() -> kick(StringArgumentType.getString(c, "name"), "")))
                    .then(ClientCommands.argument("reason", StringArgumentType.greedyString())
                        .executes(c -> run(() -> kick(StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "reason")))))));
        });
    }

    private static int run(Runnable action) {
        Minecraft.getInstance().execute(() -> {
            if (!enabled()) {
                say("Party Shortcuts are off: turn them on in Chat > Social Tools.");
                return;
            }
            action.run();
        });
        return 1;
    }

    private static void onChat(Sky2MChat.Message message) {
        String text = message.text();
        Matcher m;
        if ((m = INVITE.matcher(text)).find()) {
            inviteTarget = m.group("leader") != null ? m.group("leader") : m.group("inviter");
            inviteAt = System.currentTimeMillis();
        } else if ((m = TRANSFER.matcher(text)).find()) {
            String now = m.group("now"), by = m.group("by");
            currentLeader = now.equalsIgnoreCase("you") ? me() : now;
            previousLeader = by.equalsIgnoreCase("you") ? me() : by;
        } else if (text.startsWith("The party was transferred to ") || text.equals("You left the party.")
            || text.endsWith("has disbanded the party!") || text.startsWith("You have been kicked from the party")) {
            // A transfer because the leader left (or leaving the party) leaves nobody to give it back to.
            previousLeader = null;
            currentLeader = null;
        }
    }

    private static void acceptLast() {
        if (inviteTarget == null || System.currentTimeMillis() - inviteAt > INVITE_LIFETIME_MS) {
            say("No party invite to accept (they run out after 60 seconds).");
            return;
        }
        String target = inviteTarget;
        inviteTarget = null;
        PartyCommands.queueCommand("party accept " + target);
        say("Accepting " + target + "'s party invite.");
    }

    private static void transferBack() {
        if (previousLeader == null) {
            say("No previous party leader known: the party hasn't been transferred since you joined it.");
            return;
        }
        String leader = PartyCommands.leader();
        if (leader != null && !leader.equalsIgnoreCase(me())) {
            say("You aren't the party leader (" + leader + " is).");
            return;
        }
        if (previousLeader.equalsIgnoreCase(me())) {
            say("You were the previous leader.");
            return;
        }
        PartyCommands.queueCommand("party transfer " + previousLeader);
        say("Giving the party back to " + previousLeader + ".");
    }

    private static void startReparty() {
        if (!PartyCommands.maybeLeader()) {
            say("You aren't the party leader (" + PartyCommands.leader() + " is).");
            return;
        }
        // /p list refreshes who is in the party (PartyCommands reads the answer), then reparty() runs.
        PartyCommands.queueCommand("party list");
        repartyAt = System.currentTimeMillis() + LIST_WAIT_MS;
        say("Checking who is in the party...");
    }

    private static void reparty() {
        List<String> members = PartyCommands.members();
        String leader = PartyCommands.leader();
        if (leader != null && !leader.equalsIgnoreCase(me())) {
            say("You aren't the party leader (" + leader + " is).");
            return;
        }
        if (members.isEmpty()) {
            say("Nobody else is in the party.");
            return;
        }
        PartyCommands.queueCommand("party disband");
        PartyCommands.queueCommand("party " + String.join(" ", members));
        say("Repartying " + members.size() + " player" + (members.size() == 1 ? "" : "s") + ".");
    }

    private static void kick(String typed, String reason) {
        if (typed.equalsIgnoreCase(me())) {
            say("You can't kick yourself.");
            return;
        }
        String name = typed;
        String start = typed.toLowerCase(Locale.ROOT);
        List<String> members = PartyCommands.members();
        List<String> matches = members.stream().filter(n -> n.equalsIgnoreCase(typed)).toList();
        if (matches.isEmpty()) matches = members.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        if (matches.size() > 1) {
            say("More than one player starts with " + typed + ": " + String.join(", ", matches));
            return;
        }
        if (matches.size() == 1) name = matches.getFirst();
        reason = reason.trim();
        // A party message over 256 characters gets you disconnected, so the reason is cut short.
        if (reason.length() > 150) reason = reason.substring(0, 150);
        if (!reason.isEmpty()) PartyCommands.partyChat("Kicking " + name + ": " + reason);
        PartyCommands.queueCommand("party kick " + name);
    }
}
