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
public final class FeatureConfigs {
    public static final class Combat {

        @ConfigOption(name = "HUDs & Counters", desc = "Arrow counter, Zealot tracker and Legion display.")
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
        @ConfigOption(name = "Display Type", desc = "What the overlay shows for each family, and how it's sorted.")
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
            GLOBAL_NEXT("Global to next tier"),
            LOWEST_TOTAL("Lowest total kills"),
            HIGHEST_TOTAL("Highest total kills"),
            LOWEST_MAX("Lowest kills needed to max"),
            HIGHEST_MAX("Highest kills needed to max"),
            LOWEST_NEXT("Lowest kills needed to next tier"),
            HIGHEST_NEXT("Highest kills needed to next tier");

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

    /** Mayors: a sub-category in the sidebar per mayor (shown under Mayors when it's open). */
    public static final class Mayors {
        @Expose
        @Category(name = "Diana", desc = "Share rare Diana mobs with your party and see when you've done enough damage to lootshare them, like Skysoft.")
        public Diana diana = new Diana();
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
        @Category(name = "F7/M7", desc = "Floor 7 and Master Mode 7: dragons and relics, terminal and device solvers, and the 3x3 platform highlight.")
        public Floor7 f7 = new Floor7();

        @Expose
        @Category(name = "Dungeon Map", desc = "Dungeon map HUD.")
        public DungeonMap map = new DungeonMap();

        @Expose
        @Category(name = "Puzzle Solvers", desc = "Solutions for dungeon puzzles.")
        public Puzzles puzzles = new Puzzles();

        @Expose
        @Category(name = "Secrets and Routes", desc = "Secret waypoints and your recorded routes.")
        public Secrets secrets = new Secrets();

        @Expose
        @Category(name = "Mobs", desc = "Dungeon mob highlighting.")
        public DungeonMobs mobs = new DungeonMobs();

        @Expose
        @Category(name = "Timers and Alerts", desc = "Splits, tick timers, mask timers and debuff alerts.")
        public Timers timers = new Timers();

        @Expose
        @Category(name = "Score", desc = "270/300 score alerts and the score display.")
        public Score score = new Score();

        @Expose
        @Category(name = "Leap Menu", desc = "Odin-style Spirit Leap menu with a box per teammate, coloured by class.")
        public LeapMenu leapMenu = new LeapMenu();

        @Expose
        @Category(name = "Positional Messages", desc = "Party messages sent when you reach a spot (/s2 posmsg), plus built-in waypoints like Py Stand Here.")
        public PositionalMessages positionalMessages = new PositionalMessages();

        @Expose
        @Category(name = "Blood Camp", desc = "Watcher move prediction and blood mob kill timers.")
        public BloodCamp bloodCamp = new BloodCamp();

        @Expose
        @Category(name = "Case Opening", desc = "Open Obsidian and Bedrock reward chests like a CS2 case (SkyOcean's Dungeon Gambling).")
        public CaseOpening caseOpeningMenu = new CaseOpening();

        @Expose
        @Category(name = "Chest Profit", desc = "What dungeon reward chests are worth after their cost, at the end of a run and at Croesus.")
        public ChestProfit chestProfit = new ChestProfit();
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
    }

    public static final class Floor7 {
        @Expose
        @Accordion
        @ConfigOption(name = "M7 Dragons and Relics", desc = "Master Mode floor 7 dragon phase (P5): spawn alerts with split priority, timers, kill areas and relic spots, like NoFrills' Wither Dragons and Relic Highlight.")
        public WitherDragons witherDragons = new WitherDragons();

        @Expose
        @Accordion
        @ConfigOption(name = "Terminals and Devices", desc = "Floor 7 terminal and device solvers.")
        public Terminals terminals = new Terminals();

        @Expose
        @Accordion
        @ConfigOption(name = "Platform Highlight (3x3)", desc = "One big box over the floor 7 3x3 platform (53-55, 63, 113-115), from when Goldor starts, like NoFrills.")
        public PlatformHighlight platformHighlight = new PlatformHighlight();
    }

    private FeatureConfigs() {}

    public static final class CocoonAlert {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show an on-screen alert when you cocoon a slayer boss, slayer miniboss, elusive mob or important boss.")
        @ConfigEditorBoolean
        public boolean enabled = false;
    }

    public static final class RareDrops {
        @Expose
        @ConfigOption(name = "Copy Rare Drops", desc = "Copy rare drop messages to your clipboard (RARE, VERY RARE, CRAZY RARE, INSANE, PRAY TO RNGESUS, PET and RNG METER drops, rare crops, rare rewards and outstanding catches). What was copied shows in chat, using Copy Chat's preview settings.")
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
        @ConfigOption(name = "Copy Garden Drops", desc = "Copy only Garden drops to your clipboard: RARE CROP drops and anything else that drops in the Garden, like pest drops. Not needed with Copy Rare Drops on.")
        @ConfigEditorBoolean
        public boolean copyGarden = false;

