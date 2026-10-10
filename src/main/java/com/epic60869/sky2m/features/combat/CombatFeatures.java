package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.ItemPriceResolver;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Arrow counter, legion display, cocoon alert and rare drop copy/animation. */
public final class CombatFeatures {
    // Quiver patterns from SkyHanni's repo (MIT).
    private static final Pattern ACTIVE_ARROW = Pattern.compile("Active Arrow: (?<type>.*) \\((?<amount>[\\d,]+)\\)");
    private static final Pattern ARROWS_REMAINING = Pattern.compile("Arrows Remaining: (?<amount>[\\d,]+)");
    private static final Pattern ARROW_ADDED = Pattern.compile("You've added (?<type>.*) x(?<amount>[\\d,]+) to your quiver!");
    private static final Pattern ARROW_SELECT = Pattern.compile("You set your selected arrow type to (?<arrow>.*)!");
    private static final Pattern ARROW_RAN_OUT = Pattern.compile("QUIVER! You have run out of (?<type>.*)s!");
    /** Any name, apostrophes included ("Arachne's Keeper"), with or without "a"/"an"/"the" before it. */
    private static final Pattern COCOON = Pattern.compile("CAUGHT! You cocooned (?:an? |the )?(?<name>.+?)!");
    private static final Pattern COCOON_BOSS = Pattern.compile("\\s*YOU COCOONED YOUR SLAYER BOSS");
    /**
     * Every rare drop line: RARE / VERY RARE / CRAZY RARE / INSANE / PRAY TO RNGESUS / PET / RNG METER drops, the
     * Garden's RARE CROP lines, RARE REWARD and OUTSTANDING CATCH, with or without spaces before them.
     */
    private static final Pattern RARE_DROP = Pattern.compile(
        "^\\s*(?<type>(?:(?:VERY |CRAZY |INSANE |INCREDIBLY )?RARE|PRAY TO RNGESUS|INSANE|PET|RNG METER|LEGENDARY) (?:DROP|CROP|REWARD)!|OUTSTANDING CATCH!)"
            // The "(+241% ✯ Magic Find)" or "(+100 ☘ Farming Fortune)" after the item isn't part of its name.
            + "\\s+\\(?(?<item>.+?)\\)?(?:\\s+x(?<amount>[\\d,]+))?(?:\\s+\\(\\+[\\d,.]+%? ?[^)]*\\))?\\s*$");
    /** "You dug out a Minos Relic!", "You found a Recombobulator 3000!": just the item. */
    private static final Pattern DROP_WORDS = Pattern.compile("^(?:You (?:dug out|found|caught|got)(?: an?)? )?(?<item>.+?)!?$");
    private static final double LEGION_RANGE = 30;

    private static String arrowType;
    private static long arrowAmount = -1;
    private static int legionCount;
    private static int ticks;

    private CombatFeatures() {}

