package com.epic60869.sky2m.features.combat;

import com.mojang.blaze3d.platform.InputConstants;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MKeyMappings;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sphinx solver, ported from SkyBlock Overhaul (https://github.com/SkyblockOverhaul/SBO, Apache-2.0): SphinxSolver,
 * SphinxSession and SphinxQuestions. Hypixel's A/B/C answer lines are shown again with the right one in green and
 * underlined and the others in red; clicking anywhere while chat is open (or the Sphinx Solver key) answers it.
 */
public final class DianaSphinx {
    private static final Set<String> QUESTIONS = Set.of(
        "Which of these is NOT a pet?", "What type of mob is exclusive to the Fishing Festival?",
        "Where is Trevor the Trapper found?", "Who helps you apply Rod Parts?",
        "Which type of Gemstone has the lowest Breaking Power?", "Which item rarity comes after Mythic?",
        "How do you obtain the Dark Purple Dye?", "Who runs the Chocolate Factory?",
        "How many floors are there in The Catacombs?", "What is the first type of slayer Maddox offers?",
        "What item do you use to kill Pests?", "Who owns the Gold Essence Shop?",
        "Which of these is NOT a type of Gemstone?", "What does Junker Joel collect?", "Where is the Titanoboa found?");
    private static final Set<String> ANSWERS = Set.of("Slime", "Shark", "Mushroom Desert", "Roddy", "Ruby", "Divine",
        "Dark Auction", "Hoppity", "7", "Zombie", "Vacuum", "Marigold", "Prismite", "Junk", "Backwater Bayou");

    /** "   A) Mushroom Desert" */
    private static final Pattern ANSWER = Pattern.compile("^ {3}([ABC])\\) (.*)$");

    private static final Map<String, Component> answerLines = new LinkedHashMap<>();
    private static int correctIndex = -1;
    private static boolean sessionOpen;

    private DianaSphinx() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.events.diana.sphinxSolver && Sky2MLocation.onSkyblock();
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || onMessage(message));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
                if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && correctIndex >= 0 && enabled()) solve();
                return true;
            });
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (Sky2MKeyMappings.DIANA_SPHINX_SOLVER == null) return;
            while (Sky2MKeyMappings.DIANA_SPHINX_SOLVER.consumeClick()) if (enabled()) solve();
        });
    }

    private static void solve() {
        if (correctIndex < 0) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getConnection().sendCommand("sphinxanswer " + correctIndex);
        correctIndex = -1;
        sessionOpen = false;
        answerLines.clear();
    }

    /** Returns false to hide Hypixel's answer line (it's shown again coloured). */
    private static boolean onMessage(Component message) {
        if (!enabled()) return true;
        String text = Sky2MLocation.strip(message.getString());
        if (QUESTIONS.stream().anyMatch(q -> q.equalsIgnoreCase(text.trim()))) {
            answerLines.clear();
            correctIndex = -1;
            sessionOpen = true;
            Sky2MAlerts.chat(Component.literal("Click anywhere on the screen to answer while the chat is open.").withStyle(ChatFormatting.AQUA));
            return true;
        }
        Matcher m = ANSWER.matcher(text);
        if (!m.matches()) return true;
        String letter = m.group(1);
        String answer = m.group(2).trim();
        int index = letter.charAt(0) - 'A';
        boolean correct = ANSWERS.contains(answer);
        if (!sessionOpen) {
            answerLines.clear();
            correctIndex = -1;
            sessionOpen = true;
        }
        if (correct) correctIndex = index;
        HoverEvent hover = null;
        for (Component part : message.toFlatList()) {
            if (part.getStyle().getHoverEvent() != null) {
                hover = part.getStyle().getHoverEvent();
                break;
            }
        }
        HoverEvent originalHover = hover;
        MutableComponent line = Component.literal("   " + letter + ") ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(answer).withStyle(s -> {
                var styled = s.withColor(correct ? ChatFormatting.GREEN : ChatFormatting.RED).withUnderlined(correct);
                return originalHover == null ? styled : styled.withHoverEvent(originalHover);
            }));
        answerLines.put(letter, line);
        if (answerLines.size() == 3 && correctIndex >= 0) {
            String command = "/sphinxanswer " + correctIndex;
            Minecraft mc = Minecraft.getInstance();
            for (Component l : answerLines.values()) {
                Component clickable = l.copy().withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand(command)));
                mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(clickable));
            }
        }
        return false;
    }
}