        @Expose
        @ConfigOption(name = "Big Drop Animation", desc = "Play an animation when a rare drop is worth more than the threshold.")
        @ConfigEditorBoolean
        public boolean animation = false;

        @Expose
        @ConfigOption(name = "Animation Threshold (millions)", desc = "Minimum drop value, in millions of coins, for the animation.")
        @ConfigEditorSlider(minValue = 1, maxValue = 500, minStep = 1)
        public float thresholdMillions = 10;

        @Expose
        @ConfigOption(name = "RNG Drop Totem Animation", desc = "Skyblocker's special effect: big RNG drops (slayer, Diana and fishing RNG items, and any PRAY TO RNGESUS or RNG METER drop) pop up like a Totem of Undying, with particles and a sound.")
        @ConfigEditorBoolean
        public boolean totemAnimation = false;
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
        @Category(name = "Sea Creatures", desc = "Alerts, party messages and highlights for sea creatures you and your party catch.")
        public FishingSeaCreatures seaCreatures = new FishingSeaCreatures();

        @Expose
        @Category(name = "Rare Drops", desc = "Alerts and party messages for rare fishing drops, with their price.")
        public FishingRareDrops rareDrops = new FishingRareDrops();

        @Expose
        @Category(name = "Catch Messages", desc = "Shorter sea creature catch messages with rarity colours.")
        public FishingCatchMessages catchMessages = new FishingCatchMessages();

        @Expose
        @Category(name = "Alerts", desc = "Trophy discoveries, deployables, maxed pets, hotspots, wormholes, the Fishing Festival and lootshare.")
        public FishingAlerts alerts = new FishingAlerts();

        @Expose
        @Category(name = "Hook & Bobber", desc = "SkyHanni's Fishing Bobber Timer and Fishing Hook Display (Hypixel's reel-in countdown, bigger and on your screen).")
        public com.epic60869.sky2m.features.fishing.FishingHookTimer.Config hookTimer = new com.epic60869.sky2m.features.fishing.FishingHookTimer.Config();
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
                MATCH_PACK("Match Resource Pack", "minecraft:hud/experience_bar"),
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
    }

    public static final class Enchanting {
        @Expose
        @ConfigOption(name = "Chronomatron Solver", desc = "Highlight the Chronomatron pattern.")
        @ConfigEditorBoolean
        public boolean chronomatron = false;

        @Expose
        @ConfigOption(name = "Superpairs Solver", desc = "Show revealed Superpairs cards.")
        @ConfigEditorBoolean
        public boolean superpairs = false;

        @Expose
        @ConfigOption(name = "Ultrasequencer Solver", desc = "Highlight the Ultrasequencer order.")
        @ConfigEditorBoolean
        public boolean ultrasequencer = false;

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

    public static final class Runecrafting {
        @Expose
        @ConfigOption(name = "Valuable Rune Alert", desc = "Alert when you get a rune from the list below.")
        @ConfigEditorBoolean
        public boolean valuableRuneAlert = false;

        @Expose
        @ConfigOption(name = "Runes", desc = "Comma-separated rune names to alert for.")
        @ConfigEditorText
        public String runes = "Music, Enchant, Grand Searing, Rainbow, Spellbound, Grand Freezing, Primal Fear, Golden Carpet";
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
        @Expose @ConfigOption(name = "Odin Device Solvers", desc = "Odin's Simon Says, Arrow Align and Sharp Shooter (i4) solvers. Off: Skyblocker's. Set this to false if you want the NoammAddons path.") @ConfigEditorBoolean public boolean odinDevices = false;
        @Expose @ConfigOption(name = "SS Block Wrong Clicks", desc = "Simon Says: ignore clicks on any button but the next one. Hold shift to click anyway.") @ConfigEditorBoolean public boolean ssBlockWrong = true;
        @Expose @ConfigOption(name = "SS Announce Progress", desc = "Send \"SS n/5\" to party chat when you click the last button of a round.") @ConfigEditorBoolean public boolean ssAnnounce = false;
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

    public static final class Timers {

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

        @ConfigOption(name = "Tick Timers", desc = "Tick timers, the Storm PY timer and the max debuff alert.")
        @ConfigEditorAccordion(id = 1702)
        public boolean tickTimersGroup = false;

