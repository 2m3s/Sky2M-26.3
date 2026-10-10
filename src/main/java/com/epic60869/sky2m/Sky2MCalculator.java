package com.epic60869.sky2m;

import java.util.Locale;

public final class Sky2MCalculator {
    private Sky2MCalculator() {}

    public static String calculate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Enter a calculation.");
        }

        Parser parser = new Parser(expression);
        double value = parser.parseExpression();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected input near: " + expression.substring(parser.position()));
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("The result is not a finite number.");
        }

        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        return String.format(Locale.ROOT, "%,.10f", value)
            .replaceAll("0+$", "")
            .replaceAll("\\.$", "");
    }

    private static final class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
        }

        int position() {
            return pos;
        }

        boolean atEnd() {
            skipSpaces();
            return pos >= input.length();
        }

        double parseExpression() {
            double value = parseTerm();
            while (true) {
                skipSpaces();
                if (match('+')) value += parseTerm();
                else if (match('-')) value -= parseTerm();
                else return value;
            }
        }

        double parseTerm() {
            double value = parseFactor();
            while (true) {
                skipSpaces();
                if (match('*')) value *= parseFactor();
                else if (match('/')) {
                    double divisor = parseFactor();
                    if (divisor == 0) throw new IllegalArgumentException("Cannot divide by zero.");
                    value /= divisor;
                } else return value;
            }
        }

        double parseFactor() {
            skipSpaces();
            if (match('+')) return parseFactor();
            if (match('-')) return -parseFactor();

            if (match('(')) {
                double value = parseExpression();
                skipSpaces();
                if (!match(')')) throw new IllegalArgumentException("Missing ')'.");
                return value;
            }

            int start = pos;
            boolean decimal = false;
            boolean digit = false;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isDigit(c)) {
                    digit = true;
                    pos++;
                } else if (c == '.' && !decimal) {
                    decimal = true;
                    pos++;
                } else {
                    break;
                }
            }

            if (!digit) throw new IllegalArgumentException("Expected a number near: " + input.substring(Math.min(start, input.length())));

            double value;
            try {
                value = Double.parseDouble(input.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number.");
            }

            if (pos < input.length()) {
                char suffix = Character.toLowerCase(input.charAt(pos));
                double multiplier = switch (suffix) {
                    case 'k' -> 1_000D;
                    case 'm' -> 1_000_000D;
                    case 'b' -> 1_000_000_000D;
                    case 't' -> 1_000_000_000_000D;
                    default -> 1D;
                };
                if (multiplier != 1D) {
                    pos++;
                    value *= multiplier;
                }
            }

            return value;
        }

        private boolean match(char expected) {
            if (pos < input.length() && input.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        private void skipSpaces() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }
    }
}
