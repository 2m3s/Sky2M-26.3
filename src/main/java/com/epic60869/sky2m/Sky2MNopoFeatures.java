package com.epic60869.sky2m;

import com.epic60869.sky2m.mixin.Sky2MPlayerTabOverlayAccessor;
import com.epic60869.sky2m.sb.utils.Location;
import com.epic60869.sky2m.sb.utils.Utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Sky2MNopoFeatures {
    private static final Gson GSON = new Gson();
    private static final String SLAYER_FILE = "sky2m-slayer-drops.json";
    private static final String CROP_FILE = "sky2m-rare-crops.json";
    private static final Identifier PET_HUD_ID = Identifier.fromNamespaceAndPath("sky2m", "pet_display");

    private static Path configDir;
    private static boolean initialized;

    private static final Set<String> EMOJIS = new HashSet<>();
    private static final Pattern EMOJI_PATTERN = Pattern.compile(":([A-Za-z0-9_+\\-]+):");
    private static final Map<String, String> EMOJI_SYMBOLS = new LinkedHashMap<>();
    private static final Map<String, String> EMOJI_CANONICAL = new HashMap<>();
    private static final Map<String, String> EMOJI_UNICODE = new HashMap<>();
    private static final Map<String, SlayerData> SLAYERS = new LinkedHashMap<>();
    private static final Map<String, List<Long>> CROP_TIMES = new LinkedHashMap<>();
    private static String currentSlayer = null;
    private static final List<PendingDrop> PENDING_SLAYER_DROPS = new ArrayList<>();
    private static boolean slayerWorldChanged = false;
    private static Object lastLevel = null;

    private static final Pattern SLAYER_START = Pattern.compile(" +(?<slayer>Wolf|Zombie|Blaze|Vampire|Spider|Enderman|Guardian) Slayer LVL \\d.*");
    private static final Pattern SLAYER_DROP = Pattern.compile("(?:VERY RARE|RARE|INSANE|CRAZY RARE) DROP! \\((?<amount>\\d+x )?(?<item>[^)]+)\\)(?: .+)?");

    private static final Pattern RARE_CROP = Pattern.compile("(?:VERY )?RARE CROP! (?<crop>[a-zA-Z ]+) \\(\\+[0-9,.]+.*?\\)(?: \\(automatically donated\\))?");
    private static final Pattern PET_DROP = Pattern.compile("PET DROP! (?<pet>\\w+) \\(\\+[0-9,.]+.*?\\)");
    private static final Pattern MOSQUITO = Pattern.compile("MOSQUITO! You found an? (?<crop>.*)!");
    private static final Pattern RAT = Pattern.compile("RAT! You dropped an additional (?<crop>.*)!");
    private static final String CROP_FEVER = "WOAH! You caught a case of the CROP FEVER for 60 seconds!";

    private static List<Component> petDisplay = null;
    private static final Map<String, Component> PET_ICON_CACHE = new HashMap<>();
    private static int petTick;
    private static String currentPet = "";
    private static int currentOverflowLevel = -1;

    /** The summoned pet's name from the tab list, or "" when there is none or it isn't known. */
    public static String currentPet() {
        return currentPet;
    }

    private static final int[] PET_XP = {
        100,110,120,130,145,160,175,190,210,230,250,275,300,330,360,400,440,490,540,600,
        660,730,800,880,960,1050,1150,1260,1380,1510,1650,1800,1960,2130,2310,2500,2700,
        2920,3160,3420,3700,4000,4350,4750,5200,5700,6300,7000,7800,8700,9700,10800,
        12000,13300,14700,16200,17800,19500,21300,23200,25200,27400,29800,32400,35200,
        38200,41400,44800,48400,52200,56200,60400,64800,69400,74200,79200,84700,90700,
        97200,104200,111700,119700,128200,137200,146700,156700,167700,179700,192700,
        206700,221700,237700,254700,272700,291700,311700,333700,357700,383700,411700,
        441700,476700,516700,561700,611700,666700,726700,791700,861700,936700,1016700,
        1101700,1191700,1286700,1386700,1496700,1616700,1746700,1886700
    };

    private Sky2MNopoFeatures() {}

    public static void init(Path dir) {
        configDir = dir;
        PetHeldItems.init(dir, () -> currentPet);
        loadJson();
        loadDefaultEmojiSymbols();
        loadEmojis();
        loadBuiltInEmojiAliases();
        com.epic60869.sky2m.features.core.Sky2MChat.onGameMessage((message, overlay) -> { handleSlayer(message); handleRareCrop(message); });
        registerOverflowPets();
        registerPetHud();
        initialized = true;
    }

    // Active pet XP, read from the Pets menu, used to show overflow levels in the pet HUD.
    private static String activePetName = "";
    private static float activePetExp = -1;
    private static String activePetTier = "LEGENDARY";

    private static void scanPetsMenu(Minecraft mc) {
        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)) return;
        if (!screen.getTitle().getString().startsWith("Pets")) return;
        for (var slot : screen.getMenu().slots) {
            var custom = slot.getItem().get(DataComponents.CUSTOM_DATA);
            if (custom == null) continue;
            try {
                String petInfo = custom.copyTag().getStringOr("petInfo", "");
                if (petInfo.isBlank()) continue;
                JsonObject json = JsonParser.parseString(petInfo).getAsJsonObject();
                // Every pet's held item, for the pet HUD when you switch to it later.
                String shownName = clean(com.epic60869.sky2m.custom.util.Compat.realName(slot.getItem()).getString());
                PetHeldItems.fromMenu(shownName, json.has("heldItem") && !json.get("heldItem").isJsonNull() ? json.get("heldItem").getAsString() : "");
                PetIconRenderer.fromMenu(shownName, json.has("skin") && !json.get("skin").isJsonNull() ? json.get("skin").getAsString() : "");
                if (!json.has("active") || !json.get("active").getAsBoolean() || !json.has("exp")) continue;
                activePetExp = json.get("exp").getAsFloat();
                activePetTier = json.has("tier") ? json.get("tier").getAsString() : "LEGENDARY";
                activePetName = shownName.replaceAll("^\\[Lvl \\d+\\]\\s*", "").trim();
            } catch (Throwable ignored) {}
        }
    }

    /** Overflow level for the active pet, or -1 if unknown or not above the normal maximum. */
    private static int overflowLevel(String petName, int level) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.pets.display.overflowLevels) return -1;
        if (activePetExp < 0 || !petName.equalsIgnoreCase(activePetName)) return -1;
        int overflow = calcLevel(activePetExp, rarityOffset(activePetTier));
        return overflow > level ? overflow : -1;
    }

    public static void tick(Minecraft mc) {
        if (!initialized) return;
        tickSlayerDrops(mc);
        if (petTick % 5 == 0) scanPetsMenu(mc);
        petTick++;
        if (petTick >= 10) {
            petTick = 0;
            updatePetDisplay(mc);
        }
    }

    private static void registerOverflowPets() {
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            if (stack.getItem() != Items.PLAYER_HEAD) return;
            if (!isHypixel()) return;

            try {
                var custom = stack.get(DataComponents.CUSTOM_DATA);
                if (custom == null) return;
                String petInfo = custom.copyTag().getStringOr("petInfo", "");
                if (petInfo.isBlank()) return;

                JsonObject json = JsonParser.parseString(petInfo).getAsJsonObject();
                if (!json.has("exp")) return;

                float xp = json.get("exp").getAsFloat();
                String tier = json.has("tier") ? json.get("tier").getAsString() : "LEGENDARY";
                int rarityOffset = rarityOffset(tier);
                int overflowLevel = calcLevel(xp, rarityOffset);

                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).getString().contains("MAX LEVEL")) continue;

                    MutableComponent line = Component.literal("MAX LEVEL")
                        .withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true));
                    line.append(Component.literal(" ["));
                    line.append(Component.literal(overflowLevel + "✦")
                        .withStyle(s -> s.withColor(ChatFormatting.GOLD)));
                    line.append(Component.literal("]").withStyle(s -> s.withColor(ChatFormatting.GRAY)));
                    lines.set(i, line);
                    break;
                }
            } catch (Throwable ignored) {
            }
        });
    }

    private static void registerPetHud() {
        HudElementRegistry.addLast(PET_HUD_ID, (context, deltaTracker) -> renderPetHud(context));
    }

    private static void renderPetHud(GuiGraphicsExtractor context) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.pets.display.enabled) return;
        if (!isHypixel() || petDisplay == null || petDisplay.isEmpty()) return;
        renderPetHudAt(context, petDisplay, com.epic60869.sky2m.features.core.Sky2MHuds.mapX(config.misc.pets.display.x, petHudWidth()), com.epic60869.sky2m.features.core.Sky2MHuds.mapY(config.misc.pets.display.y, petHudHeight()));
    }

    private static final List<Component> PET_PREVIEW = List.of(
        Component.literal("Pet:").withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)),
        Component.literal(" [Lvl 200] ").withStyle(ChatFormatting.GRAY).append(Component.literal("Golden Dragon").withStyle(ChatFormatting.GOLD)),
        Component.literal("2,345,678/2,500,000 XP (93.8%)").withStyle(ChatFormatting.YELLOW)
    );
    private static final int PET_LINE_HEIGHT = 11;

    private static List<Component> shownPetLines() {
        return petDisplay != null && !petDisplay.isEmpty() ? petDisplay : PET_PREVIEW;
    }

    public static float petHudScale() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? 1.0f : config.misc.pets.display.scale;
    }

    /** Scaled on-screen width of the pet HUD, matching exactly what is drawn. */
    public static int petHudWidth() {
        var font = Minecraft.getInstance().font;
        int w = 0;
        for (Component line : shownPetLines()) w = Math.max(w, font.width(line));
        return Math.max(1, Math.round((w + petIconSpace()) * petHudScale()));
    }

    /** Room the Skysoft pet icon takes beside the text, or 0 when it's off. */
    private static int petIconSpace() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.misc.pets.display.icon ? PetIconRenderer.size() + 3 : 0;
    }

    /** Scaled on-screen height of the pet HUD, matching exactly what is drawn. */
    public static int petHudHeight() {
        int height = Math.max(shownPetLines().size() * PET_LINE_HEIGHT - 2, petIconSpace() > 0 ? PetIconRenderer.size() : 0);
        return Math.max(1, Math.round(height * petHudScale()));
    }

    public static void renderPetHudPreview(GuiGraphicsExtractor context, int x, int y) {
        renderPetHudAt(context, shownPetLines(), x, y, true);
    }

    private static void renderPetHudAt(GuiGraphicsExtractor context, List<Component> lines, int x, int y) {
        renderPetHudAt(context, lines, x, y, false);
    }

    private static void renderPetHudAt(GuiGraphicsExtractor context, List<Component> lines, int x, int y, boolean preview) {
        var font = Minecraft.getInstance().font;
        float scale = petHudScale();
        context.pose().pushMatrix();
        context.pose().translate((float) x, (float) y);
        context.pose().scale(scale, scale);
        Sky2MConfig config = Sky2MConfig.current();
        int iconSpace = petIconSpace();
        int textHeight = lines.size() * PET_LINE_HEIGHT - 2;
        int height = Math.max(textHeight, iconSpace > 0 ? PetIconRenderer.size() : 0);
        if (config != null && config.misc.pets.display.background && !lines.isEmpty()) {
            int w = 0;
            for (Component line : lines) w = Math.max(w, font.width(line));
            context.fill(-2, -2, iconSpace + w + 2, height + 2, 0x80000000);
        }
        // Skysoft draws the text to the right of the icon, centred on it.
        if (iconSpace > 0) PetIconRenderer.render(context, 0, (height - PetIconRenderer.size()) / 2, preview);
        int textTop = (height - textHeight) / 2;
        for (int i = 0; i < lines.size(); i++) {
            context.text(font, lines.get(i), iconSpace, textTop + i * PET_LINE_HEIGHT, -1);
        }
        context.pose().popMatrix();
    }

    // Matched anywhere in the row, like Skyblocker's PetWidget: Hypixel sometimes puts icons or
    // other text around "[Lvl N] Name".
    private static final Pattern PET_ROW = Pattern.compile("^(?!Pet\\s*:).*?\\[Lvl\\s+(?<level>\\d+)\\]\\s*(?<name>.+?)(?:\\s*✦)?\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PET_INLINE = Pattern.compile("^Pet\\s*:.*?\\[Lvl\\s+(?<level>\\d+)\\]\\s*(?<name>.+?)(?:\\s*✦)?\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PET_XP_LINE = Pattern.compile("^(?:\\+)?[\\d,.]+(?:[kmb])?(?:\\s*/\\s*[\\d,.]+(?:[kmb])?)?\\s+XP.*$",
        Pattern.CASE_INSENSITIVE);
    // Past max level Hypixel shows only the extra XP (" +123,456.7 XP"); NopoMod adds the XP of the
    // capped levels back and recomputes the level on the legendary curve.
    private static final Pattern PET_OVERFLOW_XP = Pattern.compile("^\\+(?<xp>[\\d,.]+) XP$");

    private static void updatePetDisplay(Minecraft mc) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.pets.display.enabled || !config.misc.pets.display.autoDisplay
            || !isHypixel() || mc.getConnection() == null) {
            petDisplay = null;
            return;
        }

        List<PlayerInfo> entries = new ArrayList<>(mc.getConnection().getOnlinePlayers());
        try {
            entries.sort(Sky2MPlayerTabOverlayAccessor.getOrdering());
        } catch (Throwable ignored) {}

        /*
         * Read the actual ordered TAB rows directly. The generic widget parser is
         * useful for most widgets, but Pet can be emitted by Hypixel as either
         * "Pet:" + "[Lvl ...] ..." or a single "Pet: [Lvl ...] ..." component.
         * In both cases the pet data is authoritative in these rows.
         */
        Pattern petPattern = PET_ROW;
        Pattern inlinePattern = PET_INLINE;
        Pattern xpPattern = PET_XP_LINE;
        Pattern overflowXpPattern = PET_OVERFLOW_XP;
        int shownOverflow = -1;

        List<Component> display = new ArrayList<>();
        String petName = "";
        int level = -1;
        int nameIndex = -1;
        Component nameComponent = null;
        boolean inPet = false;

        for (PlayerInfo entry : entries) {
            Component component = com.epic60869.sky2m.custom.util.Compat.rawTabName(entry);
            if (component == null && entry.getProfile() != null) {
                component = Component.literal(entry.getProfile().name());
            }
            if (component == null) continue;

            String raw = component.getString();
            String text = raw.strip();
            if (text.isBlank()) continue;

            Matcher inline = inlinePattern.matcher(text);
            Matcher normal = petPattern.matcher(text);

            if (inline.matches()) {
                inPet = true;
                level = Integer.parseInt(inline.group("level"));
                petName = inline.group("name").strip();
                display.clear();
                display.add(Component.literal("Pet:")
                    .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)));
                nameIndex = display.size();
                nameComponent = component;
                display.add(stylePetLine(component, level, petName, overflowLevel(petName, level)));
                continue;
            }

            if (!inPet && (text.equalsIgnoreCase("Pet:") || text.equalsIgnoreCase("Pet"))) {
                inPet = true;
                display.clear();
                display.add(Component.literal("Pet:")
                    .withStyle(style -> style.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)));
                continue;
            }

            if (!inPet) {
                // Some Hypixel revisions omit the Pet header entirely.
                if (normal.matches()) {
                    inPet = true;
                } else {
                    continue;
                }
            }

            if (normal.matches()) {
                level = Integer.parseInt(normal.group("level"));
                petName = normal.group("name").strip();
                nameIndex = display.size();
                nameComponent = component;
                display.add(stylePetLine(component, level, petName, overflowLevel(petName, level)));
                continue;
            }

            Matcher overflowXp = overflowXpPattern.matcher(text);
            if (!petName.isBlank() && nameIndex >= 0 && overflowXp.matches() && overflowLevelsEnabled()) {
                try {
                    int offset = rarityOffsetFromComponent(nameComponent, petName.replace("✦", "").strip());
                    float xp = Float.parseFloat(overflowXp.group("xp").replace(",", "")) + calculativeXpForLevel(level, offset);
                    int overflow = calcLevel(xp);
                    if (level == 200) overflow--; // Golden Dragon's curve starts at level 100
                    if (overflow > level) {
                        display.set(nameIndex, stylePetLine(nameComponent, level, petName, overflow));
                        shownOverflow = overflow;
                    }
                    display.add(overflowProgressLine(xp, overflow));
                    continue;
                } catch (NumberFormatException ignored) {}
            }

            if (!petName.isBlank() && (xpPattern.matcher(text).matches() || text.contains("MAX LEVEL"))) {
                display.add(component);
                continue;
            }

            // Once the Pet block has started, a new top-level widget terminates it.
            if (!raw.startsWith(" ") && text.contains(":")) {
                break;
            }
        }

        if (petName.isBlank()) {
            petDisplay = null;
            PetIconRenderer.clear();
            currentPet = "";
            currentOverflowLevel = -1;
            return;
        }

        if (!currentPet.equals(petName)) {
            currentPet = petName;
            currentOverflowLevel = shownOverflow > 0 ? shownOverflow : level;
        } else if (shownOverflow > 0) {
            // NopoMod's overflow level up message: Hypixel stops announcing levels past the maximum.
            if (currentOverflowLevel > 0 && shownOverflow == currentOverflowLevel + 1) {
                com.epic60869.sky2m.features.core.Sky2MAlerts.chat(Component.literal("Your ").withStyle(ChatFormatting.GRAY)
                    .append(petNameComponent(nameComponent, petName))
                    .append(Component.literal(" leveled up to level ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(Integer.toString(shownOverflow)).withStyle(ChatFormatting.BLUE))
                    .append(Component.literal("!").withStyle(ChatFormatting.GRAY)));
            }
            currentOverflowLevel = shownOverflow;
        }

        if (config.misc.pets.display.heldItem) {
            Component held = PetHeldItems.heldItem(petName);
            if (held != null) display.add(Component.literal(" Held: ").withStyle(ChatFormatting.GRAY).append(held));
        }

        petDisplay = List.copyOf(display);
        PetIconRenderer.update(petName, nameComponent, display);
    }

    private static boolean overflowLevelsEnabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.misc.pets.display.overflowLevels;
    }

    /** " 1,832,110.4/1.9M XP (97.1%)" progress towards the next overflow level, as NopoMod shows it. */
    private static Component overflowProgressLine(float xp, int overflowLevel) {
        float progress = Math.max(0, leftoverXp(xp));
        int next = getXpForLevel(overflowLevel - 1, 20);
        return Component.literal(" " + String.format(Locale.US, "%,.1f", progress)).withStyle(ChatFormatting.YELLOW)
            .append(Component.literal("/").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(compact(next) + " XP ").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal("(" + String.format(Locale.US, "%.1f", progress / next * 100) + "%)").withStyle(ChatFormatting.GOLD));
    }

    private static String compact(double value) {
        if (value >= 1_000_000) return String.format(Locale.US, "%.1fM", value / 1_000_000);
        if (value >= 1_000) return String.format(Locale.US, "%.1fK", value / 1_000);
        return String.valueOf((long) value);
    }

    private static Component stylePetLine(Component original, int level, String petName, int overflow) {
        // Hypixel's row already contains "[Lvl N]", so keep the original text from there
        // on instead of adding a second level prefix. Anything before it (e.g. "Pet: ")
        // is dropped because the HUD draws its own "Pet:" header.
        String full = original.getString();
        int lvlStart = full.indexOf("[Lvl");
        MutableComponent out = Component.literal(" ");
        Component icon = cachePetIcon(original, petName);
        if (icon != null) out.append(icon).append(Component.literal(" "));
        if (overflow > 0) {
            // Show the overflow level instead of Hypixel's capped one.
            out.append(Component.literal("[Lvl ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(overflow + "✦").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("] ").withStyle(ChatFormatting.GRAY));
            int nameAt = full.indexOf(petName, Math.max(0, lvlStart));
            if (nameAt >= 0) lvlStart = nameAt;
        } else if (lvlStart < 0) {
            out.append(Component.literal("[Lvl " + level + "] ").withStyle(ChatFormatting.GRAY));
            lvlStart = 0;
        }

        int nameStart = full.indexOf(petName, lvlStart);
        int nameEnd = nameStart < 0 ? -1 : nameStart + petName.length();
        final int from = lvlStart;
        final int[] offset = {0};
        original.visit((style, value) -> {
            if (value == null || value.isEmpty()) return Optional.empty();
            int segStart = offset[0];
            int segEnd = segStart + value.length();
            offset[0] = segEnd;
            int keepFrom = Math.max(from, segStart);
            if (keepFrom >= segEnd) return Optional.empty();

            for (int i = keepFrom; i < segEnd; ) {
                boolean inName = nameStart >= 0 && i >= nameStart && i < nameEnd;
                int boundary = inName ? Math.min(segEnd, nameEnd)
                    : (nameStart >= 0 && i < nameStart ? Math.min(segEnd, nameStart) : segEnd);
                Style partStyle = style;
                // Keep Hypixel's rarity colour; fall back to gold if the name has none.
                if (inName && partStyle.getColor() == null) partStyle = partStyle.withColor(ChatFormatting.GOLD);
                out.append(Component.literal(value.substring(i - segStart, boundary - segStart)).withStyle(partStyle));
                i = boundary;
            }
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static Component cachePetIcon(Component component, String petName) {
        Component icon = findPetIcon(component);
        String key = petName.toLowerCase(Locale.ROOT);
        if (icon != null) PET_ICON_CACHE.put(key, icon);
        return PET_ICON_CACHE.get(key);
    }

    private static Component findPetIcon(Component component) {
        if (component.getContents() instanceof ObjectContents contents && contents.contents() instanceof AtlasSprite) return component;
        for (Component sibling : component.getSiblings()) {
            Component icon = findPetIcon(sibling);
            if (icon != null) return icon;
        }
        return null;
    }

    /** The pet's name in its rarity colour, as the tab list shows it. */
    private static Component petNameComponent(Component row, String petName) {
        if (row != null) {
            final Component[] found = {null};
            row.visit((style, value) -> {
                if (found[0] == null && value.contains(petName)) found[0] = Component.literal(petName).withStyle(style);
                return Optional.empty();
            }, Style.EMPTY);
            if (found[0] != null) return found[0];
        }
        return Component.literal(petName).withStyle(ChatFormatting.GOLD);
    }

    private static Component findComponentText(Component component, String text) {
        final Component[] found = {null};
        component.visit((style, value) -> {
            if (value.equals(text)) {
                found[0] = Component.literal(value).withStyle(style);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    private static int rarityOffsetFromComponent(Component component, String name) {
        final int[] result = {20};
        component.visit((style, value) -> {
            if (!value.equals(name)) return Optional.empty();
            String color = styleColorName(style);
            result[0] = switch (color) {
                case "white" -> 0;
                case "green" -> 6;
                case "blue" -> 11;
                case "dark_purple" -> 15;
                default -> 20;
            };
            return Optional.empty();
        }, Style.EMPTY);
        return result[0];
    }

    private static String styleColorName(Style style) {
        try {
            // 26.x TextColor exposes its vanilla colour name directly. Keep
            // reflection as a fallback so this remains tolerant of mapping
            // changes between 26.1 and 26.2.
            if (style.getColor() == null) return "";
            int rgb = style.getColor().getValue();
            return switch (rgb) {
                case 0xFFFFFF -> "white";
                case 0x55FF55 -> "green";
                case 0x5555FF -> "blue";
                case 0xAA00AA -> "dark_purple";
                default -> "";
            };
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int rarityOffset(String tier) {
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "COMMON" -> 0;
            case "UNCOMMON" -> 6;
            case "RARE" -> 11;
            case "EPIC" -> 15;
            default -> 20;
        };
    }

    /** A pet's level from its petInfo exp and tier, past the normal maximum (overflow). */
    public static int petLevel(float xp, String tier) {
        return calcLevel(xp, rarityOffset(tier));
    }

    private static int getXpForLevel(int level, int offset) {
        int index = offset + Math.max(0, level);
        return index < PET_XP.length ? PET_XP[index] : 1886700;
    }

    private static int calculativeXpForLevel(int level, int offset) {
        int xp = 0;
        for (int i = 0; i < level; i++) xp += getXpForLevel(i, offset);
        return xp;
    }

    // NopoMod calculates overflow progress against the legendary curve after
    // adding the XP already accumulated by the lower-rarity pet.
    private static int calcLevel(float xp) {
        return calcLevel(xp, 20);
    }

    private static int calcLevel(float xp, int offset) {
        float remaining = xp;
        int level = 0;
        while (remaining > 0) {
            remaining -= getXpForLevel(level, offset);
            level++;
        }
        return Math.max(1, level);
    }

    private static float leftoverXp(float xp) {
        return leftoverXp(xp, 20);
    }

    private static float leftoverXp(float xp, int offset) {
        float remaining = xp;
        int level = 0;
        while (remaining > 0) {
            float needed = getXpForLevel(level, offset);
            if (remaining > needed) remaining -= needed;
            else return remaining;
            level++;
        }
        return 0;
    }

    private static void handleSlayer(Component message) {
        if (!isHypixel()) return;
        String text = clean(message.getString());
        Matcher start = SLAYER_START.matcher(text);
        if (start.matches()) {
            currentSlayer = start.group("slayer");
            SLAYERS.computeIfAbsent(currentSlayer, k -> new SlayerData()).kills++;
            slayerWorldChanged = false;
            saveJson();
            return;
        }

        Matcher drop = SLAYER_DROP.matcher(text);
        if (!drop.matches()) return;
        // Like NopoMod, wait a second before counting the drop: the drop line
        // arrives before the "Slayer LVL" line that counts the boss kill.
        PENDING_SLAYER_DROPS.add(new PendingDrop(message, drop.group("item").trim(), 20));
    }

    private static void tickSlayerDrops(Minecraft mc) {
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            slayerWorldChanged = true;
        }
        Iterator<PendingDrop> it = PENDING_SLAYER_DROPS.iterator();
        while (it.hasNext()) {
            PendingDrop pending = it.next();
            if (--pending.ticksLeft > 0) continue;
            it.remove();
            processSlayerDrop(mc, pending);
        }
    }

    private static void processSlayerDrop(Minecraft mc, PendingDrop pending) {
        String slayer = Utils.getLocation() == Location.THE_RIFT ? "Vampire" : currentSlayer;
        if (slayer == null) return;
        SlayerData data = SLAYERS.computeIfAbsent(slayer, k -> new SlayerData());
        Integer lastDropped = data.lastDropKills.get(pending.item);
        // After a world swap without a boss kill, only trust drops this boss
        // type is already known to give; otherwise it's probably a mob drop.
        if (slayerWorldChanged && lastDropped == null) return;

        // lastDropKills stores which kill number last gave the drop, so a
        // first-ever drop counts every boss since tracking began.
        int since = data.kills - (lastDropped == null ? 0 : lastDropped);
        data.lastDropKills.put(pending.item, data.kills);
        saveJson();

        if (mc.player == null) return;
        Component itemName = findComponentText(pending.message, pending.item);
        mc.player.sendSystemMessage(Component.literal("§6Sky2M §7| Took " + since
                + (since == 1 ? " boss" : " bosses") + " to drop ")
            .append(itemName != null ? itemName : Component.literal(pending.item)));
    }

    private static final class PendingDrop {
        final Component message;
        final String item;
        int ticksLeft;

        PendingDrop(Component message, String item, int ticksLeft) {
            this.message = message;
            this.item = item;
            this.ticksLeft = ticksLeft;
        }
    }

    private static void handleRareCrop(Component message) {
        if (!isHypixel()) return;
        String text = clean(message.getString());
        String crop = null;

        Matcher m = RARE_CROP.matcher(text);
        if (m.matches()) crop = m.group("crop").trim();
        else if ((m = PET_DROP.matcher(text)).matches()) crop = m.group("pet").trim();
        else if ((m = MOSQUITO.matcher(text)).matches()) crop = m.group("crop").trim();
        else if ((m = RAT.matcher(text)).matches()) crop = m.group("crop").trim();
        else if (CROP_FEVER.equals(text)) crop = "CROP FEVER";

        if (crop == null || crop.isBlank()) return;
        long now = System.currentTimeMillis();
        List<Long> times = CROP_TIMES.computeIfAbsent(crop, k -> new ArrayList<>());
        if (!times.isEmpty()) {
            long previous = times.get(times.size() - 1);
            long elapsed = now - previous;
            if (elapsed > 0) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal("§6Sky2M §7| Took "
                        + formatDuration(elapsed) + " to drop " + crop));
                }
            }
        }
        times.add(now);
        if (times.size() > 500) times.remove(0);
        saveJson();
    }

    private static String clean(String s) {
        return s.replaceAll("§.", "");
    }

    private static boolean isHypixel() {
        try {
            Minecraft mc = Minecraft.getInstance();
            // The sidebar is the reliable signal: the address can carry a port ("mc.hypixel.net:25565"),
            // be an alias (hypixel.io) or go through a proxy.
            if (com.epic60869.sky2m.features.core.Sky2MLocation.onSkyblock()) return true;
            if (mc.getCurrentServer() == null || mc.getCurrentServer().ip == null) return false;
            String ip = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            return ip.contains("hypixel");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void loadEmojis() {
        EMOJIS.clear();
        EMOJI_CANONICAL.clear();
        try (InputStream in = Sky2MNopoFeatures.class.getResourceAsStream("/assets/sky2m/emojis.json")) {
            if (in == null) return;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var element : root.getAsJsonArray("emojis")) {
                JsonObject emoji = element.getAsJsonObject();
                String name = emoji.get("name").getAsString();
                EMOJIS.add(name);
                EMOJI_CANONICAL.put(name, name);
                if (emoji.has("alternatives")) {
                    for (var alt : emoji.getAsJsonArray("alternatives")) {
                        String alias = alt.getAsString();
                        EMOJIS.add(alias);
                        EMOJI_CANONICAL.put(alias, name);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void loadBuiltInEmojiAliases() {
        String[][] aliases = {
            {"tada","🎉"},{"rocket","🚀"},{"100","💯"},{"broken_heart","💔"},
            {"orange_heart","🧡"},{"yellow_heart","💛"},{"green_heart","💚"},{"blue_heart","💙"},
            {"purple_heart","💜"},{"black_heart","🖤"},{"white_heart","🤍"},{"sparkles","✨"},
            {"star","⭐"},{"zap","⚡"},{"raised_hand","✋"},{"point_up","☝️"},{"muscle","💪"},
            {"smile","😄"},{"grin","😁"},{"joy","😂"},{"angry","😠"},{"rage","😡"},
            {"confused","😕"},{"neutral_face","😐"},{"sweat","😓"},{"scream","😱"},{"skull","💀"},
            {"poop","💩"},{"ghost","👻"},{"dog","🐶"},{"cat","🐱"},{"fox_face","🦊"},
            {"bee","🐝"},{"butterfly","🦋"},{"fish","🐟"},{"sunny","☀️"},{"cloud","☁️"},
            {"snowflake","❄️"},{"coffee","☕"},{"pizza","🍕"},{"hamburger","🍔"},{"cake","🍰"},
            {"gift","🎁"},{"moneybag","💰"},{"gem","💎"},{"warning","⚠️"},{"x","❌"},
            {"white_check_mark","✅"},{"question","❓"},{"exclamation","❗"},{"heavy_check_mark","✔️"}
        };
        for (String[] pair : aliases) {
            EMOJIS.add(pair[0]);
            EMOJI_CANONICAL.put(pair[0], pair[0]);
            EMOJI_UNICODE.put(pair[0], pair[1]);
        }
        for (var e : EMOJI_SYMBOLS.entrySet()) EMOJI_UNICODE.putIfAbsent(e.getKey(), e.getValue());
    }

    private static void addDefaultEmoji(String name, String symbol) {
        EMOJIS.add(name);
        EMOJI_SYMBOLS.put(name, symbol);
    }

    private static void loadDefaultEmojiSymbols() {
        addDefaultEmoji("wave", "👋");
        addDefaultEmoji("smile", "😄");
        addDefaultEmoji("grin", "😁");
        addDefaultEmoji("joy", "😂");
        addDefaultEmoji("heart", "❤️");
        addDefaultEmoji("fire", "🔥");
        addDefaultEmoji("sob", "😭");
        addDefaultEmoji("cry", "😢");
        addDefaultEmoji("angry", "😠");
        addDefaultEmoji("laughing", "😆");
        addDefaultEmoji("rofl", "🤣");
        addDefaultEmoji("wink", "😉");
        addDefaultEmoji("thinking", "🤔");
        addDefaultEmoji("eyes", "👀");
        addDefaultEmoji("thumbsup", "👍");
        addDefaultEmoji("+1", "👍");
        addDefaultEmoji("thumbsdown", "👎");
        addDefaultEmoji("-1", "👎");
        addDefaultEmoji("clap", "👏");
        addDefaultEmoji("pray", "🙏");
        addDefaultEmoji("ok_hand", "👌");
        addDefaultEmoji("sunglasses", "😎");
    }

    public static Component replaceChatEmojis(Component message) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.chat.chatEmoji) return message;

        final MutableComponent result = Component.empty();
        message.visit((style, value) -> {
            if (value == null || value.isEmpty()) return Optional.empty();

            MutableComponent out = Component.empty();
            Matcher matcher = EMOJI_PATTERN.matcher(value);
            int cursor = 0;
            while (matcher.find()) {
                if (matcher.start() > cursor) {
                    out.append(Component.literal(value.substring(cursor, matcher.start())).withStyle(style));
                }
                String name = matcher.group(1);
                if (EMOJIS.contains(name)) {
                    String canonical = EMOJI_CANONICAL.getOrDefault(name, name);
                    // Nopo's implementation uses the vanilla GUI atlas. Sky2M
                    // ships the same sprites under its own namespace, so aliases
                    // resolve to the canonical sprite without a Nopo dependency.
                    // Use Nopo's actual atlas-sprite approach rather than Unicode.
                    // Minecraft's normal font does not reliably contain the emoji glyphs.
                    // The build downloads the pinned Nopo sprite sheet into Sky2M's namespace.
                    try {
                        Identifier spriteId = Identifier.fromNamespaceAndPath("sky2m", canonical);
                        MutableComponent emoji = Component.object(new AtlasSprite(
                                Identifier.withDefaultNamespace("gui"), spriteId));
                        out.append(emoji.withStyle(style.withColor(ChatFormatting.WHITE)));
                    } catch (Throwable ignored) {
                        String unicode = EMOJI_UNICODE.get(canonical);
                        if (unicode == null) unicode = EMOJI_SYMBOLS.get(canonical);
                        if (unicode == null) unicode = "❔";
                        out.append(Component.literal(unicode).withStyle(style.withColor(ChatFormatting.WHITE)));
                    }
                } else {
                    out.append(Component.literal(matcher.group()).withStyle(style));
                }
                cursor = matcher.end();
            }
            if (cursor < value.length()) {
                out.append(Component.literal(value.substring(cursor)).withStyle(style));
            }
            result.append(out);
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    public static boolean chatEmojisEnabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.chat.chatEmoji;
    }

    public static List<String> getChatEmojiSuggestions() {
        if (!chatEmojisEnabled()) return List.of();
        return new ArrayList<>(EMOJIS.stream().map(name -> ":" + name + ":").sorted().toList());
    }

    /** Every Nopo emoji name (without colons). */
    public static java.util.Set<String> chatEmojiNames() {
        return java.util.Collections.unmodifiableSet(EMOJIS);
    }

    /** The Nopo emoji's sprite in the gui atlas, or null if it isn't one. */
    public static Identifier chatEmojiSprite(String name) {
        if (!chatEmojisEnabled() || !EMOJIS.contains(name)) return null;
        return Identifier.fromNamespaceAndPath("sky2m", EMOJI_CANONICAL.getOrDefault(name, name));
    }

    public static boolean isChatEmoji(String value) {
        if (value == null) return false;
        String clean = value;
        if (clean.startsWith(":")) clean = clean.substring(1);
        if (clean.endsWith(":")) clean = clean.substring(0, clean.length() - 1);
        return chatEmojisEnabled() && EMOJIS.contains(clean);
    }

    private static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private static void loadJson() {
        SLAYERS.clear();
        CROP_TIMES.clear();
        readJson(SLAYER_FILE, JsonObject.class, root -> {
            if (root.has("slayers")) {
                for (var e : root.getAsJsonObject("slayers").entrySet()) {
                    JsonObject v = e.getValue().getAsJsonObject();
                    SlayerData data = new SlayerData();
                    data.kills = v.has("kills") ? v.get("kills").getAsInt() : 0;
                    if (v.has("lastDropKills")) {
                        for (var d : v.getAsJsonObject("lastDropKills").entrySet())
                            data.lastDropKills.put(d.getKey(), d.getValue().getAsInt());
                    }
                    SLAYERS.put(e.getKey(), data);
                }
            }
        });
        readJson(CROP_FILE, JsonObject.class, root -> {
            if (root.has("drops")) {
                for (var e : root.getAsJsonObject("drops").entrySet()) {
                    List<Long> list = new ArrayList<>();
                    for (var v : e.getValue().getAsJsonArray()) list.add(v.getAsLong());
                    CROP_TIMES.put(e.getKey(), list);
                }
            }
        });
    }

    private interface RootConsumer { void accept(JsonObject root); }

    private static <T> void readJson(String fileName, Class<T> ignored, RootConsumer consumer) {
        if (configDir == null) return;
        Path file = configDir.resolve(fileName);
        if (!Files.exists(file)) return;
        try {
            consumer.accept(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception ignoredEx) {
        }
    }

    private static void saveJson() {
        if (configDir == null) return;
        try {
            Files.createDirectories(configDir);

            JsonObject slayerRoot = new JsonObject();
            JsonObject slayers = new JsonObject();
            for (var entry : SLAYERS.entrySet()) {
                JsonObject data = new JsonObject();
                data.addProperty("kills", entry.getValue().kills);
                JsonObject drops = new JsonObject();
                entry.getValue().lastDropKills.forEach(drops::addProperty);
                data.add("lastDropKills", drops);
                slayers.add(entry.getKey(), data);
            }
            slayerRoot.add("slayers", slayers);
            Files.writeString(configDir.resolve(SLAYER_FILE), GSON.toJson(slayerRoot), StandardCharsets.UTF_8);

            JsonObject cropRoot = new JsonObject();
            JsonObject crops = new JsonObject();
            CROP_TIMES.forEach((key, values) -> {
                var array = new com.google.gson.JsonArray();
                for (Long value : values) array.add(value);
                crops.add(key, array);
            });
            cropRoot.add("drops", crops);
            Files.writeString(configDir.resolve(CROP_FILE), GSON.toJson(cropRoot), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static final class SlayerData {
        int kills;
        final Map<String, Integer> lastDropKills = new LinkedHashMap<>();
    }
}
