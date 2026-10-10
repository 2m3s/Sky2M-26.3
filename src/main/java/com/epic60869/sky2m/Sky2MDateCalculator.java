package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.util.Compat;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the real-world date to SkyBlock calendar tooltips. Ported from Skyblocker's
 * DateCalculatorTooltip (LGPL-3.0), including its use of Fabric's tooltip event so it
 * works alongside other mods that change tooltip rendering.
 */
public final class Sky2MDateCalculator {
    private static final Pattern TIMER_PATTERN = Pattern.compile("((?<days>\\d+)d)? ?((?<hours>\\d+)h)? ?((?<minutes>\\d+)m)? ?((?<seconds>\\d+)s)?");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("E MMM d yyyy HH:mm", Locale.US).withZone(ZoneId.systemDefault());
    private static final TimeProvider[] PROVIDERS = {new Calendar(), new Events()};
    private static TimeProvider currentTimer;

    private Sky2MDateCalculator() {}

    public static void init() {
        // Look at whatever screen is open when the tooltip is built, rather than relying on a
        // particular container screen class or its hovered slot, so it also works when another
        // mod replaces or wraps Hypixel's menus.
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Screen screen = Minecraft.getInstance().gui.screen();
            if (screen == null) return;
            currentTimer = timerFor(screen);
            addToTooltip(stack, lines);
        });
    }

    private static TimeProvider timerFor(Screen screen) {
        String screenTitle = ChatFormatting.stripFormatting(screen.getTitle().getString()).trim();
        for (TimeProvider timer : PROVIDERS) {
            if (timer.test(screenTitle)) return timer;
        }
        return null;
    }

    /**
     * Adds the real-world date to a container item's tooltip. Called from Fabric's tooltip event and again from
     * Sky2MContainerTooltipMixin (the menu's own tooltip), so it still works when another mod's tooltip listener
     * fails before ours runs; the date is only added once.
     */
    public static List<Component> addDates(ItemStack stack, List<Component> lines) {
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen == null) return lines;
        for (Component line : lines) {
            if (line.getStyle().isItalic() && DATE_LINE.matcher(line.getString()).matches()) return lines;
        }
        List<Component> out = new java.util.ArrayList<>(lines);
        currentTimer = timerFor(screen);
        addToTooltip(stack, out);
        return out;
    }

    private static final Pattern DATE_LINE = Pattern.compile("^[A-Z][a-z]{2} [A-Z][a-z]{2} \\d{1,2} \\d{4} \\d{2}:\\d{2}$");

    private static void addToTooltip(ItemStack stack, List<Component> lines) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.calendarTimeToRealTime || !Compat.isOnSkyblock()) return;

        boolean added = false;
        if (currentTimer != null) {
            for (int i = 1; i < lines.size(); i++) {
                String text = ChatFormatting.stripFormatting(lines.get(i).getString());

                //Only attempt to look for a timer if the line contains the qualifying text
                if (!currentTimer.qualifier().test(text)) continue;

                Instant instant = currentTimer.getStartTime(stack, text);

                if (instant != null) {
                    lines.add(++i, dateLine(instant));
                    added = true;
                }
            }
            // Sky2M: a day in the month view without an event line still gets its date, at the end.
            if (!added && currentTimer instanceof Calendar calendar) {
                Instant instant = calendar.getStartTime(stack, "");
                if (instant != null) {
                    lines.add(dateLine(instant));
                    added = true;
                }
            }
        }
        if (added) return;

        // Sky2M: any SkyBlock date written in the tooltip itself ("Late Spring 12th, Year 412"), in any menu.
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = WRITTEN_DATE.matcher(ChatFormatting.stripFormatting(lines.get(i).getString()));
            if (!m.find()) continue;
            int month = Calendar.MONTHS.indexOf(m.group("month").toLowerCase(Locale.ROOT));
            int day = Integer.parseInt(m.group("day"));
            int year = m.group("year") != null ? Integer.parseInt(m.group("year")) : currentYear();
            if (month < 0 || day < 1 || day > 31 || year < 1) continue;
            lines.add(i + 1, dateLine(Sky2MSkyblockTime.toRealWorld(year, month, day).toInstant()));
            return;
        }
    }

    private static final Pattern WRITTEN_DATE = Pattern.compile("(?<month>(?:Early |Late )?(?:Spring|Summer|Autumn|Winter)) (?<day>\\d{1,2})(?:st|nd|rd|th)?(?:,? Year (?<year>\\d+))?");

    private static int currentYear() {
        long elapsed = System.currentTimeMillis() - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli();
        return (int) (elapsed / Sky2MSkyblockTime.YEAR_MILLIS) + 1;
    }

    private static Component dateLine(Instant instant) {
        return Component.literal(DATE_FORMATTER.format(instant)).withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY);
    }

    private static boolean hasAnyGroup(MatchResult result) {
        for (String group : result.namedGroups().keySet()) {
            if (result.group(group) != null) return true;
        }
        return false;
    }

    private static int group(MatchResult result, String name) {
        String value = result.group(name);
        return value == null ? 0 : Integer.parseInt(value);
    }

    private interface TimeProvider {
        boolean test(String screenTitle);

        default Predicate<String> qualifier() {
            return l -> l.contains("Starts in") || l.contains("Ends in") || l.contains(" (");
        }

        Instant getStartTime(ItemStack stack, String qualifiedLine);
    }

    private static class Events implements TimeProvider {
        @Override
        public boolean test(String screenTitle) {
            // Sky2M: also "SkyBlock Calendar" and other calendar menus.
            return screenTitle.toLowerCase(Locale.ROOT).contains("calendar");
        }

        @Override
        public Instant getStartTime(ItemStack stack, String qualifiedLine) {
            MatchResult result = TIMER_PATTERN.matcher(qualifiedLine).results()
                .filter(Sky2MDateCalculator::hasAnyGroup) //Look for the first match that has what we're looking for
                .findFirst()
                .orElse(null);

            if (result != null) {
                return Instant.now()
                    .plus(group(result, "days"), ChronoUnit.DAYS)
                    .plus(group(result, "hours"), ChronoUnit.HOURS)
                    .plus(group(result, "minutes"), ChronoUnit.MINUTES)
                    .plusSeconds(group(result, "seconds"))
                    .plusSeconds(30) // Add 30 seconds to round to the nearest minute
                    .truncatedTo(ChronoUnit.MINUTES);
            }
            return null;
        }
    }

    private static class Calendar implements TimeProvider {
        private static final Pattern PATTERN = Pattern.compile("(?<month>(?:Early |Late )?(?:Spring|Summer|Autumn|Winter)),? Year (?<year>\\d+)");
        static final List<String> MONTHS = List.of(
            "early spring", "spring", "late spring", "early summer", "summer", "late summer",
            "early autumn", "autumn", "late autumn", "early winter", "winter", "late winter");
        private int monthIndex;
        private int year = 1;

        @Override
        public boolean test(String screenTitle) {
            Matcher matcher = PATTERN.matcher(screenTitle);
            if (!matcher.find()) return false;
            int maybeMonth = MONTHS.indexOf(matcher.group("month").trim().toLowerCase(Locale.ROOT));
            if (maybeMonth < 0) return false;
            int maybeYear;
            try {
                maybeYear = Integer.parseInt(matcher.group("year"));
            } catch (NumberFormatException e) {
                return false;
            }
            if (maybeYear == 0) return false;
            monthIndex = maybeMonth;
            year = maybeYear;
            return true;
        }

        @Override
        public Instant getStartTime(ItemStack stack, String qualifiedLine) {
            if (stack.getCount() < 1 || stack.getCount() > 31) return null;
            return Sky2MSkyblockTime.toRealWorld(year, monthIndex, stack.getCount()).toInstant();
        }
    }
}
