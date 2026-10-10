package com.epic60869.sky2m.features.itemlist;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.features.core.Sky2MLocation;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/**
 * The Item List search bar's calculator: {@code 2.5m*3}, {@code (1k + 500) / 4}, {@code 2^10}, {@code 3x64}, and prices:
 * {@code bz(ENCHANTED_DIAMOND)} (bazaar buy), {@code lb(HYPERION)} (lowest BIN), {@code npc(WHEAT)} (NPC sell).
 * Suffixes k, m, b, t and st (a stack, 64), and {@code purse} (your purse from the sidebar). Written for Sky2M; the
 * functions and suffixes are SkyBlock Item List's.
 */
public final class ItemListCalculator {
    private static final Map<String, Double> SUFFIXES = Map.of("k", 1e3, "m", 1e6, "b", 1e9, "t", 1e12, "st", 64.0);
    private static final DecimalFormat FORMAT = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));

    private ItemListCalculator() {}

    /** The result ("= 7,500,000"), an error starting "ERR: " to show, or null when the text isn't a calculation. */
    public static String evaluate(String text, boolean requireEquals) {
        if (text == null) return null;
        String expression = text.trim();
        boolean explicit = expression.startsWith("=");
        if (explicit) expression = expression.substring(1).trim();
        else if (requireEquals) return null;
        if (expression.isEmpty()) return null;
        // Plain words are a search, not a calculation: it needs a digit or a price function.
        if (!explicit && !expression.matches(".*(\\d|\\b(bz|lb|npc)\\().*")) return null;
        try {
            Parser parser = new Parser(expression);
            double value = parser.parse();
            return "= " + FORMAT.format(value);
        } catch (UnknownItem e) {
            return "ERR: Unknown item " + e.getMessage();
        } catch (RuntimeException e) {
            return explicit ? "ERR: " + (e.getMessage() == null ? "can't read that" : e.getMessage()) : null;
        }
    }

    /** The number in a result, for replacing the search with it ("7500000"). */
    public static String plain(String result) {
        return result == null || !result.startsWith("= ") ? null : result.substring(2).replace(",", "");
    }

    private static final class UnknownItem extends RuntimeException {
        UnknownItem(String id) {
            super(id);
        }
    }

    /** Recursive descent: sums of products of powers of signed factors. */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s.toLowerCase(Locale.ROOT);
        }

        double parse() {
            double value = sum();
            skip();
            if (pos < s.length()) throw new IllegalArgumentException("unexpected '" + s.charAt(pos) + "'");
            return value;
        }

        private double sum() {
            double value = product();
            while (true) {
                skip();
                if (eat('+')) value += product();
                else if (eat('-')) value -= product();
                else return value;
            }
        }

        private double product() {
            double value = power();
            while (true) {
                skip();
                if (eat('*')) value *= power();
                else if (eat('/')) value /= power();
                else if (eat('%')) value %= power();
                // "3x64": x between two numbers multiplies.
                else if (pos < s.length() && s.charAt(pos) == 'x' && nextIsOperand(pos + 1)) {
                    pos++;
                    value *= power();
                } else return value;
            }
        }

        private double power() {
            double base = unary();
            skip();
            if (eat('^')) return Math.pow(base, power());
            return base;
        }

        private double unary() {
            skip();
            if (eat('-')) return -unary();
            if (eat('+')) return unary();
            return atom();
        }

        private double atom() {
            skip();
            if (eat('(')) {
                double value = sum();
                skip();
                if (!eat(')')) throw new IllegalArgumentException("missing )");
                return suffix(value);
            }
            int start = pos;
            if (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == ',')) pos++;
                double value = Double.parseDouble(s.substring(start, pos).replace(",", ""));
                return suffix(value);
            }
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) pos++;
            String word = s.substring(start, pos);
            if (word.isEmpty()) throw new IllegalArgumentException(pos < s.length() ? "unexpected '" + s.charAt(pos) + "'" : "unfinished");
            skip();
            if (eat('(')) {
                int argStart = pos;
                while (pos < s.length() && s.charAt(pos) != ')') pos++;
                if (pos >= s.length()) throw new IllegalArgumentException("missing )");
                String id = s.substring(argStart, pos).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
                pos++;
                return suffix(price(word, id));
            }
            if (word.equals("purse")) return suffix(purse());
            Double unit = SUFFIXES.get(word);
            if (unit != null) return unit;
            throw new IllegalArgumentException("unknown " + word);
        }

        /** "2.5m": a suffix right after a number multiplies it. */
        private double suffix(double value) {
            for (String unit : new String[]{"st", "k", "m", "b", "t"}) {
                if (s.startsWith(unit, pos) && (pos + unit.length() >= s.length() || !Character.isLetter(s.charAt(pos + unit.length())))) {
                    pos += unit.length();
                    return value * SUFFIXES.get(unit);
                }
            }
            return value;
        }

        private boolean nextIsOperand(int at) {
            while (at < s.length() && s.charAt(at) == ' ') at++;
            return at < s.length() && (Character.isDigit(s.charAt(at)) || s.charAt(at) == '.' || s.charAt(at) == '(' || s.charAt(at) == '-');
        }

        private void skip() {
            while (pos < s.length() && s.charAt(pos) == ' ') pos++;
        }

        private boolean eat(char c) {
            if (pos < s.length() && s.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }
    }

    private static double price(String function, String id) {
        Double price = switch (function) {
            case "bz" -> Sky2MPriceTooltip.bazaarBuyPrice(id);
            case "lb" -> Sky2MPriceTooltip.lowestBinPrice(id);
            case "npc" -> Sky2MPriceTooltip.npcSellPrice(id);
            default -> throw new IllegalArgumentException("unknown " + function + "()");
        };
        if (price == null || price <= 0) throw new UnknownItem(id);
        return price;
    }

    private static double purse() {
        String value = Sky2MLocation.scoreboardValue("Purse:");
        if (value == null || value.isEmpty()) value = Sky2MLocation.scoreboardValue("Piggy:");
        if (value == null || value.isEmpty()) return 0;
        // "1,234,567.8 (+5)": the first number.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\d,]+(?:\\.\\d+)?").matcher(value);
        return m.find() ? Double.parseDouble(m.group().replace(",", "")) : 0;
    }
}
