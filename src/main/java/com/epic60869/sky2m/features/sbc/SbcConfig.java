package com.epic60869.sky2m.features.sbc;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

/**
 * Settings for the features that talk to the Sky2M server (the "Sky2M Online" category) and for the SkyBlock
 * helpers added with them (item cooldowns, event calendar, pest highlight).
 */
public final class SbcConfig {
    private SbcConfig() {}

    public static final class Online {
        @Expose
        @Accordion
        @ConfigOption(name = "S2M Chat", desc = "Replies, mentions, reactions and item sharing in Sky2M chat (/s2c).")
        public Chat chat = new Chat();

        @Expose
        @Accordion
        @ConfigOption(name = "Friends & Who", desc = "/s2 who, /s2 friend, your location sharing and friend notifications.")
        public Social social = new Social();

        @Expose
        @Accordion
        @ConfigOption(name = "Cosmetics", desc = "Badges, supporter symbols and capes of Sky2M players.")
        public Cosmetics cosmetics = new Cosmetics();

        @Expose
        @Accordion
        @ConfigOption(name = "Server Messages", desc = "Update notices, announcements and the message of the day from the Sky2M server.")
        public Server server = new Server();

        @Expose
        @Accordion
        @ConfigOption(name = "Settings Cloud Sync", desc = "Save your Sky2M settings to the Sky2M server and load them on another computer.")
        public SettingsSync settingsSync = new SettingsSync();

        @Expose
        @ConfigOption(name = "Crash Reports", desc = "Send Sky2M errors (the stack trace, mod/Minecraft/Java version, OS, your name and UUID) to the Sky2M server so they can be fixed. Nothing else is sent.")
        @ConfigEditorBoolean
        public boolean crashReports = true;
    }

    public static final class Chat {
        @Expose
        @ConfigOption(name = "Replies", desc = "Show what a message replies to (\"↪ Name: text\") and add a ↩ button to reply to a message.")
        @ConfigEditorBoolean
        public boolean replies = true;

        @Expose
        @ConfigOption(name = "Reactions", desc = "Show emoji reactions after messages, and a reaction bar when you hover a message with chat open.")
        @ConfigEditorBoolean
        public boolean reactions = true;

        @Expose
        @ConfigOption(name = "Highlight Mentions", desc = "Highlight messages that mention you (@name, your name or nickname).")
        @ConfigEditorBoolean
        public boolean mentionHighlight = true;

        @Expose
        @ConfigOption(name = "Mention Colour", desc = "The highlight behind messages that mention you.")
        @ConfigEditorColour
        public String mentionColour = "0:90:255:170:0";

        @Expose
        @ConfigOption(name = "Mention Sound", desc = "Play a ping when a message mentions you.")
        @ConfigEditorBoolean
        public boolean mentionSound = true;

        @Expose
        @ConfigOption(name = "Mention Volume", desc = "How loud the mention ping is.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1f, minStep = 0.05f)
        public float mentionVolume = 0.8f;

        @Expose
        @ConfigOption(name = "@Name Completion", desc = "Press Tab after @ to complete the names of Sky2M players who are online.")
        @ConfigEditorBoolean
        public boolean mentionCompletion = true;

        @Expose
        @ConfigOption(name = "Item Sharing", desc = "Show shared items as clickable [Item Name] previews in every chat. Use [item] for the held item, or [inv] to share your whole inventory as one clickable [Inventory] that opens it in a menu. Works in plain chat and in /pc, /gc, /ac, /cc, /oc, /msg and /r. Recipients need Sky2M to see the previews.")
        @ConfigEditorBoolean
        public boolean itemSharing = true;

        @Expose
        @ConfigOption(name = "Party Finder Announcements", desc = "Show a line in chat with a [JOIN] button when someone opens a new /s2 pf listing.")
        @ConfigEditorBoolean
        public boolean partyFinderAnnouncements = true;
    }

    public enum LocationSharing {
        EVERYONE("Everyone"), FRIENDS("Friends"), NOBODY("Nobody");

        private final String label;

