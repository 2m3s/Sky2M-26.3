package com.epic60869.sky2m.features.misc.chat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.regex.Pattern;
import com.epic60869.sky2m.features.misc.social.Sky2MAchievements;

/**
 * Chat > Spam Filters: SkyHanni's chat filter categories (lobby, warping, welcome, Guild/Event EXP, Kill Combo, profile
 * join, empty lines, Bazaar/AH, slayer, drops, warnings, winter, powder mining, fire sales, events and more). Each one
 * can show the message, hide it or move it to the {@link SideChat}. The patterns are SkyHanni's
 * (features/chat/ChatFilter.kt, PowderMiningChatFilter.kt and GiftProfitTracker.kt), matched against the text without
 * colour codes. SkyHanni is licensed under the LGPL-2.1: https://github.com/hannibal002/SkyHanni
 * <p>
 * Only the display is filtered: Sky2M's own features read every message from the chat packet before this runs (see
 * {@link com.epic60869.sky2m.features.core.Sky2MChat}), so trackers like the Pristine record and the Gemstone tracker
 * still count moved and hidden messages. Filters already in {@link ChatFilters} (player lobby joins, Mystery Boxes,
 * Watchdog, the basic warping lines, ability spam, ads) aren't repeated here.
 */
public final class SpamFilters {
    /** What happens to a message a filter matches. */
    public enum Mode {
        SHOW("Show"), HIDE("Hide"), SIDE_CHAT("Side Chat");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ------------------------------------------------------------------------------------------------ patterns

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    private static final List<Pattern> LOBBY = List.of(
        p("^\\s*You can now create your own Hypixel SMP server!\\s*$"),
        p("(?s).*For the best experience, click the text below to enable Snow\\s.*Particles in this lobby!.*"),
        p("^✦ You earned \\d+ Mystery Dust!$"),
        p("^✦ You earned \\d+ Pet Consumables items!$"),
        p("^➤ You have reached your Hype limit! Add Hype to Prototype Lobby minigames by right-clicking with the Hype Diamond!$"),
        p(".*Welcome to the Prototype Lobby.*"),
        p(".*HYPIXEL is hosting a (?:BED WARS DOUBLES|TNT RUN) tournament!.*"),
        p(".*HYPIXEL BED WARS DOUBLES tournament is live!.*"),
        p(".*You are still radiating with Generosity!.*"));
    private static final List<Pattern> VISITING = List.of(
        p("^Warping using transfer token\\.\\.\\.$"),
        p("^Finding player\\.\\.\\.$"),
        p("^Sending a visit request\\.\\.\\.$"),
        p("^Warped to .+!$"));
    private static final List<Pattern> WELCOME = List.of(p("^Welcome to Hypixel SkyBlock!$"));
    private static final List<Pattern> GUILD_EXP = List.of(
        p("^You earned [\\d,]+ (?:GEXP|Event EXP) (?:\\+ [\\d,]+ Event EXP )?from playing SkyBlock!$"));
    private static final List<Pattern> KILL_COMBO = List.of(
        p("^\\+(.*) Kill Combo(.*)$"),
        p("^Your Kill Combo has expired! You reached a (.*) Kill Combo!$"));
    private static final List<Pattern> PROFILE_JOIN = List.of(
        p("^You are playing on profile: .*"),
        p("^Profile ID: .*"));
    private static final Pattern SEPARATOR = p("^[▬\\-=⏤━─]{20,}$");
    private static final List<Pattern> BZ_AH_PROGRESS = List.of(
        p("^Putting (?:item|coins) in escrow\\.\\.\\.$"),
        p("^(?:Setting up the auction|Processing purchase|Processing bid|Claiming BIN auction)\\.\\.\\.$"),
        p("^\\[Bazaar] (?:Submitting sell offer|Submitting buy order|Executing instant sell|Executing instant buy|Cancelling order|Claiming order|Putting goods in escrow)\\.\\.\\.$"),
        p("^(?:Depositing|Withdrawing) coins\\.\\.\\.$"));
    private static final List<Pattern> MONEY = List.of(
        p("^Buy Order Setup! (.*)x (.*) for (.*) coins.$"),
        p("^Sell Offer Setup! (.*)x (.*) for (.*) coins.$"),
        p("^Cancelled! Refunded (.*) coins from cancelling buy order!$"),
        p("^Cancelled! Refunded (.*)x (.*) from cancelling sell offer!$"),
        p("^Visit the Auction House to collect your item!$"));
    private static final List<Pattern> SLAYER = List.of(
        p("^SLAYER QUEST STARTED!$"),
        p("^» Slay (.*) Combat XP worth of (.*).$"),
        p("^SLAYER QUEST COMPLETE!$"),
        p("^(.*)Slayer LVL 9 - LVL MAXED OUT!$"),
        p("^» Talk to Maddox to claim your (.*) Slayer XP!$"),
        p("^NICE! SLAYER BOSS SLAIN!$"),
        p("^You received kill credit for assisting on a slayer miniboss!$"),
        p("^✆ RING\\.\\.\\. .*"));
    private static final List<Pattern> SLAYER_DROPS = List.of(
        p("^RARE DROP! \\((?:[\\d,]+x )?(?:Revenant Viscera|Foul Flesh|Toxic Arrow Poison|Twilight Arrow Poison|Bundle of Magma Arrows)\\) .*"),
        p("^RARE DROP! (?:Golden Powder|Arachne's Keeper Fragment|Travel Scroll to Spider's Den Top of Nest) .*"),
        p("^VERY RARE DROP! +\\((?:[\\d,]+x )?(?:.*Pestilence Rune I|Revenant Catalyst|Undead Catalyst|◆ Bite Rune I|Bane of Arthropods VI|Mana Steal I|Sinful Dice|Null Atom|Transmission Tuner|◆ Endersnake Rune I|◆ End Rune I|Hazmat Enderman|Wisp's Ice-Flavored Water I Splash Potion|(?:Glowstone|Blaze Rod|Magma Cream|Nether Wart) Distillate)\\) .*"),
        p("^CRAZY RARE DROP! +\\(Pocket Espresso Machine\\) .*"));
    private static final List<Pattern> USELESS_DROPS = List.of(
        p("^RARE DROP! (?:Enchanted Ender Pearl|Carrot|Potato|Machine Gun Bow|Earth Shard|Zombie Lord Chestplate)(?: .*)?$"),
        p("^RARE DROP! (?:Enchanted End Stone|Crystal Fragment)$"));
    private static final List<Pattern> USELESS_NOTIFICATIONS = List.of(
        p("^You tipped \\d+ players? in \\d+(?: different)? games?!$"),
        p("^Your previous Plasmaflux Power Orb was removed!$"),
        p("^You used your Mining Speed Boost Pickaxe Ability!$"),
        p("^Your Mining Speed Boost has expired!$"),
        p("^Mining Speed Boost is now available!$"),
        p("^You have just received 0 coins as interest in your (?:personal|co-op) bank account!$"),
        p("^Since you've been away you earned 0 coins as interest in your personal bank account!$"));
    private static final List<Pattern> USELESS_WARNINGS = List.of(
        p("^You are sending commands too fast! Please slow down\\.$"),
        p("^You can't use this while in combat!$"),
        p("^You can not modify your equipped armor set!$"),
        p("^Please wait a few seconds between refreshing!$"),
        p("^This item is not salvageable!$"),
        p("^Place a Dungeon weapon or armor piece above the anvil to salvage it!$"),
        p("^Whoa! Slow down there!$"),
        p("^Wait a moment before confirming!$"),
        p("^Your radio is weak\\. Find another enjoyer to boost it\\.$"),
        p("^Your radio signal is strong!$"),
        p("^Your radio lost signal\\. There's too many enjoyers on this channel\\.$"));
    private static final List<Pattern> ANNOYING = List.of(
        p("^Your (?:Implosion|Molten Wave|Spirit Sceptre) hit (.*) for (.*) damage.$"),
        p("^You need a tool with a Breaking Power of (\\d+) to mine (.*)! Speak to Fragilis by the entrance to the Crystal Hollows to learn more!$"),
        p("^Your Blessing enchant got you double drops!$"),
        p("^GOOD CATCH! You found a (?:Fish Bait|Grand Experience Bottle|Blessed Bait|Dark Bait|Light Bait|Hot Bait|Spooky Bait)\\.$"),
        p("^\\[NPC] Jacob: My contest has started!$"),
        p("^Obtain a Booster Cookie from the community shop in the hub!$"));
    /** Only outside the Garden, like SkyHanni. */
    private static final Pattern ANITA_FORTUNE = p("^\\[NPC] Jacob: Your Anita's \\w+ is giving you \\+\\d{1,2}.*Fortune during the contest!$");
    private static final List<Pattern> WINTER = List.of(
        p("^☃ (.*) mounted a Snow Cannon!$"),
        p("^(?:COMMON|RARE|SWEET|SANTA|PARTY)(?: TIER)?! .+ gift with .*"),
        p("^EXTRA! \\+[\\d,]+ North Stars?$"),
        p("^This player is playing a profile mode which doesn't allow gifting!$"),
        p("^Can't place gifts this close to spawn!$"),
        p("^You cannot place a gift so close to an NPC!$"),
        p("^Click a player to gift them! This isn't a player!$"),
        p(".*disconnected, gift refunded!$"),
        p("^This gift is for .*, sorry!$"));
    private static final List<Pattern> POWDER_MINING = List.of(
        p("^You uncovered a treasure chest!$"),
        p("^You have successfully picked the lock on this chest!$"),
        p("^This chest has already been looted\\.$"),
        p("^You received 1 (?:Wishing Compass|Ascension Rope|Oil Barrel)\\.$"));
    private static final Pattern POWDER_WRAPPER = p("^▬{64}$");
    private static final Pattern POWDER_INSIDE = p("^(?:\\s*|\\s*CHEST LOCKPICKED.*|\\s*LOOT CHEST COLLECTED.*|\\s*REWARDS.*| {4}.+)$");
    private static final List<Pattern> FIRE_SALE = List.of(
        p("(?s)^\\S? ?FIRE SALE \\S?.*"),
        p("^♨ Fire Sales for .* are starting soon!$"),
        p("^\\s*♨ .* (?:Skin|Rune|Dye) (?:for a limited time )?\\(.* left\\)!?$"),
        p("^♨ Visit the Community Shop in the next .* to grab yours!.*"),
        p("^♨ A Fire Sale for .* is starting soon!$"),
        p("^♨ Fire Sales? for .* ended!$"),
        p("^\\s*♨ And \\d+ more!$"),
        p("^♨ Selling multiple items for a limited time!$"));
    private static final List<Pattern> EVENT = List.of(
        p("^You are now Event Level .*!$"),
        p("^You earned .* Event Silver!$"),
        p("^\\S LEVEL UP! \\S$"));
    private static final List<Pattern> FACTORY = List.of(
        p("^.* has been promoted to \\[.*] .*!$"),
        p("^Your Rabbit Barn capacity has been increased to .* Rabbits!$"),
        p("^You will now produce .* Chocolate per click!$"),
        p("^You upgraded to .*?!$"));
    private static final List<Pattern> SACRIFICE = List.of(
        p("^SACRIFICE! (.*) turned (.*) into (.*) Dragon Essence!$"),
        p("^BONUS LOOT! They also received (.*) from their sacrifice!$"));
    private static final List<Pattern> SKY_MALL = List.of(
        p("^New day! Your Sky Mall buff changed!$"),
        p("^You can disable this messaging by toggling Sky Mall in your /hotm!$"));
    private static final List<Pattern> LOTTERY = List.of(
        p("^New day! Your Lottery buff changed!$"),
        p("^You can disable this messaging by toggling Lottery in your /hotf!$"));
    private static final List<Pattern> REWARD_BUNDLES = List.of(
        p("^You haven't claimed your \\w+ Rewards yet!$"),
        p("^Talk to the .+in the .+!$"));
    private static final List<Pattern> HOPPITY = List.of(
        p("^HOPPITY'S HUNT A .* has appeared!$"),
        p("^Hoppity's Hunt has begun! Help Hoppity find his Chocolate Rabbit Eggs across SkyBlock each day during the Spring!$"));
    private static final List<Pattern> PARKOUR_PADS = List.of(
        p("^Started parkour (.*)!$"),
        p("^Finished parkour (.*) in (.*)!$"),
        p("^Reached checkpoint #(.*) for parkour (.*)!$"),
        p("^Wrong checkpoint for parkour (.*)!$"),
        p("^You haven't reached all checkpoints for parkour (.*)!$"),
        p("^Cancelled parkour!.*"),
        p("^Warped from the (.*) to the (.*)!$"),
        p("^This Teleport Pad does not have a destination set!$"));
    private static final List<Pattern> FEAST_CHEF = List.of(
        p("^\\[NPC] Feast Chef Ted: Thanks for the donation! I've added a Kernel to your purse\\.$"));
    private static final List<Pattern> LEGACY = List.of(
        p("^You currently have one or more Legacy Items in your inventory or sacks that are no longer used throughout the game! Exchange them in the Legacy Trades menu, accessed through /legacytrades!$"));
    private static final List<Pattern> GARDEN_NO_PESTS = List.of(
        p("^There are not any Pests on your Garden right now! Keep farming!$"));
    private static final List<Pattern> DUNGEON_RARE_REWARD = List.of(
        p("^RARE REWARD! (.*) found a (.*) in their (.*) Chest!$"));
    /** These three only in dungeons. */
    private static final List<Pattern> DUNGEON_SPAM = List.of(
        p("^Your (?:Healer|Mage|Berserk|Archer|Tank) stats are doubled because you are the only player using this class!$"),
        p("^\\[(?:Healer|Mage|Berserk|Archer|Tank)].*"),
        p("^[\\w']+ the Fairy: You killed me! Take this Revive Stone so that my death is not in vain!$"),
        p("^[\\w']+ the Fairy: You killed me! I'll revive you so that my death is not in vain!$"),
        p("^[\\w']+ the Fairy: Have a great life!$"));
    private static final List<Pattern> FORAGING_HUNTING = List.of(
        p("^You cannot damage a tree while it is regenerating!$"),
        p("^The toughness of this tree is way too high!$"),
        p("^Mochibear ate too much and passed out! You caught it!$"),
        p("^You caught yourself an invisibug! The shard was sent to your Hunting Box!$"),
        p("^The Frog is exhausted\\.\\.\\.$"),
        p("^\\[NPC] Swoop: Wow! I forgot to tell you, monsters around here can only take damage from Axes!$"));
    /** SkyBlock's Pristine proc, moved to the Side Chat by default (Chat > Side Chat). */
    private static final Pattern PRISTINE = p("^PRISTINE! You found . Flawed \\w+ Gemstone x\\d+!$");

