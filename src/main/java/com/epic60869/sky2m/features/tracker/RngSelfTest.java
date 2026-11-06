package com.epic60869.sky2m.features.tracker;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /s2 rnglist test: runs every drop on the list through {@link RngTracker} the way Hypixel sends it (each chat format, and
 * bought reward chests for dungeon and Kuudra drops), plus the things that must not count (other players, party chat,
 * refused chests), hand ticking and saving. Uses a throwaway file and puts your real list back afterwards.
 */
final class RngSelfTest {
    record Result(int passed, int failed, List<String> failures, int chatRuns, int chestRuns) {}

    private static int pass, fail, chatRuns, chestRuns;
    private static final List<String> failures = new ArrayList<>();
    static final List<String> notified = new ArrayList<>();
    private static Path file;

    static void check(boolean ok, String what) {
        if (ok) pass++;
        else {
            fail++;
            failures.add(what);
        }
    }

    static void reset() throws Exception {
        Files.deleteIfExists(file);
        RngTracker.resetForTest(file);
        notified.clear();
    }

    static Set<String> got() {
        return new HashSet<>(RngTracker.obtained().keySet());
    }

    /** Sends a message and checks exactly {@code expect} (or nothing, when null) was crossed off. */
    static void msg(String message, String expect, String what) {
        Set<String> before = got();
        RngTracker.onMessage(message);
        Set<String> added = got();
        added.removeAll(before);
        if (expect == null) check(added.isEmpty(), what + " -> expected nothing, got " + added + "  [" + message + "]");
        else check(added.equals(Set.of(expect)) || before.contains(expect) && added.isEmpty(),
            what + " -> expected " + expect + ", got " + added + "  [" + message + "]");
    }

    static void chest(int menu, List<String[]> stacks, Set<String> expect, String refuse, String what) {
        Set<String> before = got();
        RngTracker.chestBought(menu, stacks);
        for (int i = 0; i < RngTracker.CONFIRM_TICKS + 2; i++) {
            if (i == 5 && refuse != null) RngTracker.onMessage(refuse);
            RngTracker.tick();
        }
        Set<String> added = got();
        added.removeAll(before);
        Set<String> want = new HashSet<>(expect);
        want.removeAll(before);
        check(added.equals(want), what + " -> expected " + want + ", got " + added);
    }

    static boolean isBook(String item) {
        return item.matches(".* [IVX]+$") && !item.endsWith(" Rune I") && !item.startsWith("Master Skull");
    }

    private RngSelfTest() {}

    static synchronized Result run() throws Exception {
        pass = fail = chatRuns = chestRuns = 0;
        failures.clear();
        Path realFile = RngTracker.file;
        var realNotify = RngTracker.notify;
        var realSelf = RngTracker.selfName;
        Path dir = Files.createTempDirectory("sky2m-rng-test");
        file = dir.resolve("rng-drops.json");
        try {
            RngTracker.notify = notified::add;
            RngTracker.selfName = () -> "epic60869";
            reset();
            body();
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
            RngTracker.notify = realNotify;
            RngTracker.selfName = realSelf;
            RngTracker.resetForTest(realFile);
        }
        return new Result(pass, fail, List.copyOf(failures), chatRuns, chestRuns);
    }

