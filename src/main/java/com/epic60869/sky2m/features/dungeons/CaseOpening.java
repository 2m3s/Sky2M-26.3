package com.epic60869.sky2m.features.dungeons;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.items.Sky2MItemBackgrounds;
import com.epic60869.sky2m.items.Sky2MItemRarity;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * CS2-style case opening for dungeon reward chests, ported from SkyOcean's Dungeon Gambling: opening an Obsidian or
 * Bedrock chest (or any chest, if turned on) spins a strip of item cards past a red marker, slowing down and stopping
 * on the chest's most valuable item, whose name then grows onto the screen. The menu can't be clicked while it
 * spins; Esc skips it. It also works at Croesus (like SkyOcean's Croesus gambling): the floor comes from the menu's
 * "To Catacombs - Floor VII" back arrow, and the chests' contents are hidden in the run's menu so the spin isn't
 * spoiled. When the winner is Legendary (gold) or better, the "GOLD GOLD GOLD" sound plays as soon as the spin starts
 * (assets/sky2m/sounds/gold.ogg; a resource pack can replace it).
 */
public final class CaseOpening {
    private static final Pattern CHEST = Pattern.compile("^(?<type>Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$");
    /** Croesus's back arrow: "To Catacombs - Floor VII" or "To Master Catacombs - Floor VII". */
    private static final Pattern CROESUS_BACK = Pattern.compile("^To (?<master>Master )?Catacombs - Floor (?<floor>[IV]+)$");
    /** Croesus's menu for one run, which lists its chests. */
    private static final Pattern CROESUS_RUN = Pattern.compile("^(?:Master )?Catacombs - Floor [IV]+$");
    private static final Pattern CHEST_HEAD = Pattern.compile("^(?<type>Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?");
    private static final Identifier GOLD_SOUND = Identifier.fromNamespaceAndPath("sky2m", "gold");
    private static final SoundEvent GOLD_SOUND_EVENT = Registry.register(BuiltInRegistries.SOUND_EVENT, GOLD_SOUND,
        SoundEvent.createVariableRangeEvent(GOLD_SOUND));

    private static final int ITEM_SCALE = 4;
    private static final int ITEMS = 50;
    private static final int WINNER_INDEX = ITEMS - 10;
    private static final int GAP = 5;
    private static final int CARD_W = 24;
    private static final int CARD_H = 18;
    private static final int FULL_W = CARD_W * ITEM_SCALE + GAP;
    private static final int FULL_H = CARD_H * ITEM_SCALE;

    private static final List<ItemStack> reel = new ArrayList<>();
    private static long start = -1;
    private static int randomOffset;
    private static int lastSound;
    private static int menuId = -1;

    private CaseOpening() {}

    private static FeatureConfigs.CaseOpening config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.caseOpeningMenu;
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenEvents.afterExtract(screen).register((s, g, mouseX, mouseY, delta) -> {
                maybeStart(container);
                if (running(container)) render(g);
            });
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !running(container));
            ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !running(container));
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!running(container)) return true;
                if (event.key() == InputConstants.KEY_ESCAPE) stop(); // skip the animation, then Esc works as normal
                return false;
            });
            ScreenEvents.remove(screen).register(s -> {
                stop();
                menuId = -1;
            });
        });
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> hideCroesusContents(stack, lines));
    }

    /** Whether this chest type spins with the current settings. */
    private static boolean spins(FeatureConfigs.CaseOpening c, String type) {
        return c.allChests || type.equals("Obsidian") || type.equals("Bedrock");
    }

    /** In a Croesus run's menu, replaces the contents in the tooltip of a chest that will spin, so you find out when it opens. */
    private static void hideCroesusContents(ItemStack stack, List<Component> lines) {
        FeatureConfigs.CaseOpening c = config();
        if (c == null || !c.enabled || !c.croesus || !c.hideCroesusContents || !Compat.isOnSkyblock() || !stack.is(Items.PLAYER_HEAD)) return;
        if (!(Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> screen)
            || !CROESUS_RUN.matcher(ChatFormatting.stripFormatting(screen.getTitle().getString()).trim()).matches()) return;
        var m = CHEST_HEAD.matcher(ChatFormatting.stripFormatting(stack.getHoverName().getString()).trim());
        if (!m.find() || !spins(c, m.group("type"))) return;
        int contents = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (ChatFormatting.stripFormatting(lines.get(i).getString()).trim().equals("Contents")) {
                contents = i;
                break;
            }
        }
        if (contents < 0) return;
        // The contents run until the blank line before the cost.
        int end = contents + 1;
        while (end < lines.size() && !ChatFormatting.stripFormatting(lines.get(end).getString()).isBlank()) end++;
        lines.subList(contents + 1, end).clear();
        lines.add(contents + 1, Component.literal("Hidden until you open it (Case Opening)").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
    }

    /** The floor ("F7", "M5") when this chest menu is Croesus's, read from its back arrow; null for any other menu. */
    private static String croesusFloor(AbstractContainerScreen<?> screen) {
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory || !slot.getItem().is(Items.ARROW)) continue;
            ItemLore lore = slot.getItem().get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                var m = CROESUS_BACK.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
                if (m.matches()) return (m.group("master") != null ? "M" : "F") + roman(m.group("floor"));
            }
        }
        return null;
    }

    private static int roman(String numeral) {
        int total = 0;
        for (int i = 0; i < numeral.length(); i++) {
            int value = numeral.charAt(i) == 'V' ? 5 : 1;
            boolean subtract = value == 1 && i + 1 < numeral.length() && numeral.charAt(i + 1) == 'V';
            total += subtract ? -1 : value;
        }
        return total;
    }

    /** Menu buttons (Open Reward Chest, Kismet reroll, ...) say what clicking does; the loot doesn't. */
    private static boolean isButton(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component line : lore.lines()) {
            if (ChatFormatting.stripFormatting(line.getString()).trim().startsWith("Click to")) return true;
        }
        return false;
    }

    /** While the case spins, the chest menu under it (background, items, tooltips) isn't drawn (see the Storage Overlay mixins). */
    public static boolean hidesMenu(AbstractContainerScreen<?> screen) {
        return running(screen);
    }

    private static boolean running(AbstractContainerScreen<?> screen) {
        return start > 0 && screen.getMenu().containerId == menuId && !reel.isEmpty();
    }

    private static void stop() {
        start = -1;
        reel.clear();
    }

    /** Starts once per chest, as soon as its items have arrived. */
    private static void maybeStart(AbstractContainerScreen<?> screen) {
        FeatureConfigs.CaseOpening c = config();
        if (c == null || !c.enabled || !Compat.isOnSkyblock()) return;
        int id = screen.getMenu().containerId;
        if (id == menuId) return;
        var m = CHEST.matcher(ChatFormatting.stripFormatting(screen.getTitle().getString()).trim());
        if (!m.matches()) return;
        if (!spins(c, m.group("type"))) return;
        String croesus = croesusFloor(screen);
        if (croesus != null && !c.croesus) return;
        String floor = croesus != null ? croesus : com.epic60869.sky2m.features.core.Sky2MLocation.dungeonFloor();

        List<ItemStack> loot = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()
                || stack.is(Items.BARRIER) || stack.is(Items.ARROW) || stack.is(Items.CHEST) || isButton(stack)) continue;
            // Skip the glass pane filler, keep everything else (essence has no SkyBlock id but is loot).
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("glass_pane")) continue;
            if (ChatFormatting.stripFormatting(stack.getHoverName().getString()).isBlank()) continue;
            loot.add(stack);
        }
        if (loot.isEmpty()) return; // items not here yet; try again next frame
        menuId = id;

        // The winner is the most valuable item in the chest, like SkyOcean.
        ItemStack winner = loot.getFirst();
        double best = -1;
        for (ItemStack stack : loot) {
            double value = Sky2MPriceTooltip.unitPrice(Sky2MPriceTooltip.marketId(stack)) * stack.getCount();
            if (value > best) {
                best = value;
                winner = stack;
            }
        }
        reel.clear();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Like SkyOcean, the reel shows what the floor can drop, mixed with what's really in the chest.
        List<ItemStack> pool = floorPool(floor);
        for (int i = 0; i < ITEMS; i++) {
            boolean fromFloor = !pool.isEmpty() && random.nextInt(3) != 0;
            reel.add(fromFloor ? pool.get(random.nextInt(pool.size())) : loot.get(random.nextInt(loot.size())));
        }
        reel.set(WINNER_INDEX, winner);
        randomOffset = ((4 * ITEM_SCALE) + random.nextInt(4 * ITEM_SCALE)) * (random.nextBoolean() ? 1 : -1);
        lastSound = 0;
        start = System.currentTimeMillis();
        // A gold chest plays "GOLD GOLD GOLD" as soon as it starts opening, over the whole spin.
        if (isGold(winner) && c.goldSound) playGold();
    }

    /** Well-known drops of each floor's reward chests (NEU item ids), used to fill the reel. */
    private static final java.util.Map<Integer, List<String>> FLOOR_DROPS = java.util.Map.of(
        1, List.of("BONZO_STAFF", "BONZO_MASK", "RED_NOSE"),
        2, List.of("SCARF_STUDIES", "SCARF_THESIS", "SCARF_GRIMOIRE", "RED_SCARF"),
        3, List.of("ADAPTIVE_HELMET", "ADAPTIVE_CHESTPLATE"),
        4, List.of("ITEM_SPIRIT_BOW", "SPIRIT_BONE", "SPIRIT_WING", "THORNS_BOOTS"),
        5, List.of("SHADOW_ASSASSIN_CHESTPLATE", "SHADOW_ASSASSIN_HELMET", "SHADOW_FURY", "LAST_BREATH", "LIVID_DAGGER"),
        6, List.of("GIANTS_SWORD", "PRECURSOR_EYE", "NECROMANCER_LORD_CHESTPLATE", "SUMMONING_RING", "FEL_SKULL", "NECROMANCER_SWORD", "GIANT_TOOTH", "SADAN_BROOCH"),
        7, List.of("NECRON_HANDLE", "IMPLOSION_SCROLL", "SHADOW_WARP_SCROLL", "WITHER_SHIELD_SCROLL", "AUTO_RECOMBOBULATOR",
            "POWER_WITHER_CHESTPLATE", "POWER_WITHER_HELMET", "POWER_WITHER_LEGGINGS", "POWER_WITHER_BOOTS", "WITHER_BLOOD",
            "WITHER_CLOAK", "PRECURSOR_GEAR", "DARK_CLAYMORE"));
    /** Drops every floor's chests can have. */
    private static final List<String> COMMON_DROPS = List.of("RECOMBOBULATOR_3000", "FUMING_POTATO_BOOK", "HOT_POTATO_BOOK");
    /** Master Mode chests can also have a master star. */
    private static final List<String> MASTER_DROPS = List.of("FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR",
        "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR", "MASTER_SKULL_TIER_1");

    /** Item icons for the floor's drops (unknown ids are left out). */
    private static List<ItemStack> floorPool(String floor) {
        List<String> ids = new ArrayList<>(COMMON_DROPS);
        int number = floor.length() == 2 && Character.isDigit(floor.charAt(1)) ? floor.charAt(1) - '0' : -1;
        if (number > 0) ids.addAll(FLOOR_DROPS.getOrDefault(number, List.of()));
        else FLOOR_DROPS.values().forEach(ids::addAll); // floor unknown: any floor's drops
        if (floor.startsWith("M")) ids.addAll(MASTER_DROPS);
        List<ItemStack> out = new ArrayList<>();
        for (String id : ids) {
            ItemStack stack = com.epic60869.sky2m.custom.RepoItems.itemStack(id);
            if (!stack.isEmpty() && !stack.is(Items.BARRIER)) out.add(stack);
        }
        return out;
    }

    private static float ease(float t) {
        return t < 0.5f ? (float) ((1 - Math.sqrt(1 - Math.pow(2 * t, 2))) / 2)
            : (float) ((Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) / 2);
    }

    private static void render(GuiGraphicsExtractor g) {
        FeatureConfigs.CaseOpening c = config();
        float seconds = c == null ? 6 : c.seconds;
        int w = g.guiWidth();
        int h = g.guiHeight();
        // Cover the screen while it spins (the chest itself isn't drawn, see hidesMenu).
        g.fill(0, 0, w, h, 0xFF101010);

        float raw = (System.currentTimeMillis() - start) / (seconds * 1000f);
        float progress = Mth.clamp(raw + 0.25f, 0f, 1f);
        float endOffset = (WINNER_INDEX * FULL_W) * ease(progress);
        if (progress >= 0.96f && raw >= 1.4f) {
            stop();
            return;
        }

        int soundIndex = (int) endOffset / FULL_W;
        Minecraft mc = Minecraft.getInstance();
        if (soundIndex > lastSound) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 2f, 1f));
            lastSound = soundIndex;
        }

        g.pose().pushMatrix();
        g.pose().translate(w / 2f - endOffset - (8 * ITEM_SCALE + randomOffset), (h - FULL_H) / 2f);
        for (int i = 0; i < reel.size(); i++) {
            ItemStack item = reel.get(i);
            g.pose().pushMatrix();
            g.pose().translate(i * FULL_W, 0);
            g.pose().scale(ITEM_SCALE, ITEM_SCALE);
            card(g, item, ARGB.opaque(rarityColour(item)));
            g.pose().popMatrix();
        }
        g.pose().popMatrix();

        // The red marker in the middle.
        g.fill(w / 2 - 1, (h - FULL_H) / 2 - 6, w / 2 + 1, (h + FULL_H) / 2 + FULL_H / 4, 0xFFFF5555);

        if (progress >= 0.96f) {
            ItemStack winner = reel.get(WINNER_INDEX);
            Component name = Compat.realName(winner);
            float scale = Mth.lerp(Math.min(1f, (progress - 0.96f) / 0.04f), 1f, 3f);
            g.pose().pushMatrix();
            g.pose().translate(w / 2f, h * 0.2f);
            g.pose().scale(scale, scale);
            g.pose().translate(-mc.font.width(name) / 2f, 0);
            g.text(mc.font, name, 0, 0, 0xFFFFFFFF, true);
            g.pose().popMatrix();
        }
    }

    /** SkyOcean's card: a dark box fading into the rarity colour, a rarity line at the bottom, and the item. */
    private static void card(GuiGraphicsExtractor g, ItemStack item, int colour) {
        int tint = ARGB.color(0x60, colour);
        g.fillGradient(0, 0, CARD_W, CARD_H - 1, 0x80303030, tint);
        g.fill(0, CARD_H - 1, CARD_W, CARD_H, colour);
        g.item(item, CARD_W / 2 - 8, CARD_H / 2 - 8);
    }

    private static int rarityColour(ItemStack stack) {
        Sky2MItemRarity rarity = Sky2MItemBackgrounds.rarity(stack);
        return rarity == null || rarity == Sky2MItemRarity.UNKNOWN ? 0xFFFFFF : rarity.color;
    }

    /** "Gold" like a CS2 knife: Legendary or better. */
    private static boolean isGold(ItemStack stack) {
        Sky2MItemRarity rarity = Sky2MItemBackgrounds.rarity(stack);
        return rarity != null && rarity != Sky2MItemRarity.UNKNOWN && rarity.ordinal() >= Sky2MItemRarity.LEGENDARY.ordinal();
    }

    private static void playGold() {
        Minecraft mc = Minecraft.getInstance();
        boolean custom = mc.getResourceManager().getResource(Identifier.fromNamespaceAndPath("sky2m", "sounds/gold.ogg")).isPresent();
        SoundEvent sound = custom ? GOLD_SOUND_EVENT : SoundEvents.UI_TOAST_CHALLENGE_COMPLETE;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, 1f, 1f));
    }
}
