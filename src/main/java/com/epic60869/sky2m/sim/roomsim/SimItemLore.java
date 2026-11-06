/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), part of the Dungeon Sim. MIT License,
 * Copyright (c) 2026 Killer560.
 */
package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.profileviewer.item.LegacyText;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Skyblock tooltips for the sim's items.
 *
 * <p>killer560 (2026-09-29): "can you make them have the same tooltip as main as well for all items the player
 * gets."
 *
 * <p>A sim item was a vanilla stack with a plain white name on it, so the tooltip read "Superboom TNT" and
 * under it "TNT". That is not the item he uses on Hypixel, and the tooltip is not decoration: the ability line
 * and the mana cost are what you check when you are learning what a class can do, and the rarity colour is how
 * you tell two items apart in a hotbar at a glance.
 *
 * <p><b>What is here and what is deliberately not.</b> These are the ability blocks and the rarity lines, which
 * are stable and well known. The numeric STAT blocks - damage, strength, crit - are not, and they change with
 * reforges, stars, enchantments and Hypixel's own balance passes. A tooltip that showed made-up stat numbers
 * would look more authentic and be worth less than one that shows none, so the stats are left out rather than
 * guessed at. If he wants them, they should come from the same place the item browser's data does.
 *
 * <p>Vanilla's own lines are hidden the way {@code profileviewer.item.LegacyItems} already hides them, so a
 * Superboom does not explain that TNT can be lit by flint and steel.
 */
public final class SimItemLore {

    private static final TooltipDisplay HIDE_VANILLA_LINES;

    static {
        SequencedSet<DataComponentType<?>> hidden = new LinkedHashSet<>();
        hidden.add(DataComponents.ATTRIBUTE_MODIFIERS);
        hidden.add(DataComponents.ENCHANTMENTS);
        hidden.add(DataComponents.STORED_ENCHANTMENTS);
        hidden.add(DataComponents.UNBREAKABLE);
        // The wardrobe's dyed leather pieces: Hypixel never shows vanilla's "Dyed" line.
        hidden.add(DataComponents.DYED_COLOR);
        HIDE_VANILLA_LINES = new TooltipDisplay(false, hidden);
    }