    private static void body() throws Exception {

        Map<String, String> categoryOf = new LinkedHashMap<>();
        for (RngDropList.Category c : RngDropList.ALL) for (String item : c.items()) categoryOf.putIfAbsent(item, c.group() + ":" + c.name());

        // ---------------------------------------------------------------- 1. every drop, from chat, in each format
        String[] tags = {"RARE DROP!", "VERY RARE DROP!", "CRAZY RARE DROP!", "INSANE DROP!", "PRAY TO RNGESUS DROP!", "RNGESUS INCARNATE DROP!"};
        int t = 0;
        for (String item : categoryOf.keySet()) {
            String cat = categoryOf.get(item);
            List<String> forms = new ArrayList<>();
            String tag = tags[t++ % tags.length];
            if (isBook(item)) {
                forms.add(tag + " (Enchanted Book) (" + item + ")");
                forms.add(tag + " Enchanted Book (" + item + ") (+215% ✯ Magic Find)");
                forms.add("RARE REWARD! You found a Enchanted Book (" + item + ") in your Bedrock Chest!");
            } else if (cat.startsWith("Other:Diana")) {
                forms.add("RARE DROP! You dug out a " + item + "!");
                forms.add("Wow! You dug out a " + item + "!");
                forms.add(tag + " " + item + " (+300% ✯ Magic Find)");
            } else if (cat.startsWith("Other:Fishing")) {
                forms.add(tag + " " + item + " (+142% ✯ Magic Find)");
                forms.add("OUTSTANDING CATCH! You caught a " + item + "!");
            } else if (cat.startsWith("Other:Garden")) {
                forms.add("RARE DROP! " + item + " (+240% ☘ Pest Drop)");
                forms.add("PEST LOOT! " + item);
            } else if (item.equals("Spirit")) {
                forms.add("PET DROP! Spirit");
                forms.add("PET DROP! [Lvl 1] Spirit (+50% ✯ Magic Find)");
            } else {
                forms.add(tag + " (" + item + ") (+312% ✯ Magic Find)");
                forms.add(tag + " " + item);
                forms.add("RNGESUS INCARNATE DROP! (" + item + ") (+999% ✯ Magic Find)");
                forms.add("RARE REWARD! epic60869 found a " + item + " in their Obsidian Chest!");
                forms.add("   " + tag + " 2x " + item);
            }
            for (String form : forms) {
                reset();
                msg(form, item, "chat " + cat);
                chatRuns++;
                check(notified.equals(List.of(item)), "chat message shown for " + item + " got " + notified);
            }
        }

        // ---------------------------------------------------------------- 2. every dungeon/Kuudra drop, from a bought chest
        for (RngDropList.Category c : RngDropList.ALL) {
            if (!c.group().equals("Catacombs") && !c.group().equals("Master Mode") && !c.name().equals("Kuudra")) continue;
            for (String item : c.items()) {
                reset();
                List<String[]> stacks = new ArrayList<>();
                stacks.add(new String[]{"Wither Essence x24", "Dungeon Essence"});
                stacks.add(new String[]{"Undead Essence x14", ""});
                if (isBook(item)) stacks.add(new String[]{"Enchanted Book", item});
                else if (item.equals("Spirit")) stacks.add(new String[]{"[Lvl 1] Spirit", "Combat Pet"});
                else stacks.add(new String[]{item, "Some lore"});
                stacks.add(new String[]{"Open Reward Chest", "Cost"});
                chest(1000 + chestRuns, stacks, Set.of(item), null, "chest " + c.name() + " " + item);
                chestRuns++;
            }
        }

        // ---------------------------------------------------------------- 3. a full chest with several drops at once
        reset();
        chest(50, List.of(new String[]{"Necron's Handle", ""}, new String[]{"Enchanted Book", "Ultimate Wise II"},
                new String[]{"Recombobulator 3000", ""}, new String[]{"Wither Essence x30", ""}),
            Set.of("Necron's Handle", "Ultimate Wise II", "Recombobulator 3000"), null, "bedrock chest with 3 drops");

        // ---------------------------------------------------------------- 4. things that must NOT count
        reset();
        msg("RARE REWARD! Bob123 found a Necron's Handle in their Bedrock Chest!", null, "someone else's reward");
        msg("RNG DROP! [MVP+] Koehy just found a Necron's Handle!", null, "someone else's RNG drop line");
        msg("RARE DROP! Bob123 found a Warden Heart!", null, "someone else's drop, no rank");
        msg("Party > [MVP+] Bob: RARE DROP! (Warden Heart)", null, "party chat");
        msg("Guild > [VIP] Bob: PRAY TO RNGESUS DROP! Judgement Core", null, "guild chat");
        msg("[MVP+] Bob: RARE DROP! Warden Heart", null, "lobby chat");
        msg("From [MVP+] Bob: CRAZY RARE DROP! Overflux Capacitor", null, "private message");
        msg("RARE DROP! Smite VII", null, "Smite VII is not Smite VI");
        msg("RARE DROP! Revenant Flesh", null, "drop not on the list");
        msg("RARE DROP! Enchanted Book (Critical V)", null, "Critical V is not Critical VI");
        msg("You picked up a Warden Heart from the ground", null, "not a drop line");
        msg("[NPC] Kat: Warden Heart is a great item!", null, "NPC chat");
        chest(60, List.<String[]>of(new String[]{"Shadow Fury", ""}), Set.of(), "You don't have enough coins to open this chest!", "refused chest (no coins)");
        chest(61, List.<String[]>of(new String[]{"Giant's Sword", ""}), Set.of(), "You already opened this chest!", "refused chest (already opened)");
        chest(62, List.of(new String[]{"Wither Essence x20", ""}, new String[]{"Undead Essence x5", ""}), Set.of(), null, "chest with no listed drops");
        check(got().isEmpty(), "nothing crossed off after the negative tests, got " + got());

        // same chest clicked twice: counts once, no second message
        reset();
        chest(70, List.<String[]>of(new String[]{"Dark Claymore", ""}), Set.of("Dark Claymore"), null, "first click");
        RngTracker.chestBought(70, List.<String[]>of(new String[]{"Dark Claymore", ""}));
        for (int i = 0; i < 40; i++) RngTracker.tick();
        check(notified.size() == 1, "double click counted once, messages " + notified);
        // a drop you already have doesn't message again
        msg("RARE DROP! (Dark Claymore)", "Dark Claymore", "repeat drop");
        check(notified.size() == 1, "repeat drop doesn't message again, messages " + notified);

        // ---------------------------------------------------------------- 5. manual ticking
        reset();
        RngTracker.toggle("Warden Heart");
        check(RngTracker.obtained().containsKey("Warden Heart") && RngTracker.BY_HAND.equals(RngTracker.obtained().get("Warden Heart").how()), "tick by hand");
        check(notified.isEmpty(), "ticking by hand doesn't send the chat message");
        RngTracker.toggle("Warden Heart");
        check(!RngTracker.obtained().containsKey("Warden Heart"), "untick by hand");
        RngTracker.toggle("Judgement Core");
        msg("PRAY TO RNGESUS DROP! (Judgement Core)", "Judgement Core", "drop after ticking by hand");
        check(RngTracker.BY_HAND.equals(RngTracker.obtained().get("Judgement Core").how()), "a drop after a hand tick keeps the hand tick");
        msg("RARE DROP! (Scythe Blade)", "Scythe Blade", "auto drop");
        RngTracker.toggle("Scythe Blade");
        check(!RngTracker.obtained().containsKey("Scythe Blade"), "untick an auto-detected drop");

        // ---------------------------------------------------------------- 6. saving and loading
        reset();
        msg("RARE DROP! (Warden Heart)", "Warden Heart", "save setup");
        RngTracker.toggle("Necron's Handle");
        RngTracker.resetForTest(file); // drops what's in memory, reads the file again
        check(RngTracker.obtained().keySet().equals(new LinkedHashSet<>(List.of("Warden Heart", "Necron's Handle"))), "reloaded from file: " + RngTracker.obtained().keySet());
        check(RngTracker.obtained().get("Warden Heart").how().equals("RARE DROP"), "reloaded how: " + RngTracker.obtained().get("Warden Heart").how());
        // the first version's signed file still loads
        Files.writeString(file, "{\"got\":{\"Overflux Capacitor\":{\"time\":1793600000000,\"how\":\"CRAZY RARE DROP\"}},\"sig\":\"abc\"}");
        RngTracker.resetForTest(file);
        check(RngTracker.obtained().containsKey("Overflux Capacitor"), "old signed file loads");
        Files.writeString(file, "not json{{");
        RngTracker.resetForTest(file);
        check(RngTracker.obtained().isEmpty(), "broken file doesn't crash");

        // ---------------------------------------------------------------- 7. every list name maps to itself
        for (String item : categoryOf.keySet()) check(item.equals(RngTracker.find(item)), "find(" + item + ") = " + RngTracker.find(item));

    }

