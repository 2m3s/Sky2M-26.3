package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Sign calculator, following Skyblocker's SignCalculator: on SkyBlock's number signs (auction prices, bazaar amounts,
 * ...: the ones with a "^^^^^^^^^^^^^^^" line) it shows what you typed as a number above the sign, e.g. "15m =
 * 15,000,000", and sends that number when you're done, so sums like "2.5m*3" or "64*9" work too. Understands
 * + - * x / % ^, brackets and the suffixes k, m, b, t, s (a stack, 64) and e (an enchanted item, 160).
 * The sign itself is hooked by Sky2MSignEditMixin.
 */
public final class SignCalculator {
    private static final String INPUT_MARKER = "^^^^^^^^^^^^^^^";
    private static final String ALT_INPUT_MARKER = "^^^^^^";
    private static final String FLIP_MARKER = "^^Flipping^^";

    private SignCalculator() {}

    public static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.signCalculator && Compat.isOnSkyblock();
    }

    /** A number sign, not a search sign (/recipes, /bestiary) or a name sign. */
    public static boolean isInputSign(String[] lines) {
        if (lines.length < 4) return false;
        boolean search = lines[2].endsWith("your") || lines[2].endsWith("query");
        return lines[1].equals(INPUT_MARKER) && !search
            || lines[1].equals(ALT_INPUT_MARKER) && !lines[2].endsWith("your") && !lines[2].equals("Enter name")
            || lines[1].equals(FLIP_MARKER);
    }

    /** The line shown above the sign: "15m = 15,000,000" in green, or why it isn't a number in red; null when empty. */
    public static Component preview(String input) {
        String text = input.trim();
        if (text.isEmpty()) return null;
        try {
            return Component.literal(text + " = " + format(calculate(text))).withStyle(ChatFormatting.GREEN);
        } catch (IllegalArgumentException e) {
            return Component.literal(e.getMessage()).withStyle(ChatFormatting.RED);
        }
    }

    /**
     * What to send instead of {@code input}: the worked-out number (whole for amounts, up to 2 decimals for prices),
     * or {@code input} unchanged if it isn't a valid sum.
     */
    public static String result(String input, boolean price) {
        try {
            double value = calculate(input.trim());
            String out = price ? String.valueOf(Math.round(value * 100d) / 100d) : Long.toString(Math.round(value));
            if (out.endsWith(".0")) out = out.substring(0, out.length() - 2);
            return out.length() > 15 ? out.substring(0, 15) : out;
        } catch (IllegalArgumentException e) {
            return input;
        }
    }

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return String.format(Locale.US, "%,d", (long) value);
        return String.format(Locale.US, "%,.2f", value);
    }

    // ---------------------------------------------------------------- the calculator

    /** Works out {@code text}; throws IllegalArgumentException with a short reason when it isn't a valid sum. */
    public static double calculate(String text) {
        Parser parser = new Parser(text.toLowerCase(Locale.ROOT).replace(" ", "").replace('x', '*'));
        double value = parser.sum();
        if (parser.pos < parser.text.length()) throw new IllegalArgumentException("Unexpected \"" + parser.text.charAt(parser.pos) + "\"");
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new IllegalArgumentException("Not a number");
        return value;
    }

    /** Recursive descent: sum = product (+|- product)*, product = power (*|/|% power)*, power = unary (^ power)?. */
    private static final class Parser {
        final String text;
        int pos;

        Parser(String text) {
            this.text = text;
        }

        private boolean eat(char c) {
            if (pos < text.length() && text.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        double sum() {
            double value = product();
            while (true) {
                if (eat('+')) value += product();
                else if (eat('-')) value -= product();
                else return value;
            }
        }

        double product() {
            double value = power();
            while (true) {
                if (eat('*')) value *= power();
                else if (eat('/')) {
                    double right = power();
                    if (right == 0) throw new IllegalArgumentException("Can't divide by 0");
                    value /= right;
                } else if (eat('%')) {
                    double right = power();
                    if (right == 0) throw new IllegalArgumentException("Can't divide by 0");
                    value %= right;
                } else return value;
            }
        }

        double power() {
            double base = unary();
            return eat('^') ? Math.pow(base, power()) : base;
        }

        double unary() {
            if (eat('-')) return -unary();
            if (eat('+')) return unary();
            if (eat('(')) {
                double value = sum();
                eat(')'); // an unclosed bracket is closed at the end, as in Skyblocker
                return value;
            }
            return number();
        }

        double number() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos)) || text.charAt(pos) == '.' || text.charAt(pos) == ',' || text.charAt(pos) == '_')) pos++;
            if (start == pos) {
                throw new IllegalArgumentException(pos < text.length() ? "Unexpected \"" + text.charAt(pos) + "\"" : "Missing a number");
            }
            double value;
            try {
                value = Double.parseDouble(text.substring(start, pos).replace(",", "").replace("_", ""));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad number \"" + text.substring(start, pos) + "\"");
            }
            if (pos < text.length()) {
                long magnitude = switch (text.charAt(pos)) {
                    case 'k' -> 1_000L;
                    case 'm' -> 1_000_000L;
                    case 'b' -> 1_000_000_000L;
                    case 't' -> 1_000_000_000_000L;
                    case 's' -> 64L;
                    case 'e' -> 160L;
                    default -> 1L;
                };
                if (magnitude != 1L) {
                    pos++;
                    value *= magnitude;
                }
            }
            // "2(3)" means 2*3.
            if (pos < text.length() && text.charAt(pos) == '(') value *= unary();
            return value;
        }
    }
}
