package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MCalculator;
import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;

import java.util.regex.Pattern;

/**
 * Live answers in the chat box: while you type {@code /s2 calc 8*9}, or just {@code 8*9}, "= 72" shows after the text,
 * without pressing Enter. Plain chat only counts when it is nothing but a sum (digits, k/m/b/t, + - * / and brackets).
 * Drawn by Sky2MChatScreenMixin.
 */
public final class ChatCalculator {
    private static final Pattern SUM = Pattern.compile("[\\d\\s.,+\\-*/()kmbtKMBT]+");
    private static final Pattern HAS_OPERATOR = Pattern.compile(".*\\d.*[+\\-*/].*\\d.*|.*\\d\\s*[kmbtKMBT].*");
    private static String lastInput;
    private static String lastAnswer;

    private ChatCalculator() {}

    /** The text after "/s2 calc ", or the whole input when it is a plain sum; null when there's nothing to work out. */
    private static String expression(String value) {
        String text = value.trim();
        for (String root : Compat.COMMAND_ROOTS) {
            String prefix = "/" + root + " calc ";
            if (text.regionMatches(true, 0, prefix, 0, prefix.length())) return text.substring(prefix.length()).trim();
        }
        if (text.startsWith("/") || !SUM.matcher(text).matches() || !HAS_OPERATOR.matcher(text).matches()) return null;
        return text;
    }

    private static String answer(String value) {
        if (value.equals(lastInput)) return lastAnswer;
        lastInput = value;
        lastAnswer = null;
        String expression = expression(value);
        if (expression == null || expression.isEmpty()) return null;
        try {
            lastAnswer = "= " + Sky2MCalculator.calculate(expression);
        } catch (RuntimeException e) {
            // Half-typed sums ("8*") just show nothing until they make sense.
        }
        return lastAnswer;
    }

    public static void render(GuiGraphicsExtractor g, EditBox input) {
        if (input == null) return;
        String answer = answer(input.getValue());
        if (answer == null) return;
        Font font = Minecraft.getInstance().font;
        int right = input.getX() + input.getWidth() - font.width(answer) - 2;
        int x = Math.min(input.getX() + font.width(input.getValue()) + 6, right);
        g.text(font, answer, x, input.getY(), 0xFFAAAAAA, true);
    }
}
