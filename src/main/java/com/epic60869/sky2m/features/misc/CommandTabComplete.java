package com.epic60869.sky2m.features.misc;

import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Command Tab Complete, after SkyHanni's tabcomplete package (hannibal002/SkyHanni, LGPL-2.1): player names for
 * /p, /f, /g, /msg, /visit, /pv, /trade... (players on your island, your party, friends and guild), their subcommands,
 * /warp destinations, /gfs sack items, /show item slots and /visit's VIP islands. Completes the word at the cursor, in
 * the same popup as Command Autofill.
 */
final class CommandTabComplete {
    /** SkyHanni-REPO constants/Warps.json warpCommands. */
    private static final List<String> WARPS = List.of("arachne", "atoll", "backwater", "barn", "base", "basecamp", "bayou", "camp",
        "canyon", "carnival", "castle", "ch", "ci", "cn", "crimson", "crypt", "crypts", "crystals", "da", "deep", "deeper", "desert", "dh",
        "dhub", "dmines", "drag", "dragons", "dungeon_hub", "dungeons", "dwarves", "elizabeth", "end", "farming", "foraging", "forge",
        "galatea", "garden", "glacite", "glowing", "gold", "gt", "hollows", "home", "howl", "howling_cave", "hub", "island", "isle", "jerry",
        "jungle", "kuudra", "loch", "lotus", "mines", "moby", "moonglade", "mound", "murk", "murkwater", "museum", "nest", "nether", "nuc",
        "nucleus", "park", "rift", "safari", "sepulture", "skull", "smold", "smoldering", "smoldering_tomb", "spider", "spiders", "spring",
        "springs", "stonks", "taylor", "the_rift", "top", "torrhus", "torrhus_canyon", "tower", "trap", "trapper", "trees", "tunnel", "tunnels",
        "village", "void", "winter", "wiz", "wizard", "wizard_tower", "workshop");
    /** SkyHanni-REPO constants/VipVisits.json. */
    private static final List<String> VIP_VISITS = List.of("prtlhub", "PortalHub", "cake_hub", "Hubportal", "MelonKingDe", "Minikloon");
    private static final List<String> SHOW_ITEM = List.of("item", "helmet", "chestplate", "leggings", "boots", "necklace", "cloak", "belt",
        "bracelet", "gloves", "pet");
    private static final List<String> FRIEND_SUBS = List.of("accept", "add", "deny", "best", "remove", "nickname", "list", "help",
        "notifications", "removeall", "requests");
    private static final List<String> GUILD_SUBS = List.of("invite", "kick", "transfer", "setrank", "promote", "demote", "mute", "unmute",
        "member", "top", "toggle", "tagcolor", "tag", "slow", "settings", "rename", "quest", "permissions", "party", "onlinemode", "online",
        "officerchat", "notifications", "mypermissions", "motd", "menu", "members", "log", "leave", "info", "history", "help", "discord",
        "disband", "create", "chat", "accept");
    private static final List<String> PARTY_SUBS = List.of("accept", "invite", "kick", "demote", "promote", "transfer", "chat", "disband",
        "kickoffline", "leave", "list", "mute", "poll", "private", "settings", "warp");

    private enum Source { ISLAND, PARTY, FRIENDS, GUILD }

    private CommandTabComplete() {}

