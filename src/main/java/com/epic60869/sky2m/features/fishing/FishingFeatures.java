package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MPriceTooltip;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.EntityGlow;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcItems;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fishing, ported from Feesh (https://github.com/Sleepy-Panda/Feesh, Apache-2.0): alerts when you or your party catch
 * or cocoon a chosen sea creature (features/alerts/RareCatchAlert.kt), party messages for your catches
 * (features/chat/RareCatchMessage.kt), compact catch messages with rarity gradients (features/chat/CompactCatchMessages.kt),
 * fishing boss death alerts (PlayerDeathAlert.kt, PlayerDeathMessage.kt), rare drop alerts with their price and party
 * messages (RareDropAlert.kt, RareDropMessage.kt), trophy discoveries, deployables running out
 * (overlays/DeployablesTimer.kt), maxed pets and their price, hotspots and wormholes closing, hotspot sharing
 * (chat/HotspotFoundMessage.kt), the Fishing Festival's shark count and personal best, "Lootshare!" in party chat and
 * sea creature highlights that never show through walls (rendering/RareMobHighlight.kt).
 */
public final class FishingFeatures {
    private static final Pattern DOUBLE_HOOK = Pattern.compile("^It's a Double Hook!");
    private static final Pattern COCOONED = Pattern.compile("^CAUGHT! You cocooned (?:a|an) (.+)!$");
    private static final Pattern PARTY = Pattern.compile("^Party > (?:\\[[^]]+] )?([A-Za-z0-9_]{1,16})[^:]*: (.*)$");
    // Party messages from Feesh (and Sky2M) and from SkyHanni.
    private static final Pattern PARTY_CAUGHT = Pattern.compile("^--> (?:A|An) (.+) has spawned (?:.*)?<--$");
    private static final Pattern PARTY_DOUBLE_HOOK = Pattern.compile("^--> DOUBLE HOOK! Two (.+)s have spawned (?:.*)?<--$");
    private static final Pattern PARTY_COCOONED = Pattern.compile("^--> (?:A|An) (.+) was cocooned (?:.*)?<--$");
    private static final Pattern SKYHANNI_CAUGHT = Pattern.compile("^(DOUBLE HOOK: )?I caught (?:a|an) (.+)!$");
    private static final Pattern SKYHANNI_COCOONED = Pattern.compile("^My (.+) has been cocooned!$");
    private static final Pattern PARTY_DROP = Pattern.compile("^--> (?:A|An) (.+?) has dropped(?: \\([^)]*\\))? <--$");
    private static final String DEATH_MESSAGE = "--> I was killed, please wait for me until I come back <--";
    private static final String LOOTSHARE = "Lootshare!";
    private static final Pattern YOU_DIED = Pattern.compile("^ ☠ You were killed by (Ragnarok|Thunder|Lord Jawbus|Jawbus Follower|Wiki Tiki|Wiki Tiki Laser Totem|Titanoboa|Nessie|Torrid|Silkbreeze|Giant Isopod)\\.$");
    // Rare drops, read from the plain text (Feesh reads the § codes; the rarity of a pet comes from its colour here).
    private static final Pattern RARE_DROP_BOOK = Pattern.compile("^RARE DROP! Enchanted Book \\((.+?)\\)(?: \\(\\+(\\d+) . Magic Find\\))?$");
    private static final Pattern RARE_DROP = Pattern.compile("^RARE DROP! (.+?)(?: \\(\\+(\\d+) . Magic Find\\))?$");
    private static final Pattern PET_DROP = Pattern.compile("^PET DROP! (?:(?:LEGENDARY|EPIC|RARE|UNCOMMON|COMMON) )?(.+?)(?: \\(\\+\\d+.*\\))?$");
    private static final Pattern PET_CATCH = Pattern.compile("^. (?:GREAT|OUTSTANDING) CATCH! You caught a \\[Lvl 1] (?:(?:LEGENDARY|EPIC|RARE|UNCOMMON|COMMON) )?(.+?)!$");
    private static final Pattern DYE_DROP = Pattern.compile("^WOW! (.+?) found an? (.+? Dye)(?: #\\d+)?!.*$");
    private static final Pattern PHOENIX_DROP = Pattern.compile("^Wow! (.+?) found a Phoenix pet!.*$");
    private static final Pattern NEW_DISCOVERY = Pattern.compile("^NEW DISCOVERY: (.+)$");
    private static final Pattern PET_LEVEL_UP = Pattern.compile("^Your (.+?) leveled up to level (\\d+)!$");
    private static final String WORMHOLE_CLOSED = "Your Wormhole closed up...";
    private static final String FESTIVAL_ENDED = "FISHING FESTIVAL The festival has concluded! Time to dry off and repair your rods!";
    private static final long FESTIVAL_MS = 61 * 60 * 1000L;
    private static final List<String> SHARKS = List.of("Great White Shark", "Tiger Shark", "Blue Shark", "Nurse Shark");
    private static final Set<String> NO_FISHING = Set.of("The Rift", "Rift Dimension", "Garden", "Kuudra", "Catacombs",
        "Dungeon Hub", "The End", "Glacite Mineshafts", "Mineshaft", "Safari");
    private static final Set<String> HOTSPOT_ISLANDS = Set.of("Backwater Bayou", "Spider's Den", "Hub", "Jerry's Workshop",
        "Lotus Atoll", "The Park", "Crimson Isle", "Torrhus Canyon");

    private static boolean doubleHook;
    private static int ticks;
    private static ClientLevel lastLevel;

    // Fishing Festival
    private static final Map<String, Integer> sharks = new LinkedHashMap<>();
    private static long festivalStartedAt;

    // Hotspots: the one your bobber was in, and the last two you were offered to share.
    private record Hotspot(int entityId, UUID uuid, double x, double y, double z, Component perk) {}
    private static Hotspot fishedHotspot;
    private static final List<UUID> offeredHotspots = new ArrayList<>();
    private static Hotspot offeredNearby;

    // Highlights: entity id -> rgb; the armour stands already looked at; which highlighted ones you can see.
    private static final Map<Integer, Integer> highlighted = new HashMap<>();
    private static final Set<Integer> checkedStands = new HashSet<>();
    private static final Set<Integer> visible = new HashSet<>();

    private FishingFeatures() {}

    private static FeatureConfigs.Fishing config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.fishing;
    }

    public static void init() {
        for (String shark : SHARKS) sharks.put(shark, 0);
        FishingRecords.load();
        Sky2MChat.onChat(FishingFeatures::onChat);
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !hideMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(FishingFeatures::tick);
        EntityGlow.register(entity -> visible.contains(entity.getId()) ? highlighted.getOrDefault(entity.getId(), -1) : -1);
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide()) Deployables.onUse(player.getItemInHand(hand));
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) Deployables.onUse(player.getItemInHand(hand));
            return InteractionResult.PASS;
        });
    }

    // ------------------------------------------------------------------------------------------------ chat

    /** Compact catch messages replace Hypixel's double hook and catch messages. */
    private static boolean hideMessage(Component message) {
        FeatureConfigs.Fishing c = config();
        if (c == null || !c.catchMessages.compact || !Sky2MLocation.onSkyblock()) return false;
        String text = Sky2MLocation.strip(message.getString());
        if (DOUBLE_HOOK.matcher(text).find()) return true;
        return catchOf(text) != null;
    }

    private static FishingData.SeaCreature catchOf(String text) {
        for (FishingData.SeaCreature creature : FishingData.creatures()) {
            if (creature.pattern().matcher(text).find()) return creature;
        }
        return null;
    }

    private static void onChat(Sky2MChat.Message message) {
        FeatureConfigs.Fishing c = config();
        if (c == null || !Sky2MLocation.onSkyblock()) return;
        String text = message.text();
        FishingParty.onMessage(text);

        if (DOUBLE_HOOK.matcher(text).find()) {
            doubleHook = true;
            return;
        }
        FishingData.SeaCreature creature = catchOf(text);
        if (creature != null) {
            boolean dh = creature.canBeDoubleHooked() && doubleHook;
            doubleHook = false;
            onOwnCatch(c, creature, dh);
            return;
        }
        Matcher m = COCOONED.matcher(text);
        if (m.matches()) {
            FishingData.SeaCreature cocooned = FishingData.creature(m.group(1));
            if (cocooned != null) onOwnCocoon(c, cocooned);
            return;
        }
        m = PARTY.matcher(text);
        if (m.matches()) {
            onPartyMessage(c, message.component(), m.group(1), m.group(2).trim());
            return;
        }
        m = YOU_DIED.matcher(text);
        if (m.matches()) {
            onOwnDeath(c, m.group(1));
            return;
        }
        if (onOwnDrop(c, message.component(), text)) return;
        m = NEW_DISCOVERY.matcher(text);
        if (m.matches()) {
            onDiscovery(c, message.component(), text);
            return;
        }
        m = PET_LEVEL_UP.matcher(text);
        if (m.matches()) {
            onPetLevelUp(c, message.component(), m.group(1), Integer.parseInt(m.group(2)));
            return;
        }
        if (text.equals(WORMHOLE_CLOSED) && c.alerts.wormholeGone && (inArea("Lotus Atoll") || inArea("Crimson Isle"))) {
            Sky2MAlerts.title(Component.literal("Wormhole ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("is gone").withStyle(ChatFormatting.RED)), null);
            return;
        }
        if (text.equals(FESTIVAL_ENDED)) onFestivalEnded(c);
    }

    // ------------------------------------------------------------------------------------------------ sea creatures

    private static void onOwnCatch(FeatureConfigs.Fishing c, FishingData.SeaCreature creature, boolean dh) {
        if (c.catchMessages.compact) chat(catchMessage(c.catchMessages, creature, dh), false);
        if (c.seaCreatures.alert && listed(c.seaCreatures.alertList, creature.name())) {
            showCaught(creature, dh, ownName());
        }
        if (c.seaCreatures.share && listed(c.seaCreatures.shareList, creature.name())) {
            String upper = creature.name().toUpperCase(Locale.ROOT);
            FishingParty.send(dh ? "--> DOUBLE HOOK! Two " + upper + "s have spawned <--"
                : "--> " + FishingData.article(creature.name(), false) + " " + upper + " has spawned <--");
        }
        if (SHARKS.contains(creature.name()) && (c.alerts.festivalEnded || c.alerts.festivalPersonalBest)) {
            long now = System.currentTimeMillis();
            if (festivalStartedAt == 0 || now - festivalStartedAt > FESTIVAL_MS) {
                sharks.replaceAll((k, v) -> 0);
                festivalStartedAt = now;
            }
            sharks.merge(creature.name(), dh ? 2 : 1, Integer::sum);
        }
    }

    private static void onOwnCocoon(FeatureConfigs.Fishing c, FishingData.SeaCreature creature) {
        if (c.seaCreatures.alert && c.seaCreatures.alertCocooned && listed(c.seaCreatures.alertList, creature.name())) {
            showCocooned(creature, ownName());
        }
        if (c.seaCreatures.share && c.seaCreatures.shareCocooned && listed(c.seaCreatures.shareList, creature.name())) {
            FishingParty.send("--> " + FishingData.article(creature.name(), false) + " " + creature.name().toUpperCase(Locale.ROOT) + " was cocooned <--");
        }
    }

    private static void onPartyMessage(FeatureConfigs.Fishing c, Component component, String sender, String body) {
        if (FishingParty.isLocal(sender)) return;
        Component name = nameIn(component, sender);
        if (body.equalsIgnoreCase(LOOTSHARE)) {
            if (c.alerts.lootshare) Sky2MAlerts.title(Component.literal("Lootshare!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), null);
            return;
        }
        if (body.equals(DEATH_MESSAGE)) {
            if (c.seaCreatures.alertDeath) {
                Sky2MAlerts.title(name.copy().append(Component.literal(" was killed ☠").withStyle(ChatFormatting.RED)),
                    Component.literal("Wait for them to come back"), false);
                Sky2MAlerts.play(SoundEvents.VILLAGER_DEATH, 1f);
            }
            return;
        }
        Matcher drop = PARTY_DROP.matcher(body);
        if (drop.matches()) {
            if (c.rareDrops.alert && c.rareDrops.alertSource == FishingData.AlertSource.OWN_AND_PARTY) showDrop(c, drop.group(1), name, false);
            return;
        }
        boolean creatures = c.seaCreatures.alert && c.seaCreatures.alertSource == FishingData.AlertSource.OWN_AND_PARTY;
        if (!creatures) return;
        Matcher m;
        if ((m = PARTY_DOUBLE_HOOK.matcher(body)).matches()) partyCaught(c, m.group(1), true, name);
        else if ((m = PARTY_CAUGHT.matcher(body)).matches()) partyCaught(c, m.group(1), false, name);
        else if ((m = PARTY_COCOONED.matcher(body)).matches()) partyCocooned(c, m.group(1), name);
        else if ((m = SKYHANNI_CAUGHT.matcher(body)).matches()) partyCaught(c, skyHanniName(m.group(2)), m.group(1) != null, name);
        else if ((m = SKYHANNI_COCOONED.matcher(body)).matches()) partyCocooned(c, skyHanniName(m.group(1)), name);
    }

    private static String skyHanniName(String name) {
        return name.equals("The Sea Emperor") ? "The Loch Emperor" : name;
    }

    private static void partyCaught(FeatureConfigs.Fishing c, String name, boolean dh, Component player) {
        FishingData.SeaCreature creature = FishingData.creature(name);
        if (creature != null && listed(c.seaCreatures.alertList, creature.name())) showCaught(creature, dh, player);
    }

    private static void partyCocooned(FeatureConfigs.Fishing c, String name, Component player) {
        FishingData.SeaCreature creature = FishingData.creature(name);
        if (c.seaCreatures.alertCocooned && creature != null && listed(c.seaCreatures.alertList, creature.name())) showCocooned(creature, player);
    }

    private static boolean listed(List<FishingData.AlertableSeaCreature> list, String name) {
        if (list == null) return false;
        for (FishingData.AlertableSeaCreature creature : list) if (creature != null && creature.displayName.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** The creature's name in bold in its rarity colour, between obfuscated x's when it's mythic, "X2" on a double hook. */
    private static MutableComponent creatureTitle(FishingData.SeaCreature creature, boolean dh) {
        MutableComponent title = Component.empty();
        boolean mythic = creature.rarity() == FishingData.Rarity.MYTHIC;
        if (mythic) title.append(Component.literal("x").withStyle(ChatFormatting.GOLD, ChatFormatting.OBFUSCATED)).append(" ");
        title.append(Component.literal(creature.name()).withStyle(creature.rarity().colour, ChatFormatting.BOLD));
        if (dh) title.append(" ").append(Component.literal("X2").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        if (mythic) title.append(" ").append(Component.literal("x").withStyle(ChatFormatting.GOLD, ChatFormatting.OBFUSCATED));
        return title;
    }

    private static void showCaught(FishingData.SeaCreature creature, boolean dh, Component player) {
        Sky2MAlerts.title(creatureTitle(creature, dh), player);
        if (creature.name().equals("Nessie")) chatWithCommand("Click to warp to Murkwater Loch!", "/warp murk");
        else if (creature.name().equals("Giant Isopod")) chatWithCommand("Click to warp to Torrhus Springs!", "/warp springs");
    }

    private static void showCocooned(FishingData.SeaCreature creature, Component player) {
        Sky2MAlerts.title(Component.literal(creature.name()).withStyle(creature.rarity().colour, ChatFormatting.BOLD)
            .append(Component.literal(" cocooned").withStyle(ChatFormatting.RED)), player);
    }

    private static void onOwnDeath(FeatureConfigs.Fishing c, String killer) {
        if (c.seaCreatures.alertDeath) {
            Sky2MAlerts.title(Component.literal("You were killed ☠").withStyle(ChatFormatting.RED), null, false);
            Sky2MAlerts.play(SoundEvents.VILLAGER_DEATH, 1f);
            if (killer.equals("Nessie")) chatWithCommand("Click to warp to Murkwater Loch!", "/warp murk");
        }
        if (c.seaCreatures.shareDeath) FishingParty.send(DEATH_MESSAGE);
    }

    // ------------------------------------------------------------------------------------------------ compact catch messages

    static Component catchMessage(FeatureConfigs.FishingCatchMessages c, FishingData.SeaCreature creature, boolean dh) {
        String template = c.catchTemplate == null || c.catchTemplate.isBlank() ? "&7{Article} {sc} &7has spawned!" : c.catchTemplate;
        template = colours(template.replace("{Article}", FishingData.article(creature.name(), false))
            .replace("{article}", FishingData.article(creature.name(), true)));
        MutableComponent line = Component.empty();
        if (dh) {
            String dhTemplate = c.doubleHookTemplate == null || c.doubleHookTemplate.isBlank() ? "&b&lDOUBLE HOOK!" : c.doubleHookTemplate;
            line.append(SbcItems.parseLegacy(colours(dhTemplate) + " "));
        }
        String[] parts = template.split("\\{sc}", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) line.append(creatureName(c, creature));
            line.append(SbcItems.parseLegacy(parts[i]));
        }
        return line;
    }

    private static String colours(String text) {
        return text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    private static Component creatureName(FeatureConfigs.FishingCatchMessages c, FishingData.SeaCreature creature) {
        if (!c.gradient) return Component.literal(creature.name()).withStyle(creature.rarity().colour, ChatFormatting.BOLD);
        return gradient(creature.name(), creature.rarity().gradient);
    }

    /** Feesh's gradient: the colours spread over the letters (fewer steps for short names), in bold. */
    private static Component gradient(String text, int[] colours) {
        int steps = text.length() <= 2 ? Math.min(text.length(), colours.length) : Math.min(text.length() - 1, colours.length);
        int[] used = java.util.Arrays.copyOf(colours, Math.max(1, steps));
        MutableComponent out = Component.empty();
        int last = text.length() - 1;
        for (int i = 0; i < text.length(); i++) {
            int rgb = used.length == 1 || last == 0 ? used[0] : along(used, (float) i / last);
            out.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb)).withBold(true)));
        }
        return out;
    }

    private static int along(int[] steps, float t) {
        if (t <= 0) return steps[0];
        if (t >= 1) return steps[steps.length - 1];
        float scaled = t * (steps.length - 1);
        int index = Math.min((int) scaled, steps.length - 2);
        float local = scaled - index;
        int a = steps[index], b = steps[index + 1];
        int r = (a >> 16 & 255) + (int) (((b >> 16 & 255) - (a >> 16 & 255)) * local);
        int g = (a >> 8 & 255) + (int) (((b >> 8 & 255) - (a >> 8 & 255)) * local);
        int bl = (a & 255) + (int) (((b & 255) - (a & 255)) * local);
        return r << 16 | g << 8 | bl;
    }

    /** Settings > Fishing > Catch Messages > Send Test Message: Lord Jawbus, then a double hooked Thunder. */
    public static void sendTestCatchMessages() {
        FeatureConfigs.Fishing c = config();
        if (c == null) return;
        FishingData.SeaCreature jawbus = FishingData.creature("Lord Jawbus");
        FishingData.SeaCreature thunder = FishingData.creature("Thunder");
        if (jawbus != null) chat(catchMessage(c.catchMessages, jawbus, false), false);
        if (thunder != null) chat(catchMessage(c.catchMessages, thunder, true), false);
    }

    // ------------------------------------------------------------------------------------------------ rare drops

    /** Your rare drops; true if the message was one. */
    private static boolean onOwnDrop(FeatureConfigs.Fishing c, Component component, String text) {
        String item = null;
        Integer magicFind = null;
        Matcher m;
        if ((m = RARE_DROP_BOOK.matcher(text)).matches()) {
            item = m.group(1);
            magicFind = m.group(2) == null ? null : Integer.valueOf(m.group(2));
        } else if ((m = RARE_DROP.matcher(text)).matches()) {
            item = m.group(1);
            magicFind = m.group(2) == null ? null : Integer.valueOf(m.group(2));
        } else if ((m = PET_DROP.matcher(text)).matches() || (m = PET_CATCH.matcher(text)).matches()) {
            item = m.group(1) + " (" + rarityName(colourOf(component, m.group(1))) + ")";
        } else if ((m = PHOENIX_DROP.matcher(text)).matches()) {
            if (!isOwnName(m.group(1))) return true;
            item = "Phoenix";
        } else if ((m = DYE_DROP.matcher(text)).matches()) {
            if (!isOwnName(m.group(1))) return true;
            item = m.group(2);
        }
        if (item == null) return false;
        FishingData.RareDrop drop = FishingData.drop(item);
        if (drop == null) return true;
        if (c.rareDrops.alert && dropListed(c.rareDrops.alertList, drop)) showDrop(c, drop.name(), ownName(), true);
        if (c.rareDrops.share && dropListed(c.rareDrops.shareList, drop)) {
            String extra = magicFind != null && c.rareDrops.shareMagicFind ? " (+" + magicFind + " ✯ Magic Find)" : "";
            FishingParty.send("--> " + FishingData.article(drop.name(), false) + " " + drop.name() + " has dropped" + extra + " <--");
        }
        return true;
    }

    private static boolean dropListed(List<FishingData.RareDropType> list, FishingData.RareDrop drop) {
        return list != null && (list.contains(FishingData.RareDropType.ALL) || list.contains(drop.type()));
    }

    private static void showDrop(FeatureConfigs.Fishing c, String itemName, Component player, boolean own) {
        FishingData.RareDrop drop = FishingData.drop(itemName);
        if (drop == null || !dropListed(c.rareDrops.alertList, drop)) return;
        MutableComponent title = Component.empty();
        String name = drop.name().contains(" (") ? drop.name().substring(0, drop.name().indexOf(" (")) : drop.name();
        if (drop.extremelyRare()) title.append(Component.literal("x").withStyle(ChatFormatting.GOLD, ChatFormatting.OBFUSCATED)).append(" ");
        title.append(Component.literal(name).withStyle(drop.colour(), ChatFormatting.BOLD));
        if (drop.extremelyRare()) title.append(" ").append(Component.literal("x").withStyle(ChatFormatting.GOLD, ChatFormatting.OBFUSCATED));
        boolean price = switch (c.rareDrops.priceScope) {
            case OWN -> own;
            case OWN_AND_PARTY -> true;
            case OFF -> false;
        };
        double value = price ? Sky2MPriceTooltip.unitPrice(priceId(drop.id())) : 0;
        if (value > 0) {
            title.append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("+").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(CombatFeatures.formatCoins(value)).withStyle(ChatFormatting.GOLD))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        }
        // Hypixel already plays its rare drop sound.
        Sky2MAlerts.title(title, player, false);
    }

    /** The price API's key: "FLYING_FISH;4" is a level 1 legendary Flying Fish pet. */
    private static String priceId(String id) {
        int semicolon = id.indexOf(';');
        if (semicolon < 0) return id;
        String tier = switch (id.substring(semicolon + 1)) {
            case "0" -> "COMMON";
            case "1" -> "UNCOMMON";
            case "2" -> "RARE";
            case "3" -> "EPIC";
            case "4" -> "LEGENDARY";
            case "5" -> "MYTHIC";
            default -> "";
        };
        return tier.isEmpty() ? id : "LVL_1_" + tier + "_" + id.substring(0, semicolon);
    }

    // ------------------------------------------------------------------------------------------------ trophies, pets

    private static void onDiscovery(FeatureConfigs.Fishing c, Component component, String text) {
        boolean obfuscated1 = text.contains("Obfuscated-1") || text.contains("Obfuscated 1");
        String legacy = SbcItems.legacy(component);
        Component details = SbcItems.parseLegacy(legacy.substring(legacy.lastIndexOf(": ") + 2));
        if (inArea("Lotus Atoll") && !obfuscated1) {
            if (c.alerts.trophyFrog) Sky2MAlerts.title(details, Component.literal("FROG DISCOVERED!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);
        } else if (obfuscated1 || inArea("Crimson Isle")) {
            if (c.alerts.trophyFish) Sky2MAlerts.title(details, Component.literal("TROPHY FISH DISCOVERED!").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD), false);
        }
    }

    private static void onPetLevelUp(FeatureConfigs.Fishing c, Component component, String pet, int level) {
        if (level != 100 && level != 200) return;
        ChatFormatting colour = colourOf(component, pet);
        Component petName = Component.literal(pet).withStyle(colour == null ? ChatFormatting.WHITE : colour);
        if (c.alerts.petMaxLevel) {
            Sky2MAlerts.title(petName.copy().append(Component.literal(" is maxed").withStyle(ChatFormatting.WHITE)),
                Component.literal("Level " + level).withStyle(ChatFormatting.WHITE));
        }
        if (!c.alerts.petLevelUpPrice) return;
        String tier = rarityName(colour).toUpperCase(Locale.ROOT);
        String type = pet.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", "").trim().replace(' ', '_');
        double maxed = Sky2MPriceTooltip.unitPrice("LVL_" + level + "_" + tier + "_" + type);
        double base = Sky2MPriceTooltip.unitPrice("LVL_1_" + tier + "_" + type);
        if (maxed <= 0) {
            Sky2MAlerts.chat(Component.literal("No price found for ").withStyle(ChatFormatting.YELLOW).append(petName).append(Component.literal(".").withStyle(ChatFormatting.YELLOW)));
            return;
        }
        Sky2MAlerts.chat(Component.literal("Estimated price for ").withStyle(ChatFormatting.WHITE).append(petName)
            .append(Component.literal(" is ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(CombatFeatures.formatCoins(maxed)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(", profit for leveling it up is ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(CombatFeatures.formatCoins(maxed - base)).withStyle(ChatFormatting.GOLD))
            .append(Component.literal(".").withStyle(ChatFormatting.WHITE)));
    }

    private static String rarityName(ChatFormatting colour) {
        if (colour == null) return "Common";
        return switch (colour) {
            case GREEN -> "Uncommon";
            case BLUE -> "Rare";
            case DARK_PURPLE -> "Epic";
            case GOLD -> "Legendary";
            case LIGHT_PURPLE -> "Mythic";
            case RED -> "Special";
            default -> "Common";
        };
    }

    // ------------------------------------------------------------------------------------------------ festival

    private static void onFestivalEnded(FeatureConfigs.Fishing c) {
        int total = 0;
        for (int count : sharks.values()) total += count;
        if (total == 0 || !inFishingWorld()) {
            resetFestival();
            return;
        }
        if (c.alerts.festivalEnded) {
            Sky2MAlerts.title(Component.literal("Fishing Festival ended").withStyle(ChatFormatting.YELLOW), null);
            MutableComponent counts = Component.literal("You caught ").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(String.valueOf(total)).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .append(Component.literal(" (").withStyle(ChatFormatting.WHITE));
            int i = 0;
            for (Map.Entry<String, Integer> shark : sharks.entrySet()) {
                FishingData.SeaCreature creature = FishingData.creature(shark.getKey());
                if (i++ > 0) counts.append(" ");
                counts.append(Component.literal(String.format(Locale.US, "%,d", shark.getValue()))
                    .withStyle(creature == null ? ChatFormatting.WHITE : creature.rarity().colour));
            }
            Sky2MAlerts.chat(counts.append(Component.literal(") sharks during the Fishing Festival.").withStyle(ChatFormatting.WHITE)));
        }
        if (c.alerts.festivalPersonalBest) {
            int greatWhites = sharks.getOrDefault("Great White Shark", 0);
            boolean best = false;
            if (total > FishingRecords.sharks) {
                announceBest(FishingRecords.sharks, total, "sharks", "Sharks: ");
                FishingRecords.sharks = total;
                best = true;
            }
            if (greatWhites > FishingRecords.greatWhites) {
                announceBest(FishingRecords.greatWhites, greatWhites, "Great White Sharks", "Great White Sharks: ");
                FishingRecords.greatWhites = greatWhites;
                best = true;
            }
            if (best) {
                Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1f);
                FishingRecords.save();
            }
        }
        resetFestival();
    }

    private static void announceBest(int previous, int now, String what, String subtitle) {
        Sky2MAlerts.chat(Component.literal("PERSONAL BEST! ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
            .append(Component.literal("You caught ").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(String.valueOf(previous)).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
            .append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.valueOf(now)).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
            .append(Component.literal(" " + what + " during the Fishing Festival!").withStyle(ChatFormatting.WHITE)));
        Sky2MAlerts.title(Component.literal("PERSONAL BEST!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
            Component.literal(subtitle).append(Component.literal(String.valueOf(now)).withStyle(ChatFormatting.GREEN)), false);
    }

    private static void resetFestival() {
        sharks.replaceAll((k, v) -> 0);
        festivalStartedAt = 0;
    }

    // ------------------------------------------------------------------------------------------------ tick

    private static void tick(Minecraft mc) {
        FishingParty.tick(mc);
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            fishedHotspot = null;
            offeredNearby = null;
            offeredHotspots.clear();
            highlighted.clear();
            checkedStands.clear();
            visible.clear();
            doubleHook = false;
            Deployables.reset();
            if (festivalStartedAt != 0 && System.currentTimeMillis() - festivalStartedAt > FESTIVAL_MS) resetFestival();
        }
        FeatureConfigs.Fishing c = config();
        if (c == null || level == null || mc.player == null || !Sky2MLocation.onSkyblock()) return;
        ticks++;
        if (ticks % 20 == 0) Deployables.tick(mc, level, c.alerts);
        Deployables.watchSpawns(mc, level);
        if (ticks % 10 == 0 && HOTSPOT_ISLANDS.contains(Sky2MLocation.area()) && rodInHotbar(mc)) {
            if (c.alerts.hotspotGone) trackHotspot(mc, level);
            if (c.alerts.shareHotspots) offerHotspot(mc, level);
        }
        checkHotspotGone(mc, level, c);
        if (c.seaCreatures.highlight && inFishingWorld()) {
            if (ticks % 5 == 0) findHighlights(level, c.seaCreatures.highlightList);
            highlighted.keySet().removeIf(id -> level.getEntity(id) == null);
            visible.clear();
            for (int id : highlighted.keySet()) {
                Entity entity = level.getEntity(id);
                if (entity != null && mc.player.hasLineOfSight(entity)) visible.add(id);
            }
        } else if (!highlighted.isEmpty()) {
            highlighted.clear();
            checkedStands.clear();
            visible.clear();
        }
    }

    private static boolean rodInHotbar(Minecraft mc) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).is(Items.FISHING_ROD)) return true;
        return false;
    }

    private static boolean inFishingWorld() {
        return Sky2MLocation.onSkyblock() && !Sky2MLocation.area().isEmpty() && !NO_FISHING.contains(Sky2MLocation.area());
    }

    private static boolean inArea(String area) {
        return Sky2MLocation.area().equals(area);
    }

    // ------------------------------------------------------------------------------------------------ hotspots

    /** The closest "HOTSPOT" armour stand within {@code range} of the point, with the perk stand just below it. */
    private static Hotspot closestHotspot(ClientLevel level, double x, double y, double z, double range) {
        ArmorStand best = null;
        double bestDistance = range * range;
        List<ArmorStand> stands = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName()) continue;
            double d = stand.distanceToSqr(x, y, z);
            if (d > (range + 2) * (range + 2)) continue;
            stands.add(stand);
            if (d <= bestDistance && Sky2MLocation.strip(stand.getCustomName().getString()).equals("HOTSPOT")) {
                best = stand;
                bestDistance = d;
            }
        }
        if (best == null) return null;
        Component perk = null;
        for (ArmorStand stand : stands) {
            if (stand.getX() == best.getX() && stand.getZ() == best.getZ() && stand.getY() < best.getY()
                && best.getY() - stand.getY() <= 1.0 && stand.getXRot() == best.getXRot()) {
                perk = stand.getCustomName();
                break;
            }
        }
        return new Hotspot(best.getId(), best.getUUID(), best.getX(), best.getY(), best.getZ(), perk);
    }

    private static void trackHotspot(Minecraft mc, ClientLevel level) {
        var hook = mc.player.fishing;
        if (hook == null || !hook.isInLiquid()) return;
        Hotspot hotspot = closestHotspot(level, hook.getX(), hook.getY(), hook.getZ(), 5);
        if (hotspot != null) fishedHotspot = hotspot;
    }

    private static void checkHotspotGone(Minecraft mc, ClientLevel level, FeatureConfigs.Fishing c) {
        Hotspot hotspot = fishedHotspot;
        if (hotspot == null) return;
        Entity entity = level.getEntity(hotspot.entityId());
        if (entity != null && !entity.isRemoved()) return;
        fishedHotspot = null;
        if (!c.alerts.hotspotGone || mc.player.distanceToSqr(hotspot.x(), hotspot.y(), hotspot.z()) > 30 * 30) return;
        Sky2MAlerts.title(Component.literal("Hotspot ").withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal("is gone").withStyle(ChatFormatting.RED)), null);
        MutableComponent message = Component.empty();
        if (hotspot.perk() != null) message.append(hotspot.perk()).append(" ");
        chat(message.append(Component.literal("Hotspot").withStyle(ChatFormatting.LIGHT_PURPLE))
            .append(Component.literal(" is gone, time to find another one!").withStyle(ChatFormatting.WHITE)), true);
    }

    private static void offerHotspot(Minecraft mc, ClientLevel level) {
        Hotspot hotspot = closestHotspot(level, mc.player.getX(), mc.player.getY(), mc.player.getZ(), 10);
        if (hotspot == null) return;
        if (offeredHotspots.contains(hotspot.uuid())) {
            offeredNearby = hotspot;
            return;
        }
        if (offeredNearby != null && offeredNearby.uuid().equals(hotspot.uuid())) return;
        offeredNearby = hotspot;
        offeredHotspots.addFirst(hotspot.uuid());
        while (offeredHotspots.size() > 2) offeredHotspots.removeLast();

        MutableComponent found = Component.literal("You found ").withStyle(ChatFormatting.WHITE);
        if (hotspot.perk() != null) found.append(hotspot.perk()).append(" ");
        chat(found.append(Component.literal("Hotspot").withStyle(ChatFormatting.LIGHT_PURPLE)).append(Component.literal(".").withStyle(ChatFormatting.WHITE)), true);
        String party = hotspotMessage(hotspot, false), all = hotspotMessage(hotspot, true);
        chat(Component.literal("[Share to ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
            .append(Component.literal("PARTY").withStyle(ChatFormatting.BLUE, ChatFormatting.BOLD))
            .append(Component.literal(" chat]").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
            .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/pc " + party))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to share to PARTY chat"))))
            .append(Component.literal(" or ").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)))
            .append(Component.literal("[Share to ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
                .append(Component.literal("ALL").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
                .append(Component.literal(" chat]").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD))
                .withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/ac " + all))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to share to ALL chat")))))
            .append(Component.literal(" [Turn off]").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY).withBold(false)
                .withClickEvent(new ClickEvent.RunCommand("/s2 toggle fishing.alerts.shareHotspots"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Stop offering to share hotspots.\nTurn it back on in /s2 > Fishing > Alerts."))))), false);
        Sky2MAlerts.ding();
    }

    /** "x: 1, y: 2, z: 3 | Double Hook Chance Hotspot at Fishing Outpost", plus an id in all chat (no duplicate block). */
    private static String hotspotMessage(Hotspot hotspot, boolean withId) {
        String perk = hotspot.perk() == null ? "" : Sky2MLocation.strip(hotspot.perk().getString()).trim() + " ";
        String zone = Sky2MLocation.location().replaceAll("^[^A-Za-z']+", "").trim();
        String text = "x: " + Math.round(hotspot.x()) + ", y: " + Math.round(hotspot.y()) + ", z: " + Math.round(hotspot.z())
            + " | " + perk + "Hotspot" + (zone.isEmpty() || inArea("Backwater Bayou") ? "" : " at " + zone);
        if (withId) {
            StringBuilder id = new StringBuilder(" | @");
            String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
            for (int i = 0; i < 10; i++) id.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
            text += id;
        }
        return text;
    }

    // ------------------------------------------------------------------------------------------------ highlight

    private static final Pattern NAMETAG_HP = Pattern.compile("[0-9.,]+[kKmMbB]?/[0-9.,]+[kKmMbB]?❤");

    /** Sea creature nametags ("[Lv600] ♆⚙♣ Lord Jawbus 69M/100M❤"): the creature is an entity just before its stand. */
    private static void findHighlights(ClientLevel level, List<FishingData.HighlightableSeaCreature> list) {
        if (list == null || list.isEmpty()) return;
        Set<String> wanted = new HashSet<>();
        for (FishingData.HighlightableSeaCreature creature : list) if (creature != null) wanted.add(creature.displayName);
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName() || checkedStands.contains(stand.getId())) continue;
            String name = Sky2MLocation.strip(stand.getCustomName().getString());
            if (!name.contains("[Lv") || (!name.contains("❤") && !name.contains("Puddle Jumper"))) continue;
            checkedStands.add(stand.getId());
            String mob = baseName(name);
            if (mob == null || !wanted.contains(mob)) continue;
            highlight(level, stand.getId(), mob);
        }
    }

    /** "[Lv600] ♆⚙♣ Corrupted Lord Jawbus 69M/100M❤ ✯" -> "Lord Jawbus". */
    private static String baseName(String nametag) {
        int bracket = nametag.indexOf("] ");
        if (bracket < 0) return null;
        String rest = nametag.substring(bracket + 2).replace("Corrupted ", "");
        Matcher hp = NAMETAG_HP.matcher(rest);
        if (hp.find()) rest = rest.substring(0, hp.start());
        return rest.replaceAll("[^a-zA-Z\\s'-]", "").trim();
    }

    private static void highlight(ClientLevel level, int standId, String mob) {
        // How far before its nametag the creature's own entity is (Feesh's offsets).
        int shift = switch (mob) {
            case "Werewolf", "Puddle Jumper" -> 2;
            case "Fire Eel" -> 11;
            case "Drowned Captain" -> 6;
            case "Reindrake" -> 8;
            case "Titanoboa" -> 43;
            default -> 1;
        };
        Entity target = level.getEntity(standId - shift);
        if (!(target instanceof LivingEntity) && !(target instanceof Display.ItemDisplay)) return;
        if (mob.equals("Jawbus Follower") && target.getType() == EntityTypes.SLIME) {
            target = level.getEntity(standId - 11);
            if (!(target instanceof LivingEntity)) return;
        }
        if (target instanceof LivingEntity living && !living.isAlive()) return;
        // Some creatures are player entities (Alligator, Abyssal Miner); real players have version 4 (or 1) UUIDs.
        if (target instanceof Player && (target.getUUID().version() == 4 || target.getUUID().version() == 1)) return;
        int colour = colourFor(mob);
        List<Entity> parts = new ArrayList<>(List.of(target));
        if (target.getVehicle() instanceof LivingEntity vehicle) parts.add(vehicle);
        if (target.getFirstPassenger() instanceof LivingEntity passenger) parts.add(passenger);
        if (mob.equals("Wiki Tiki")) {
            for (int s : new int[]{3, 5, 7}) if (level.getEntity(standId - s) instanceof LivingEntity part) parts.add(part);
        }
        if (mob.equals("Magma Pillar")) {
            for (int s = 2; s <= 9; s++) if (level.getEntity(standId - s) instanceof LivingEntity part) parts.add(part);
        }
        for (Entity part : parts) highlighted.put(part.getId(), colour);
    }

    private static int colourFor(String mob) {
        FishingData.SeaCreature creature = FishingData.creature(mob);
        if (creature != null) return creature.rarity().rgb;
        return switch (mob) {
            case "Flipflopper", "Seashine" -> FishingData.Rarity.DIVINE.rgb;
            case "Jawbus Follower", "Wiki Tiki Laser Totem" -> FishingData.Rarity.SPECIAL.rgb;
            default -> 0x00FFFF;
        };
    }

    // ------------------------------------------------------------------------------------------------ helpers

    static String localName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.getGameProfile().name();
    }

    private static boolean isOwnName(String playerAndRank) {
        String me = localName();
        return me != null && playerAndRank.contains(me);
    }

    /** Your name in its rank colour, as your nametag shows it. */
    private static Component ownName() {
        Minecraft mc = Minecraft.getInstance();
        String me = localName();
        if (mc.player == null || me == null) return Component.empty();
        return nameIn(mc.player.getDisplayName(), me);
    }

    /** {@code name} in the colour it has in {@code component} (a party member's rank colour). */
    private static Component nameIn(Component component, String name) {
        TextColor[] colour = {null};
        component.visit((style, text) -> {
            if (colour[0] == null && text.contains(name)) colour[0] = style.getColor();
            return Optional.empty();
        }, Style.EMPTY);
        return Component.literal(name).withStyle(Style.EMPTY.withColor(colour[0] == null ? TextColor.fromLegacyFormat(ChatFormatting.WHITE) : colour[0]));
    }

    /** The legacy colour {@code text} has in {@code component}, or null. */
    private static ChatFormatting colourOf(Component component, String text) {
        TextColor[] colour = {null};
        String first = text.split(" ")[0];
        component.visit((style, part) -> {
            if (colour[0] == null && part.contains(first) && style.getColor() != null) colour[0] = style.getColor();
            return Optional.empty();
        }, Style.EMPTY);
        if (colour[0] == null) return null;
        for (ChatFormatting f : ChatFormatting.values()) {
            TextColor legacy = TextColor.fromLegacyFormat(f);
            if (legacy != null && legacy.getValue() == colour[0].getValue()) return f;
        }
        return null;
    }

    private static void chat(Component message, boolean prefixed) {
        if (prefixed) {
            Sky2MAlerts.chat(message);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
        });
    }

    private static void chatWithCommand(String text, String command) {
        chat(Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(command)))), true);
    }

    // ------------------------------------------------------------------------------------------------ party chat

    /**
     * Party messages, one at a time (Hypixel drops messages sent too fast) and only while you're in a party, as
     * Sky2M's Diana sharing does; party membership is read from Hypixel's party messages.
     */
    static final class FishingParty {
        private static final long SEND_INTERVAL_MS = 600L;
        private static final List<String> queue = new ArrayList<>();
        /** Null until a party message says either way: then messages are sent, as you may already be in one. */
        private static Boolean inParty;
        private static long lastSentAt;

        private FishingParty() {}

        static void send(String message) {
            if (!Boolean.FALSE.equals(inParty)) queue.add(message);
        }

        static void tick(Minecraft mc) {
            long now = System.currentTimeMillis();
            if (queue.isEmpty() || now - lastSentAt < SEND_INTERVAL_MS) return;
            if (mc.getConnection() == null) {
                queue.clear();
                return;
            }
            mc.getConnection().sendCommand("pc " + queue.removeFirst());
            lastSentAt = now;
        }

        static boolean isLocal(String name) {
            String local = localName();
            return local != null && local.equalsIgnoreCase(name);
        }

        static void onMessage(String text) {
            if (text.startsWith("Party > ") || text.startsWith("Party Leader: ") || text.startsWith("Party Members (")
                || text.matches("^You have joined .+ party!$") || text.matches("^.+ joined the party\\.$")) {
                inParty = true;
            } else if (text.equals("You left the party.") || text.startsWith("The party was disbanded")
                || text.startsWith("You have been kicked from the party") || text.startsWith("You are not currently in a party")
                || text.startsWith("You are not in a party")) {
                inParty = false;
                queue.clear();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ deployables

    /** Your deployables, from their armour stands, for the "expires soon" alert (Feesh's DeployablesTimer). */
    static final class Deployables {
        private static final List<String> LANTERNS = List.of("Dwarven Lantern", "Mithril Lantern", "Titanium Lantern", "Glacite Lantern", "Will-o'-wisp");
        private static final List<String> FLUXES = List.of("Overflux", "Plasmaflux", "Mana Flux");

        private static final class Tracked {
            final boolean shortLived;
            int remaining = -1;
            boolean alerted;
            Integer entityId;
            String name;

            Tracked(boolean shortLived) {
                this.shortLived = shortLived;
            }
        }

        private static final Map<FishingData.DeployableType, Tracked> TRACKED = new HashMap<>();
        private static long lanternUsedAt, umberellaUsedAt, fluxUsedAt, flareUsedAt;
        private static String flareName;

        static {
            reset();
        }

        static void reset() {
            for (FishingData.DeployableType type : FishingData.DeployableType.values()) {
                TRACKED.put(type, new Tracked(type == FishingData.DeployableType.FLUX));
            }
            lanternUsedAt = umberellaUsedAt = fluxUsedAt = flareUsedAt = 0;
        }

        private static boolean wanted(FishingData.DeployableType type) {
            FeatureConfigs.Fishing c = config();
            return c != null && c.alerts.deployables && c.alerts.deployableList != null && c.alerts.deployableList.contains(type);
        }

        static void onUse(ItemStack stack) {
            if (stack.isEmpty() || !Sky2MLocation.onSkyblock()) return;
            String name = Sky2MLocation.strip(com.epic60869.sky2m.custom.util.Compat.realName(stack).getString());
            long now = System.currentTimeMillis();
            if (name.equals("Umberella")) umberellaUsedAt = now;
            if (LANTERNS.contains(name)) lanternUsedAt = now;
            for (String flux : FLUXES) if (name.contains(flux + " Power Orb")) fluxUsedAt = now;
            if (name.endsWith("Flare") && wanted(FishingData.DeployableType.FLARE) && now - flareUsedAt > 500) {
                flareUsedAt = now;
                flareName = SbcItems.legacy(com.epic60869.sky2m.custom.util.Compat.realName(stack));
            }
        }

        /** Umberellas, lanterns and power orbs don't name their owner: the one that appears next to you just after you use one is yours. */
        static void watchSpawns(Minecraft mc, ClientLevel level) {
            long now = System.currentTimeMillis();
            // A flare is a firework rocket half a second after the click.
            if (flareUsedAt != 0 && now - flareUsedAt >= 500 && now - flareUsedAt < 1500) {
                for (Entity entity : level.entitiesForRendering()) {
                    if (entity.getType() == EntityTypes.FIREWORK_ROCKET && entity.distanceTo(mc.player) <= 10) {
                        Tracked flare = TRACKED.get(FishingData.DeployableType.FLARE);
                        flare.remaining = 180;
                        flare.alerted = false;
                        flare.name = flareName;
                        flareUsedAt = 0;
                        break;
                    }
                }
            }
            if (now - lanternUsedAt > 1000 && now - umberellaUsedAt > 1000 && now - fluxUsedAt > 1000) return;
            for (Entity entity : level.entitiesForRendering()) {
                if (!(entity instanceof ArmorStand stand) || !stand.hasCustomName() || stand.distanceTo(mc.player) > 5) continue;
                String name = Sky2MLocation.strip(stand.getCustomName().getString());
                if (now - lanternUsedAt <= 1000 && startsWithAny(name, LANTERNS) && (name.endsWith("300s") || name.endsWith("600s"))) {
                    start(FishingData.DeployableType.DWARVEN_LANTERN, stand, name);
                } else if (now - umberellaUsedAt <= 1000 && (name.equals("Umberella 300s") || name.equals("Umberella 600s"))) {
                    start(FishingData.DeployableType.UMBERELLA, stand, "§9Umberella");
                } else if (now - fluxUsedAt <= 1000 && startsWithAny(name, FLUXES) && (name.endsWith("30s") || name.endsWith("60s") || name.endsWith("120s"))) {
                    start(FishingData.DeployableType.FLUX, stand, name);
                }
            }
        }

        private static void start(FishingData.DeployableType type, ArmorStand stand, String name) {
            Tracked tracked = TRACKED.get(type);
            if (Integer.valueOf(stand.getId()).equals(tracked.entityId)) return;
            tracked.entityId = stand.getId();
            tracked.alerted = false;
            tracked.name = name.startsWith("§") ? name : SbcItems.legacy(stand.getCustomName()).replaceAll(" §.[\\d]+s$", "").replaceAll(" [\\d]+s$", "").replace("§l", "");
        }

        private static boolean startsWithAny(String name, List<String> prefixes) {
            for (String prefix : prefixes) if (name.startsWith(prefix)) return true;
            return false;
        }

        static void tick(Minecraft mc, ClientLevel level, FeatureConfigs.FishingAlerts c) {
            if (!c.deployables || c.deployableList == null || c.deployableList.isEmpty()) return;
            String me = localName();
            if (me == null) return;
            Map<Integer, ArmorStand> stands = new HashMap<>();
            List<ArmorStand> named = new ArrayList<>();
            for (Entity entity : level.entitiesForRendering()) {
                if (entity instanceof ArmorStand stand && stand.hasCustomName()) {
                    stands.put(stand.getId(), stand);
                    named.add(stand);
                }
            }
            if (wanted(FishingData.DeployableType.TOTEM_OF_CORRUPTION)) {
                int remaining = -1;
                for (ArmorStand owner : named) {
                    String name = text(owner);
                    if (!name.contains("Owner:") || !name.contains(me)) continue;
                    ArmorStand totem = stands.get(owner.getId() - 2), timer = stands.get(owner.getId() - 1);
                    if (totem == null || timer == null || !text(totem).equals("Totem of Corruption") || !text(timer).contains("Remaining: ")) continue;
                    remaining = seconds(text(timer).substring(text(timer).indexOf("Remaining: ") + 11));
                    break;
                }
                update(FishingData.DeployableType.TOTEM_OF_CORRUPTION, remaining, "§5Totem of Corruption", c);
            }
            if (wanted(FishingData.DeployableType.BLACK_HOLE)) {
                int remaining = -1;
                for (ArmorStand owner : named) {
                    String name = text(owner);
                    if (!name.contains("Spawned by:") || !name.contains(me)) continue;
                    ArmorStand hole = stands.get(owner.getId() + 1);
                    if (hole == null || !text(hole).startsWith("Black Hole")) continue;
                    String timer = text(hole).substring("Black Hole".length()).trim();
                    remaining = timer.isEmpty() ? 180 : seconds(timer);
                    break;
                }
                update(FishingData.DeployableType.BLACK_HOLE, remaining, "§5Black Hole", c);
            }
            for (FishingData.DeployableType type : List.of(FishingData.DeployableType.UMBERELLA, FishingData.DeployableType.DWARVEN_LANTERN, FishingData.DeployableType.FLUX)) {
                Tracked tracked = TRACKED.get(type);
                if (tracked.entityId == null || !wanted(type)) continue;
                ArmorStand stand = stands.get(tracked.entityId);
                if (stand == null) {
                    tracked.entityId = null;
                    tracked.remaining = -1;
                    continue;
                }
                String[] words = text(stand).split(" ");
                update(type, seconds(words[words.length - 1]), tracked.name, c);
            }
            Tracked flare = TRACKED.get(FishingData.DeployableType.FLARE);
            if (flare.remaining > 0 && wanted(FishingData.DeployableType.FLARE)) {
                update(FishingData.DeployableType.FLARE, flare.remaining - 1, flare.name == null ? "Flare" : flare.name, c);
            }
        }

        private static String text(ArmorStand stand) {
            return Sky2MLocation.strip(stand.getCustomName().getString());
        }

        /** "1m 02s", "50s" or "120s" in seconds; 0 if it can't be read. */
        private static int seconds(String time) {
            try {
                time = time.trim();
                if (time.contains("m")) {
                    String[] parts = time.split("m");
                    int minutes = Integer.parseInt(parts[0].trim());
                    int seconds = parts.length > 1 && !parts[1].isBlank() ? Integer.parseInt(parts[1].replace("s", "").trim()) : 0;
                    return minutes * 60 + seconds;
                }
                return Integer.parseInt(time.replace("s", "").trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static void update(FishingData.DeployableType type, int remaining, String name, FeatureConfigs.FishingAlerts c) {
            Tracked tracked = TRACKED.get(type);
            tracked.remaining = remaining;
            if (remaining <= 0) {
                if (remaining < 0) tracked.alerted = false;
                return;
            }
            int soon = Math.max(1, tracked.shortLived ? c.shortDeployableSeconds : c.deployableSeconds);
            if (remaining > soon) {
                tracked.alerted = false;
                return;
            }
            if (tracked.alerted) return;
            tracked.alerted = true;
            Component display = SbcItems.parseLegacy(name == null ? type.displayName : name);
            Sky2MAlerts.title(display.copy().append(Component.literal(" expires soon").withStyle(ChatFormatting.RED)), null);
            chat(Component.literal("Your ").withStyle(ChatFormatting.WHITE).append(display).append(Component.literal(" expires soon.").withStyle(ChatFormatting.WHITE)), true);
        }
    }
}
