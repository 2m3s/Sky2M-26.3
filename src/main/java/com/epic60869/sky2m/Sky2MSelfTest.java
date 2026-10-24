package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.hunting.IslandGraph;
import com.epic60869.sky2m.features.sbc.SbcInfo;
import com.epic60869.sky2m.features.slayer.SlayerBossProfit;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 selftest: whether the data Sky2M downloads or reads (NEU items, SkyHanni drop lists and island graphs, prices),
 * the Sky2M server connection and location detection are working, one pass/fail line each with the reason, so a bug
 * report can say what was missing. [Copy] puts the report with the mod and Minecraft versions on the clipboard.
 */
public final class Sky2MSelfTest {
    /** The islands whose SkyHanni graphs features use (slayer areas, hunting, Safari). */
    private static final List<String> GRAPHS = List.of("HUB", "SPIDER_DEN", "THE_PARK", "THE_END", "CRIMSON_ISLE",
        "THE_RIFT", "GALATEA", "TORRHUS_CANYON", "SAFARI");

    record Check(String name, boolean pass, String reason) {}

    private Sky2MSelfTest() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("selftest")
                    .executes(c -> show())
                    .then(ClientCommands.literal("copy").executes(c -> copy()))));
            }
        });
    }

    static List<Check> run() {
        List<Check> out = new ArrayList<>();
        out.add(new Check("Config", Sky2MConfig.current() != null,
            Sky2MConfig.current() != null ? "sky2m-mod.json loaded" : "the settings didn't load (see the game log)"));
        boolean items = RepoItems.itemsLoaded();
        out.add(new Check("NEU repo items", items, items ? "loaded" : "not loaded yet (downloading, or GitHub unreachable)"));
        boolean drops = SlayerBossProfit.dropListsLoaded();
        out.add(new Check("Slayer drop lists", drops, drops ? "SkyHanni's lists loaded" : "not loaded (no download and no cached copy)"));
        List<String> missing = new ArrayList<>();
        for (String island : GRAPHS) if (IslandGraph.get(island) == null) missing.add(island);
        out.add(new Check("Island graphs", missing.isEmpty(), missing.isEmpty()
            ? "all " + GRAPHS.size() + " loaded" : "missing " + String.join(", ", missing)));
        boolean prices = Sky2MPriceTooltip.pricesLoaded();
        out.add(new Check("Item prices", prices, prices ? "bazaar/auction prices loaded" : "no prices yet (the price API couldn't be reached)"));
        boolean connected = Sky2MGlobalChat.currentConnection() != null;
        boolean loggedIn = Sky2MLogin.loggedIn();
        String problem = Sky2MLogin.problem();
        out.add(new Check("Sky2M server", connected && loggedIn, !connected ? "not connected (it reconnects by itself)"
            : loggedIn ? "connected and logged in"
            : "connected, not logged in" + (problem == null || problem.isEmpty() ? " yet" : ": " + problem)));
        out.add(location());
        return out;
    }

    private static Check location() {
        if (Sky2MLocation.onSkyblock()) {
            String area = Sky2MLocation.area();
            boolean known = area != null && !area.isEmpty();
            return new Check("Location", known, known ? "SkyBlock, " + area : "on SkyBlock but the area wasn't read from the scoreboard/tab list");
        }
        ServerData server = Minecraft.getInstance().getCurrentServer();
        boolean hypixel = server != null && com.epic60869.sky2m.custom.util.Compat.isHypixelAddress(server.ip);
        return new Check("Location", false, hypixel ? "on Hypixel but not detected as SkyBlock (join SkyBlock and run this again)"
            : "not on Hypixel SkyBlock, so it can't be checked");
    }

    private static int show() {
        List<Check> checks = run();
        int passed = (int) checks.stream().filter(Check::pass).count();
        MutableComponent message = Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("Self-test: " + passed + "/" + checks.size() + " passed ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal("[Copy]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("s2 selftest copy"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy this report with your Sky2M and Minecraft versions for a bug report")))));
        for (Check check : checks) {
            message.append(Component.literal("\n " + (check.pass() ? "PASS " : "FAIL ")).withStyle(check.pass() ? ChatFormatting.GREEN : ChatFormatting.RED))
                .append(Component.literal(check.name()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - " + check.reason()).withStyle(ChatFormatting.GRAY));
        }
        say(message);
        return 1;
    }

    /** The copyable report: versions and one PASS/FAIL line per check (also added to Error Reporter bug reports). */
    public static String reportText() {
        StringBuilder text = new StringBuilder("Sky2M self-test\nSky2M " + SbcInfo.modVersion() + ", Minecraft " + SbcInfo.mcVersion() + "\n");
        for (Check check : run()) text.append(check.pass() ? "PASS " : "FAIL ").append(check.name()).append(" - ").append(check.reason()).append('\n');
        return text.toString().strip();
    }

    private static int copy() {
        String text = reportText();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.keyboardHandler.setClipboard(text));
        say(Component.literal("[S2M] ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("Copied the self-test report.").withStyle(ChatFormatting.GREEN)));
        return 1;
    }

    private static void say(Component message) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
        });
    }
}
