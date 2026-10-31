package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pet Rarity in chat, like SkyHanni's RareDropMessages pet rarity (hannibal002/SkyHanni, LGPL-2.1): Hypixel shows a
 * pet's rarity only as the colour of its name, so "PET DROP! Slug (+300% ✯ Magic Find)" with "Slug" purple becomes
 * "PET DROP! Epic Slug (+300% ✯ Magic Find)" with "Epic Slug" purple (Legendary gold, ...). Also for pets in RARE
 * DROP! lines and fishing's "GREAT CATCH! You caught a [Lvl 1] ..." lines. Only the text is added: every other part of
 * the message, with its click and hover events, stays as it was. Sky2M's own features still read Hypixel's original
 * line (they read the chat packet).
 */
public final class PetDropRarity {
    /** "PET DROP! " or "RARE DROP! " (and the rarer kinds) before the pet's name. */
    private static final Pattern DROP = Pattern.compile(
        "^\\s*(?<kind>PET DROP!|(?:VERY |CRAZY |INSANE |INCREDIBLY )?RARE DROP!|PRAY TO RNGESUS DROP!) +\\(?(?<name>[^(!]+?)\\)?(?:\\s+x[\\d,]+)?(?:\\s+\\(.*)?\\s*!?\\s*$");
    /** "⛃ GREAT CATCH! You caught a [Lvl 1] Squid!" (fishing a pet). */
    private static final Pattern CATCH = Pattern.compile(
        "^[^:]{0,4}(?:GOOD|GREAT|OUTSTANDING) CATCH! You caught an? \\[Lvl \\d+] (?<name>[^(!]+?)\\s*!?\\s*$");
    private static final List<String> RARITIES = List.of("Common", "Uncommon", "Rare", "Epic", "Legendary", "Mythic");
    private static final Pattern LEGACY_COLOUR = Pattern.compile("§([0-9a-fA-F])");

    private PetDropRarity() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.chat.petRarity;
    }

    public static void init() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((component, overlay) -> {
            if (overlay || !enabled() || !Compat.isOnSkyblock()) return component;
            try {
                Component changed = rewrite(component);
                return changed == null ? component : changed;
            } catch (Exception e) {
                return component;
            }
        });
    }

    /** One styled piece of a message, as {@link Component#visit} gives it (the style already includes its parents'). */
    private record Part(Style style, String text) {}

    /** Where the pet's name starts in the message (which part, which character of it) and its rarity tier. */
    private record Found(int part, int offset, int tier, String name) {}

    /** The message with the rarity word before the pet's name, or null if it isn't a pet drop line. */
    static Component rewrite(Component component) {
        List<Part> parts = parts(component);
        Found found = find(parts);
        if (found == null) return null;
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            String text = p.text();
            if (i == found.part()) {
                // In the name's own style, so the word is the name's colour and has its hover/click too.
                text = text.substring(0, found.offset()) + RARITIES.get(found.tier()) + " " + text.substring(found.offset());
            }
            out.append(Component.literal(text).setStyle(p.style()));
        }
        return out;
    }

    /**
     * "Epic Slug" in the pet's rarity colour for {@code item} in a drop {@code message}, for Sky2M's own drop messages;
     * null when the item isn't a pet (or the setting is off).
     */
    public static MutableComponent label(Component message, String item) {
        if (!enabled() || message == null || item == null) return null;
        try {
            Found found = find(parts(message));
            if (found == null || !found.name().equalsIgnoreCase(item.trim())) return null;
            return Component.literal(RARITIES.get(found.tier()) + " " + found.name()).withStyle(colour(found.tier()));
        } catch (Exception e) {
            return null;
        }
    }

    private static ChatFormatting colour(int tier) {
        return switch (tier) {
            case 1 -> ChatFormatting.GREEN;
            case 2 -> ChatFormatting.BLUE;
            case 3 -> ChatFormatting.DARK_PURPLE;
            case 4 -> ChatFormatting.GOLD;
            case 5 -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.WHITE;
        };
    }

    private static List<Part> parts(Component component) {
        List<Part> parts = new ArrayList<>();
        component.visit((style, text) -> {
            if (!text.isEmpty()) parts.add(new Part(style, text));
            return Optional.empty();
        }, Style.EMPTY);
        return parts;
    }

    private static Found find(List<Part> parts) {
        StringBuilder raw = new StringBuilder();
        for (Part p : parts) raw.append(p.text());
        String plain = Sky2MLocation.strip(raw.toString());
        boolean drop = true;
        Matcher m = DROP.matcher(plain);
        if (!m.matches()) {
            m = CATCH.matcher(plain);
            if (!m.matches()) return null;
            drop = false;
        }
        String name = m.group("name").trim();
        if (name.isEmpty()) return null;
        // Already says its rarity (another mod, or Hypixel one day).
        String first = name.split(" ")[0];
        for (String rarity : RARITIES) if (rarity.equalsIgnoreCase(first)) return null;
        int start = m.start("name");
        while (start < plain.length() && plain.charAt(start) == ' ') start++;
        // The part and character of the name's first letter, skipping legacy "§x" codes in the text.
        int plainIndex = 0;
        for (int i = 0; i < parts.size(); i++) {
            String text = parts.get(i).text();
            for (int j = 0; j < text.length(); j++) {
                if (text.charAt(j) == '§' && j + 1 < text.length() && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(text.charAt(j + 1))) >= 0) {
                    j++;
                    continue;
                }
                if (plainIndex == start) {
                    int tier = tier(parts.get(i), j);
                    if (tier < 0) return null;
                    boolean pet = drop && m.group("kind").startsWith("PET") || isPet(name, tier);
                    return pet ? new Found(i, j, tier, name) : null;
                }
                plainIndex++;
            }
        }
        return null;
    }

    /** The rarity tier (0 Common ... 5 Mythic) of the name's colour at {@code index} of the part, -1 if not one. */
    private static int tier(Part part, int index) {
        String before = part.text().substring(0, index);
        Matcher legacy = LEGACY_COLOUR.matcher(before);
        int code = -1;
        while (legacy.find()) code = legacy.group(1).toLowerCase(Locale.ROOT).charAt(0);
        if (code != -1) {
            return switch ((char) code) {
                case 'f' -> 0;
                case 'a' -> 1;
                case '9' -> 2;
                case '5' -> 3;
                case '6' -> 4;
                case 'd' -> 5;
                default -> -1;
            };
        }
        TextColor colour = part.style().getColor();
        if (colour == null) return -1;
        return switch (colour.getValue() & 0xFFFFFF) {
            case 0xFFFFFF -> 0;
            case 0x55FF55 -> 1;
            case 0x5555FF -> 2;
            case 0xAA00AA -> 3;
            case 0xFFAA00 -> 4;
            case 0xFF55FF -> 5;
            default -> -1;
        };
    }

    /** Whether the item list has a pet of this name and rarity ("SLUG;3" is an Epic Slug). */
    private static boolean isPet(String name, int tier) {
        String base = name.endsWith(" Pet") ? name.substring(0, name.length() - 4) : name;
        String id = base.toUpperCase(Locale.ROOT).replace('-', '_').replaceAll("[^A-Z0-9_ ]", "").trim().replace(' ', '_');
        String repo = RepoItems.displayName(id + ";" + tier);
        return repo != null && repo.contains("[Lvl");
    }
}