    /**
     * Name colour and lore per Skyblock id.
     *
     * <p>Keyed on the id in CUSTOM_DATA rather than on the sim's own enum, so anything that hands out an item -
     * the picker, a loadout, a secret drop, a wither key - gets the same tooltip without knowing about this
     * class.
     */
    private static final Map<String, String[]> LORE = Map.ofEntries(
        Map.entry("ASPECT_OF_THE_VOID", new String[]{
            "§5Aspect of the Void",
            "§6Ability: Instant Transmission  §e§lRIGHT CLICK",
            "§7Teleport §a12 blocks §7ahead of you",
            "§7and gain §a+50§f ✦ Speed §7for 3",
            "§7seconds.",
            "§8Mana Cost: §323",
            "",
            "§6Ability: Etherwarp  §e§lSNEAK RIGHT CLICK",
            "§7Teleport to the block you are",
            "§7looking at, up to §a61 blocks §7away.",
            "§8Mana Cost: §390",
            "",
            "§5§lEPIC SWORD",
        }),
        Map.entry("HYPERION", new String[]{
            "§6Hyperion",
            "§7Deals §c+50% §7damage to Withers.",
            "",
            "§6Ability: Wither Impact  §e§lRIGHT CLICK",
            "§7Teleport §a10 blocks §7ahead of you.",
            "§7Then implode dealing §c10,000 §7damage",
            "§7to nearby enemies. Also applies the",
            "§7wither shield scroll ability reducing",
            "§7damage taken and granting an",
            "§7absorption shield for §e5 §7seconds.",
            "§8Mana Cost: §3150",
            "§8Vitality Cost: §450",
            "§8Cooldown: §a0.15s",
            "",
            "§6§lLEGENDARY DUNGEON SWORD",
        }),
        Map.entry("BAT_WAND", new String[]{
            "§6Spirit Sceptre",
            "§6Ability: Guided Bat  §e§lRIGHT CLICK",
            "§7Shoots a guided spirit bat, following",
            "§7your aim and exploding for §c2,000",
            "§7damage.",
            "§8Mana Cost: §3100",
            "",
            "§6§lLEGENDARY WAND",
        }),
        Map.entry("TERMINATOR", new String[]{
            "§6Terminator",
            // THE LINE THREE AUTOS LOOK FOR. killer560 (2026-10-01): "the auto puzzles none were working except
            // auto blaze wanted to look towards the middle."
            //
            // That is this line's absence, exactly. Auto Creeper Beams, Auto Ice Path and Auto Blaze all gate
            // their shot on AutoPuzzleUtil.isShortbow, which is a lore search for "Shortbow: Instantly shoots!"
            // and nothing else - it is how Hypixel marks every shortbow, Terminator included - and this lore did
            // not have it. So all three ran their aim and then declined to fire, and Auto Blaze stopping with
            // the crosshair on the middle blaze is precisely what that looks like. AutoReposition's own
            // swap-to-a-bow (swapTo(isShortbow)) was failing for the same reason.
            "§6Shortbow: Instantly shoots!",
            "§6Ability: Salvation §e§lLEFT CLICK",
            "§7Shoots §a3 §7arrows at once.",
            "",
            "§6§lLEGENDARY BOW",
        }),
        Map.entry("SUPERBOOM_TNT", new String[]{
            "§9Superboom TNT",
            "§7Blast the fragile walls in §cThe",
            "§cCatacombs §7to open crypts and",
            "§7uncover secrets.",
            "",
            "§9§lRARE",
        }),
        Map.entry("ARCHITECT_FIRST_DRAFT", new String[]{
            "§5Architect's First Draft",
            "§6Ability: Solve  §e§lRIGHT CLICK",
            "§7Instantly completes the puzzle in",
            "§7the room you are standing in.",
            "§8Consumed on use",
            "",
            "§5§lEPIC",
        }),
        // Approximate tooltips: the sim's own text for what each one does in here.
        Map.entry("REVIVE_STONE", new String[]{
            "§9Revive Stone",
            "§6Ability: Revive  §e§lRIGHT CLICK",
            "§7While you are a ghost, brings you",
            "§7back to life after a short delay.",
            "§8Consumed on use",
            "",
            "§9§lRARE DUNGEON ITEM",
        }),
        Map.entry("BONZO_MASK", new String[]{
            "§9Bonzo's Mask",
            "§6Ability: Clownin' Around",
            "§7Instead of dying, gain §aDamage",
            "§aimmunity §7and §c+20 ❁ Strength §7for",
            "§a3s §7and fully replenish your Health.",
            "§8Cooldown: §a360s §8(-3.6s per Catacombs level)",
            "§8Only works in The Catacombs.",
            "",
            "§9§lRARE DUNGEON HELMET",
        }),
        Map.entry("SPIRIT_MASK", new String[]{
            "§5Spirit Mask",
            "§6Ability: Second Wind",
            "§7Instead of dying, gain §f+50 ✦ Speed",
            "§7and §adamage immunity §7for §a3 §7seconds.",
            "§8Cooldown: §a30s",
            "",
            "§5§lEPIC DUNGEON HELMET",
        }),
        Map.entry("TACTICAL_INSERTION", new String[]{
            "§9Tactical Insertion",
            "§6Ability: Insert  §e§lRIGHT CLICK",
            "§7Place a beacon. Use it again to",
            "§7teleport back to it.",
            "",
            "§9§lRARE",
        }),
        Map.entry("DUNGEONBREAKER", new String[]{
            "§cDungeonbreaker",
            "§7Breaks blocks in §cThe Catacombs §7for",
            "§7a charge each; they come back after",
            "§a10s§7. Regains §a2 §7charges a second.",
            "§7Not in puzzle rooms, on doors, on",
            "§7crypts or in most boss rooms.",
            "§8Charges: 20/20",
            "",
            "§c§lSPECIAL DUNGEON PICKAXE",
        }),
        Map.entry("ENDER_PEARL", new String[]{
            "§fEnder Pearl",
            "§7Throw it to teleport where it lands.",
            "",
            "§f§lCOMMON",
        }),
        Map.entry("WITHER_KEY", new String[]{
            "§6Wither Key",
            "§7Opens a §8Wither Door §7in",
            "§cThe Catacombs§7.",
            "",
            "§6§lLEGENDARY",
        }),
        Map.entry("DECOY", new String[]{
            "§aDecoy",
            "§7Places a decoy that draws mobs",
            "§7towards it.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("TRAP", new String[]{
            "§aTrap",
            "§7Slows down nearby enemies.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("DEFUSE_KIT", new String[]{
            "§aDefuse Kit",
            "§7Defuses a §cSuperboom §7trap.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("INFLATABLE_JERRY", new String[]{
            "§aInflatable Jerry",
            "§7Places an inflatable Jerry that",
            "§7distracts nearby mobs.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("TRAINING_WEIGHTS", new String[]{
            "§aTraining Weights",
            "§7Slows you down, but you hit harder.",
            "",
            "§a§lUNCOMMON",
        }),
        Map.entry("SPIRIT_LEAP", new String[]{
            "§9Spirit Leap",
            "§6Ability: Spirit Leap  §e§lRIGHT CLICK",
            "§7Allows you to teleport to any",
            "§7teammate! Immune for §a1s §7after,",
            "§7until you deal damage.",
            "§8Cooldown: §a5s",
            "§8Consumed on use",
            "",
            "§9§lRARE DUNGEON ITEM",
        }),
        Map.entry("INFINITE_SPIRIT_LEAP", new String[]{
            "§5InfiniLeap",
            "§6Ability: Spirit Leap  §e§lRIGHT CLICK",
            "§7Allows you to teleport to any",
            "§7teammate! Immune for §a1s §7after,",
            "§7until you deal damage.",
            "§8Cooldown: §a2s",
            "",
            "§5§lEPIC DUNGEON ITEM",
        }),
        // The class setups' items (SimWeapons, SimItemEffects, SimArrows): Hypixel's ability text from the NEU
        // tooltips and the wiki, with the Jun/Jul 2026 numbers. No stat blocks, as for the items above.
        Map.entry("LAST_BREATH", new String[]{
            "§6Last Breath",
            "§7Reduces the target's §a❈ Defense §7by",
            "§a10% §7of its max on hit, stacking up",
            "§7to §a5 §7times.",
            "",
            "§6§lLEGENDARY DUNGEON BOW",
        }),
        Map.entry("DEATH_BOW", new String[]{
            "§5Death Bow",
            "§7Deals §c+100% §7damage to §cUndead§7.",
            "§7Arrows have a §a50% §7chance to bounce",
            "§7to another target.",
            "",
            "§5§lEPIC DUNGEON BOW",
        }),
        Map.entry("AXE_OF_THE_SHREDDED", new String[]{
            "§6Halberd of the Shredded",
            "§7Heal §c25❤ §7per hit.",
            "§7Deal §c+250% §7damage to Undead.",
            "§7Receive §3+45 Combat Wisdom §7against",
            "§7Undead.",
            "",
            "§6Ability: Throw  §e§lRIGHT CLICK",
            "§7Throw your halberd, damaging all",
            "§7enemies in its path for §c10% §7of",
            "§7your melee damage. Each throw in a",
            "§7row costs §a2x §7mana and deals §a2x",
            "§7damage, up to §a16x§7.",
            "§8Mana Cost: §320",
            "",
            "§6§lLEGENDARY SWORD",
        }),
        Map.entry("DARK_CLAYMORE", new String[]{
            "§6Dark Claymore",
            "§7Swing Range: §e+2",
            "",
            "§6§lLEGENDARY DUNGEON LONGSWORD",
        }),
        Map.entry("SOUL_WHIP", new String[]{
            "§5Soul Whip",
            "§7Deals §c2x §7damage to Sea Creatures.",
            "",
            "§6Ability: Flay  §e§lRIGHT CLICK",
            "§7Flay your whip in an arc, dealing",
            "§7your melee damage to all enemies in",
            "§7its path. The first §a3 §7take full",
            "§7damage, the rest half.",
            "",
            "§5§lEPIC SWORD",
        }),
        Map.entry("FLAMING_FLAY", new String[]{
            "§6Flaming Flay",
            "§7Deals §c3x §7damage to Sea Creatures.",
            "",
            "§6Ability: Flay  §e§lRIGHT CLICK",
            "§7Flay your whip in an arc, dealing",
            "§7your melee damage to all enemies in",
            "§7its path. The first §a3 §7take full",
            "§7damage, the rest half. Heals §c1%",
            "§7per Sea Creature hit, up to §a25§7.",
            "",
            "§6§lLEGENDARY SWORD",
        }),
        Map.entry("RAGNAROCK_AXE", new String[]{
            "§9Ragnarock",
            "§6Ability: Ragnarock  §e§lRIGHT CLICK",
            "§7Begin a channel. After §a3s §7without",
            "§7taking damage, gain §a1.5x §7this",
            "§7weapon's §c❁ Strength §7for §a10s§7.",
            "§7Taking damage interrupts the channel.",
            "§8Mana Cost: §3500",
            "§8Cooldown: §a20s",
            "",
            "§9§lRARE SWORD",
        }),
        Map.entry("SWORD_OF_BAD_HEALTH", new String[]{
            "§aSword of Bad Health",
            "§6Ability: Bad Health  §e§lRIGHT CLICK",
            "§7Gain §c+5 ❁ Strength §7for every §a5%",
            "§7of your total §c❤ Health §7for §a5s§7,",
            "§7up to §c+100 ❁ Strength§7.",
            "§8Health Cost: §c100",
            "§8Cooldown: §a5s",
            "",
            "§a§lUNCOMMON SWORD",
        }),
        Map.entry("ROGUE_SWORD", new String[]{
            "§fRogue Sword",
            "§6Ability: Speed Boost  §e§lRIGHT CLICK",
            "§7Grants §f+100 ✦ Speed §7for §a30s§7.",
            "§8Mana Cost: §350",
            "§8Cooldown: §a5s",
            "",
            "§f§lCOMMON SWORD",
        }),
        Map.entry("WITHER_CLOAK", new String[]{
            "§5Wither Cloak Sword",
            "§6Ability: Creeper Veil  §e§lRIGHT CLICK",
            "§7Spawn a layered veil that negates",
            "§7damage for §a10s§7. Each hit removes a",
            "§7layer, blocking up to §c1,000 §7damage.",
            "§7You can't attack or regain §4Vitality",
            "§7while it is up. Right click again to",
            "§7remove it and halve the cooldown.",
            "§8Vitality Cost: §430 §8per layer",
            "§8Cooldown: §a10s",
            "",
            "§5§lEPIC DUNGEON SWORD",
        }),
        Map.entry("SOUL_ESOWARD", new String[]{
            "§9Soul Esoward",
            "§6Ability: Soulward  §e§lRIGHT CLICK",
            "§7Become invulnerable for §a5s§7, but you",
            "§7cannot deal damage. Your damage is",
            "§7halved for §a2s §7after. Does not",
            "§7block §fTrue Damage§7.",
            "§8Mana Cost: §3350",
            "§8Soulflow Cost: §310⸎",
            "§8Cooldown: §a20s",
            "",
            "§9§lRARE",
        }),
        Map.entry("GLOOMLOCK_GRIMOIRE", new String[]{
            "§5Gloomlock Grimoire",
            "§6Ability: Life Tap  §e§lLEFT CLICK",
            "§7Convert §315⸎ Soulflow §7into up to",
            "§b600 Overflow Mana§7.",
            "§8Health Cost: §c800",
            "§8Cooldown: §a1s",
            "",
            "§6Ability: Extreme Measures  §e§lRIGHT CLICK",
            "§7Heal §c1,000❤ Health§7.",
            "§8Vitality Cost: §445",
            "",
            "§5§lEPIC",
        }),
        Map.entry("BONZO_STAFF", new String[]{
            "§9Bonzo's Staff",
            "§6Ability: Showtime  §e§lRIGHT CLICK",
            "§7Shoot balloons that explode and",
            "§7deal up to §c1,000 §7damage to nearby",
            "§7mobs, and knock you back if you are",
            "§7in the blast.",
            "§8Mana Cost: §390",
            "",
            "§9§lRARE DUNGEON SWORD",
        }),
        Map.entry("JERRY_STAFF", new String[]{
            "§5Jerry-chine Gun",
            "§6Ability: Rapid-fire  §e§lRIGHT CLICK",
            "§7Fire a bullet dealing §c500 §7damage.",
            "§7Knocks you back if it lands near you.",
            "§7Each shot costs §b+30 Mana §7more,",
            "§7resetting after §a4s§7.",
            "§8Mana Cost: §330",
            "",
            "§5§lEPIC SWORD",
        }),
        Map.entry("ICE_SPRAY_WAND", new String[]{
            "§9Ice Spray Wand",
            "§6Ability: Ice Spray  §e§lRIGHT CLICK",
            "§7Produce a cone of ice in front of",
            "§7you, dealing §c17,000 §7damage and",
            "§7freezing enemies for §a5s§7. Frozen",
            "§7enemies take §c10% §7more damage.",
            "§8Mana Cost: §350",
            "§8Cooldown: §a5s",
            "",
            "§9§lRARE DUNGEON SWORD",
        }),
        Map.entry("GYROKINETIC_WAND", new String[]{
            "§5Gyrokinetic Wand",
            "§6Ability: Gravity Storm  §e§lLEFT CLICK",
            "§7Create a rift that pulls mobs within",
            "§a10 §7blocks to its centre and holds",
            "§7them for §a4s§7. Your mana regenerates",
            "§a10x §7slower for §a3s§7.",
            "§8Mana Cost: §31,200",
            "§8Soulflow Cost: §310⸎",
            "§8Cooldown: §a30s",
            "",
            "§6Ability: Cells Alignment  §e§lRIGHT CLICK",
            "§7Apply §dAligned §7to you and §a4 §7nearby",
            "§7players for §a6s§7, splitting the",
            "§7damage they take over §a3s§7.",
            "§8Mana Cost: §3220",
            "§8Soulflow Cost: §32⸎",
            "§8Cooldown: §a10s",
            "",
            "§5§lEPIC WAND",
        }),
        Map.entry("WEIRDER_TUBA", new String[]{
            "§5Weirder Tuba",
            "§6Ability: Howl  §e§lRIGHT CLICK",
            "§7You and §a4 §7nearby players gain",
            "§c+40 ❁ Strength§7, §e+5 ⚔ Attack Speed§7,",
            "§9+10 ☠ Crit Damage §7and §f+30 ✦ Speed",
            "§7for §a30s§7. Does not stack.",
            "§8Mana Cost: §3150",
            "§8Cooldown: §a20s",
            "",
            "§5§lEPIC",
        }),
        Map.entry("SPRING_BOOTS", new String[]{
            "§9Spring Boots",
            "§7Feather Falling XX",
            "",
            "§6Ability: To the moon!  §e§lSNEAK",
            "§7Charge your jump by sneaking. The",
            "§7longer you sneak, the higher you",
            "§7will jump.",
            "",
            "§9§lRARE BOOTS",
        }),
        Map.entry("MENDER_CROWN", new String[]{
            "§5Empowered Mender Crown",
            "§7Grants §a+10 Mending §7while in",
            "§7Dungeons. §8(Empowered)",
            "",
            "§5§lEPIC DUNGEON HELMET",
        }),
        Map.entry("BLAZETEKK_HAM_RADIO", new String[]{
            "§6Blazetekk Ham Radio",
            "§6Ability: Telecommunications",
            "§7If exactly §atwo §7players within §a110",
            "§7blocks are on the same channel, both",
            "§7gain §a+60 ❈ Defense§7, §f+40 ❂ True",
            "§fDefense§7, §3+5 Combat Wisdom §7and",
            "§4+10 Vitality§7. Earn half stats while",
            "§7alone!",
            "",
            "§6Ability: Tuning 4 Dummies  §e§lRIGHT CLICK",
            "§7Switch to the next channel.",
            "",
            "§6§lLEGENDARY",
        }),
        Map.entry("ALERT_FLARE", new String[]{
            "§9Alert Flare",
            "§7Deploy for §a3m§7, buffing up to §a8",
            "§7players within §a40 §7blocks:",
            "§b+50% §7base Mana regen, §4+15 Vitality§7,",
            "§f+20 ❂ True Defense§7, §c+10 ⫽ Ferocity§7.",
            "§8Mana Cost: §3300",
            "§8Vitality Cost: §410",
            "§8Cooldown: §a20s",
            "",
            "§9§lRARE DEPLOYABLE",
        }),
        Map.entry("SOS_FLARE", new String[]{
            "§5SOS Flare",
            "§7Deploy for §a3m§7, buffing up to §a8",
            "§7players within §a40 §7blocks:",
            "§b+125% §7base Mana regen, §4+25 Vitality§7,",
            "§f+30 ❂ True Defense§7, §c+10 ⫽ Ferocity§7,",
            "§e+5 ⚔ Attack Speed§7.",
            "§8Mana Cost: §3250",
            "§8Vitality Cost: §410",
            "§8Cooldown: §a10s",
            "",
            "§5§lEPIC DEPLOYABLE",
        }),
        Map.entry("JALAPENO_BOOK", new String[]{
            "§5Jalapeno Book",
            "§7Apply to a Deployable to give it",
            "§9+5 ☠ Crit Damage §7and §9+1 ☣ Crit",
            "§9Chance §7while deployed.",
            "§8Right click: apply to a flare you carry.",
            "",
            "§5§lEPIC",
        }),
        Map.entry("ARROW", new String[]{
            "§fFlint Arrow",
            "§7Damage: §c+1",
            "",
            "§f§lCOMMON ARROW",
        }),
        Map.entry("BOUNCY_ARROW", new String[]{
            "§9Bouncy Arrow",
            "§7Arrows have a §a50% §7chance to bounce",
            "§7to another target. Bounces up to §a4",
            "§7times, each dealing §c15% §7less damage.",
            "",
            "§9§lRARE ARROW",
        }),
        Map.entry("ARMORSHRED_ARROW", new String[]{
            "§9Armorshred Arrow",
            "§7Hits ignore §a5% §7of the target's",
            "§a❈ Defense§7.",
            "",
            "§9§lRARE ARROW",
        }),
        Map.entry("NANSORB_ARROW", new String[]{
            "§9Nansorb Arrow",
            "§7Replaces the damage reduction from",
            "§7the §9Piercing §7enchant with §aSkewer§7:",
            "§7each pierced hit deals §c+7.5% §7more",
            "§7than the last, up to §a5 §7victims.",
            "",
            "§9§lRARE ARROW",
        }),
        Map.entry("TWILIGHT_ARROW_POISON", new String[]{
            "§aTwilight Arrow Poison",
            "§7Arrows apply a Poison that increases",
            "§7all incoming damage by §c10% §7for §a20s§7.",
            "§8Consumed on arrow shot. Does not work",
            "§8with the Skeleton Master Chestplate.",
            "",
            "§a§lUNCOMMON ARROW POISON",
        }),
        Map.entry("INFINITE_TWILIGHT_ARROW_POISON", new String[]{
            "§dInfinite Twilight Arrow Poison",
            "§7Arrows apply a Poison that increases",
            "§7all incoming damage by §c10% §7for §a20s§7.",
            "§dNever runs out. §8(Dungeon Sim only)",
            "§8Does not work with the Skeleton",
            "§8Master Chestplate.",
            "",
            "§a§lUNCOMMON ARROW POISON",
        }),
        Map.entry("TOXIC_ARROW_POISON", new String[]{
            "§aToxic Arrow Poison",
            "§7Arrows deal §c+10% §7damage and reduce",
            "§7mob healing by §c24% §7over §a4s§7.",
            "§8Consumed on arrow shot. Does not work",
            "§8with the Skeleton Master Chestplate.",
            "",
            "§a§lUNCOMMON ARROW POISON",
        }),
        // The class item (SimClassKit). The lore names what the clicks do rather than the class's spells: the
        // table is the same for every class and the refresh below rewrites a stack to it every second.
        Map.entry(SimClassKit.ORB_ID, new String[]{
            "§aDungeon Orb",
            "§6Class Ability  §e§lRIGHT CLICK",
            "§7Uses your class's ability.",
            "",
            "§6Ultimate  §e§lLEFT CLICK §7or §e§lDROP",
            "§7Uses your class's Ultimate. The drop",
            "§7key casts it with any item in hand.",
            "",
            "§7Change class with §e/simclass§7.",
            "",
            "§a§lDUNGEON ITEM",
        })
    );