    /** For Sky2M's game test: what failed (empty when everything passed). */
    static List<String> failuresNow() throws Exception {
        return run().failures();
    }

    /** /s2 rnglist test: runs the tests and says how they went in chat. */
    static void chat(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        try {
            Result r = run();
            boolean ok = r.failed() == 0;
            source.sendFeedback(Component.literal("[Sky2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("RNG test: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(ok ? "all " + r.passed() + " checks passed" : r.failed() + " of " + (r.passed() + r.failed()) + " checks failed")
                    .withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal(" (" + RngTracker.total() + " drops, " + r.chatRuns() + " chat messages, " + r.chestRuns() + " reward chests)")
                    .withStyle(ChatFormatting.DARK_GRAY)));
            for (int i = 0; i < Math.min(8, r.failures().size()); i++) {
                source.sendFeedback(Component.literal("  " + r.failures().get(i)).withStyle(ChatFormatting.RED));
            }
            if (!ok) TrackLines.LOG.warn("RNG test failures:\n{}", String.join("\n", r.failures()));
        } catch (Exception e) {
            source.sendError(Component.literal("[Sky2M] RNG test couldn't run: " + e));
            TrackLines.LOG.error("RNG test couldn't run", e);
        }
    }

    /** Offline run, without the game: prints the result. */
    public static void main(String[] args) throws Exception {
        Result r = run();
        System.out.println(r.chatRuns() + " chat runs, " + r.chestRuns() + " chest runs");
        System.out.println("PASSED " + r.passed() + "   FAILED " + r.failed());
        for (String f : r.failures()) System.out.println("  FAIL " + f);
        System.exit(r.failed() == 0 ? 0 : 1);
    }
}
