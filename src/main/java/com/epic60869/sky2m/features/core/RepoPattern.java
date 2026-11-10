package com.epic60869.sky2m.features.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A regex for Hypixel's text (chat, menus, lore, tab list, scoreboard) that Sky2M-REPO can replace without a new jar,
 * like SkyHanni's RepoPattern. The default stays in the code; {@code constants/Regexes.json} in the repo lists only
 * the patterns it replaces, by key ({@code <package>.<Class>.<FIELD>}, e.g. {@code garden.PestTimer.PEST_SPAWN}).
 * A replacement that doesn't compile is logged and the default kept. Removing a key from the repo restores the
 * default. {@code reference/AllRegexes.json} in the repo lists every key with its default.
 *
 * <p>Use it like a {@link Pattern}: {@code PATTERN.matcher(text)}. Code that needs a real {@link Pattern} calls
 * {@link #get()} each time (don't keep the result, or a repo change won't reach it).
 */
public final class RepoPattern {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, RepoPattern> ALL = new ConcurrentHashMap<>();
    private static volatile Map<String, String> overrides = Map.of();

    private final String key;
    private final String defaultRegex;
    private final int flags;
    private volatile Pattern pattern;

    private RepoPattern(String key, String regex, int flags) {
        this.key = key;
        this.defaultRegex = regex;
        this.flags = flags;
        this.pattern = Pattern.compile(regex, flags);
    }

    public static RepoPattern of(String key, String regex) {
        return of(key, regex, 0);
    }

    public static RepoPattern of(String key, String regex, int flags) {
        RepoPattern p = new RepoPattern(key, regex, flags);
        RepoPattern old = ALL.putIfAbsent(key, p);
        if (old != null) LOGGER.warn("[Sky2M] Two patterns use the key {}", key);
        String override = overrides.get(key);
        if (override != null) p.use(override);
        return p;
    }

    /** Starts loading Regexes.json from Sky2M-REPO. */
    public static void init() {
        Sky2MRepo.load("Regexes", RepoPattern::apply);
    }

    /** Applies a Regexes.json ({@code {"regexes": {"key": "regex"}}}): listed keys are replaced, the rest use their default. */
    public static void apply(JsonObject json) {
        Map<String, String> next = new HashMap<>();
        JsonObject regexes = json.has("regexes") ? json.getAsJsonObject("regexes") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : regexes.entrySet()) next.put(e.getKey(), e.getValue().getAsString());
        overrides = Collections.unmodifiableMap(next);
        int replaced = 0;
        for (RepoPattern p : ALL.values()) {
            String regex = next.get(p.key);
            if (regex != null && p.use(regex)) replaced++;
            else if (regex == null && !p.pattern.pattern().equals(p.defaultRegex)) p.pattern = Pattern.compile(p.defaultRegex, p.flags);
        }
        int unknown = 0;
        for (String k : next.keySet()) if (!ALL.containsKey(k)) unknown++;
        if (!next.isEmpty()) LOGGER.info("[Sky2M] Sky2M-REPO replaces {} patterns ({} keys not loaded yet or unknown)", replaced, unknown);
    }

    private boolean use(String regex) {
        if (regex.equals(pattern.pattern())) return false;
        try {
            pattern = Pattern.compile(regex, flags);
            return true;
        } catch (PatternSyntaxException e) {
            LOGGER.error("[Sky2M] Sky2M-REPO's pattern {} doesn't compile, keeping the current one: {}", key, e.getDescription());
            return false;
        }
    }

    /** Every pattern loaded so far, by key, with its default regex (for the repo's reference list). */
    public static Map<String, String> defaults() {
        Map<String, String> out = new TreeMap<>();
        for (RepoPattern p : ALL.values()) out.put(p.key, p.defaultRegex);
        return out;
    }

    /** The pattern with this key, or null if its class isn't loaded yet. */
    public static RepoPattern byKey(String key) {
        return ALL.get(key);
    }

    public String key() {
        return key;
    }

    /** The current {@link Pattern}. Call it each time rather than keeping it, so repo changes are picked up. */
    public Pattern get() {
        return pattern;
    }

    public Matcher matcher(CharSequence input) {
        return pattern.matcher(input);
    }

    public String pattern() {
        return pattern.pattern();
    }

    public int flags() {
        return pattern.flags();
    }

    public String[] split(CharSequence input) {
        return pattern.split(input);
    }

    public String[] split(CharSequence input, int limit) {
        return pattern.split(input, limit);
    }

    public Predicate<String> asPredicate() {
        return s -> pattern.matcher(s).find();
    }

    public Predicate<String> asMatchPredicate() {
        return s -> pattern.matcher(s).matches();
    }

    public java.util.stream.Stream<String> splitAsStream(CharSequence input) {
        return pattern.splitAsStream(input);
    }

    @Override
    public String toString() {
        return pattern.pattern();
    }
}