    private SimItemLore() {
    }

    /**
     * Puts a name and lore on a stack that is not in the table - the wardrobe's armour pieces ({@link SimGear}),
     * whose lines are built from their stats. Vanilla's own lines (leather armour's Defense) are hidden the same way.
     */
    static void applyCustom(ItemStack stack, String name, List<String> lore) {
        stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(name));
        List<Component> lines = new ArrayList<>(lore.size());
        for (String l : lore) {
            lines.add(LegacyText.parse(l));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines, lines));
        stack.set(DataComponents.TOOLTIP_DISPLAY, HIDE_VANILLA_LINES);
    }

    /**
     * Puts the Skyblock name and lore on a stack.
     *
     * <p>The first line of the table is the NAME, which is where the rarity colour lives - an item's colour is
     * how you find it in a hotbar, and a white "Hyperion" is not a Hyperion. Unknown ids are left alone rather
     * than given an invented tooltip.
     *
     * @return true when a tooltip was applied
     */
    public static boolean apply(ItemStack stack, String skyblockId) {
        if (stack == null || skyblockId == null) {
            return false;
        }
        String[] text = LORE.get(skyblockId.toUpperCase(Locale.ROOT));
        if (text == null || text.length < 2) {
            return false;
        }
        stack.set(DataComponents.CUSTOM_NAME, LegacyText.parse(text[0]));
        List<Component> lines = new ArrayList<>(text.length - 1);
        for (int i = 1; i < text.length; i++) {
            lines.add(LegacyText.parse(text[i]));
        }
        stack.set(DataComponents.LORE, new ItemLore(lines, lines));
        stack.set(DataComponents.TOOLTIP_DISPLAY, HIDE_VANILLA_LINES);
        return true;
    }

    // ------------------------------------------------------------------ stale tooltips in a reused save

    private static int refreshCounter;

    /**
     * Keeps every sim item the player carries on the CURRENT tooltip, the way Hypixel's server re-sends an item's
     * lore whenever it changes.
     *
     * <p>killer560's log, 2026-10-04 18:07: "[AutoIcePath] waiting: no shortbow ("Shortbow: Instantly shoots!" in
     * its lore) in the hotbar", over and over, with a Terminator in slot 5. The sim's save is reused
     * ({@link SimWorld}), so the player's inventory is too, and the Terminator in it had been built before the
     * Shortbow line was added on 2026-10-01: its saved lore, read out of the world's player file, is "Ability:
     * Salvation / Shoots 3 arrows at once. / (blank) / LEGENDARY BOW" - no Shortbow line. Fixing the table fixed
     * every Terminator handed out after that, and none already in his hotbar. Every 20 server ticks while the sim
     * is active, any stack whose id is in the table and whose lore reads differently from the table gets the table's
     * tooltip again. The Dungeon Breaker's "Charges" line is the one line the server changes on purpose
     * ({@link SimBreakerState}), so it is left out of the comparison and kept.
     */
    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++refreshCounter % 20 != 0 || !SimState.isActive()) {
                return;
            }
            for (net.minecraft.server.level.ServerPlayer sp : server.getPlayerList().getPlayers()) {
                for (ItemStack stack : sp.getInventory().getNonEquipmentItems()) {
                    refreshIfStale(stack);
                }
            }
        });
    }

    /** @return true when the stack's tooltip was out of date and has been rewritten. Server thread. */
    static boolean refreshIfStale(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String id = com.epic60869.sky2m.sim.util.SimItemIds.skyblockId(stack);
        if (id == null) {
            return false;
        }
        // A stack carrying an item build has its tooltip made from the build (SimItemData.applyLore): leave it.
        net.minecraft.world.item.component.CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data != null && data.copyTag().contains(SimItemBuild.BUILD_MARK)) {
            return false;
        }
        String[] text = LORE.get(id.toUpperCase(Locale.ROOT));
        if (text == null || text.length < 2) {
            return false;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        List<Component> have = lore == null ? List.of() : lore.lines();
        boolean stale = have.size() != text.length - 1;
        String keptCharges = null;
        for (int i = 0; !stale && i < have.size(); i++) {
            String got = have.get(i).getString();
            String want = LegacyText.strip(text[i + 1]);
            if (got.equals(want)) {
                continue;
            }
            if (got.startsWith("Charges: ") && want.startsWith("Charges: ")) {
                continue;
            }
            stale = true;
        }
        if (!stale) {
            return false;
        }
        for (Component line : have) {
            if (line.getString().startsWith("Charges: ")) {
                keptCharges = line.getString();
            }
        }
        apply(stack, id);
        if (keptCharges != null) {
            ItemLore fresh = stack.get(DataComponents.LORE);
            if (fresh != null) {
                List<Component> lines = new ArrayList<>(fresh.lines());
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).getString().startsWith("Charges: ")) {
                        lines.set(i, LegacyText.parse("§8" + keptCharges));
                    }
                }
                stack.set(DataComponents.LORE, new ItemLore(lines, lines));
            }
        }
        return true;
    }

    /** Whether there is a tooltip for this id at all - for tests, which should not assert on a typo. */
    public static boolean knows(String skyblockId) {
        return skyblockId != null && LORE.containsKey(skyblockId.toUpperCase(Locale.ROOT));
    }
}
