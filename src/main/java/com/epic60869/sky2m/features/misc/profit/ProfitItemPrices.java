package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which price a profit tracker counts one item at, chosen per item by the player: the tracker's own Price Source
 * (Default), NPC sell, Bazaar Insta-Sell or Sell Order, the Auction House's lowest BIN, or a coin value they typed.
 * Used by every Profit Tracker, /s2 session and its share, and kept in {@code sky2m/profit-item-prices.json}.
 */
public final class ProfitItemPrices {
    private ProfitItemPrices() {}

    public enum Source {
        DEFAULT("Default", ""), NPC_SELL("NPC Sell", "NPC"), INSTA_SELL("Insta-Sell", "Insta"),
        SELL_ORDER("Sell Order", "Order"), AUCTION("Auction", "AH"), CUSTOM("Custom", "Custom");

        final String label;
        /** The short tag shown after an item's value when it isn't the tracker's default. */
        final String tag;

        Source(String label, String tag) {
            this.label = label;
            this.tag = tag;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One item's choice; {@code custom} is the coins per item for {@link Source#CUSTOM}. */
    private static final class Choice {
        Source source;
        double custom;

        Choice(Source source, double custom) {
            this.source = source;
            this.custom = custom;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Choice> CHOICES = new LinkedHashMap<>();
    private static Path file;

    static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("profit-item-prices.json");
        try {
            if (!Files.exists(file)) return;
            Map<String, Choice> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Choice>>() {}.getType());
            if (loaded == null) return;
            for (Map.Entry<String, Choice> e : loaded.entrySet()) {
                Choice c = e.getValue();
                if (e.getKey() == null || c == null) continue;
                if (c.source == null || c.source == Source.CUSTOM && !(c.custom > 0)) c.source = Source.DEFAULT;
                if (c.source == Source.DEFAULT && !(c.custom > 0)) continue;
                CHOICES.put(e.getKey(), c);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the profit tracker item prices: " + e.getMessage());
        }
    }

    private static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(CHOICES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't save the profit tracker item prices: " + e.getMessage());
        }
    }

    /** The price source the player picked for {@code id}, {@link Source#DEFAULT} if none. */
    public static Source source(String id) {
        Choice c = CHOICES.get(id);
        return c == null ? Source.DEFAULT : c.source;
    }

    /** The custom coins per item set for {@code id}, or 0. Kept while another source is picked, so it can be cycled back to. */
    public static double custom(String id) {
        Choice c = CHOICES.get(id);
        return c == null ? 0 : c.custom;
    }

    /** What one {@code id} is worth by a source alone (no fallback); 0 when that source has no price for it. */
    public static double priceBy(String id, Source source, FeatureConfigs.ProfitPriceSource fallback) {
        Double price = switch (source) {
            case DEFAULT -> Sky2MPriceTooltip.price(id, fallback);
            case NPC_SELL -> Sky2MPriceTooltip.npcSellPrice(id);
            case INSTA_SELL -> Sky2MPriceTooltip.bazaarSellPrice(id);
            case SELL_ORDER -> Sky2MPriceTooltip.bazaarBuyPrice(id);
            case AUCTION -> Sky2MPriceTooltip.lowestBinPrice(id);
            case CUSTOM -> custom(id);
        };
        return price == null || !(price > 0) ? 0 : price;
    }

    /**
     * What one {@code id} counts as in a tracker whose Price Source is {@code fallback}: the item's own source when the
     * player picked one and it has a price, else the tracker's. 0 if unknown.
     */
    public static double price(String id, FeatureConfigs.ProfitPriceSource fallback) {
        Choice c = CHOICES.get(id);
        if (c != null) {
            double own = priceBy(id, c.source, fallback);
            if (own > 0) return own;
        }
        return Sky2MPriceTooltip.price(id, fallback);
    }

    /** The sources that have a price for {@code id} now (Default always; Custom when a custom value is set). */
    public static List<Source> available(String id, FeatureConfigs.ProfitPriceSource fallback) {
        List<Source> out = new ArrayList<>();
        for (Source s : Source.values()) {
            if (s == Source.DEFAULT || priceBy(id, s, fallback) > 0) out.add(s);
        }
        return out;
    }

    /** The source after (or before, {@code step} -1) the item's current one among those {@link #available}. */
    public static Source next(String id, Source current, int step, FeatureConfigs.ProfitPriceSource fallback) {
        List<Source> sources = available(id, fallback);
        int at = sources.indexOf(current);
        if (at < 0) return step > 0 ? sources.getFirst() : sources.getLast();
        return sources.get(Math.floorMod(at + step, sources.size()));
    }

    /** Right-click on an item: its next available source, saved. */
    static void cycle(String id, int step, FeatureConfigs.ProfitPriceSource fallback) {
        set(id, next(id, source(id), step, fallback), custom(id));
    }

    /** Sets {@code id}'s source and custom value (0 clears it) and saves. */
    static void set(String id, Source source, double custom) {
        if (id == null) return;
        if (source == Source.CUSTOM && !(custom > 0)) source = Source.DEFAULT;
        if (source == Source.DEFAULT && !(custom > 0)) CHOICES.remove(id);
        else CHOICES.put(id, new Choice(source, Math.max(0, custom)));
        save();
    }

    /** " §8[NPC]" after an item's value when its own source is used, "" otherwise. */
    static String tag(String id, FeatureConfigs.ProfitPriceSource fallback) {
        Source s = source(id);
        if (s == Source.DEFAULT || priceBy(id, s, fallback) <= 0) return "";
        return " §8[" + s.tag + "]";
    }
}
