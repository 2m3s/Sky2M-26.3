package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lets party members run !warp, !allinvite, !pt, !promote and Odin's !f1-!f7, !m1-!m7 and !t1-!t5, and answers !fps,
 * !ping and !tps. The leader commands are always tried, never held back because Sky2M thinks someone else leads: if
 * you don't lead, Hypixel says "You are not this party's leader!" instead of the command silently doing nothing (the
 * leader is guessed from party messages, which can be wrong). The answers to !fps, !ping and !tps work (Odin's ChatCommands, https://github.com/odtheking/Odin, BSD-3-Clause)
 * whoever is leader. Leadership is tracked from Hypixel's party messages. SBO's party commands
 * (https://github.com/SkyblockOverhaul/SBO, Apache-2.0) add !demote, !carrot, !time and the Diana ones (!chim, !since,
 * !profit, ...; see {@link com.epic60869.sky2m.features.combat.DianaPartyCommands}).
 */
public final class PartyCommands {
    // (?:[^\w\s\[]+ )? skips a Hypixel emblem (☘, ☣, ...) before a name: "Party > [MVP++] ☘ Name: !warp".
    private static final Pattern PARTY_CHAT = Pattern.compile("^Party > (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w+)[^:]*: !(?<command>\\w+)(?: +(?:[^\\w\\s\\[]+ )?(?<arg>\\S+))?(?<more> +\\S.*?)?\\s*$");
    private static final Pattern INVITED = Pattern.compile("^(?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<me>\\w+) invited (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?\\w+ to the party!");
    private static final Pattern TRANSFERRED = Pattern.compile("^The party was transferred to (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w+)");
    private static final Pattern JOINED_OTHER = Pattern.compile("^You have joined (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w+)'s? party!");
    private static final Pattern LEADER_LIST = Pattern.compile("^Party Leader: (?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w+)");
    // Who's in the party, for "!pt nix" -> NixJussid: joins, leaves, kicks, /p list and the list when you join.
    private static final String PLAYER = "(?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w+)";
    private static final Pattern MEMBER_JOINED = Pattern.compile("^" + PLAYER + " joined the party\\.");
    private static final Pattern MEMBER_LEFT = Pattern.compile("^" + PLAYER + " (?:has left the party|has been removed from the party|was removed from your party because they disconnected)");
    private static final Pattern MEMBER_LIST = Pattern.compile("^(?:Party (?:Leader|Moderators|Members): |You'll be partying with: )(?<names>.+)$");
    private static final Pattern LIST_NAME = Pattern.compile("(?:\\[[^]]+] )?(?:[^\\w\\s\\[]+ )?(?<name>\\w{1,16})");

    private static final Pattern INSTANCE = Pattern.compile("([fmt])([1-7])");
    private static final String[] FLOORS = {"ENTRANCE", "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN"};
    private static final String[] KUUDRA = {"NORMAL", "HOT", "BURNING", "FIERY", "INFERNAL"};

    /** Hypixel says "Woah slow down" to chat sent this soon after your last message. */
    private static final long SEND_GAP_MS = 1_200L;
    /** Like Odin, answer a moment after the ! message, not in the same tick. */
    private static final long REPLY_DELAY_MS = 250L;

    /** SBO's !carrot answers. */
    private static final List<String> CARROT = List.of("As I see it, Carrot", "It is Carrot", "It is decidedly Carrot",
        "Most likely Carrot", "Outlook Carrot", "Signs point to Carrot", "Without a Carrot", "Yes - Carrot", "Carrot - definitely",
        "You may rely on Carrot", "Ask Carrot later", "Carrot predict now", "Concentrate and ask Carrot ",
        "Don't count on it - Carrot 2024", "My reply is Carrot", "My sources say Carrot", "Outlook not so Carrot", "Very Carrot");

    private record Pending(String command, long at) {}

    private static String leader;
    /** Party members seen in party messages (not you), in the case Hypixel shows them. */
    private static final Set<String> members = new LinkedHashSet<>();
    /** A leader command that came while the leader was unknown, run if /p list shows you lead. */
    private static Pending afterLeaderKnown;
    private static long askedLeaderAt;
    private static long lastCommand;
    private static long lastSent;
    /** Commands waiting to be sent, one at a time so Hypixel doesn't say "Woah slow down". */
    private static final Deque<Pending> pending = new ArrayDeque<>();

    private PartyCommands() {}

    public static void init() {
        Sky2MChat.onChat(PartyCommands::onChat);
        ClientSendMessageEvents.CHAT.register(message -> lastSent = System.currentTimeMillis());
        ClientSendMessageEvents.COMMAND.register(command -> lastSent = System.currentTimeMillis());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Pending next = pending.peek();
            long now = System.currentTimeMillis();
            if (next == null || now < next.at() || now < lastSent + SEND_GAP_MS) return;
            pending.poll();
            if (mc.getConnection() != null) mc.getConnection().sendCommand(next.command());
        });
    }

    private static String me() {
        return Minecraft.getInstance().getUser().getName();
    }

    private static void onChat(Sky2MChat.Message message) {
        String text = message.text();
        Matcher m;
        trackMembers(text);
        if ((m = INVITED.matcher(text)).find() && m.group("me").equalsIgnoreCase(me())) leader = me();
        else if ((m = TRANSFERRED.matcher(text)).find()) leader = m.group("name");
        else if ((m = JOINED_OTHER.matcher(text)).find()) leader = m.group("name");
        else if ((m = LEADER_LIST.matcher(text)).find()) {
            leader = m.group("name");
            onLeaderKnown();
        }
        else if (text.equals("You left the party.") || text.endsWith("has disbanded the party!")
            || text.startsWith("You have been kicked from the party") || text.equals("The party was disbanded because all invites expired and the party was empty.")) {
            leader = null;
            members.clear();
        }

        Sky2MConfig c = Sky2MConfig.current();
        if (c == null) return;
        FeatureConfigs.PartyCommands config = c.misc.partyCommands;
        if (!config.enabled || !(m = PARTY_CHAT.matcher(text)).find()) return;
        String sender = m.group("name");
        String command = m.group("command").toLowerCase(Locale.ROOT);
        String arg = m.group("arg");
        // Like SBO, a command with more words after it than it takes isn't one.
        if (m.group("more") != null) return;
        if (!sender.equalsIgnoreCase(me())) addMember(sender);

        if (config.diana) {
            // A short list: a party message over 256 characters gets you disconnected.
            String diana = command.equals("help") ? "Diana: !chim !inq !king !manti !sphinx !relic !stick !core !wool !mobs !burrows !profit !since <drop/mob>"
                : com.epic60869.sky2m.features.combat.DianaPartyCommands.reply(command, arg, me());
            if (diana != null) {
                run("pc " + diana);
                return;
            }
        }

        // Anyone in the party (you too) can ask these, like Odin's chat commands.
        String reply = switch (command) {
            case "fps" -> config.fps ? "Current FPS: " + ServerInfo.fps() : null;
            case "ping" -> {
                Integer ping = ServerInfo.ping();
                yield config.ping ? "Current Ping: " + (ping == null ? "?" : ping) + "ms" : null;
            }
            case "tps" -> {
                Double tps = ServerInfo.tps();
                yield config.tps ? "Current TPS: " + (tps == null ? "?" : String.format(Locale.ROOT, "%.1f", tps)) : null;
            }
            case "carrot", "c" -> config.carrot ? CARROT.get(ThreadLocalRandom.current().nextInt(CARROT.size())) : null;
            case "time" -> config.time ? LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) : null;
            default -> null;
        };
        if (reply != null) {
            run("pc " + reply);
            return;
        }

        // Your own !warp, !allinvite and !f7 work too (like Odin); transferring or promoting yourself doesn't.
        boolean self = sender.equalsIgnoreCase(me());
        // One !kick in a hundred from someone else, while you lead, kicks them instead.
        if (command.equals("kick") && config.kick && arg != null && !self && me().equalsIgnoreCase(leader)
            && ThreadLocalRandom.current().nextInt(100) == 0) {
            partyChat("The !kick backfired! Bye " + sender + "!");
            run("party kick " + sender);
            return;
        }
        String toRun = switch (command) {
            case "warp", "w" -> config.warp ? "party warp" : null;
            case "allinvite", "allinv" -> config.allInvite ? "party settings allinvite" : null;
            // "!pt Name" transfers to Name (yours too); "!pt" and "!ptme" to whoever asked. A name can be just the
            // start of one: "!pt nix" transfers to NixJussid.
            case "pt", "transfer" -> config.transfer && (arg != null || !self) ? playerCommand("party transfer ", arg == null ? sender : arg) : null;
            case "ptme" -> config.transfer && !self ? "party transfer " + sender : null;
            case "promote" -> config.promote && (arg != null || !self) ? playerCommand("party promote ", arg == null ? sender : arg) : null;
            case "demote" -> config.promote && (arg != null || !self) ? playerCommand("party demote ", arg == null ? sender : arg) : null;
            // "!kick nix" kicks NixJussid; never you.
            case "kick" -> config.kick && arg != null ? playerCommand("party kick ", arg) : null;
            default -> instance(config, command);
        };
        if (toRun == null) return;
        // Always tried: Hypixel answers "You are not this party's leader!" when you aren't.
        run(toRun);
    }

    private static void trackMembers(String text) {
        Matcher m;
        if ((m = MEMBER_JOINED.matcher(text)).find()) addMember(m.group("name"));
        else if ((m = MEMBER_LEFT.matcher(text)).find()) {
            String name = m.group("name");
            members.removeIf(n -> n.equalsIgnoreCase(name));
        } else if ((m = TRANSFERRED.matcher(text)).find()) addMember(m.group("name"));
        else if ((m = JOINED_OTHER.matcher(text)).find()) {
            members.clear();
            addMember(m.group("name"));
        } else if ((m = MEMBER_LIST.matcher(text)).find()) {
            // "[MVP+] Nix ● [VIP] Steve ●" or "You'll be partying with: [MVP+] Nix, Steve"
            for (String part : m.group("names").split("●|, ")) {
                Matcher n = LIST_NAME.matcher(part.trim());
                if (n.find()) addMember(n.group("name"));
            }
        }
    }

    private static void addMember(String name) {
        if (name == null || name.equalsIgnoreCase(me())) return;
        members.removeIf(n -> n.equalsIgnoreCase(name));
        members.add(name);
    }

    /**
     * The command for the party member a typed name means: the exact name, else the one member whose name starts
     * with it ("nix" -> NixJussid). Null when it would kick you or several members match (said in party chat); the
     * name as typed when no member matches (Hypixel then says who it couldn't find).
     */
    private static String playerCommand(String command, String typed) {
        boolean kick = command.equals("party kick ");
        if (typed.equalsIgnoreCase(me())) return kick ? null : command + me();
        for (String member : members) if (member.equalsIgnoreCase(typed)) return command + member;
        String start = typed.toLowerCase(Locale.ROOT);
        List<String> matches = members.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(start)).toList();
        if (matches.size() == 1) return command + matches.getFirst();
        if (matches.size() > 1) {
            run("pc More than one player starts with " + typed + ": " + String.join(", ", matches));
            return null;
        }
        // Never kick yourself by the start of your name ("!kick 2m" when you're 2m3s).
        if (kick && me().toLowerCase(Locale.ROOT).startsWith(start)) return null;
        return command + typed;
    }

    /** Called when "Party Leader:" says who leads: runs the command that was waiting for it, if you lead. */
    private static void onLeaderKnown() {
        Pending waiting = afterLeaderKnown;
        afterLeaderKnown = null;
        if (waiting == null || System.currentTimeMillis() - waiting.at() > 8_000L || !me().equalsIgnoreCase(leader)) return;
        long now = System.currentTimeMillis();
        lastCommand = now;
        Minecraft.getInstance().execute(() -> pending.add(new Pending(waiting.command(), now + REPLY_DELAY_MS)));
    }

    /** !f1-!f7, !m1-!m7 and !t1-!t5: Hypixel's /joininstance for that floor or tier (Odin's queue commands). */
    private static String instance(FeatureConfigs.PartyCommands config, String command) {
        Matcher m = INSTANCE.matcher(command);
        if (!m.matches()) return null;
        int n = Integer.parseInt(m.group(2));
        return switch (m.group(1)) {
            case "f" -> config.floors ? "joininstance CATACOMBS_FLOOR_" + FLOORS[n] : null;
            case "m" -> config.masterFloors ? "joininstance MASTER_CATACOMBS_FLOOR_" + FLOORS[n] : null;
            default -> config.kuudra && n <= 5 ? "joininstance KUUDRA_" + KUUDRA[n - 1] : null;
        };
    }

    private static void run(String command) {
        // Small cooldown so a spammed command is only run once.
        if (System.currentTimeMillis() - lastCommand <= 1500) return;
        long now = System.currentTimeMillis();
        lastCommand = now;
        // Sent from the tick, once it's been long enough since the last message you sent.
        Minecraft.getInstance().execute(() -> pending.add(new Pending(command, now + REPLY_DELAY_MS)));
    }

    /** Whether you might lead the party: true unless party messages showed someone else leads. */
    public static boolean maybeLeader() {
        return leader == null || leader.equalsIgnoreCase(me());
    }

    /** Sends a command through the same queue, so it doesn't trip Hypixel's "slow down". */
    public static void queueCommand(String command) {
        long now = System.currentTimeMillis();
        Minecraft.getInstance().execute(() -> pending.add(new Pending(command, now + 300)));
    }

    /** Says something in party chat, queued behind anything else waiting to be sent (Diana drop announcements). */
    public static void partyChat(String message) {
        long now = System.currentTimeMillis();
        Minecraft.getInstance().execute(() -> pending.add(new Pending("pc " + message, now)));
    }
}