    private static FeatureConfigs.Combat config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.combat;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 10 == 0) tick(mc);
        });
        Sky2MChat.onChat(CombatFeatures::onChat);

        com.epic60869.sky2m.features.core.Sky2MHuds.setting("arrows", () -> config() != null && config().arrowCounter);
        Sky2MHuds.register("arrows", "Arrow Counter",
            () -> config() != null && config().arrowCounter && Sky2MLocation.onSkyblock() && (!config().arrowCounterBowOnly || holdingBow()),
            CombatFeatures::arrowLines,
            List.of(line("Arrows: ", "Flint Arrow ", ChatFormatting.WHITE).append(Component.literal("x1,234").withStyle(ChatFormatting.GREEN))),
            8, 120);
        com.epic60869.sky2m.features.core.Sky2MHuds.setting("legion", () -> config() != null && config().legionDisplay);
        Sky2MHuds.register("legion", "Legion Display",
            () -> config() != null && config().legionDisplay && Sky2MLocation.onSkyblock(),
            () -> List.of(line("Legion: ", legionCount + (legionCount == 1 ? " player" : " players"), ChatFormatting.AQUA)),
            List.of(line("Legion: ", "4 players", ChatFormatting.AQUA)),
            8, 134);
    }

    private static Component header(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static net.minecraft.network.chat.MutableComponent line(String label, String value, ChatFormatting color) {
        return Component.literal(label).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(color));
    }

    /** A bow or crossbow in your main hand (SkyBlock bows, shortbows included, are vanilla bows underneath). */
    private static boolean holdingBow() {
        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        ItemStack held = player.getMainHandItem();
        return held.getItem() instanceof net.minecraft.world.item.BowItem || held.getItem() instanceof net.minecraft.world.item.CrossbowItem;
    }

    private static List<Component> arrowLines() {
        if (arrowType == null) return List.of();
        var value = Component.literal(arrowType + " ").withStyle(ChatFormatting.WHITE);
        if (arrowAmount >= 0) {
            value.append(Component.literal("x" + String.format(Locale.US, "%,d", arrowAmount))
                .withStyle(arrowAmount < 128 ? ChatFormatting.RED : ChatFormatting.GREEN));
        }
        return List.of(header("Arrows: ").copy().append(value));
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;

        // The quiver's active arrow is shown in two ways (SkyHanni's QuiverApi): an "Active Arrow: X (N)" lore
        // line, or a preview arrow item named after the arrow with "Arrows Remaining: N" in its lore.
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (readArrowItem(mc.player.getInventory().getItem(i))) break;
        }
        // While the Quiver menu is open, count the arrows in it.
        if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen
            && screen.getTitle().getString().contains("Quiver") && arrowType != null) {
            long total = 0;
            var slots = screen.getMenu().slots;
            for (var slot : slots) {
                if (slot.container == mc.player.getInventory()) continue;
                ItemStack stack = slot.getItem();
                if (!stack.isEmpty() && Sky2MLocation.strip(com.epic60869.sky2m.custom.util.Compat.realName(stack).getString()).trim().equals(arrowType)) total += stack.getCount();
            }
            if (total > 0) arrowAmount = total;
        }

        FeatureConfigs.Combat config = config();
        if (config != null && config.legionDisplay) {
            int count = 0;
            for (Player player : mc.level.players()) {
                if (player == mc.player || player.getUUID().version() != 4) continue; // NPCs use v2 UUIDs
                if (player.distanceTo(mc.player) <= LEGION_RANGE) count++;
            }
            legionCount = count;
        }
    }

    private static boolean readArrowItem(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return false;
        for (Component loreLine : lore.lines()) {
            String line = Sky2MLocation.strip(loreLine.getString());
            Matcher m = ACTIVE_ARROW.matcher(line);
            if (m.find()) {
                arrowType = m.group("type").trim();
                arrowAmount = Long.parseLong(m.group("amount").replace(",", ""));
                return true;
            }
            m = ARROWS_REMAINING.matcher(line);
            if (m.find()) {
                arrowType = Sky2MLocation.strip(com.epic60869.sky2m.custom.util.Compat.realName(stack).getString()).trim();
                arrowAmount = Long.parseLong(m.group("amount").replace(",", ""));
                return true;
            }
        }
        return false;
    }

    private static void onChat(Sky2MChat.Message message) {
        FeatureConfigs.Combat config = config();
        if (config == null) return;
        String text = message.text();

        Matcher m = ARROW_SELECT.matcher(text);
        if (m.find()) {
            arrowType = m.group("arrow").trim();
            arrowAmount = -1;
        } else if ((m = ARROW_RAN_OUT.matcher(text)).find()) {
            arrowType = m.group("type").trim();
            arrowAmount = 0;
        } else if ((m = ARROW_ADDED.matcher(text)).find()) {
            if (arrowType == null) arrowType = m.group("type").trim();
            if (arrowType.equals(m.group("type").trim()) && arrowAmount >= 0) arrowAmount += Long.parseLong(m.group("amount").replace(",", ""));
        } else if (text.contains("Cleared your quiver!") || text.contains("Your quiver is now completely empty!")) {
            arrowAmount = 0;
        }

        if (config.cocoonAlert.enabled) {
            String mob = null;
            if ((m = COCOON.matcher(text)).find()) mob = m.group("name").trim();
            else if (COCOON_BOSS.matcher(text).matches()) mob = "Slayer Boss";
            if (mob != null && (mob.equals("Slayer Boss") || important(mob))) {
                Sky2MAlerts.title(Component.literal("COCOONED!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
                    Component.literal(mob).withStyle(ChatFormatting.WHITE));
            }
        }

        if ((m = RARE_DROP.matcher(text)).find()) {
            Matcher words = DROP_WORDS.matcher(m.group("item").trim());
            String item = words.matches() ? words.group("item").trim() : m.group("item").trim();
            int amount = m.group("amount") == null ? 1 : Integer.parseInt(m.group("amount").replace(",", ""));
            // "2x Item" as well as "Item x2".
            Matcher prefixed = AMOUNT_FIRST.matcher(item);
            if (prefixed.matches()) {
                amount = Integer.parseInt(prefixed.group("amount"));
                item = prefixed.group("item").trim();
            }
            String type = m.group("type").trim();
            boolean rng = RareDropTotem.isRngDrop(type, item);
            boolean slayer = com.epic60869.sky2m.features.slayer.SlayerBossProfit.isSlayerDrop(item);
            // RARE CROP lines, and anything else that drops in the Garden (pest drops).
            boolean garden = type.contains("CROP") || Sky2MLocation.inGarden();
            // A Chimera's animations wait for the Inquisitor Gamble's shot.
            InquisitorGamble.checkLine(text.trim());
            String dropText = text.trim(), dropItem = item, dropType = m.group("type").trim();
            int dropAmount = amount;
            InquisitorGamble.afterReveal(() -> {
                onRareDrop(config.rareDrops, dropText, dropItem, dropAmount, rng, slayer, garden);
                RareDropTotem.onDrop(dropType, dropItem);
            });
        }
    }

    private static final Pattern AMOUNT_FIRST = Pattern.compile("^(?<amount>\\d+)\\s*x\\s+(?<item>.+)$", Pattern.CASE_INSENSITIVE);

    /** Mobs worth a cocoon alert: slayer bosses and minibosses, elusive mobs and important bosses. */
    private static final List<String> IMPORTANT_MOBS = List.of(
        // Slayer bosses
        "Revenant Horror", "Atoned Horror", "Tarantula Broodfather", "Conjoined Brood", "Sven Packmaster",
        "Voidgloom Seraph", "Inferno Demonlord", "Riftstalker Bloodfiend",
        // Slayer minibosses
        "Revenant Sycophant", "Revenant Champion", "Deformed Revenant", "Atoned Champion", "Atoned Revenant",
        "Tarantula Vermin", "Tarantula Beast", "Mutant Tarantula", "Primordial Jockey", "Primordial Viscount",
        "Pack Enforcer", "Sven Follower", "Sven Alpha",
        "Voidling Devotee", "Voidling Radical", "Voidcrazed Maniac",
        "Flare Demon", "Kindleheart Demon", "Burningsoul Demon",
        // Elusive and rare mobs
        "Special Zealot", "Voidling Extremist", "Soul of the Alpha", "Old Wolf", "Arachne's Keeper", "Scatha",
        "Golden Goblin", "Diamond Goblin", "Star Sentry", "Mimic", "Corleone",
        // Diana
        "Minos Inquisitor", "Minos Champion", "King Minos", "Manticore", "Sphinx", "Vanquisher",
        "Thunder", "Lord Jawbus", "Water Hydra", "Sea Emperor", "The Loch Emperor", "Great White Shark", "Phantom Fisher",
        "Grim Reaper", "Yeti", "Reindrake", "Plhlegblast", "Ragnarok", "Wiki Tiki", "Titanoboa", "Abyssal Miner",
        "Fire Mage", "Elusive",
        // Important bosses
        "Magma Boss", "Arachne", "Bladesoul", "Mage Outlaw", "Barbarian Duke", "Ashfang", "Endstone Protector",
        "Dragon", "Bal", "Kuudra", "Leech Supreme", "Bacte", "Headless Horseman", "Dreadlord");

    private static boolean important(String mob) {
        String lower = mob.toLowerCase(Locale.ROOT);
        for (String name : IMPORTANT_MOBS) {
            String n = name.toLowerCase(Locale.ROOT);
            // Short names must match a whole word so "Bal" does not match "Ball".
            if (n.length() <= 5 ? (" " + lower + " ").contains(" " + n + " ") : lower.contains(n)) return true;
        }
        return false;
    }

    private static void onRareDrop(FeatureConfigs.RareDrops config, String text, String item, int amount,
                                   boolean rng, boolean slayer, boolean garden) {
        Minecraft mc = Minecraft.getInstance();
        if (config.copy || (config.copyRng && rng) || (config.copySlayer && slayer) || (config.copyGarden && garden)) {
            mc.execute(() -> {
                mc.keyboardHandler.setClipboard(text);
                com.epic60869.sky2m.features.misc.CopyChat.showPreview("Drop copied", text);
            });
        }
        if (config.animation) {
            int count = Math.max(1, amount);
            ItemPriceResolver.valueByNameAsync(item).thenAccept(value -> {
                if (value != null && value * count >= config.thresholdMillions * 1_000_000d) {
                    Sky2MAlerts.dropAnimation(Component.literal(item + "!").withStyle(ChatFormatting.BOLD), 0xFFAA00);
                    Sky2MAlerts.chat(Component.literal(item + " is worth " + formatCoins(value * count) + " coins!").withStyle(ChatFormatting.GOLD));
                }
            });
        }
    }

    public static String formatCoins(double value) {
        if (value >= 1_000_000_000) return String.format(Locale.US, "%.2fB", value / 1_000_000_000);
        if (value >= 1_000_000) return String.format(Locale.US, "%.2fM", value / 1_000_000);
        if (value >= 1_000) return String.format(Locale.US, "%.1fk", value / 1_000);
        return String.format(Locale.US, "%.0f", value);
    }

    static List<Component> list(Component... lines) {
        return new ArrayList<>(Arrays.asList(lines));
    }
}