        @Expose
        @ConfigOption(name = "Tick Timers", desc = "HUD for Storm's pillars (20 ticks) and Goldor's death tick (every 60 ticks, from Storm's death until the core opens).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 1702)
        public boolean tickTimers = false;

        @Expose
        @ConfigOption(name = "Storm PY Timer", desc = "Odin's Storm PY HUD: when Storm calls his lightning (\"ENERGY HEED MY CALL!\" / \"THUNDER LET ME BE YOUR CATALYST!\"), counts down 95 ticks to when to crush him under the purple pillar. Move it in /s2 gui.")
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
        @ConfigOption(name = "Built-in Waypoints", desc = "Show Sky2M's hard-coded waypoints on F7 and M7: Py Stand Here (95, 165.5, 94.4), Mage Stop (34, 169, 65), Arch Stand Here (102-104, 168, 49), Healer Stand Here After Lighting (58, 169, 66), Tank Stand Here (109, 170, 93) and SS: the block at 109, 120, 93 is highlighted and standing at 108, 120, 93 sends \"At SS\" to party chat once each time you step on it.")
        @ConfigEditorBoolean
        public boolean builtInWaypoints = true;

        @Expose
        @ConfigOption(name = "Your Class", desc = "Which class's built-in waypoints to show. Auto reads it from the tab list; pick one if it isn't detected.")
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
            CROSS("Cross"), T_SHAPE("T-Shape"), X("X"), DOT("Dot"), CIRCLE("Circle"), CROSS_CIRCLE("Cross + Circle"), IMAGE("Image");

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

    /** Killer560's Inventory HUD. */
    public static final class InventoryHud {
        public enum Show {
            ALWAYS("Always"), HOLD_KEY("While Holding the Key"), TOGGLE_KEY("Toggled by the Key");

            private final String label;

            Show(String label) {
                this.label = label;
            }

            @Override
            public String toString() {
                return label;
            }
        }

        public enum Background {
            NONE("None"), PANEL("Panel"), SLOTS("Panel and Slots");

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
        @ConfigOption(name = "Background", desc = "What's drawn behind the items.")
        @ConfigEditorDropdown
        public Background background = Background.SLOTS;

        @Expose
        @ConfigOption(name = "Opacity", desc = "How see-through the whole HUD is.")
        @ConfigEditorSlider(minValue = 0.1f, maxValue = 1f, minStep = 0.05f)
        public float opacity = 0.8f;

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
        @ConfigOption(name = "Cancel If Someone Leaves", desc = "Don't requeue when a party member leaves or disconnects before it happens.")
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
            TOP("Newest at the Top"), BOTTOM("Newest at the Bottom");

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
        @ConfigOption(name = "Kick Button", desc = "Adds a [KICK] button under the stats that kicks them from the party.")
        @ConfigEditorBoolean
        public boolean kickButton = true;

        @Expose
        @ConfigOption(name = "Auto Kick", desc = "While you lead, kick players who don't meet the requirements below (0 turns a requirement off).")
        @ConfigEditorBoolean
        public boolean autoKick = false;

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

        @Expose
        @ConfigOption(name = "Profile API", desc = "Where profiles are loaded from (a Hypixel API proxy; Sky2M has no API key). Default: Odin's, https://api.odtheking.com/hypixel/")
        @ConfigEditorText
        public String apiUrl = "https://api.odtheking.com/hypixel/";
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
        @Category(name = "Farming", desc = "Track Farming profit in the Garden.")
        public ProfitTrackerSettings farming = new ProfitTrackerSettings(ProfitSummaryLine.farmingDefaults());

        @Expose
        @Category(name = "Fishing", desc = "Track Fishing profit while you fish.")
        public ProfitTrackerSettings fishing = new ProfitTrackerSettings(ProfitSummaryLine.resourceDefaults());

        @Expose
        @Category(name = "Foraging", desc = "Track Foraging profit in the Park, the Hub's forest, the Moonglade Marsh and Torrhus Canyon.")
        public ProfitTrackerSettings foraging = new ProfitTrackerSettings(ProfitSummaryLine.resourceDefaults());

        @Expose
        @Category(name = "Mining", desc = "Track Mining profit in the mining islands.")
        public ProfitTrackerSettings mining = new ProfitTrackerSettings(ProfitSummaryLine.resourceDefaults());

        @Expose
        @Category(name = "Mythological Ritual", desc = "Track Mythological Ritual (Diana) profit in the Hub with a spade in your hotbar.")
        public ProfitTrackerSettings mythologicalRitual = new ProfitTrackerSettings(ProfitSummaryLine.mythologicalDefaults());

        @Expose
        @Category(name = "Zombie Slayer", desc = "Track Zombie Slayer profit.")
        public ProfitTrackerSettings zombie = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Category(name = "Spider Slayer", desc = "Track Spider Slayer profit.")
        public ProfitTrackerSettings spider = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Category(name = "Wolf Slayer", desc = "Track Wolf Slayer profit.")
        public ProfitTrackerSettings wolf = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Category(name = "Enderman Slayer", desc = "Track Enderman Slayer profit.")
        public ProfitTrackerSettings enderman = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Category(name = "Blaze Slayer", desc = "Track Blaze Slayer profit.")
        public ProfitTrackerSettings blaze = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());

        @Expose
        @Category(name = "Vampire Slayer", desc = "Track Vampire Slayer profit.")
        public ProfitTrackerSettings vampire = new ProfitTrackerSettings(ProfitSummaryLine.slayerDefaults());
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
        PROFIT_PER_HOUR("Profit/h"), ACTIONS("Actions"), UPTIME("Uptime");

        private final String label;

        ProfitSummaryLine(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        static List<ProfitSummaryLine> farmingDefaults() {
            return List.of(values());
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
}