    /** Any § code, even ones vanilla doesn't know (and a lone § at the end), for {@link #isBlank}. */
    private static final Pattern FORMAT_CODE = Pattern.compile("(?s)§.?");

    /** Inside a powder mining chest's reward block (between the two ▬ lines). */
    private static boolean inPowderRewards;

    private SpamFilters() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null || c.chat == null ? null : c.chat.spamFilters;
    }

    public static void init() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || allow(message));
    }

    /** @return false when the message leaves the main chat (hidden or moved to the Side Chat). */
    private static boolean allow(Component message) {
        Mode mode;
        try {
            mode = mode(message);
        } catch (Exception e) {
            return true;
        }
        if (mode == Mode.SHOW) return true;
        if (mode == Mode.SIDE_CHAT) {
            // With the Side Chat off the message stays in the main chat rather than disappearing.
            return !SideChat.add(message);
        }
        Sky2MAchievements.add(Sky2MAchievements.Stat.HIDDEN_MESSAGES, 1);
        return false;
    }

    private static Mode mode(Component message) {
        String raw = Sky2MLocation.strip(message.getString());
        String text = raw.trim();

        SideChat.Config side = SideChat.config();
        if (side != null && side.pristine != Mode.SHOW && PRISTINE.matcher(text).matches()) return side.pristine;

        Config c = config();
        if (c == null) return Mode.SHOW;

        // Powder mining chest blocks: the ▬ lines open and close them, everything in between belongs to them.
        if (c.powderMining != Mode.SHOW && Sky2MLocation.inMiningIsland()) {
            if (POWDER_WRAPPER.matcher(text).matches()) {
                inPowderRewards = !inPowderRewards;
                return c.powderMining;
            }
            if (inPowderRewards) {
                if (POWDER_INSIDE.matcher(raw).matches()) return c.powderMining;
                inPowderRewards = false;
            }
            if (any(POWDER_MINING, text)) return c.powderMining;
        } else {
            inPowderRewards = false;
        }

        if (isBlank(raw)) return c.empty;
        if (c.separators != Mode.SHOW && SEPARATOR.matcher(text).matches()) return c.separators;
        if (check(c.lobby, LOBBY, text)) return c.lobby;
        if (check(c.visiting, VISITING, text)) return c.visiting;
        if (check(c.welcome, WELCOME, text)) return c.welcome;
        if (check(c.guildExp, GUILD_EXP, text)) return c.guildExp;
        if (check(c.killCombo, KILL_COMBO, text)) return c.killCombo;
        if (check(c.profileJoin, PROFILE_JOIN, text)) return c.profileJoin;
        if (check(c.bazaarProgress, BZ_AH_PROGRESS, text)) return c.bazaarProgress;
        if (check(c.money, MONEY, text)) return c.money;
        if (check(c.slayer, SLAYER, text)) return c.slayer;
        if (check(c.slayerDrops, SLAYER_DROPS, text)) return c.slayerDrops;
        if (check(c.uselessDrops, USELESS_DROPS, text)) return c.uselessDrops;
        if (check(c.uselessNotifications, USELESS_NOTIFICATIONS, text)) return c.uselessNotifications;
        if (check(c.uselessWarnings, USELESS_WARNINGS, text)) return c.uselessWarnings;
        if (check(c.annoying, ANNOYING, text)) return c.annoying;
        if (c.annoying != Mode.SHOW && !Sky2MLocation.inGarden() && ANITA_FORTUNE.matcher(text).matches()) return c.annoying;
        if (check(c.winter, WINTER, text)) return c.winter;
        if (check(c.fireSale, FIRE_SALE, raw.stripLeading())) return c.fireSale;
        if (check(c.event, EVENT, text)) return c.event;
        if (check(c.factory, FACTORY, text)) return c.factory;
        if (check(c.sacrifice, SACRIFICE, text)) return c.sacrifice;
        if (c.skyMallLottery != Mode.SHOW) {
            if ((c.skyMallEverywhere || !Sky2MLocation.inMiningIsland()) && any(SKY_MALL, text)) return c.skyMallLottery;
            if ((c.skyMallEverywhere || !inForagingIsland()) && any(LOTTERY, text)) return c.skyMallLottery;
        }
        if (check(c.rewardBundles, REWARD_BUNDLES, text)) return c.rewardBundles;
        if (check(c.hoppity, HOPPITY, text)) return c.hoppity;
        if (check(c.parkourPads, PARKOUR_PADS, text)) return c.parkourPads;
        if (check(c.feastChef, FEAST_CHEF, text)) return c.feastChef;
        if (check(c.legacyItems, LEGACY, text)) return c.legacyItems;
        if (c.gardenNoPests != Mode.SHOW && Sky2MLocation.inGarden() && any(GARDEN_NO_PESTS, text)) return c.gardenNoPests;
        if (check(c.dungeonRareRewards, DUNGEON_RARE_REWARD, text)) return c.dungeonRareRewards;
        if (c.dungeonSpam != Mode.SHOW && Sky2MLocation.inDungeon() && any(DUNGEON_SPAM, text)) return c.dungeonSpam;
        if (check(c.foragingHunting, FORAGING_HUNTING, text)) return c.foragingHunting;
        return Mode.SHOW;
    }

    /** Like SkyHanni's StringUtils.isEmpty: nothing left once every § code (valid or not) and the whitespace are gone. */
    private static boolean isBlank(String raw) {
        if (raw.isEmpty()) return true;
        return FORMAT_CODE.matcher(raw).replaceAll("").trim().isBlank();
    }

    private static boolean inForagingIsland() {
        String area = Sky2MLocation.area();
        return area.equals("The Park") || area.equals("Galatea");
    }

    private static boolean check(Mode mode, List<Pattern> patterns, String text) {
        return mode != Mode.SHOW && any(patterns, text);
    }

    private static boolean any(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) if (pattern.matcher(text).matches()) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ config

    public static final class Config {
        @Expose
        @ConfigOption(name = "Lobby Spam", desc = "The Hypixel SMP ad, snow particles, Mystery Dust, Pet Consumables, Prototype Lobby and tournament lines. (Player lobby joins and Mystery Boxes are under Chat Filters.)")
        @ConfigEditorDropdown
        public Mode lobby = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Visiting & Warp Portals", desc = "\"Warping using transfer token...\", \"Finding player...\", \"Sending a visit request...\" and \"Warped to ...!\". (The other warping lines are under Chat Filters.)")
        @ConfigEditorDropdown
        public Mode visiting = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Welcome", desc = "\"Welcome to Hypixel SkyBlock!\"")
        @ConfigEditorDropdown
        public Mode welcome = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Guild/Event EXP", desc = "\"You earned 2 GEXP from playing SkyBlock!\" and the Event EXP version.")
        @ConfigEditorDropdown
        public Mode guildExp = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Kill Combo", desc = "Grandma Wolf's \"+50 Kill Combo\" and \"Your Kill Combo has expired!\" lines.")
        @ConfigEditorDropdown
        public Mode killCombo = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Profile Join", desc = "\"You are playing on profile: ...\" and \"Profile ID: ...\"")
        @ConfigEditorDropdown
        public Mode profileJoin = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Empty Lines", desc = "Blank chat lines, including lines of only spaces or colour codes.")
        @ConfigEditorDropdown
        public Mode empty = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Separator Lines", desc = "Lines that are only ▬▬▬, ----- or ===== (the borders around Hypixel's message blocks, like the party ones).")
        @ConfigEditorDropdown
        public Mode separators = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Bazaar & AH Progress", desc = "\"Putting item in escrow...\", \"Processing purchase...\", \"[Bazaar] Submitting buy order...\", \"Depositing coins...\" and the like.")
        @ConfigEditorDropdown
        public Mode bazaarProgress = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Bazaar & AH Messages", desc = "\"Buy Order Setup!\", \"Sell Offer Setup!\", \"Cancelled! Refunded ...\" and \"Visit the Auction House to collect your item!\"")
        @ConfigEditorDropdown
        public Mode money = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Slayer Quest", desc = "Slayer quest started/complete, \"NICE! SLAYER BOSS SLAIN!\", \"Talk to Maddox\" and Maddox's phone ringing.")
        @ConfigEditorDropdown
        public Mode slayer = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Slayer Drops", desc = "Common slayer drops: Revenant Viscera, Foul Flesh, arrow poisons, catalysts, Null Atoms, Sinful Dice, distillates, low runes...")
        @ConfigEditorDropdown
        public Mode slayerDrops = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Useless Drops", desc = "RARE DROP! Enchanted Ender Pearl, Carrot, Potato, Machine Gun Bow, Earth Shard, Zombie Lord Chestplate, Enchanted End Stone and Crystal Fragment.")
        @ConfigEditorDropdown
        public Mode uselessDrops = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Useless Notifications", desc = "Tips sent, Plasmaflux removed, Mining Speed Boost used/expired/available and 0 coins bank interest.")
        @ConfigEditorDropdown
        public Mode uselessNotifications = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Useless Warnings", desc = "\"Sending commands too fast\", \"Can't use this while in combat\", salvage warnings, \"Whoa! Slow down there!\", radio signal lines...")
        @ConfigEditorDropdown
        public Mode uselessWarnings = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Annoying Spam", desc = "Implosion/Molten Wave/Spirit Sceptre hit messages, the Breaking Power warning, Blessing double drops, bait catches, Jacob's contest start, the Booster Cookie reminder and Anita's accessory outside the Garden.")
        @ConfigEditorDropdown
        public Mode annoying = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Winter Island & Gifts", desc = "Snow Cannon mounts, gift rewards, North Stars and gifting errors.")
        @ConfigEditorDropdown
        public Mode winter = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Powder Mining", desc = "Treasure chest lines and the whole chest reward block (▬ lines, CHEST LOCKPICKED, REWARDS and every reward) in the mining islands.")
        @ConfigEditorDropdown
        public Mode powderMining = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Fire Sales", desc = "The repeating Fire Sale announcements and reminders.")
        @ConfigEditorDropdown
        public Mode fireSale = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Event Level Up", desc = "Event Level, Event Silver and LEVEL UP lines.")
        @ConfigEditorDropdown
        public Mode event = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Chocolate Factory Upgrades", desc = "Employee promotions, Rabbit Barn capacity, chocolate per click and \"You upgraded to ...\".")
        @ConfigEditorDropdown
        public Mode factory = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Sacrifice", desc = "Other players' Dragon Essence sacrifices and their bonus loot.")
        @ConfigEditorDropdown
        public Mode sacrifice = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Sky Mall & Lottery", desc = "\"New day! Your Sky Mall/Lottery buff changed!\" when you aren't on a mining (Sky Mall) or foraging (Lottery) island.")
        @ConfigEditorDropdown
        public Mode skyMallLottery = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Sky Mall & Lottery: Everywhere", desc = "Apply Sky Mall & Lottery on every island too, including the mining (Sky Mall) and foraging (Lottery) islands. Needs Sky Mall & Lottery set to Hide or Side Chat.")
        @ConfigEditorBoolean
        public boolean skyMallEverywhere = false;

        @Expose
        @ConfigOption(name = "Reward Bundles", desc = "The reminders to claim seasonal reward bundles.")
        @ConfigEditorDropdown
        public Mode rewardBundles = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Hoppity's Hunt", desc = "\"Hoppity's Hunt has begun!\" and \"A Chocolate ... Egg has appeared!\"")
        @ConfigEditorDropdown
        public Mode hoppity = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Parkour & Teleport Pads", desc = "Parkour start/checkpoint/finish lines and teleport pad messages.")
        @ConfigEditorDropdown
        public Mode parkourPads = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Feast Chef Ted", desc = "Kernels added to your purse while farming.")
        @ConfigEditorDropdown
        public Mode feastChef = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Legacy Items Warning", desc = "The warning about Legacy Items in your inventory or sacks.")
        @ConfigEditorDropdown
        public Mode legacyItems = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Garden No Pests", desc = "\"There are not any Pests on your Garden right now!\"")
        @ConfigEditorDropdown
        public Mode gardenNoPests = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Dungeon Rare Rewards", desc = "Other players' \"RARE REWARD! ... found a ... in their ... Chest!\"")
        @ConfigEditorDropdown
        public Mode dungeonRareRewards = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Dungeon Spam", desc = "In dungeons: solo class stats doubled, the [Class] stat lines and the fairy's messages.")
        @ConfigEditorDropdown
        public Mode dungeonSpam = Mode.SHOW;

        @Expose
        @ConfigOption(name = "Foraging & Hunting", desc = "Regenerating/too tough tree warnings, redundant shard catch comments and Swoop's axe message.")
        @ConfigEditorDropdown
        public Mode foragingHunting = Mode.SHOW;
    }
}
