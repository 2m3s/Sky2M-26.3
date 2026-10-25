package com.epic60869.sky2m.features;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.sb.utils.waypoint.Waypoint;
import com.epic60869.sky2m.features.fishing.FishingData;
import com.google.gson.annotations.Expose;
import java.util.ArrayList;
import java.util.List;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDraggableList;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * Config sections for Sky2M's skill features. They are referenced as categories or
 * accordions from {@link com.epic60869.sky2m.Sky2MConfig}. HUD positions and scales are
 * set in /s2 gui and stored in sky2m-huds.json.
 */
// Uses the deprecated old-style accordions on purpose, like Sky2MConfig (moving would reset saved settings).
@SuppressWarnings("deprecation")
public final class FeatureConfigs {
    public static final class Combat {

        @ConfigOption(name = "HUDs & Counters", desc = "Arrow counter, Zealot tracker, Legion display and armor stacks.")
        @ConfigEditorAccordion(id = 301)
        public boolean hudsCountersGroup = false;

        @Expose
        @ConfigOption(name = "Arrow Counter", desc = "HUD showing the selected arrow type and how many arrows are left in your quiver.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 301)
        public boolean arrowCounter = false;

        @Expose
        @ConfigOption(name = "Arrow Counter Only With Bow", desc = "Only show the Arrow Counter while you're holding a bow (or crossbow).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 301)
        public boolean arrowCounterBowOnly = true;

        @Expose
        @ConfigOption(name = "Zealot Tracker", desc = "Tracker for the Zealots you kill in the End and the Summoning Eyes you drop, for this session or in total.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 301)
        public boolean zealotCounter = false;

        @Expose
        @ConfigOption(name = "Legion Display", desc = "HUD showing how many players are within Legion range (30 blocks).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 301)
        public boolean legionDisplay = false;

        @Expose
        @ConfigOption(name = "Armor Stack Display", desc = "HUD showing your Crimson, Terror, Aurora or Fervor armor stacks from the action bar, with the time until the next stack decays.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 301)
        public boolean armorStackDisplay = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Boss Damage Indicator", desc = "Health, DPS, time left, hits and heal messages for the boss you are fighting.")
        public com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.BossIndicator bossIndicator = new com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.BossIndicator();

        @Expose
        @Accordion
        @ConfigOption(name = "Ghost Counter", desc = "Ghost kills and drop profit in the Mist of the Dwarven Mines (SkyHanni).")
        public com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.GhostCounter ghostCounter = new com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.GhostCounter();

        @Expose
        @Accordion
        @ConfigOption(name = "End Dragons", desc = "Dragon profit tracker, weight and Superior Dragon notification (SkyHanni).")
        public com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.Dragons dragons = new com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.Dragons();

        @Expose
        @Accordion
        @ConfigOption(name = "Ender Node Tracker", desc = "Ender Nodes mined and their loot in the End (SkyHanni).")
        public com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.EnderNodes enderNodes = new com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.EnderNodes();

        @Expose
        @Accordion
        @ConfigOption(name = "Area Bosses", desc = "Highlight, spawn alert and timer for Boss Corleone, Arachne's Keepers and Zealot Bruisers.")
        public com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.AreaBosses areaBosses = new com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.AreaBosses();

        @Expose
        @Accordion
        @ConfigOption(name = "Cocoon Alert", desc = "Alert when you cocoon a mob.")
        public CocoonAlert cocoonAlert = new CocoonAlert();

        @Expose
        @Accordion
        @ConfigOption(name = "Rare Drops", desc = "Copy rare drops and animate big ones.")
        public RareDrops rareDrops = new RareDrops();

        @Expose
        @Accordion
        @ConfigOption(name = "Bestiary Overlay", desc = "SkyHanni's Bestiary Data overlay in the Bestiary menu.")
        public Bestiary bestiary = new Bestiary();

        @Expose
        @Accordion
        @ConfigOption(name = "Damage Numbers", desc = "Short damage numbers (1.2M), and a HUD adding up the damage around you.")
        public DamageNumbers damageNumbers = new DamageNumbers();

        @Expose
        @Accordion
        @ConfigOption(name = "Low Health & Mana", desc = "A warning when your health or mana drops below a percentage.")
        public LowStats lowStats = new LowStats();
    }

    /** Crimson Isle: Kuudra and Ashfang. */
    public static final class CrimsonIsle {
        @Expose
        @Accordion
        @ConfigOption(name = "Kuudra", desc = "Supply and pile waypoints, pre-spot alerts, phase splits, chest profit, Fresh Tools, Kuudra's health and the Perk Menu cleaner.")
        public com.epic60869.sky2m.features.combat.kuudra.KuudraConfig kuudra = new com.epic60869.sky2m.features.combat.kuudra.KuudraConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Ashfang", desc = "Ashfang's blazes, Blazing Souls, Gravity Orbs, freeze cooldown and next reset (SkyHanni).")
        public com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.Ashfang ashfang = new com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.Ashfang();
    }

    /** SkyHanni's BestiaryConfig (LGPL-2.1). The overlay is moved and resized in /s2 gui. */
    public static final class Bestiary {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the Bestiary Data overlay in the Bestiary menu: each family's kills and progress to max or to the next tier, sorted how you choose, and each category's families found and completed. Maxed families and categories are highlighted green, and with Overall Progress hidden its Eye of Ender is highlighted red. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Number Format", desc = "Short: 1.1k\nLong: 1,100")
        @ConfigEditorDropdown
        public NumberFormat numberFormat = NumberFormat.SHORT;

        @Expose
        @ConfigOption(name = "Display Type", desc = "What the overlay shows for each family, and how it's sorted. Fewest/Most Kills: your kills. Fewest/Most to Max: kills left to max. Fewest/Most Next: kills left to the next tier. Global Max / Global Next: closest to max or the next tier, by percent.")
        @ConfigEditorDropdown
        public DisplayType displayType = DisplayType.GLOBAL_MAX;

        @Expose
        @ConfigOption(name = "Hide Maxed", desc = "Hide maxed families.")
        @ConfigEditorBoolean
        public boolean hideMaxed = false;

        @Expose
        @ConfigOption(name = "Replace Romans", desc = "Show tiers as regular numbers (9) instead of Roman numerals (IX).")
        @ConfigEditorBoolean
        public boolean replaceRoman = false;

        public enum NumberFormat {
            SHORT("Short"), LONG("Long");

            private final String label;

            NumberFormat(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum DisplayType {
            GLOBAL_MAX("Global to max"),
            GLOBAL_NEXT("Global Next"),
            LOWEST_TOTAL("Fewest Kills"),
            HIGHEST_TOTAL("Most Kills"),
            LOWEST_MAX("Fewest to Max"),
            HIGHEST_MAX("Most to Max"),
            LOWEST_NEXT("Fewest Next"),
            HIGHEST_NEXT("Most Next");

            private final String label;

            DisplayType(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }
    }

    /** Diana burrow detection and guesses, ported from SkyBlock Overhaul. */
    public static final class DianaBurrows {
        @Expose
        @ConfigOption(name = "Close Burrow Detection", desc = "Detects burrow locations when being close to them from the particles when holding shovel to register/update it as a Treasure, Mob or Start burrow. Needs Critical Hit and Enchant particles, and set /particlequality extreme. To reset waypoints type /s2 clearburrows.")
        @ConfigEditorBoolean
        public boolean closeBurrowDetection = false;

        @Expose
        @ConfigOption(name = "Arrow Guess", desc = "Guesses the burrow location from the arrow direction after digging a burrow.\n§cHave Dust and Smoke Particles enabled and /particlequality extreme!\n§aDo every burrow you see and Use Spade when the mod tells you to for doing Diana the fastest way!")
        @ConfigEditorBoolean
        public boolean arrowGuess = false;

        @Expose
        @ConfigOption(name = "Spade Guess", desc = "Guess the burrow location when using spade ability. Needs Dripping Lava Particles and set /particlequality to Extreme for more accuracy.")
        @ConfigEditorBoolean
        public boolean spadeGuess = false;

        @Expose
        @ConfigOption(name = "Multi Guesses", desc = "Remember previous guess locations when guessing to a new location. Off: a new arrow or spade guess replaces the old ones.")
        @ConfigEditorBoolean
        public boolean multiGuesses = false;

        @Expose
        @ConfigOption(name = "Keep Waypoints on World Change", desc = "Keep your burrow and guess waypoints when you change servers (burrows are yours, so they're in the same places on every Hub). Off: they're cleared.")
        @ConfigEditorBoolean
        public boolean keepOnWorldChange = false;

        @Expose
        @ConfigOption(name = "Show Beacon Beam", desc = "Shows a beacon beam for waypoints going to the sky if enabled.")
        @ConfigEditorBoolean
        public boolean beaconBeam = false;

        @Expose
        @ConfigOption(name = "Show Progress", desc = "Show burrow click progress.")
        @ConfigEditorBoolean
        public boolean showProgress = true;

        @Expose
        @ConfigOption(name = "Progress Position", desc = "Where to show burrow click progress.")
        @ConfigEditorDropdown
        public ProgressPosition progressPosition = ProgressPosition.RIGHT;

        public enum ProgressPosition {
            RIGHT("Right"), LEFT("Left"), ABOVE("Above"), BELOW("Below");

            private final String label;

            ProgressPosition(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @Expose
        @ConfigOption(name = "Guess Colour", desc = "Border (and beam) colour of arrow and spade guesses.")
        @ConfigEditorColour
        public String guessColour = "0:255:170:0:255";

        @Expose
        @ConfigOption(name = "Mob Colour", desc = "Border (and beam) colour of mob burrows.")
        @ConfigEditorColour
        public String mobColour = "0:255:255:85:85";

        @Expose
        @ConfigOption(name = "Treasure Colour", desc = "Border (and beam) colour of treasure burrows.")
        @ConfigEditorColour
        public String treasureColour = "0:255:255:170:0";

        @Expose
        @ConfigOption(name = "Start Colour", desc = "Border (and beam) colour of start burrows.")
        @ConfigEditorColour
        public String startColour = "0:255:85:255:85";
    }

    public static final class DianaProfitTracker {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the Diana profit tracker HUD in the Hub while you're doing Diana (and for 10 minutes after).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show", desc = "Which totals the HUD shows: this session, this mayor term (Diana's whole season) or all time. Also /s2 dianatracker session|season|alltime, and /s2 dianatracker reset to reset the one shown.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.combat.DianaProfitTracker.Period period = com.epic60869.sky2m.features.combat.DianaProfitTracker.Period.SESSION;
    }

    public static final class DianaLobbyCompromised {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Alert when too many non-party players join the lobby.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Stranger Limit", desc = "Non-party players before alerting.")
        @ConfigEditorSlider(minValue = 2, maxValue = 12, minStep = 1)
        public int strangerLimit = 10;

        @Expose
        @ConfigOption(name = "Title Alert", desc = "A \"Lobby compromised!\" title and a bell.")
        @ConfigEditorBoolean
        public boolean titleAlert = true;

        @Expose
        @ConfigOption(name = "Chat Alert", desc = "Say \"Lobby compromised!\" in party chat.")
        @ConfigEditorBoolean
        public boolean chatAlert = false;
    }

    /** SkyHanni's Mythological Creature Tracker, with SBO's lootshare counts. */
    public static final class DianaMobTracker {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show how many of each mythological creature you dug up, how many creatures since each rare one and your mobs per hour, in the Hub while you do Diana. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show", desc = "This session, this mayor term (Diana's season) or all time. Also /s2 mobtracker session|season|alltime, and /s2 mobtracker reset to reset the one shown.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.combat.DianaProfitTracker.Period period = com.epic60869.sky2m.features.combat.DianaProfitTracker.Period.SESSION;

        @Expose
        @ConfigOption(name = "Show Percentage", desc = "Each creature's share of all the creatures you dug up.")
        @ConfigEditorBoolean
        public boolean showPercentage = true;

        @Expose
        @ConfigOption(name = "Show Lootshare", desc = "How many of each rare mob you lootshared, after it (SBO's [LS: n]).")
        @ConfigEditorBoolean
        public boolean showLootshare = true;

        @Expose
        @ConfigOption(name = "Creatures Since in Chat", desc = "Add how many creatures you dug up since the last one of that kind to Hypixel's \"You dug out a ...\" message, like SkyHanni.")
        @ConfigEditorBoolean
        public boolean sinceInChat = true;
    }

    /** Which RNG drops Rare Drop Announcer says in party chat. */
    public static final class DianaPartyDrops {
        @Expose @ConfigOption(name = "Chimera", desc = "") @ConfigEditorBoolean public boolean chimera = true;
        @Expose @ConfigOption(name = "Shimmering Wool", desc = "") @ConfigEditorBoolean public boolean wool = true;
        @Expose @ConfigOption(name = "Manti-core", desc = "") @ConfigEditorBoolean public boolean core = true;
        @Expose @ConfigOption(name = "Fateful Stinger", desc = "") @ConfigEditorBoolean public boolean stinger = true;
        @Expose @ConfigOption(name = "Brain Food", desc = "") @ConfigEditorBoolean public boolean brainFood = true;
        @Expose @ConfigOption(name = "Daedalus Stick", desc = "") @ConfigEditorBoolean public boolean stick = true;
        @Expose @ConfigOption(name = "Minos Relic", desc = "") @ConfigEditorBoolean public boolean relic = true;
        @Expose @ConfigOption(name = "Crown of Greed", desc = "") @ConfigEditorBoolean public boolean crown = true;
        @Expose @ConfigOption(name = "Mythological Dye", desc = "") @ConfigEditorBoolean public boolean dye = true;
        @Expose @ConfigOption(name = "Myth the Fish", desc = "") @ConfigEditorBoolean public boolean mythFish = true;
        @Expose @ConfigOption(name = "Braided Griffin Feather", desc = "") @ConfigEditorBoolean public boolean braided = true;
    }

    /** SBO's loot announcer and since messages. */
    public static final class DianaDrops {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Announce your Diana RNG drops (Chimera, Wool, Manti-core, Stinger, Brain Food, Stick, Relic, ...) with how many you've had this season and their price, like SBO.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Send to Party", desc = "Say the drop in party chat: \"RARE DROP! Chimera (+304 ✯ Magic Find) #3 (+95.2M coins)\". Pick the drops below.")
        @ConfigEditorBoolean
        public boolean sendToParty = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Party Drops", desc = "Which drops Send to Party says in party chat.")
        public DianaPartyDrops partyDrops = new DianaPartyDrops();

        @Expose
        @ConfigOption(name = "Chat Message", desc = "Your own RARE DROP! line with the drop's count this season (and lootshare count) and price.")
        @ConfigEditorBoolean
        public boolean chatMessage = true;

        @Expose
        @ConfigOption(name = "All Drops in Chat", desc = "Also the small drops (Hilt, Urn, Souvenir, Shelmet, Plushie, Remedies, Feathers, Fragments) in the chat message.")
        @ConfigEditorBoolean
        public boolean allDrops = false;

        @Expose
        @ConfigOption(name = "Title", desc = "Show the drop and its price as a title.")
        @ConfigEditorBoolean
        public boolean title = true;

        @Expose
        @ConfigOption(name = "Crown of Greed Title", desc = "Show a title for Crown of Greed too.")
        @ConfigEditorBoolean
        public boolean crownTitle = false;

        @Expose
        @ConfigOption(name = "Sound", desc = "Play a sound for drops without a title.")
        @ConfigEditorBoolean
        public boolean sound = true;

        @Expose
        @ConfigOption(name = "Since Messages", desc = "\"Took 12 Inquisitors to get Chimera!\", \"Took 240 mobs and 1h 2m to get an Inquisitor!\" and \"b2b Chimera!\" in chat.")
        @ConfigEditorBoolean
        public boolean sinceMessages = true;

        @Expose
        @ConfigOption(name = "Announce Cocoon", desc = "Say \"Cocooned a Minos Inquisitor!\" in party chat when you cocoon a rare mob.")
        @ConfigEditorBoolean
        public boolean announceCocoon = false;
    }

    /** SBO's mythos mob HP overlay. */
    public static final class DianaMobHealth {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the nametags (health) of the Diana mobs near you, and King Minos's hits left, on a HUD. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Low HP Alert (millions)", desc = "A \"HP LOW!\" title when an Inquisitor, King Minos, Manticore or Sphinx gets below this much health, in millions. 0: off.")
        @ConfigEditorSlider(minValue = 0, maxValue = 50, minStep = 0.5f)
        public float lowHpAlert = 0;

        @Expose
        @ConfigOption(name = "Low HP Sound", desc = "Also play a bell with the Low HP Alert.")
        @ConfigEditorBoolean
        public boolean lowHpSound = true;
    }

    /** SBO's Diana achievements. */
    public static final class DianaAchievements {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Unlock SBO's Diana achievements (b2b Chimera, 5k burrows in one event, 100 Inquisitors since Chimera, ...) with a title and a chat message.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Repeat Each Event", desc = "Achievements can be earned again every Diana season (except the first-time and bestiary ones).")
        @ConfigEditorBoolean
        public boolean repeatEachEvent = false;

        @ConfigOption(name = "Achievements", desc = "See your achievements. Also /s2 achievements (/s2 achievements backtrack checks your saved seasons, /s2 achievements lock CONFIRM resets them).")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton(buttonText = "OPEN")
        public Runnable open = com.epic60869.sky2m.features.combat.DianaAchievements::open;
    }

    /** Which rare mobs Rare Mob Sharing sends in party chat and picks up from it. */
    public static final class DianaSharedMobs {
        @Expose @ConfigOption(name = "Minos Inquisitor", desc = "Share and receive Minos Inquisitors.") @ConfigEditorBoolean public boolean inquisitor = true;
        @Expose @ConfigOption(name = "King Minos", desc = "Share and receive King Minos.") @ConfigEditorBoolean public boolean kingMinos = true;
        @Expose @ConfigOption(name = "Manticore", desc = "Share and receive Manticores.") @ConfigEditorBoolean public boolean manticore = true;
        @Expose @ConfigOption(name = "Sphinx", desc = "Share and receive Sphinxes.") @ConfigEditorBoolean public boolean sphinx = false;
    }

    /** The Diana warp keys (SBO's): set them in Options > Controls > Key Binds, under Sky2M. */
    public static final class DianaWarp {
        @ConfigOption(name = "Diana Warp", desc = "You must configure the warp keys from vanilla Minecraft settings, under (ESC) -> Options -> Controls -> Key Binds... scroll till you find Sky2M and configure it from there.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorInfoText
        public boolean info = false;

        @Expose @ConfigOption(name = "Warp: Castle", desc = "Let the warp keys use /warp castle.") @ConfigEditorBoolean public boolean castle = true;
        @Expose @ConfigOption(name = "Warp: Wizard", desc = "Let the warp keys use /warp wizard.") @ConfigEditorBoolean public boolean wizard = true;
        @Expose @ConfigOption(name = "Warp: Dark Auction", desc = "Let the warp keys use /warp da.") @ConfigEditorBoolean public boolean da = true;
        @Expose @ConfigOption(name = "Warp: Crypt", desc = "Let the warp keys use /warp crypt.") @ConfigEditorBoolean public boolean crypt = false;
        @Expose @ConfigOption(name = "Warp: Stonks", desc = "Let the warp keys use /warp stonks.") @ConfigEditorBoolean public boolean stonks = false;
        @Expose @ConfigOption(name = "Warp: Taylor", desc = "Let the warp keys use /warp taylor.") @ConfigEditorBoolean public boolean taylor = false;
        @Expose @ConfigOption(name = "Warp: Museum", desc = "Let the warp keys use /warp museum.") @ConfigEditorBoolean public boolean museum = false;

        @Expose
        @ConfigOption(name = "Don't Warp If Close", desc = "The warp key won't warp you if you are already within 60 blocks of the target (burrow, guess or rare mob).")
        @ConfigEditorBoolean
        public boolean dontWarpIfClose = true;

        @Expose
        @ConfigOption(name = "Warp Block Difference", desc = "Only warp if the warp is at least this many blocks closer to the target than you are.")
        @ConfigEditorSlider(minValue = 0, maxValue = 60, minStep = 1)
        public int warpDiff = 22;
    }

    /** Diana rare mob sharing and the lootshare helper, ported from Skysoft. */
    public static final class Diana {

        @ConfigOption(name = "Burrows & Warp", desc = "Burrow waypoints and guesses, and warping to the closest one.")
        @ConfigEditorAccordion(id = 401)
        public boolean burrowsWarpGroup = false;
        @Expose
        @Accordion
        @ConfigOption(name = "Burrows", desc = "Burrow waypoints from the particles near you, the arrow after each burrow and your spade, like SkyBlock Overhaul (SBO).")
        @ConfigAccordionId(id = 401)
        public DianaBurrows burrows = new DianaBurrows();

        @Expose
        @Accordion
        @ConfigOption(name = "Diana Warp", desc = "Keys that warp you to the warp closest to your next burrow or to a shared rare mob.")
        @ConfigAccordionId(id = 401)
        public DianaWarp warp = new DianaWarp();

        @ConfigOption(name = "Rare Mobs", desc = "Sharing, highlighting and health of rare mobs, and what they drop.")
        @ConfigEditorAccordion(id = 402)
        public boolean rareMobsGroup = false;

        @Expose
        @ConfigOption(name = "Rare Mob Sharing", desc = "When you dig up a rare mob picked in Shared Mobs, send its coordinates in party chat. Rare mobs your party shares get a waypoint and a title.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 402)
        public boolean rareMobSharing = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Shared Mobs", desc = "Which rare mobs Rare Mob Sharing sends and receives: Minos Inquisitors, King Minos and Manticores by default, Sphinxes too if you turn them on.")
        @ConfigAccordionId(id = 402)
        public DianaSharedMobs sharedMobs = new DianaSharedMobs();

        @Expose
        @ConfigOption(name = "Line to Rare Mob", desc = "Draw a line from your crosshair to the nearest shared rare mob.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 402)
        public boolean crosshairLine = true;

        @Expose
        @ConfigOption(name = "Siamese Lynx Highlight", desc = "Highlight the Siamese Lynx you can hit (the one with angry villager particles) in green.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 402)
        public boolean lynxHighlight = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Mob HP", desc = "The health of the Diana mobs near you on a HUD, and a low HP alert, like SBO.")
        @ConfigAccordionId(id = 402)
        public DianaMobHealth mobHealth = new DianaMobHealth();

        @Expose
        @Accordion
        @ConfigOption(name = "Rare Drop Announcer", desc = "Your Diana RNG drops in chat, as a title and in party chat, with how many mobs they took, like SBO.")
        @ConfigAccordionId(id = 402)
        public DianaDrops drops = new DianaDrops();

        @Expose
        @ConfigOption(name = "Inquisitor Gamble", desc = "When an Inquisitor you dug up dies, or you lootshare one, it gets shot on screen: the bullet goes through its heart if a Chimera dropped and misses if not. Try it with /s2 inqgamble hit|miss.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 402)
        public boolean inquisitorGamble = true;

        @ConfigOption(name = "Party & Lootshare", desc = "Party messages, checkmarks, coordinate waypoints and the lootshare helper.")
        @ConfigEditorAccordion(id = 403)
        public boolean partyLootshareGroup = false;

        @Expose
        @ConfigOption(name = "Show Party Messages", desc = "Keep the rare mob and lootshare party messages in chat. Off: they're hidden, and a shared mob shows as \"Name found a Minos Inquisitor at x y z\".")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 403)
        public boolean showPartyMessages = false;

        @Expose
        @ConfigOption(name = "Party Coord Waypoints", desc = "Like SkyHanni: coordinates someone sends in party chat (\"x: -30, y: 87, z: 126\") get a waypoint with their name and how far away it is, for a minute or until you reach it. Rare mob shares keep their own waypoint.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 403)
        public boolean partyCoordWaypoints = true;

        @Expose
        @ConfigOption(name = "Party Checkmarks", desc = "A checkmark above party members who secured lootshare (cyan) or spawned the rare mob (pink).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 403)
        public boolean partyCheckmarks = true;

        @Expose
        @ConfigOption(name = "Share Secured Message", desc = "Say \"Loot share secured!\" in party chat once you've done enough damage.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 403)
        public boolean shareSecuredMessage = true;

        @ConfigOption(name = "Lootshare Helper", desc = "The lootshare range ring and its colours.")
        @ConfigEditorAccordion(id = 404)
        @ConfigAccordionId(id = 403)
        public boolean lootshareHelperGroup = false;

        @Expose
        @ConfigOption(name = "Lootshare Helper", desc = "Count your damage on rare mobs your party shares and show whether you've done the 1% needed to lootshare them.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 404)
        public boolean lootshare = false;

        @Expose
        @ConfigOption(name = "Lootshare Radius", desc = "Draw the 30 block lootshare radius around the rare mob.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 404)
        public boolean lootshareRadius = true;

        @Expose
        @ConfigOption(name = "Lootshare Missing Colour", desc = "Colour of \"Lootsharing\" above the mob before you've done enough damage.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 404)
        public String lootshareMissingColor = "0:230:255:85:85";

        @Expose
        @ConfigOption(name = "Lootshare Ready Colour", desc = "Colour of \"Lootsharing\" above the mob once you've done enough damage.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 404)
        public String lootshareReadyColor = "0:230:85:255:255";

        @ConfigOption(name = "Trackers", desc = "Profit, mob and achievement tracking for the Mythological Ritual.")
        @ConfigEditorAccordion(id = 405)
        public boolean trackersGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Profit Tracker", desc = "What each Diana drop was worth, the total profit and the time spent, for this session, this mayor term or all time, like SkyHanni's. Move it in /s2 hud.")
        @ConfigAccordionId(id = 405)
        public DianaProfitTracker profitTracker = new DianaProfitTracker();

        @Expose
        @Accordion
        @ConfigOption(name = "Mob Tracker", desc = "How many of each mythological creature you dug up and how many since each rare one, like SkyHanni's Mythological Creature Tracker.")
        @ConfigAccordionId(id = 405)
        public DianaMobTracker mobTracker = new DianaMobTracker();

        @Expose
        @Accordion
        @ConfigOption(name = "Achievements", desc = "SBO's Diana achievements.")
        @ConfigAccordionId(id = 405)
        public DianaAchievements achievements = new DianaAchievements();

        @ConfigOption(name = "Other", desc = "The Sphinx solver and the compromised lobby warning.")
        @ConfigEditorAccordion(id = 406)
        public boolean otherGroup = false;

        @Expose
        @ConfigOption(name = "Sphinx Solver", desc = "Helps you solve the sphinx riddle by showing you the answer choices in chat and it automatically clicks the correct one for you when you click anywhere while the chat is open.\nTheres also the option to us a keybind in the mc keybinds menu but §c⚠ USE AT YOUR OWN RISK ⚠")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 406)
        public boolean sphinxSolver = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Lobby Compromised", desc = "Alert when too many non-party players join the lobby while you do Diana, like Skysoft.")
        @ConfigAccordionId(id = 406)
        public DianaLobbyCompromised lobbyCompromised = new DianaLobbyCompromised();
    }

    /**
     * Dungeons: the boss helpers for floors 4 to 6 and the Blessing Display are on the Dungeons page itself; every other
     * section is a sub-category in the sidebar (shown under Dungeons when it's open), like SkyHanni's.
     */
    public static final class Dungeons {

        @Expose
        @Accordion
        @ConfigOption(name = "Dungeon Sim", desc = "Practise dungeon rooms and whole floors in a singleplayer world (Killer560's Dungeon Sim and Map Designer).")
        public DungeonSim dungeonSim = new DungeonSim();

        @Expose
        @Accordion
        @ConfigOption(name = "Cross-Mod Bridge", desc = "Share party dungeon data with Odin, NoammAddons and Devonian users through their own sockets (Killer560's).")
        public CrossModBridge crossModBridge = new CrossModBridge();

        @Expose
        @Accordion
        @ConfigOption(name = "Team Melody", desc = "Teammates' Melody progress in P3, and sending yours (Killer560's).")
        public TeamMelody teamMelody = new TeamMelody();

        @Expose
        @Accordion
        @ConfigOption(name = "Party Dungeon Data", desc = "Share what you know about the run with teammates running Sky2M, and use what they know (Killer560's).")
        public PartyData partyData = new PartyData();

        @Expose
        @Accordion
        @ConfigOption(name = "Custom Mage Beam", desc = "One clean beam in place of the Mage's firework particles (Killer560's, from NoammAddons).")
        public MageBeam mageBeam = new MageBeam();

        @Expose
        @Accordion
        @ConfigOption(name = "Death Tracker", desc = "Who died this run, how, and what it cost the score, in a HUD and chat.")
        public DeathTracker deathTracker = new DeathTracker();

        @Expose
        @Accordion
        @ConfigOption(name = "Archer & Tank Counters", desc = "Arrows you've shot and damage you've taken this run.")
        public ClassCounters classCounters = new ClassCounters();

        @Expose
        @Accordion
        @ConfigOption(name = "Key & Door Alerts", desc = "Alerts when a Wither or Blood key is picked up, and who opened each door.")
        public KeyAlerts keyAlerts = new KeyAlerts();

        @Expose
        @Accordion
        @ConfigOption(name = "Duplicate Class Warning", desc = "Warn about parties that already have your class, in the Party Finder and when someone joins.")
        public DupeClass dupeClass = new DupeClass();

        @Expose
        @Accordion
        @ConfigOption(name = "Catacombs Calculator", desc = "/s2 catacalc: how many runs until a Catacombs level.")
        public CataCalculator cataCalculator = new CataCalculator();

        @ConfigOption(name = "Floors 4-6", desc = "Spirit Bear, Livid and Terracotta features.")
        @ConfigEditorAccordion(id = 1301)
        public boolean floors46Group = false;
        @Expose
        @ConfigOption(name = "Spirit Bear (F4/M4)", desc = "Odin's Spirit Bear HUD for the F4 and M4 boss: spirits killed (25 on F4, 30 on M4), then the countdown until the Spirit Bear spawns once the last one dies, then \"Alive!\". Move it in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1301)
        public boolean spiritBear = true;

        @Expose
        @ConfigOption(name = "Livid Solver (F5/M5)", desc = "Odin's Livid Solver: once the wool above the arena shows which Livid is real, says so in chat and boxes that Livid (not while you're blind).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1301)
        public boolean lividSolver = true;

        @Expose
        @ConfigOption(name = "Livid Colour", desc = "Colour of the box around the real Livid.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 1301)
        public String lividColour = "0:120:255:85:255";

        @Expose
        @ConfigOption(name = "Livid Invulnerability (F5/M5)", desc = "Odin's HUD with the server ticks left of Livid's invulnerability after her opening line. Move it in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1301)
        public boolean lividTimer = true;

        @Expose
        @ConfigOption(name = "Terracotta Timer (F6/M6)", desc = "Odin's Terracotta Timer: in the Sadan fight, seconds until each terracotta respawns (15 on F6, 12 on M6), over its flower pot.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1301)
        public boolean terracottaTimer = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Blessing Display", desc = "Odin's Blessing Display: the dungeon's blessings and their levels, from the tab list.")
        public BlessingDisplay blessings = new BlessingDisplay();

        @Expose
        @Accordion
        @ConfigOption(name = "Auto Requeue", desc = "NoammAddons' Auto Requeue: start the next run of the same floor by itself when a run ends.")
        public AutoRequeue autoRequeue = new AutoRequeue();

        @Expose
        @Accordion
        @ConfigOption(name = "Party Finder Stats", desc = "Odin's Better Party Finder: stats of players who join your dungeon group, with a kick button and optional auto kick.")
        public PartyFinderStats partyFinderStats = new PartyFinderStats();

        @Expose
        @Category(name = "F7/M7", desc = "Dragons, relics, terminals and devices.")
        public Floor7 f7 = new Floor7();

        @Expose
        @Category(name = "Dungeon Map", desc = "The dungeon map HUD.")
        public DungeonMap map = new DungeonMap();

        @Expose
        @Category(name = "Puzzle Solvers", desc = "Solutions for dungeon puzzles.")
        public Puzzles puzzles = new Puzzles();

        @Expose
        @Category(name = "Secrets and Routes", desc = "Secret waypoints and recorded routes.")
        public Secrets secrets = new Secrets();

        @Expose
        @Category(name = "Mobs", desc = "Dungeon mob highlighting.")
        public DungeonMobs mobs = new DungeonMobs();

        @Expose
        @Category(name = "Timers and Alerts", desc = "Splits, tick timers and debuff alerts.")
        public Timers timers = new Timers();

        @Expose
        @Category(name = "Boss Phases", desc = "Boss phase times, timers and alerts.")
        public BossPhases bossPhases = new BossPhases();

        @Expose
        @Category(name = "Score", desc = "Score display and 270/300 alerts.")
        public Score score = new Score();

        @Expose
        @Category(name = "Leap Menu", desc = "Odin-style Spirit Leap menu.")
        public LeapMenu leapMenu = new LeapMenu();

        @Expose
        @Category(name = "Positional Messages", desc = "Party messages at set spots.")
        public PositionalMessages positionalMessages = new PositionalMessages();

        @Expose
        @Category(name = "Blood Camp", desc = "Watcher prediction and blood timers.")
        public BloodCamp bloodCamp = new BloodCamp();

        @Expose
        @Category(name = "Case Opening", desc = "Open reward chests like a CS2 case.")
        public CaseOpening caseOpeningMenu = new CaseOpening();

        @Expose
        @Category(name = "Chest Profit", desc = "What dungeon reward chests are worth.")
        public ChestProfit chestProfit = new ChestProfit();

        @Expose
        @Category(name = "Dungeon Extras", desc = "Waypoints, clutter, Croesus and more.")
        public com.epic60869.sky2m.features.dungeons.extras.DungeonExtras.Config dungeonExtras = new com.epic60869.sky2m.features.dungeons.extras.DungeonExtras.Config();

        @Expose
        @Category(name = "Run Recap", desc = "Death recap, cooldowns and run summary.")
        public com.epic60869.sky2m.features.dungeons.DungeonRecapExtras.Config recapExtras = new com.epic60869.sky2m.features.dungeons.DungeonRecapExtras.Config();
    }

    /** Odin's Blessing Display: which blessings to list, and their colours. */
    public static final class BlessingDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In dungeons, a HUD with the blessings you have and their levels. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose @ConfigOption(name = "Power", desc = "List the Blessing of Power.") @ConfigEditorBoolean public boolean power = true;
        @Expose @ConfigOption(name = "Power Colour", desc = "Colour of the Power line.") @ConfigEditorColour public String powerColour = "0:255:170:0:0";
        @Expose @ConfigOption(name = "Time", desc = "List the Blessing of Time.") @ConfigEditorBoolean public boolean time = true;
        @Expose @ConfigOption(name = "Time Colour", desc = "Colour of the Time line.") @ConfigEditorColour public String timeColour = "0:255:170:0:170";
        @Expose @ConfigOption(name = "Stone", desc = "List the Blessing of Stone.") @ConfigEditorBoolean public boolean stone = false;
        @Expose @ConfigOption(name = "Stone Colour", desc = "Colour of the Stone line.") @ConfigEditorColour public String stoneColour = "0:255:170:170:170";
        @Expose @ConfigOption(name = "Life", desc = "List the Blessing of Life.") @ConfigEditorBoolean public boolean life = false;
        @Expose @ConfigOption(name = "Life Colour", desc = "Colour of the Life line.") @ConfigEditorColour public String lifeColour = "0:255:255:85:85";
        @Expose @ConfigOption(name = "Wisdom", desc = "List the Blessing of Wisdom.") @ConfigEditorBoolean public boolean wisdom = false;
        @Expose @ConfigOption(name = "Wisdom Colour", desc = "Colour of the Wisdom line.") @ConfigEditorColour public String wisdomColour = "0:255:85:255:255";
    }

    /** NoFrills' Dungeon Chest Value and Croesus Solver (BSD-3-Clause). */
    public static final class ChestProfit {

        @ConfigOption(name = "Chest Value", desc = "Chest values, tooltips, floor labels and the Croesus solver.")
        @ConfigEditorAccordion(id = 2001)
        public boolean chestValueGroup = false;
        @Expose
        @ConfigOption(name = "Dungeon Chest Value", desc = "NoFrills: in a dungeon reward chest (at the end of a run or at Croesus), show what its contents are worth minus its cost, over the chest.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2001)
        public boolean chestValue = true;

        @Expose
        @ConfigOption(name = "Chest Value Background", desc = "The box behind the Chest Value text.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2001)
        public String background = "0:204:32:32:32";

        @Expose
        @ConfigOption(name = "Value Tooltip", desc = "Add each chest's value (contents minus cost) to its tooltip in a run's chest menu.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2001)
        public boolean valueTooltip = true;

        @Expose
        @ConfigOption(name = "Floor Label", desc = "Show each run's floor (F7, M7) on it in Croesus's list.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2001)
        public boolean floorLabel = true;

        @Expose
        @ConfigOption(name = "Croesus Solver", desc = "NoFrills: in Croesus's list of runs, colour each run by whether its chests are unopened, rerolled with a Kismet Feather, opened or opened with a key; in a run's chests, highlight the most profitable one and the second best.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2001)
        public boolean croesusSolver = true;

        @ConfigOption(name = "Colours", desc = "The colours chests are drawn in.")
        @ConfigEditorAccordion(id = 2002)
        public boolean coloursGroup = false;

        @Expose
        @ConfigOption(name = "Profit Color", desc = "The most profitable chest.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String profitColor = "0:160:85:255:85";

        @Expose
        @ConfigOption(name = "Profit Secondary Color", desc = "The second most profitable chest, when it's profitable.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String profitSecondaryColor = "0:160:255:255:85";

        @Expose
        @ConfigOption(name = "Profit Key Color", desc = "The second most profitable chest, when it's worth more than a Dungeon Chest Key.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String profitKeyColor = "0:160:85:255:255";

        @Expose
        @ConfigOption(name = "Profit High Color", desc = "The most profitable chest, when it's worth more than the High Profit Threshold or holds a dye.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String profitHighColor = "0:160:255:85:255";

        @Expose
        @ConfigOption(name = "High Profit Threshold", desc = "Profit above which the best chest gets the High color.")
        @ConfigEditorSlider(minValue = 0, maxValue = 100_000_000, minStep = 250_000)
        @ConfigAccordionId(id = 2002)
        public float profitHighThreshold = 5_000_000;

        @Expose
        @ConfigOption(name = "Unopened Color", desc = "Croesus runs with no chests opened yet.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String unopenedColor = "0:160:85:255:85";

        @Expose
        @ConfigOption(name = "Rerolled Color", desc = "Croesus runs with no chests opened yet, after a Kismet Feather reroll.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String rerolledColor = "0:160:85:255:255";

        @Expose
        @ConfigOption(name = "Opened Color", desc = "Croesus runs with a chest opened.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String openedColor = "0:160:255:85:85";

        @Expose
        @ConfigOption(name = "Opened Key Color", desc = "Croesus runs with no more chests to open (a key was used).")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2002)
        public String openedKeyColor = "0:160:85:85:85";

        @ConfigOption(name = "Spending History", desc = "Keep a history of the reward chests you open: cost, contents and profit. /s2 chesthistory shows it.")
        @ConfigEditorAccordion(id = 2003)
        public boolean historyGroup = false;

        @Expose
        @ConfigOption(name = "Chest Spending History", desc = "Record every dungeon reward chest you open (at the end of a run or at Croesus): its floor, cost (coins, Dungeon Chest Key, Kismet Feather), what was inside and the profit. /s2 chesthistory shows the totals.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2003)
        public boolean history = true;

        @ConfigOption(name = "Reset History", desc = "Delete the chest spending history of the current profile.")
        @ConfigEditorButton(buttonText = "Reset")
        @ConfigAccordionId(id = 2003)
        public Runnable resetHistory = com.epic60869.sky2m.features.dungeons.ChestHistory::reset;
    }

    public static final class Floor7 {
        @Expose
        @Accordion
        @ConfigOption(name = "M7 Dragons and Relics", desc = "Master Mode floor 7 dragon phase (P5): spawn alerts with split priority, timers, kill areas and relic spots, like NoFrills' Wither Dragons and Relic Highlight.")
        public WitherDragons witherDragons = new WitherDragons();

        @Expose
        @Accordion
        @ConfigOption(name = "M7 Dragon Arrows and Splits", desc = "Arrows on your screen pointing at spawning dragons, and how long each dragon took to kill.")
        public DragonSplits dragonSplits = new DragonSplits();

        @Expose
        @Accordion
        @ConfigOption(name = "Terminal Progress", desc = "Who did each terminal, lever and device in P3, like \"Name 3/7\", in a HUD and as a title.")
        public TerminalProgress terminalProgress = new TerminalProgress();

        @Expose
        @Accordion
        @ConfigOption(name = "Terminals and Devices", desc = "Floor 7 terminal and device solvers.")
        public Terminals terminals = new Terminals();

        @Expose
        @Accordion
        @ConfigOption(name = "Platform Highlight (3x3)", desc = "One big box over the floor 7 3x3 platform (53-55, 63, 113-115), from when Goldor starts, like NoFrills.")
        public PlatformHighlight platformHighlight = new PlatformHighlight();

        @Expose
        @Accordion
        @ConfigOption(name = "F7 Extras", desc = "Maxor's crystals, boss titles, the Storm lightning timer, the P3 gate and terminal boxes, the Leap Counter and the M7 relic spawn timer (from Odin and NoammAddons).")
        public F7Extras extras = new F7Extras();
    }

    /** Dungeons > Floor 7 > F7 Extras (features from NoammAddons and Odin). */
    public static final class F7Extras {
        @ConfigOption(name = "Maxor's Crystals", desc = "P1 Energy Crystal timers and alerts.")
        @ConfigEditorAccordion(id = 3010)
        public boolean crystalsGroup = false;

        @Expose
        @ConfigOption(name = "Crystal Spawn Timer", desc = "When Maxor is stunned, count down the 34 server ticks until the Energy Crystals respawn, big in a HUD you can move in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3010)
        public boolean crystalSpawnTimer = false;

        @Expose
        @ConfigOption(name = "Crystal Place Timer", desc = "Chat line with how long you took to place an Energy Crystal after picking it up, with your PB.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3010)
        public boolean crystalPlaceTimer = false;

        @Expose
        @ConfigOption(name = "Crystal Place Alert", desc = "In P1, a big \"⚠ Crystal ⚠\" in the HUD while you hold an Energy Crystal in your last hotbar slot that you haven't placed.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3010)
        public boolean crystalPlaceAlert = false;

        @ConfigOption(name = "Boss Titles", desc = "Titles for the F7/M7 boss fight.")
        @ConfigEditorAccordion(id = 3011)
        public boolean titlesGroup = false;

        @Expose
        @ConfigOption(name = "Crystal Titles", desc = "Hide \"The Energy Laser is charging up!\" and show \"Crystal (1/2)\" instead of Hypixel's crystal subtitles.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3011)
        public boolean crystalTitles = false;

        @Expose
        @ConfigOption(name = "Wither Titles", desc = "Titles when Maxor is stunned and dies, Storm is crushed and dies, Goldor dies and Necron dies, and a coloured title when Maxor or Storm is enraged.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3011)
        public boolean witherTitles = false;

        @Expose
        @ConfigOption(name = "Storm Lightning Timer", desc = "Hides Storm's lightning countdown titles (the numbers) and shows the time until his lightning ends, big in a HUD you can move in /s2 gui, then \"Storm's Lightning Ended!\".")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3011)
        public boolean lightningTimer = false;

        @ConfigOption(name = "P3", desc = "The gate, terminal boxes and the Leap Counter.")
        @ConfigEditorAccordion(id = 3012)
        public boolean p3Group = false;

        @Expose
        @ConfigOption(name = "Gate Highlight", desc = "Box the gate of the P3 section you're in until it's destroyed.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3012)
        public boolean gateHighlight = false;

        @Expose
        @ConfigOption(name = "Gate Colour", desc = "")
        @ConfigEditorColour
        @ConfigAccordionId(id = 3012)
        public String gateColour = "0:255:255:85:255";

        @Expose
        @ConfigOption(name = "Terminal Boxes", desc = "Box the hitbox of each inactive terminal in your P3 section (NoammAddons' Terminal Highlight). Odin's Terminal Status shows the terminal's real hitbox the same way.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3012)
        public boolean terminalBoxes = false;

        @Expose
        @ConfigOption(name = "Terminal Box Colour", desc = "")
        @ConfigEditorColour
        @ConfigAccordionId(id = 3012)
        public String terminalColour = "0:255:85:255:255";

        @Expose
        @ConfigOption(name = "Flash On Click", desc = "Terminal boxes flash red for a moment when you click the terminal.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3012)
        public boolean terminalFlash = true;

        @Expose
        @ConfigOption(name = "Boxes Through Walls", desc = "Show the gate and terminal boxes through walls.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3012)
        public boolean throughWalls = false;

        @Expose
        @ConfigOption(name = "Leap Counter", desc = "When you stand on a leap spot (Simon Says, the early-enter spots, the core, the relics), a HUD with how many teammates have leaped to you, like \"2/4 Players Leaped\". Move it in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3012)
        public boolean leapCounter = false;

        @Expose
        @ConfigOption(name = "Everyone Leaped Alert", desc = "A title and a sound when everyone has leaped to your spot.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3012)
        public boolean leapAlert = true;

        @ConfigOption(name = "M7 Relics", desc = "The relic spawn timer.")
        @ConfigEditorAccordion(id = 3013)
        public boolean relicsGroup = false;

        @Expose
        @ConfigOption(name = "Relic Spawn Timer", desc = "M7: when Necron dies, count down the 61 server ticks until the relics spawn, in your class colour, big in a HUD you can move in /s2 gui (NoammAddons).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3013)
        public boolean relicSpawnTimer = false;
    }

    private FeatureConfigs() {}

    public static final class CocoonAlert {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show an on-screen alert with the mob's name when you cocoon a mob.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Cocoon Label", desc = "Show the mob's name over every cocoon you make, with the time until it hatches (once one has been seen hatching), even with the alert off or limited to important mobs.")
        @ConfigEditorBoolean
        public boolean label = true;

        @Expose
        @ConfigOption(name = "Only Important Mobs", desc = "Only alert for slayer bosses and minibosses, elusive mobs and important bosses, not every mob.")
        @ConfigEditorBoolean
        public boolean onlyImportant = true;
    }

    public static final class RareDrops {
        @Expose
        @ConfigOption(name = "Copy Rare Drops", desc = "Copy rare drop messages to your clipboard (RARE, VERY RARE, CRAZY RARE, INSANE, PRAY TO RNGESUS, PET and RNG METER drops, rare rewards and outstanding catches). Garden drops (rare crops, pest drops) are copied only with Copy Garden Drops. Only your own drops are copied, not other players' drops announced in the lobby. What was copied shows in chat, using Copy Chat's preview settings.")
        @ConfigEditorBoolean
        public boolean copy = false;

        @Expose
        @ConfigOption(name = "Copy RNG Drops", desc = "Copy only RNG drops to your clipboard: PRAY TO RNGESUS and RNG METER drops, and slayer, Diana and fishing RNG items (Skyblocker's list). Not needed with Copy Rare Drops on, which copies these too.")
        @ConfigEditorBoolean
        public boolean copyRng = false;

        @Expose
        @ConfigOption(name = "Copy Slayer Drops", desc = "Copy only slayer drops to your clipboard: anything on a slayer's drop list (SkyHanni's slayer profit tracker lists). Not needed with Copy Rare Drops on.")
        @ConfigEditorBoolean
        public boolean copySlayer = false;

        @Expose
        @ConfigOption(name = "Copy Garden Drops", desc = "Copy only Garden drops to your clipboard: RARE CROP drops and anything else that drops in the Garden, like pest drops. The other copy options never copy Garden drops, so with this off none are copied.")
        @ConfigEditorBoolean
        public boolean copyGarden = false;

        @Expose
        @ConfigOption(name = "Big Drop Animation", desc = "Play an animation and a chat message when any rare drop is worth at least the Animation Threshold. Separate from the RNG Drop Totem Animation below.")
        @ConfigEditorBoolean
        public boolean animation = false;

        @Expose
        @ConfigOption(name = "Animation Threshold (millions)", desc = "Minimum drop value, in millions of coins, for the Big Drop Animation. Does not affect the RNG Drop Totem Animation.")
        @ConfigEditorSlider(minValue = 1, maxValue = 500, minStep = 1)
        public float thresholdMillions = 10;

        @Expose
        @ConfigOption(name = "RNG Drop Totem Animation", desc = "Skyblocker's special effect: RNG drops pop up like a Totem of Undying, with particles and a sound, whatever they are worth (no threshold). Plays for slayer, Diana and fishing RNG items, dyes, and every PRAY TO RNGESUS DROP! or RNG METER DROP! chat message (the item you were filling the RNG Meter for), not for every item listed in the RNG Meter menu.")
        @ConfigEditorBoolean
        public boolean totemAnimation = false;

        @Expose
        @ConfigOption(name = "Drop Chat Message", desc = "SkyHanni's drop tracker message: \"+Drop: Gold Bottle Cap (16.1M)\" in chat for every rare drop you get, with the item and what it is worth.")
        @ConfigEditorBoolean
        public boolean dropMessage = false;

        @Expose
        @ConfigOption(name = "RNG Drop Title", desc = "Show RNG drops (PRAY TO RNGESUS, RNG METER, slayer, Diana and fishing RNG items, dyes) as a big title in the middle of the screen that fades, like SkyHanni, with the item and its value.")
        @ConfigEditorBoolean
        public boolean rngTitle = false;

        /** Remembered by the /s2 rng screen. */
        @Expose
        public String rngMagicFind = "";
        @Expose
        public String rngPetLuck = "";
        @Expose
        public String rngOverbloom = "";
        @Expose
        public String rngLooting = "";
        @Expose
        public String rngLuck = "";
        @Expose
        public String rngSearch = "";
        @Expose
        public String rngCategory = "ALL";
    }

    public static final class Slayer {
        @Expose
        @ConfigOption(name = "Boss Phase Display", desc = "HUD showing your slayer boss's nametag lines, including Voidgloom hits and Inferno attunement.")
        @ConfigEditorBoolean
        public boolean phaseDisplay = false;

        @Expose
        @ConfigOption(name = "Show Minibosses", desc = "Also show slayer minibosses near you in the HUD, under Hypixel's SLAYER MINIBOSS line with their name and health.")
        @ConfigEditorBoolean
        public boolean showMinibosses = true;

        @Expose
        @ConfigOption(name = "T5 Phase Times", desc = "For Tier 5 Tarantula and Tier 5 Revenant, under the boss in the Boss Phase Display (needs it on): hits left while the nametag shows hits, the current phase's time, and each finished phase's time with the downtime between phases (while the boss's nametag is gone, e.g. Tarantula Broodfather to Conjoined Brood).")
        @ConfigEditorBoolean
        public boolean t5PhaseTimes = false;

        @Expose
        @ConfigOption(name = "T5 Phase Summary", desc = "When a Tier 5 Tarantula or Revenant dies, one line in chat (only you see it), e.g. \"T5 Tara: 41.2s (phase 1 18.1s · downtime 3.4s · phase 2 19.7s)\". Replaces the Phase Timer's chat summary for those bosses.")
        @ConfigEditorBoolean
        public boolean t5PhaseSummary = false;
    }

    /** Title and sound when your slayer boss spawns, for runs where you aren't watching the screen. */
    public static final class BossSpawnAlert {
        @Expose
        @ConfigOption(name = "Boss Spawn Alert", desc = "\"BOSS SPAWNED\" with the boss and tier (e.g. Tarantula Broodfather V) and a sound as soon as the sidebar says \"Slay the boss!\" for your quest, or when your boss's nametag appears. If the boss is off-screen, the subtitle says where it is.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Sound", desc = "Sound played with the alert.")
        @ConfigEditorDropdown
        public BossSpawnSound sound = BossSpawnSound.ANVIL;

        @Expose
        @ConfigOption(name = "Volume", desc = "Volume of the alert sound.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 1f, minStep = 0.05f)
        public float volume = 1f;

        @Expose
        @ConfigOption(name = "Repeat Until Seen", desc = "Play the sound again every 2 seconds until your boss is on-screen or under your crosshair, for up to 10 seconds.")
        @ConfigEditorBoolean
        public boolean repeatUntilSeen = false;
    }

    public enum BossSpawnSound {
        ANVIL("Anvil"), BELL("Bell"), PLING("Pling"), LEVEL_UP("Level Up"), WITHER("Wither Spawn"), DRAGON("Dragon Growl");

        private final String label;

        BossSpawnSound(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Voidgloom Seraph helpers, ported from SkyHanni's Enderman slayer features. */
    public static final class EndermanSlayer {

        @ConfigOption(name = "Yang Glyphs", desc = "Highlight, warning and line for Yang Glyph beacons.")
        @ConfigEditorAccordion(id = 701)
        public boolean yangGlyphsGroup = false;
        @Expose
        @ConfigOption(name = "Highlight Yang Glyph", desc = "Highlight the Yang Glyph (beacon) while a Voidgloom holds it, throws it and after it lands, with a timer until it explodes.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 701)
        public boolean highlightBeacon = false;

        @Expose
        @ConfigOption(name = "Yang Glyph Colour", desc = "Colour of the Yang Glyph highlight.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 701)
        public String beaconColor = "0:255:255:0:88";

        @Expose
        @ConfigOption(name = "Yang Glyph Warning", desc = "A title when a Voidgloom throws a Yang Glyph.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 701)
        public boolean beaconWarning = false;

        @Expose
        @ConfigOption(name = "Line to Yang Glyph", desc = "Draw a line from your crosshair to the Yang Glyph.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 701)
        public boolean beaconLine = false;

        @Expose
        @ConfigOption(name = "Yang Glyph Line Colour", desc = "Colour of the line to the Yang Glyph.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 701)
        public String beaconLineColor = "0:255:255:0:88";

        @Expose
        @ConfigOption(name = "Yang Glyph Line Width", desc = "Width of the line to the Yang Glyph.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        @ConfigAccordionId(id = 701)
        public int beaconLineWidth = 3;

        @ConfigOption(name = "Nukekubi Skulls", desc = "Highlight and lines for Nukekubi skulls.")
        @ConfigEditorAccordion(id = 702)
        public boolean nukekubiSkullsGroup = false;

        @Expose
        @ConfigOption(name = "Highlight Nukekubi Skulls", desc = "Highlight the Nukekubi Fixation skulls (the eyes) in gold.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 702)
        public boolean highlightNukekubi = false;

        @Expose
        @ConfigOption(name = "Line to Nukekubi Skulls", desc = "Draw a line from your crosshair to each Nukekubi Fixation skull you can see.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 702)
        public boolean lineToNukekubi = false;

        @ConfigOption(name = "Boss", desc = "Phase numbers, hidden particles and a line to your boss.")
        @ConfigEditorAccordion(id = 703)
        public boolean bossGroup = false;

        @Expose
        @ConfigOption(name = "Phase Numbers", desc = "Put the boss's phase (1/3, or 1/6 for tier IV) in front of its health in the Slayer Boss Phase HUD.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 703)
        public boolean phaseDisplay = false;

        @Expose
        @ConfigOption(name = "Hide Particles", desc = "Hide the smoke, flame and witch particles around endermen in The End.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 703)
        public boolean hideParticles = false;

        @Expose
        @ConfigOption(name = "Line to Boss", desc = "Draw a line from your crosshair to your Voidgloom Seraph while you can see it.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 703)
        public boolean lineToBoss = false;

        @Expose
        @ConfigOption(name = "Boss Line Width", desc = "Width of the line to your Voidgloom Seraph.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        @ConfigAccordionId(id = 703)
        public int bossLineWidth = 3;

        @Expose
        @ConfigOption(name = "Laser Warning", desc = "Like SkyHanni: a \"LASERS!\" title when your Voidgloom Seraph starts its laser phase (it sits on an armour stand and spins its beams around).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 703)
        public boolean laserWarning = false;

        @Expose
        @ConfigOption(name = "Laser Timer", desc = "How long the laser phase has left (about 8.2s), in a HUD you can move in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 703)
        public boolean laserTimer = false;
    }

    /** Inferno Demonlord helpers, ported from SkyHanni's Blaze slayer features. */
    public static final class BlazeSlayer {

        @ConfigOption(name = "Daggers", desc = "Dagger display and which dagger to use.")
        @ConfigEditorAccordion(id = 601)
        public boolean daggersGroup = false;

        @Expose
        @ConfigOption(name = "Dagger Display", desc = "HUD with the attunements on your Twilight and Firedust daggers, updated as soon as you swap them. Hypixel's attunement title is hidden while it shows.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 601)
        public boolean daggers = false;

        @Expose
        @ConfigOption(name = "Mark Right Dagger", desc = "In the dagger HUD, mark the attunement that matches the nearest boss or demon's shield.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 601)
        public boolean markRightDagger = false;

        @Expose
        @ConfigOption(name = "First Dagger", desc = "Which dagger is shown on the left of the dagger HUD. Twilight: Spirit/Crystal. Firedust: Ashen/Auric.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 601)
        public com.epic60869.sky2m.features.slayer.BlazeSlayer.FirstDagger firstDagger = com.epic60869.sky2m.features.slayer.BlazeSlayer.FirstDagger.TWILIGHT;

        @Expose
        @ConfigOption(name = "Hide Dagger Chat", desc = "Hide Hypixel's \"Strike using the ... attunement\" and \"Your hit was reduced by Hellion Shield!\" messages.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 601)
        public boolean hideDaggerChat = false;

        @ConfigOption(name = "Boss", desc = "Hellion Shield colours, phase numbers, fire pits, fire pillars and clear view.")
        @ConfigEditorAccordion(id = 602)
        public boolean bossGroup = false;
        @Expose
        @ConfigOption(name = "Hellion Shield Colours", desc = "Outline the Inferno Demonlord and its demons in their Hellion Shield's colour and show the shield above them.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 602)
        public boolean coloredMobs = false;

        @Expose
        @ConfigOption(name = "Phase Numbers", desc = "Put the boss's phase (1/2, or 1/3 for tiers III and IV) in front of its health in the Slayer Boss Phase HUD.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 602)
        public boolean phaseDisplay = false;

        @Expose
        @ConfigOption(name = "Fire Pits Warning", desc = "A title and sound when a tier III or IV Inferno Demonlord drops below a third of its health and the fire pits start.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 602)
        public boolean firePitsWarning = false;

        @Expose
        @ConfigOption(name = "Fire Pillar Display", desc = "HUD with the time until a Fire Pillar explodes, for any player's boss. Move it in /s2 hud.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 602)
        public boolean firePillarDisplay = false;

        @Expose
        @ConfigOption(name = "Clear View", desc = "Hide particles and fireballs within 10 blocks of an Inferno Demonlord.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 602)
        public boolean clearView = false;
    }

    public static final class Garden {
        @Expose
        @ConfigOption(name = "Greenhouse Preview", desc = "Enables /s2 greenhouse paste: copy a layout link from SkyMutations and preview the greenhouse layout (crops and mutation spots) in a screen, like Skyblocker's greenhouse paste. /s2 greenhouse reopens it.")
        @ConfigEditorBoolean
        public boolean greenhousePreview = true;

        @Expose
        @ConfigOption(name = "Yaw and Pitch", desc = "HUD showing your yaw, pitch and facing direction.")
        @ConfigEditorBoolean
        public boolean yawPitch = false;

        @Expose
        @ConfigOption(name = "Pest Cooldown Time (seconds)", desc = "Your pest spawn cooldown. The Pest Spawn Timer counts down this long from each pest spawn (the tab list isn't used).")
        @ConfigEditorSlider(minValue = 60, maxValue = 900, minStep = 5)
        public float pestCooldownSeconds = 300;

        @Expose
        @Accordion
        @ConfigOption(name = "Pest Spawn Timer", desc = "SkyHanni's Pest Spawn Timer: time since the last pest, the pest cooldown and cooldown warnings.")
        public PestTimer pestTimer = new PestTimer();

        @Expose
        @Accordion
        @ConfigOption(name = "Pest Spawn", desc = "SkyHanni's pest spawn title, chat format and spawn sound.")
        public PestSpawn pestSpawn = new PestSpawn();

        @Expose
        @Accordion
        @ConfigOption(name = "Pest Finder", desc = "SkyHanni's Teleport Hotkey and /shtpinfested: warp to the nearest plot with pests on it.")
        public PestFinder pestFinder = new PestFinder();

        @Expose
        @ConfigOption(name = "Blocks Per Second", desc = "HUD showing how many blocks per second you are breaking.")
        @ConfigEditorBoolean
        public boolean blocksPerSecond = false;

        @Expose
        @ConfigOption(name = "Special Drop Animation", desc = "Play an animation when you drop a farming dye or a Ray of Helios.")
        @ConfigEditorBoolean
        public boolean specialDropAnimation = false;

        @Expose
        @ConfigOption(name = "Mute Overflow Drop Sound", desc = "Don't play the nether portal sound with \"OVERFLOW! Your ... has just dropped a ...!\" (e.g. a Tool Exp Capsule).")
        @ConfigEditorBoolean
        public boolean muteOverflowDropSound = true;

        @Expose
        @ConfigOption(name = "Composter Reminder", desc = "Title and chat reminder in the Garden when your composter is out of Organic Matter or Fuel (read from the tab list Composter widget).")
        @ConfigEditorBoolean
        public boolean composterReminder = false;

        @Expose
        @ConfigOption(name = "Composter Low Amount", desc = "Also remind when Organic Matter or Fuel drops below this amount. 0 = only when the composter is out.")
        @ConfigEditorSlider(minValue = 0, maxValue = 20000, minStep = 500)
        public float composterLowAmount = 0;

        @Expose
        @ConfigOption(name = "Safari Ticket Reminder", desc = "Remind you once per SkyBlock day (every 20 minutes) to get and use your Safari ticket.")
        @ConfigEditorBoolean
        public boolean safariTicketReminder = false;
    }

    public static final class FishingCatchStreak {
        @Expose
        @ConfigOption(name = "Catch Streak", desc = "HUD showing the time since your last sea creature catch and how many you caught in a row. The streak resets when the time window runs out. Needs §eSkyBlock Settings -> Personal -> Fishing Settings -> Sea Creature Chat§7. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Catch Streak Window (seconds)", desc = "How long you can go without a catch before the streak resets.")
        @ConfigEditorSlider(minValue = 5, maxValue = 600, minStep = 5)
        public float windowSeconds = 60;
    }

    /** SkyHanni's PestTimerConfig (LGPL-2.1). */
    public static final class PestTimer {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the time since the last pest spawned in your garden.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Only When Holding", desc = "Only show the time display when holding the specified items.\nLeave empty to always show.")
        @ConfigEditorDraggableList
        public List<HeldItem> onlyWhenHolding = new ArrayList<>(List.of(HeldItem.FARMING_TOOL));

        public enum HeldItem {
            FARMING_TOOL("Farming Tool"),
            VACUUM("Vacuum"),
            LASSO("Lasso");

            private final String displayName;

            HeldItem(String displayName) {
                this.displayName = displayName;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }

        @Expose
        @ConfigOption(name = "Pest Timer Text", desc = "Drag text to change the appearance of the overlay.")
        @ConfigEditorDraggableList
        public List<TextEntry> pestDisplay = new ArrayList<>(List.of(TextEntry.PEST_TIMER, TextEntry.PEST_COOLDOWN));

        public enum TextEntry {
            PEST_TIMER("§eLast pest spawned: §b8s ago"),
            PEST_COOLDOWN("§ePest Cooldown: §b1m 8s"),
            AVERAGE_PEST_SPAWN("§eAverage time to spawn: §b4m 32s");

            private final String displayName;

            TextEntry(String displayName) {
                this.displayName = displayName;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }

        @Expose
        @ConfigOption(name = "Pest Cooldown Warning", desc = "Warn when pests are eligible to spawn.")
        @ConfigEditorBoolean
        public boolean cooldownOverWarning = false;

        @Expose
        @ConfigOption(name = "Repeat Warning", desc = "Repeats the warning sound and title until the loadouts or wardrobe menu is opened, or the pest cooldown expires.")
        @ConfigEditorBoolean
        public boolean repeatWarning = false;

        @Expose
        @ConfigOption(name = "Warn Before Cooldown End", desc = "Warn this many seconds before the cooldown is over.")
        @ConfigEditorSlider(minValue = 1, maxValue = 30, minStep = 1)
        public int cooldownWarningTime = 5;

        @Expose
        @ConfigOption(name = "AFK Timeout", desc = "Don't include spawn time in average spawn time display when the player goes AFK for at least this many seconds.")
        @ConfigEditorSlider(minValue = 5, maxValue = 300, minStep = 1)
        public int averagePestSpawnTimeout = 30;

        @Expose
        @ConfigOption(name = "Pest Spawn Time Chat Message", desc = "When a pest spawns, send the time it took to spawn it in chat.")
        @ConfigEditorBoolean
        public boolean pestSpawnChatMessage = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Sound Settings", desc = "")
        public PestTimerSound sound = new PestTimerSound();
    }

    public static final class PestTimerSound {
        @Expose
        @ConfigOption(name = "Notification Sound", desc = "The sound played for the notification.")
        @ConfigEditorText
        public String name = "block.note_block.pling";

        @Expose
        @ConfigOption(name = "Pitch", desc = "The pitch of the notification sound.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2f, minStep = 0.1f)
        public float pitch = 0.5f;

        @ConfigOption(name = "Test Sound", desc = "Test current sound settings.")
        @ConfigEditorButton(buttonText = "Test")
        public Runnable testSound = com.epic60869.sky2m.features.garden.PestTimer::playUserSound;

        @Expose
        @ConfigOption(name = "Repeat Duration", desc = "Change how often the sound should be repeated in ticks. Change to 20 for only once per second.")
        @ConfigEditorSlider(minValue = 1, maxValue = 20, minStep = 1)
        public int repeatDuration = 20;

        @ConfigOption(name = "List of Sounds", desc = "A list of available sounds.")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable listOfSounds = com.epic60869.sky2m.features.garden.PestTimer::openSoundsList;
    }

    /** SkyHanni's PestFinderConfig teleport options (LGPL-2.1). */
    public static final class PestFinder {
        @Expose
        @ConfigOption(name = "Teleport Hotkey", desc = "Press this key to warp to the nearest plot with pests on it.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = -1)
        public int teleportHotkey = -1;

        @Expose
        @ConfigOption(name = "Always Teleport", desc = "Allow teleporting with the Teleport Hotkey even when you're already in an infested plot.")
        @ConfigEditorBoolean
        public boolean alwaysTp = false;

        @Expose
        @ConfigOption(name = "Back to Garden", desc = "Make the Teleport Hotkey warp you to Garden if you don't have any pests.")
        @ConfigEditorBoolean
        public boolean backToGarden = false;

        @Expose
        @ConfigOption(name = "Highlight Infested Plots", desc = "In Configure Plots (/desk), colour the plots that have pests red.")
        @ConfigEditorBoolean
        public boolean highlightInfestedPlots = true;

        @Expose
        @ConfigOption(name = "Plot Teleport Panel", desc = "In the Garden, show a map of your plots to the right of your inventory, like Skyblocker's Garden Plots widget: click a plot to /plottp to it. Plots with pests are red with their pest count, locked plots grey, and the plot you're in is outlined. Open Configure Plots (/desk) once so plot names and pests are known.")
        @ConfigEditorBoolean
        public boolean plotPanel = true;
    }

    /** SkyHanni's PestSpawnConfig (LGPL-2.1). */
    public static final class PestSpawn {
        @Expose
        @ConfigOption(name = "Chat Message Format", desc = "Change how the pest spawn chat message should be formatted.")
        @ConfigEditorDropdown
        public ChatMessageFormat chatMessageFormat = ChatMessageFormat.HYPIXEL;

        public enum ChatMessageFormat {
            HYPIXEL("Hypixel Style"),
            COMPACT("Compact"),
            DISABLED("Disabled");

            private final String displayName;

            ChatMessageFormat(String displayName) {
                this.displayName = displayName;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }

        @Expose
        @ConfigOption(name = "Show Title", desc = "Show a Title when a pest spawns.")
        @ConfigEditorBoolean
        public boolean showTitle = true;

        public enum SoundMode {
            DEFAULT("Default"),
            MUTED("Muted"),
            CUSTOM("Custom"),
            PLUMBER("Plumber");

            private final String displayName;

            SoundMode(String displayName) {
                this.displayName = displayName;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }

        @Expose
        @ConfigOption(name = "Spawn Sound", desc = "Mute or replace the default spawn sound with a custom one.")
        @ConfigEditorDropdown
        public SoundMode soundMode = SoundMode.DEFAULT;

        @Expose
        @Accordion
        @ConfigOption(name = "Sound Settings", desc = "")
        public PestSpawnSound sound = new PestSpawnSound();
    }

    public static final class PestSpawnSound {
        @Expose
        @ConfigOption(name = "Notification Sound", desc = "The sound played for the notification.")
        @ConfigEditorText
        public String name = "block.note_block.bass";

        @Expose
        @ConfigOption(name = "Pitch", desc = "The pitch of the notification sound.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2f, minStep = 0.1f)
        public float pitch = 1.4920635f;

        @Expose
        @ConfigOption(name = "Repeat Frequency", desc = "Change how often the sound should be repeated in milliseconds.")
        @ConfigEditorSlider(minValue = 50, maxValue = 1000, minStep = 50)
        public int repeatFrequency = 150;

        @Expose
        @ConfigOption(name = "Repeat Amount", desc = "Change the amount of times the sound should be repeated.")
        @ConfigEditorSlider(minValue = 1, maxValue = 20, minStep = 1)
        public int repeatAmount = 3;

        @ConfigOption(name = "Test Sound", desc = "Test current sound settings.")
        @ConfigEditorButton(buttonText = "Test")
        public Runnable testSound = com.epic60869.sky2m.features.garden.PestTimer::repeatSpawnSound;

        @ConfigOption(name = "List of Sounds", desc = "A list of available sounds.")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable listOfSounds = com.epic60869.sky2m.features.garden.PestTimer::openSoundsList;
    }

    /** Fishing, ported from Feesh (https://github.com/Sleepy-Panda/Feesh, Apache-2.0): see features/fishing. */
    public static final class Fishing {
        @Expose
        @Category(name = "Sea Creatures", desc = "Sea creature alerts and highlights.")
        public FishingSeaCreatures seaCreatures = new FishingSeaCreatures();

        @Expose
        @Category(name = "Rare Drops", desc = "Rare fishing drop alerts and prices.")
        public FishingRareDrops rareDrops = new FishingRareDrops();

        @Expose
        @Category(name = "Rare Sea Creatures", desc = "Rare sea creature alerts and health.")
        public com.epic60869.sky2m.features.fishing.RareSc.Config rareSc = new com.epic60869.sky2m.features.fishing.RareSc.Config();

        @Expose
        @Category(name = "Catch Messages", desc = "Shorter sea creature catch messages.")
        public FishingCatchMessages catchMessages = new FishingCatchMessages();

        @Expose
        @Category(name = "Catch Streak", desc = "Time since your last catch and streak.")
        public FishingCatchStreak catchStreak = new FishingCatchStreak();

        @Expose
        @Category(name = "Alerts", desc = "Trophies, hotspots, festivals and more.")
        public FishingAlerts alerts = new FishingAlerts();

        @Expose
        @Category(name = "Hook & Bobber", desc = "Bobber timer and hook countdown.")
        public com.epic60869.sky2m.features.fishing.FishingHookTimer.Config hookTimer = new com.epic60869.sky2m.features.fishing.FishingHookTimer.Config();

        @Expose
        @Category(name = "Fishing Extras", desc = "Cast, armor and hotspot helpers.")
        public com.epic60869.sky2m.features.fishing.FishingExtras.Config extras = new com.epic60869.sky2m.features.fishing.FishingExtras.Config();

        @Expose
        @Category(name = "Trackers & Timers", desc = "Totems, trackers and fishing timers.")
        public com.epic60869.sky2m.features.fishing.FishingMore.Config more = new com.epic60869.sky2m.features.fishing.FishingMore.Config();

        @Expose
        @Category(name = "Visuals", desc = "Lava as water and bobber visuals.")
        public com.epic60869.sky2m.features.fishing.FishingVisuals.Config visuals = new com.epic60869.sky2m.features.fishing.FishingVisuals.Config();

        @Expose
        @Category(name = "Trophy Fish", desc = "Trophy fish counts by tier.")
        public com.epic60869.sky2m.features.fishing.TrophyFishTracker.Config trophyFish = new com.epic60869.sky2m.features.fishing.TrophyFishTracker.Config();
    }

    public static final class FishingSeaCreatures {

        @ConfigOption(name = "Alerts", desc = "Titles and sounds for sea creatures you catch.")
        @ConfigEditorAccordion(id = 901)
        public boolean alertsGroup = false;
        @Expose
        @ConfigOption(name = "Alert on Sea Creatures", desc = "A title and a sound when a sea creature from the list is caught by you or your party members. Turn on §eSkyBlock Settings -> Personal -> Fishing Settings -> Sea Creature Chat§7.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 901)
        public boolean alert = true;

        @Expose
        @ConfigOption(name = "Sea Creatures to Alert On", desc = "Which sea creatures to alert on. Add more with the + button, drag to reorder, drag out to remove.")
        @ConfigEditorDraggableList
        @ConfigAccordionId(id = 901)
        public List<FishingData.AlertableSeaCreature> alertList = defaultCreatures(FishingData.AlertableSeaCreature.values(), c -> c.enabledByDefault);

        @Expose
        @ConfigOption(name = "Alert on Cocooned Sea Creature", desc = "Also alert when a sea creature from the list is cocooned by you or your party members.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 901)
        public boolean alertCocooned = true;

        @Expose
        @ConfigOption(name = "Alert Source", desc = "\"Own and party\": your catches and your party members'. \"Own\": only yours.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 901)
        public FishingData.AlertSource alertSource = FishingData.AlertSource.OWN_AND_PARTY;

        @Expose
        @ConfigOption(name = "Alert When Killed by a Fishing Boss", desc = "A title and a sound when you, or a party member, are killed by a fishing boss (Thunder, Lord Jawbus, Ragnarok, Wiki Tiki, Titanoboa, Nessie, ...).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 901)
        public boolean alertDeath = true;

        @ConfigOption(name = "Party Sharing", desc = "Telling your party about sea creatures.")
        @ConfigEditorAccordion(id = 902)
        public boolean partySharingGroup = false;

        @Expose
        @ConfigOption(name = "Share Sea Creatures to Party", desc = "Says in party chat when you catch a sea creature from the list below (\"--> A YETI has spawned <--\"). Only while you're in a party.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 902)
        public boolean share = true;

        @Expose
        @ConfigOption(name = "Sea Creatures to Share", desc = "Which of your catches to share to party chat.")
        @ConfigEditorDraggableList
        @ConfigAccordionId(id = 902)
        public List<FishingData.AlertableSeaCreature> shareList = defaultCreatures(FishingData.AlertableSeaCreature.values(), c -> c.enabledByDefault);

        @Expose
        @ConfigOption(name = "Share Cocooned Sea Creature", desc = "Also says in party chat when you cocoon a sea creature from the list.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 902)
        public boolean shareCocooned = true;

        @Expose
        @ConfigOption(name = "Tell Party When Killed", desc = "Says so in party chat when a fishing boss kills you, so your party's Sky2M (or Feesh) alerts them to wait for you.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 902)
        public boolean shareDeath = true;

        @ConfigOption(name = "Highlight", desc = "Highlighting sea creatures in the world.")
        @ConfigEditorAccordion(id = 903)
        public boolean highlightGroup = false;

        @Expose
        @ConfigOption(name = "Highlight Sea Creatures", desc = "A glowing outline in the creature's rarity colour on the sea creatures below. Only while you can see them: never through walls.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 903)
        public boolean highlight = false;

        @Expose
        @ConfigOption(name = "Sea Creatures to Highlight", desc = "Which sea creatures get the outline.")
        @ConfigEditorDraggableList
        @ConfigAccordionId(id = 903)
        public List<FishingData.HighlightableSeaCreature> highlightList = defaultCreatures(FishingData.HighlightableSeaCreature.values(), c -> c.enabledByDefault);
    }

    public static final class FishingRareDrops {

        @ConfigOption(name = "Alerts", desc = "Titles and sounds for rare fishing drops.")
        @ConfigEditorAccordion(id = 1001)
        public boolean alertsGroup = false;
        @Expose
        @ConfigOption(name = "Alert on Rare Drops", desc = "A title when a rare fishing drop from the list drops for you (or your party, see Alert Source).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1001)
        public boolean alert = true;

        @Expose
        @ConfigOption(name = "Rare Drops to Alert On", desc = "ALL counts every drop in the list, including ones added later.")
        @ConfigEditorDraggableList
        @ConfigAccordionId(id = 1001)
        public List<FishingData.RareDropType> alertList = new ArrayList<>(List.of(FishingData.RareDropType.ALL));

        @Expose
        @ConfigOption(name = "Alert Source", desc = "\"Own\": only your drops. \"Own and party\": also party members' drops they shared.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 1001)
        public FishingData.AlertSource alertSource = FishingData.AlertSource.OWN;

        @Expose
        @ConfigOption(name = "Show Price in Title", desc = "Show what the dropped item is worth in the alert's title. \"Own\": only for your drops. \"Own and party\": for party members' too. \"Off\": no price.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 1001)
        public FishingData.PriceScope priceScope = FishingData.PriceScope.OWN_AND_PARTY;

        @ConfigOption(name = "Party Sharing", desc = "Telling your party about rare drops.")
        @ConfigEditorAccordion(id = 1002)
        public boolean partySharingGroup = false;

        @Expose
        @ConfigOption(name = "Share Rare Drops to Party", desc = "Says in party chat when a rare drop from the list drops for you (\"--> A Deep Sea Orb has dropped <--\").")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1002)
        public boolean share = true;

        @Expose
        @ConfigOption(name = "Rare Drops to Share", desc = "ALL counts every drop in the list, including ones added later.")
        @ConfigEditorDraggableList
        @ConfigAccordionId(id = 1002)
        public List<FishingData.RareDropType> shareList = new ArrayList<>(List.of(FishingData.RareDropType.ALL));

        @Expose
        @ConfigOption(name = "Include Magic Find", desc = "Add the drop's ✯ Magic Find to the party message.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1002)
        public boolean shareMagicFind = true;
    }

    public static final class FishingCatchMessages {
        @Expose
        @ConfigOption(name = "Compact Catch Messages", desc = "Shortens the double hook and sea creature catch messages: instead of \"It's a Double Hook! Woot Woot!\" and \"What is this creature!?\" you see \"DOUBLE HOOK! A Yeti has spawned!\".")
        @ConfigEditorBoolean
        public boolean compact = false;

        @Expose
        @ConfigOption(name = "Gradient for Rarity", desc = "Colour the sea creature's name with a gradient of its rarity instead of one colour.")
        @ConfigEditorBoolean
        public boolean gradient = false;

        @Expose
        @ConfigOption(name = "Double Hook Template", desc = "Shown before the catch message on a double hook. Use & or § for colours. Empty for the default (&b&lDOUBLE HOOK!).")
        @ConfigEditorText
        public String doubleHookTemplate = "&b&lDOUBLE HOOK!";

        @Expose
        @ConfigOption(name = "Catch Message Template", desc = "The catch message. {Article}/{article}: A or An; {sc}: the sea creature, in its rarity colour. Use & or § for colours. Empty for the default.")
        @ConfigEditorText
        public String catchTemplate = "&7{Article} {sc} &7has spawned!";

        @ConfigOption(name = "Send Test Message", desc = "Shows a sample catch message and a sample double hook with your settings.")
        @ConfigEditorButton(buttonText = "SEND")
        public Runnable test = () -> com.epic60869.sky2m.features.fishing.FishingFeatures.sendTestCatchMessages();
    }

    public static final class FishingAlerts {

        @ConfigOption(name = "Trophy Fishing", desc = "Alerts for new Trophy Fish and Trophy Frogs.")
        @ConfigEditorAccordion(id = 1101)
        public boolean trophyFishingGroup = false;
        @Expose
        @ConfigOption(name = "Alert on New Trophy Fish", desc = "A title when you discover a new Trophy Fish on the Crimson Isle.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1101)
        public boolean trophyFish = true;

        @Expose
        @ConfigOption(name = "Alert on New Trophy Frog", desc = "A title when you discover a new Trophy Frog on the Lotus Atoll.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1101)
        public boolean trophyFrog = true;

        @ConfigOption(name = "Deployables", desc = "Alerts before your deployables run out.")
        @ConfigEditorAccordion(id = 1102)
        public boolean deployablesGroup = false;

        @Expose
        @ConfigOption(name = "Alert When Deployable Expires Soon", desc = "A title and a sound when one of your deployables below is about to run out.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1102)
        public boolean deployables = true;

        @Expose
        @ConfigOption(name = "Deployables to Alert On", desc = "Which of your deployables to alert on.")
        @ConfigEditorDraggableList
        @ConfigAccordionId(id = 1102)
        public List<FishingData.DeployableType> deployableList = new ArrayList<>(List.of(FishingData.DeployableType.TOTEM_OF_CORRUPTION));

        @Expose
        @ConfigOption(name = "Seconds Before (Long Deployables)", desc = "How long before it runs out to alert, for deployables that last 3 minutes or more.")
        @ConfigEditorSlider(minValue = 1, maxValue = 60, minStep = 1)
        @ConfigAccordionId(id = 1102)
        public int deployableSeconds = 10;

        @Expose
        @ConfigOption(name = "Seconds Before (Short Deployables)", desc = "How long before it runs out to alert, for deployables that last a minute or less (power orbs).")
        @ConfigEditorSlider(minValue = 1, maxValue = 30, minStep = 1)
        @ConfigAccordionId(id = 1102)
        public int shortDeployableSeconds = 5;

        @ConfigOption(name = "Hotspots & Wormholes", desc = "Alerts when a hotspot or wormhole disappears, and sharing hotspots you find.")
        @ConfigEditorAccordion(id = 1103)
        public boolean hotspotsWormholesGroup = false;

        @Expose
        @ConfigOption(name = "Alert When Hotspot Is Gone", desc = "A title and a sound when the hotspot you were fishing in disappears.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1103)
        public boolean hotspotGone = true;

        @Expose
        @ConfigOption(name = "Offer Sharing Found Hotspots", desc = "When you come near a hotspot, a chat message with buttons to share its location and perk to party or all chat.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1103)
        public boolean shareHotspots = false;

        @Expose
        @ConfigOption(name = "Alert When Wormhole Is Gone", desc = "A title and a sound when your Wormhole closes up.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1103)
        public boolean wormholeGone = true;

        @ConfigOption(name = "Pets", desc = "Pet max level alert and level-up price.")
        @ConfigEditorAccordion(id = 1104)
        public boolean petsGroup = false;

        @Expose
        @ConfigOption(name = "Alert When Pet Reaches Max Level", desc = "A title and a sound when a pet reaches level 100 (or 200).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1104)
        public boolean petMaxLevel = true;

        @Expose
        @ConfigOption(name = "Show Estimated Pet Level-Up Price", desc = "When a pet reaches max level, says in chat what it's worth now and the profit over a level 1 one (lowest BINs).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1104)
        public boolean petLevelUpPrice = true;

        @ConfigOption(name = "Fishing Festival & Party", desc = "Fishing Festival alerts and personal best, and Lootshare! in party chat.")
        @ConfigEditorAccordion(id = 1105)
        public boolean fishingFestivalPartyGroup = false;

        @Expose
        @ConfigOption(name = "Alert on Fishing Festival Ended", desc = "A title and your shark counts in chat when the Fishing Festival ends.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1105)
        public boolean festivalEnded = true;

        @Expose
        @ConfigOption(name = "Track Festival Personal Best", desc = "Your best total sharks and Great White Sharks in one Fishing Festival, announced when you beat them.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1105)
        public boolean festivalPersonalBest = true;

        @Expose
        @ConfigOption(name = "Alert on Lootshare! in Party Chat", desc = "A title and a sound when a party member says \"Lootshare!\" in party chat.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1105)
        public boolean lootshare = true;
    }

    private static <T extends Enum<T>> List<T> defaultCreatures(T[] values, java.util.function.Predicate<T> enabled) {
        List<T> list = new ArrayList<>();
        for (T value : values) if (enabled.test(value)) list.add(value);
        return list;
    }

    public static final class MiningFeatures {
        @Expose
        @ConfigOption(name = "Commissions For Current Area Only", desc = "Only show the commission HUD in the Dwarven Mines, Crystal Hollows, Glacite Tunnels and Mineshafts.")
        @ConfigEditorBoolean
        public boolean commissionsAreaOnly = true;

        @Expose
        @ConfigOption(name = "Crystal Hollows Map", desc = "HUD map of the Crystal Hollows with your position and the structures you have found.")
        @ConfigEditorBoolean
        public boolean crystalHollowsMap = false;

        @Expose
        @ConfigOption(name = "Crystal Hollows Waypoints", desc = "Mark Mines of Divan, Jungle Temple, Goblin Queen's Den and other places when you find them, like Skyblocker. /s2 crystalwaypoints add|share|remove|clear.")
        @ConfigEditorBoolean
        public boolean crystalWaypoints = false;

        @Expose
        @ConfigOption(name = "Waypoints From Chat", desc = "Turn Crystal Hollows coordinates in chat into waypoints.")
        @ConfigEditorBoolean
        public boolean crystalWaypointsFromChat = true;

        @Expose
        @ConfigOption(name = "Auto-Find From Nametags", desc = "Mark King Yolkar, Odawa, Xalx, Corleone, the Key Guardian and the Jungle Temple door guardian as soon as their nametags load, even from a distance.")
        @ConfigEditorBoolean
        public boolean crystalWaypointsNametags = false;

        @Expose
        @ConfigOption(name = "Share Finds In Party Chat", desc = "When you find a place yourself, post it once per lobby in party chat (\"Place: x, y, z\"), which Sky2M and Skyblocker party members turn into waypoints.")
        @ConfigEditorBoolean
        public boolean crystalWaypointsPartyShare = false;

        @Expose
        @ConfigOption(name = "Pickaxe Ability HUD", desc = "Cooldown of your pickaxe ability (Mining Speed Boost, Pickobulus, ...) on the mining islands (Gold Mine, Deep Caverns, Dwarven Mines, Crystal Hollows, Mineshafts). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean pickaxeAbilityHud = false;

        @Expose
        @ConfigOption(name = "Pickaxe Ability Ready Alert", desc = "Show a title when your pickaxe ability is ready again.")
        @ConfigEditorBoolean
        public boolean pickaxeAbilityAlert = false;

        @Expose
        @ConfigOption(name = "Mines of Divan Tools Alert", desc = "Alert when you are holding all four scavenged tools for the Jade Crystal.")
        @ConfigEditorBoolean
        public boolean divanToolsAlert = false;

        @Expose
        @ConfigOption(name = "Mineshaft Timer", desc = "HUD with your time in the mineshaft and time until you freeze.")
        @ConfigEditorBoolean
        public boolean mineshaftTimer = false;

        @Expose
        @ConfigOption(name = "Pristine Record", desc = "Keep your highest pristine proc, overall and per gemstone, and alert on a new PB. /s2 pristine to see them.")
        @ConfigEditorBoolean
        public boolean pristineRecord = false;

        @Expose
        @ConfigOption(name = "Drill Fuel HUD", desc = "Show your drill's remaining fuel (from SkyblockAddons). Only shown when the drill has its fuel data. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean drillFuelHud = false;

        @Expose
        @ConfigOption(name = "Only When Holding", desc = "Only show the drill fuel while you hold the drill. Off: also use a drill in your hotbar.")
        @ConfigEditorBoolean
        public boolean drillFuelOnlyWhenHolding = true;

        @Expose
        @ConfigOption(name = "Display", desc = "Show the drill fuel as an amount, a percentage or both.")
        @ConfigEditorDropdown
        public DrillFuelDisplay drillFuelDisplay = DrillFuelDisplay.AMOUNT;

        @Expose
        @ConfigOption(name = "Low Fuel Warning", desc = "Show a title once when your drill fuel drops below the warning threshold.")
        @ConfigEditorBoolean
        public boolean drillFuelWarning = false;

        @Expose
        @ConfigOption(name = "Warning Threshold", desc = "Warn when the drill fuel is below this percentage.")
        @ConfigEditorSlider(minValue = 1, maxValue = 50, minStep = 1)
        public int drillFuelWarningThreshold = 10;

        public enum DrillFuelDisplay {
            AMOUNT("Amount"),
            PERCENTAGE("Percentage"),
            BOTH("Both");

            private final String displayName;

            DrillFuelDisplay(String displayName) {
                this.displayName = displayName;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }
    }

    /** SkyHanni's SkillProgressConfig and its sub-configs (LGPL-2.1). Move the displays in /s2 gui. */
    public static final class SkillProgress {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the Skill Progress Display.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @ConfigOption(name = "Display", desc = "What the skill progress display shows and when.")
        @ConfigEditorAccordion(id = 1201)
        public boolean displayGroup = false;

        @Expose
        @ConfigOption(name = "Text Alignment", desc = "Align the display text with the progress bar.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 1201)
        public TextAlignment textAlignmentProperty = TextAlignment.CENTERED;

        @Expose
        @ConfigOption(name = "Hide In Action Bar", desc = "Hide the skill progress in the Hypixel action bar.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1201)
        public boolean hideInActionBar = false;

        @Expose
        @ConfigOption(name = "Always Show", desc = "Always show the skill progress.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1201)
        public boolean alwaysShow = false;

        @Expose
        @ConfigOption(name = "Show Action left", desc = "Show action left until you reach the next level.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1201)
        public boolean showActionLeft = false;

        @Expose
        @ConfigOption(name = "Use percentage", desc = "Use percentage instead of XP.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1201)
        public boolean usePercentage = false;

        @Expose
        @ConfigOption(name = "Use Icon", desc = "Show the skill icon in the display.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1201)
        public boolean useIcon = true;

        @Expose
        @ConfigOption(name = "Use Skill Name", desc = "Show the skill name in the display.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1201)
        public boolean useSkillName = false;

        @Expose
        @ConfigOption(name = "Show Level", desc = "Show your current level in the display.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1201)
        public boolean showLevel = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Progress Bar", desc = "Progress Bar Config.")
        public SkillProgressBar skillProgressBarConfig = new SkillProgressBar();

        @Expose
        @Accordion
        @ConfigOption(name = "Overflow", desc = "Overflow Config.")
        public SkillOverflow overflowConfig = new SkillOverflow();

        @Expose
        @Accordion
        @ConfigOption(name = "Custom Goal", desc = "Define a custom goal for each skill.")
        public SkillCustomGoal customGoalConfig = new SkillCustomGoal();

        @Expose
        @Accordion
        @ConfigOption(name = "All Skill Display", desc = "All Skill Display Config.")
        public AllSkillDisplay allSkillDisplayConfig = new AllSkillDisplay();

        @Expose
        @Accordion
        @ConfigOption(name = "ETA Display", desc = "ETA Display Config.")
        public SkillEtaDisplay skillETADisplayConfig = new SkillEtaDisplay();

        public enum TextAlignment {
            NONE("None", null),
            CENTERED("Centered", 0),
            LEFT("Left", -1),
            RIGHT("Right", 1);

            private final String displayName;
            public final Integer alignment;

            TextAlignment(String displayName, Integer alignment) {
                this.displayName = displayName;
                this.alignment = alignment;
            }

            @Override
            public String toString() {
                return displayName;
            }
        }
    }

    public static final class SkillProgressBar {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Enable or disable the progress bar.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Textured Bar", desc = "Use a textured progress bar.\n§eCan be changed with a resource pack.")
        @ConfigEditorBoolean
        public boolean useTexturedBar = false;

        @Expose
        @ConfigOption(name = "Chroma", desc = "Use the SBA like chroma effect on the bar.\n§eIf enabled, ignore the Bar Color setting.")
        @ConfigEditorBoolean
        public boolean useChroma = false;

        @Expose
        @ConfigOption(name = "Bar Color", desc = "Color of the progress bar.\n§eIgnored if Chroma is enabled.")
        @ConfigEditorColour
        public String barStartColor = "0:255:255:0:0";

        @Expose
        @Accordion
        @ConfigOption(name = "Textured Bar", desc = "")
        public TexturedBar texturedBar = new TexturedBar();

        public static final class TexturedBar {
            @Expose
            @ConfigOption(name = "Used Texture", desc = "Choose what texture to use.")
            @ConfigEditorDropdown
            public UsedTexture usedTexture = UsedTexture.MATCH_PACK;

            public enum UsedTexture {
                MATCH_PACK("Resource Pack", "minecraft:hud/experience_bar"),
                CUSTOM_1("Texture 1", "sky2m:textures/bars/1.png"),
                CUSTOM_2("Texture 2", "sky2m:textures/bars/2.png"),
                CUSTOM_3("Texture 3", "sky2m:textures/bars/3.png"),
                CUSTOM_4("Texture 4", "sky2m:textures/bars/4.png"),
                CUSTOM_5("Texture 5", "sky2m:textures/bars/5.png");

                private final String displayName;
                public final String path;

                UsedTexture(String displayName, String path) {
                    this.displayName = displayName;
                    this.path = path;
                }

                @Override
                public String toString() {
                    return displayName;
                }
            }

            @Expose
            @ConfigOption(name = "Width", desc = "Modify the width of the bar.\n§eDefault: 182\n§c!!Does not work for now!!")
            @ConfigEditorSlider(minStep = 1, minValue = 16, maxValue = 1024)
            public int width = 182;

            @Expose
            @ConfigOption(name = "Height", desc = "Modify the height of the bar.\n§eDefault: 5\n§c!!Does not work for now!!")
            @ConfigEditorSlider(minStep = 1, minValue = 3, maxValue = 16)
            public int height = 5;
        }

        @Expose
        @Accordion
        @ConfigOption(name = "Regular Bar", desc = "")
        public RegularBar regularBar = new RegularBar();

        public static final class RegularBar {
            @Expose
            @ConfigOption(name = "Width", desc = "Modify the width of the bar.")
            @ConfigEditorSlider(minStep = 1, minValue = 100, maxValue = 1000)
            public int width = 182;

            @Expose
            @ConfigOption(name = "Height", desc = "Modify the height of the bar.")
            @ConfigEditorSlider(minStep = 1, minValue = 3, maxValue = 15)
            public int height = 6;
        }
    }

    public static final class SkillOverflow {
        @Expose
        @ConfigOption(name = "Display", desc = "Enable the overflow calculation in the progress display.")
        @ConfigEditorBoolean
        public boolean enableInDisplay = false;

        @Expose
        @ConfigOption(name = "All Skill Display", desc = "Enable the overflow calculation in the all skill progress display.")
        @ConfigEditorBoolean
        public boolean enableInAllDisplay = false;

        @Expose
        @ConfigOption(name = "ETA Display", desc = "Enable the overflow calculation in the ETA skill display.")
        @ConfigEditorBoolean
        public boolean enableInEtaDisplay = false;

        @Expose
        @ConfigOption(name = "Progress Bar", desc = "Enable the overflow calculation in the progress bar of the display.")
        @ConfigEditorBoolean
        public boolean enableInProgressBar = false;

        @Expose
        @ConfigOption(name = "Skill Menu Stack Size", desc = "Enable the overflow calculation when the 'Skill Level' Item Number is enabled.")
        @ConfigEditorBoolean
        public boolean enableInSkillMenuAsStackSize = false;

        @Expose
        @ConfigOption(name = "Skill Menu Tooltips", desc = "Enable the overflow calculation in the tooltip of items in skills menu.")
        @ConfigEditorBoolean
        public boolean enableInSkillMenuTooltip = false;

        @Expose
        @ConfigOption(name = "Chat", desc = "Enable the overflow level up message when you gain an overflow level.")
        @ConfigEditorBoolean
        public boolean enableInChat = false;
    }

    public static final class SkillCustomGoal {
        @Expose
        @ConfigOption(name = "Display", desc = "Enable the custom goal in the progress display.")
        @ConfigEditorBoolean
        public boolean enableInDisplay = true;

        @Expose
        @ConfigOption(name = "All Skill Display", desc = "Enable the custom goal in the all skill display.")
        @ConfigEditorBoolean
        public boolean enableInAllDisplay = false;

        @Expose
        @ConfigOption(name = "ETA Display", desc = "Enable the custom goal in the ETA skill display.")
        @ConfigEditorBoolean
        public boolean enableInETADisplay = false;

        @Expose
        @ConfigOption(name = "Progress Bar", desc = "Enable the custom goal in the progress bar.")
        @ConfigEditorBoolean
        public boolean enableInProgressBar = true;

        @Expose
        @ConfigOption(name = "Skill Menu Tooltips", desc = "Enable the custom goal in the tooltip of items in skills menu.")
        @ConfigEditorBoolean
        public boolean enableInSkillMenuTooltip = false;

        @Expose
        @ConfigOption(name = "Chat", desc = "Send a message when you reach your goal.")
        @ConfigEditorBoolean
        public boolean enableInChat = false;
    }

    public static final class AllSkillDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show a display with all skills progress.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Text", desc = "Choose what skills you want to see in the display.")
        @ConfigEditorDraggableList
        public List<com.epic60869.sky2m.features.skills.SkillType> skillEntryList = new ArrayList<>(List.of(com.epic60869.sky2m.features.skills.SkillType.COMBAT, com.epic60869.sky2m.features.skills.SkillType.FARMING, com.epic60869.sky2m.features.skills.SkillType.FISHING, com.epic60869.sky2m.features.skills.SkillType.MINING, com.epic60869.sky2m.features.skills.SkillType.FORAGING, com.epic60869.sky2m.features.skills.SkillType.ENCHANTING, com.epic60869.sky2m.features.skills.SkillType.ALCHEMY, com.epic60869.sky2m.features.skills.SkillType.CARPENTRY, com.epic60869.sky2m.features.skills.SkillType.TAMING));
    }

    public static final class SkillEtaDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show a display of your current active skill with the XP/hour rate, ETA to the next level and current session time.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Farming", desc = "After how many seconds should the Farming session timer pause.")
        @ConfigEditorSlider(minStep = 1, minValue = 3, maxValue = 60)
        public int farmingPauseTime = 3;

        @Expose
        @ConfigOption(name = "Mining", desc = "After how many seconds should the Mining session timer pause.")
        @ConfigEditorSlider(minStep = 1, minValue = 3, maxValue = 60)
        public int miningPauseTime = 3;

        @Expose
        @ConfigOption(name = "Combat", desc = "After how many seconds should the Combat session timer pause.")
        @ConfigEditorSlider(minStep = 1, minValue = 3, maxValue = 60)
        public int combatPauseTime = 30;

        @Expose
        @ConfigOption(name = "Foraging", desc = "After how many seconds should the Foraging session timer pause.")
        @ConfigEditorSlider(minStep = 1, minValue = 3, maxValue = 60)
        public int foragingPauseTime = 3;

        @Expose
        @ConfigOption(name = "Fishing", desc = "After how many seconds should the Fishing session timer pause.")
        @ConfigEditorSlider(minStep = 1, minValue = 3, maxValue = 60)
        public int fishingPauseTime = 15;
    }

    public static final class Foraging {
        @Expose
        @ConfigOption(name = "Sweep Display", desc = "HUD showing your Sweep stat and how many logs you will cut.")
        @ConfigEditorBoolean
        public boolean sweepDisplay = false;

        /** SkyHanni's ForagingBeaconConfig (LGPL-2.1). */
        @Expose
        @ConfigOption(name = "Beacon Solver", desc = "In the Moonglade Beacon menus, highlights the controls that match and shows how many clicks the others are off.")
        @ConfigEditorBoolean
        public boolean beaconSolver = false;

        @Expose
        @ConfigOption(name = "Beacon Middle Click", desc = "Click Moonglade Beacon controls with a middle click to speed up interactions.")
        @ConfigEditorBoolean
        public boolean beaconMiddleClick = true;

        @Expose
        @ConfigOption(name = "Beacon Prevent Over-Clicking", desc = "Prevents clicking a Moonglade Beacon control that already matches. Hold Control to bypass.")
        @ConfigEditorBoolean
        public boolean beaconPreventOverClicking = true;

        /** SkyHanni's ForagingBeaconWarning (LGPL-2.1). */
        @Expose
        @ConfigOption(name = "Beacon Ready Alert", desc = "Shows a title and chat message when the Moonglade Beacon cooldown in the tab list is over.")
        @ConfigEditorBoolean
        public boolean beaconReadyAlert = false;

        @Expose
        @ConfigOption(name = "Foraging Extras", desc = "Foraging Tracker, Tree Progress, Clean Tree View, tutorial quest helper, Birries and Hideonleaf highlights, Mute Phantoms, coupon profit and the Honeyhive reminder (SkyHanni).")
        @Accordion
        public com.epic60869.sky2m.features.foraging.ForagingExtras.Config extras = new com.epic60869.sky2m.features.foraging.ForagingExtras.Config();
    }

    public static final class Enchanting {
        @Expose
        @ConfigOption(name = "Chronomatron Solver", desc = "Highlight the Chronomatron pattern.")
        @ConfigEditorBoolean
        public boolean chronomatron = true;

        @Expose
        @ConfigOption(name = "Superpairs Solver", desc = "Show revealed Superpairs cards.")
        @ConfigEditorBoolean
        public boolean superpairs = true;

        @Expose
        @ConfigOption(name = "Ultrasequencer Solver", desc = "Highlight the Ultrasequencer order.")
        @ConfigEditorBoolean
        public boolean ultrasequencer = true;

        @Expose
        @ConfigOption(name = "Ultrasequencer Numbers", desc = "Show the click order as numbers on every Ultrasequencer slot, instead of only highlighting the next one.")
        @ConfigEditorBoolean
        public boolean ultrasequencerNumbers = true;

        @Expose
        @ConfigOption(name = "Prevent Misclicks", desc = "Block incorrect Chronomatron and Ultrasequencer clicks, including clicks before the pattern is ready.")
        @ConfigEditorBoolean
        public boolean preventMisclicks = true;
    }

    /** SkyHanni's LoadoutHighlightingConfig (LGPL-2.1), without favourites. */
    public static final class LoadoutHighlight {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Enable highlighting of loadouts in the inventory.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Currently Equipped", desc = "Highlight the currently equipped loadout in the inventory.")
        @ConfigEditorBoolean
        public boolean currentlyEquipped = true;

        @Expose
        @ConfigOption(name = "Currently Equipped Color", desc = "The color used to highlight the currently equipped loadout in the inventory.")
        @ConfigEditorColour
        public String equippedColor = "0:170:85:255:85";
    }

    public static final class WardrobeHotkeys {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Use configurable keys to equip Wardrobe slots while its menu is open.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Loadout Hotkeys Enabled", desc = "Use configurable keys to equip Loadouts while the Loadouts menu is open.")
        @ConfigEditorBoolean
        public boolean loadoutEnabled = false;

        @Expose @ConfigOption(name = "Wardrobe Slot 1 Key", desc = "Select slot 1 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_1) public int slot1Key = InputConstants.KEY_1;
        @Expose @ConfigOption(name = "Wardrobe Slot 2 Key", desc = "Select slot 2 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_2) public int slot2Key = InputConstants.KEY_2;
        @Expose @ConfigOption(name = "Wardrobe Slot 3 Key", desc = "Select slot 3 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_3) public int slot3Key = InputConstants.KEY_3;
        @Expose @ConfigOption(name = "Wardrobe Slot 4 Key", desc = "Select slot 4 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_4) public int slot4Key = InputConstants.KEY_4;
        @Expose @ConfigOption(name = "Wardrobe Slot 5 Key", desc = "Select slot 5 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_5) public int slot5Key = InputConstants.KEY_5;
        @Expose @ConfigOption(name = "Wardrobe Slot 6 Key", desc = "Select slot 6 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_6) public int slot6Key = InputConstants.KEY_6;
        @Expose @ConfigOption(name = "Wardrobe Slot 7 Key", desc = "Select slot 7 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_7) public int slot7Key = InputConstants.KEY_7;
        @Expose @ConfigOption(name = "Wardrobe Slot 8 Key", desc = "Select slot 8 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_8) public int slot8Key = InputConstants.KEY_8;
        @Expose @ConfigOption(name = "Wardrobe Slot 9 Key", desc = "Select slot 9 on the current Wardrobe page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_9) public int slot9Key = InputConstants.KEY_9;

        @Expose @ConfigOption(name = "Loadout Slot 1 Key", desc = "Select loadout 1 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_1) public int loadoutSlot1Key = InputConstants.KEY_1;
        @Expose @ConfigOption(name = "Loadout Slot 2 Key", desc = "Select loadout 2 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_2) public int loadoutSlot2Key = InputConstants.KEY_2;
        @Expose @ConfigOption(name = "Loadout Slot 3 Key", desc = "Select loadout 3 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_3) public int loadoutSlot3Key = InputConstants.KEY_3;
        @Expose @ConfigOption(name = "Loadout Slot 4 Key", desc = "Select loadout 4 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_4) public int loadoutSlot4Key = InputConstants.KEY_4;
        @Expose @ConfigOption(name = "Loadout Slot 5 Key", desc = "Select loadout 5 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_5) public int loadoutSlot5Key = InputConstants.KEY_5;
        @Expose @ConfigOption(name = "Loadout Slot 6 Key", desc = "Select loadout 6 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_6) public int loadoutSlot6Key = InputConstants.KEY_6;
        @Expose @ConfigOption(name = "Loadout Slot 7 Key", desc = "Select loadout 7 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_7) public int loadoutSlot7Key = InputConstants.KEY_7;
        @Expose @ConfigOption(name = "Loadout Slot 8 Key", desc = "Select loadout 8 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_8) public int loadoutSlot8Key = InputConstants.KEY_8;
        @Expose @ConfigOption(name = "Loadout Slot 9 Key", desc = "Select loadout 9 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_9) public int loadoutSlot9Key = InputConstants.KEY_9;
        @Expose @ConfigOption(name = "Loadout Slot 10 Key", desc = "Select loadout 10 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_0) public int loadoutSlot10Key = InputConstants.KEY_0;
        @Expose @ConfigOption(name = "Loadout Slot 11 Key", desc = "Select loadout 11 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_MINUS) public int loadoutSlot11Key = InputConstants.KEY_MINUS;
        @Expose @ConfigOption(name = "Loadout Slot 12 Key", desc = "Select loadout 12 on the current page.") @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_EQUALS) public int loadoutSlot12Key = InputConstants.KEY_EQUALS;
    }

    public static final class CaseOpening {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Open Obsidian and Bedrock reward chests like a CS2 case (SkyOcean's Dungeon Gambling): the items spin past and stop on the best one. Esc skips it.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "All Chests", desc = "Spin every reward chest, not only Obsidian and Bedrock.")
        @ConfigEditorBoolean
        public boolean allChests = false;

        @Expose
        @ConfigOption(name = "Croesus", desc = "Also spin the chests you open at Croesus in the Dungeon Hub, with that run's floor drops.")
        @ConfigEditorBoolean
        public boolean croesus = false;

        @Expose
        @ConfigOption(name = "Hide Contents In Croesus", desc = "In Croesus's menu for a run, hide what's in the chests that will spin, so the spin shows you.")
        @ConfigEditorBoolean
        public boolean hideCroesusContents = true;

        @Expose
        @ConfigOption(name = "Seconds", desc = "How long the spin takes.")
        @ConfigEditorSlider(minValue = 2, maxValue = 12, minStep = 1)
        public int seconds = 6;

        @Expose
        @ConfigOption(name = "Gold Sound", desc = "Play the \"GOLD GOLD GOLD\" sound as soon as a chest whose best item is Legendary (gold) or better starts spinning.")
        @ConfigEditorBoolean
        public boolean goldSound = true;
    }

    public static final class DungeonMap {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the dungeon map.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Map Style", desc = "Which mod's dungeon map to draw: Skyblocker's, Stella's or Devonian's. The Dungeon Sim draws the same style.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.dungeons.mapstyle.DungeonMapStyle style = com.epic60869.sky2m.features.dungeons.mapstyle.DungeonMapStyle.SKYBLOCKER;

        @Expose
        @ConfigOption(name = "Fancy Map", desc = "Show player heads and room colours on the map.")
        @ConfigEditorBoolean
        public boolean fancy = true;

        @Expose
        @ConfigOption(name = "Room Labels", desc = "Show room names and secret counts on the map.")
        @ConfigEditorBoolean
        public boolean roomLabels = true;

        @Expose
        @ConfigOption(name = "Background", desc = "Draw a blurred background behind the dungeon map.")
        @ConfigEditorBoolean
        public boolean background = false;

        @Expose public int x = 2;
        @Expose public int y = 2;
        @Expose public float scale = 1f;
    }

    /** Ice Fill, Boulder, Creeper Beams, Three Weirdos, Quiz, Teleport Maze, Water Board and Blaze use Odin's solvers. */
    public static final class Puzzles {

        @ConfigOption(name = "Water Board", desc = "The water board solver and its previews.")
        @ConfigEditorAccordion(id = 1401)
        public boolean waterBoardGroup = false;
        @Expose @ConfigOption(name = "Water Board", desc = "Odin: when to flip each lever, with CLICK ME! and countdowns, and a line to the next lever.") @ConfigEditorBoolean @ConfigAccordionId(id = 1401) public boolean waterBoard = false;
        @Expose @ConfigOption(name = "Water Board Optimized", desc = "Use Odin's faster Water Board solutions.") @ConfigEditorBoolean @ConfigAccordionId(id = 1401) public boolean waterOptimized = false;
        @Expose @ConfigOption(name = "Water Board Path Preview", desc = "Skyblocker: show where the water will flow on the board right now.") @ConfigEditorBoolean @ConfigAccordionId(id = 1401) public boolean waterPreviewPath = true;
        @Expose @ConfigOption(name = "Water Board Lever Preview", desc = "Skyblocker: while looking at a lever, show which blocks flipping it adds (green) and removes (red).") @ConfigEditorBoolean @ConfigAccordionId(id = 1401) public boolean waterPreviewLevers = true;

        @ConfigOption(name = "Blaze", desc = "The higher or lower blaze solver.")
        @ConfigEditorAccordion(id = 1402)
        public boolean blazeGroup = false;
        @Expose @ConfigOption(name = "Blaze", desc = "Odin: the next three blazes to shoot (green, orange, white) with lines between them.") @ConfigEditorBoolean @ConfigAccordionId(id = 1402) public boolean blaze = false;
        @Expose @ConfigOption(name = "Show All Blazes", desc = "Also box every other blaze.") @ConfigEditorBoolean @ConfigAccordionId(id = 1402) public boolean blazeShowAll = false;

        @ConfigOption(name = "Boulder", desc = "The boulder solver.")
        @ConfigEditorAccordion(id = 1403)
        public boolean boulderGroup = false;
        @Expose @ConfigOption(name = "Boulder", desc = "Odin: the boulder to push next. The box clears when you click its button.") @ConfigEditorBoolean @ConfigAccordionId(id = 1403) public boolean boulder = false;
        @Expose @ConfigOption(name = "Show All Boulder Clicks", desc = "Show every boulder to push instead of only the next one.") @ConfigEditorBoolean @ConfigAccordionId(id = 1403) public boolean boulderShowAll = false;

        @ConfigOption(name = "Ice Fill", desc = "The ice fill solver.")
        @ConfigEditorAccordion(id = 1404)
        public boolean iceFillGroup = false;
        @Expose @ConfigOption(name = "Ice Fill", desc = "Odin: the path over each Ice Fill floor.") @ConfigEditorBoolean @ConfigAccordionId(id = 1404) public boolean iceFill = false;
        @Expose @ConfigOption(name = "Ice Fill Optimized Patterns", desc = "Use Odin's shorter (harder) Ice Fill paths.") @ConfigEditorBoolean @ConfigAccordionId(id = 1404) public boolean iceFillOptimized = false;

        @ConfigOption(name = "Other Puzzles", desc = "Tic Tac Toe, Three Weirdos, Quiz, Creeper Beams, Silverfish and Teleport Maze.")
        @ConfigEditorAccordion(id = 1405)
        public boolean otherPuzzlesGroup = false;
        @Expose @ConfigOption(name = "Tic Tac Toe", desc = "Show the best move.") @ConfigEditorBoolean @ConfigAccordionId(id = 1405) public boolean ticTacToe = false;
        @Expose @ConfigOption(name = "Three Weirdos", desc = "Odin: the chest with the reward in green, wrong chests in red.") @ConfigEditorBoolean @ConfigAccordionId(id = 1405) public boolean threeWeirdos = false;
        @Expose @ConfigOption(name = "Quiz", desc = "Odin: a box and beam on the right answer.") @ConfigEditorBoolean @ConfigAccordionId(id = 1405) public boolean trivia = false;
        @Expose @ConfigOption(name = "Creeper Beams", desc = "Odin: each pair of lanterns to connect in its own colour, with a line between them.") @ConfigEditorBoolean @ConfigAccordionId(id = 1405) public boolean creeperBeams = false;
        @Expose @ConfigOption(name = "Silverfish", desc = "Show the path.") @ConfigEditorBoolean @ConfigAccordionId(id = 1405) public boolean silverfish = false;
        @Expose @ConfigOption(name = "Teleport Maze", desc = "Odin: visited pads in red, the right pad in green (orange while there are several), and a line to the best next pad.") @ConfigEditorBoolean @ConfigAccordionId(id = 1405) public boolean teleportMaze = false;
    }

    public static final class Secrets {

        @ConfigOption(name = "Waypoints & Routes", desc = "Waypoints and secret routes for each room.")
        @ConfigEditorAccordion(id = 1501)
        public boolean secretWaypointsGroup = false;
        @Expose
        @ConfigOption(name = "Secret Waypoints", desc = "Show waypoints for each room's secrets.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1501)
        public boolean secretWaypoints = false;

        @Expose
        @ConfigOption(name = "Waypoint Type", desc = "How dungeon secret waypoints are drawn. Outline: a clean boxless marker. Highlight: a filled box. Waypoint: a filled box with a beacon beam. A + adds an outline.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 1501)
        public Waypoint.Type waypointType = Waypoint.Type.OUTLINE;

        @Expose
        @ConfigOption(name = "Show Routes", desc = "Show a secret route for the current room: yours if you recorded one, otherwise Stella's. Record with /s2 route start and /s2 route stop; share with /s2 export; import a Stella (or SecretRoutes) export with /s2 route import (clipboard) or /s2 route import <file>.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1501)
        public boolean routes = false;

        @ConfigOption(name = "Secrets Display", desc = "The secrets found HUD.")
        @ConfigEditorAccordion(id = 1502)
        public boolean secretsDisplayGroup = false;

        @Expose
        @ConfigOption(name = "Secrets Display", desc = "SkyblockAddons' Secrets Display: how many of the room's secrets you've found (2/5), with a chest icon, going from red to green. Move it in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1502)
        public boolean secretsDisplay = false;

        @Expose
        @ConfigOption(name = "Hide Secrets In Action Bar", desc = "While the Secrets Display is on, take \"2/5 Secrets\" out of the action bar, like SkyblockAddons.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1502)
        public boolean hideSecretsInActionBar = true;

        @ConfigOption(name = "Secret Boxes", desc = "Boxes around secrets you can click.")
        @ConfigEditorAccordion(id = 1503)
        public boolean secretBoxesGroup = false;

        @Expose
        @ConfigOption(name = "Secret Boxes", desc = "Odin's Secret Boxes: a highlight box on each secret you get for a few seconds (red when the chest is locked).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1503)
        public boolean secretBoxes = true;

        @Expose
        @ConfigOption(name = "Box Colour", desc = "The secret box's colour.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 1503)
        public String secretBoxColour = "0:204:255:170:0";

        @Expose
        @ConfigOption(name = "Locked Colour", desc = "The box's colour when the chest is locked.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 1503)
        public String secretBoxLockedColour = "0:204:255:85:85";

        @Expose
        @ConfigOption(name = "Box Time", desc = "Seconds the box stays.")
        @ConfigEditorSlider(minValue = 1, maxValue = 120, minStep = 0.5f)
        @ConfigAccordionId(id = 1503)
        public float secretBoxSeconds = 7;

        @Expose
        @ConfigOption(name = "Boxes Through Walls", desc = "Show the boxes through walls.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1503)
        public boolean secretBoxThroughWalls = false;

        @Expose
        @ConfigOption(name = "Item Boxes", desc = "Also box dungeon items you pick up.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1503)
        public boolean secretBoxItems = true;

        @Expose
        @ConfigOption(name = "Boxes In Boss", desc = "Also box chests and levers in the boss room.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1503)
        public boolean secretBoxInBoss = false;

        @ConfigOption(name = "Secret Chime", desc = "A sound when you collect a secret.")
        @ConfigEditorAccordion(id = 1504)
        public boolean secretChimeGroup = false;

        @Expose
        @ConfigOption(name = "Secret Chime", desc = "Odin's Secret Chime: a sound when you get a secret (a chest, lever, wither essence or redstone key, a dungeon item picked up, or a secret bat killed).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1504)
        public boolean secretChime = true;

        @Expose
        @ConfigOption(name = "Chime Sound", desc = "The sound's id, like Odin's default entity.blaze.hurt, or entity.experience_orb.pickup, block.note_block.pling...")
        @ConfigEditorText
        @ConfigAccordionId(id = 1504)
        public String secretChimeSound = "entity.blaze.hurt";

        @Expose
        @ConfigOption(name = "Chime Pitch", desc = "Pitch of the chime.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 2f, minStep = 0.01f)
        @ConfigAccordionId(id = 1504)
        public float secretChimePitch = 1f;

        @Expose
        @ConfigOption(name = "Chime Volume", desc = "Volume of the chime.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 1f, minStep = 0.01f)
        @ConfigAccordionId(id = 1504)
        public float secretChimeVolume = 1f;

        @Expose
        @ConfigOption(name = "Chime In Boss", desc = "Also chime for chests and levers in the boss room.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1504)
        public boolean secretChimeInBoss = false;

        @ConfigOption(name = "Play Chime", desc = "Plays the chime with these settings.")
        @ConfigEditorButton(buttonText = "PLAY")
        @ConfigAccordionId(id = 1504)
        public Runnable playChime = com.epic60869.sky2m.features.dungeons.SecretChime::play;

        @ConfigOption(name = "Doors & Keys", desc = "Door and key highlights, and announcing keys.")
        @ConfigEditorAccordion(id = 1505)
        public boolean doorsKeysGroup = false;

        @Expose
        @ConfigOption(name = "Door Highlight", desc = "Highlight wither and blood doors through walls once they show on your dungeon map (a room next to them is opened): green when your team has the key, red when locked.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1505)
        public boolean doorHighlight = false;

        @Expose
        @ConfigOption(name = "Key Highlight", desc = "Outline dropped Wither and Blood keys, visible through walls.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1505)
        public boolean keyHighlight = false;

        @Expose
        @ConfigOption(name = "Announce Key Spawn", desc = "Show a title when a Wither or Blood key spawns.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1505)
        public boolean announceKeySpawn = false;

        @ConfigOption(name = "Room Clear Alert", desc = "An alert when a room is cleared.")
        @ConfigEditorAccordion(id = 1506)
        public boolean roomClearAlertGroup = false;

        @Expose
        @ConfigOption(name = "Room Clear Alert", desc = "Odin's Room Clear: a title and a ding when the room you're in is cleared (white checkmark, \"Room Cleared!\") or has all its secrets done (green checkmark, \"Room Complete!\"). Not for the entrance or fairy room.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1506)
        public boolean roomClearAlert = false;

        @Expose
        @ConfigOption(name = "Room Clear Alert Mode", desc = "Which checkmarks to alert on. Secrets: all secrets done (green). Cleared: room cleared (white). Both: either.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 1506)
        public RoomClearMode roomClearMode = RoomClearMode.BOTH;
    }

    public enum RoomClearMode {
        BOTH("Both"), GREEN("Secrets"), WHITE("Cleared");

        private final String label;

        RoomClearMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class DungeonMobs {

        @ConfigOption(name = "Starred Mobs", desc = "Highlighting starred mobs.")
        @ConfigEditorAccordion(id = 1601)
        public boolean starredMobsGroup = false;
        @Expose
        @ConfigOption(name = "Highlight Starred Mobs", desc = "Draw a box around starred (✯) dungeon mobs you can see. Hidden behind walls.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1601)
        public boolean starredMobs = false;

        @Expose
        @ConfigOption(name = "Starred Mob Colour", desc = "Colour of the box around starred mobs.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 1601)
        public String starredColor = "0:255:255:217:51";

        @Expose
        @ConfigOption(name = "Fill Opacity", desc = "How see-through the fill inside the box is. 0 draws only the outline.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1f, minStep = 0.05f)
        @ConfigAccordionId(id = 1601)
        public float starredFill = 0f;

        @Expose
        @ConfigOption(name = "Line Width", desc = "Thickness of the box outline.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 5f, minStep = 0.5f)
        @ConfigAccordionId(id = 1601)
        public float starredLineWidth = 2f;

        @ConfigOption(name = "Teammates & Bats", desc = "Highlighting your teammates and bats.")
        @ConfigEditorAccordion(id = 1602)
        public boolean teammatesBatsGroup = false;

        @Expose
        @ConfigOption(name = "Highlight Teammates", desc = "Outline your dungeon teammates in their class colour, the same colours as the Leap Menu (Mage blue, Tank green...; change them under Leap Menu). Shows through walls.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1602)
        public boolean teammates = false;

        @Expose
        @ConfigOption(name = "Highlight Bats", desc = "Draw a box around bats in dungeons (secret bats), hidden behind walls.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1602)
        public boolean bats = true;

        @Expose
        @ConfigOption(name = "Bat Colour", desc = "Colour of the box around bats.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 1602)
        public String batColor = "0:255:85:255:255";

        @ConfigOption(name = "Withers", desc = "Highlighting withers.")
        @ConfigEditorAccordion(id = 1603)
        public boolean withersGroup = false;

        @Expose
        @ConfigOption(name = "Highlight Withers", desc = "Draw a box around the F7/M7 boss withers (Maxor, Storm, Goldor, Necron) you can see. Hidden behind walls, so it isn't ESP.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1603)
        public boolean withers = false;

        @Expose
        @ConfigOption(name = "Wither Colour", desc = "Colour of the box around the withers.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 1603)
        public String witherColor = "0:255:170:0:255";
    }

    public enum TerminalStyle {
        ODIN("Odin"), NOAMM("Noamm");

        private final String label;

        TerminalStyle(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum NoammSlotStyle {
        RECT("Rect"), BORDERED("Bordered"), BUTTON("Button");

        private final String label;

        NoammSlotStyle(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class Terminals {
        @Expose @ConfigOption(name = "Terminal Solver Enabled", desc = "Solve the F7/M7 terminals (Correct all the panes, Change all to same color, Click in order, What starts with, Select all). Off: terminals look and work like normal.") @ConfigEditorBoolean public boolean enabled = true;
        @Expose @ConfigOption(name = "Terminal Solver", desc = "Which terminal solver to use. Odin: covers the terminal and shows what to click. NoammAddons: its big centred panel with the terminal's name, slot styles and colours.") @ConfigEditorDropdown public TerminalStyle solverStyle = TerminalStyle.NOAMM;
        @Expose @ConfigOption(name = "NoammAddons: Scale", desc = "Size of the NoammAddons terminal panel.") @ConfigEditorSlider(minValue = 0.3f, maxValue = 2f, minStep = 0.05f) public float noammScale = 1f;
        @Expose @ConfigOption(name = "NoammAddons: Slot Style", desc = "How solution slots are drawn in the NoammAddons panel.") @ConfigEditorDropdown public NoammSlotStyle noammSlotStyle = NoammSlotStyle.RECT;
        @Expose @ConfigOption(name = "NoammAddons: Show Numbers", desc = "Show the number on each slot in Click in order!") @ConfigEditorBoolean public boolean noammShowNumbers = false;
        @Expose @ConfigOption(name = "Block Misclicks", desc = "In terminals, ignore clicks on slots that aren't part of the solution (and wrong-button rubix clicks).") @ConfigEditorBoolean public boolean blockMisclicks = true;
        @Expose @ConfigOption(name = "Client Prediction", desc = "Update the solution as soon as you click instead of waiting for the server.") @ConfigEditorBoolean public boolean clickPrediction = true;
        @Expose @ConfigOption(name = "Resolve Timeout (ms)", desc = "How long a predicted click waits for the server before the terminal is re-read.") @ConfigEditorSlider(minValue = 300, maxValue = 1200, minStep = 10) public int resolveTimeout = 600;
        @Expose @ConfigOption(name = "First Click Protection (ms)", desc = "Clicks this soon after a terminal opens are ignored (Odin recommends 500 minus your ping).") @ConfigEditorSlider(minValue = 350, maxValue = 800, minStep = 10) public int firstClickProt = 500;
        @Expose @ConfigOption(name = "Account For Server Lag", desc = "Also block clicks until the terminal has been open for the ticks below.") @ConfigEditorBoolean public boolean lagProtection = false;
        @Expose @ConfigOption(name = "Lag Protection Ticks", desc = "Server ticks (50ms each) before clicks go through.") @ConfigEditorSlider(minValue = 7, maxValue = 16, minStep = 1) public int lagProtectionTicks = 8;
        @Expose @ConfigOption(name = "Rubix Left Clicks Only", desc = "Only use left clicks for the rubix terminal instead of the fewest clicks.") @ConfigEditorBoolean public boolean rubixLeftClicksOnly = false;
        @Expose @ConfigOption(name = "Melody Solver", desc = "Show the melody solver.") @ConfigEditorBoolean public boolean melodySolver = false;
        @Expose @ConfigOption(name = "Terminal Click Sound", desc = "Play a sound when you click a right slot in a terminal, and mute Hypixel's terminal click sound (Odin's Terminal Sounds).") @ConfigEditorBoolean public boolean clickSound = false;
        @Expose @ConfigOption(name = "Terminal Complete Sound", desc = "Play a sound when you finish a terminal.") @ConfigEditorBoolean public boolean completeSound = false;
        @Expose @ConfigOption(name = "Odin Device Solvers", desc = "Odin's Simon Says, Arrow Align and Sharp Shooter (i4) solvers. Off: Skyblocker's. Set this to false if you want the NoammAddons path.") @ConfigEditorBoolean public boolean odinDevices = false;
        @Expose @ConfigOption(name = "SS Block Wrong Clicks", desc = "Simon Says: ignore clicks on any button but the next one. Hold shift to click anyway.") @ConfigEditorBoolean public boolean ssBlockWrong = true;
        @Expose @ConfigOption(name = "SS Announce Progress", desc = "Send \"SS n/4\" to party chat when you click the last button of a round.") @ConfigEditorBoolean public boolean ssAnnounce = false;
        @Expose @ConfigOption(name = "SS First", desc = "") @ConfigEditorColour public String ssFirstColor = "0:128:85:255:85";
        @Expose @ConfigOption(name = "SS Second", desc = "") @ConfigEditorColour public String ssSecondColor = "0:128:255:170:0";
        @Expose @ConfigOption(name = "SS Rest", desc = "") @ConfigEditorColour public String ssThirdColor = "0:128:255:85:85";
        @Expose @ConfigOption(name = "Arrow Align Block Wrong Clicks", desc = "Arrow Align: ignore clicks on frames that are already right. Hold shift to click anyway.") @ConfigEditorBoolean public boolean arrowAlignBlockWrong = true;
        @Expose @ConfigOption(name = "i4 Aim Positions", desc = "Sharp Shooter: also show the three best places to aim to hit two blocks at once.") @ConfigEditorBoolean public boolean i4AimPositions = false;
        @Expose @ConfigOption(name = "i4 Complete Alert", desc = "Sharp Shooter: show a title when you complete the device.") @ConfigEditorBoolean public boolean i4CompleteAlert = false;
        @Expose @ConfigOption(name = "Background", desc = "") @ConfigEditorColour public String backgroundColor = "0:128:38:38:38";
        @Expose @ConfigOption(name = "Panes", desc = "") @ConfigEditorColour public String panesColor = "0:255:85:255:85";
        @Expose @ConfigOption(name = "Rubix 1", desc = "") @ConfigEditorColour public String rubix1Color = "0:255:85:255:85";
        @Expose @ConfigOption(name = "Rubix 2", desc = "") @ConfigEditorColour public String rubix2Color = "0:255:42:127:42";
        @Expose @ConfigOption(name = "Rubix -1", desc = "") @ConfigEditorColour public String rubixMinus1Color = "0:255:170:0:0";
        @Expose @ConfigOption(name = "Rubix -2", desc = "") @ConfigEditorColour public String rubixMinus2Color = "0:255:85:0:0";
        @Expose @ConfigOption(name = "Numbers Next", desc = "Click in order: the slot to click now.") @ConfigEditorColour public String numbersNextColor = "0:255:85:255:85";
        @Expose @ConfigOption(name = "Numbers Second", desc = "Click in order: the slot after that.") @ConfigEditorColour public String numbersSecondColor = "0:255:255:255:85";
        @Expose @ConfigOption(name = "Numbers Third", desc = "Click in order: the third slot.") @ConfigEditorColour public String numbersThirdColor = "0:255:255:85:85";
        @Expose @ConfigOption(name = "Starts With", desc = "") @ConfigEditorColour public String startsWithColor = "0:255:85:255:85";
        @Expose @ConfigOption(name = "Select", desc = "") @ConfigEditorColour public String selectColor = "0:255:85:255:85";
        @Expose @ConfigOption(name = "Melody Column", desc = "") @ConfigEditorColour public String melodyColumnColor = "0:255:170:0:170";
        @Expose @ConfigOption(name = "Melody Pointer", desc = "") @ConfigEditorColour public String melodyPointerColor = "0:255:85:255:85";
        @Expose @ConfigOption(name = "Melody Background", desc = "The Melody lanes and buttons that aren't lit (Odin style).") @ConfigEditorColour public String melodyBackgroundColor = "0:255:38:38:38";
        @Expose @ConfigOption(name = "Simon Says", desc = "Highlight the buttons to press.") @ConfigEditorBoolean public boolean simonSays = false;
        @Expose @ConfigOption(name = "Lights On", desc = "Highlight the levers to flip.") @ConfigEditorBoolean public boolean lightsOn = false;
        @Expose @ConfigOption(name = "Arrow Align", desc = "Show how many clicks each frame needs.") @ConfigEditorBoolean public boolean arrowAlign = false;
        @Expose @ConfigOption(name = "Target Practice (i4)", desc = "Highlight the targets to shoot.") @ConfigEditorBoolean public boolean targetPractice = false;
    }

    public static final class BossPhases {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the Boss Phases HUD (move it in /s2 gui) during F1-F7 and M1-M7 boss fights: the boss, its current phase and how long it's been going. Phases change on the bosses' [BOSS] lines.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show Previous Phases", desc = "List the phases already done this fight with their times.")
        @ConfigEditorBoolean
        public boolean showPrevious = true;

        @Expose
        @ConfigOption(name = "Show Attack Timers", desc = "Countdowns for attacks on a fixed timer: Livid's invulnerability, Storm's pillars and lightning (PY), and Goldor's death tick.")
        @ConfigEditorBoolean
        public boolean showCooldowns = true;

        @Expose
        @ConfigOption(name = "Show Records", desc = "Show your best time next to each phase and the whole fight, green when you're faster. Records are saved in config/sky2m/boss-phases.json; /s2 resetbossrecords clears them.")
        @ConfigEditorBoolean
        public boolean showRecords = true;

        @Expose
        @ConfigOption(name = "Record Messages", desc = "A chat message when you beat a phase or fight record, with the time saved (only you see it).")
        @ConfigEditorBoolean
        public boolean recordMessages = true;

        @Expose
        @ConfigOption(name = "Phase Alert Title", desc = "Show the new phase's name as a title when the phase changes.")
        @ConfigEditorBoolean
        public boolean alertTitle = false;

        @Expose
        @ConfigOption(name = "Phase Alert Sound", desc = "Play a ding when the phase changes.")
        @ConfigEditorBoolean
        public boolean alertSound = false;

        @Expose
        @ConfigOption(name = "Party Message", desc = "Send the finished phase's time to party chat when the phase changes.")
        @ConfigEditorBoolean
        public boolean partyMessage = false;

        @Expose @ConfigOption(name = "Bonzo (F1/M1)", desc = "Track Bonzo's fight.") @ConfigEditorBoolean public boolean bonzo = true;
        @Expose @ConfigOption(name = "Scarf (F2/M2)", desc = "Track Scarf's fight.") @ConfigEditorBoolean public boolean scarf = true;
        @Expose @ConfigOption(name = "The Professor (F3/M3)", desc = "Track The Professor's fight.") @ConfigEditorBoolean public boolean professor = true;
        @Expose @ConfigOption(name = "Thorn (F4/M4)", desc = "Track Thorn's fight (one phase).") @ConfigEditorBoolean public boolean thorn = true;
        @Expose @ConfigOption(name = "Livid (F5/M5)", desc = "Track Livid's fight (one phase).") @ConfigEditorBoolean public boolean livid = true;
        @Expose @ConfigOption(name = "Sadan (F6/M6)", desc = "Track Sadan's fight.") @ConfigEditorBoolean public boolean sadan = true;
        @Expose @ConfigOption(name = "Necron (F7/M7)", desc = "Track the F7/M7 fight: Maxor, Storm, terminal sections, Goldor, Necron and (M7) the Wither King.") @ConfigEditorBoolean public boolean necron = true;
    }

    public static final class Timers {

        @Expose
        @Accordion
        @ConfigOption(name = "Run History", desc = "Save every dungeon run and see them with /s2 runs, with personal bests per floor (Killer560's).")
        public RunHistory runHistory = new RunHistory();

        @Expose
        @Accordion
        @ConfigOption(name = "Terminal Timers", desc = "F7/M7 P3 terminal, device, section and phase times in chat and a HUD (Killer560's, from Odin).")
        public TerminalTimers terminalTimers = new TerminalTimers();

        @Expose
        @Accordion
        @ConfigOption(name = "Rag Axe", desc = "Ragnarock Axe cast, buff and cooldown timers, and \"rag now\" prompts in boss fights (Killer560's).")
        public RagAxe ragAxe = new RagAxe();

        @ConfigOption(name = "Splits", desc = "Run splits and their messages.")
        @ConfigEditorAccordion(id = 1701)
        public boolean splitsGroup = false;
        @Expose
        @ConfigOption(name = "Splits", desc = "Odin-style split HUD (Blood Open, Blood Clear, Portal Entry, each boss phase, Total) with personal bests per floor and a chat message after each split.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1701)
        public boolean splits = false;

        @Expose
        @ConfigOption(name = "Split Messages", desc = "Send \"<split> took <time>\" with your PB to chat when a split finishes.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1701)
        public boolean splitMessages = true;

        @Expose
        @ConfigOption(name = "Boss Entry Split", desc = "Add a Boss Entry row (Blood Open + Blood Clear + Portal Entry) to the split HUD.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1701)
        public boolean bossEntrySplit = true;

        @Expose
        @ConfigOption(name = "Show Tick Time", desc = "Show the split time counted in server ticks next to the real time.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1701)
        public boolean splitTickTime = true;

        @Expose
        @ConfigOption(name = "Show ETA", desc = "Show an estimated finish time under the split HUD, from your average split times for this floor in Run History (or your personal bests).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1701)
        public boolean splitEta = true;

        @ConfigOption(name = "Tick Timers", desc = "Tick timers, the Storm PY timer and the max debuff alert.")
        @ConfigEditorAccordion(id = 1702)
        public boolean tickTimersGroup = false;

        @Expose
        @ConfigOption(name = "Tick Timers", desc = "HUD for Storm's pillars (20 ticks) and Goldor's death tick (every 60 ticks, from Storm's death until the core opens).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1702)
        public boolean tickTimers = false;

        @Expose
        @ConfigOption(name = "Maxor Start", desc = "Tick Timers: count down the 83 server ticks from Maxor's first line until the fight starts (NoammAddons).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1702)
        public boolean maxorStart = true;

        @Expose
        @ConfigOption(name = "Storm Lightning", desc = "Tick Timers: count down the 520 server ticks from Storm's first line until his lightning (Odin).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1702)
        public boolean stormLightning = true;

        @Expose
        @ConfigOption(name = "Goldor Start", desc = "Tick Timers: count down the 64 server ticks from Storm's death until terminals and devices can be done (Odin's Start timer).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1702)
        public boolean goldorStart = true;

        @Expose
        @ConfigOption(name = "Storm PY Timer", desc = "Odin's Storm PY HUD: when Storm calls his lightning (\"ENERGY HEED MY CALL!\" / \"THUNDER LET ME BE YOUR CATALYST!\"), counts down 55 ticks to when to crush him under the purple pillar. Move it in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1702)
        public boolean pyTimer = false;

        @Expose
        @ConfigOption(name = "Max Debuff Alert", desc = "Alert when your own Last Breath shots (5), Ice Spray uses (1) and Lethality hits (5) reach the max debuff on an M7 dragon. Counts reset when a new dragon spawns.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1702)
        public boolean debuffAlert = false;

        @ConfigOption(name = "Masks", desc = "Mask cooldown timers and proc alerts.")
        @ConfigEditorAccordion(id = 1703)
        public boolean masksGroup = false;

        @Expose
        @ConfigOption(name = "Mask Timers", desc = "HUD with the Spirit Mask, Bonzo's Mask and Phoenix pet: invincibility time (gold), cooldown (red) or ready (green), counted in server ticks. Your worn mask is marked with a purple bar.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1703)
        public boolean maskTimers = false;

        @Expose
        @ConfigOption(name = "Mask Proc Alert", desc = "Show a title and play a sound when a mask or Phoenix procs.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1703)
        public boolean maskAlert = false;

        @Expose
        @ConfigOption(name = "Announce Mask Procs", desc = "Send \"<Mask> Procced! (n/3)\" to party chat when one of your masks or Phoenix procs.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1703)
        public boolean maskAnnounce = false;

        @ConfigOption(name = "Last Breath", desc = "When to release Last Breath.")
        @ConfigEditorAccordion(id = 1704)
        public boolean lastBreathGroup = false;

        @Expose
        @ConfigOption(name = "Last Breath Release", desc = "Play a sound and show RELEASE once you have charged Last Breath for the set number of server ticks.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1704)
        public boolean lastBreathRelease = false;

        @Expose
        @ConfigOption(name = "Last Breath Ticks", desc = "Server ticks of charging before the release cue.")
        @ConfigEditorSlider(minValue = 1, maxValue = 20, minStep = 1)
        @ConfigAccordionId(id = 1704)
        public float lastBreathTicks = 5;

        @Expose
        @ConfigOption(name = "Last Breath Sound", desc = "Sound played with RELEASE.")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 1704)
        public ReleaseSound lastBreathSound = ReleaseSound.BELL;

        @Expose
        @ConfigOption(name = "Last Breath Volume", desc = "Volume of the RELEASE sound.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 1f, minStep = 0.05f)
        @ConfigAccordionId(id = 1704)
        public float lastBreathVolume = 1f;
    }

    public enum ReleaseSound {
        BELL("Bell"), NOTE_BELL("Note Bell"), DING("Ding"), ORB("XP Orb"), NONE("None");

        private final String label;

        ReleaseSound(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class Score {

        @ConfigOption(name = "Score Display", desc = "The score HUD.")
        @ConfigEditorAccordion(id = 1801)
        public boolean scoreDisplayGroup = false;

        @Expose
        @ConfigOption(name = "Score Display", desc = "NoammAddons' score HUD: the estimated score, coloured red below 270, yellow below 300 and green at 300.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1801)
        public boolean display = false;

        @Expose
        @ConfigOption(name = "Detailed Score Display", desc = "Also show secrets, crypts, deaths and mimic/prince under the score, like the info under NoammAddons' map.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1801)
        public boolean detailed = false;

        @Expose
        @ConfigOption(name = "Force Paul", desc = "Count Paul's +10 bonus score even when the EZPZ perk isn't detected.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1801)
        public boolean forcePaul = false;

        @ConfigOption(name = "Score Alerts", desc = "Alerts at 270 and 300 score.")
        @ConfigEditorAccordion(id = 1802)
        public boolean scoreAlertsGroup = false;
        @Expose
        @ConfigOption(name = "270 Score Alert", desc = "Show a title and play a sound when the run reaches 270 score (S).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1802)
        public boolean alert270 = false;

        @Expose
        @ConfigOption(name = "300 Score Alert", desc = "Show a title and play a sound when the run reaches 300 score (S+).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1802)
        public boolean alert300 = false;

        @Expose
        @ConfigOption(name = "Score Time Message", desc = "Like NoammAddons: a chat message when the run reaches 270 and 300 score, with how long it took and the floor (\"300 score reached in 6m 12s || M7.\"). Only you see it.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1802)
        public boolean timeMessage = false;

        @ConfigOption(name = "Party Messages", desc = "Telling your party at 270 and 300 score.")
        @ConfigEditorAccordion(id = 1803)
        public boolean partyMessagesGroup = false;

        @Expose
        @ConfigOption(name = "Send 270 to Party", desc = "Also send \"[S2M] 270 Score Reached!\" to party chat.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1803)
        public boolean party270 = false;

        @Expose
        @ConfigOption(name = "Send 300 to Party", desc = "Also send \"[S2M] 300 Score Reached!\" to party chat.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1803)
        public boolean party300 = false;

        @Expose
        @ConfigOption(name = "270 Message", desc = "Text for the 270 title and party message. [score] is replaced with the score. Party messages start with [S2M].")
        @ConfigEditorText
        @ConfigAccordionId(id = 1803)
        public String message270 = "270 Score Reached!";

        @Expose
        @ConfigOption(name = "300 Message", desc = "Text for the 300 title and party message. [score] is replaced with the score. Party messages start with [S2M].")
        @ConfigEditorText
        @ConfigAccordionId(id = 1803)
        public String message300 = "300 Score Reached!";

        @ConfigOption(name = "Bonus Score Messages", desc = "Mimic, Prince and bat kills, and the crypts reminder.")
        @ConfigEditorAccordion(id = 1804)
        public boolean bonusMessagesGroup = false;

        @Expose
        @ConfigOption(name = "Mimic Message", desc = "Skyblocker's: say \"Mimic dead!\" in party chat when the Mimic dies near you, so everyone's score counts it. Only once a run, and not if a teammate already said it.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1804)
        public boolean mimicMessage = false;

        @Expose
        @ConfigOption(name = "Prince Message", desc = "Skyblocker's: say \"Prince dead!\" in party chat when you kill a Prince (Hypixel's \"A Prince falls. +1 Bonus Score\"). Only the first one, and not if a teammate already said it.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1804)
        public boolean princeMessage = false;

        @Expose
        @ConfigOption(name = "Bat Message", desc = "Skyblocker's: say \"Bat dead!\" in party chat on the first bonus score bat of the run (\"A Bat has been slain. +1 Bonus Score\"), unless a teammate already said it.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1804)
        public boolean batMessage = false;

        @Expose
        @ConfigOption(name = "Kill Titles", desc = "A title on your screen the first time the Mimic, a Prince or a bonus bat dies in a run, whoever killed it.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1804)
        public boolean killTitles = false;

        @Expose
        @ConfigOption(name = "Crypts Message", desc = "Skyblocker's: once the score reaches the threshold below with fewer than 5 crypts, tell your party how many crypts there are, once a run.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1804)
        public boolean cryptsMessage = false;

        @Expose
        @ConfigOption(name = "Crypts Threshold", desc = "The score that sends the crypts message.")
        @ConfigEditorSlider(minValue = 100, maxValue = 300, minStep = 5)
        @ConfigAccordionId(id = 1804)
        public int cryptsThreshold = 250;

        @Expose
        @ConfigOption(name = "Crypts Text", desc = "The crypts message. [crypts] is replaced with the number of crypts. It starts with [S2M].")
        @ConfigEditorText
        @ConfigAccordionId(id = 1804)
        public String cryptsText = "Crypts: [crypts]/5";
    }

    public enum ClassOverride {
        AUTO("Auto"), ARCHER("Archer"), BERSERK("Berserk"), HEALER("Healer"), MAGE("Mage"), TANK("Tank");

        private final String label;

        ClassOverride(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum LeapCorner {
        TOP_LEFT("Top Left"), TOP_RIGHT("Top Right"), BOTTOM_LEFT("Bot Left"), BOTTOM_RIGHT("Bot Right");

        private final String label;

        LeapCorner(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Defaults follow Odin's leap menu: class colours and quadrants. */
    public static final class LeapMenu {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Replace the Spirit Leap / Infinileap menu with four large boxes. Click a box to leap to that teammate.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Top Left Key", desc = "Leap to the teammate in the top left box.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_1)
        public int keyTopLeft = InputConstants.KEY_1;

        @Expose
        @ConfigOption(name = "Top Right Key", desc = "Leap to the teammate in the top right box.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_2)
        public int keyTopRight = InputConstants.KEY_2;

        @Expose
        @ConfigOption(name = "Bottom Left Key", desc = "Leap to the teammate in the bottom left box.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_3)
        public int keyBottomLeft = InputConstants.KEY_3;

        @Expose
        @ConfigOption(name = "Bottom Right Key", desc = "Leap to the teammate in the bottom right box.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = InputConstants.KEY_4)
        public int keyBottomRight = InputConstants.KEY_4;

        @Expose
        @ConfigOption(name = "Colored Boxes", desc = "Fill each box with the class colour. Off: dark boxes with class-coloured names.")
        @ConfigEditorBoolean
        public boolean coloredBoxes = false;

        @Expose
        @ConfigOption(name = "Leap Announce", desc = "Send \"Leaped to <name>!\" to party chat after you leap.")
        @ConfigEditorBoolean
        public boolean announce = false;

        @Expose @ConfigOption(name = "Archer Colour", desc = "Archer box colour.") @ConfigEditorColour public String archerColor = "0:255:255:170:0";
        @Expose @ConfigOption(name = "Archer Position", desc = "Where the Archer goes. If two classes want the same corner, the extra one takes a free corner.") @ConfigEditorDropdown public LeapCorner archerCorner = LeapCorner.TOP_LEFT;
        @Expose @ConfigOption(name = "Berserk Colour", desc = "Berserk box colour.") @ConfigEditorColour public String berserkColor = "0:255:170:0:0";
        @Expose @ConfigOption(name = "Berserk Position", desc = "Where the Berserk goes.") @ConfigEditorDropdown public LeapCorner berserkCorner = LeapCorner.TOP_RIGHT;
        @Expose @ConfigOption(name = "Healer Colour", desc = "Healer box colour.") @ConfigEditorColour public String healerColor = "0:255:170:0:170";
        @Expose @ConfigOption(name = "Healer Position", desc = "Where the Healer goes.") @ConfigEditorDropdown public LeapCorner healerCorner = LeapCorner.BOTTOM_LEFT;
        @Expose @ConfigOption(name = "Mage Colour", desc = "Mage box colour.") @ConfigEditorColour public String mageColor = "0:255:85:170:255";
        @Expose @ConfigOption(name = "Mage Position", desc = "Where the Mage goes.") @ConfigEditorDropdown public LeapCorner mageCorner = LeapCorner.BOTTOM_RIGHT;
        @Expose @ConfigOption(name = "Tank Colour", desc = "Tank box colour.") @ConfigEditorColour public String tankColor = "0:255:0:170:0";
        @Expose @ConfigOption(name = "Tank Position", desc = "Where the Tank goes.") @ConfigEditorDropdown public LeapCorner tankCorner = LeapCorner.BOTTOM_RIGHT;
    }

    public static final class BloodCamp {
        @Expose
        @ConfigOption(name = "Move Prediction", desc = "Predict when the Watcher moves after its first spawns and show a Move Timer HUD.")
        @ConfigEditorBoolean
        public boolean movePrediction = false;

        @Expose
        @ConfigOption(name = "Move Message", desc = "Print \"Watcher will move in Xs.\" in chat.")
        @ConfigEditorBoolean
        public boolean moveMessage = false;

        @Expose
        @ConfigOption(name = "Party Move Message", desc = "Send \"Watcher will move in Xs.\" to party chat.")
        @ConfigEditorBoolean
        public boolean partyMoveMessage = false;

        @Expose
        @ConfigOption(name = "Kill Title", desc = "Show a \"Kill Mobs\" title when it is time to kill the first spawns.")
        @ConfigEditorBoolean
        public boolean killTitle = false;

        @Expose
        @ConfigOption(name = "Mob Kill Timers", desc = "Box where each blood mob will land, with a countdown until it spawns, coloured by Beam Time (red when to shoot, aqua once spawned). Like Odin, only heads the Watcher throws are tracked, not the heads on the walls.")
        @ConfigEditorBoolean
        public boolean killTimers = false;

        @Expose
        @ConfigOption(name = "Spawn Colour", desc = "Box where the mob will land.")
        @ConfigEditorColour
        public String spawnColor = "0:255:255:85:85";

        @Expose
        @ConfigOption(name = "Final Colour", desc = "Box once the head has reached where the mob spawns.")
        @ConfigEditorColour
        public String finalColor = "0:255:0:170:170";

        @Expose
        @ConfigOption(name = "Position Colour", desc = "Box on the flying head (moved ahead by your ping).")
        @ConfigEditorColour
        public String positionColor = "0:255:85:255:85";

        @Expose
        @ConfigOption(name = "Box Size", desc = "Size of the boxes.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 1f, minStep = 0.1f)
        public float boxSize = 1f;

        @Expose
        @ConfigOption(name = "Line", desc = "Draw a line from the head to where it lands.")
        @ConfigEditorBoolean
        public boolean line = true;

        @Expose
        @ConfigOption(name = "Time Left", desc = "Show the time until the mob spawns.")
        @ConfigEditorBoolean
        public boolean timeLeft = true;

        @Expose
        @ConfigOption(name = "Beam Time (s)", desc = "When to shoot (your Mage beam) before the mob spawns, like Odin and NoammAddons. The countdown goes green, yellow, then gold, and turns red at this time until the mob spawns (aqua once it has).")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1f, minStep = 0.05f)
        public float beamTime = 0.2f;

        @Expose
        @ConfigOption(name = "Colour Box By Time", desc = "Colour the box where the mob lands like the countdown (green, yellow, gold, red at Beam Time), instead of the Spawn Colour.")
        @ConfigEditorBoolean
        public boolean colourBoxByTime = true;

        @Expose
        @ConfigOption(name = "Offset (ms)", desc = "Shifts the countdown to match when mobs really spawn.")
        @ConfigEditorSlider(minValue = -100, maxValue = 100, minStep = 1)
        public int offset = 40;

        @Expose
        @ConfigOption(name = "Spawn Tick", desc = "Tick the mob is assumed to spawn on (mobs spawn 37-41 ticks after the throw).")
        @ConfigEditorSlider(minValue = 35, maxValue = 41, minStep = 1)
        public int tick = 38;

        @Expose
        @ConfigOption(name = "Interpolation", desc = "Smooth the landing box between ticks.")
        @ConfigEditorBoolean
        public boolean interpolation = true;

        @Expose
        @ConfigOption(name = "Ping Offset", desc = "Move the position box ahead by your ping.")
        @ConfigEditorBoolean
        public boolean pingOffset = true;

        @Expose
        @ConfigOption(name = "Watcher Bar", desc = "Show how many blood mobs are left in the Watcher's boss bar.")
        @ConfigEditorBoolean
        public boolean watcherBar = true;
    }

    public static final class PositionalMessages {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Send your positional messages to party chat when you reach them. Add them with /s2 posmsg add here <radius> <delay ticks> <message>.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Only in Boss", desc = "Only send and show positional messages in a dungeon boss fight.")
        @ConfigEditorBoolean
        public boolean onlyInBoss = true;

        @Expose
        @ConfigOption(name = "Your Class", desc = "Your dungeon class. Auto reads it from the tab list; pick one if it isn't detected.")
        @ConfigEditorDropdown
        public ClassOverride classOverride = ClassOverride.AUTO;

        @ConfigOption(name = "Display", desc = "How positions and messages are drawn.")
        @ConfigEditorAccordion(id = 1901)
        public boolean displayGroup = false;

        @Expose
        @ConfigOption(name = "Show Positions", desc = "Draw each positional message's circle or box and text in the world.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1901)
        public boolean showPositions = true;

        @Expose
        @ConfigOption(name = "Ring Height", desc = "Height of the ring drawn around radius messages.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 5f, minStep = 0.1f)
        @ConfigAccordionId(id = 1901)
        public float ringHeight = 0.2f;

        @Expose
        @ConfigOption(name = "Show Message", desc = "Show each message's text above its spot.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1901)
        public boolean showMessage = true;

        @Expose
        @ConfigOption(name = "Message Size", desc = "Size of the text above each spot.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 4f, minStep = 0.1f)
        @ConfigAccordionId(id = 1901)
        public float messageSize = 1f;
    }

    public enum BoxStyle {
        OUTLINE("Outline"), FILLED("Filled"), BOTH("Both");

        private final String label;

        BoxStyle(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class PlatformHighlight {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Highlight the 3x3 platform on floor 7 once Goldor starts (after Storm).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Healer Only", desc = "Only show it while you are Healer.")
        @ConfigEditorBoolean
        public boolean healerOnly = false;

        @Expose
        @ConfigOption(name = "Style", desc = "Outline, fill, or both.")
        @ConfigEditorDropdown
        public BoxStyle style = BoxStyle.OUTLINE;

        @Expose @ConfigOption(name = "Outline Colour", desc = "Colour of the outline.") @ConfigEditorColour public String outlineColor = "0:255:85:255:85";
        @Expose @ConfigOption(name = "Fill Colour", desc = "Colour of the fill.") @ConfigEditorColour public String fillColor = "0:127:85:255:85";
    }

    public static final class WitherDragons {
        @Expose @ConfigOption(name = "Relic Highlight", desc = "While you hold a relic (hotbar slot 9), fill the cauldron it goes in with the relic's colour.") @ConfigEditorBoolean public boolean relicHighlight = false;
        @Expose @ConfigOption(name = "Spawn Alert", desc = "Title, sound and chat line when a dragon starts spawning. Your priority dragon is the one that spawns first (since the Minister update dragons spawn one after another), or the second one for the Solo Priority class.") @ConfigEditorBoolean public boolean alert = false;
        @Expose @ConfigOption(name = "Solo Priority", desc = "When two dragons are spawning, this class takes the one that spawns second; everyone else takes the first. Off: everyone takes the first.") @ConfigEditorDropdown public DragonSoloPriority soloPriority = DragonSoloPriority.OFF;
        @Expose @ConfigOption(name = "First Dragon Only", desc = "Solo Priority only on the first two dragons; after that everyone takes the one that spawns first.") @ConfigEditorBoolean public boolean firstDragonOnly = true;
        @Expose @ConfigOption(name = "Spawn Timer", desc = "Seconds until each spawning dragon appears, above its spawn point, and your priority dragon's timer big in the middle of the screen.") @ConfigEditorBoolean public boolean timer = false;
        @Expose @ConfigOption(name = "Kill Areas", desc = "Outline the kill area of every spawning or alive dragon.") @ConfigEditorBoolean public boolean boxes = false;
        @Expose @ConfigOption(name = "Dragon Hitboxes", desc = "Outline each part of every alive dragon in its colour.") @ConfigEditorBoolean public boolean hitboxes = false;
        @Expose @ConfigOption(name = "Tracer", desc = "Line to your priority spawning dragon.") @ConfigEditorBoolean public boolean tracers = false;
        @Expose @ConfigOption(name = "Stack Waypoints", desc = "Where to stack while a dragon spawns. Simple: one box on the spawn point. Advanced: boxes the shape of the dragon.") @ConfigEditorDropdown public DragonWaypoints waypoints = DragonWaypoints.OFF;
        @Expose @ConfigOption(name = "Dragon Health", desc = "Each alive dragon's health on it.") @ConfigEditorBoolean public boolean health = false;
        @Expose @ConfigOption(name = "Relic Place Timer", desc = "Once all five relics are placed, a chat line per relic with who placed it and how long after the Wither King appeared, with your PB for your relic (NoammAddons' Place Timer).") @ConfigEditorBoolean public boolean relicTimer = false;
        @Expose @ConfigOption(name = "Ice Spray Tracker", desc = "Chat line with how many ticks after spawning each dragon was Ice Sprayed.") @ConfigEditorBoolean public boolean trackIceSpray = false;
        @Expose @ConfigOption(name = "Relic Beacon", desc = "While you hold a relic, a beacon beam in the relic's colour on the cauldron it goes in (Odin's King Relic).") @ConfigEditorBoolean public boolean relicBeacon = false;
        @Expose @ConfigOption(name = "Relic Tracer", desc = "While you hold a relic, a line to the cauldron it goes in (NoammAddons' Box Relics).") @ConfigEditorBoolean public boolean relicTracer = false;
        @Expose @ConfigOption(name = "Arrow Stack Indicator", desc = "While a dragon spawns, a marker where to aim so your arrows land on its stack spot, allowing for the arrows' drop over the distance (NoammAddons).") @ConfigEditorBoolean public boolean arrowStack = false;
        @Expose @ConfigOption(name = "Arrows Hit", desc = "When your priority dragon dies, a chat line with how many of your arrows hit it in its skip window (NoammAddons' Send Arrows Hit).") @ConfigEditorBoolean public boolean arrowsHit = false;
    }

    public enum DragonSoloPriority {
        OFF("Off"), HEALER("Healer"), TANK("Tank");

        private final String label;

        DragonSoloPriority(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum DragonWaypoints {
        OFF("Off"), SIMPLE("Simple"), ADVANCED("Advanced");

        private final String label;

        DragonWaypoints(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** NoFrills' Egg Hits Display. */
    public static final class EggHitsDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "While you fight a Tarantula Broodfather, show how many hits each egg sack still needs, big on the egg (NoFrills' Egg Hits Display).")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose @ConfigOption(name = "Colour", desc = "Colour of the text.") @ConfigEditorColour public String colour = "0:255:255:255:255";

        @Expose
        @ConfigOption(name = "Scale", desc = "Size of the text.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 6, minStep = 0.25f)
        public float scale = 2f;
    }

    /** Skysoft's Slayer Target Highlighting. */
    public static final class SlayerTargetHighlight {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Box your slayer boss and the minibosses you spawned while you can see them, not through walls (Skysoft's Slayer Target Highlighting).")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose @ConfigOption(name = "Highlight Bosses", desc = "Highlight your slayer boss.") @ConfigEditorBoolean public boolean highlightBosses = true;
        @Expose @ConfigOption(name = "Highlight Mini-Bosses", desc = "Highlight the minibosses you spawned.") @ConfigEditorBoolean public boolean highlightMinibosses = true;
        @Expose @ConfigOption(name = "Target Line", desc = "Draw a line to your boss, or the closest miniboss.") @ConfigEditorBoolean public boolean targetLine = true;

        @Expose
        @ConfigOption(name = "Style", desc = "Box: a box around them. Outline: a glowing outline, like Skysoft's.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.slayer.SlayerTargetHighlight.Style style = com.epic60869.sky2m.features.slayer.SlayerTargetHighlight.Style.BOX;

        @Expose @ConfigOption(name = "Highlight Colour", desc = "Colour of the box or outline.") @ConfigEditorColour public String colour = "0:255:255:85:85";
        @Expose @ConfigOption(name = "Target Line Colour", desc = "Colour of the line.") @ConfigEditorColour public String lineColour = "0:255:255:255:255";
    }

    /** Odin's Etherwarp overlay. */
    public static final class EtherwarpOverlay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "While you sneak with an etherwarp item, show where you'd teleport to (Odin's Etherwarp).")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose @ConfigOption(name = "Colour", desc = "Colour of the box.") @ConfigEditorColour public String colour = "0:217:255:170:0";
        @Expose @ConfigOption(name = "Show When Failed", desc = "Show the box even when the teleport would fail.") @ConfigEditorBoolean public boolean showFail = true;
        @Expose @ConfigOption(name = "Fail Colour", desc = "Colour of the box when the teleport would fail.") @ConfigEditorColour public String failColour = "0:217:255:85:85";

        @Expose
        @ConfigOption(name = "Style", desc = "How the box is drawn.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.EtherwarpOverlay.Style style = com.epic60869.sky2m.features.misc.EtherwarpOverlay.Style.FILLED_OUTLINE;

        @Expose @ConfigOption(name = "Use Server Position", desc = "Work it out from where the server last had you, instead of where you are.") @ConfigEditorBoolean public boolean useServerPosition = false;
        @Expose @ConfigOption(name = "Full Block", desc = "Draw a whole block instead of the block's own shape.") @ConfigEditorBoolean public boolean fullBlock = false;
        @Expose @ConfigOption(name = "Through Walls", desc = "Draw the box through walls.") @ConfigEditorBoolean public boolean throughWalls = false;
    }

    /** Killer560's Trail. */
    public static final class Trail {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Leave a trail of squares behind you as you move: one per tick, in one colour on the ground and another in the air. Nothing while you stand still. Only you see it.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Length", desc = "How many squares the trail has at most.")
        @ConfigEditorSlider(minValue = 1, maxValue = 100, minStep = 1)
        public int length = 40;

        @Expose
        @ConfigOption(name = "Square Size", desc = "How wide each square is, in blocks.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 1f, minStep = 0.05f)
        public float size = 0.3f;

        @Expose
        @ConfigOption(name = "Lifetime (seconds)", desc = "How long each square stays before it fades away. 0 keeps them until the trail is longer than its length.")
        @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1)
        public int lifetimeSeconds = 0;

        @Expose
        @ConfigOption(name = "Ground Colour", desc = "Colour of the squares you leave while on the ground. Its opacity is the trail's opacity.")
        @ConfigEditorColour
        public String groundColour = "0:153:85:255:85";

        @Expose
        @ConfigOption(name = "Air Colour", desc = "Colour of the squares you leave while in the air.")
        @ConfigEditorColour
        public String airColour = "0:153:34:153:255";

        @Expose
        @ConfigOption(name = "Fade Out", desc = "Make the trail fainter towards its tail.")
        @ConfigEditorBoolean
        public boolean fadeOut = true;
    }

    /** Killer560's GIF Player. */
    public static final class GifPlayer {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Play every GIF in the GIFs folder (config/sky2m/gifs) on loop, each as its own HUD you can move and resize in /s2 gui. GIFs you add or remove are picked up while you play.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @ConfigOption(name = "Open GIFs Folder", desc = "Open the folder to put your GIFs in.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable openFolder = com.epic60869.sky2m.features.misc.gif.GifPlayer::openFolder;

        @ConfigOption(name = "Reload GIFs", desc = "Load every GIF again, for example after replacing one with a file of the same name.")
        @ConfigEditorButton(buttonText = "RELOAD")
        public Runnable reload = () -> com.epic60869.sky2m.features.misc.gif.GifPlayer.rescan(true);

        @Expose
        @ConfigOption(name = "Speed", desc = "How fast the GIFs play: 2 is twice as fast.")
        @ConfigEditorSlider(minValue = 0.25f, maxValue = 4f, minStep = 0.25f)
        public float speed = 1f;

        @Expose
        @ConfigOption(name = "Largest Size", desc = "GIFs bigger than this many pixels across are shown smaller to start with. Resize them in /s2 gui.")
        @ConfigEditorSlider(minValue = 32, maxValue = 512, minStep = 16)
        public int maxSize = 256;

        @Expose
        @ConfigOption(name = "Hidden GIFs", desc = "File names of GIFs in the folder not to show, separated by commas (e.g. cat.gif, dog.gif).")
        @ConfigEditorText
        public String disabledGifs = "";
    }

    /** Killer560's DVD screensaver. */
    public static final class Dvd {
        public enum Content {
            TEXT("Text"), GIF("GIF");

            private final String label;

            Content(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @Expose
        @ConfigOption(name = "Enabled", desc = "Text or a GIF bouncing around your screen, turning off the edges like an old DVD player. Hitting a corner exactly can change its colour, play a sound and show a message.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "How Many", desc = "How many bounce around at once.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        public int count = 1;

        @Expose
        @ConfigOption(name = "Show", desc = "Text, or a GIF from the GIF Player's folder.")
        @ConfigEditorDropdown
        public Content content = Content.TEXT;

        @Expose
        @ConfigOption(name = "Text", desc = "The text that bounces.")
        @ConfigEditorText
        public String text = "DVD";

        @Expose
        @ConfigOption(name = "GIF File", desc = "The GIF that bounces: a file name from the GIF Player's folder (Misc > HUDs > GIF Player > Open GIFs Folder).")
        @ConfigEditorText
        public String gifFile = "";

        @Expose
        @ConfigOption(name = "Text Colour", desc = "Colour of the text.")
        @ConfigEditorColour
        public String colour = "0:255:255:255:255";

        @Expose
        @ConfigOption(name = "Background", desc = "A dark box behind the text.")
        @ConfigEditorBoolean
        public boolean background = false;

        @Expose
        @ConfigOption(name = "Speed", desc = "How fast it moves.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 5f, minStep = 0.1f)
        public float speed = 1f;

        @Expose
        @ConfigOption(name = "Size", desc = "How big it is.")
        @ConfigEditorSlider(minValue = 0.25f, maxValue = 4f, minStep = 0.25f)
        public float scale = 1f;

        @Expose
        @ConfigOption(name = "New Colour on Any Wall", desc = "Change the text colour every time it bounces.")
        @ConfigEditorBoolean
        public boolean colourOnWall = false;

        @Expose
        @ConfigOption(name = "New Colour on Corner", desc = "Change the text colour when it hits a corner exactly.")
        @ConfigEditorBoolean
        public boolean colourOnCorner = true;

        @Expose
        @ConfigOption(name = "Corner Sound", desc = "Play a sound when it hits a corner exactly.")
        @ConfigEditorBoolean
        public boolean cornerSound = true;

        @Expose
        @ConfigOption(name = "Corner Message", desc = "Shown above your hotbar when it hits a corner exactly (only you see it). {name} is the GIF's name, or the text. Empty for none.")
        @ConfigEditorText
        public String cornerMessage = "{name} hit the corner!";
    }

    /** Killer560's Custom Crosshair, with Sky2M' import and export. */
    public static final class Crosshair {
        /** Shapes. Append only: Killer560's share code stores the position. */
        public enum Style {
            CROSS("Cross"), T_SHAPE("T-Shape"), X("X"), DOT("Dot"), CIRCLE("Circle"), CROSS_CIRCLE("Cross+Circle"), IMAGE("Image");

            private final String label;

            Style(String label) {
                this.label = label;
            }

            public boolean hasArms() {
                return this == CROSS || this == T_SHAPE || this == X || this == CROSS_CIRCLE;
            }

            public boolean hasCircle() {
                return this == CIRCLE || this == CROSS_CIRCLE;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        /** What one size unit is. Append only (share code). */
        public enum SizeMode {
            GUI("GUI Pixels"), PIXELS("Screen Pixels");

            private final String label;

            SizeMode(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum Preset {
            CLASSIC("Classic"), VANILLA("Vanilla"), GREEN_PRO("Green Pro"), DOT("Dot"), CIRCLE_DOT("Circle Dot"),
            T_SHAPE("T-Shape"), X("X"), VALORANT("Valorant");

            private final String label;

            Preset(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @Expose
        @ConfigOption(name = "Enabled", desc = "Replace the crosshair with your own: its shape, every size, colours, outline, and how it moves when you walk, sprint, jump or swing. Import a Valorant or CS2 crosshair code, or use a picture.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Style", desc = "The crosshair's shape. Image uses the picture set under Image.")
        @ConfigEditorDropdown
        public Style style = Style.CROSS;

        @ConfigOption(name = "Shape & Size", desc = "Lengths, thickness, gap, dot, circle and rotation.")
        @ConfigEditorAccordion(id = 3001)
        public boolean shapeGroup = false;

        @Expose
        @ConfigOption(name = "Size Unit", desc = "GUI Pixels grow with your GUI Scale like the normal crosshair; Screen Pixels stay the same size whatever the GUI Scale (imported Valorant and CS2 crosshairs use these, sized for a 1080p screen).")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 3001)
        public SizeMode sizeMode = SizeMode.GUI;

        @Expose
        @ConfigOption(name = "Scale", desc = "Makes the whole crosshair bigger or smaller.")
        @ConfigEditorSlider(minValue = 0.25f, maxValue = 4f, minStep = 0.05f)
        @ConfigAccordionId(id = 3001)
        public float scale = 1f;

        @Expose
        @ConfigOption(name = "Line Length", desc = "How long each line is (0 hides them).")
        @ConfigEditorSlider(minValue = 0f, maxValue = 40f, minStep = 0.5f)
        @ConfigAccordionId(id = 3001)
        public float length = 4f;

        @Expose
        @ConfigOption(name = "Thickness", desc = "How thick the lines are.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 10f, minStep = 0.5f)
        @ConfigAccordionId(id = 3001)
        public float thickness = 1f;

        @Expose
        @ConfigOption(name = "Gap", desc = "Space between the centre and the lines. Below 0 the lines overlap in the middle.")
        @ConfigEditorSlider(minValue = -10f, maxValue = 20f, minStep = 0.5f)
        @ConfigAccordionId(id = 3001)
        public float gap = 2f;

        @Expose @ConfigOption(name = "Top Line", desc = "Show the top line.") @ConfigEditorBoolean @ConfigAccordionId(id = 3001) public boolean armTop = true;
        @Expose @ConfigOption(name = "Bottom Line", desc = "Show the bottom line.") @ConfigEditorBoolean @ConfigAccordionId(id = 3001) public boolean armBottom = true;
        @Expose @ConfigOption(name = "Left Line", desc = "Show the left line.") @ConfigEditorBoolean @ConfigAccordionId(id = 3001) public boolean armLeft = true;
        @Expose @ConfigOption(name = "Right Line", desc = "Show the right line.") @ConfigEditorBoolean @ConfigAccordionId(id = 3001) public boolean armRight = true;

        @Expose
        @ConfigOption(name = "Centre Dot", desc = "A dot in the middle (the Dot style always has one).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3001)
        public boolean dot = false;

        @Expose
        @ConfigOption(name = "Dot Size", desc = "How big the dot is.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 10f, minStep = 0.5f)
        @ConfigAccordionId(id = 3001)
        public float dotSize = 1f;

        @Expose
        @ConfigOption(name = "Circle Radius", desc = "How big the circle is (Circle styles).")
        @ConfigEditorSlider(minValue = 1f, maxValue = 50f, minStep = 0.5f)
        @ConfigAccordionId(id = 3001)
        public float circleRadius = 6f;

        @Expose
        @ConfigOption(name = "Circle Thickness", desc = "How thick the circle is.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 10f, minStep = 0.5f)
        @ConfigAccordionId(id = 3001)
        public float circleThickness = 1f;

        @Expose
        @ConfigOption(name = "Rotation", desc = "Turns the crosshair, in degrees.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 359f, minStep = 1f)
        @ConfigAccordionId(id = 3001)
        public float rotation = 0f;

        @ConfigOption(name = "Colours", desc = "The crosshair's colour, the dot's, and colours when you look at a mob or a block.")
        @ConfigEditorAccordion(id = 3002)
        public boolean coloursGroup = false;

        @Expose
        @ConfigOption(name = "Colour", desc = "The crosshair's colour. Pick chroma for a rainbow crosshair.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 3002)
        public String colour = "0:255:255:255:255";

        @Expose
        @ConfigOption(name = "Separate Dot Colour", desc = "Give the centre dot its own colour.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3002)
        public boolean separateDotColour = false;

        @Expose
        @ConfigOption(name = "Dot Colour", desc = "The centre dot's colour.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 3002)
        public String dotColour = "0:255:255:48:48";

        @Expose
        @ConfigOption(name = "Invert Colours", desc = "Draw it like the normal crosshair: the opposite colour of whatever is behind it.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3002)
        public boolean invert = false;

        @Expose
        @ConfigOption(name = "Colour On Mobs", desc = "Change colour while you're looking at a mob or player.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3002)
        public boolean colourOnEntity = false;

        @Expose
        @ConfigOption(name = "Mob Colour", desc = "The colour while looking at a mob or player.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 3002)
        public String entityColour = "0:255:255:48:48";

        @Expose
        @ConfigOption(name = "Colour On Blocks", desc = "Change colour while you're looking at a block in reach.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3002)
        public boolean colourOnBlock = false;

        @Expose
        @ConfigOption(name = "Block Colour", desc = "The colour while looking at a block.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 3002)
        public String blockColour = "0:255:64:176:255";

        @ConfigOption(name = "Outline", desc = "A border around every part of the crosshair.")
        @ConfigEditorAccordion(id = 3003)
        public boolean outlineGroup = false;

        @Expose
        @ConfigOption(name = "Outline", desc = "Draw a border around the crosshair so it shows on any background.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3003)
        public boolean outline = true;

        @Expose
        @ConfigOption(name = "Outline Thickness", desc = "How thick the border is.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 5f, minStep = 0.5f)
        @ConfigAccordionId(id = 3003)
        public float outlineThickness = 1f;

        @Expose
        @ConfigOption(name = "Outline Colour", desc = "The border's colour.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 3003)
        public String outlineColour = "0:255:0:0:0";

        @ConfigOption(name = "Movement", desc = "The crosshair opening up as you move, sprint or jump, and kicking when you swing.")
        @ConfigEditorAccordion(id = 3004)
        public boolean movementGroup = false;

        @Expose @ConfigOption(name = "Spread While Moving", desc = "Open up a little while you walk.") @ConfigEditorBoolean @ConfigAccordionId(id = 3004) public boolean spreadMoving = false;
        @Expose @ConfigOption(name = "Spread While Sprinting", desc = "Open up while you sprint.") @ConfigEditorBoolean @ConfigAccordionId(id = 3004) public boolean spreadSprinting = false;
        @Expose @ConfigOption(name = "Spread While Jumping", desc = "Open up while you're in the air.") @ConfigEditorBoolean @ConfigAccordionId(id = 3004) public boolean spreadJumping = false;

        @Expose
        @ConfigOption(name = "Spread Amount", desc = "How far it opens up.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 20f, minStep = 0.5f)
        @ConfigAccordionId(id = 3004)
        public float spreadAmount = 3f;

        @Expose
        @ConfigOption(name = "Swing Kick", desc = "Open up for a moment each time you swing.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3004)
        public boolean recoil = false;

        @Expose
        @ConfigOption(name = "Kick Amount", desc = "How far a swing opens it.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 20f, minStep = 0.5f)
        @ConfigAccordionId(id = 3004)
        public float recoilAmount = 3f;

        @ConfigOption(name = "Visibility", desc = "The attack indicator and third person.")
        @ConfigEditorAccordion(id = 3005)
        public boolean visibilityGroup = false;

        @Expose
        @ConfigOption(name = "Attack Indicator", desc = "Keep the game's attack cooldown indicator under the crosshair (when it's set to Crosshair in Options).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3005)
        public boolean attackIndicator = true;

        @Expose
        @ConfigOption(name = "Show In Third Person", desc = "Also show the crosshair in third person.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 3005)
        public boolean thirdPerson = false;

        @ConfigOption(name = "Image", desc = "Use a picture (PNG) as your crosshair.")
        @ConfigEditorAccordion(id = 3006)
        public boolean imageGroup = false;

        @Expose
        @ConfigOption(name = "Image File", desc = "A PNG in the crosshairs folder (config/sky2m/crosshairs) to use with the Image style, e.g. dot.png. It's drawn at its own size times Scale, centred.")
        @ConfigEditorText
        @ConfigAccordionId(id = 3006)
        public String imageFile = "";

        @ConfigOption(name = "Open Crosshairs Folder", desc = "Open the folder to put crosshair pictures in.")
        @ConfigEditorButton(buttonText = "OPEN")
        @ConfigAccordionId(id = 3006)
        public Runnable openFolder = com.epic60869.sky2m.features.misc.crosshair.CustomCrosshair::openFolder;

        @ConfigOption(name = "Import & Export", desc = "Presets, and sharing crosshairs as codes.")
        @ConfigEditorAccordion(id = 3007)
        public boolean importExportGroup = false;

        @ConfigOption(name = "Import From Clipboard", desc = "Copy a crosshair code, then click this. Works with Valorant codes (0;P;c;5;...), CS2 share codes (CSGO-xxxxx-xxxxx-xxxxx-xxxxx-xxxxx), Sky2M and Killer560's Mod codes (KXH1-...), and the path or name of a PNG (it's copied into the crosshairs folder and used as an Image crosshair).")
        @ConfigEditorButton(buttonText = "IMPORT")
        @ConfigAccordionId(id = 3007)
        public Runnable importCode = com.epic60869.sky2m.features.misc.crosshair.CustomCrosshair::importFromClipboard;

        @ConfigOption(name = "Export To Clipboard", desc = "Copy your crosshair as a code (KXH1-...) to share. Anyone with Sky2M or Killer560's Mod can import it.")
        @ConfigEditorButton(buttonText = "EXPORT")
        @ConfigAccordionId(id = 3007)
        public Runnable exportCode = com.epic60869.sky2m.features.misc.crosshair.CustomCrosshair::exportToClipboard;

        @Expose
        @ConfigOption(name = "Preset", desc = "A ready-made crosshair to start from. Click Apply Preset to use it (your current look is replaced).")
        @ConfigEditorDropdown
        @ConfigAccordionId(id = 3007)
        public Preset preset = Preset.CLASSIC;

        @ConfigOption(name = "Apply Preset", desc = "Replace your crosshair's look with the chosen preset.")
        @ConfigEditorButton(buttonText = "APPLY")
        @ConfigAccordionId(id = 3007)
        public Runnable applyPreset = com.epic60869.sky2m.features.misc.crosshair.CustomCrosshair::applyPreset;
    }

    /** Inventory HUD, styled after Inventory HUD+. */
    public static final class InventoryHud {
        public enum Show {
            ALWAYS("Always"), HOLD_KEY("Hold Key"), TOGGLE_KEY("Toggle Key");

            private final String label;

            Show(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum Style {
            DARK("Dark"), VANILLA("Vanilla");

            private final String label;

            Style(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum Background {
            NONE("None"), PANEL("Panel"), SLOTS("Panel + Slots");

            private final String label;

            Background(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @Expose
        @ConfigOption(name = "Enabled", desc = "Show your 27 inventory slots (not the hotbar) as a HUD. Move and resize it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show", desc = "Always, only while you hold the key, or switched on and off by the key.")
        @ConfigEditorDropdown
        public Show show = Show.ALWAYS;

        @Expose
        @ConfigOption(name = "Key", desc = "The key for While Holding the Key and Toggled by the Key.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = -1)
        public int key = -1;

        @Expose
        @ConfigOption(name = "Vertical", desc = "Three columns of nine instead of three rows of nine.")
        @ConfigEditorBoolean
        public boolean vertical = false;

        @Expose
        @ConfigOption(name = "Mini", desc = "Half-size slots.")
        @ConfigEditorBoolean
        public boolean mini = false;

        @Expose
        @ConfigOption(name = "Style", desc = "Dark, or the light gray of vanilla inventories.")
        @ConfigEditorDropdown
        public Style style = Style.DARK;

        @Expose
        @ConfigOption(name = "Background", desc = "What's drawn behind the items.")
        @ConfigEditorDropdown
        public Background background = Background.SLOTS;

        @Expose
        @ConfigOption(name = "Background Opacity", desc = "How see-through the background is. The items always stay solid.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1f, minStep = 0.05f)
        public float opacity = 0.8f;

        @Expose
        @ConfigOption(name = "Animation", desc = "Items bounce when they're picked up, like on the hotbar.")
        @ConfigEditorBoolean
        public boolean animation = true;

        @Expose
        @ConfigOption(name = "Show Counts", desc = "Stack counts and durability bars on the items.")
        @ConfigEditorBoolean
        public boolean showCounts = true;

        @Expose
        @ConfigOption(name = "Hide When Empty", desc = "Hide it while your inventory is empty.")
        @ConfigEditorBoolean
        public boolean hideWhenEmpty = false;

        @Expose
        @ConfigOption(name = "Hide In Menus", desc = "Hide it while a menu is open (not chat).")
        @ConfigEditorBoolean
        public boolean hideInScreens = true;
    }

    /** NoammAddons' Auto Requeue. */
    public static final class AutoRequeue {
        @Expose
        @ConfigOption(name = "Enabled", desc = "When a dungeon run ends, /instancerequeue after the delay below, so the party goes into another run of the same floor. Only the party leader can requeue. /s2 requeue cancel stops the next one.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Delay (seconds)", desc = "How long after the run ends to requeue, to look at the score and loot first.")
        @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1)
        public int delaySeconds = 5;

        @Expose
        @ConfigOption(name = "Cancel If Someone Leaves", desc = "Don't requeue when a party member leaves, is kicked or disconnects during the run or before the requeue.")
        @ConfigEditorBoolean
        public boolean cancelOnLeave = true;

        @Expose
        @ConfigOption(name = "Announce", desc = "Say in chat (only you see it) that it's going to requeue.")
        @ConfigEditorBoolean
        public boolean announce = true;
    }

    /** SkyHanni's Item Pickup Log. */
    public static final class ItemPickupLog {
        public enum Alignment {
            TOP("Newest Top"), BOTTOM("Newest Bottom");

            private final String label;

            Alignment(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum Part {
            AMOUNT("Amount"), ICON("Icon"), NAME("Item Name");

            private final String label;

            Part(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @Expose
        @ConfigOption(name = "Enabled", desc = "Show a log of what items you pick up and drop, and how many, for a few seconds after. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Compact Lines", desc = "Combine the + and - lines of an item into one line with what it changed by overall.")
        @ConfigEditorBoolean
        public boolean compactLines = true;

        @Expose
        @ConfigOption(name = "Compact Numbers", desc = "Shorten the amounts added and removed (1.2k).")
        @ConfigEditorBoolean
        public boolean shorten = false;

        @Expose
        @ConfigOption(name = "Sacks", desc = "Also show items added to and removed from your sacks.")
        @ConfigEditorBoolean
        public boolean sacks = false;

        @Expose
        @ConfigOption(name = "Shards", desc = "Also show shards you get and send to your Hunting Box.")
        @ConfigEditorBoolean
        public boolean shards = false;

        @Expose
        @ConfigOption(name = "Coins", desc = "Also show coins added to and removed from your purse.")
        @ConfigEditorBoolean
        public boolean coins = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Pickup Coin Value", desc = "The total coin value of what's in the log.")
        public ItemPickupLogValue coinValue = new ItemPickupLogValue();

        @Expose
        @ConfigOption(name = "Alignment", desc = "Where new lines go.")
        @ConfigEditorDropdown
        public Alignment alignment = Alignment.TOP;

        @Expose
        @ConfigOption(name = "Layout", desc = "Drag to change the order of each line's parts; remove one to hide it.")
        @ConfigEditorDraggableList
        public List<Part> layout = new ArrayList<>(List.of(Part.AMOUNT, Part.ICON, Part.NAME));

        @Expose
        @ConfigOption(name = "Expire After", desc = "How long items show after being picked up or dropped, in seconds.")
        @ConfigEditorSlider(minValue = 1, maxValue = 20, minStep = 1)
        public int expireAfter = 10;
    }

    public static final class ItemPickupLogValue {
        @Expose
        @ConfigOption(name = "Total Coin Value", desc = "Show the total coin value of the items in your pickup log.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Price Source", desc = "What price to value items at.")
        @ConfigEditorDropdown
        public ProfitPriceSource priceSource = ProfitPriceSource.INSTANT_SELL;

        @Expose
        @ConfigOption(name = "Total Coin Value Threshold", desc = "Only show the total coin value above this many coins.")
        @ConfigEditorSlider(minValue = 0, maxValue = 1000000, minStep = 10000)
        public int threshold = 50000;
    }

    /** SkyHanni's chat Translator. */
    public static final class Translator {
        public enum Language {
            ENGLISH("English", "en"), SPANISH("Spanish", "es"), FRENCH("French", "fr"), GERMAN("German", "de"), PORTUGUESE("Portuguese", "pt"), RUSSIAN("Russian", "ru"), POLISH("Polish", "pl"), DUTCH("Dutch", "nl"), ITALIAN("Italian", "it"), TURKISH("Turkish", "tr"), SWEDISH("Swedish", "sv"), NORWEGIAN("Norwegian", "no"), DANISH("Danish", "da"), FINNISH("Finnish", "fi"), CZECH("Czech", "cs"), HUNGARIAN("Hungarian", "hu"), ROMANIAN("Romanian", "ro"), GREEK("Greek", "el"), UKRAINIAN("Ukrainian", "uk"), ARABIC("Arabic", "ar"), HEBREW("Hebrew", "iw"), HINDI("Hindi", "hi"), CHINESE("Chinese", "zh-CN"), JAPANESE("Japanese", "ja"), KOREAN("Korean", "ko"), INDONESIAN("Indonesian", "id"), VIETNAMESE("Vietnamese", "vi"), THAI("Thai", "th"), FILIPINO("Filipino", "tl");

            public final String label;
            public final String code;

            Language(String label, String code) {
                this.label = label;
                this.code = code;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @Expose
        @ConfigOption(name = "Click to Translate", desc = "Click a player's chat message to translate it into your language below. /s2 translate <language> <message> translates your own message and copies it, ready to paste. Uses Google Translate: only what you click or type is sent.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Your Language", desc = "What clicked messages are translated into.")
        @ConfigEditorDropdown
        public Language language = Language.ENGLISH;
    }

    /** Odin's Better Party Finder. */
    public static final class PartyFinderStats {
        @Expose
        @ConfigOption(name = "Stats When Someone Joins", desc = "When someone joins your group from the Party Finder, show their Catacombs level, classes, secrets, floor times (hover Normal/Master), magical power, armour and missing key items in chat. Only you see it. /s2 cata <name> shows anyone's.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Played Before", desc = "When someone you've seen before joins your group, say so: dungeon runs you played together (from Run History), how many finished, your best together, when you last played, how often they joined your groups and how often you kicked them. /s2 pfhistory lists recent players.")
        @ConfigEditorBoolean
        public boolean history = true;

        @Expose
        @ConfigOption(name = "Flag Kicked Players", desc = "When someone you kicked from your group before joins your Party Finder group or your party, warn in chat: how often you kicked them and when last, your note on them, and [Kick] and [History] buttons. Kicks count once Hypixel says they were removed.")
        @ConfigEditorBoolean
        public boolean flagKicked = true;

        @Expose
        @ConfigOption(name = "Auto-Note Kicks", desc = "The first time you kick someone who joined your Party Finder group, save a note on them (\"Kicked from M7 on Oct 18, 2026\") unless they already have one. See /s2 notes.")
        @ConfigEditorBoolean
        public boolean autoNoteKicks = false;

        @Expose
        @ConfigOption(name = "Kick Button", desc = "Adds a [KICK] button under the stats that kicks them from the party.")
        @ConfigEditorBoolean
        public boolean kickButton = true;

        @Expose
        @ConfigOption(name = "Auto Kick", desc = "While you lead, kick players who don't meet the requirements below (0 turns a requirement off).")
        @ConfigEditorBoolean
        public boolean autoKick = false;

        @Expose
        @ConfigOption(name = "Per-Floor Requirements", desc = "Use different requirements for each floor (F1-F7 and M1-M7), set with /s2 pfreq: the ones for the floor you're queued for or playing. Floors without their own fall back to the requirements below.")
        @ConfigEditorBoolean
        public boolean perFloor = false;

        @Expose
        @ConfigOption(name = "Floor", desc = "The floor whose S+ time is checked.")
        @ConfigEditorSlider(minValue = 1, maxValue = 7, minStep = 1)
        public int floor = 7;

        @Expose
        @ConfigOption(name = "Master Mode", desc = "Check the Master Mode floor's time instead of the normal one.")
        @ConfigEditorBoolean
        public boolean masterMode = true;

        @Expose
        @ConfigOption(name = "Slowest S+ (seconds)", desc = "Kick when their fastest S+ on that floor is slower than this, or they have none. 0 = don't check.")
        @ConfigEditorSlider(minValue = 0, maxValue = 600, minStep = 5)
        public int maxPbSeconds = 0;

        @Expose
        @ConfigOption(name = "Minimum Secrets (thousands)", desc = "0 = don't check.")
        @ConfigEditorSlider(minValue = 0, maxValue = 200, minStep = 1)
        public int minSecretsK = 0;

        @Expose
        @ConfigOption(name = "Minimum Catacombs Level", desc = "0 = don't check.")
        @ConfigEditorSlider(minValue = 0, maxValue = 50, minStep = 1)
        public int minCata = 0;

        @Expose
        @ConfigOption(name = "Minimum Magical Power", desc = "0 = don't check. Skipped when their inventory API is off, unless the option below is on.")
        @ConfigEditorSlider(minValue = 0, maxValue = 2000, minStep = 10)
        public int minMagicalPower = 0;

        @Expose
        @ConfigOption(name = "Kick If API Off", desc = "Kick players whose inventory API is off.")
        @ConfigEditorBoolean
        public boolean apiOffKick = false;

        @Expose
        @ConfigOption(name = "Say Why In Party Chat", desc = "Say in party chat why they were kicked.")
        @ConfigEditorBoolean
        public boolean informKicked = false;

        @Expose
        @ConfigOption(name = "Remember Kicks", desc = "Kick straight away anyone auto kicked before who joins again (until restart, or /s2 cata clearkicks).")
        @ConfigEditorBoolean
        public boolean kickCache = true;
    }

    /** The Profile Viewer (/s2 pv), in NEU's layout. */
    public static final class ProfileViewer {
        @Expose
        @ConfigOption(name = "Use /pv", desc = "/pv opens Sky2M's Profile Viewer too, not just /s2 pv. Off by default because NEU and SBPV use /pv. Takes effect when you next join a server.")
        @ConfigEditorBoolean
        public boolean pvCommand = false;

        @Expose
        @ConfigOption(name = "Profile API", desc = "Where profiles are loaded from, for the Profile Viewer and Party Finder Stats: a public proxy of Hypixel's API, since Sky2M has no API key. Default: Odin's, https://api.odtheking.com/hypixel/")
        @ConfigEditorText
        public String apiUrl = com.epic60869.sky2m.features.profileviewer.ProfileApi.DEFAULT_URL;

        @Expose
        @ConfigOption(name = "Overflow Levels", desc = "Show levels past Hypixel's maximum, like SkyHanni: skills (the normal levels up to 60, then 7.6M XP for 61 and more for each after), Catacombs and classes past 50 (200M XP each), the garden level past its max (10,000 garden XP each) and crop milestones past their max (each needing as many crops as the last). Off: everything stops at Hypixel's max. Skill Average always uses the capped levels.")
        @ConfigEditorBoolean
        public boolean overflowLevels = true;
    }

    /** Skysoft's Keep Terrain Loaded. */
    public static final class KeepTerrainLoaded {
        @Expose
        @ConfigOption(name = "Enabled", desc = "On SkyBlock islands, keep terrain you've visited loaded out to your render distance, past Hypixel's view distance, and remember it on disk for next time (Skysoft's). Not in dungeons or Kuudra. Off when Bobby is installed.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Excluded Islands", desc = "Islands to leave out, separated by commas, as the tab list names them (e.g. Hub, Garden, Crimson Isle).")
        @ConfigEditorText
        public String excludedIslands = "";
    }

    /** Skysoft's Farming Profit Tracker. */
    /** Profit Trackers, ported from Skysoft (LGPL-3.0): see features/misc/profit/ProfitTracker. */
    public static final class ProfitTrackers {
        @Expose
        @Accordion
        @ConfigOption(name = "Session Tracker", desc = "/s2 session: your Diana, Mining and Farming this game session, with a Total of all three, until you restart the game.")
        public SessionTrackerSettings session = new SessionTrackerSettings();

        @Expose
        @Accordion
        @ConfigOption(name = "Farming", desc = "Track Farming profit in the Garden.")
        public ProfitTrackerSettings farming = new ProfitTrackerSettings(ProfitSummaryLine.farmingDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Fishing", desc = "Track Fishing profit while you fish.")
        public ProfitTrackerSettings fishing = new ProfitTrackerSettings(ProfitSummaryLine.resourceDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Foraging", desc = "Track Foraging profit in the Park, the Hub's forest, the Moonglade Marsh and Torrhus Canyon.")
        public ProfitTrackerSettings foraging = new ProfitTrackerSettings(ProfitSummaryLine.resourceDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Mining", desc = "Track Mining profit in the mining islands.")
        public ProfitTrackerSettings mining = new ProfitTrackerSettings(ProfitSummaryLine.resourceDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Mythological Ritual", desc = "Track Mythological Ritual (Diana) profit in the Hub with a spade in your hotbar.")
        public ProfitTrackerSettings mythologicalRitual = new ProfitTrackerSettings(ProfitSummaryLine.mythologicalDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Zombie Slayer", desc = "Track Zombie Slayer profit.")
        public ProfitTrackerSettings zombie = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Spider Slayer", desc = "Track Spider Slayer profit.")
        public ProfitTrackerSettings spider = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Wolf Slayer", desc = "Track Wolf Slayer profit.")
        public ProfitTrackerSettings wolf = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Enderman Slayer", desc = "Track Enderman Slayer profit.")
        public ProfitTrackerSettings enderman = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Blaze Slayer", desc = "Track Blaze Slayer profit.")
        public ProfitTrackerSettings blaze = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Accordion
        @ConfigOption(name = "Vampire Slayer", desc = "Track Vampire Slayer profit.")
        public ProfitTrackerSettings vampire = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());
    }

    public static final class SessionTrackerSettings {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Track all built-in profit presets into one daily record, including when individual tracker HUDs are disabled. Open /s2 session to click a day and see total profit plus each tracker's drops and breakdown. Game restarts do not split a day; history is kept by calendar day.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Pause After", desc = "Seconds without mining, farming or digging burrows before an activity's playtime pauses.")
        @ConfigEditorSlider(minValue = 5, maxValue = 60, minStep = 1)
        public int pauseSeconds = 10;

        @Expose
        @ConfigOption(name = "Price Refresh", desc = "How often, in seconds, /s2 session works out its profit again with the latest prices.")
        @ConfigEditorSlider(minValue = 5, maxValue = 300, minStep = 5)
        public int priceRefreshSeconds = 30;

        @Expose
        @ConfigOption(name = "Price Source", desc = "Which price the session's drops are worth.")
        @ConfigEditorDropdown
        public ProfitPriceSource priceSource = ProfitPriceSource.INSTANT_SELL;

        @Expose
        @ConfigOption(name = "Breakdown on Launch", desc = "When you join on a new day, show the previous day's breakdown in chat.")
        @ConfigEditorBoolean
        public boolean summaryOnLaunch = true;

        @Expose
        @ConfigOption(name = "Recap on Leaving", desc = "When you leave a world or server, show a one-message recap of the session (playtime, profit, coins, drops) the next time you join. /s2 recap shows it any time.")
        @ConfigEditorBoolean
        public boolean recapOnLeave = false;

        @Expose
        @ConfigOption(name = "Share Sessions to Website", desc = "Upload your finished tracker sessions (activity, time, profit, top drops) to the Sky2M server so they can show on your shadowisabot.com player page. Needs /s2 link first. Nothing is public until you switch it on at shadowisabot.com/link. /s2 link delete removes everything stored.")
        @ConfigEditorBoolean
        public boolean shareToWebsite = false;

        /** Whether the one-time explanation of Share Sessions to Website was shown. */
        @Expose
        public boolean shareNoticeShown = false;

        @Expose
        @ConfigOption(name = "Sessions Kept", desc = "How many past daily sections /s2 session keeps.")
        @ConfigEditorSlider(minValue = 1, maxValue = 100, minStep = 1)
        public int keep = 50;

        @ConfigOption(name = "Open", desc = "Open the Session Tracker (/s2 session).")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable open = () -> com.epic60869.sky2m.features.misc.profit.SessionTrackerScreen.open(-1);
    }

    public static final class ProfitTrackerSettings {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Track profit for this activity: what your drops are worth, coins, costs, profit per hour and uptime. With a menu open, click its Display Mode, Price Source and Reset lines, and scroll its items. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Settings", desc = "Profit Tracker settings.")
        @Accordion
        public ProfitTrackerOptions settings = new ProfitTrackerOptions();

        @Expose
        @ConfigOption(name = "Details", desc = "Profit Tracker appearance.")
        @Accordion
        public ProfitTrackerDetails details;

        /** Which totals the HUD shows (cycled with its Display Mode line). */
        @Expose
        public com.epic60869.sky2m.features.misc.profit.ProfitTracker.Period period = com.epic60869.sky2m.features.misc.profit.ProfitTracker.Period.SESSION;

        public ProfitTrackerSettings() {
            this(ProfitSummaryLine.standardDefaults());
        }

        public ProfitTrackerSettings(List<ProfitSummaryLine> summaryLines) {
            details = new ProfitTrackerDetails(summaryLines);
        }
    }

    public static final class ProfitTrackerOptions {
        @Expose
        @ConfigOption(name = "Price Source", desc = "How tracked items are valued (lowest BIN when the bazaar doesn't sell it).")
        @ConfigEditorDropdown
        public ProfitPriceSource priceSource = ProfitPriceSource.INSTANT_SELL;

        @Expose
        @ConfigOption(name = "Pause After", desc = "Pause time tracking after a while without tracked activity.")
        @ConfigEditorBoolean
        public boolean pauseAfter = true;

        @Expose
        @ConfigOption(name = "Inactivity Time", desc = "Seconds without tracked activity before time tracking pauses.")
        @ConfigEditorSlider(minValue = 15, maxValue = 900, minStep = 15)
        public int pauseAfterSeconds = 60;

        @Expose
        @ConfigOption(name = "Maximum Items", desc = "Most tracked item rows shown at once.")
        @ConfigEditorSlider(minValue = 1, maxValue = 15, minStep = 1)
        public int maximumItems = 8;

        @Expose
        @ConfigOption(name = "Profit Goal", desc = "A profit goal for the period the tracker shows (Session, Today or Total), in millions of coins: a Goal line with your progress, and a message when you reach it. 0 turns it off.")
        @ConfigEditorSlider(minValue = 0, maxValue = 2000, minStep = 5)
        public int goalMillions = 0;
    }

    public static final class ProfitTrackerDetails {
        @Expose
        @ConfigOption(name = "Show Item Icons", desc = "Show item icons beside tracked drops.")
        @ConfigEditorBoolean
        public boolean showItemIcons = true;

        @Expose
        @ConfigOption(name = "Quantity Position", desc = "Where item quantities are shown.")
        @ConfigEditorDropdown
        public ProfitQuantityPosition quantityPosition = ProfitQuantityPosition.RIGHT;

        @Expose
        @ConfigOption(name = "Highlight Changes", desc = "Briefly highlight item quantities when they change.")
        @ConfigEditorBoolean
        public boolean highlightChanges = true;

        @Expose
        @ConfigOption(name = "Summary Lines", desc = "Choose and reorder the summary lines shown by the tracker.")
        @ConfigEditorDraggableList
        public List<ProfitSummaryLine> summaryLines;

        @Expose
        @ConfigOption(name = "Show Background", desc = "Draw a dark background behind the Profit Tracker.")
        @ConfigEditorBoolean
        public boolean showBackground = false;

        public ProfitTrackerDetails() {
            this(ProfitSummaryLine.standardDefaults());
        }

        public ProfitTrackerDetails(List<ProfitSummaryLine> summaryLines) {
            this.summaryLines = new ArrayList<>(summaryLines);
        }
    }

    public enum ProfitPriceSource {
        INSTANT_SELL("Insta-Sell"), SELL_ORDER("Sell Order"), BUY_ORDER("Buy Order"), NPC_SELL("NPC Sell");

        private final String label;

        ProfitPriceSource(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum ProfitQuantityPosition {
        LEFT("Left"), RIGHT("Right");

        private final String label;

        ProfitQuantityPosition(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum ProfitSummaryLine {
        COINS("Coins"), KERNEL_PROFIT("Kernel Profit"), QUEST_COSTS("Costs"), TOTAL_PROFIT("Total Profit"),
        PROFIT_PER_HOUR("Profit/h"), ACTIONS("Actions"), UPTIME("Uptime"),
        /** Slayer trackers only: bosses/h and profit/boss this session, with your best session. Not in any defaults. */
        PACE("Slayer Pace");

        private final String label;

        ProfitSummaryLine(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        static List<ProfitSummaryLine> farmingDefaults() {
            return List.of(COINS, KERNEL_PROFIT, QUEST_COSTS, TOTAL_PROFIT, PROFIT_PER_HOUR, ACTIONS, UPTIME);
        }

        static List<ProfitSummaryLine> standardDefaults() {
            return List.of(COINS, QUEST_COSTS, TOTAL_PROFIT, PROFIT_PER_HOUR, ACTIONS, UPTIME);
        }

        static List<ProfitSummaryLine> slayerDefaults() {
            return standardDefaults();
        }

        static List<ProfitSummaryLine> resourceDefaults() {
            return List.of(TOTAL_PROFIT, PROFIT_PER_HOUR, UPTIME);
        }

        static List<ProfitSummaryLine> mythologicalDefaults() {
            return List.of(TOTAL_PROFIT, PROFIT_PER_HOUR, ACTIONS, UPTIME);
        }
    }

    public static final class ServerInfoDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show your FPS, the server's TPS, your ping and the time on a HUD (Skysoft's Server Info and Real Time Displays). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose @ConfigOption(name = "Show FPS", desc = "Frames per second.") @ConfigEditorBoolean public boolean showFps = true;
        @Expose @ConfigOption(name = "Show TPS", desc = "The server's ticks per second, from how fast its clock moves.") @ConfigEditorBoolean public boolean showTps = true;
        @Expose @ConfigOption(name = "Show Ping", desc = "Your ping, measured once a second.") @ConfigEditorBoolean public boolean showPing = true;
        @Expose @ConfigOption(name = "Show Time", desc = "Your computer's clock (Skysoft's Real Time Display).") @ConfigEditorBoolean public boolean showTime = true;

        @Expose
        @ConfigOption(name = "Time Format", desc = "12-hour or 24-hour time, with optional seconds.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.ServerInfo.TimeFormat timeFormat = com.epic60869.sky2m.features.misc.ServerInfo.TimeFormat.TWENTY_FOUR_HOUR;

        @Expose
        @ConfigOption(name = "Style", desc = "Simple: one HUD with everything. Split: a separate HUD for each value, each moved on its own.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.ServerInfo.Style style = com.epic60869.sky2m.features.misc.ServerInfo.Style.SIMPLE;

        @Expose
        @ConfigOption(name = "Simple Layout", desc = "Arrange the Simple display vertically or horizontally.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.ServerInfo.Layout layout = com.epic60869.sky2m.features.misc.ServerInfo.Layout.VERTICAL;

        @Expose
        @ConfigOption(name = "Labels", desc = "Show text labels, symbols, or values only.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.ServerInfo.LabelStyle labelStyle = com.epic60869.sky2m.features.misc.ServerInfo.LabelStyle.TEXT;

        @Expose
        @ConfigOption(name = "Show Icons", desc = "Draw a small pixel icon before each value: signal bars for ping, arrows for TPS, a clock for the time and a bolt for FPS. With Labels on Symbols, the icons replace the symbols.")
        @ConfigEditorBoolean
        public boolean showIcons = true;

        @Expose @ConfigOption(name = "FPS Colour", desc = "Colour of the FPS text.") @ConfigEditorColour public String fpsColour = "0:255:255:255:255";
        @Expose @ConfigOption(name = "TPS Colour", desc = "Colour of the TPS text.") @ConfigEditorColour public String tpsColour = "0:255:255:255:255";
        @Expose @ConfigOption(name = "Ping Colour", desc = "Colour of the ping text.") @ConfigEditorColour public String pingColour = "0:255:255:255:255";
        @Expose @ConfigOption(name = "Time Colour", desc = "Colour of the time.") @ConfigEditorColour public String timeColour = "0:255:255:255:255";
    }

    public static final class PartyCommands {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Answer ! commands party members type in party chat: the leader ones (!warp, !pt, !f7, ...) while you are party leader, and !fps, !ping and !tps always.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose @ConfigOption(name = "!warp", desc = "Runs /party warp.") @ConfigEditorBoolean public boolean warp = true;
        @Expose @ConfigOption(name = "!allinvite", desc = "Runs /party settings allinvite.") @ConfigEditorBoolean public boolean allInvite = true;
        @Expose @ConfigOption(name = "!pt / !transfer", desc = "Transfers the party to the player who asked, or the player named after it. The name can be just the start of it: !pt nix transfers to NixJussid.") @ConfigEditorBoolean public boolean transfer = true;
        @Expose @ConfigOption(name = "!promote / !demote", desc = "Promotes or demotes the player who asked, or the player named after it (!promote Name).") @ConfigEditorBoolean public boolean promote = false;
        @Expose @ConfigOption(name = "!kick", desc = "Kicks the player named after it (!kick Name). The name can be just the start of it: !kick nix kicks NixJussid. Never kicks you.") @ConfigEditorBoolean public boolean kick = true;

        @Expose @ConfigOption(name = "!f1 - !f7", desc = "Joins that Catacombs floor (Odin's queue commands). Only while you're leader.") @ConfigEditorBoolean public boolean floors = true;
        @Expose @ConfigOption(name = "!m1 - !m7", desc = "Joins that Master Mode floor. Only while you're leader.") @ConfigEditorBoolean public boolean masterFloors = true;
        @Expose @ConfigOption(name = "!t1 - !t5", desc = "Joins that Kuudra tier (Basic, Hot, Burning, Fiery, Infernal). Only while you're leader.") @ConfigEditorBoolean public boolean kuudra = true;
        @Expose @ConfigOption(name = "!fps", desc = "Says your FPS in party chat (Odin's). Works whoever is leader.") @ConfigEditorBoolean public boolean fps = true;
        @Expose @ConfigOption(name = "!ping", desc = "Says your ping in party chat. Works whoever is leader.") @ConfigEditorBoolean public boolean ping = true;
        @Expose @ConfigOption(name = "!tps", desc = "Says the server's TPS in party chat. Works whoever is leader.") @ConfigEditorBoolean public boolean tps = true;
        @Expose @ConfigOption(name = "!carrot", desc = "SBO's Ask Carrot: a magic 8-ball answer in party chat.") @ConfigEditorBoolean public boolean carrot = false;
        @Expose @ConfigOption(name = "!time", desc = "Says your time in party chat.") @ConfigEditorBoolean public boolean time = false;

        @Expose
        @ConfigOption(name = "Diana Commands", desc = "SBO's Diana party commands, answered from this season's Diana trackers: !chim, !chimls, !inq, !king, !manti, !sphinx, !relic, !stick, !core, !wool, !food, !stinger, !feathers, !mobs, !burrows, !profit, !playtime, !mf, !stats <you>, !help and !since <chim|inq|relic|stick|king|manti|core|wool|food|...>.")
        @ConfigEditorBoolean
        public boolean diana = true;
    }

    public static final class SacksValue {
        @Expose
        @ConfigOption(name = "Sacks Value", desc = "Show what everything in your sacks is worth and how many items are in them on a HUD (SkyHanni's Sack Display value). Open your sacks once to fill it; after that it follows the [Sacks] messages and your bazaar insta-sells, so you don't have to reopen them. /s2 resetsacks forgets it. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Price", desc = "Bazaar insta-sell price, sell offer price, or both as a range (insta-sell to sell offer). Items not on the bazaar use their NPC sell price.")
        @ConfigEditorDropdown
        public SacksPriceMode priceMode = SacksPriceMode.RANGE;

        @Expose
        @ConfigOption(name = "Value History", desc = "Save your sacks' total value once a day per profile (the latest value of each day; the insta-sell value when Price is Range) whenever Sacks Value works it out, and draw it as a graph with /s2 sacks history. Needs Sacks Value on.")
        @ConfigEditorBoolean
        public boolean history = true;

        @ConfigOption(name = "Open Value Graph", desc = "Your sacks' value over time. Also /s2 sacks history.")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable openHistory = com.epic60869.sky2m.features.misc.SackValueHistory::open;
    }

    public enum SacksPriceMode {
        INSTANT_SELL("Instant Sell"), SELL_OFFER("Sell Offer"), RANGE("Range");

        private final String label;

        SacksPriceMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class ItemNotification {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show items from your list on a HUD when you get them (in your sacks or your inventory), in the RNG HUD (with the farming RNG drops): amount, name and total price. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @ConfigOption(name = "Items", desc = "Open the list of items to watch for: one per line, with item name suggestions as you type. Also /s2 itemnotify.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton(buttonText = "EDIT")
        public Runnable editItems = com.epic60869.sky2m.features.misc.ItemNotification::openEditor;

        /** The items, one per line (edited in the Items window). */
        @Expose
        public String items = "";

        @Expose
        @ConfigOption(name = "Use Item Rarity Colour", desc = "Show each item's name in its own rarity colour on the RNG HUD. Turn off to use the Name Colour below.")
        @ConfigEditorBoolean
        public boolean rarityColour = true;

        @Expose
        @ConfigOption(name = "Name Colour", desc = "Colour of item names on the RNG HUD when Use Item Rarity Colour is off.")
        @ConfigEditorColour
        public String nameColour = "0:255:255:255:255";

        @Expose
        @ConfigOption(name = "Check Sacks", desc = "Watch the [Sacks] messages for items on the list.")
        @ConfigEditorBoolean
        public boolean checkSacks = true;

        @Expose
        @ConfigOption(name = "Check Inventory", desc = "Watch your inventory for items on the list.")
        @ConfigEditorBoolean
        public boolean checkInventory = true;

        @Expose
        @ConfigOption(name = "Show For (seconds)", desc = "How long an item stays on the HUD after you get it. Getting more of it keeps it there and adds to the amount.")
        @ConfigEditorSlider(minValue = 2, maxValue = 30, minStep = 1)
        public int seconds = 5;

        @Expose
        @ConfigOption(name = "Sound", desc = "Play a sound when an item on the list comes in.")
        @ConfigEditorBoolean
        public boolean sound = true;
    }

    /** The Item List (Item Browser): SkyBlock Item List's panel beside inventories, written for Sky2M. */
    public static final class ItemList {
        public enum Side {
            RIGHT("Right"), LEFT("Left");

            private final String label;

            Side(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum ClickAction {
            RECIPE("Show Recipes"), USAGE("Show Usages"), WIKI("Open Wiki"), NOTHING("Nothing");

            private final String label;

            ClickAction(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum HideWhen {
            SEARCH("No Search"), FILTER("No Filter"), NEVER("Never");

            private final String label;

            HideWhen(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        /** How the list is ordered, like NEU's sort buttons. */
        public enum Sort {
            DEFAULT("Default"), PRICE("Price"), RARITY("Rarity"), TYPE("Type"), NAME("Name");

            private final String label;

            Sort(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @Expose
        @ConfigOption(name = "Enabled", desc = "A panel of every SkyBlock item, attribute, enchantment, mob, NPC, pet, potion and rune beside your inventory and SkyBlock menus, like SkyBlock Item List. Click an item for its recipes (crafting, Forge, Kat, NPC shops, mob drops), search, type sums in the search bar, and double-click the search bar to highlight matching items in the menu you have open. Items come from the NotEnoughUpdates repo (about 22 MB, downloaded once).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show Outside SkyBlock", desc = "Also show it in your inventory outside SkyBlock.")
        @ConfigEditorBoolean
        public boolean outsideSkyblock = false;

        @Expose
        @ConfigOption(name = "Side", desc = "Which side of the screen the list is on. Favourites go on the other side.")
        @ConfigEditorDropdown
        public Side side = Side.RIGHT;

        @Expose
        @ConfigOption(name = "Max Width", desc = "How much of the space beside the menu the list may use.")
        @ConfigEditorSlider(minValue = 0.25f, maxValue = 1f, minStep = 0.05f)
        public float maxWidth = 1f;

        @Expose
        @ConfigOption(name = "Item Size", desc = "How big the items are. Hold Ctrl and scroll on the list to change it there.")
        @ConfigEditorSlider(minValue = 8, maxValue = 48, minStep = 1)
        public int itemSize = 18;

        @Expose
        @ConfigOption(name = "Hide List When", desc = "Hide the items until you search (or pick a filter), which keeps the list out of the way. Never: always show every item.")
        @ConfigEditorDropdown
        public HideWhen hideWhen = HideWhen.SEARCH;

        @Expose
        @ConfigOption(name = "Sort By", desc = "How the list is ordered. Price: most expensive first (lowest BIN, else bazaar, else NPC price). Rarity: highest first. Type: by what the item is (Boots, Helmet, Sword ...). Also the sort button beside the search bar: click for the next order, right click to reverse it. Search type:armor, type:sword or rarity:legendary to narrow the list.")
        @ConfigEditorDropdown
        public Sort sort = Sort.DEFAULT;

        @Expose
        @ConfigOption(name = "Reverse Sort", desc = "Reverse the order: cheapest or lowest rarity first, Z to A.")
        @ConfigEditorBoolean
        public boolean sortReversed = false;

        @Expose
        @ConfigOption(name = "Left Click", desc = "What clicking an item in the list or a recipe does.")
        @ConfigEditorDropdown
        public ClickAction leftClick = ClickAction.RECIPE;

        @Expose
        @ConfigOption(name = "Right Click", desc = "What right-clicking an item does.")
        @ConfigEditorDropdown
        public ClickAction rightClick = ClickAction.WIKI;

        @Expose
        @ConfigOption(name = "Middle Click", desc = "What middle-clicking an item does.")
        @ConfigEditorDropdown
        public ClickAction middleClick = ClickAction.NOTHING;

        @Expose
        @ConfigOption(name = "Hide Vanilla Items", desc = "Leave out plain Minecraft items.")
        @ConfigEditorBoolean
        public boolean hideVanilla = false;

        @Expose
        @ConfigOption(name = "Prefix Matches First", desc = "Items whose name starts with your search come first.")
        @ConfigEditorBoolean
        public boolean prefixFirst = true;

        @Expose
        @ConfigOption(name = "Fuzzy Search", desc = "Also find items when the search has small typos.")
        @ConfigEditorBoolean
        public boolean fuzzy = false;

        @Expose
        @ConfigOption(name = "Clear Search on Restart", desc = "Start with an empty search bar after restarting the game.")
        @ConfigEditorBoolean
        public boolean clearOnRestart = false;

        @Expose
        @ConfigOption(name = "Favourites", desc = "A Favourites panel on the other side: press the Favourite key over an item or recipe to add it. Ctrl and click a favourite recipe to pin it at the top.")
        @ConfigEditorBoolean
        public boolean favourites = true;

        @Expose
        @ConfigOption(name = "Favourites Item Size", desc = "How big the favourites are. Hold Ctrl and scroll on them to change it there.")
        @ConfigEditorSlider(minValue = 8, maxValue = 48, minStep = 1)
        public int favouritesItemSize = 18;

        @Expose
        @ConfigOption(name = "Calculator Needs =", desc = "Only work out sums when the search starts with =. Off: anything that looks like a sum is worked out (2.5m*3, bz(ENCHANTED_DIAMOND)*64).")
        @ConfigEditorBoolean
        public boolean calculatorNeedsEquals = false;

        @Expose
        @ConfigOption(name = "Enter Replaces Sum", desc = "Pressing Enter replaces the sum with its result.")
        @ConfigEditorBoolean
        public boolean enterReplaces = true;

        @Expose
        @ConfigOption(name = "Hide Key", desc = "Hold Ctrl and press this to hide or show the list and favourites.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = com.mojang.blaze3d.platform.InputConstants.KEY_O)
        public int hideKey = com.mojang.blaze3d.platform.InputConstants.KEY_O;

        @Expose
        @ConfigOption(name = "Recipe Key", desc = "Over an item (in the list, a recipe or any menu): show its recipes.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = com.mojang.blaze3d.platform.InputConstants.KEY_R)
        public int recipeKey = com.mojang.blaze3d.platform.InputConstants.KEY_R;

        @Expose
        @ConfigOption(name = "Usage Key", desc = "Over an item: show the recipes that use it.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = com.mojang.blaze3d.platform.InputConstants.KEY_U)
        public int usageKey = com.mojang.blaze3d.platform.InputConstants.KEY_U;

        @Expose
        @ConfigOption(name = "Back Key", desc = "In a recipe: go back to the one before.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE)
        public int backKey = com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE;

        @Expose
        @ConfigOption(name = "Favourite Key", desc = "Over an item or a recipe: add it to (or remove it from) your favourites.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = com.mojang.blaze3d.platform.InputConstants.KEY_F)
        public int favouriteKey = com.mojang.blaze3d.platform.InputConstants.KEY_F;

        @Expose
        @ConfigOption(name = "Search Key", desc = "Jump to the search bar while the list is open.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = -1)
        public int searchKey = -1;

        /** Remembered between sessions. */
        @Expose
        public String lastSearch = "";

        @Expose
        public String lastFilter = "ALL";

        @Expose
        public boolean hidden = false;
    }

    /** Ability Timers (Killer560's): countdowns you start with a key when you use an ability. */
    public static final class AbilityTimers {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Countdowns you start with a key the moment you use an ability (a mask, a Ragnarock Axe, Wither Cloak...), shown in a HUD you can move in /s2 gui. Set each timer's name, length, key and colour below. Keys only count while no menu or chat is open.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Ready Sound", desc = "Play a sound when a timer runs out.")
        @ConfigEditorBoolean
        public boolean readySound = false;

        @Expose
        @ConfigOption(name = "Show Ready Timers", desc = "Keep showing a timer as READY after it runs out, until you start it again.")
        @ConfigEditorBoolean
        public boolean showReady = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Timer 1", desc = "")
        public AbilityTimer timer1 = new AbilityTimer("Mask", 60);

        @Expose
        @Accordion
        @ConfigOption(name = "Timer 2", desc = "")
        public AbilityTimer timer2 = new AbilityTimer("Timer 2", 30);

        @Expose
        @Accordion
        @ConfigOption(name = "Timer 3", desc = "")
        public AbilityTimer timer3 = new AbilityTimer("Timer 3", 30);

        @Expose
        @Accordion
        @ConfigOption(name = "Timer 4", desc = "")
        public AbilityTimer timer4 = new AbilityTimer("Timer 4", 30);

        @Expose
        @Accordion
        @ConfigOption(name = "Timer 5", desc = "")
        public AbilityTimer timer5 = new AbilityTimer("Timer 5", 30);

        public java.util.List<AbilityTimer> timers() {
            return java.util.List.of(timer1, timer2, timer3, timer4, timer5);
        }
    }

    public static final class AbilityTimer {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Use this timer.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Name", desc = "Shown in the HUD.")
        @ConfigEditorText
        public String name;

        @Expose
        @ConfigOption(name = "Seconds", desc = "How long it counts down.")
        @ConfigEditorSlider(minValue = 1, maxValue = 300, minStep = 1)
        public int seconds;

        @Expose
        @ConfigOption(name = "Key", desc = "Starts (or restarts) the countdown.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind(defaultKey = -1)
        public int key = -1;

        @Expose
        @ConfigOption(name = "Colour", desc = "The square beside its name.")
        @ConfigEditorColour
        public String colour = "0:255:255:170:0";

        public AbilityTimer() {
            this("Timer", 30);
        }

        public AbilityTimer(String name, int seconds) {
            this.name = name;
            this.seconds = seconds;
        }
    }

    /** Rag Axe timer (Killer560's): Ragnarock Axe cast, buff and cooldown timers and "rag now" prompts. */
    public static final class RagAxe {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Spots your Ragnarock Axe casts (by the ability's sound while you hold the axe, like Odin) and times the 3s channel, the 10s Strength buff and the cooldown, in a HUD you can move in /s2 gui. Also \"rag now\" prompts in boss fights, below.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Channel Timer", desc = "Show the 3s channel before the buff.")
        @ConfigEditorBoolean
        public boolean channelTimer = false;

        @Expose
        @ConfigOption(name = "Buff Timer", desc = "Show how long the Strength buff has left.")
        @ConfigEditorBoolean
        public boolean buffTimer = true;

        @Expose
        @ConfigOption(name = "Cooldown Timer", desc = "Show the time until you can cast it again.")
        @ConfigEditorBoolean
        public boolean cooldownTimer = false;

        @Expose
        @ConfigOption(name = "Cooldown", desc = "The axe's cooldown in seconds. Hypixel doesn't tell the client your cooldown reductions, so set yours here (20 is the base).")
        @ConfigEditorSlider(minValue = 5, maxValue = 30, minStep = 0.5f)
        public float cooldownSeconds = 20f;

        @Expose
        @ConfigOption(name = "Mage Cooldown Reduction", desc = "Take this much off the cooldown while you're playing Mage in a dungeon (its perk shortens ability cooldowns; it grows with class level). 0 turns it off.")
        @ConfigEditorSlider(minValue = 0, maxValue = 50, minStep = 1)
        public int mageReduction = 25;

        @Expose
        @ConfigOption(name = "Sound Is Buff Start", desc = "Off: the cast sound starts the 3s channel and the buff follows. On: the buff starts at the sound. Only change this if the timers are 3s off.")
        @ConfigEditorBoolean
        public boolean soundIsBuffStart = false;

        @Expose
        @ConfigOption(name = "Cast Alert", desc = "\"Casted Rag\" on your screen when you cast it.")
        @ConfigEditorBoolean
        public boolean castAlert = true;

        @Expose
        @ConfigOption(name = "Cancel Alert", desc = "\"Ragnarock Cancelled!\" when taking damage cancels the channel.")
        @ConfigEditorBoolean
        public boolean cancelAlert = true;

        @Expose
        @ConfigOption(name = "End Alert", desc = "\"Ragnarock Ended\" when the buff runs out.")
        @ConfigEditorBoolean
        public boolean endAlert = true;

        @Expose
        @ConfigOption(name = "Ready Alert", desc = "\"Ragnarock Ready\" when the cooldown is over.")
        @ConfigEditorBoolean
        public boolean readyAlert = false;

        @Expose
        @ConfigOption(name = "Strength Message", desc = "When the buff starts, how much Strength it gave (1.5x the axe's own) in chat, only to you.")
        @ConfigEditorBoolean
        public boolean strengthMessage = true;

        @Expose
        @ConfigOption(name = "Announce Strength", desc = "Also say it in party chat.")
        @ConfigEditorBoolean
        public boolean announceStrength = false;

        @Expose
        @ConfigOption(name = "Prompt Text", desc = "What the \"rag now\" prompts show.")
        @ConfigEditorText
        public String promptText = "rag";

        @Expose
        @ConfigOption(name = "Prompt As Title", desc = "Also show prompts as a big title (they always show in their HUD).")
        @ConfigEditorBoolean
        public boolean promptTitle = false;

        @Expose
        @ConfigOption(name = "Skip Tank and Healer", desc = "No dragon prompts while you're Tank or Healer (they don't rag for dragons).")
        @ConfigEditorBoolean
        public boolean skipTankHealer = true;

        @Expose
        @Accordion
        @ConfigOption(name = "M7 Wither King Line", desc = "When the Wither King says \"I no longer wish to fight...\" (NoammAddons' M7 rag call).")
        public RagPrompt m7Dragons = new RagPrompt();

        @Expose
        @Accordion
        @ConfigOption(name = "M7 Dragon Spawns", desc = "Before each dragon spawns, from Wither Dragons' spawn timers (turn Wither Dragons on for this).")
        public RagPrompt m7DragonSpawn = new RagPrompt();

        @Expose
        @Accordion
        @ConfigOption(name = "F7/M7 Necron", desc = "After Necron's \"You went further than any human before...\" line, when he's down and hittable (about 8s; an estimate, tune the lead).")
        public RagPrompt necron = new RagPrompt();

        @Expose
        @Accordion
        @ConfigOption(name = "F7/M7 Storm", desc = "After Storm's \"Pathetic Maxor...\" line (about 5s; an estimate, tune the lead).")
        public RagPrompt storm = new RagPrompt();

        @Expose
        @Accordion
        @ConfigOption(name = "F5/M5 Livid", desc = "Before Livid can first be hit, 19.5s after her entry line.")
        public RagPrompt livid = new RagPrompt();
    }

    public static final class RagPrompt {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show this prompt.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Lead", desc = "How many milliseconds early to prompt. 3000 (the channel) means the buff lands right on the moment.")
        @ConfigEditorSlider(minValue = 0, maxValue = 10000, minStep = 100)
        public int leadMs = 3000;

        @Expose
        @ConfigOption(name = "Sound", desc = "Play NoammAddons' rising rag tune with the prompt.")
        @ConfigEditorBoolean
        public boolean sound = false;
    }

    /** Custom Mage Beam (Killer560's, from NoammAddons): one clean beam in place of the Mage's firework particles. */
    public static final class MageBeam {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In dungeons, draw each Mage beam as one clean beam from start to end, in your colour and width, instead of the trail of firework particles (NoammAddons' Mage Beam).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Hide Particles", desc = "Hide the firework particles the beam is made of, so only the custom beam shows.")
        @ConfigEditorBoolean
        public boolean hideParticles = true;

        @Expose
        @ConfigOption(name = "Colour", desc = "The beam's colour.")
        @ConfigEditorColour
        public String colour = "0:255:204:102:0";

        @Expose
        @ConfigOption(name = "Width", desc = "How thick the beam is.")
        @ConfigEditorSlider(minValue = 1, maxValue = 20, minStep = 0.5f)
        public float width = 2f;

        @Expose
        @ConfigOption(name = "Duration", desc = "How long a beam stays, in ticks (20 a second).")
        @ConfigEditorSlider(minValue = 5, maxValue = 100, minStep = 1)
        public int durationTicks = 40;

        @Expose
        @ConfigOption(name = "Fade", desc = "Fade the beam out over its duration.")
        @ConfigEditorBoolean
        public boolean fade = false;
    }

    /** Terminal Timers (Killer560's, from Odin's Terminal Times): P3 terminal, device and section times. */
    public static final class TerminalTimers {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Time F7/M7 P3 (Goldor): each terminal, device and lever, each section and the whole phase, in chat and a HUD you can move in /s2 gui (Odin's Terminal Times).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Splits in Chat", desc = "Add (section | phase) times to every \"... completed a terminal! (3/7)\" line, and the section times and total when the core opens.")
        @ConfigEditorBoolean
        public boolean splits = true;

        @Expose
        @ConfigOption(name = "Solve Times", desc = "\"Correct all the panes! solved in 4.21s!\" in chat after each terminal you complete (from opening it to closing it).")
        @ConfigEditorBoolean
        public boolean solveTimes = true;

        @Expose
        @ConfigOption(name = "HUD", desc = "During P3, a HUD with the section you're on, its time, the phase time and the finished sections.")
        @ConfigEditorBoolean
        public boolean hud = true;
    }

    /** Dungeon Run Summary and History (Killer560's): every finished run saved, with /s2 runs. */
    public static final class RunHistory {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Save every dungeon run (floor, time, splits, P3 terminal times, score, secrets, crypts, deaths, class and party) and see them with /s2 runs, with your personal bests per floor.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Chat Summary", desc = "After each run, a line in chat with its time, score, secrets and deaths, and a button to the history.")
        @ConfigEditorBoolean
        public boolean chatSummary = true;

        @Expose
        @ConfigOption(name = "Announce Personal Bests", desc = "Say so in chat when a run beats your best time or score on that floor.")
        @ConfigEditorBoolean
        public boolean announceBests = true;

        @Expose
        @ConfigOption(name = "Runs Kept", desc = "How many runs to keep; the oldest go first.")
        @ConfigEditorSlider(minValue = 50, maxValue = 2000, minStep = 50)
        public int maxRuns = 500;

        @Expose
        @ConfigOption(name = "Record Route", desc = "Record your path through each run (and Dungeon Sim run) so it can be replayed on the map: Replay in a run's details, or /s2 replay.")
        @ConfigEditorBoolean
        public boolean recordRoute = true;
    }

    /** Custom Scoreboard, like j10a1n15's Custom Scoreboard mod (LGPL), written for Sky2M. */
    public static final class CustomScoreboard {
        public enum Element {
            TITLE("§e§lSkyBlock §7(Title)"), LOBBY("§8Lobby (date and server)"), DATE("SkyBlock Date"), TIME("SkyBlock Time"),
            IRL_TIME("Real Time"), ISLAND("§a㋖ Island"), AREA("§7⏣ Area"), PROFILE("Profile"), SKYBLOCK_LEVEL("SkyBlock Level"),
            PLAYER_COUNT("Player Count"), SEPARATOR("------- Separator -------"), PURSE("§6Purse"), BANK("§6Bank"), MOTES("§dMotes"),
            BITS("§bBits"), COPPER("§cCopper"), SOWDUST("§6Sowdust"), GEMS("§aGems"), HEAT("§cHeat"), COLD("§bCold"),
            NORTH_STARS("§dNorth Stars"), SOULFLOW("§3Soulflow"), SEPARATOR_2("------- Separator -------"),
            COOKIE_BUFF("§dBooster Cookie"), POWDER("§9Powder"), PET("Pet"), PARTY("§9Party"), OBJECTIVE("Objective"),
            SLAYER("§cSlayer Quest"), EVENTS("§eEvents §7(see Events below)"), SEPARATOR_3("------- Separator -------"),
            SEPARATOR_4("------- Separator -------"), FOOTER("§ewww.hypixel.net §7(Footer)");

            private final String label;

            Element(String label) {
                this.label = label;
            }

            public boolean isSeparator() {
                return name().startsWith("SEPARATOR");
            }

            @Override
            public String toString() {
                return label;
            }
        }

        /** The Events line's parts, like the Custom Scoreboard mod's events list: shown in this order. */
        public enum Event {
            SERVER_RESTART("Server Restart"), DUNGEONS("Dungeons"), KUUDRA("Kuudra"), DOJO("Dojo"), DARK_AUCTION("Dark Auction"),
            JACOBS_CONTEST("Jacob's Contest"), JACOB_MEDALS("Jacob's Medals"), TRAPPER("Trapper"), GARDEN("Garden"),
            FLIGHT_DURATION("Flight Duration"), WINTER("Jerry's Workshop"), NEW_YEAR("New Year"), ANNIVERSARY("Anniversary"),
            SPOOKY("Spooky Festival"), CARNIVAL("Carnival"), BROODMOTHER("Broodmother"), MINING("Mining"), FORAGING("Foraging"),
            DAMAGE("Dragon / Protector Damage"), MAGMA_BOSS("Magma Boss"), RIFT("Rift"), ESSENCE("Essence"), QUEUE("Queue"),
            REDSTONE("Redstone"), VOTING("Election"), ACTIVE_TABLIST("Tab List Event"), OTHER("Other Lines §7(anything unknown)");

            private final String label;

            Event(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum Align {
            LEFT("Left"), CENTER("Centre"), RIGHT("Right");

            private final String label;

            Align(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum HorizontalPosition {
            LEFT("Left"), CENTER("Centre"), RIGHT("Right"), FREE("Free");

            private final String label;

            HorizontalPosition(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum VerticalPosition {
            TOP("Top"), CENTER("Centre"), BOTTOM("Bottom"), FREE("Free");

            private final String label;

            VerticalPosition(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum NumberFormat {
            LONG("1,234,567"), SHORT("1.23M");

            private final String label;

            NumberFormat(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum NumberDisplay {
            TEXT_NUMBER("§fPurse: §6123"), COLOR_TEXT_NUMBER("§6Purse: 123"), COLOR_NUMBER_TEXT("§6123 Purse"),
            NUMBER_TEXT("§6123 §fPurse"), NUMBER_ONLY("§6123");

            private final String label;

            NumberDisplay(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        @ConfigOption(name = "Open Editor", desc = "Set it up in its own screen, like the Custom Scoreboard mod's: drag the lines and events into order, change the title, background and options, and see it change as you go. Also /s2 scoreboard.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton(buttonText = "Open")
        public Runnable openEditor = com.epic60869.sky2m.features.misc.CustomScoreboard::openEditor;

        @Expose
        @ConfigOption(name = "Enabled", desc = "Replace the sidebar with your own: pick which lines show and in what order, with a background (rounded, bordered, or your own picture or GIF), title and footer, number changes like \"(+12k)\", and lines you can click with chat open. Like j10a1n15's Custom Scoreboard.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Hide Hypixel's Sidebar", desc = "Hide the normal sidebar while the custom one is on.")
        @ConfigEditorBoolean
        public boolean hideHypixel = true;

        @Expose
        @ConfigOption(name = "Custom Lines", desc = "Build the lines from your list. Off: show Hypixel's own lines in the custom box.")
        @ConfigEditorBoolean
        public boolean customLines = true;

        @Expose
        @ConfigOption(name = "Outside SkyBlock", desc = "Also replace the sidebar outside SkyBlock (it shows Hypixel's own lines there).")
        @ConfigEditorBoolean
        public boolean outsideSkyblock = false;

        @Expose
        @ConfigOption(name = "Hide While Tab Is Held", desc = "Hide it while you hold the player list key.")
        @ConfigEditorBoolean
        public boolean hideWhenTab = false;

        @Expose
        @ConfigOption(name = "Hide While Chat Is Open", desc = "Hide it while chat is open (its click actions need chat open, so they're off then).")
        @ConfigEditorBoolean
        public boolean hideWhenChat = false;

        @Expose
        @ConfigOption(name = "Hide With F3", desc = "Hide it while the debug screen is open.")
        @ConfigEditorBoolean
        public boolean hideWhenF3 = false;

        @Expose
        @ConfigOption(name = "Text Shadow", desc = "Draw the lines with a shadow.")
        @ConfigEditorBoolean
        public boolean textShadow = true;

        @Expose
        @ConfigOption(name = "Click and Hover Actions", desc = "With chat open, hover lines for a tip and click them to run their command (Bits opens the SkyBlock menu, Objective your quests, Island /warp...).")
        @ConfigEditorBoolean
        public boolean actions = true;

        @Expose
        @ConfigOption(name = "Lines", desc = "Which lines show, in order. Drag to reorder; separators are blank lines. Keep Events in: it's where dungeon, Kuudra, Garden and other changing lines go.")
        @ConfigEditorDraggableList
        public java.util.List<Element> lines = new java.util.ArrayList<>(java.util.List.of(Element.TITLE, Element.LOBBY, Element.SEPARATOR,
            Element.DATE, Element.TIME, Element.ISLAND, Element.AREA, Element.PROFILE, Element.SEPARATOR_2, Element.PURSE, Element.MOTES,
            Element.BANK, Element.BITS, Element.COPPER, Element.SOWDUST, Element.GEMS, Element.HEAT, Element.COLD, Element.NORTH_STARS,
            Element.SOULFLOW, Element.SEPARATOR_3, Element.OBJECTIVE, Element.SLAYER, Element.EVENTS, Element.POWDER, Element.PARTY,
            Element.PET, Element.FOOTER));

        @Expose
        @ConfigOption(name = "Events", desc = "What the Events line shows, in order: each one shows Hypixel's lines for it while it's going on.")
        @ConfigEditorDraggableList
        public java.util.List<Event> events = new java.util.ArrayList<>(java.util.List.of(Event.values()));

        /** Which default layout this config has been moved to (1: the Custom Scoreboard mod's order). */
        @Expose
        public int layoutVersion = 0;

        @Expose
        @ConfigOption(name = "Horizontal Position", desc = "Where on the screen it sits, side to side. Free: drag it anywhere in /s2 gui.")
        @ConfigEditorDropdown
        public HorizontalPosition horizontal = HorizontalPosition.RIGHT;

        @Expose
        @ConfigOption(name = "Vertical Position", desc = "Where on the screen it sits, up and down; it grows from there as lines come and go. Free: drag it anywhere in /s2 gui.")
        @ConfigEditorDropdown
        public VerticalPosition vertical = VerticalPosition.CENTER;

        @Expose
        @ConfigOption(name = "Scale", desc = "How big it is (with a Free position, scroll on it in /s2 gui instead).")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2f, minStep = 0.05f)
        public float scale = 1f;

        @Expose
        @ConfigOption(name = "Text Alignment", desc = "Where lines sit in the box (the title and footer have their own).")
        @ConfigEditorDropdown
        public Align lineAlignment = Align.LEFT;

        @Expose
        @ConfigOption(name = "Line Spacing", desc = "Extra pixels between lines.")
        @ConfigEditorSlider(minValue = 0, maxValue = 6, minStep = 1)
        public int lineSpacing = 0;

        @Expose
        @Accordion
        @ConfigOption(name = "Title and Footer", desc = "")
        public ScoreboardTitle title = new ScoreboardTitle();

        @Expose
        @Accordion
        @ConfigOption(name = "Background", desc = "")
        public ScoreboardBackground background = new ScoreboardBackground();

        @Expose
        @Accordion
        @ConfigOption(name = "Line Options", desc = "")
        public ScoreboardLines lineOptions = new ScoreboardLines();

        @Expose
        @Accordion
        @ConfigOption(name = "Colours and Labels", desc = "The colour and name of each number line (Purse, Bank, Bits...).")
        public ScoreboardColours colours = new ScoreboardColours();
    }

    public static final class ScoreboardTitle {
        @Expose
        @ConfigOption(name = "Hypixel's Title", desc = "Show Hypixel's own title (SKYBLOCK, or the event's) instead of your text.")
        @ConfigEditorBoolean
        public boolean hypixelTitle = true;

        @Expose
        @ConfigOption(name = "Title Text", desc = "Your title; & colour codes work (&6&lSKYBLOCK), and \\n starts a new line.")
        @ConfigEditorText
        public String titleText = "&e&lSkyBlock";

        @Expose
        @ConfigOption(name = "Title Alignment", desc = "")
        @ConfigEditorDropdown
        public CustomScoreboard.Align titleAlignment = CustomScoreboard.Align.CENTER;

        @Expose
        @ConfigOption(name = "Hypixel's Footer", desc = "Show www.hypixel.net instead of your text.")
        @ConfigEditorBoolean
        public boolean hypixelFooter = true;

        @Expose
        @ConfigOption(name = "Footer Text", desc = "Your footer; & colour codes work, and \\n starts a new line.")
        @ConfigEditorText
        public String footerText = "&ewww.hypixel.net";

        @Expose
        @ConfigOption(name = "Footer Alignment", desc = "")
        @ConfigEditorDropdown
        public CustomScoreboard.Align footerAlignment = CustomScoreboard.Align.CENTER;
    }

    public static final class ScoreboardBackground {
        @Expose
        @ConfigOption(name = "Background", desc = "Draw a background behind the lines.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Colour", desc = "The background's colour (and how see-through it is).")
        @ConfigEditorColour
        public String colour = "0:85:0:0:0";

        @Expose
        @ConfigOption(name = "Padding", desc = "Space between the lines and the edge of the box.")
        @ConfigEditorSlider(minValue = 0, maxValue = 15, minStep = 1)
        public int padding = 5;

        @Expose
        @ConfigOption(name = "Margin", desc = "Space between the box and the edge of the screen.")
        @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1)
        public int margin = 0;

        @Expose
        @ConfigOption(name = "Rounded Corners", desc = "How round the corners are (0: square).")
        @ConfigEditorSlider(minValue = 0, maxValue = 12, minStep = 1)
        public int radius = 5;

        @Expose
        @ConfigOption(name = "Min Width", desc = "The box is at least this wide (the lines stay in the middle).")
        @ConfigEditorSlider(minValue = 0, maxValue = 250, minStep = 5)
        public int minWidth = 0;

        @Expose
        @ConfigOption(name = "Min Height", desc = "The box is at least this tall (the lines stay in the middle).")
        @ConfigEditorSlider(minValue = 0, maxValue = 250, minStep = 5)
        public int minHeight = 0;

        @Expose
        @ConfigOption(name = "Border", desc = "A border around the box, fading between its four corner colours.")
        @ConfigEditorBoolean
        public boolean outline = false;

        @Expose
        @ConfigOption(name = "Border Size", desc = "")
        @ConfigEditorSlider(minValue = 1, maxValue = 6, minStep = 1)
        public int outlineWidth = 3;

        @Expose
        @ConfigOption(name = "Border Top Left", desc = "")
        @ConfigEditorColour
        public String outlineTop = "0:255:50:161:219";

        @Expose
        @ConfigOption(name = "Border Top Right", desc = "")
        @ConfigEditorColour
        public String outlineTopRight = "0:255:50:219:194";

        @Expose
        @ConfigOption(name = "Border Bottom Left", desc = "")
        @ConfigEditorColour
        public String outlineBottom = "0:255:41:196:174";

        @Expose
        @ConfigOption(name = "Border Bottom Right", desc = "")
        @ConfigEditorColour
        public String outlineBottomRight = "0:255:43:207:122";

        @Expose
        @ConfigOption(name = "Picture", desc = "A PNG or GIF from config/sky2m/scoreboard to use as the background (its file name, e.g. stars.gif). Empty: none.")
        @ConfigEditorText
        public String image = "";

        @Expose
        @ConfigOption(name = "Picture Opacity", desc = "How see-through the picture is.")
        @ConfigEditorSlider(minValue = 0, maxValue = 100, minStep = 5)
        public int imageOpacity = 90;

        @ConfigOption(name = "Open Picture Folder", desc = "Opens config/sky2m/scoreboard, where pictures for the background go.")
        @io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton(buttonText = "Open")
        public Runnable openFolder = com.epic60869.sky2m.features.misc.CustomScoreboard::openFolder;
    }

    /** Custom Scoreboard: each number line's colour and name, like the Custom Scoreboard mod's custom prefixes. */
    public static final class ScoreboardColours {
        @Expose
        @ConfigOption(name = "Name Colour", desc = "The colour of the name on number lines (\"Purse:\"), in the Number Displays that colour it on its own.")
        @ConfigEditorColour
        public String label = "0:255:255:255:255";

        @Expose
        @ConfigOption(name = "Purse Colour", desc = "")
        @ConfigEditorColour
        public String purse = "0:255:255:170:0";

        @Expose
        @ConfigOption(name = "Purse Name", desc = "What the line calls it; & colour codes work. Empty: Purse.")
        @ConfigEditorText
        public String purseLabel = "Purse";

        @Expose
        @ConfigOption(name = "Piggy Colour", desc = "")
        @ConfigEditorColour
        public String piggy = "0:255:255:170:0";

        @Expose
        @ConfigOption(name = "Piggy Name", desc = "What the line calls it; & colour codes work. Empty: Piggy.")
        @ConfigEditorText
        public String piggyLabel = "Piggy";

        @Expose
        @ConfigOption(name = "Bank Colour", desc = "")
        @ConfigEditorColour
        public String bank = "0:255:255:170:0";

        @Expose
        @ConfigOption(name = "Bank Name", desc = "What the line calls it; & colour codes work. Empty: Bank.")
        @ConfigEditorText
        public String bankLabel = "Bank";

        @Expose
        @ConfigOption(name = "Motes Colour", desc = "")
        @ConfigEditorColour
        public String motes = "0:255:255:85:255";

        @Expose
        @ConfigOption(name = "Motes Name", desc = "What the line calls it; & colour codes work. Empty: Motes.")
        @ConfigEditorText
        public String motesLabel = "Motes";

        @Expose
        @ConfigOption(name = "Bits Colour", desc = "")
        @ConfigEditorColour
        public String bits = "0:255:85:255:255";

        @Expose
        @ConfigOption(name = "Bits Name", desc = "What the line calls it; & colour codes work. Empty: Bits.")
        @ConfigEditorText
        public String bitsLabel = "Bits";

        @Expose
        @ConfigOption(name = "Copper Colour", desc = "")
        @ConfigEditorColour
        public String copper = "0:255:255:85:85";

        @Expose
        @ConfigOption(name = "Copper Name", desc = "What the line calls it; & colour codes work. Empty: Copper.")
        @ConfigEditorText
        public String copperLabel = "Copper";

        @Expose
        @ConfigOption(name = "Sowdust Colour", desc = "")
        @ConfigEditorColour
        public String sowdust = "0:255:255:170:0";

        @Expose
        @ConfigOption(name = "Sowdust Name", desc = "What the line calls it; & colour codes work. Empty: Sowdust.")
        @ConfigEditorText
        public String sowdustLabel = "Sowdust";

        @Expose
        @ConfigOption(name = "Gems Colour", desc = "")
        @ConfigEditorColour
        public String gems = "0:255:85:255:85";

        @Expose
        @ConfigOption(name = "Gems Name", desc = "What the line calls it; & colour codes work. Empty: Gems.")
        @ConfigEditorText
        public String gemsLabel = "Gems";

        @Expose
        @ConfigOption(name = "Heat Colour", desc = "")
        @ConfigEditorColour
        public String heat = "0:255:255:85:85";

        @Expose
        @ConfigOption(name = "Heat Name", desc = "What the line calls it; & colour codes work. Empty: Heat.")
        @ConfigEditorText
        public String heatLabel = "Heat";

        @Expose
        @ConfigOption(name = "Cold Colour", desc = "")
        @ConfigEditorColour
        public String cold = "0:255:85:255:255";

        @Expose
        @ConfigOption(name = "Cold Name", desc = "What the line calls it; & colour codes work. Empty: Cold.")
        @ConfigEditorText
        public String coldLabel = "Cold";

        @Expose
        @ConfigOption(name = "North Stars Colour", desc = "")
        @ConfigEditorColour
        public String northStars = "0:255:255:85:255";

        @Expose
        @ConfigOption(name = "North Stars Name", desc = "What the line calls it; & colour codes work. Empty: North Stars.")
        @ConfigEditorText
        public String northStarsLabel = "North Stars";

        @Expose
        @ConfigOption(name = "Soulflow Colour", desc = "")
        @ConfigEditorColour
        public String soulflow = "0:255:0:170:170";

        @Expose
        @ConfigOption(name = "Soulflow Name", desc = "What the line calls it; & colour codes work. Empty: Soulflow.")
        @ConfigEditorText
        public String soulflowLabel = "Soulflow";

        @Expose
        @ConfigOption(name = "Players Colour", desc = "")
        @ConfigEditorColour
        public String players = "0:255:85:255:85";

        @Expose
        @ConfigOption(name = "Players Name", desc = "What the line calls it; & colour codes work. Empty: Players.")
        @ConfigEditorText
        public String playersLabel = "Players";
    }

    public static final class ScoreboardLines {
        @Expose
        @ConfigOption(name = "Number Format", desc = "How numbers like your purse are written.")
        @ConfigEditorDropdown
        public CustomScoreboard.NumberFormat numberFormat = CustomScoreboard.NumberFormat.LONG;

        @Expose
        @ConfigOption(name = "Number Display", desc = "How number lines are laid out and coloured.")
        @ConfigEditorDropdown
        public CustomScoreboard.NumberDisplay numberDisplay = CustomScoreboard.NumberDisplay.TEXT_NUMBER;

        @Expose
        @ConfigOption(name = "Show Changes", desc = "Show how much your purse, bits, motes, copper... just changed, like \"(+12,000)\", until 5 seconds after the last change.")
        @ConfigEditorBoolean
        public boolean showChanges = true;

        @Expose
        @ConfigOption(name = "Show Active Only", desc = "Hide number lines that are 0 (no copper, no motes...).")
        @ConfigEditorBoolean
        public boolean hideEmpty = false;

        @Expose
        @ConfigOption(name = "Show Piggy", desc = "Call your purse Piggy when Hypixel does (with a Piggy Bank).")
        @ConfigEditorBoolean
        public boolean showPiggy = false;

        @Expose
        @ConfigOption(name = "Hide Purse in Dungeons", desc = "")
        @ConfigEditorBoolean
        public boolean hidePurseInDungeons = false;

        @Expose
        @ConfigOption(name = "24-Hour Time", desc = "SkyBlock Time and Real Time as 21:40 instead of 9:40pm.")
        @ConfigEditorBoolean
        public boolean time24h = false;

        @Expose
        @ConfigOption(name = "Indent Location and Time", desc = "Indent the Date, Time, Island and Area lines by a space, like Hypixel.")
        @ConfigEditorBoolean
        public boolean indentLocationTime = false;

        @Expose
        @ConfigOption(name = "Condense Separators", desc = "Show one blank line where several meet.")
        @ConfigEditorBoolean
        public boolean condenseSeparators = true;

        @Expose
        @ConfigOption(name = "Hide Separators at Top and Bottom", desc = "No blank line at the very top or bottom.")
        @ConfigEditorBoolean
        public boolean hideSeparatorsAtEnds = true;

        @Expose
        @ConfigOption(name = "Show All Active Events", desc = "Show every event that's going on. Off: only the first one in your Events order.")
        @ConfigEditorBoolean
        public boolean showAllEvents = true;

        @Expose
        @ConfigOption(name = "Separator Between Events", desc = "A blank line between events.")
        @ConfigEditorBoolean
        public boolean separatorBetweenEvents = false;

        @Expose
        @ConfigOption(name = "Hide Tab List Events in Dungeons", desc = "Don't show the tab list's event (Spooky Festival, Mining Fiesta...) in dungeons and Kuudra.")
        @ConfigEditorBoolean
        public boolean tabEventsInDungeon = true;

        @Expose
        @ConfigOption(name = "Max Party Members", desc = "How many party members the Party line lists.")
        @ConfigEditorSlider(minValue = 1, maxValue = 25, minStep = 1)
        public int maxParty = 5;

        @Expose
        @ConfigOption(name = "Party in Dungeons", desc = "Also show the Party line in dungeons (Hypixel's sidebar already lists your team there).")
        @ConfigEditorBoolean
        public boolean partyInDungeons = false;

        @Expose
        @ConfigOption(name = "Powder Only While Mining", desc = "Only show Powder in the Dwarven Mines, Crystal Hollows and Mineshafts.")
        @ConfigEditorBoolean
        public boolean powderOnlyMining = true;
    }

    /** Party Dungeon Data (Killer560's): teammates running Sky2M share what they know about the run. */
    public static final class PartyData {
        @Expose
        @ConfigOption(name = "Share With Teammates", desc = "Send what this client knows about the run (Mimic and Prince dead, blood opened, secrets, crypts, deaths, P3 terminals, each room's secrets, M7 dragon spawns, your Melody) to teammates running Sky2M, through the Sky2M server. Only those facts are sent, only when they change; nothing about you.")
        @ConfigEditorBoolean
        public boolean share = false;

        @Expose
        @ConfigOption(name = "Use Teammates' Data", desc = "Believe what teammates' Sky2M sends: the score calculator counts a Mimic or Prince killed out of your sight, and their rooms' secrets are known.")
        @ConfigEditorBoolean
        public boolean receive = true;

        @Expose
        @ConfigOption(name = "HUD", desc = "In dungeons, a HUD of what the party knows (Mimic, Prince, blood, secrets, crypts, deaths) and who's sharing. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Mod Conflict Warnings", desc = "Once a session, say in chat when another mod does the same job as a Sky2M feature you have on (Skyblocker's terminal solver, the Custom Scoreboard and SkyBlock Item List mods...).")
        @ConfigEditorBoolean
        public boolean conflictWarnings = true;
    }

    /** Team Melody HUD (Killer560's): teammates' Melody terminal progress in P3. */
    public static final class TeamMelody {
        @Expose
        @ConfigOption(name = "HUD", desc = "During F7/M7 P3, each teammate's Melody progress, from their Sky2M (Party Dungeon Data), the Cross-Mod Bridge, or the \"Melody 2/4\" lines Odin and NoammAddons users send in party chat. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Show Yourself", desc = "Include your own Melody in the HUD.")
        @ConfigEditorBoolean
        public boolean showSelf = false;

        @Expose
        @ConfigOption(name = "Send Progress in Party Chat", desc = "Like Odin: when a row of your Melody is done, say \"Melody 2/4 - 50%\" in party chat, which Odin, NoammAddons and Sky2M users all read.")
        @ConfigEditorBoolean
        public boolean sendProgress = false;

        @Expose
        @ConfigOption(name = "Send Coords", desc = "Like Odin's Melody Send Coords: when you open Melody in P3, say where you are in party chat (\"x: 1, y: 2, z: 3\").")
        @ConfigEditorBoolean
        public boolean sendCoords = false;

        @Expose
        @ConfigOption(name = "Progress Alert", desc = "A title and a sound when a teammate's Melody progress comes in party chat, like \"Steve has 2/3 melody\" (NoammAddons' Melody Display).")
        @ConfigEditorBoolean
        public boolean alert = false;
    }

    /** Cross-Mod Bridge (Killer560's): share party dungeon data with Odin, NoammAddons and Devonian users. */
    public static final class CrossModBridge {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Talk to the party sockets of Odin (Melody progress), NoammAddons (map rooms, Mimic, Prince, dragons, room secrets) and Devonian (room secrets), so data flows both ways between Sky2M users and users of those mods in your party. Only on Hypixel, only while the grouping applies, never to a mod you have installed yourself (it already holds that connection), and only ever as your own signed-in account. Needs \"Use Teammates' Data\" (Party Dungeon Data) for what comes in.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Odin", desc = "Odin's socket (wss://ws.odtheking.com): during F7/M7 P3 until the core opens, Melody progress both ways. No sign-in.")
        @ConfigEditorBoolean
        public boolean odin = true;

        @Expose
        @ConfigOption(name = "NoammAddons", desc = "NoammAddons' socket (wss://ws.noamm.org): during dungeon runs with others, rooms, doors, Mimic, Prince, bat, dragons and room secrets both ways. Signs in with your chat-signing key (api.noamm.org).")
        @ConfigEditorBoolean
        public boolean noamm = true;

        @Expose
        @ConfigOption(name = "Devonian", desc = "Devonian's socket (ws://wss.docilelm.top): room secrets both ways, and teammates' secret counts in. Signs in through Mojang like a server login, once everyone in your party is known.")
        @ConfigEditorBoolean
        public boolean devonian = true;

        @ConfigOption(name = "Show Status", desc = "Say in chat how each connection is doing: connected, signed in, waiting, skipped (the mod is installed here), or gave up after failed sign-ins. Also /s2 bridge.")
        @ConfigEditorButton(buttonText = "Show")
        public Runnable showStatus = com.epic60869.sky2m.features.dungeons.bridge.BridgeFeature::printStatus;

        @ConfigOption(name = "Retry Now", desc = "Forget a failed sign-in or a wait between retries and try again now.")
        @ConfigEditorButton(buttonText = "Retry")
        public Runnable retry = com.epic60869.sky2m.features.dungeons.bridge.BridgeFeature::onSettingsChanged;
    }

    /** Dungeon Sim (Killer560's): practise dungeon rooms and whole floors in a singleplayer world of your own. */
    public static final class DungeonSim {
        @ConfigOption(name = "Open Dungeon Sim", desc = "Open the Dungeon Sim's menu: load any room or generate a whole floor (F1 to M7 sizes) in a singleplayer world, with secrets, puzzles, mobs, keys, the Mimic, a run timer and score. Also /s2 sim. It needs its own world, so leave the server first; from the title screen use the button below.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable open = com.epic60869.sky2m.sim.Sky2MSim::openMenu;

        @Expose
        @ConfigOption(name = "Title Screen Button", desc = "A \"Dungeon Sim\" button on the title screen, in the top left, that opens the Sim's menu.")
        @ConfigEditorBoolean
        public boolean titleButton = false;

        @Expose
        @ConfigOption(name = "1.8.9 AOTV Eye Line", desc = "Aspect of the Void / Instant Transmission teleports along your eye line like 1.8.9: the line starts at your eye (1.62 above the feet) and you land with your eyes in the block it reaches. Off: the old model, which walked the line from your feet.")
        @ConfigEditorBoolean
        public boolean aotvEyeLine = true;
    }
    // ------------------------------------------------------------------------------------------------ 1.0.5 additions

    public static final class DeathTracker {
        @Expose
        @ConfigOption(name = "HUD", desc = "In dungeons, a HUD listing each death this run: who, what killed them and the score it cost (2 each; the first death costs 1 if that player has a Spirit pet). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Chat Message", desc = "A line in chat (only you see it) for each death: who, what killed them, the score it cost and how many deaths the run has.")
        @ConfigEditorBoolean
        public boolean chat = false;

        @Expose
        @ConfigOption(name = "Count Across Runs", desc = "Also keep how many times each player has died in your runs, shown next to them in the HUD. /s2 deaths lists the most deaths.")
        @ConfigEditorBoolean
        public boolean lifetime = true;
    }

    public static final class ClassCounters {
        @Expose
        @ConfigOption(name = "Arrows Shot", desc = "How many arrows you've shot this run (a Terminator shot is 3), in a HUD you can move in /s2 gui.")
        @ConfigEditorBoolean
        public boolean arrows = false;

        @Expose
        @ConfigOption(name = "Arrows Only As Archer", desc = "Only count and show arrows while you're Archer.")
        @ConfigEditorBoolean
        public boolean arrowsArcherOnly = true;

        @Expose
        @ConfigOption(name = "Damage Taken", desc = "How much health you've lost this run, after defence (read from the action bar, so healing doesn't take any off), in a HUD you can move in /s2 gui.")
        @ConfigEditorBoolean
        public boolean damageTaken = false;

        @Expose
        @ConfigOption(name = "Damage Only As Tank", desc = "Only count and show damage taken while you're Tank.")
        @ConfigEditorBoolean
        public boolean damageTankOnly = true;

        @Expose
        @ConfigOption(name = "Run Summary", desc = "When the run ends, both counts in chat (only you see it).")
        @ConfigEditorBoolean
        public boolean summary = true;
    }

    public static final class KeyAlerts {
        @Expose
        @ConfigOption(name = "Key Pickup Alert", desc = "A title and a ding when a Wither Key or Blood Key is picked up, with who picked it up.")
        @ConfigEditorBoolean
        public boolean pickupAlert = false;

        @Expose
        @ConfigOption(name = "Door Opened Message", desc = "When a door opens, say in chat (only to you) who opened it and how long after the key was picked up. Hypixel doesn't say who opens the Blood Door, so it's whoever had the Blood Key.")
        @ConfigEditorBoolean
        public boolean doorMessage = false;

        @Expose
        @ConfigOption(name = "Key Holder HUD", desc = "A HUD with who has the Wither and Blood keys right now. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean holderHud = false;
    }

    public static final class DupeClass {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In the Party Finder, parties that already have your class get a red slot and a line in their tooltip. When someone joins your dungeon group, you're warned if their class is already taken.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Your Class", desc = "Auto: the class you last picked or played (read from the Catacombs menus and your dungeon runs).")
        @ConfigEditorDropdown
        public ClassOverride myClass = ClassOverride.AUTO;

        @Expose
        @ConfigOption(name = "Join Warning Sound", desc = "Also play a sound with the join warning.")
        @ConfigEditorBoolean
        public boolean sound = true;
    }

    public static final class CataCalculator {
        @ConfigOption(name = "How To Use", desc = "/s2 catacalc [level] [floor or XP per run]: how many runs of that floor to reach the level (50 and your last floor by default). It reads your Catacombs XP from your profile, and uses the XP your last run of that floor gave you (from the run summary), else the floor's base XP with the bonus below. /s2 catacalc 50 m7, /s2 catacalc 45 f7, /s2 catacalc 50 350000.")
        @ConfigEditorButton(buttonText = "50")
        public Runnable run = () -> com.epic60869.sky2m.features.dungeons.CataCalculator.calculate(50, "");

        @Expose
        @ConfigOption(name = "XP Bonus", desc = "Your Catacombs XP bonus in percent (Catacombs Expert Ring, Hecatomb, mayors...), used when a floor's XP isn't known from your own runs yet.")
        @ConfigEditorSlider(minValue = 0, maxValue = 200, minStep = 1)
        public int bonusPercent = 0;
    }

    public static final class DragonSplits {
        @Expose
        @ConfigOption(name = "Spawn Arrows", desc = "Arrows around your crosshair pointing at each M7 dragon that's spawning, in its colour, with its countdown.")
        @ConfigEditorBoolean
        public boolean arrows = false;

        @Expose
        @ConfigOption(name = "Arrow Distance", desc = "How far from the crosshair the arrows are drawn.")
        @ConfigEditorSlider(minValue = 20, maxValue = 150, minStep = 5)
        public int arrowRadius = 50;

        @Expose
        @ConfigOption(name = "Splits HUD", desc = "A HUD with each dragon killed this run and how long it was alive, with your best for each colour. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Chat Message", desc = "When a dragon dies, how long it took in chat (only you see it), and whether it was your best.")
        @ConfigEditorBoolean
        public boolean chat = false;
    }

    public static final class SlayerPhaseTimer {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Times each phase of your slayer boss: hit phases (the \"15 Hits\" shields of a Voidgloom) and damage phases, in a HUD you can move in /s2 gui, from the boss's spawn to its death.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Chat Summary", desc = "When the boss dies, every phase's time in chat (only you see it).")
        @ConfigEditorBoolean
        public boolean chat = true;

        @Expose
        @ConfigOption(name = "Phase Alerts", desc = "A title and ding when your boss switches between a hit phase and a damage phase (needs Enabled).")
        @ConfigEditorBoolean
        public boolean phaseAlerts = false;
    }

    /** Slayer RNG meter HUD, ported from SkyHanni's SlayerRngMeterDisplay. */
    public static final class SlayerRngMeterDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "A HUD for your current quest's slayer: the drop selected in its RNG Meter (open Maddox's RNG Meter menu once to read it), the meter's progress (\"Scythe Blade: 63.2% (XP / needed)\") from the Stored XP line after each boss, and about how many bosses are left. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Bosses Left", desc = "Show how many bosses are left: the XP still needed over the XP one boss added last time (or the tier's slayer XP before that).")
        @ConfigEditorBoolean
        public boolean bossesLeft = true;

        @Expose
        @ConfigOption(name = "Warn Without a Drop", desc = "After a boss, say in chat when that slayer has no RNG Meter drop selected.")
        @ConfigEditorBoolean
        public boolean warnEmpty = false;
    }

    public static final class DamageNumbers {
        @Expose
        @ConfigOption(name = "Short Numbers", desc = "Show damage numbers short (1.2M instead of 1,234,567), keeping their colours.")
        @ConfigEditorBoolean
        public boolean compact = false;

        @Expose
        @ConfigOption(name = "Hide Damage Numbers", desc = "Hide floating damage numbers completely.")
        @ConfigEditorBoolean
        public boolean hide = false;

        @Expose
        @ConfigOption(name = "Damage HUD", desc = "A HUD adding up the damage numbers that pop up near you: the total over the window below and per second. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "HUD Window", desc = "How many seconds of damage the HUD adds up.")
        @ConfigEditorSlider(minValue = 1, maxValue = 60, minStep = 1)
        public int windowSeconds = 10;

        @Expose
        @ConfigOption(name = "HUD Range", desc = "Only count damage numbers this close to you (blocks).")
        @ConfigEditorSlider(minValue = 3, maxValue = 30, minStep = 1)
        public int range = 12;
    }

    public static final class LowStats {
        @Expose
        @ConfigOption(name = "Low Health Warning", desc = "A title and a sound when your health (from the action bar) drops below the percentage below.")
        @ConfigEditorBoolean
        public boolean health = false;

        @Expose
        @ConfigOption(name = "Health Percent", desc = "Warn below this much of your max health.")
        @ConfigEditorSlider(minValue = 5, maxValue = 90, minStep = 5)
        public int healthPercent = 30;

        @Expose
        @ConfigOption(name = "Low Mana Warning", desc = "A title and a sound when your mana drops below the percentage below.")
        @ConfigEditorBoolean
        public boolean mana = false;

        @Expose
        @ConfigOption(name = "Mana Percent", desc = "Warn below this much of your max mana.")
        @ConfigEditorSlider(minValue = 5, maxValue = 90, minStep = 5)
        public int manaPercent = 20;

        @Expose
        @ConfigOption(name = "Sound", desc = "Play a sound with the warnings.")
        @ConfigEditorBoolean
        public boolean sound = true;

        @Expose
        @ConfigOption(name = "Only In Dungeons", desc = "Only warn in dungeons and Kuudra.")
        @ConfigEditorBoolean
        public boolean dungeonsOnly = false;
    }

    public static final class MayorDisplay {
        @Expose
        @ConfigOption(name = "HUD", desc = "The mayor and minister in a HUD you can move in /s2 gui, read from Hypixel's election API every 10 minutes.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Show Perks", desc = "List each perk under the mayor and minister in the HUD.")
        @ConfigEditorBoolean
        public boolean perks = true;

        @Expose
        @ConfigOption(name = "Show Election", desc = "While an election is open, who's leading and each candidate's share of the votes, in the HUD.")
        @ConfigEditorBoolean
        public boolean election = true;

        @ConfigOption(name = "Show In Chat", desc = "Everything (mayor, minister, perks, the election and the candidates' perks) in chat. Also /s2 mayor.")
        @ConfigEditorButton(buttonText = "Show")
        public Runnable show = com.epic60869.sky2m.features.misc.MayorDisplay::printToChat;
    }

    public static final class ScathaTracker {
        @Expose
        @ConfigOption(name = "HUD", desc = "In the Crystal Hollows, a HUD with your worm and Scatha spawns, worms since your last Scatha, your Scatha rate, Scatha pets and the 30s spawn cooldown. Counts are kept between sessions. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Spawn Alert", desc = "A title when a worm or Scatha spawns (Scatha in red).")
        @ConfigEditorBoolean
        public boolean spawnAlert = false;

        @Expose
        @ConfigOption(name = "Cooldown Ready Alert", desc = "A ding when the 30s worm spawn cooldown is over.")
        @ConfigEditorBoolean
        public boolean cooldownAlert = false;

        @ConfigOption(name = "Reset Counts", desc = "Set every count back to 0.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable reset = com.epic60869.sky2m.features.mining.ScathaTracker::resetCounts;
    }

    public static final class GemstoneTracker {
        @Expose
        @ConfigOption(name = "Enabled", desc = "While mining in the Crystal Hollows, Dwarven Mines and Mineshafts (and for 5 minutes after your last gemstone), a HUD with the gemstones you mined this session per type with their value, Flawed gemstones and coins per hour (paused when you stop mining), pristine procs and Glossy Gemstones (Mining Fiesta). Counts gemstones from the [Sacks] messages and your inventory. /s2 resetgemstones [session]. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Show as Flawed Equivalent", desc = "Show each gemstone type as Flawed gemstones (1 Fine = 80 Flawed, 80 Rough = 1 Flawed). Off: the count of each tier (Rough, Flawed, Fine...).")
        @ConfigEditorBoolean
        public boolean flawedEquivalent = true;

        @Expose
        @ConfigOption(name = "Show Glossy Gemstones", desc = "A line with the Glossy Gemstones from the Mining Fiesta and their value. They always count towards the total value.")
        @ConfigEditorBoolean
        public boolean showGlossy = true;

        @Expose
        @ConfigOption(name = "Show Pristine Procs", desc = "A line with this session's pristine procs and the Flawed gemstones they gave.")
        @ConfigEditorBoolean
        public boolean showPristine = true;

        @Expose
        @ConfigOption(name = "Show All-Time Totals", desc = "Also show every gemstone and Glossy Gemstone you've tracked, kept between sessions.")
        @ConfigEditorBoolean
        public boolean showAllTime = false;

        @ConfigOption(name = "Reset", desc = "Reset the session and the all-time totals. /s2 resetgemstones session resets only the session.")
        @ConfigEditorButton(buttonText = "Reset")
        public Runnable reset = com.epic60869.sky2m.features.mining.GemstoneTracker::resetAll;
    }

    /** SkyHanni's OrderedWaypointsConfig (LGPL-2.1), which credits Coleweight and SoopyV2. */
    public static final class OrderedWaypoints {
        @Expose
        @ConfigOption(name = "Enabled", desc = "SkyHanni's ordered waypoints: load a route (Coleweight or Skytils format) from your clipboard with /sho load, and the waypoints show one after another as you reach them. /sho load, unload, save, skip, skipto, unskip, add, delete, export, erase, setupmode. Also /s2 sho.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Current Colour", desc = "Colour of the waypoint you're going to.")
        @ConfigEditorColour
        public String currentColor = "0:153:85:255:85";

        @Expose
        @ConfigOption(name = "Previous Colour", desc = "Colour of the waypoint you came from.")
        @ConfigEditorColour
        public String previousColor = "0:153:85:85:255";

        @Expose
        @ConfigOption(name = "Next Colour", desc = "Colour of the waypoints after the current one.")
        @ConfigEditorColour
        public String nextColor = "0:153:255:255:85";

        @Expose
        @ConfigOption(name = "Next Waypoints", desc = "How many waypoints after the current one to show.")
        @ConfigEditorSlider(minValue = 1, maxValue = 5, minStep = 1)
        public int nextCount = 2;

        @Expose
        @ConfigOption(name = "Fill Block", desc = "Fill the waypoints instead of only outlining them.")
        @ConfigEditorBoolean
        public boolean fillBlock = false;

        @Expose
        @ConfigOption(name = "Outline Thickness", desc = "Thickness of the waypoint outline.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        public int outlineThickness = 2;

        @Expose
        @ConfigOption(name = "Waypoint Range", desc = "How close you have to be for it to go to the next waypoint.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 0.1f)
        public float range = 3f;

        @Expose
        @ConfigOption(name = "Trace Line", desc = "A line from your crosshair to the next waypoint.")
        @ConfigEditorBoolean
        public boolean traceLine = true;

        @Expose
        @ConfigOption(name = "Trace Line Colour", desc = "")
        @ConfigEditorColour
        public String traceLineColor = "0:255:85:255:85";

        @Expose
        @ConfigOption(name = "Show Distance", desc = "Show how far each waypoint is.")
        @ConfigEditorBoolean
        public boolean showDistance = true;

        @Expose
        @ConfigOption(name = "Show Number", desc = "Show each waypoint's number.")
        @ConfigEditorBoolean
        public boolean showName = true;

        @Expose
        @ConfigOption(name = "Show All Waypoints", desc = "Show the whole route at once.")
        @ConfigEditorBoolean
        public boolean showAll = false;

        @Expose
        @ConfigOption(name = "Setup Mode", desc = "For building routes: also show every waypoint within the range below, and a line of sight from the current waypoint to the next. Also /sho setupmode.")
        @ConfigEditorBoolean
        public boolean setupMode = false;

        @Expose
        @ConfigOption(name = "Setup Mode Range", desc = "How close waypoints have to be to show in setup mode.")
        @ConfigEditorSlider(minValue = 1, maxValue = 100, minStep = 1)
        public int setupModeRange = 16;

        @Expose
        @ConfigOption(name = "Sneaking During Route", desc = "Whether you sneak between waypoints (with an AOTV), for setup mode's line of sight.")
        @ConfigEditorBoolean
        public boolean sneaking = true;

        @Expose
        @ConfigOption(name = "Auto-Skip Forward", desc = "Skip ahead when you reach a waypoint further down the route.")
        @ConfigEditorBoolean
        public boolean autoSkipForward = false;

        @Expose
        @ConfigOption(name = "Auto Unload", desc = "Unload the route when you change islands (Mineshafts too).")
        @ConfigEditorBoolean
        public boolean autoUnload = false;

        @Expose
        @ConfigOption(name = "Auto-Load Shaft Routes", desc = "When you enter a Mineshaft, load the saved route named after its type (from the scoreboard, like JASP_1 or TOPA_C) if there is one.")
        @ConfigEditorBoolean
        public boolean autoLoadShaft = false;
    }

    public static final class ChatFilters {
        @Expose
        @ConfigOption(name = "Lobby Joins", desc = "Hide \"[MVP+] Name joined the lobby!\" and the other ranks' join lines.")
        @ConfigEditorBoolean
        public boolean lobbyJoins = false;

        @Expose
        @ConfigOption(name = "Ads", desc = "Hide all-chat messages from players that look like ads: the words below (lowballing, /visit, selling, guild recruiting, Discord invites...). Party, guild and private messages are never hidden.")
        @ConfigEditorBoolean
        public boolean ads = false;

        @Expose
        @ConfigOption(name = "Ad Words", desc = "What counts as an ad, separated by commas (not case sensitive).")
        @ConfigEditorText
        public String adWords = "lowball,/visit,visit me,visit my,selling,buying,cheap,discord.gg,dsc.gg,guild recruit,join my guild,lf guild members,carry service,carries,free coins,giveaway";

        @Expose
        @ConfigOption(name = "Watchdog & Announcements", desc = "Hide the Watchdog ban counts and Hypixel's announcement lines.")
        @ConfigEditorBoolean
        public boolean watchdog = false;

        @Expose
        @ConfigOption(name = "Warping", desc = "Hide \"Sending to server ...\", \"Warping...\", \"Request join for Hub #...\" and similar lines.")
        @ConfigEditorBoolean
        public boolean warping = false;

        @Expose
        @ConfigOption(name = "Mystery Boxes", desc = "Hide other players' Mystery Box finds.")
        @ConfigEditorBoolean
        public boolean mysteryBoxes = false;

        @Expose
        @ConfigOption(name = "Ability Spam", desc = "Hide \"This ability is on cooldown\", \"There are blocks in the way!\" and \"You do not have enough mana\".")
        @ConfigEditorBoolean
        public boolean abilitySpam = false;

        @Expose
        @ConfigOption(name = "Your Own Filters", desc = "Hide lines containing any of these, separated by semicolons (not case sensitive). Put one between slashes for a regular expression: /^Some \\w+ line$/")
        @ConfigEditorText
        public String custom = "";
    }

    public enum TimestampFormat {
        H24("13:45"), H24_SECONDS("13:45:12"), H12("1:45 PM"), H12_SECONDS("1:45:12 PM");

        private final String label;

        TimestampFormat(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class ChatTabs {
        @Expose
        @ConfigOption(name = "Chat Tabs", desc = "All, Party, Guild and Private tabs above the chat box while chat is open: a tab shows only those messages (also when chat is closed). Click All to see everything again.")
        @ConfigEditorBoolean
        public boolean tabs = false;

        @Expose
        @ConfigOption(name = "Message Search", desc = "A search tab next to them (or Ctrl+F with chat open): what you type in the chat box filters the chat instead of being sent. Click the tab again or close chat to stop searching.")
        @ConfigEditorBoolean
        public boolean search = false;

        @Expose
        @ConfigOption(name = "Timestamps", desc = "The time each message arrived, in grey, in front of it.")
        @ConfigEditorBoolean
        public boolean timestamps = false;

        @Expose
        @ConfigOption(name = "Timestamp Format", desc = "")
        @ConfigEditorDropdown
        public TimestampFormat timestampFormat = TimestampFormat.H24;
    }

    public static final class SoundSetting {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Play this sound.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Sound", desc = "A sound id, like minecraft:entity.experience_orb.pickup or minecraft:block.note_block.pling. Sounds from your resource packs work too (namespace:name).")
        @ConfigEditorText
        public String sound;

        @Expose
        @ConfigOption(name = "Volume", desc = "")
        @ConfigEditorSlider(minValue = 0, maxValue = 2, minStep = 0.05f)
        public float volume = 1f;

        @Expose
        @ConfigOption(name = "Pitch", desc = "")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 2f, minStep = 0.05f)
        public float pitch = 1f;

        @ConfigOption(name = "Test", desc = "Play it now.")
        @ConfigEditorButton(buttonText = "Play")
        public Runnable test = () -> com.epic60869.sky2m.features.misc.CustomSounds.play(this);

        public SoundSetting() {
            this("minecraft:block.note_block.pling", 1f);
        }

        public SoundSetting(String sound, float pitch) {
            this.sound = sound;
            this.pitch = pitch;
        }
    }

    public static final class CustomSounds {
        @Expose
        @Accordion
        @ConfigOption(name = "Leap Sound", desc = "Played instead of the teleport sound when you Spirit Leap or Infinileap to a teammate.")
        public SoundSetting leap = new SoundSetting("minecraft:entity.player.levelup", 2f);

        @Expose
        @Accordion
        @ConfigOption(name = "Teleport Sound", desc = "Played instead of the teleport sound of your Aspect of the End or Void, etherwarp and other teleports.")
        public SoundSetting teleport = new SoundSetting("minecraft:entity.experience_orb.pickup", 1.2f);

        @Expose
        @Accordion
        @ConfigOption(name = "Death Sound", desc = "Played when you die.")
        public SoundSetting death = new SoundSetting("minecraft:entity.wither.death", 1f);

        @Expose
        @Accordion
        @ConfigOption(name = "Kill Sound", desc = "Played when a mob you hit in melee in the last few seconds dies.")
        public SoundSetting kill = new SoundSetting("minecraft:entity.arrow.hit_player", 1f);
    }

    public static final class CarryTracker {
        @Expose
        @ConfigOption(name = "HUD", desc = "Your carries in a HUD you can move in /s2 gui: who, how many are done, and what they owe. /s2 carry add <player> <amount> [type] [price each], /s2 carry paid <player> <coins>, /s2 carry count <player>, /s2 carry remove <player>, /s2 carry list, /s2 carry clear.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Count Dungeon Runs", desc = "Count a carry when a dungeon run ends with that player in it (if the carry's type is a floor like f7 or m5, only runs of that floor).")
        @ConfigEditorBoolean
        public boolean countDungeons = true;

        @Expose
        @ConfigOption(name = "Count Slayer Bosses", desc = "Count a carry when a slayer boss spawned by that player dies near you.")
        @ConfigEditorBoolean
        public boolean countSlayers = true;

        @Expose
        @ConfigOption(name = "Done Message", desc = "When a carry is finished, a line in chat with what they still owe.")
        @ConfigEditorBoolean
        public boolean doneMessage = true;

        @Expose
        @ConfigOption(name = "Party Progress", desc = "After each counted carry, say their progress in party chat (\"Name: 3/5 carries done\").")
        @ConfigEditorBoolean
        public boolean partyProgress = false;
    }

    public static final class NetworthHistory {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Save your networth (worked out like the profile viewer's, museum included) while you play SkyBlock, at most once every few hours as set below, and draw it as a graph with /s2 networth.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Hours Between", desc = "How long to wait between snapshots.")
        @ConfigEditorSlider(minValue = 1, maxValue = 24, minStep = 1)
        public int hours = 6;

        @ConfigOption(name = "Open Graph", desc = "Your networth over time. Also /s2 networth.")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable open = com.epic60869.sky2m.features.misc.NetworthHistory::open;

        @ConfigOption(name = "Snapshot Now", desc = "Save your networth now.")
        @ConfigEditorButton(buttonText = "Save")
        public Runnable now = () -> com.epic60869.sky2m.features.misc.NetworthHistory.snapshot(true);
    }

    public static final class SignAutofill {
        @Expose
        @ConfigOption(name = "Bazaar Prices", desc = "Custom Price signs in the Bazaar start filled in: a buy order at the top buy order plus the Bazaar Step (outbid), a sell offer at the lowest sell offer minus it (undercut). Press Done to use it, or type over it.")
        @ConfigEditorBoolean
        public boolean bazaarPrices = true;

        @Expose
        @ConfigOption(name = "Bazaar Step", desc = "How much to outbid the top buy order or undercut the lowest sell offer by.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 10, minStep = 0.1f)
        public float bazaarStep = 0.1f;

        @Expose
        @ConfigOption(name = "Bazaar Last Amount", desc = "Custom Amount signs in the Bazaar start with the last amount you entered on one.")
        @ConfigEditorBoolean
        public boolean bazaarAmount = true;

        @Expose
        @ConfigOption(name = "AH BIN Price", desc = "The Set Item Price sign in Create BIN Auction starts with the item's lowest BIN (times the stack size) minus the AH Undercut.")
        @ConfigEditorBoolean
        public boolean ahBinPrice = true;

        @Expose
        @ConfigOption(name = "AH Undercut", desc = "How far under the lowest BIN to suggest.")
        @ConfigEditorDropdown
        public Undercut ahUndercut = Undercut.ONE_COIN;

        public enum Undercut {
            ONE_COIN("1 coin"), ONE_PERCENT("1%"), FIVE_PERCENT("5%");

            private final String label;

            Undercut(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }
    }

    public static final class AuctionGuard {
        @Expose
        @ConfigOption(name = "Too Cheap Warning", desc = "When you create a BIN auction for much less than the item's lowest BIN, the Create/Confirm click is stopped with a warning; click again within 3 seconds to list it anyway.")
        @ConfigEditorBoolean
        public boolean underprice = false;

        @Expose
        @ConfigOption(name = "Too Cheap Below", desc = "Warn when the price is below this percent of the lowest BIN.")
        @ConfigEditorSlider(minValue = 10, maxValue = 95, minStep = 5)
        public int underpricePercent = 60;

        @Expose
        @ConfigOption(name = "Overpay Warning", desc = "When you buy a BIN auction for much more than the item's lowest BIN, the Buy/Confirm click is stopped with a warning; click again within 3 seconds to buy it anyway.")
        @ConfigEditorBoolean
        public boolean overpay = false;

        @Expose
        @ConfigOption(name = "Overpay Above", desc = "Warn when the price is more than this percent above the lowest BIN.")
        @ConfigEditorSlider(minValue = 10, maxValue = 500, minStep = 10)
        public int overpayPercent = 50;
    }

    public static final class ItemQuality {
        @Expose
        @ConfigOption(name = "Quality On Slot", desc = "Like SkyblockAddons: the item quality (base stat boost, 0 to 50%) of dungeon items in the corner of their slot, coloured by how good it is.")
        @ConfigEditorBoolean
        public boolean slot = false;

        @Expose
        @ConfigOption(name = "Quality As Stack Size", desc = "SkyHanni's and SkyOcean's way: draw the quality like a stack count, in the bottom right corner of the slot at normal size, instead of small in the top left. Needs Quality On Slot.")
        @ConfigEditorBoolean
        public boolean stackSize = false;

        @Expose
        @ConfigOption(name = "Quality In Tooltip", desc = "The item quality and the floor it dropped on in the tooltip of dungeon items.")
        @ConfigEditorBoolean
        public boolean tooltip = false;

        @Expose
        @ConfigOption(name = "Floor On Slot", desc = "Also the floor the item dropped on (F7, M5...) in the other corner of its slot.")
        @ConfigEditorBoolean
        public boolean floor = false;
    }

    public static final class Achievements {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Sky2M's own achievements, earned as you play with Sky2M: dungeon runs, S+ runs, deaths, slayer bosses, Scathas, carries, dragons, kills, days played and more. See them with /s2 achievements, next to the Diana achievements.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Unlock Popup", desc = "A title and a sound when you unlock one.")
        @ConfigEditorBoolean
        public boolean popup = true;

        @Expose
        @ConfigOption(name = "Unlock Message", desc = "A line in chat when you unlock one.")
        @ConfigEditorBoolean
        public boolean chat = true;

        @ConfigOption(name = "Open", desc = "Your achievements and stats, with a tab for each activity. Also /s2 achievements.")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable open = com.epic60869.sky2m.features.misc.Sky2MAchievements::open;
    }
    /** Odin's Waypoints settings (BSD-3-Clause). */
    public static final class AreaWaypoints {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Odin's area waypoints: waypoints that only show in one dungeon boss phase, Kuudra tier or island, drawn as a box or a circle with a label. Proximity ones run a command when you walk in, Click ones when you click a block inside. /s2w (or /s2 wp) opens the manager.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Text Scale", desc = "Size of the waypoints' labels.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 4f, minStep = 0.1f)
        public float textScale = 1f;

        @Expose
        @ConfigOption(name = "Show Through Walls", desc = "Draw the waypoints and labels through walls.")
        @ConfigEditorBoolean
        public boolean throughWalls = false;

        @ConfigOption(name = "Open Waypoint Manager", desc = "Add, edit, import and export waypoints. Also /s2w.")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable open = com.epic60869.sky2m.features.misc.waypoints.AreaWaypoints::open;
    }
    /** Fairy Soul finder (SkyHanni's Fast Fairy Soul Finder / NEU-Souls Pathfinder style, /s2 souls). */
    public static final class FairySouls {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Draw every Fairy Soul you haven't collected on this island as a box with a label, with the nearest ones you still need highlighted, using NEU's soul coordinates. One saved for all profiles. Commands: /s2 souls.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Nearby Souls", desc = "How many of the nearest uncollected Fairy Souls to draw.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        public int nearbySouls = 4;

        @Expose
        @ConfigOption(name = "Faded Colour", desc = "Colour of the boxes that aren't the nearest one.")
        @ConfigEditorColour
        public String faded = "0:255:119:41:145";

        @Expose
        @ConfigOption(name = "Show Through Walls", desc = "Draw the boxes and labels through walls.")
        @ConfigEditorBoolean
        public boolean throughWalls = true;

        @Expose
        @ConfigOption(name = "Labels", desc = "Write \"Fairy Soul\" above each box.")
        @ConfigEditorBoolean
        public boolean labels = true;

        @Expose
        @ConfigOption(name = "Distances", desc = "Add how far away each nearest Fairy Soul is, in metres.")
        @ConfigEditorBoolean
        public boolean distances = true;

        @Expose
        @ConfigOption(name = "HUD", desc = "A HUD with how many Fairy Souls you've found on this island, out of all of them (\"Fairy Souls 45/81 — Hub\"). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Progress on Island Join", desc = "Say the island's Fairy Souls count in chat each time you arrive.")
        @ConfigEditorBoolean
        public boolean progress = true;
    }
    /** A shopping list of what to get and how many (/s2 shopping), ticked off by what you pick up. */
    public static final class ShoppingList {
        @Expose
        @ConfigOption(name = "HUD", desc = "Show what's still on your shopping list. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Auto Pickups", desc = "Count what you pick up towards the list: an entry whose name matches an item that lands in your inventory goes up on its own, and says in chat when it's done.")
        @ConfigEditorBoolean
        public boolean autoPickups = true;
    }
    public static final class TerminalProgress {
        @Expose
        @ConfigOption(name = "HUD", desc = "During F7/M7 P3, a HUD with the section you're on, how many of its terminals, levers and devices are done, and who did each one (\"Name 3/7\"). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = true;

        @Expose
        @ConfigOption(name = "Title", desc = "A small title each time someone finishes one: \"Name 3/7\" (green when the section is done).")
        @ConfigEditorBoolean
        public boolean title = false;

        @Expose
        @ConfigOption(name = "Replace Hypixel's Titles", desc = "With Title on, hide Hypixel's own \"Name activated a terminal! (3/7)\" subtitles so only Sky2M's shows (Odin's Terminal Titles).")
        @ConfigEditorBoolean
        public boolean replaceTitles = false;

        @Expose
        @ConfigOption(name = "Phase Done", desc = "With Title on, show \"Phase Done!\" when a section's last one is done.")
        @ConfigEditorBoolean
        public boolean phaseDone = false;

        @Expose
        @ConfigOption(name = "Gate Titles", desc = "With Title on, \"GATE!\" when the gate will open in 5 seconds and \"Gate Destroyed!\" when it's destroyed, instead of Hypixel's subtitles (NoammAddons' F7 Titles).")
        @ConfigEditorBoolean
        public boolean gateTitles = false;

        @Expose
        @ConfigOption(name = "Show Type", desc = "Say whether it was a terminal, lever or device after the name.")
        @ConfigEditorBoolean
        public boolean showType = true;
    }
    /** SkyOcean's CropFeverEffectsConfig (MIT). */
    public static final class CropFever {
        @Expose
        @ConfigOption(name = "Enabled", desc = "When you catch the CROP FEVER in the Garden (\"WOAH! You caught a case of the CROP FEVER...\"), play the effects below until it's cured. Try them with /s2 cropfever start and /s2 cropfever stop.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Starting Sound", desc = "A fanfare when the fever starts.")
        @ConfigEditorBoolean
        public boolean startingSound = true;

        @Expose
        @ConfigOption(name = "Jackpot Sound", desc = "A rising bell jackpot when the fever starts.")
        @ConfigEditorBoolean
        public boolean rngSound = true;

        @Expose
        @ConfigOption(name = "Background Music", desc = "A note block tune, looped while the fever lasts.")
        @ConfigEditorBoolean
        public boolean backgroundMusic = true;

        @Expose
        @ConfigOption(name = "Hue Shift", desc = "The screen cycles through every colour while the fever lasts (SkyOcean's shader).")
        @ConfigEditorBoolean
        public boolean hueShift = true;

        @Expose
        @ConfigOption(name = "Hue Shift Speed", desc = "How fast the colours cycle.")
        @ConfigEditorDropdown
        public HueSpeed hueSpeed = HueSpeed.NORMAL;

        @Expose
        @ConfigOption(name = "Coin Rain", desc = "Coins rain down the screen when the fever starts.")
        @ConfigEditorBoolean
        public boolean coinRain = true;

        @Expose
        @ConfigOption(name = "Coin Rain Seconds", desc = "How long coins keep falling.")
        @ConfigEditorSlider(minValue = 1, maxValue = 60, minStep = 1)
        public int coinRainSeconds = 3;

        @Expose
        @ConfigOption(name = "Coins Per Tick", desc = "How many coins fall each tick. Auto: more on bigger screens.")
        @ConfigEditorDropdown
        public CoinMultiplier coinMultiplier = CoinMultiplier.AUTO;

        public enum HueSpeed {
            SLOWER("Slower", "slower"), SLOW("Slow", "slow"), NORMAL("Normal", "normal"), FAST("Fast", "fast"), FASTER("Faster", "faster"), INSANE("Insane", "insane");

            private final String label;
            public final String id;

            HueSpeed(String label, String id) {
                this.label = label;
                this.id = id;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum CoinMultiplier {
            AUTO(0), ONE(1), TWO(2), THREE(3), FOUR(4), FIVE(5), TEN(10), FIFTEEN(15), TWENTY(20);

            public final int amount;

            CoinMultiplier(int amount) {
                this.amount = amount;
            }

            @Override
            public String toString() {
                return amount == 0 ? "Auto" : String.valueOf(amount);
            }
        }
    }
}