    static @Nullable Suggestions suggest(CommandAutofill.Config c, String uptoCursor) {
        String[] words = uptoCursor.substring(1).split(" ", -1);
        if (words.length < 2) return null;
        String command = words[0].toLowerCase(Locale.ROOT);
        int arg = words.length - 1;
        String sub = arg >= 2 ? words[1].toLowerCase(Locale.ROOT) : "";
        List<String> options = new ArrayList<>();
        switch (command) {
            case "p", "party" -> {
                if (!c.party && !c.players) return null;
                if (arg == 1) {
                    options.addAll(PARTY_SUBS);
                    options.addAll(players(c, Source.PARTY));
                } else if (arg == 2 && (sub.equals("accept") || sub.equals("invite"))) {
                    options.addAll(players(c, Source.PARTY));
                } else if (arg == 2 && List.of("kick", "demote", "promote", "transfer").contains(sub)) {
                    options.addAll(PartyCommands.members());
                }
            }
            case "f", "friend" -> {
                if (!c.players) return null;
                if (arg == 1) options.addAll(FRIEND_SUBS);
                else if (arg == 2 && List.of("accept", "add", "deny").contains(sub)) options.addAll(players(c, Source.FRIENDS));
                else if (arg == 2 && List.of("best", "remove", "nickname").contains(sub)) options.addAll(SocialNames.friends());
                else if (arg == 2 && sub.equals("list")) options.add("best");
            }
            case "g", "guild" -> {
                if (!c.players) return null;
                if (arg == 1) options.addAll(GUILD_SUBS);
                else if (arg == 2 && sub.equals("invite")) options.addAll(players(c, Source.GUILD));
                else if (arg == 2 && List.of("kick", "transfer", "setrank", "promote", "demote", "member").contains(sub)) options.addAll(SocialNames.guild());
                else if (arg == 2 && (sub.equals("mute") || sub.equals("unmute"))) {
                    options.addAll(SocialNames.guild());
                    options.add("everyone");
                }
            }
            case "w", "msg", "tell", "boop", "boo", "invite", "ah" -> {
                if (!c.players || arg != 1) return null;
                options.addAll(players(c, null));
            }
            case "visit" -> {
                if (!c.players || arg != 1) return null;
                options.addAll(players(c, null));
                if (c.vipVisits) options.addAll(VIP_VISITS);
            }
            case "pv" -> {
                if (!c.players || arg != 1) return null;
                options.addAll(players(c, null));
                var self = Minecraft.getInstance().player;
                if (self != null) options.add(self.getGameProfile().name());
            }
            case "trade" -> {
                if (!c.players || arg != 1) return null;
                options.addAll(islandPlayers());
            }
            case "warp" -> {
                if (!c.warps || arg != 1) return null;
                options.addAll(WARPS);
            }
            case "gfs", "getfromsacks" -> {
                if (!c.gfsSack || arg != 1) return null;
                for (String name : SackTracker.snapshot().keySet()) options.add(name.toUpperCase(Locale.ROOT).replace(' ', '_'));
            }
            case "show", "showitem", "showoff" -> {
                if (!c.showItem || arg != 1) return null;
                options.addAll(SHOW_ITEM);
            }
            default -> {
                return null;
            }
        }

        String word = words[arg];
        String lower = word.toLowerCase(Locale.ROOT);
        int start = uptoCursor.length() - word.length();
        StringRange range = StringRange.between(start, uptoCursor.length());
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> list = new ArrayList<>();
        for (boolean prefixPass : new boolean[]{true, false}) {
            for (String option : options.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()) {
                String key = option.toLowerCase(Locale.ROOT);
                boolean match = prefixPass ? key.startsWith(lower) : !key.startsWith(lower) && key.contains(lower);
                if (match && !key.equals(lower) && seen.add(key)) list.add(new Suggestion(range, option));
            }
        }
        return list.isEmpty() ? null : new Suggestions(range, list);
    }

    /** Everyone Tab Complete knows of, without {@code excluded} (who are already in it). */
    private static List<String> players(CommandAutofill.Config c, @Nullable Source excluded) {
        List<String> out = new ArrayList<>();
        if (c.players && excluded != Source.ISLAND) out.addAll(islandPlayers());
        if (c.party && excluded != Source.PARTY) out.addAll(PartyCommands.members());
        if (c.friends && excluded != Source.FRIENDS) out.addAll(SocialNames.friends());
        if (c.guild && excluded != Source.GUILD) out.addAll(SocialNames.guild());
        return out;
    }

    /** Real players on your island (Hypixel's NPCs and tab list entries are fake players, whose UUIDs aren't version 4). */
    private static List<String> islandPlayers() {
        Minecraft mc = Minecraft.getInstance();
        List<String> out = new ArrayList<>();
        if (mc.level == null) return out;
        for (Player player : mc.level.players()) {
            if (player == mc.player || player.getUUID().version() != 4) continue;
            String name = player.getGameProfile().name();
            if (name.matches("\\w{1,16}")) out.add(name);
        }
        return out;
    }
}
