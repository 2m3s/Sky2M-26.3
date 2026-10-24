package com.epic60869.sky2m.features.mining.dwarven;

import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dwarven Mines NPC solvers, ported from Skyblocker's Fetchur and Puzzler solvers (SkyblockerMod/Skyblocker, LGPL-3.0;
 * SkyOcean has the same Fetchur and Puzzler helpers): Fetchur's riddle is replaced in chat with the item he wants, and
 * the Puzzler's arrows are turned into the block to mine, which is marked in the world.
 */
public final class DwarvenSolvers {
    private static final Pattern FETCHUR = Pattern.compile("^\\[NPC] Fetchur: (?:its|theyre) ([a-zA-Z, \\-]*)$");
    private static final Pattern PUZZLER = Pattern.compile("^\\[NPC] Puzzler: ([▲▶◀▼]{10})$");

    private static final Map<String, String> FETCHUR_ANSWERS = Map.ofEntries(
        Map.entry("yellow and see through", "Yellow Stained Glass"),
        Map.entry("circular and sometimes moves", "Compass"),
        Map.entry("expensive minerals", "Mithril"),
        Map.entry("useful during celebrations", "Firework Rocket"),
        Map.entry("hot and gives energy", "Cheap / Decent / Black Coffee"),
        Map.entry("tall and can be opened", "Any Wooden Door / Iron Door"),
        Map.entry("brown and fluffy", "Rabbit's Foot"),
        Map.entry("explosive but more than usual", "Superboom TNT"),
        Map.entry("wearable and grows", "Pumpkin"),
        Map.entry("shiny and makes sparks", "Flint and Steel"),
        Map.entry("green and some dudes trade stuff for it", "Emerald"),
        Map.entry("red and soft", "Red Wool"));

    private static volatile BlockPos puzzlerBlock;
    private static long puzzlerSet;

    private DwarvenSolvers() {}

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !fetchur(message));
        Sky2MChat.onChat(message -> puzzler(message.text().trim()));
        Sky2MLocation.onAreaChange(area -> puzzlerBlock = null);
        Sky2MWorldRender.register(DwarvenSolvers::render);
    }

    /** Replaces Fetchur's riddle with the answer; true when the original line must be hidden. */
    private static boolean fetchur(Component message) {
        DwarvenConfig.Solvers c = DwarvenMining.solvers();
        if (c == null || !c.fetchur || !Sky2MLocation.inDwarvenMines()) return false;
        Matcher m = FETCHUR.matcher(Sky2MLocation.strip(message.getString()).trim());
        if (!m.matches()) return false;
        String riddle = m.group(1);
        String answer = FETCHUR_ANSWERS.getOrDefault(riddle, riddle);
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[NPC] Fetchur").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(": " + answer).withStyle(ChatFormatting.WHITE)));
        });
        return true;
    }

    /** The Puzzler's reward block: every arrow moves it one block from (181, 195, 135). */
    private static void puzzler(String text) {
        DwarvenConfig.Solvers c = DwarvenMining.solvers();
        if (c == null || !c.puzzler) return;
        Matcher m = PUZZLER.matcher(text);
        if (!m.matches()) return;
        int x = 181;
        int z = 135;
        for (char arrow : m.group(1).toCharArray()) {
            switch (arrow) {
                case '▲' -> z++;
                case '▼' -> z--;
                case '◀' -> x++;
                case '▶' -> x--;
                default -> { }
            }
        }
        puzzlerBlock = new BlockPos(x, 195, z);
        puzzlerSet = System.currentTimeMillis();
    }

    private static void render(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector) {
        BlockPos pos = puzzlerBlock;
        DwarvenConfig.Solvers c = DwarvenMining.solvers();
        Minecraft mc = Minecraft.getInstance();
        if (pos == null || c == null || !c.puzzler || mc.level == null) return;
        if (System.currentTimeMillis() - puzzlerSet > 10 * 60_000L) {
            puzzlerBlock = null;
            return;
        }
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir() || state.is(Blocks.BEDROCK)) {
            // Only forget it once the chunk is loaded, so the marker survives walking up to it.
            if (mc.level.hasChunkAt(pos)) puzzlerBlock = null;
            return;
        }
        float[] colour = {1f, 0.2f, 0.2f};
        collector.submitFilledBox(pos, colour, 0.4f, true);
        collector.submitOutlinedBox(pos, colour, 3f, true);
        collector.submitText(Component.literal("Puzzler").withStyle(ChatFormatting.GOLD), Vec3.atCenterOf(pos).add(0, 1.2, 0), 1.5f, true);
    }
}
