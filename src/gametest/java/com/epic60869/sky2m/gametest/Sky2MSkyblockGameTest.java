package com.epic60869.sky2m.gametest;

import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.slayer.SlayerArea;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fake SkyBlock: a singleplayer world dressed up as Hypixel SkyBlock, so features that only run there can be seen
 * working in a test. A SKYBLOCK sidebar (built on the client, the way Hypixel's arrives) makes Sky2M think it is on
 * Hypixel SkyBlock; chat lines are fed in as server messages; menus are fake chests laid out like Hypixel's; mobs and
 * name tags are summoned. Each scenario screenshots what it shows (build/run/clientGameTest/screenshots/sb-*) and checks
 * what it can; the test fails if any check fails or a scenario throws.
 */
public class Sky2MSkyblockGameTest implements FabricClientGameTest {
    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            ctx.waitTicks(60);
            ctx.runOnClient(mc -> mc.options.guiScale().set(2));
            world.getServer().runCommand("time set noon");
            world.getServer().runCommand("gamerule doDaylightCycle false");

            scenario(ctx, "location", () -> location(ctx));
            scenario(ctx, "slayer quest", () -> slayerQuest(ctx, world));
            scenario(ctx, "rare drops", () -> rareDrops(ctx));
            scenario(ctx, "items on ground", () -> itemsOnGround(ctx, world));
            scenario(ctx, "skills menu", () -> skillsMenu(ctx));
            scenario(ctx, "chocolate factory", () -> chocolateFactory(ctx));
            scenario(ctx, "dungeon", () -> dungeon(ctx));
            scenario(ctx, "garden", () -> garden(ctx));
            scenario(ctx, "mining", () -> mining(ctx));
            scenario(ctx, "fishing", () -> fishing(ctx));
            scenario(ctx, "diana", () -> diana(ctx));
            scenario(ctx, "kuudra", () -> kuudra(ctx));
            scenario(ctx, "the end", () -> theEnd(ctx));
            scenario(ctx, "crimson isle", () -> crimson(ctx));
            scenario(ctx, "the rift", () -> rift(ctx));
            scenario(ctx, "menus", () -> menus(ctx));
            scenario(ctx, "party and chat", () -> partyChat(ctx));
            scenario(ctx, "every /s2 command", () -> everyCommand(ctx));

            ctx.runOnClient(mc -> clearSidebar(mc));
            Sky2MLocation.testArea = null;
        }
        // A command can queue a screen that opens after the world closes; tests have to end on the title screen.
        ctx.waitTicks(10);
        ctx.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        ctx.waitTicks(5);
        if (!failures.isEmpty()) throw new AssertionError("Fake SkyBlock checks failed:\n  " + String.join("\n  ", failures));
    }

    // ------------------------------------------------------------------------------------------------ scenarios

    /** A Hub sidebar: Sky2M must think it's on Hypixel SkyBlock, in the Village. */
    private void location(ClientGameTestContext ctx) {
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Village", "", "Purse: 1,234,567", "Bits: 0", "", "www.hypixel.net");
        Sky2MLocation.testArea = "Hub";
        ctx.waitTicks(20);
        check(ctx.computeOnClient(mc -> Sky2MLocation.onSkyblock()), "on SkyBlock from the sidebar");
        check(ctx.computeOnClient(mc -> com.epic60869.sky2m.custom.util.Compat.isOnSkyblock()), "Compat.isOnSkyblock");
        check("Village".equals(ctx.computeOnClient(mc -> Sky2MLocation.location())), "location Village (got " + ctx.computeOnClient(mc -> Sky2MLocation.location()) + ")");
        check("Hub".equals(ctx.computeOnClient(mc -> Sky2MLocation.area())), "area Hub");
        shot(ctx, "sb-01-hub");
    }

    /** A Tarantula quest in the Spider Mound with the boss up: the slayer HUDs. */
    private void slayerQuest(ClientGameTestContext ctx, TestSingleplayerContext world) {
        Sky2MLocation.testArea = "Spider's Den";
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Spider Mound", "", "Purse: 1,134,567", "", "Slayer Quest", "Tarantula Broodfather V",
            "Slay the boss!", "", "www.hypixel.net");
        ctx.waitTicks(20);
        check("Tarantula Broodfather".equals(ctx.computeOnClient(mc -> SlayerArea.questSlayer())), "quest slayer Tarantula");
        check("Tarantula Broodfather".equals(ctx.computeOnClient(mc -> SlayerArea.slayerHere())), "Spider Mound is Tarantula's area");
        chat(ctx, "  SLAYER QUEST STARTED!");
        chat(ctx, "   » Slay 2,000 Combat XP worth of Spiders.");
        // The boss and its tags, as Hypixel names them, in front of you.
        world.getServer().runCommand("summon cave_spider ~ ~ ~4 {NoAI:1b,PersistenceRequired:1b}");
        world.getServer().runCommand("summon armor_stand ~ ~0.9 ~4 {Invisible:1b,NoGravity:1b,Marker:1b,CustomNameVisible:1b,CustomName:\"☠ Tarantula Broodfather V 2.4M❤\"}");
        world.getServer().runCommand("summon armor_stand ~ ~0.6 ~4 {Invisible:1b,NoGravity:1b,Marker:1b,CustomNameVisible:1b,CustomName:\"Spawned by: Player0\"}");
        world.getServer().runCommand("summon armor_stand ~ ~1.2 ~4 {Invisible:1b,NoGravity:1b,Marker:1b,CustomNameVisible:1b,CustomName:\"03:58\"}");
        chat(ctx, "SLAYER BOSS! The Tarantula Broodfather V spawned!");
        ctx.waitTicks(40);
        shot(ctx, "sb-02-slayer-boss");
        world.getServer().runCommand("kill @e[type=cave_spider]");
        world.getServer().runCommand("kill @e[type=armor_stand]");
        chat(ctx, "  NICE! SLAYER BOSS SLAIN!");
        chat(ctx, "   → Spider Slayer LVL 9 - Next LVL in 1,234,567 XP!");
        chat(ctx, "  SLAYER QUEST COMPLETE!");
        ctx.waitTicks(60);
        openChat(ctx);
        shot(ctx, "sb-03-slayer-complete-chat");
        ctx.setScreen(() -> null);
    }

    /** Rare and RNG drop lines: the chat tweaks and the RNG HUD. */
    private void rareDrops(ClientGameTestContext ctx) {
        chat(ctx, "RARE DROP! Tarantula Talisman (+212% ✯ Magic Find)");
        chat(ctx, "VERY RARE DROP! Fly Swatter (+212% ✯ Magic Find)");
        chat(ctx, "CRAZY RARE DROP! Digested Mosquito (+212% ✯ Magic Find)");
        chat(ctx, "PET DROP! Tarantula (+212% ✯ Magic Find)");
        ctx.waitTicks(60);
        shot(ctx, "sb-04-rare-drops-hud");
        openChat(ctx);
        shot(ctx, "sb-05-rare-drops-chat");
        ctx.setScreen(() -> null);
    }

    /** Slayer drops on the ground near you during the quest: Items on Ground labels. */
    private void itemsOnGround(ClientGameTestContext ctx, TestSingleplayerContext world) {
        world.getServer().runCommand("summon item ~2 ~ ~3 {PickupDelay:32767,Item:{id:\"minecraft:player_head\",count:1,components:{"
            + "\"minecraft:custom_name\":\"Tarantula Talisman\",\"minecraft:custom_data\":{id:\"TARANTULA_TALISMAN\"}}}}");
        world.getServer().runCommand("summon item ~-2 ~ ~3 {PickupDelay:32767,Item:{id:\"minecraft:string\",count:64,components:{"
            + "\"minecraft:custom_name\":\"Tarantula Web\",\"minecraft:custom_data\":{id:\"TARANTULA_WEB\"}}}}");
        world.getServer().runCommand("summon item ~ ~ ~5 {PickupDelay:32767,Item:{id:\"minecraft:diamond\",count:1,components:{"
            + "\"minecraft:custom_name\":\"Digested Mosquito\",\"minecraft:custom_data\":{id:\"DIGESTED_MOSQUITO\"}}}}");
        ctx.waitTicks(40);
        shot(ctx, "sb-06-items-on-ground");
        world.getServer().runCommand("kill @e[type=item]");
    }

    /** The Your Skills menu: each skill's level as its stack size. */
    private void skillsMenu(ClientGameTestContext ctx) {
        sidebar(ctx, "Late Spring 7th", "⏣ Village", "", "Purse: 1,134,567", "", "www.hypixel.net");
        String[][] skills = {{"Combat", "LVIII", "iron_sword"}, {"Farming", "LX", "golden_hoe"}, {"Mining", "XLV", "stone_pickaxe"},
            {"Foraging", "XXXV", "jungle_sapling"}, {"Fishing", "XXV", "fishing_rod"}, {"Enchanting", "LX", "enchanting_table"},
            {"Alchemy", "L", "brewing_stand"}, {"Carpentry", "L", "crafting_table"}, {"Taming", "LX", "spawner"}};
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 29, 30};
        SimpleContainer container = new SimpleContainer(54);
        for (int i = 0; i < 54; i++) container.setItem(i, stack("black_stained_glass_pane", " "));
        for (int i = 0; i < skills.length; i++) {
            container.setItem(slots[i], stack(skills[i][2], skills[i][0] + " " + skills[i][1], "Progress to Level " + skills[i][1] + ": 50%",
                "", "Click to view!"));
        }
        menu(ctx, container, "Your Skills");
        shot(ctx, "sb-07-skills-menu");
        ctx.setScreen(() -> null);
    }

    /** The Chocolate Factory, laid out like Hypixel's: its stats HUD and upgrade highlight. */
    private void chocolateFactory(ClientGameTestContext ctx) {
        SimpleContainer container = new SimpleContainer(54);
        for (int i = 0; i < 54; i++) container.setItem(i, stack("black_stained_glass_pane", " "));
        container.setItem(13, stack("cookie", "5,272,230 Chocolate", "3,780.72 per second", "", "All-time Chocolate: 641,119,115"));
        container.setItem(27, stack("dropper", "Chocolate Factory V", "Chocolate this Prestige: 483,023,853",
            "Requires 4B Chocolate this Prestige!"));
        for (int i = 0; i < 7; i++) {
            String[] names = {"Rabbit Bro", "Rabbit Cousin", "Rabbit Sis", "Rabbit Daddy", "Rabbit Granny", "Rabbit Uncle", "Rabbit Dog"};
            container.setItem(28 + i, stack("player_head", names[i] + " - [" + (40 + i) + "]", "Cost", String.format("%,d Chocolate", 1_500_000L * (i + 1))));
        }
        container.setItem(35, stack("oak_fence", "Chocolate Barn", "Your Barn: 171/190 Rabbits"));
        container.setItem(38, stack("clock", "Time Tower", "Charges: 2/3", "Status: INACTIVE", "Next Charge: 5h 13m 59s"));
        container.setItem(41, stack("player_head", "Coach Jackrabbit", "Cost", "12,000,000 Chocolate"));
        container.setItem(45, stack("paper", "Chocolate Production", "Total Multiplier: 1.77x"));
        container.setItem(52, stack("gold_ingot", "Chocolate Leaderboard", "You are #103 in all-time Chocolate.",
            "You are in the top 0.87% of players!"));
        menu(ctx, container, "Chocolate Factory");
        ctx.waitTicks(20);
        shot(ctx, "sb-08-chocolate-factory");
        ctx.setScreen(() -> null);
    }

    /** A Floor 7 run: the dungeon sidebar, start lines, a puzzle, the blood room and the boss. */
    private void dungeon(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Catacombs";
        sidebar(ctx, "10/26/26 m12AB", "", "Auto-closing in: 1:58", "⏣ The Catacombs (F7)", "", "Time Elapsed: 01m 23s", "Cleared: 45% (12)", "",
            "[M] Player0 ❤ 100%", "[A] Tank_Guy ❤ 87%", "[H] HealBot ❤ 100%", "[B] Bers ❤ 64%", "", "www.hypixel.net");
        chat(ctx, "[NPC] Mort: Here, I found this map when I first entered the dungeon.");
        chat(ctx, "Starting in 1 second.");
        chat(ctx, "Player0 is now ready!");
        chat(ctx, "PUZZLE SOLVED! Player0 tied Tic Tac Toe! Good job!");
        chat(ctx, "A Crypt Key was picked up!");
        chat(ctx, "The BLOOD DOOR has been opened!");
        chat(ctx, "[BOSS] The Watcher: Congratulations, you made it through the Entrance.");
        chat(ctx, "[BOSS] The Watcher: You have proven yourself. You may pass.");
        chat(ctx, "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!");
        ctx.waitTicks(40);
        shot(ctx, "sb-10-dungeon");
        openChat(ctx);
        shot(ctx, "sb-11-dungeon-chat");
        ctx.setScreen(() -> null);
    }

    private void garden(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Garden";
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Plot - 4", "", "Copper: 1,234", "Sowdust: 56,789", "", "Jacob's Contest", "◆ Wheat 12m 34s",
            " Collected 12,345", "", "www.hypixel.net");
        chat(ctx, "GARDEN ൠ A Pest has appeared in Plot - 4!");
        chat(ctx, "VISITOR Jerry has arrived on your Garden!");
        ctx.waitTicks(40);
        shot(ctx, "sb-12-garden");
    }

    private void mining(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Dwarven Mines";
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Royal Mines", "", "Purse: 1,134,567", "", "᠅ Mithril: 123,456", "᠅ Gemstone: 45,678",
            "᠅ Glacite: 9,876", "", "www.hypixel.net");
        chat(ctx, "Mining Speed Boost is now available!");
        chat(ctx, "You used your Mining Speed Boost Pickaxe Ability!");
        chat(ctx, "COMMISSION COMPLETE! Royal Mines Titanium");
        chat(ctx, "PRISTINE! You found Flawed Ruby Gemstone x2!");
        ctx.waitTicks(40);
        shot(ctx, "sb-13-mining");
    }

    private void fishing(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Hub";
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Fisherman's Hut", "", "Purse: 1,134,567", "", "www.hypixel.net");
        chat(ctx, "A Squid appeared.");
        chat(ctx, "GOOD CATCH! You found a Lily Pad.");
        chat(ctx, "The Water Hydra has come to test your strength.");
        chat(ctx, "TROPHY FISH! You caught a Sulphur Skitter BRONZE.");
        ctx.waitTicks(40);
        shot(ctx, "sb-14-fishing");
    }

    private void diana(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Hub";
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Wilderness", "", "Purse: 1,134,567", "", "www.hypixel.net");
        chat(ctx, "You dug out a Griffin Burrow! (1/4)");
        chat(ctx, "Woah! You dug out a Minos Champion!");
        chat(ctx, "RARE DROP! You dug out a Griffin Feather!");
        chat(ctx, "Yikes! You dug out a Minos Inquisitor!");
        ctx.waitTicks(40);
        shot(ctx, "sb-15-diana");
        openChat(ctx);
        shot(ctx, "sb-16-diana-chat");
        ctx.setScreen(() -> null);
    }

    private void kuudra(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Kuudra";
        sidebar(ctx, "Late Spring 7th", "⏣ Kuudra's Hollow (T5)", "", "Time Elapsed: 02m 03s", "", "www.hypixel.net");
        chat(ctx, "[NPC] Elle: Talk with me to begin!");
        chat(ctx, "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!");
        chat(ctx, "[NPC] Elle: Not again!");
        ctx.waitTicks(40);
        shot(ctx, "sb-17-kuudra");
    }

    private void theEnd(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "The End";
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Void Sepulture", "", "Purse: 1,134,567", "", "Slayer Quest", "Voidgloom Seraph IV",
            "(1,234/3,000) Combat XP", "", "www.hypixel.net");
        chat(ctx, "  SLAYER QUEST STARTED!");
        ctx.waitTicks(40);
        shot(ctx, "sb-18-end-voidgloom");
    }

    private void crimson(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Crimson Isle";
        sidebar(ctx, "Late Spring 7th", "6:40pm", "⏣ Stronghold", "", "Purse: 1,134,567", "", "Slayer Quest", "Inferno Demonlord IV",
            "Slay the boss!", "", "www.hypixel.net");
        ctx.waitTicks(40);
        shot(ctx, "sb-19-crimson-inferno");
    }

    private void rift(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "The Rift";
        sidebar(ctx, "Late Spring 7th", "ф Wizard Tower", "", "Motes: 1,234", "ф Rift Time: 12m 34s", "", "www.hypixel.net");
        ctx.waitTicks(40);
        shot(ctx, "sb-20-rift");
    }

    /** Hypixel menus Sky2M dresses up. */
    private void menus(ClientGameTestContext ctx) {
        Sky2MLocation.testArea = "Hub";
        sidebar(ctx, "Late Spring 7th", "⏣ Bazaar Alley", "", "Purse: 1,134,567", "", "www.hypixel.net");
        String[][] menus = {{"Bazaar ➜ Mining", "diamond", "Enchanted Diamond", "Buy Price: 1,234.5 coins", "Sell Price: 1,100.1 coins"},
            {"Auctions Browser", "diamond_sword", "Hyperion", "Buy it now: 1,200,000,000 coins", "Seller: [MVP+] Someone"},
            {"Pets (1/3)", "player_head", "[Lvl 100] Golden Dragon", "Progress to Level 101: 12%", "Click to summon!"},
            {"Accessory Bag (1/2)", "player_head", "Tarantula Talisman", "LEGENDARY ACCESSORY"},
            {"SkyBlock Menu", "player_head", "Your SkyBlock Profile", "View your equipment, stats, and more!"},
            {"Ender Chest", "chest", "Backpack", "Click to open!"}};
        int n = 21;
        for (String[] m : menus) {
            SimpleContainer container = new SimpleContainer(54);
            for (int i = 0; i < 54; i++) container.setItem(i, stack("black_stained_glass_pane", " "));
            for (int i = 10; i < 17; i++) container.setItem(i, stack(m[1], m[2], Arrays.copyOfRange(m, 3, m.length)));
            menu(ctx, container, m[0]);
            shot(ctx, "sb-" + (n++) + "-menu-" + m[0].replaceAll("[^A-Za-z]", "").toLowerCase());
            ctx.setScreen(() -> null);
        }
    }

    private void partyChat(ClientGameTestContext ctx) {
        chat(ctx, "Party Finder > Tank_Guy joined the dungeon group! (Tank Level 34)");
        chat(ctx, "Party > [MVP+] Tank_Guy: !warp");
        chat(ctx, "Party > [VIP] HealBot: x: 12, y: 70, z: -45");
        chat(ctx, "Guild > [MVP++] GuildMate [Officer]: hello guild");
        chat(ctx, "From [VIP] Friend: hey");
        chat(ctx, "[123] ⚔ [MVP+] Someone: wtb hyperion");
        chat(ctx, "+5 SkyBlock XP (Accessories) (35/100)");
        ctx.waitTicks(30);
        openChat(ctx);
        shot(ctx, "sb-30-party-chat");
        ctx.setScreen(() -> null);
    }

    /** Runs every /s2 subcommand that is safe to run alone (nothing sent, opened outside the game, or reset). */
    private void everyCommand(ClientGameTestContext ctx) {
        java.util.Set<String> skip = java.util.Set.of("discord", "website", "download", "upload", "folder", "open", "link", "unlink",
            "bugreport", "report", "reports", "feedback", "suggest", "send", "sendcoords", "share", "staff", "stafflogin", "stafflogout",
            "password", "disableall", "chat", "leave", "nick", "paste", "import", "importskyshards", "lobby", "join", "requeue", "pt", "pkick",
            "pa", "rp", "posmsg", "chatwaypoint", "react", "reply", "mute", "unmute", "ignore", "unignore", "block", "unblock", "delete",
            "remove", "selftest", "sim", "simbuild", "simloadout", "simpuzzle", "simscore", "simwhere", "summon", "fly", "speed", "reload",
            "export", "replay", "load", "unload", "save", "screenshot", "playasmob", "setup", "setupmode", "translate", "translatechat",
            "viewreport", "viewsuggest", "code", "friend", "friends", "rival", "accept", "deny");
        List<String> names = ctx.computeOnClient(mc -> {
            var root = net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher().getRoot().getChild("s2");
            List<String> out = new ArrayList<>();
            if (root != null) for (var child : root.getChildren()) {
                if (child.getCommand() != null && child instanceof com.mojang.brigadier.tree.LiteralCommandNode<?>) out.add(child.getName());
            }
            out.sort(null);
            return out;
        });
        System.out.println("[Sky2M gametest] /s2 subcommands: " + names);
        // On GUI scale Auto, as most players are: Sky2M's screens get one step smaller (Sky2MAutoScale).
        ctx.runOnClient(mc -> {
            mc.options.guiScale().set(0);
            mc.resizeGui();
        });
        ctx.waitTicks(5);
        int n = 0;
        for (String name : names) {
            if (skip.contains(name) || name.startsWith("reset") || name.startsWith("clear")) {
                System.out.println("[Sky2M gametest] /s2 " + name + " skipped");
                continue;
            }
            int chatBefore = ctx.computeOnClient(mc -> chat(mc).size());
            String error = ctx.computeOnClient(mc -> {
                try {
                    net.fabricmc.fabric.api.client.command.v2.ClientCommands.getActiveDispatcher().execute("s2 " + name,
                        (net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource) mc.getConnection().getSuggestionsProvider());
                    return null;
                } catch (Throwable e) {
                    return e.toString();
                }
            });
            ctx.waitTicks(15);
            String screen = ctx.computeOnClient(mc -> mc.gui.screen() == null ? "none"
                : mc.gui.screen().getClass().getSimpleName() + " (GUI scale " + mc.getWindow().getGuiScale() + ")");
            List<String> said = ctx.computeOnClient(mc -> newChat(mc, chatBefore));
            System.out.println("[Sky2M gametest] /s2 " + name + " -> screen " + screen + (error == null ? "" : ", threw " + error)
                + (said.isEmpty() ? "" : ", chat: " + String.join(" | ", said)));
            if (error != null) failures.add("/s2 " + name + " threw " + error);
            if (!"none".equals(screen)) {
                shot(ctx, String.format("cmd-%03d-%s", n++, name));
                // Closed with Esc, as a player would (some screens only let go that way).
                ctx.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE);
                ctx.waitTicks(5);
                ctx.setScreen(() -> null);
                ctx.waitTicks(3);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ fake SkyBlock

    /** Replaces the sidebar with these lines, top to bottom, under a SKYBLOCK title (built on the client, like Hypixel's). */
    private static void sidebar(ClientGameTestContext ctx, String... lines) {
        ctx.runOnClient(mc -> {
            clearSidebar(mc);
            Scoreboard board = mc.level.getScoreboard();
            Objective objective = board.addObjective("sky2m_test", ObjectiveCriteria.DUMMY, Component.literal("§e§lSKYBLOCK"),
                ObjectiveCriteria.RenderType.INTEGER, false, null);
            board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
            for (int i = 0; i < lines.length; i++) {
                String holder = "§" + Integer.toHexString(i % 16) + (i >= 16 ? "§r" : "");
                PlayerTeam team = board.addPlayerTeam("sky2m_test_" + i);
                team.setPlayerPrefix(Component.literal(lines[i]));
                board.addPlayerToTeam(holder, team);
                board.getOrCreatePlayerScore(ScoreHolder.forNameOnly(holder), objective).set(lines.length - i);
            }
        });
        ctx.waitTicks(5);
    }

    private static void clearSidebar(Minecraft mc) {
        Scoreboard board = mc.level.getScoreboard();
        Objective old = board.getObjective("sky2m_test");
        if (old != null) board.removeObjective(old);
        for (PlayerTeam team : List.copyOf(board.getPlayerTeams())) {
            if (team.getName().startsWith("sky2m_test_")) board.removePlayerTeam(team);
        }
    }

    /** A chat line as the server sends it. */
    private static void chat(ClientGameTestContext ctx, String text) {
        ctx.runOnClient(mc -> mc.getConnection().handleSystemChat(new ClientboundSystemChatPacket(Component.literal(text), false)));
        ctx.waitTicks(2);
    }

    private static void openChat(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.ChatScreen("", false)));
        ctx.waitTicks(5);
    }

    /** A fake six-row chest menu with this title. */
    private static void menu(ClientGameTestContext ctx, SimpleContainer container, String title) {
        ctx.runOnClient(mc -> {
            ChestMenu menu = ChestMenu.sixRows(1, mc.player.getInventory(), container);
            mc.gui.setScreen(new ContainerScreen(menu, mc.player.getInventory(), Component.literal(title)));
        });
        ctx.waitTicks(15);
    }

    private static ItemStack stack(String id, String name, String... lore) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id)));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        if (lore.length > 0) stack.set(DataComponents.LORE, new ItemLore(Arrays.stream(lore).map(l -> (Component) Component.literal(l)).toList()));
        return stack;
    }

    // ------------------------------------------------------------------------------------------------ results

    private void scenario(ClientGameTestContext ctx, String name, Runnable body) {
        int errorsBefore = com.epic60869.sky2m.features.core.Sky2MErrors.records().size();
        int chatBefore = ctx.computeOnClient(mc -> chat(mc).size());
        try {
            body.run();
            System.out.println("[Sky2M gametest] fake SkyBlock: " + name + " done");
        } catch (Throwable e) {
            e.printStackTrace();
            failures.add(name + " threw " + e);
            ctx.setScreen(() -> null);
        }
        // What was written to chat meanwhile (oldest first), for reading through.
        List<String> lines = ctx.computeOnClient(mc -> newChat(mc, chatBefore));
        for (String line : lines) System.out.println("[Sky2M gametest] chat (" + name + "): " + line);
        var errors = com.epic60869.sky2m.features.core.Sky2MErrors.records();
        for (int i = errorsBefore; i < errors.size(); i++) {
            var r = errors.get(i);
            failures.add(name + ": Sky2M error in " + r.feature + ": " + r.exception + " " + r.message);
            System.out.println("[Sky2M gametest] ERROR (" + name + ") " + r.feature + ": " + r.exception + " " + r.message + "\n" + r.stack);
        }
    }

    private static List<net.minecraft.client.multiplayer.chat.GuiMessage> chat(Minecraft mc) {
        return ((com.epic60869.sky2m.mixin.Sky2MChatComponentAccessor) mc.gui.hud.getChat()).sky2m$allMessages();
    }

    /** Chat lines added since there were {@code before} (the list is newest first), oldest first. */
    private static List<String> newChat(Minecraft mc, int before) {
        var all = chat(mc);
        List<String> out = new ArrayList<>();
        for (int i = all.size() - before - 1; i >= 0; i--) out.add(all.get(i).content().getString());
        return out;
    }

    private void check(boolean ok, String what) {
        System.out.println("[Sky2M gametest] fake SkyBlock check " + (ok ? "OK  " : "FAIL") + " " + what);
        if (!ok) failures.add(what);
    }

    private static void shot(ClientGameTestContext ctx, String name) {
        System.out.println("[Sky2M gametest] screenshot " + name + " -> " + ctx.takeScreenshot(name));
    }
}
