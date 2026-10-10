package com.epic60869.sky2m;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * SkyBlock time conversion ported from Skyblocker's SkyblockTime.
 *
 * Hypixel SkyBlock uses a 50,000 ms hour, 24 hours per day,
 * 31 days per month, and 12 months per year.
 */
public final class Sky2MSkyblockTime {
    public static final Instant SKYBLOCK_EPOCH = Instant.ofEpochMilli(1560275700000L);

    public static final long HOUR_MILLIS = 50_000L;
    public static final long DAY_MILLIS = HOUR_MILLIS * 24L;
    public static final long MONTH_MILLIS = DAY_MILLIS * 31L;
    public static final long SEASON_MILLIS = MONTH_MILLIS * 3L;
    public static final long YEAR_MILLIS = SEASON_MILLIS * 4L;

    private static final DateTimeFormatter REAL_TIME_FORMAT =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm:ss VV", Locale.US);

    private Sky2MSkyblockTime() {}

    /**
     * Exact Skyblocker-style conversion from Calendar screen values.
     * Month is zero-based (0 = Early Spring), day is 1-31 and year starts at 1.
     */
    public static ZonedDateTime toRealWorld(int year, int monthIndex, int day) {
        if (year < 1 || monthIndex < 0 || monthIndex >= 12 || day < 1 || day > 31) {
            throw new IllegalArgumentException("Invalid SkyBlock calendar date");
        }

        long offset = (long) (year - 1) * YEAR_MILLIS
            + (long) monthIndex * MONTH_MILLIS
            + (long) (day - 1) * DAY_MILLIS;

        return SKYBLOCK_EPOCH.plusMillis(offset).atZone(ZoneId.systemDefault());
    }

    public static String formatRealWorld(int year, int monthIndex, int day) {
        return REAL_TIME_FORMAT.format(toRealWorld(year, monthIndex, day));
    }
}