        LocationSharing(String label) {
            this.label = label;
        }

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class Social {
        @Expose
        @ConfigOption(name = "Who List Screen", desc = "/s2 who opens a list of online Sky2M players. Off shows the list in chat instead.")
        @ConfigEditorBoolean
        public boolean whoScreen = true;

        @Expose
        @ConfigOption(name = "Share Your Status", desc = "Tell the Sky2M server which area and server you're in and whether you're AFK (who sees it follows Location Sharing).")
        @ConfigEditorBoolean
        public boolean presence = true;

        @Expose
        @ConfigOption(name = "AFK After (minutes)", desc = "Show you as AFK after this long without moving, clicking or typing.")
        @ConfigEditorSlider(minValue = 1, maxValue = 30, minStep = 1)
        public int afkMinutes = 5;

        @Expose
        @ConfigOption(name = "Location Sharing", desc = "Who can see your area and server in /s2 who and the friends list.")
        @ConfigEditorDropdown
        public LocationSharing location = LocationSharing.EVERYONE;

        @Expose
        @ConfigOption(name = "Allow Friend Requests", desc = "Let other Sky2M players send you friend requests.")
        @ConfigEditorBoolean
        public boolean friendRequests = true;

        @Expose
        @ConfigOption(name = "Tell Friends When I'm Online", desc = "Your friends get a notification when you come online or go offline.")
        @ConfigEditorBoolean
        public boolean notifyFriendsOnline = true;

        @Expose
        @ConfigOption(name = "Friend Online Toasts", desc = "Show a popup when a friend comes online or goes offline.")
        @ConfigEditorBoolean
        public boolean friendToasts = true;

        @ConfigOption(name = "Friends", desc = "Open your Sky2M friends list.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable openFriends = () -> SbcSocial.openFriends();
    }

    public static final class Cosmetics {
        @Expose
        @ConfigOption(name = "Badges & Supporter Symbols", desc = "Draw players' Sky2M badge and supporter symbol before their name in chat, tab and nametags.")
        @ConfigEditorBoolean
        public boolean symbols = true;

        @Expose
        @ConfigOption(name = "Capes", desc = "Show Sky2M capes on players who have one.")
        @ConfigEditorBoolean
        public boolean capes = true;

        @ConfigOption(name = "My Cosmetics", desc = "Pick the badge and cape you show.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable open = () -> SbcCosmetics.openScreen();
    }

    public static final class Server {
        @Expose
        @ConfigOption(name = "Announcements", desc = "Show announcements from the Sky2M team (in chat, as a title or as a popup).")
        @ConfigEditorBoolean
        public boolean announcements = true;

        @Expose
        @ConfigOption(name = "Message of the Day", desc = "Show the Sky2M message of the day once each time you connect.")
        @ConfigEditorBoolean
        public boolean motd = true;
    }

    public static final class SettingsSync {
        @Expose
        @ConfigOption(name = "Slot", desc = "The name to save your settings under (up to 5 slots).")
        @ConfigEditorText
        public String slot = "default";

        @ConfigOption(name = "Upload", desc = "Save your current settings to the slot.")
        @ConfigEditorButton(buttonText = "UPLOAD")
        public Runnable upload = () -> SbcSettingsSync.upload(null);

        @ConfigOption(name = "Download", desc = "Replace your settings with the ones saved in the slot.")
        @ConfigEditorButton(buttonText = "DOWNLOAD")
        public Runnable download = () -> SbcSettingsSync.download(null);

        @ConfigOption(name = "List Slots", desc = "Show your saved slots in chat.")
        @ConfigEditorButton(buttonText = "LIST")
        public Runnable list = SbcSettingsSync::list;
    }

    // ------------------------------------------------------------------------------------------ SkyBlock features

    public static final class ItemCooldowns {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show item ability cooldowns (read from the item's \"Cooldown: Xs\") on the item's slot.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Cooldown Bar", desc = "A bar at the bottom of the slot, like item durability.")
        @ConfigEditorBoolean
        public boolean bar = true;

        @Expose
        @ConfigOption(name = "Slot Overlay", desc = "Shade the slot from the top while the ability cools down, like vanilla ender pearls.")
        @ConfigEditorBoolean
        public boolean overlay = true;

        @Expose
        @ConfigOption(name = "Cooldown HUD", desc = "A HUD listing abilities that are cooling down. Move it with /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "Deployable Timers HUD", desc = "Show how long your deployed power orbs and flares (Warning, Alert and SOS Flare) have left. Works without Item Cooldowns on. Move it with /s2 gui.")
        @ConfigEditorBoolean
        public boolean deployableHud = false;

        @Expose
        @ConfigOption(name = "Ready Sound", desc = "Play a sound when an ability is ready again.")
        @ConfigEditorBoolean
        public boolean readySound = false;
    }

    public static final class EventCalendar {
        @Expose
        @ConfigOption(name = "Event HUD", desc = "A HUD with upcoming SkyBlock events and countdowns. Move it with /s2 gui. /s2 calendar opens the full list.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "HUD Events", desc = "How many upcoming events the HUD shows.")
        @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1)
        public int hudCount = 5;

        @Expose
        @ConfigOption(name = "Reminders", desc = "Popups and a sound before the events turned on below.")
        @ConfigEditorBoolean
        public boolean reminders = false;

        @Expose
        @ConfigOption(name = "Reminder Sound", desc = "Play a sound with each reminder.")
        @ConfigEditorBoolean
        public boolean sound = true;

        @Expose @ConfigOption(name = "Jacob's Contest", desc = "Remind before Jacob's Farming Contests (with the crops).") @ConfigEditorBoolean public boolean jacob = true;
        @Expose @ConfigOption(name = "Jacob's: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int jacobMinutes = 2;
        @Expose @ConfigOption(name = "Jacob's: Only These Crops", desc = "Only remind for contests with one of these crops (comma separated, e.g. \"Wheat, Nether Wart\"). Empty = every contest.") @ConfigEditorText public String jacobCrops = "";
        @Expose @ConfigOption(name = "Dark Auction", desc = "") @ConfigEditorBoolean public boolean darkAuction = true;
        @Expose @ConfigOption(name = "Dark Auction: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int darkAuctionMinutes = 2;
        @Expose @ConfigOption(name = "Cult of the Fallen Star", desc = "") @ConfigEditorBoolean public boolean fallenStar = false;
        @Expose @ConfigOption(name = "Fallen Star: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int fallenStarMinutes = 1;
        @Expose @ConfigOption(name = "Spooky Festival", desc = "") @ConfigEditorBoolean public boolean spooky = true;
        @Expose @ConfigOption(name = "Spooky: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int spookyMinutes = 5;
        @Expose @ConfigOption(name = "New Year Celebration", desc = "") @ConfigEditorBoolean public boolean newYear = true;
        @Expose @ConfigOption(name = "New Year: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int newYearMinutes = 5;
        @Expose @ConfigOption(name = "Season of Jerry", desc = "") @ConfigEditorBoolean public boolean jerry = true;
        @Expose @ConfigOption(name = "Jerry: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int jerryMinutes = 5;
        @Expose @ConfigOption(name = "Traveling Zoo", desc = "") @ConfigEditorBoolean public boolean zoo = true;
        @Expose @ConfigOption(name = "Zoo: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int zooMinutes = 5;
        @Expose @ConfigOption(name = "Hoppity's Hunt", desc = "") @ConfigEditorBoolean public boolean hoppity = true;
        @Expose @ConfigOption(name = "Hoppity: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int hoppityMinutes = 5;
        @Expose @ConfigOption(name = "Elections", desc = "When the mayor election opens and closes.") @ConfigEditorBoolean public boolean elections = true;
        @Expose @ConfigOption(name = "Elections: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int electionsMinutes = 5;
        @Expose @ConfigOption(name = "Mayor Events", desc = "Marina's Fishing Festival and Diana's Mythological Ritual.") @ConfigEditorBoolean public boolean mayorEvents = true;
        @Expose @ConfigOption(name = "Mayor Events: Minutes Before", desc = "") @ConfigEditorSlider(minValue = 0, maxValue = 30, minStep = 1) public int mayorEventsMinutes = 5;
    }

    public static final class PestHighlight {
        @Expose
        @ConfigOption(name = "Enabled", desc = "In the Garden only: a box around pests, seen through crops and walls, so they're easy to find. Off everywhere else.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Colour", desc = "The box colour.")
        @ConfigEditorColour
        public String colour = "0:255:255:85:255";

        @Expose
        @ConfigOption(name = "Line To Nearest", desc = "Draw a line from your crosshair to the nearest pest.")
        @ConfigEditorBoolean
        public boolean tracer = false;

        @Expose
        @ConfigOption(name = "Beacon On Nearest", desc = "Draw a tall beam on the nearest pest.")
        @ConfigEditorBoolean
        public boolean beacon = false;

        @Expose
        @ConfigOption(name = "Pest HUD", desc = "A HUD line with the pests alive and the infested plots. Move it with /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = true;
    }
}
