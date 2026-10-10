package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pet HUD's icon, as Skysoft draws it (https://github.com/Akinsoft/Skysoft, features/pets/PetDisplayRenderer.kt,
 * utils/renderables/decorators/CircularLayoutRenderable.kt and the pet display visual configs, LGPL-3.0): the pet's
 * head at 1.5x, its held item on the bottom-right, on a circle in the pet's rarity colour, inside a grey divider ring
 * and a level progress ring (cyan filled, silver unfilled, clockwise from the top). Heads (and skins seen in the Pets
 * menu) come from the NEU repo.
 */
public final class PetIconRenderer {
    private static final float ICON_SCALE = 1.5f;
    private static final int ICON_SIZE = Math.round(16 * ICON_SCALE);
    private static final int BACKGROUND_PADDING = 2;
    private static final int RING_PADDING = 2;
    private static final int DIVIDER_COLOUR = 0xFF808080;
    private static final int PROGRESS_FILLED = 0xFF00FFFF;
    private static final int PROGRESS_UNFILLED = 0xFFC0C0C0;
    private static final Pattern PERCENT = Pattern.compile("\\((\\d+(?:\\.\\d+)?)%\\)");
    private static final Pattern TEXTURE = Pattern.compile("Value:\\\\?\"([A-Za-z0-9+/=]+)\\\\?\"");
    private static final int[] TIER_COLOURS = {0xFFFFFFFF, 0xFF55FF55, 0xFF5555FF, 0xFFAA00AA, 0xFFFFAA00, 0xFFFF55FF};

    /** NEU repo item id -> its head (EMPTY while loading or if it has none). */
    private static final Map<String, ItemStack> HEADS = new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    /** Pet name (lower case) -> skin id, from the Pets menu. */
    private static final Map<String, String> SKINS = new ConcurrentHashMap<>();

    private static String petName = "";
    private static int tier = 4;
    private static float progress = -1;

    private PetIconRenderer() {}

    /** Width and height of the icon with its rings. */
    public static int size() {
        int radius = ICON_SIZE / 2 + BACKGROUND_PADDING;
        return (radius + RING_PADDING * 2) * 2;
    }

    /** From the Pets menu: the pet's skin ("" for none). */
    public static void fromMenu(String shownName, String skin) {
        String key = key(shownName);
        if (key.isEmpty()) return;
        if (skin == null || skin.isBlank()) SKINS.remove(key);
        else SKINS.put(key, skin);
    }

    /** The tab list's pet: its name, its rarity (the name's colour) and its level progress from the XP line. */
    public static void update(String name, Component nameComponent, List<Component> lines) {
        petName = name == null ? "" : name;
        tier = nameComponent == null ? 4 : tierOf(nameComponent, petName);
        progress = -1;
        for (Component line : lines) {
            String text = line.getString();
            if (text.contains("MAX LEVEL")) progress = 1;
            Matcher m = PERCENT.matcher(text);
            if (m.find()) progress = Math.min(1f, Float.parseFloat(m.group(1)) / 100f);
        }
    }

    public static void clear() {
        petName = "";
        progress = -1;
    }

    private static int tierOf(Component component, String name) {
        int[] found = {4};
        component.visit((style, value) -> {
            if (!value.isBlank() && name.contains(value.trim()) && style.getColor() != null) {
                int rgb = style.getColor().getValue() | 0xFF000000;
                for (int i = 0; i < TIER_COLOURS.length; i++) if (TIER_COLOURS[i] == rgb) found[0] = i;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    private static String key(String name) {
        return PetHeldItems.key(name);
    }

    /** "Golden Dragon" -> "GOLDEN_DRAGON;4". */
    private static String petId(String name, int tier) {
        String type = name.replace("✦", "").replaceAll("[^A-Za-z ]", "").trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return type + ";" + tier;
    }

    private static ItemStack head(String repoId) {
        ItemStack head = HEADS.get(repoId);
        if (head != null) return head;
        if (REQUESTED.add(repoId)) {
            RepoItems.runAsync(() -> {
                String texture = null;
                try {
                    JsonObject item = JsonParser.parseString(RepoItems.neuRepoFile("items/" + repoId + ".json")).getAsJsonObject();
                    Matcher m = TEXTURE.matcher(item.has("nbttag") ? item.get("nbttag").getAsString() : "");
                    if (m.find()) texture = m.group(1);
                } catch (Exception ignored) {}
                String found = texture;
                Minecraft.getInstance().execute(() -> HEADS.put(repoId, found == null ? ItemStack.EMPTY : Compat.createSkull(found)));
            });
        }
        return ItemStack.EMPTY;
    }

    /** The pet's head (its skin's when it has one), or EMPTY while it loads. */
    private static ItemStack petHead(String name, int tier) {
        String skin = SKINS.get(key(name));
        if (skin != null) {
            ItemStack skinHead = head("PET_SKIN_" + skin);
            if (!skinHead.isEmpty()) return skinHead;
        }
        return head(petId(name, tier));
    }

    /**
     * Draws the icon with its top-left at (x, y). In the HUD editor without a pet, a level 200 legendary Golden Dragon
     * at 93.8%.
     */
    public static void render(GuiGraphicsExtractor g, int x, int y, boolean preview) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null) return;
        Sky2MConfig.PetDisplay settings = config.misc.pets.display;
        boolean sample = preview && petName.isEmpty();
        String name = sample ? "Golden Dragon" : petName;
        int petTier = sample ? 4 : tier;
        float level = sample ? 0.938f : progress;
        if (name.isEmpty()) return;

        int size = size();
        float centre = size / 2f;
        int background = ICON_SIZE / 2 + BACKGROUND_PADDING;
        Minecraft mc = Minecraft.getInstance();
        int resolution = Math.clamp((int) Math.round(mc.getWindow().getGuiScale()), 1, 2);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(1f / resolution, 1f / resolution);
        float c = centre * resolution;
        if (settings.iconRarityBackground) {
            if (settings.iconXpRing) {
                ring(g, c, (background + RING_PADDING * 2) * resolution, level < 0 ? 1 : level);
                disc(g, c, (background + RING_PADDING) * resolution, DIVIDER_COLOUR);
            }
            disc(g, c, background * resolution, TIER_COLOURS[petTier]);
        }
        g.pose().popMatrix();

        int iconX = x + Math.round(centre - ICON_SIZE / 2f), iconY = y + Math.round(centre - ICON_SIZE / 2f);
        ItemStack head = petHead(name, petTier);
        if (!head.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(iconX, iconY);
            g.pose().scale(ICON_SCALE, ICON_SCALE);
            g.item(head, 0, 0);
            g.pose().popMatrix();
        }
        if (settings.iconHeldItem) {
            String held = sample ? "DWARF_TURTLE_SHELMET" : PetHeldItems.heldItemId(name);
            if (held != null && !held.isEmpty() && RepoItems.displayName(held) != null) {
                // Hypixel's model when its pack is loaded, else the item's picture: many pet items are paper underneath.
                com.epic60869.sky2m.features.sbc.SbcItemIcons.drawId(g, held, iconX + ICON_SIZE - 8, iconY + ICON_SIZE - 8);
            }
        }
    }

    /** A filled circle around (c, c), one row of pixels at a time. */
    private static void disc(GuiGraphicsExtractor g, float c, int radius, int colour) {
        for (int dy = -radius; dy < radius; dy++) {
            float yMid = dy + 0.5f;
            int half = (int) Math.floor(Math.sqrt(Math.max(0, radius * (float) radius - yMid * yMid)) + 0.5f);
            if (half <= 0) continue;
            int y = Math.round(c + dy);
            g.fill(Math.round(c - half), y, Math.round(c + half), y + 1, colour);
        }
    }

    /** A circle filled clockwise from the top for {@code fraction} of the way round, the rest in the unfilled colour. */
    private static void ring(GuiGraphicsExtractor g, float c, int radius, float fraction) {
        if (fraction >= 1) {
            disc(g, c, radius, PROGRESS_FILLED);
            return;
        }
        double limit = Math.max(0, fraction) * Math.PI * 2;
        for (int dy = -radius; dy < radius; dy++) {
            float yMid = dy + 0.5f;
            int half = (int) Math.floor(Math.sqrt(Math.max(0, radius * (float) radius - yMid * yMid)) + 0.5f);
            if (half <= 0) continue;
            int y = Math.round(c + dy);
            int runStart = -half;
            boolean runFilled = filled(-half + 0.5f, yMid, limit);
            for (int dx = -half + 1; dx <= half; dx++) {
                boolean f = dx < half && filled(dx + 0.5f, yMid, limit);
                if (dx == half || f != runFilled) {
                    g.fill(Math.round(c + runStart), y, Math.round(c + dx), y + 1, runFilled ? PROGRESS_FILLED : PROGRESS_UNFILLED);
                    runStart = dx;
                    runFilled = f;
                }
            }
        }
    }

    private static boolean filled(float dx, float dy, double limit) {
        double angle = Math.atan2(dx, -dy);
        if (angle < 0) angle += Math.PI * 2;
        return angle < limit;
    }
}
