package com.epic60869.sky2m;

import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.Category;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorKeybind;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorSlider;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour;
import io.github.notenoughupdates.moulconfig.annotations.ConfigAccordionId;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorAccordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;
import io.github.notenoughupdates.moulconfig.managed.ManagedConfig;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sky2M's configuration model.
 *
 * The configuration screen is powered directly by MoulConfig, the same
 * configuration engine used by SkyHanni/NotEnoughUpdates.
 */
// The old-style accordions (@ConfigEditorAccordion / @ConfigAccordionId) are deprecated in MoulConfig, but moving to
// @Accordion objects would change the saved config layout and reset everyone's settings, so they stay.
@SuppressWarnings("deprecation")
public final class Sky2MConfig extends Config {
    public static final class General {
        @Expose
        @ConfigOption(name = "Show What's New on Update", desc = "After Sky2M updates, say so in chat with a [What's New] button: the new features, each with an on/off toggle and a button to its settings. Once per new version, after joining a world; not on a fresh install. /s2 whatsnew opens it any time.")
        @ConfigEditorBoolean
        public boolean whatsNewMessage = true;

        @Expose
        @ConfigOption(name = "Update Notifications", desc = "Tell you in chat when a newer Sky2M version is out (\"New Sky2M Mod Version 1.2.3 --> 1.2.5\"), with a download link.")
        @ConfigEditorBoolean
        public boolean updateNotifications = true;

        @ConfigOption(name = "Sky2M GitHub", desc = "Open Sky2M's GitHub page for this Minecraft version, with the downloads and source code.")
        @ConfigEditorButton(buttonText = "GITHUB")
        public Runnable openGithub = () -> com.mojang.blaze3d.Blaze3D.openUri(java.net.URI.create(Sky2MUpdateChecker.GITHUB_URL));

        @Expose
        @ConfigOption(name = "SkyBlock Only", desc = "Pause every Sky2M feature outside SkyBlock (the Hypixel lobby, other games, other servers): HUDs, highlights, chat features, the custom crosshair and the rest. Singleplayer still works. Settings, commands and Sky2M chat keep working (Killer560's).")
        @ConfigEditorBoolean
        public boolean skyblockOnly = false;

        @Expose
        @ConfigOption(name = "Warn About Key Conflicts", desc = "Say in chat when two features (Sky2M settings, Sky2M or other mods' key binds, Command Keys macros) use the same key. Checked a few seconds after joining and whenever a key changes. Keys that only work in one menu (Wardrobe, Leap, Fusion...) don't count against keys used in the world. /s2 keyconflicts lists them any time.")
        @ConfigEditorBoolean
        public boolean warnKeyConflicts = true;

        @Expose
        @ConfigOption(name = "Performance Warnings", desc = "Say in chat when one Sky2M HUD or in-world renderer takes over 1 ms a frame for 15 seconds in a row (the numbers /s2 perf shows), with a button to its settings. Once per feature per session; [Don't warn again] stops it for that feature.")
        @ConfigEditorBoolean
        public boolean performanceWarnings = true;

        /** Features the player chose [Don't warn again] for in a performance warning. */
        @Expose
        public java.util.List<String> perfWarningMuted = new java.util.ArrayList<>();

        @Expose
        @ConfigOption(name = "Error Reporter", desc = "When a Sky2M HUD, chat feature or in-world renderer hits an error, say so in chat with [Report to staff], [Copy] and [Ignore]. Reporting asks first, then sends a bug report with the error, the /s2 selftest report and your versions. Once per error per session, at most one message every 2 minutes. /s2 errors lists this session's errors.")
        @ConfigEditorBoolean
        public boolean errorReporter = true;

        @Expose
        @ConfigOption(name = "Warn About Overlapping Mods", desc = "Once, after joining a world, say in chat when other installed mods (SkyHanni, Skyblocker...) do some of the same things as Sky2M, so you can turn one side off. Turn this off to never be told.")
        @ConfigEditorBoolean
        public boolean overlapNotice = true;

        /** Whether the overlapping-mods message has been shown (once per install). */
        @Expose
        public boolean overlapNoticeShown = false;

        @ConfigOption(name = "Export Settings Code", desc = "Copy all your Sky2M settings to the clipboard as one code to share (/s2 config export). Versions and other personal values are left out.")
        @ConfigEditorButton(buttonText = "COPY")
        public Runnable exportSettingsCode = () -> Sky2MConfigCode.exportToClipboard();

        @ConfigOption(name = "Import Settings Code", desc = "Load a settings code from the clipboard (/s2 config import). Asks in chat before replacing anything; your current settings are backed up to config/sky2m/config-backups first.")
        @ConfigEditorButton(buttonText = "PASTE")
        public Runnable importSettingsCode = () -> Sky2MConfigCode.importFromGui();

        @ConfigOption(name = "Tools & Editors", desc = "The HUD editor, notes, command keys and item customization.")
        @ConfigEditorAccordion(id = 101)
        public boolean toolsEditorsGroup = false;

        @ConfigOption(name = "Gui Editor", desc = "Open the transparent HUD/GUI editor.")
        @ConfigEditorButton(buttonText = "OPEN")
        @ConfigAccordionId(id = 101)
        public Runnable guiEditor = () -> openHudEditor();

        @ConfigOption(name = "Notes", desc = "Open your Sky2M notes.")
        @ConfigEditorButton(buttonText = "OPEN")
        @ConfigAccordionId(id = 101)
        public Runnable notes = () -> openNotes();

        @ConfigOption(name = "Command Keys", desc = "Configure Sky2M command shortcuts.")
        @ConfigEditorButton(buttonText = "OPEN")
        @ConfigAccordionId(id = 101)
        public Runnable commandKeys = () -> openCommandKeys();

        /** The Sky2M version that last ran, to notice updates. */
        @Expose
        public String lastSeenVersion = "";

        /** The version before the last update, for /s2 whatsnew. */
        @Expose
        public String whatsNewSince = "";

        @Expose
        public boolean firstBootAcknowledged = false;

        /** Which changed defaults this config has already been given (see migrateConfigShape). */
        @Expose
        public int defaultsVersion = 7;
    }

    public static final class Gui {
        @Expose
        @ConfigOption(name = "HUD Theme", desc = "Colours for Sky2M's text HUDs: High contrast (brighter text on a darker background) or a palette for Deuteranopia, Protanopia or Tritanopia that swaps the colours those are hard to tell apart. Default leaves every colour as it is.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.core.Sky2MTheme.HudTheme hudTheme = com.epic60869.sky2m.features.core.Sky2MTheme.HudTheme.DEFAULT;

        @ConfigOption(name = "HUDs", desc = "The HUD overview and the HUD editor (/s2 gui).")
        @ConfigEditorAccordion(id = 102)
        public boolean hudsGroup = false;

        // Its search also matches every registered HUD's name (see buttonEditor), so searching a HUD finds this.
        @ConfigOption(name = "HUD Overview", desc = "Every Sky2M HUD with whether it's on, its position and scale, and whether it's off-screen, with buttons to reset each one or edit it in /s2 gui (/s2 huds).")
        @ConfigEditorButton(buttonText = "OPEN")
        @ConfigAccordionId(id = 102)
        public Runnable hudOverview = () -> com.epic60869.sky2m.features.core.Sky2MHudOverviewScreen.open(Minecraft.getInstance().gui.screen());

        @ConfigOption(name = "Edit HUDs", desc = "Move and resize every HUD (/s2 gui).")
        @ConfigEditorButton(buttonText = "/s2 gui")
        @ConfigAccordionId(id = 102)
        public Runnable editHuds = Sky2MConfig::openHudEditor;

        @Expose
        @Accordion
        @ConfigOption(name = "Custom Scoreboard", desc = "Your own sidebar: pick the lines and their order, background, title and footer, like j10a1n15's Custom Scoreboard.")
        public com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard customScoreboard = new com.epic60869.sky2m.features.FeatureConfigs.CustomScoreboard();

        @Expose
        @Accordion
        @ConfigOption(name = "Tab List Extras", desc = "A compact tab list (SkyHanni) and a tab widgets HUD (Skyblocker).")
        public com.epic60869.sky2m.features.qol.QolConfigs.TabListExtras tabListExtras = new com.epic60869.sky2m.features.qol.QolConfigs.TabListExtras();

        @Expose
        @Accordion
        @ConfigOption(name = "Status Bars", desc = "Health, mana, defense, XP and speed as movable bars (Skyblocker, SkyHanni).")
        public com.epic60869.sky2m.features.qol.QolConfigs.StatusBars statusBars = new com.epic60869.sky2m.features.qol.QolConfigs.StatusBars();

        @Expose
        @Accordion
        @ConfigOption(name = "Server Info Display", desc = "FPS, TPS, ping and time HUD (Skysoft's).")
        public com.epic60869.sky2m.features.FeatureConfigs.ServerInfoDisplay serverInfoDisplay = new com.epic60869.sky2m.features.FeatureConfigs.ServerInfoDisplay();

        @Expose
        @Accordion
        @ConfigOption(name = "Speed Display", desc = "SkyblockAddons' Speed Percentage: your speed as a percentage on a HUD.")
        public SpeedDisplay speedDisplay = new SpeedDisplay();

        @Expose
        @Accordion
        @ConfigOption(name = "Inventory HUD", desc = "Your inventory as a HUD you can move and resize in /s2 gui (Killer560's).")
        public com.epic60869.sky2m.features.FeatureConfigs.InventoryHud inventoryHud = new com.epic60869.sky2m.features.FeatureConfigs.InventoryHud();

        @Expose
        @Accordion
        @ConfigOption(name = "Radial Menu", desc = "Hold a key for a wheel of commands (Skyblocker).")
        public com.epic60869.sky2m.features.qol.QolConfigs.RadialMenu radialMenu = new com.epic60869.sky2m.features.qol.QolConfigs.RadialMenu();

        @Expose
        @Accordion
        @ConfigOption(name = "Custom Crosshair", desc = "Design your own crosshair, or import a Valorant or CS2 crosshair code or a picture (Killer560's).")
        public com.epic60869.sky2m.features.FeatureConfigs.Crosshair crosshair = new com.epic60869.sky2m.features.FeatureConfigs.Crosshair();

        @Expose
        @Accordion
        @ConfigOption(name = "More Slot Labels", desc = "Pet, attribute shard, minion, collection and bestiary levels on slots (SkyHanni).")
        public com.epic60869.sky2m.features.qol.QolConfigs.SlotLabels slotLabels = new com.epic60869.sky2m.features.qol.QolConfigs.SlotLabels();
    }

    public static final class Inventory {
        // Sub-category in the sidebar (shown under Inventory when it's open), like SkyHanni's.
        @Expose
        @Category(name = "Bazaar", desc = "Notifications for your Bazaar orders.")
        public BazaarNotificationsSettings bazaar = new BazaarNotificationsSettings();

        @ConfigOption(name = "Items & Tooltips", desc = "Prices, museum and accessory info, enchants, rarity backgrounds and other item tooltips and labels.")
        @ConfigEditorAccordion(id = 2301)
        public boolean itemsTooltipsGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Item Price Tooltip", desc = "Add prices to SkyBlock item tooltips, like Skyblocker, and what you paid for items you bought on the auction house.")
        @ConfigAccordionId(id = 2301)
        public PriceTooltip priceTooltip = new PriceTooltip();

        @Expose
        @Accordion
        @ConfigOption(name = "Economy", desc = "Item value breakdown, Bazaar best sell method and daily sales, trade value, bits, reforge and anvil helpers.")
        @ConfigAccordionId(id = 2301)
        public com.epic60869.sky2m.features.economy.EconomyConfig economy = new com.epic60869.sky2m.features.economy.EconomyConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Museum & Accessory Tooltips", desc = "Show in item tooltips whether you've donated the item to your museum and whether you're missing an accessory, like Skyblocker.")
        @ConfigAccordionId(id = 2301)
        public CollectionTooltips collectionTooltips = new CollectionTooltips();

        @Expose
        @Accordion
        @ConfigOption(name = "Enchant Parsing", desc = "SkyHanni's enchant parsing: enchants in tooltips coloured by level (perfect enchants in chroma), sorted, and laid out normal, compressed or stacked.")
        @ConfigAccordionId(id = 2301)
        public com.epic60869.sky2m.features.misc.EnchantParsingConfig enchantParsing = new com.epic60869.sky2m.features.misc.EnchantParsingConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Item Rarity", desc = "Rarity-coloured backgrounds behind SkyBlock items.")
        @ConfigAccordionId(id = 2301)
        public ItemRarity itemRarity = new ItemRarity();

        @Expose
        @ConfigOption(name = "Enchanted Book Labels", desc = "Two letters in the top right of enchanted books naming their enchantment (Sh for Sharpness, UW for Ultimate Wise, in pink for Ultimate enchantments), so you can tell them apart without hovering. Books with several enchantments aren't labelled.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2301)
        public boolean enchantedBookLabels = true;

        @Expose
        @ConfigOption(name = "Revert Master Stars", desc = "Show Master Stars in item names the old way: that many of the item's stars in red, instead of Hypixel's new numbered star after them (Killer560's).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2301)
        public boolean revertMasterStars = false;

        @Expose
        @ConfigOption(name = "Pack Disabler", desc = "Draw SkyBlock items the way they looked before Hypixel's 2026 resource pack: their old vanilla item (shown by your own resource packs) or old head skin, and new textures for items that never had one. Hypixel's pack still loads, only item looks change. Off when Noamm's PackDisabler is installed (Killer560's).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2301)
        public boolean packDisabler = false;

        @Expose
        @ConfigOption(name = "Shard Level Up Highlight", desc = "Highlight attribute shards you have enough of to level up or unlock, in the Hunting Box and the Attribute Menu. The Attribute Menu uses the amounts last seen in your Hunting Box (remembered between sessions), so open it once first.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2301)
        public boolean shardLevelUpHighlight = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Ability Timers", desc = "Countdowns you start with a key when you use an ability, in a HUD (Killer560's).")
        @ConfigAccordionId(id = 2301)
        public com.epic60869.sky2m.features.FeatureConfigs.AbilityTimers abilityTimers = new com.epic60869.sky2m.features.FeatureConfigs.AbilityTimers();

        @Expose
        @Accordion
        @ConfigOption(name = "Item Quality", desc = "Dungeon item quality (base stat boost) and floor on the slot and in the tooltip, like SkyblockAddons.")
        @ConfigAccordionId(id = 2301)
        public com.epic60869.sky2m.features.FeatureConfigs.ItemQuality itemQuality = new com.epic60869.sky2m.features.FeatureConfigs.ItemQuality();

        @Expose
        @Accordion
        @ConfigOption(name = "Item Cooldowns", desc = "Show item ability cooldowns on the item's slot (and optionally a HUD).")
        @ConfigAccordionId(id = 2301)
        public com.epic60869.sky2m.features.sbc.SbcConfig.ItemCooldowns itemCooldowns = new com.epic60869.sky2m.features.sbc.SbcConfig.ItemCooldowns();

        @Expose
        @Accordion
        @ConfigOption(name = "Tooltip Scroll", desc = "Move tooltips with the mouse wheel and keys so long tooltips can be read (Skysoft's Tooltip Scroll). Off automatically when Skysoft is installed.")
        @ConfigAccordionId(id = 2301)
        public TooltipScroll tooltipScroll = new TooltipScroll();

        @Expose
        @ConfigOption(name = "Calendar Time to Real Time", desc = "When enabled, hovering a SkyBlock calendar date adds the equivalent real-world date and time in your computer's local time zone.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2301)
        public boolean calendarTimeToRealTime = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Item Custom", desc = "Item and armor customization settings.")
        @ConfigAccordionId(id = 2301)
        public ItemCustom itemCustom = new ItemCustom();

        @ConfigOption(name = "Inventory & Storage", desc = "Storage overlay, inventory buttons, slot binding, wardrobe hotkeys and other menu helpers.")
        @ConfigEditorAccordion(id = 2302)
        public boolean inventoryStorageGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Storage Overlay", desc = "Firmament's storage overlay: /storage, your Ender Chest pages and backpacks as one scrollable view, and its layout and search options.")
        @ConfigAccordionId(id = 2302)
        public StorageOverlaySettings storageOverlaySettings = new StorageOverlaySettings();

        @Expose
        @Accordion
        @ConfigOption(name = "Inventory Buttons", desc = "Quick action buttons with custom icons and commands on inventory/container screens.")
        @ConfigAccordionId(id = 2302)
        public InventoryButtonsSettings inventoryButtons = new InventoryButtonsSettings();

        @Expose
        @Accordion
        @ConfigOption(name = "Item Protect & Slot Binding", desc = "Protect held items from dropping with /s2 protect, and bind hotbar slots to inventory slots.")
        @ConfigAccordionId(id = 2302)
        public SlotLocking slotLocking = new SlotLocking();

        @Expose
        @Accordion
        @ConfigOption(name = "Wardrobe & Loadout Hotkeys", desc = "Equip Wardrobe or Loadout slots on the open page with configurable keys, like SkyHanni.")
        @ConfigAccordionId(id = 2302)
        public com.epic60869.sky2m.features.FeatureConfigs.WardrobeHotkeys wardrobeHotkeys = new com.epic60869.sky2m.features.FeatureConfigs.WardrobeHotkeys();

        @Expose
        @Accordion
        @ConfigOption(name = "Loadout Highlight", desc = "SkyHanni's loadout highlighting: the loadout you have equipped is highlighted in the Loadouts menu.")
        @ConfigAccordionId(id = 2302)
        public com.epic60869.sky2m.features.FeatureConfigs.LoadoutHighlight loadoutHighlight = new com.epic60869.sky2m.features.FeatureConfigs.LoadoutHighlight();

        @Expose
        @Accordion
        @ConfigOption(name = "Mouse Reset", desc = "Reset the mouse cursor when selected SkyBlock menus open.")
        @ConfigAccordionId(id = 2302)
        public MouseReset mouseReset = new MouseReset();

        @Expose
        @Accordion
        @ConfigOption(name = "Experimental Table", desc = "Experimentation table solvers: Chronomatron, Superpairs and Ultrasequencer.")
        @ConfigAccordionId(id = 2302)
        public com.epic60869.sky2m.features.FeatureConfigs.Enchanting experimentalTable = new com.epic60869.sky2m.features.FeatureConfigs.Enchanting();

        @Expose
        @Accordion
        @ConfigOption(name = "Item List", desc = "Every SkyBlock item beside your inventory, with recipes, usages, search, a calculator and favourites, like SkyBlock Item List.")
        @ConfigAccordionId(id = 2302)
        public com.epic60869.sky2m.features.FeatureConfigs.ItemList itemList = new com.epic60869.sky2m.features.FeatureConfigs.ItemList();

        @Expose
        @ConfigOption(name = "Sign Calculator", desc = "On SkyBlock's number signs (auction prices, bazaar amounts, ...) show what you typed as a number above the sign, e.g. 15m = 15,000,000, and send that number, so sums like 2.5m*3 work. Suffixes: k, m, b, s (64), e (160).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2302)
        public boolean signCalculator = false;

        @Expose
        @ConfigOption(name = "Enter Confirms Signs", desc = "Pressing Enter in a sign (an auction price, a bazaar amount, a search) sends it, like clicking Done, instead of moving to the next line.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2302)
        public boolean signEnterConfirms = true;

        @Expose
        @ConfigOption(name = "Bazaar & AH Search", desc = "From Skyblocker: clicking Search in the Bazaar or Auction House opens a search box instead of the sign, suggesting what's sold there as you type (bazaar items and enchantments, or auction items and pets) and your last searches. In the Auction House it can search for max level pets or dungeon items with stars. /ahs and /bzs open it from anywhere.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2302)
        public boolean searchOverlay = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Sign Autofill", desc = "Bazaar and Auction House price/amount signs start filled in with a suggestion (outbid/undercut prices, your last amount, the lowest BIN minus an undercut), selected so you just press Done or type over it. Never sent for you.")
        @ConfigAccordionId(id = 2302)
        public com.epic60869.sky2m.features.FeatureConfigs.SignAutofill signAutofill = new com.epic60869.sky2m.features.FeatureConfigs.SignAutofill();

        @Expose
        @Accordion
        @ConfigOption(name = "Auction Price Guard", desc = "Stop a click that would list an item far too cheap or buy one far too dear on the auction house, until you click again.")
        @ConfigAccordionId(id = 2302)
        public com.epic60869.sky2m.features.FeatureConfigs.AuctionGuard auctionGuard = new com.epic60869.sky2m.features.FeatureConfigs.AuctionGuard();

        @Expose
        @ConfigOption(name = "Highlight Auctions", desc = "In Manage Auctions, your sold auctions are green and expired ones red (SkyHanni).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2302)
        public boolean highlightAuctions = true;

        @Expose
        @ConfigOption(name = "Highlight Underbid BINs", desc = "Also highlight your BIN auctions in Manage Auctions, in orange, when their price is above the item's estimated value.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2302)
        public boolean highlightAuctionsUnderbid = false;

        @ConfigOption(name = "Sacks & Item Tracking", desc = "Sacks value, the item pickup log and item notifications.")
        @ConfigEditorAccordion(id = 2310)
        public boolean itemTrackingGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Sacks Value", desc = "What everything in your sacks is worth, on a HUD, kept right through bazaar insta-sells without reopening your sacks (SkyHanni's Sack Display value).")
        @ConfigAccordionId(id = 2310)
        public com.epic60869.sky2m.features.FeatureConfigs.SacksValue sacksValue = new com.epic60869.sky2m.features.FeatureConfigs.SacksValue();

        @Expose
        @Accordion
        @ConfigOption(name = "Item Pickup Log", desc = "SkyHanni's Item Pickup Log: what goes into and out of your inventory, sacks, Hunting Box and purse.")
        @ConfigAccordionId(id = 2310)
        public com.epic60869.sky2m.features.FeatureConfigs.ItemPickupLog itemPickupLog = new com.epic60869.sky2m.features.FeatureConfigs.ItemPickupLog();

        @Expose
        @Accordion
        @ConfigOption(name = "Item Notification", desc = "Show items from your list on a HUD when they go into your sacks or inventory (SkyOcean's Sack Notification as a HUD).")
        @ConfigAccordionId(id = 2310)
        public com.epic60869.sky2m.features.FeatureConfigs.ItemNotification itemNotification = new com.epic60869.sky2m.features.FeatureConfigs.ItemNotification();
    }

    public static final class Chat {
        @Expose
        @Accordion
        @ConfigOption(name = "Custom Chat", desc = "Control how Sky2M command output from other players appears in chat.")
        public CustomChat customChat = new CustomChat();

        @ConfigOption(name = "Chat Tweaks", desc = "Compact chat, the current chat display, copying messages and stash messages.")
        @ConfigEditorAccordion(id = 201)
        public boolean chatTweaksGroup = false;

        @Expose
        @ConfigOption(name = "Compact Chat", desc = "Compact repeated chat messages into one message with an occurrence counter.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 201)
        public boolean compactChat = false;

        @Expose
        @ConfigOption(name = "Current Chat Display", desc = "Show which chat you are typing in (All, Party, Guild, Officer, Co-op, a private conversation or Sky2M chat) just above the chat box while it is open.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 201)
        public boolean currentChatDisplay = false;

        @Expose
        @ConfigOption(name = "Compact Bestiary", desc = "SkyHanni's compact Bestiary messages: Hypixel's BESTIARY level-up block becomes one line (\"BESTIARY Zombie II➡III\"); hover it for the rewards, click it to open the Bestiary. Milestones get their own line.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 201)
        public boolean compactBestiary = true;

        @Expose
        @ConfigOption(name = "SkyBlock XP in Chat", desc = "SkyHanni's SkyBlock XP in Chat: also send the \"+5 SkyBlock XP (Accessories) (35/100)\" shown in the action bar to chat.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 201)
        public boolean skyBlockXpInChat = true;

        @Expose
        @ConfigOption(name = "Pet Rarity", desc = "Say a pet's rarity in Hypixel's pet drop lines, in its colour: \"PET DROP! Epic Slug\" (purple), \"Legendary Slug\" (gold). Also for pets in RARE DROP! and fishing catch lines, and Sky2M's +Drop message.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 201)
        public boolean petRarity = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Copy Chat", desc = "Right-click chat messages to copy them, like NoFrills' Chat Tweaks.")
        @ConfigAccordionId(id = 201)
        public CopyChat copyChat = new CopyChat();

        @Expose
        @Accordion
        @ConfigOption(name = "Stash Messages", desc = "SkyHanni's compact stash warnings: Hypixel's stash block as one clickable line, without repeats.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.misc.StashCompact.Config stashMessages = new com.epic60869.sky2m.features.misc.StashCompact.Config();

        @Expose
        @Accordion
        @ConfigOption(name = "Translator", desc = "SkyHanni's chat translator: click a player's message to translate it, and /s2 translate for your own.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.FeatureConfigs.Translator translator = new com.epic60869.sky2m.features.FeatureConfigs.Translator();

        @Expose
        @Accordion
        @ConfigOption(name = "Chat Tabs, Search & Timestamps", desc = "All, Party, Guild and Private tabs, searching your chat, and the time on each message.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.FeatureConfigs.ChatTabs chatTabs = new com.epic60869.sky2m.features.FeatureConfigs.ChatTabs();

        @Expose
        @Accordion
        @ConfigOption(name = "Chat Filters", desc = "Hide lobby joins, ads, Watchdog lines, warping lines, Mystery Boxes, ability spam and your own filters.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.FeatureConfigs.ChatFilters filters = new com.epic60869.sky2m.features.FeatureConfigs.ChatFilters();

        @Expose
        @Accordion
        @ConfigOption(name = "Spam Filters", desc = "SkyHanni's chat filters: hide lobby, warping, profile, Guild EXP, Kill Combo, Bazaar/AH, slayer, fire sale, event and other spam, or move it to the Side Chat.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.misc.SpamFilters.Config spamFilters = new com.epic60869.sky2m.features.misc.SpamFilters.Config();

        @Expose
        @Accordion
        @ConfigOption(name = "Side Chat", desc = "A second, movable chat window (/s2 gui) for messages moved out of the main chat: Pristine procs and the spam filters set to Move to Side Chat.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.misc.SideChat.Config sideChat = new com.epic60869.sky2m.features.misc.SideChat.Config();

        @Expose
        @Accordion
        @ConfigOption(name = "Chat Rules & Player Format", desc = "Your own hide/replace/notify chat rules, leaving the level, rank or emblem out of player chat messages, and holding a key to peek at the whole chat.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.qol.ChatRules.Config chatRules = new com.epic60869.sky2m.features.qol.ChatRules.Config();

        @Expose
        @Accordion
        @ConfigOption(name = "Mentions", desc = "Highlight and a sound when your name or keywords appear in chat.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.qol.MentionAlerts.Config mentions = new com.epic60869.sky2m.features.qol.MentionAlerts.Config();

        @Expose
        @ConfigOption(name = "Keep Chat Scroll Position", desc = "While you are scrolled up in chat, new messages, compacted repeats and chat tabs don't move what you are reading. Chat goes back to the bottom when you scroll down to it, close chat or send a message.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 201)
        public boolean keepScrollPosition = true;

        @Expose
        @ConfigOption(name = "Clickable Names", desc = "Click a player's name on their chat message (all, party, guild, co-op and private chat) to open their profile in the Sky2M profile viewer. This replaces Hypixel's own click on the name.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 201)
        public boolean clickableNames = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Social Tools", desc = "Party shortcut commands (/rp, /pa, /pback, /pkick), waypoints from coordinates in chat, and profile icons next to chat names.")
        @ConfigAccordionId(id = 201)
        public com.epic60869.sky2m.features.social.SocialConfig social = new com.epic60869.sky2m.features.social.SocialConfig();

        @ConfigOption(name = "Emojis", desc = "Emoji shortcodes, emoji autocomplete and SkyBlock item emojis in chat.")
        @ConfigEditorAccordion(id = 202)
        public boolean emojisGroup = false;

        @Expose
        @ConfigOption(name = "Chat Emoji", desc = "Replace :emoji: shortcodes with Sky2M emoji sprites and provide emoji autocomplete while typing chat.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 202)
        public boolean chatEmoji = false;

        @Expose
        @ConfigOption(name = "Emoji Autocomplete", desc = "Suggest emojis (with a picture of each) while you type :name in chat. Turn off to hide the emoji suggestions.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 202)
        public boolean emojiAutocomplete = true;

        @Expose
        @ConfigOption(name = "Hypixel Item Emojis", desc = "In any chat (all, party, guild, private, Sky2M), :item_id: shows that SkyBlock item's icon, like the SkyHelper Discord: :summoning_eye:, :hyperion:, :enchanted_diamond:. Client side: everyone with Sky2M sees the icon, others see the text. Hover the icon for the item's name.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 202)
        public boolean itemEmojis = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Command Autofill", desc = "Suggestions for Hypixel commands the server doesn't suggest: Bazaar products for /bz (enchanted_diamond, enchanted_diamond_block...), items for /ahs and item ids for /viewrecipe. Tab completes, like vanilla command suggestions.")
        public com.epic60869.sky2m.features.misc.CommandAutofill.Config commandAutofill = new com.epic60869.sky2m.features.misc.CommandAutofill.Config();
    }

    public static final class Farming {

        @ConfigOption(name = "Garden", desc = "Garden HUDs, pests and visitors.")
        @ConfigEditorAccordion(id = 801)
        public boolean gardenGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Garden", desc = "Yaw/pitch, pest cooldown, blocks per second and special drop animations.")
        @ConfigAccordionId(id = 801)
        public com.epic60869.sky2m.features.FeatureConfigs.Garden garden = new com.epic60869.sky2m.features.FeatureConfigs.Garden();

        @Expose
        @Accordion
        @ConfigOption(name = "Pest Highlight", desc = "Outline pests in the Garden, with an optional line or beacon to the nearest one and a pests/plots HUD.")
        @ConfigAccordionId(id = 801)
        public com.epic60869.sky2m.features.sbc.SbcConfig.PestHighlight pestHighlight = new com.epic60869.sky2m.features.sbc.SbcConfig.PestHighlight();

        @Expose
        @Accordion
        @ConfigOption(name = "Visitors", desc = "SkyHanni's garden visitor features: shopping list, visitor timer, offer prices, reward warning, status highlights, chat options, drop statistics and charmed visitors.")
        @ConfigAccordionId(id = 801)
        public com.epic60869.sky2m.features.garden.visitor.VisitorConfig visitors = new com.epic60869.sky2m.features.garden.visitor.VisitorConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "SkyHanni Farming", desc = "SkyHanni's farming HUD (crop milestones, crops per minute, coins per hour, Garden level), Jacob's Contest, true Farming Fortune, optimal angles and speed, plot borders, pest traps, pest profit and spray display.")
        @ConfigAccordionId(id = 801)
        public com.epic60869.sky2m.features.garden.skyhanni.SkyHanniGardenConfig skyhanniGarden = new com.epic60869.sky2m.features.garden.skyhanni.SkyHanniGardenConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Crop Fever Effects", desc = "SkyOcean's crop fever effects: a jackpot sound, music, the screen cycling through colours and coins raining down while you have the CROP FEVER.")
        @ConfigAccordionId(id = 801)
        public com.epic60869.sky2m.features.FeatureConfigs.CropFever cropFever = new com.epic60869.sky2m.features.FeatureConfigs.CropFever();

        @Expose
        @Accordion
        @ConfigOption(name = "Garden Helpers", desc = "SkyHanni's composter overlay, Anita shop profit, DNA Analyzer and Phantomleaf solvers, Stereo Harmony and Atmospheric Filter displays and farming weight display.")
        @ConfigAccordionId(id = 801)
        public com.epic60869.sky2m.features.garden.helpers.HelperConfig gardenHelpers = new com.epic60869.sky2m.features.garden.helpers.HelperConfig();

        @ConfigOption(name = "Farming Tools", desc = "The farming RNG HUD and mouse lock.")
        @ConfigEditorAccordion(id = 802)
        public boolean farmingToolsGroup = false;
        @Expose
        @Accordion
        @ConfigOption(name = "Farming RNG HUD", desc = "Click to expand the farming RNG HUD options.")
        @ConfigAccordionId(id = 802)
        public FarmingRng rng = new FarmingRng();

        @Expose
        @Accordion
        @ConfigOption(name = "Mouse Lock", desc = "Fully lock the camera while holding a farming tool.")
        @ConfigAccordionId(id = 802)
        public MouseLock mouseLock = new MouseLock();

    }

    public static final class Mining {
        @Expose
        @Accordion
        @ConfigOption(name = "Mining Commissions", desc = "Show and configure the Mining Commission HUD.")
        public MiningCommissions commissions = new MiningCommissions();

        @Expose
        @Accordion
        @ConfigOption(name = "Mining Features", desc = "Crystal Hollows map, Divan tools alert and mineshaft timer.")
        public com.epic60869.sky2m.features.FeatureConfigs.MiningFeatures features = new com.epic60869.sky2m.features.FeatureConfigs.MiningFeatures();

        @Expose
        @Accordion
        @ConfigOption(name = "Scatha Tracker", desc = "Worm and Scatha spawns, Scatha rate and pets in the Crystal Hollows.")
        public com.epic60869.sky2m.features.FeatureConfigs.ScathaTracker scatha = new com.epic60869.sky2m.features.FeatureConfigs.ScathaTracker();

        @Expose
        @Accordion
        @ConfigOption(name = "Gemstone Tracker", desc = "Gemstones mined per type with their value and rate, pristine procs and Glossy Gemstones from the Mining Fiesta.")
        public com.epic60869.sky2m.features.FeatureConfigs.GemstoneTracker gemstones = new com.epic60869.sky2m.features.FeatureConfigs.GemstoneTracker();

        @Expose
        @Accordion
        @ConfigOption(name = "Dwarven Mines Solvers", desc = "Fetchur, Puzzler and the Mines of Divan Metal Detector.")
        public com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Solvers dwarvenSolvers = new com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Solvers();

        @Expose
        @Accordion
        @ConfigOption(name = "Crystal Nucleus Tracker", desc = "Your Crystal Nucleus runs and their loot.")
        public com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Nucleus nucleus = new com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Nucleus();

        @Expose
        @Accordion
        @ConfigOption(name = "Glacite Cold", desc = "Cold overlay, cold alert and Ascension Rope reminder.")
        public com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Glacite glaciteCold = new com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Glacite();

        @Expose
        @Accordion
        @ConfigOption(name = "Precision Mining & Flowstate", desc = "Precision Mining highlighter and the Flowstate helper with personal best.")
        public com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Precision precision = new com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Precision();

        @Expose
        @Accordion
        @ConfigOption(name = "Heart of the Mountain & Forest", desc = "HOTM and HOTF perk levels on the icons, powder cost tooltips and perk highlights.")
        public com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Hotx hotx = new com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Hotx();

        @Expose
        @Accordion
        @ConfigOption(name = "Mining Events", desc = "Mining events HUD and alerts for events, goblins, scrap and mineshafts.")
        public com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Events miningEvents = new com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Events();

        @Expose
        @Accordion
        @ConfigOption(name = "Retextures", desc = "Retextured blocks of the mining islands.")
        public com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Retexture retexture = new com.epic60869.sky2m.features.mining.dwarven.DwarvenConfig.Retexture();

        @Expose
        @Accordion
        @ConfigOption(name = "Glacite Mineshaft", desc = "Corpse finder and profit, entrance and ladder waypoints, cave-in timer and pity.")
        public com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.GlaciteMineshaft glaciteMineshaft = new com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.GlaciteMineshaft();

        @Expose
        @Accordion
        @ConfigOption(name = "Mineshaft Stats", desc = "Time in the current Glacite Mineshaft and blocks broken in it.")
        public com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.MineshaftStats mineshaftStats = new com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.MineshaftStats();

        @Expose
        @Accordion
        @ConfigOption(name = "Powder Tracker", desc = "Mithril, Gemstone and Glacite powder gained this session and per hour.")
        public com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.PowderTracker powderTracker = new com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.PowderTracker();

        @Expose
        @Accordion
        @ConfigOption(name = "Fossil Excavator", desc = "Excavator solver and excavation profit tracker in the Fossil Research Center.")
        public com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.FossilExcavator fossilExcavator = new com.epic60869.sky2m.features.mining.extras.MiningExtrasConfig.FossilExcavator();

        @Expose
        @Accordion
        @ConfigOption(name = "Ordered Waypoints", desc = "SkyHanni's ordered waypoints (/sho): routes that show one waypoint after another.")
        public com.epic60869.sky2m.features.FeatureConfigs.OrderedWaypoints orderedWaypoints = new com.epic60869.sky2m.features.FeatureConfigs.OrderedWaypoints();
    }

    public static final class Slayers {
        // Sub-categories in the sidebar (shown under Slayers when it's open), like SkyHanni's.
        @Expose
        @Category(name = "Blaze", desc = "Inferno Demonlord helpers.")
        public com.epic60869.sky2m.features.FeatureConfigs.BlazeSlayer blaze = new com.epic60869.sky2m.features.FeatureConfigs.BlazeSlayer();

        @Expose
        @Category(name = "Enderman", desc = "Voidgloom Seraph helpers.")
        public com.epic60869.sky2m.features.FeatureConfigs.EndermanSlayer enderman = new com.epic60869.sky2m.features.FeatureConfigs.EndermanSlayer();

        @Expose
        @Category(name = "Vampire", desc = "Riftstalker Bloodfiend helpers.")
        public com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.VampireSlayer vampire = new com.epic60869.sky2m.features.combat.extra.CombatExtraConfig.VampireSlayer();

        @ConfigOption(name = "Boss Fight", desc = "Boss phase HUD, boss and miniboss highlights, egg hits and the miniboss alert.")
        @ConfigEditorAccordion(id = 501)
        public boolean bossFightGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Slayer HUDs", desc = "Slayer boss phase HUD.")
        @ConfigAccordionId(id = 501)
        public com.epic60869.sky2m.features.FeatureConfigs.Slayer huds = new com.epic60869.sky2m.features.FeatureConfigs.Slayer();

        @Expose
        @Accordion
        @ConfigOption(name = "Boss Highlight", desc = "Box your slayer boss and your minibosses (Skysoft's).")
        @ConfigAccordionId(id = 501)
        public com.epic60869.sky2m.features.FeatureConfigs.SlayerTargetHighlight targetHighlight = new com.epic60869.sky2m.features.FeatureConfigs.SlayerTargetHighlight();

        @Expose
        @ConfigOption(name = "Miniboss Alert", desc = "A title and a ding when one of your slayer minibosses spawns (Hypixel's \"SLAYER MINI-BOSS ... has spawned!\"), not other players' minibosses.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 501)
        public boolean minibossAlert = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Boss Spawn Alert", desc = "A title and a loud sound when your slayer boss spawns, even off-screen, so it isn't missed while AFK.")
        @ConfigAccordionId(id = 501)
        public com.epic60869.sky2m.features.FeatureConfigs.BossSpawnAlert bossSpawnAlert = new com.epic60869.sky2m.features.FeatureConfigs.BossSpawnAlert();

        @Expose
        @Accordion
        @ConfigOption(name = "Egg Hits Display", desc = "Hits each Tarantula egg sack still needs, shown on the egg (NoFrills').")
        @ConfigAccordionId(id = 501)
        public com.epic60869.sky2m.features.FeatureConfigs.EggHitsDisplay eggHitsDisplay = new com.epic60869.sky2m.features.FeatureConfigs.EggHitsDisplay();

        @Expose
        @Accordion
        @ConfigOption(name = "Phase Timer", desc = "How long each hit phase and damage phase of your boss took.")
        @ConfigAccordionId(id = 501)
        public com.epic60869.sky2m.features.FeatureConfigs.SlayerPhaseTimer phaseTimer = new com.epic60869.sky2m.features.FeatureConfigs.SlayerPhaseTimer();

        @ConfigOption(name = "Kill Times", desc = "Kill times, personal bests and the PB leaderboard HUD.")
        @ConfigEditorAccordion(id = 502)
        public boolean killTimesGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Personal Best", desc = "Slayer kill times, personal bests and quest times in chat.")
        @ConfigAccordionId(id = 502)
        public PersonalBest personalBest = new PersonalBest();

        @Expose
        @Accordion
        @ConfigOption(name = "PB Leaderboard HUD", desc = "A HUD with the Sky2M kill time leaderboard for your slayer boss, and a live timer against your PB and the #1 time. Move it in /s2 hud.")
        @ConfigAccordionId(id = 502)
        public PbLeaderboardHud pbLeaderboardHud = new PbLeaderboardHud();

        @ConfigOption(name = "Loot & Profit", desc = "Boss profit, RNG meter value and kills since your last rare drop.")
        @ConfigEditorAccordion(id = 503)
        public boolean lootProfitGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Boss Profit", desc = "Profit from each slayer boss: its drops and their value, minus the quest's cost, in chat and in a HUD.")
        @ConfigAccordionId(id = 503)
        public BossProfit bossProfit = new BossProfit();

        @Expose
        @ConfigOption(name = "RNG Meter Value", desc = "In an RNG Meter menu, show each drop's price and how many coins one Slayer XP (or dungeon Score) of meter progress is worth.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 503)
        public boolean rngMeterValue = false;

        @Expose
        @Accordion
        @ConfigOption(name = "RNG Meter Display", desc = "A HUD with your quest slayer's selected RNG Meter drop, its progress and the bosses left (SkyHanni's).")
        @ConfigAccordionId(id = 503)
        public com.epic60869.sky2m.features.FeatureConfigs.SlayerRngMeterDisplay rngMeterDisplay = new com.epic60869.sky2m.features.FeatureConfigs.SlayerRngMeterDisplay();

        @Expose
        @Accordion
        @ConfigOption(name = "RNG Meter Advisor", desc = "A HUD naming the slayer RNG meter closest to dropping its selected item, and /s2 rngadvice.")
        @ConfigAccordionId(id = 503)
        public com.epic60869.sky2m.features.slayer.SlayerRngAdvisor.Config rngAdvisor = new com.epic60869.sky2m.features.slayer.SlayerRngAdvisor.Config();

        @Expose
        @ConfigOption(name = "Kills Since Rare Drop", desc = "Show the kills-since-drop counter.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 503)
        public boolean killsSinceDrop = false;
    }

    public static final class PbLeaderboardHud {
        @Expose
        @ConfigOption(name = "PB Leaderboard HUD", desc = "Show the slayer PB leaderboard HUD.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "PB Leaderboard Rows", desc = "How many of the fastest times the HUD lists.")
        @ConfigEditorSlider(minValue = 3, maxValue = 10, minStep = 1)
        public int rows = 5;

        @Expose
        @ConfigOption(name = "PB Leaderboard: Show", desc = "When the HUD shows.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.slayer.SlayerPbHud.Show show = com.epic60869.sky2m.features.slayer.SlayerPbHud.Show.QUEST;

        @Expose
        @ConfigOption(name = "PB Leaderboard Boss", desc = "Leave empty (or Auto) for your current or last slayer boss, or pin one, e.g. Revenant Horror V or Voidgloom Seraph IV.")
        @ConfigEditorText
        public String boss = "Auto";
    }

    public static final class BossProfit {
        @Expose
        @ConfigOption(name = "Chat Message", desc = "After each slayer boss, \"Profit: +50k\" in chat: hover it for each drop and its value, the drops' total, the quest's cost and what's left. It goes out once the boss's drops have landed on the ground (or Hypixel's [Sacks] message with them comes); the HUD shows straight away.")
        @ConfigEditorBoolean
        public boolean chat = false;

        @Expose
        @ConfigOption(name = "HUD", desc = "When a boss dies, its profit (+84.2k or -15.0k) on screen, then it hides until the next boss. Move it in /s2 hud.")
        @ConfigEditorBoolean
        public boolean hud = false;

        @Expose
        @ConfigOption(name = "HUD Time (seconds)", desc = "How long the HUD stays up once the boss's drops are in, before it hides until the next boss.")
        @ConfigEditorSlider(minValue = 1, maxValue = 60, minStep = 1)
        public int hudHideSeconds = 2;

        @Expose
        @ConfigOption(name = "HUD Drops", desc = "How many of the most valuable drops the HUD lists under the profit (0: just the profit).")
        @ConfigEditorSlider(minValue = 0, maxValue = 10, minStep = 1)
        public int hudDrops = 0;
    }

    public static final class PersonalBest {
        @Expose
        @ConfigOption(name = "Time to Kill", desc = "Say in chat how long your slayer boss took to kill.")
        @ConfigEditorBoolean
        public boolean timeToKill = false;

        @Expose
        @ConfigOption(name = "Time to Kill Personal Bests", desc = "Your fastest kill of that boss and tier, in brackets after the kill time: (PB: 30.10s), or (NEW PERSONAL BEST!) when you beat it. Saved per Minecraft account.")
        @ConfigEditorBoolean
        public boolean personalBests = false;

        @Expose
        @ConfigOption(name = "Quest Complete", desc = "Say in chat how long the whole slayer quest (spawn and kill) took.")
        @ConfigEditorBoolean
        public boolean questComplete = false;

        @Expose
        @ConfigOption(name = "Compact Time Messages", desc = "Shorter Time to Kill, Personal Best and Quest Complete messages.")
        @ConfigEditorBoolean
        public boolean compactTimes = false;

        @Expose
        @ConfigOption(name = "Share Slayer PBs", desc = "Send your slayer kill time personal bests to the Sky2M leaderboard (/s2 leaderboard slayer). When off, nothing is sent.")
        @ConfigEditorBoolean
        public boolean sharePbs = true;
    }

    public static final class Pets {
        @Expose
        @Accordion
        @ConfigOption(name = "Pets Display", desc = "Pet display, overflow XP and positioning.")
        public PetDisplay display = new PetDisplay();

        @ConfigOption(name = "Pets Menu", desc = "Highlights and levels in the Pets menu.")
        @ConfigEditorAccordion(id = 2101)
        public boolean petsMenuGroup = false;

        @Expose
        @ConfigOption(name = "Highlight Active Pet", desc = "Highlight your summoned pet in the Pets menu.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2101)
        public boolean highlightActive = true;

        @Expose
        @ConfigOption(name = "Active Pet Color", desc = "The color used to highlight your summoned pet in the Pets menu.")
        @ConfigEditorColour
        @ConfigAccordionId(id = 2101)
        public String activeColor = "0:170:85:255:85";

        @Expose
        @ConfigOption(name = "Show Pet Level", desc = "Show each pet's level on its slot in the Pets menu, so you don't have to hover over it. Maxed pets show it in gold.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2101)
        public boolean showLevel = true;

        @Expose
        @ConfigOption(name = "Show Overflow Pet Level", desc = "With Show Pet Level on, maxed pets show their overflow level past 100 (or 200) instead of just the max.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2101)
        public boolean showOverflowLevel = true;
    }

    public static final class Misc {
        @Expose
        @Category(name = "Pets", desc = "Pet displays and overflow XP.")
        public Pets pets = new Pets();

        @Expose
        @Category(name = "Profit Trackers", desc = "Session and per-activity profit.")
        public com.epic60869.sky2m.features.FeatureConfigs.ProfitTrackers profitTrackers = new com.epic60869.sky2m.features.FeatureConfigs.ProfitTrackers();

        @Expose
        @Category(name = "Clips", desc = "Save the last few seconds as a video.")
        public Clips clips = new Clips();

        @Expose
        @Category(name = "Fonts", desc = "Draw all text in Inter or a .ttf font of your own instead of Minecraft's font.")
        public Fonts fonts = new Fonts();

        @Expose
        @Accordion
        @ConfigOption(name = "Visual Tweaks", desc = "Fullbright, fog distance, hiding clouds, lightning and fire, dead and far-away mobs, other players' armor and particles (some per island), and a disconnect fix. Only changes your own screen.")
        public com.epic60869.sky2m.features.qol.VisualTweaks.Config visualTweaks = new com.epic60869.sky2m.features.qol.VisualTweaks.Config();

        @Expose
        @Accordion
        @ConfigOption(name = "SkyBlock Level Guide", desc = "Highlight tasks you have not finished in the SkyBlock Guide menus, like SkyHanni.")
        public com.epic60869.sky2m.features.qol.LevelGuide.Config levelGuide = new com.epic60869.sky2m.features.qol.LevelGuide.Config();

        @Expose
        @Accordion
        @ConfigOption(name = "Profile Viewer", desc = "/s2 pv <player>: anyone's SkyBlock profile, laid out like NEU's profile viewer.")
        public com.epic60869.sky2m.features.FeatureConfigs.ProfileViewer profileViewer = new com.epic60869.sky2m.features.FeatureConfigs.ProfileViewer();

        @ConfigOption(name = "HUDs", desc = "Coins per hour, toggle sprint, the event calendar, the recipe HUD and the GIF player.")
        @ConfigEditorAccordion(id = 2303)
        public boolean hudsGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Coins Per Hour", desc = "Purse graph HUD with slayer profit and powder goal lines.")
        @ConfigAccordionId(id = 2303)
        public CoinsChartSettings coinsChart = new CoinsChartSettings();

        @Expose
        @Accordion
        @ConfigOption(name = "Toggle Sprint", desc = "Always sprint, with a HUD while it's on.")
        @ConfigAccordionId(id = 2303)
        public ToggleSprintSettings toggleSprint = new ToggleSprintSettings();

        @Expose
        @Accordion
        @ConfigOption(name = "Event Calendar", desc = "Upcoming SkyBlock events with countdowns (/s2 calendar), a HUD and reminders before the events you pick.")
        @ConfigAccordionId(id = 2303)
        public com.epic60869.sky2m.features.sbc.SbcConfig.EventCalendar eventCalendar = new com.epic60869.sky2m.features.sbc.SbcConfig.EventCalendar();

        @ConfigOption(name = "Recipe HUD", desc = "The /s2 recipe HUD.")
        @ConfigEditorAccordion(id = 2304)
        @ConfigAccordionId(id = 2303)
        public boolean recipeHUDGroup = false;

        @Expose
        @ConfigOption(name = "Recipe HUD", desc = "While a /s2 recipe is selected, show a movable HUD with the item and the base ingredients you still need (like SkyOcean's craft helper overlay). Move it in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2304)
        public boolean recipeHud = false;

        @Expose
        @ConfigOption(name = "Recipe HUD Hide Completed", desc = "Hide ingredients you already have enough of in the Recipe HUD.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2304)
        public boolean recipeHudHideCompleted = false;

        @Expose
        @Accordion
        @ConfigOption(name = "GIF Player", desc = "GIFs from a folder playing on loop as HUDs (Killer560's).")
        @ConfigAccordionId(id = 2303)
        public com.epic60869.sky2m.features.FeatureConfigs.GifPlayer gifPlayer = new com.epic60869.sky2m.features.FeatureConfigs.GifPlayer();

        @ConfigOption(name = "Collections", desc = "The collection tracker HUD, the collection portfolio and the Rival HUD.")
        @ConfigEditorAccordion(id = 2305)
        public boolean collectionsGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Collection Tracker", desc = "A HUD with the collection you're gathering, what you've gained this session and per hour, and your Elite leaderboard rank.")
        @ConfigAccordionId(id = 2305)
        public CollectionTrackerSettings collectionTracker = new CollectionTrackerSettings();

        @Expose
        @ConfigOption(name = "Collection Portfolio", desc = "Save your collections every 30 minutes on SkyBlock (from Elite's copy of the Hypixel API) so /s2 collections can show how much each one went up over the last day, week or month. Needs your Collections API on.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2305)
        public boolean collectionPortfolio = true;

        @Expose
        @ConfigOption(name = "Rival HUD", desc = "While you farm, mine, forage, fish or gather a collection, show the Sky2M leaderboard for it against your rival there (/s2 rival add <name> <board>, or click them in /s2 leaderboard): both amounts and who's ahead by how much. Hidden when you have no rival on that board. Move it in /s2 gui.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2305)
        public boolean rivalHud = false;

        @Expose
        @ConfigOption(name = "Rival Alerts", desc = "With the Rival HUD on: a title and a sound when a rival passes you, or you pass them, on the board the Rival HUD shows (at most once a minute per rival).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2305)
        public boolean rivalAlerts = true;

        @Expose
        @ConfigOption(name = "Weekly Rival Recap", desc = "The first time you're on SkyBlock each week (after the weekly boards reset on Monday 00:00 UTC), say in chat who gained more last week on each board you have a rival on: you or them, both gains and the difference. Sky2M keeps the latest weekly standings it sees for those boards during the week (and checks them every 30 minutes on SkyBlock) in config/sky2m/rival-weekly.json.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2305)
        public boolean rivalRecap = true;

        @ConfigOption(name = "Visual", desc = "Fire, explosions, player size, held item, etherwarp overlay and render tweaks.")
        @ConfigEditorAccordion(id = 2306)
        public boolean visualGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Random", desc = "Low fire overlay, hidden explosions and other small visual tweaks.")
        @ConfigAccordionId(id = 2306)
        public Random random = new Random();

        @Expose
        @Accordion
        @ConfigOption(name = "Player Size", desc = "Make yourself, other players, or both bigger or smaller (client side only), like Odin.")
        @ConfigAccordionId(id = 2306)
        public PlayerSize playerSize = new PlayerSize();

        @Expose
        @Accordion
        @ConfigOption(name = "Play as Mob", desc = "Players drawn as the mob or 3D model they chose in /s2 cosmetics: you, and every other Sky2M player. Models are given out by Sky2M staff on shadowisabot.com; every mob is free. Only Sky2M players see looks.")
        @ConfigAccordionId(id = 2306)
        public PlayAsMob playAsMob = new PlayAsMob();

        @Expose
        @Accordion
        @ConfigOption(name = "Hit Colour", desc = "Change the colour of the flash when a mob or player gets hit, like Lunar Client's Hit Color.")
        @ConfigAccordionId(id = 2306)
        public HitColour hitColour = new HitColour();

        @Expose
        @Accordion
        @ConfigOption(name = "Own Name Tag", desc = "See your own name tag in third person (F5), with your networth and skill average above it.")
        @ConfigAccordionId(id = 2306)
        public OwnNameTag ownNameTag = new OwnNameTag();

        @Expose
        @Accordion
        @ConfigOption(name = "Held Item", desc = "Move, rotate and scale the item in your hand, change its swing speed and style, and show vanilla textures, globally or per item, ported from Skysoft. Open the editor with /s2 helditem.")
        @ConfigAccordionId(id = 2306)
        public com.epic60869.sky2m.features.helditem.HeldItemConfig heldItem = new com.epic60869.sky2m.features.helditem.HeldItemConfig();

        @Expose
        @Accordion
        @ConfigOption(name = "Etherwarp Overlay", desc = "Show where your etherwarp would take you (Odin's).")
        @ConfigAccordionId(id = 2306)
        public com.epic60869.sky2m.features.FeatureConfigs.EtherwarpOverlay etherwarpOverlay = new com.epic60869.sky2m.features.FeatureConfigs.EtherwarpOverlay();

        @Expose
        @ConfigOption(name = "Hide Status Effects", desc = "Hide potion effects beside inventories and in the top-right of the screen (Skysoft's).")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2306)
        public boolean hideStatusEffects = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Keep Terrain Loaded", desc = "Keep visited terrain loaded past Hypixel's view distance (Skysoft's).")
        @ConfigAccordionId(id = 2306)
        public com.epic60869.sky2m.features.FeatureConfigs.KeepTerrainLoaded keepTerrainLoaded = new com.epic60869.sky2m.features.FeatureConfigs.KeepTerrainLoaded();

        @Expose
        @Accordion
        @ConfigOption(name = "Trail", desc = "A trail of squares behind you as you move (Killer560's).")
        @ConfigAccordionId(id = 2306)
        public com.epic60869.sky2m.features.FeatureConfigs.Trail trail = new com.epic60869.sky2m.features.FeatureConfigs.Trail();

        @Expose
        @Accordion
        @ConfigOption(name = "DVD Screensaver", desc = "Text or a GIF bouncing around your screen like an old DVD player (Killer560's).")
        @ConfigAccordionId(id = 2306)
        public com.epic60869.sky2m.features.FeatureConfigs.Dvd dvd = new com.epic60869.sky2m.features.FeatureConfigs.Dvd();

        @ConfigOption(name = "Hypixel & Commands", desc = "Auto join SkyBlock, the Hypixel button, party commands, warp shortcuts and join commands.")
        @ConfigEditorAccordion(id = 2307)
        public boolean hypixelCommandsGroup = false;

        @Expose
        @ConfigOption(name = "Auto Join SkyBlock", desc = "Like SkyHanni: when you join Hypixel, go straight into SkyBlock instead of staying in the main lobby. Only once per join, so /lobby still works.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2307)
        public boolean autoJoinSkyblock = false;

        @Expose
        @ConfigOption(name = "Hypixel Button", desc = "A Hypixel button on the title screen, next to Multiplayer, that joins play.hypixel.net in one click.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2307)
        public boolean hypixelButton = false;

        @Expose
        @ConfigOption(name = "Smart Disconnect", desc = "Clicking Disconnect in the pause menu asks first, with Cancel and a red Disconnect button, so a misclick doesn't kick you from the server.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2307)
        public boolean smartDisconnect = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Party Commands", desc = "Let party members use !warp, !allinvite, !pt, !f7, !m7 and !t5 when you are leader, and ask your !fps, !ping and !tps.")
        @ConfigAccordionId(id = 2307)
        public com.epic60869.sky2m.features.FeatureConfigs.PartyCommands partyCommands = new com.epic60869.sky2m.features.FeatureConfigs.PartyCommands();

        @Expose
        @ConfigOption(name = "Warp Shortcuts", desc = "Type /dhub, /crypts, /garden and other warp names without /warp (SkyHanni's and Skysoft's short warp commands). In the Garden, /home warps to the Garden, /barn goes to the barn and /tp <plot> to a plot.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2307)
        public boolean warpShortcuts = false;

        @Expose
        @ConfigOption(name = "Join Commands", desc = "Quick commands to join dungeons and Kuudra: /f0 (Entrance) to /f7, /m1 to /m7, and /t1 to /t5 for Kuudra (Basic to Infernal). Applies next time you join a server.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2307)
        public boolean joinCommands = false;

        @ConfigOption(name = "Discord & Screenshots", desc = "Discord Rich Presence and screenshot sharing.")
        @ConfigEditorAccordion(id = 2308)
        public boolean discordScreenshotsGroup = false;

        @Expose
        @ConfigOption(name = "Discord Rich Presence", desc = "Show that you're playing Sky2M on your Discord profile, with where you are on SkyBlock and how long you've been playing. Needs the Discord app open on this computer.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2308)
        public boolean discordPresence = true;

        @Expose
        @ConfigOption(name = "Show Location in Discord", desc = "Show your SkyBlock island, area and dungeon floor in your Discord status. Off: it only says you're playing SkyBlock.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2308)
        public boolean discordPresenceLocation = true;

        @Expose
        @Accordion
        @ConfigOption(name = "Screenshots", desc = "Upload F2 screenshots and get a link to share in /s2c.")
        @ConfigAccordionId(id = 2308)
        public Screenshots screenshots = new Screenshots();

        @ConfigOption(name = "Waypoints, Trackers & Sounds", desc = "Area waypoints, carries, your networth over time, custom sounds and Sky2M's own achievements.")
        @ConfigEditorAccordion(id = 2309)
        public boolean trackersGroup = false;

        @Expose
        @Accordion
        @ConfigOption(name = "Area Waypoints", desc = "Odin's waypoints for one dungeon boss phase, Kuudra tier or island, that can run commands (/s2w).")
        @ConfigAccordionId(id = 2309)
        public com.epic60869.sky2m.features.FeatureConfigs.AreaWaypoints areaWaypoints = new com.epic60869.sky2m.features.FeatureConfigs.AreaWaypoints();

        @Expose
        @Accordion
        @ConfigOption(name = "Fairy Souls", desc = "A Fairy Soul finder over NEU's coordinates: outlines the ones you haven't collected (SkyHanni style), counts them on a HUD (/s2 souls).")
        @ConfigAccordionId(id = 2309)
        public com.epic60869.sky2m.features.FeatureConfigs.FairySouls fairySouls = new com.epic60869.sky2m.features.FeatureConfigs.FairySouls();

        @Expose
        @Accordion
        @ConfigOption(name = "Shopping List", desc = "What you still want to get, ticked off by what you pick up (/s2 shopping).")
        @ConfigAccordionId(id = 2309)
        public com.epic60869.sky2m.features.FeatureConfigs.ShoppingList shoppingList = new com.epic60869.sky2m.features.FeatureConfigs.ShoppingList();

        @Expose
        @Accordion
        @ConfigOption(name = "Carry Tracker", desc = "Who you're carrying, how many are done and what they owe (/s2 carry).")
        @ConfigAccordionId(id = 2309)
        public com.epic60869.sky2m.features.FeatureConfigs.CarryTracker carryTracker = new com.epic60869.sky2m.features.FeatureConfigs.CarryTracker();

        @Expose
        @Accordion
        @ConfigOption(name = "Networth History", desc = "Your networth saved over time and drawn as a graph (/s2 networth).")
        @ConfigAccordionId(id = 2309)
        public com.epic60869.sky2m.features.FeatureConfigs.NetworthHistory networthHistory = new com.epic60869.sky2m.features.FeatureConfigs.NetworthHistory();

        @Expose
        @Accordion
        @ConfigOption(name = "Custom Sounds", desc = "Your own sounds for leaping, teleporting, dying and kills.")
        @ConfigAccordionId(id = 2309)
        public com.epic60869.sky2m.features.FeatureConfigs.CustomSounds customSounds = new com.epic60869.sky2m.features.FeatureConfigs.CustomSounds();

        @Expose
        @Accordion
        @ConfigOption(name = "Sky2M Achievements", desc = "Achievements for playing with Sky2M (/s2 achievements).")
        @ConfigAccordionId(id = 2309)
        public com.epic60869.sky2m.features.FeatureConfigs.Achievements achievements = new com.epic60869.sky2m.features.FeatureConfigs.Achievements();

        /** The old Held Item Model settings, only read to copy them into Held Item once. */
        @Expose
        public LegacyHeldItemModel heldItemModel = null;

        /** Collection pinned with /s2 trackcollection (a Hypixel item id), or "" to follow what you gather. */
        @Expose
        public String collectionTrackerItem = "";

        /** Goal set with /s2 trackcollection &lt;item&gt; &lt;goal&gt;, or 0. */
        @Expose
        public long collectionTrackerGoal = 0;
    }

    /** Inventory > Inventory Buttons, ported from Skyblocker's Quick Navigation (LGPL-3.0): see features/misc/InventoryButtons. */
    public static final class InventoryButtonsSettings {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Skyblocker's Quick Navigation: up to 14 tabs above and below every SkyBlock menu, like the creative inventory's, that run a command. The tab for the menu you're in is drawn selected. Home, Garden and the hubs need a double click.")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Button 1 (Skills)", desc = "Top tab 1.")
        @Accordion
        public QuickNavButton button1 = new QuickNavButton(false, "minecraft:diamond_sword", "Your Skills", "/skills", "Skills");

        @Expose
        @ConfigOption(name = "Button 2 (Collections)", desc = "Top tab 2.")
        @Accordion
        public QuickNavButton button2 = new QuickNavButton(false, "minecraft:painting", "Collections", "/collection", "Collections");

        @Expose
        @ConfigOption(name = "Button 3 (Pets)", desc = "Top tab 3.")
        @Accordion
        public QuickNavButton button3 = new QuickNavButton(false, "minecraft:bone", "(?:\\(\\d+/\\d+\\) )?Pets", "/pets", "Pets");

        @Expose
        @ConfigOption(name = "Button 4 (Armor Sets)", desc = "Top tab 4.")
        @Accordion
        public QuickNavButton button4 = new QuickNavButton(false, "minecraft:leather_chestplate#8991416", "\\(\\d+/\\d+\\) Armor Sets", "/armor", "Armor Sets");

        @Expose
        @ConfigOption(name = "Button 5 (Sacks)", desc = "Top tab 5.")
        @Accordion
        public QuickNavButton button5 = new QuickNavButton(false, "skull:ewogICJ0aW1lc3RhbXAiIDogMTU5MTMxMDU4NTYwOSwKICAicHJvZmlsZUlkIiA6ICI0MWQzYWJjMmQ3NDk0MDBjOTA5MGQ1NDM0ZDAzODMxYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNZWdha2xvb24iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODBhMDc3ZTI0OGQxNDI3NzJlYTgwMDg2NGY4YzU3OGI5ZDM2ODg1YjI5ZGFmODM2YjY0YTcwNjg4MmI2ZWMxMCIKICAgIH0KICB9Cn0=", "Sack of Sacks", "/sacks", "Sacks");

        @Expose
        @ConfigOption(name = "Button 6 (Accessories)", desc = "Top tab 6.")
        @Accordion
        public QuickNavButton button6 = new QuickNavButton(false, "skull:eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTYxYTkxOGMwYzQ5YmE4ZDA1M2U1MjJjYjkxYWJjNzQ2ODkzNjdiNGQ4YWEwNmJmYzFiYTkxNTQ3MzA5ODVmZiJ9fX0=", "Accessory Bag(?: \\(\\d/\\d\\))?", "/accessories", "Accessories");

        @Expose
        @ConfigOption(name = "Button 7 (Storage)", desc = "Top tab 7.")
        @Accordion
        public QuickNavButton button7 = new QuickNavButton(false, "minecraft:ender_chest", "(?:Rift )?Storage(?: \\(\\d/\\d\\))?", "/storage", "Storage");

        @Expose
        @ConfigOption(name = "Button 8 (Home)", desc = "Bottom tab 1.")
        @Accordion
        public QuickNavButton button8 = new QuickNavButton(true, "skull:eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzljODg4MWU0MjkxNWE5ZDI5YmI2MWExNmZiMjZkMDU5OTEzMjA0ZDI2NWRmNWI0MzliM2Q3OTJhY2Q1NiJ9fX0=", "none", "/is", "Home");

        @Expose
        @ConfigOption(name = "Button 9 (Garden)", desc = "Bottom tab 2.")
        @Accordion
        public QuickNavButton button9 = new QuickNavButton(true, "skull:eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjQ4ODBkMmMxZTdiODZlODc1MjJlMjA4ODI2NTZmNDViYWZkNDJmOTQ5MzJiMmM1ZTBkNmVjYWE0OTBjYjRjIn19fQ==", "none", "/warp garden", "Garden");

        @Expose
        @ConfigOption(name = "Button 10 (Skyblock Hub)", desc = "Bottom tab 3.")
        @Accordion
        public QuickNavButton button10 = new QuickNavButton(true, "skull:e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDdjYzY2ODc0MjNkMDU3MGQ1NTZhYzUzZTA2NzZjYjU2M2JiZGQ5NzE3Y2Q4MjY5YmRlYmVkNmY2ZDRlN2JmOCJ9fX0=", "none", "/hub", "Skyblock Hub");

        @Expose
        @ConfigOption(name = "Button 11 (Dungeons Hub)", desc = "Bottom tab 4.")
        @Accordion
        public QuickNavButton button11 = new QuickNavButton(true, "skull:e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNzg5MWQ1YjI3M2ZmMGJjNTBjOTYwYjJjZDg2ZWVmMWM0MGExYjk0MDMyYWU3MWU3NTQ3NWE1NjhhODI1NzQyMSJ9fX0=", "none", "/warp dungeon_hub", "Dungeons Hub");

        @Expose
        @ConfigOption(name = "Button 12 (Auction House)", desc = "Bottom tab 5.")
        @Accordion
        public QuickNavButton button12 = new QuickNavButton(false, "minecraft:gold_block", "^(?:Auctions Browser|Co-op Auction House|Auction House|Manage Auctions|Your Bids|BIN Auction View|Auction View|Auctions:.*)$", "/ah", "Auction House");

        @Expose
        @ConfigOption(name = "Button 13 (Bazaar)", desc = "Bottom tab 6.")
        @Accordion
        public QuickNavButton button13 = new QuickNavButton(false, "skull:e3RleHR1cmVzOntTS0lOOnt1cmw6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZmZlMmRjZGE0MWVjM2FmZjhhZjUwZjI3MmVjMmUwNmE4ZjUwOWUwZjgwN2YyMzU1YTFmNWEzM2MxYjY2ZTliNCJ9fX0=", "(?:Co-op )?Bazaar .*", "/bz", "Bazaar");

        @Expose
        @ConfigOption(name = "Button 14 (Crafting Table)", desc = "Bottom tab 7.")
        @Accordion
        public QuickNavButton button14 = new QuickNavButton(false, "minecraft:crafting_table", "Craft Item", "/craft", "Crafting Table");
    }

    public static final class QuickNavButton {
        @Expose
        @ConfigOption(name = "Show", desc = "Show this tab.")
        @ConfigEditorBoolean
        public boolean render = true;

        @Expose
        @ConfigOption(name = "Double Click", desc = "Only run the command on a second click within a second (for warps).")
        @ConfigEditorBoolean
        public boolean doubleClick = false;

        @Expose
        @ConfigOption(name = "Icon", desc = "The tab's item: a Minecraft item (minecraft:bone), a SkyBlock item id (ASPECT_OF_THE_END), or skull:<texture> for a player head. Add #<colour> for a dyed leather item (minecraft:leather_chestplate#8991416).")
        @ConfigEditorText
        public String icon = "minecraft:barrier";

        @Expose
        @ConfigOption(name = "Menu Title", desc = "A regex for the menu title this tab belongs to: it's drawn selected in that menu. \"none\" for no menu.")
        @ConfigEditorText
        public String uiTitle = "none";

        @Expose
        @ConfigOption(name = "Command", desc = "What clicking the tab runs, for example /pets or /warp hub.")
        @ConfigEditorText
        public String command = "";

        @Expose
        @ConfigOption(name = "Tooltip", desc = "The text shown when you hover the tab.")
        @ConfigEditorText
        public String tooltip = "";

        public QuickNavButton() {}

        public QuickNavButton(boolean doubleClick, String icon, String uiTitle, String command, String tooltip) {
            this.doubleClick = doubleClick;
            this.icon = icon;
            this.uiTitle = uiTitle;
            this.command = command;
            this.tooltip = tooltip;
        }
    }

    public static final class Screenshots {
        @Expose
        @ConfigOption(name = "Screenshot Sharing", desc = "After F2, the screenshot message gets an [Upload] button that gives you a link to post in /s2c, like Skysoft. Uploads are public to anyone with the link.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Upload Host", desc = "Where screenshots are uploaded. Sky2M sends them to ImgBB through the Sky2M server (kept 30 days, and Catbox if that fails), Catbox keeps them for good, Uguu for 3 hours.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.ScreenshotShare.Host host = com.epic60869.sky2m.features.misc.ScreenshotShare.Host.SKY2M;
    }

    public static final class CollectionTrackerSettings {
        @Expose
        @ConfigOption(name = "Collection Tracker", desc = "While you mine, farm, forage or fish, show the collection you're gathering, what you've gained this session and per hour, like SkyHanni's farming display. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Elite Rank", desc = "Also show your rank on the Elite (elitebot.dev) collection leaderboard and how much you need to pass the next player.")
        @ConfigEditorBoolean
        public boolean eliteRank = true;
    }

    public static final class ToggleSprintSettings {
        @Expose
        @ConfigOption(name = "Toggle Sprint", desc = "Always sprint, like Odin's Auto Sprint. Set a \"Toggle Sprint\" key in Controls to switch it on and off.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Toggle Sprint HUD", desc = "Show [Sprinting (Toggled)] while toggle sprint is on. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean hud = true;

        @Expose
        @ConfigOption(name = "Toggle Sprint HUD Text", desc = "The text the HUD shows while toggle sprint is on, e.g. Hi (Toggled). Use & or § for colours, {key} for the Toggle Sprint key. Empty for the default ([Sprinting (Toggled)]).")
        @ConfigEditorText
        public String hudText = "[Sprinting (Toggled)]";
    }

    private static final Gson LEGACY_GSON = new Gson();

    private static ManagedConfig<Sky2MConfig> managed;

    @Expose
    @Category(name = "About", desc = "Updates, warnings and settings tools.")
    public General general = new General();

    @Expose
    @Category(name = "GUI", desc = "HUDs, scoreboard, tab list and displays.")
    public Gui gui = new Gui();

    @Expose
    @Category(name = "Garden", desc = "Features for the Garden island.")
    public Farming farming = new Farming();

    @Expose
    @Category(name = "Crimson Isle", desc = "Kuudra and Ashfang.")
    public com.epic60869.sky2m.features.FeatureConfigs.CrimsonIsle crimsonIsle = new com.epic60869.sky2m.features.FeatureConfigs.CrimsonIsle();

    @Expose
    @Category(name = "Fishing", desc = "Sea creatures, drops and fishing alerts.")
    public com.epic60869.sky2m.features.FeatureConfigs.Fishing fishing = new com.epic60869.sky2m.features.FeatureConfigs.Fishing();

    @Expose
    @Category(name = "Mining", desc = "Features that help you break blocks.")
    public Mining mining = new Mining();

    @Expose
    @Category(name = "Foraging", desc = "Features that help you cut down trees.")
    public com.epic60869.sky2m.features.FeatureConfigs.Foraging foraging = new com.epic60869.sky2m.features.FeatureConfigs.Foraging();

    @Expose
    @Category(name = "Hunting", desc = "Shards, lassos, fusions and the Safari.")
    public com.epic60869.sky2m.features.hunting.HuntingConfig hunting = new com.epic60869.sky2m.features.hunting.HuntingConfig();

    @Expose
    @Category(name = "Combat", desc = "Everything combat and PvE related.")
    public com.epic60869.sky2m.features.FeatureConfigs.Combat combat = new com.epic60869.sky2m.features.FeatureConfigs.Combat();

    @Expose
    @Category(name = "Slayer", desc = "Slayer bosses, kill times and profit.")
    public Slayers slayers = new Slayers();

    @Expose
    @Category(name = "Dungeon", desc = "Map, puzzles, secrets and splits.")
    public com.epic60869.sky2m.features.FeatureConfigs.Dungeons dungeons = new com.epic60869.sky2m.features.FeatureConfigs.Dungeons();

    @Expose
    @Category(name = "Inventory", desc = "Items, tooltips, storage and the Bazaar.")
    public Inventory inventory = new Inventory();

    @Expose
    @Category(name = "Events", desc = "Diana, the mayor and seasonal events.")
    public com.epic60869.sky2m.features.events.EventsConfig events = new com.epic60869.sky2m.features.events.EventsConfig();

    @Expose
    @Category(name = "Skill Progress", desc = "Skill progress, ETA and overflow levels.")
    public com.epic60869.sky2m.features.FeatureConfigs.SkillProgress skills = new com.epic60869.sky2m.features.FeatureConfigs.SkillProgress();

    @Expose
    @Category(name = "Chat", desc = "Change how the chat looks and works.")
    public Chat chat = new Chat();

    @Expose
    @Category(name = "Sky2M Online", desc = "S2M chat, friends, cosmetics and sync.")
    public com.epic60869.sky2m.features.sbc.SbcConfig.Online online = new com.epic60869.sky2m.features.sbc.SbcConfig.Online();

    @Expose
    @Category(name = "Misc", desc = "Settings without a category.")
    public Misc misc = new Misc();

    /** Save Clip's default key. */
    private static final int CLIP_KEY = com.mojang.blaze3d.platform.InputConstants.KEY_F8;

    public static final class TooltipScroll {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Allow tooltips to be moved with the mouse wheel and movement keys.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Enable Scroll Wheel", desc = "Move tooltips with the mouse wheel.")
        @ConfigEditorBoolean
        public boolean enableScrollWheel = true;

        @Expose
        @ConfigOption(name = "Enable in Chat", desc = "Allow tooltip movement while chat is open.")
        @ConfigEditorBoolean
        public boolean enabledInChat = false;

        @Expose
        @ConfigOption(name = "Enable WASD", desc = "Use WASD to move the hovered tooltip.")
        @ConfigEditorBoolean
        public boolean enableWASD = false;

        @Expose
        @ConfigOption(name = "Mouse Scrolling Speed", desc = "Pixels moved per mouse-wheel step.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 40f, minStep = 1f)
        public int mouseScrollingSpeed = 10;

        @Expose
        @ConfigOption(name = "Keyboard Scrolling Speed", desc = "Pixels moved per tick while a tooltip movement key is held.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 40f, minStep = 1f)
        public int keyboardScrollingSpeed = 5;

        @Expose
        @ConfigOption(name = "Move Up Key", desc = "Move the hovered tooltip up.")
        @ConfigEditorKeybind(defaultKey = InputConstants.KEY_PAGEUP)
        public int moveUpKey = InputConstants.KEY_PAGEUP;

        @Expose
        @ConfigOption(name = "Move Down Key", desc = "Move the hovered tooltip down.")
        @ConfigEditorKeybind(defaultKey = InputConstants.KEY_PAGEDOWN)
        public int moveDownKey = InputConstants.KEY_PAGEDOWN;

        @Expose
        @ConfigOption(name = "Horizontal Movement Key", desc = "Hold this key to make up and down movement horizontal.")
        @ConfigEditorKeybind(defaultKey = -1)
        public int horizontalMovementKey = InputConstants.UNKNOWN.getValue();

        @Expose
        @ConfigOption(name = "Reset Tooltip Key", desc = "Reset the hovered tooltip's moved position.")
        @ConfigEditorKeybind(defaultKey = -1)
        public int resetTooltipKey = InputConstants.UNKNOWN.getValue();

        @Expose
        @ConfigOption(name = "Start On Top", desc = "Show the top of oversized tooltips when they first appear.")
        @ConfigEditorBoolean
        public boolean startOnTop = false;

        @Expose
        @ConfigOption(name = "Reset Position When Not Hovered", desc = "Reset tooltip movement after the tooltip disappears.")
        @ConfigEditorBoolean
        public boolean resetWhenNotHovered = true;

        @Expose
        @ConfigOption(name = "Use Left Shift", desc = "Hold left shift to move tooltips horizontally with the mouse wheel.")
        @ConfigEditorBoolean
        public boolean useLeftShift = true;

        @Expose
        @ConfigOption(name = "Invert Horizontal Movement", desc = "Invert horizontal tooltip movement.")
        @ConfigEditorBoolean
        public boolean invertHorizontal = false;

        @Expose
        @ConfigOption(name = "Invert Vertical Movement", desc = "Invert vertical tooltip movement.")
        @ConfigEditorBoolean
        public boolean invertVertical = false;

        @Expose
        @ConfigOption(name = "Scroll Smoothness", desc = "How quickly tooltips slide toward the moved position. 100 is instant.")
        @ConfigEditorSlider(minValue = 5f, maxValue = 100f, minStep = 5f)
        public int scrollSmoothness = 25;
    }

    public static final class ItemCustom {
        @ConfigOption(name = "Open Item Editor", desc = "Open the item and armor customization screen.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable openItemEditor = () -> openCustom();

        @Expose
        @ConfigOption(name = "Show Customize Button", desc = "Show a button in the inventory that opens the item and armor customization screen.")
        @ConfigEditorBoolean
        public boolean showCustomizeButton = false;
    }

    public static final class SlotLocking {
        @Expose
        @ConfigOption(name = "Slot Binding", desc = "Enable binding hotbar slots to inventory slots (Odin's Slot Binds). Item protection is toggled separately with /s2 protect.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Bind Key", desc = "In your inventory: press over a slot, then over another (one in the hotbar), to bind them. Press on a bound slot to unbind.")
        @ConfigEditorKeybind(defaultKey = InputConstants.KEY_B)
        public int bindKey = InputConstants.KEY_B;

        @Expose
        @ConfigOption(name = "Bind Line Only With Shift", desc = "Only show the line between bound slots while holding Shift.")
        @ConfigEditorBoolean
        public boolean lineOnlyWithShift = false;

        @Expose
        @ConfigOption(name = "Always Show Binds", desc = "In your inventory, always show every bind (a box around both slots and a line between them), not just the one you hover (Killer560's).")
        @ConfigEditorBoolean
        public boolean alwaysShowBinds = false;

        /** Protected item UUIDs, or item IDs for items without a UUID. */
        @Expose
        public java.util.List<String> protectedItems = new java.util.ArrayList<>();

        /** Slot binds, by inventory screen slot (36-44 is the hotbar). */
        @Expose
        public java.util.Map<Integer, Integer> binds = new java.util.HashMap<>();
    }

    public static final class BazaarNotificationsSettings {

        @ConfigOption(name = "Order Notifications", desc = "Chat and sound when your Bazaar orders are outbid or filled.")
        @ConfigEditorAccordion(id = 2201)
        public boolean orderNotificationsGroup = false;
        @Expose
        @ConfigOption(name = "Order Notifications", desc = "Track your Bazaar orders and notify when an order is outbid or filled.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2201)
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Chat on Outbid", desc = "Show a clickable chat notification when a tracked order becomes outbid.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2201)
        public boolean outbidChat = true;

        @Expose
        @ConfigOption(name = "Sound on Outbid", desc = "Play a sound when a tracked order becomes outbid.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2201)
        public boolean outbidSound = true;

        @Expose
        @ConfigOption(name = "Sound on Order Filled", desc = "Play a sound when one of your Bazaar orders or offers fills.")
        @ConfigEditorBoolean
        @ConfigAccordionId(id = 2201)
        public boolean filledSound = true;

        @Expose
        @ConfigOption(name = "Order Colours", desc = "In your Bazaar orders menu, colour each order's slot by where it stands, like Bazaar Utils: green when it's the best price, yellow when another order matches its price, red when it's outbid. Uses the bazaar prices from Hypixel's API, which can be up to a minute old.")
        @ConfigEditorBoolean
        public boolean orderColours = true;

        @Expose
        @ConfigOption(name = "Bookmarks", desc = "Bookmark Bazaar items, like Bazaar Utils: click the ★ in the top-left of an item's Bazaar page to bookmark it. Your bookmarks show to the right of the Bazaar; hover one for its instant buy and sell prices, click to open it, shift-click to remove it.")
        @ConfigEditorBoolean
        public boolean bookmarks = true;

        /** Bookmarked Bazaar items, in the order they were added. */
        @Expose
        public java.util.List<com.epic60869.sky2m.features.portfolio.BazaarBookmarks.Bookmark> bookmarkedItems = new java.util.ArrayList<>();
    }

    public static final class CopyChat {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Copy chat messages, like NoFrills' Chat Tweaks: with chat open, press the Copy Message Key over a message to copy it. Sky2M rank prefixes aren't copied.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        /** A keyboard key, or a mouse button stored the MoulConfig way (see BindKeys.mouseButton; right click is -99 or 1). */
        @Expose
        @ConfigOption(name = "Copy Message Key", desc = "Key or mouse button that copies the message under the mouse while chat is open. Right click by default.")
        @ConfigEditorKeybind(defaultKey = -99)
        public int copyMessageKey = -99;

        @Expose
        @ConfigOption(name = "Copy Preview", desc = "Show what was copied in chat.")
        @ConfigEditorBoolean
        public boolean preview = true;

        @Expose
        @ConfigOption(name = "Preview Length", desc = "How many characters of the copied text to show (0 just says it was copied).")
        @ConfigEditorSlider(minValue = 0, maxValue = 200, minStep = 10)
        public int previewLength = 50;

        @Expose
        @ConfigOption(name = "Trim On Copy", desc = "Remove spaces at the start and end of what's copied.")
        @ConfigEditorBoolean
        public boolean trim = false;
    }

    public static final class CustomChat {
        @Expose
        @ConfigOption(name = "Show S2M Chat", desc = "Show Sky2M chat (/s2c) messages from other players. Off hides them; you can still send with /s2c.")
        @ConfigEditorBoolean
        public boolean showSjChat = true;

        @Expose
        @ConfigOption(name = "Show Ranks", desc = "Show Sky2M ranks ([OWNER], [TESTER], ...) in front of names in /s2c. Turn off to hide them.")
        @ConfigEditorBoolean
        public boolean showRanks = true;

        @Expose
        @ConfigOption(name = "S2M Chat Ping", desc = "Play a little ping when someone sends a message in /s2c.")
        @ConfigEditorBoolean
        public boolean pingSound = false;

        @Expose
        @ConfigOption(name = "Hide Other Players' Commands", desc = "Hide other players' ! commands (like !nw or !lvl) and the bot's answers to them in /s2c. Your own commands and answers stay visible.")
        @ConfigEditorBoolean
        public boolean hideOtherCommands = true;

        @Expose
        @ConfigOption(name = "! Chat Commands", desc = "Use the Sky2M ! commands (!nw, !lvl, !mp, !skill, !lb, !help, ...) in /s2c. Off: nothing is sent to the command bot, ! messages go out as normal chat, and the bot's command answers are not shown.")
        @ConfigEditorBoolean
        public boolean botCommands = true;

        @Expose
        @ConfigOption(name = "Calendar In Chat", desc = "Show the Sky2M calendar reminders (\"Reminder: Dark Auction starts in 5 minutes\") in /s2c. Click one to open /s2 calendar. Off hides them.")
        @ConfigEditorBoolean
        public boolean calendarInChat = true;
    }

    public static final class MiningCommissions {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the commission HUD when commission data is present in the tab list.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Background", desc = "Draw a dark background behind the commission HUD.")
        @ConfigEditorBoolean
        public boolean background = false;

        @Expose
        @ConfigOption(name = "Scale", desc = "Scale the commission HUD.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 3.0f, minStep = 0.1f)
        public float scale = 1.0f;

        @Expose
        public int x = 8;

        @Expose
        public int y = 80;

        @ConfigOption(name = "Edit Position", desc = "Open the HUD editor and drag the Mining Commissions HUD.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable editPosition = () -> openHudEditor();

        @Expose
        @ConfigOption(name = "Highlight Event", desc = "In the Dwarven Mines, highlight the current mining event's targets: the goblins and golems of a Goblin Raid, and the Raffle Tickets on the ground during a Raffle.")
        @ConfigEditorBoolean
        public boolean highlightEvent = false;

        @Expose
        @ConfigOption(name = "Goblin Raid Colour", desc = "Outline colour for Goblin Raid goblins and golems.")
        @ConfigEditorColour
        public String goblinRaidColour = "0:255:255:85:85";

        @Expose
        @ConfigOption(name = "Raffle Ticket Colour", desc = "Outline and box colour for Raffle Tickets on the ground.")
        @ConfigEditorColour
        public String raffleTicketColour = "0:255:85:255:85";
    }

    public static final class FarmingRng {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the RNG HUD: farming RNG drops, plus any other rare drop worth 5M or more (slayer, Diana, fishing...).")
        @ConfigEditorBoolean
        public boolean enabled = true;

        @Expose
        @ConfigOption(name = "Background", desc = "Draw a background behind the farming RNG HUD.")
        @ConfigEditorBoolean
        public boolean background = false;

        @Expose
        @ConfigOption(name = "Scale", desc = "Scale the farming RNG HUD.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 3.0f, minStep = 0.1f)
        public float scale = 1.0f;

        @Expose
        @ConfigOption(name = "Drop Colour", desc = "Colour of RNG drop names on the RNG HUD when the item's rarity isn't known (known items use their rarity colour, like SkyHanni).")
        @ConfigEditorColour
        public String dropColour = "0:255:85:255:255";

        @Expose
        @ConfigOption(name = "Pet Rarity Colours", desc = "Show slug pets in their rarity colour (Epic purple, Legendary gold) instead of the Drop Colour.")
        @ConfigEditorBoolean
        public boolean petRarityColours = true;

        @Expose
        @ConfigOption(name = "Price Colour", desc = "Colour of the prices in brackets on the RNG HUD (RNG drops and Item Notification).")
        @ConfigEditorColour
        public String valueColour = "0:255:255:170:0";

        @Expose
        public int x = 8;

        @Expose
        public int y = 8;

        @ConfigOption(name = "Edit Position", desc = "Open the Sky2M HUD editor and drag the Farming RNG HUD.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable editPosition = () -> openRngEditor();
    }

    public static final class PetDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show the active pet HUD.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Overflow Pet Levels", desc = "Show pet XP beyond the normal maximum level.")
        @ConfigEditorBoolean
        public boolean overflowLevels = true;

        @Expose
        @ConfigOption(name = "Auto-Pet Display", desc = "Keep the pet display synced with the active pet.")
        @ConfigEditorBoolean
        public boolean autoDisplay = true;

        @Expose
        @ConfigOption(name = "Show Held Item", desc = "Show what your pet is holding under it. Hypixel's tab list doesn't say, so it's learned from the Pets menu (open it once), \"Your pet is now holding ...\" and Autopet messages.")
        @ConfigEditorBoolean
        public boolean heldItem = true;

        @Expose
        @ConfigOption(name = "Pet Icon", desc = "Skysoft's pet icon beside the text: your pet's head (its skin, once you've opened the Pets menu) with its held item in the corner.")
        @ConfigEditorBoolean
        public boolean icon = true;

        @Expose
        @ConfigOption(name = "Rarity Background", desc = "Put the pet icon on a circle in the pet's rarity colour.")
        @ConfigEditorBoolean
        public boolean iconRarityBackground = true;

        @Expose
        @ConfigOption(name = "Level Progress Ring", desc = "A ring around the icon showing how far the pet is to its next level (cyan), with a grey divider.")
        @ConfigEditorBoolean
        public boolean iconXpRing = true;

        @Expose
        @ConfigOption(name = "Held Item Icon", desc = "Show the pet's held item on the bottom-right of its icon.")
        @ConfigEditorBoolean
        public boolean iconHeldItem = true;

        @Expose
        @ConfigOption(name = "Scale", desc = "Scale the pet HUD.")
        @ConfigEditorSlider(minValue = 0.5f, maxValue = 3.0f, minStep = 0.1f)
        public float scale = 1.0f;

        @Expose
        @ConfigOption(name = "Background", desc = "Draw a dark background behind the pet display.")
        @ConfigEditorBoolean
        public boolean background = false;

        @Expose public int x = 10;
        @Expose public int y = 10;

        @ConfigOption(name = "Edit Position", desc = "Open the HUD editor and drag the Pet Display.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable editPosition = () -> openHudEditor();
    }

    public static final class MouseLock {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Fully lock the camera while holding a farming tool.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Garden Only", desc = "Only lock the camera on the Garden.")
        @ConfigEditorBoolean
        public boolean gardenOnly = true;

        @Expose
        @ConfigOption(name = "Ground Only", desc = "Only apply Mouse Lock while the player is on the ground.")
        @ConfigEditorBoolean
        public boolean groundOnly = true;
    }

    public static final class PriceTooltip {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show item prices in tooltips.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Bazaar Prices", desc = "For items sold on the bazaar instead of the auction house, show the bazaar insta-buy and insta-sell price where the lowest BIN and 3 day average would be (for the whole stack, or the whole sack in the Sacks menu).")
        @ConfigEditorBoolean
        public boolean bazaar = true;

        @Expose
        @ConfigOption(name = "NPC Sell Price", desc = "How much an NPC buys the item for.")
        @ConfigEditorBoolean
        public boolean npcPrice = true;

        @Expose
        @ConfigOption(name = "Lowest BIN Price", desc = "The item's current lowest Buy It Now price on the auction house.")
        @ConfigEditorBoolean
        public boolean lowestBin = true;

        @Expose
        @ConfigOption(name = "3 Day Avg. Price", desc = "The item's average lowest BIN price over the last 3 days.")
        @ConfigEditorBoolean
        public boolean threeDayAverage = true;

        @Expose
        @ConfigOption(name = "Estimated Item Value", desc = "Below the lowest BIN and 3 day average: the item's price plus what's applied to it (recombobulator, potato books, enchantments, master stars, gemstones, scrolls, dyes, skins and other upgrades), like SkyHanni's Estimated Item Value.")
        @ConfigEditorBoolean
        public boolean estimatedValue = true;

        @Expose
        @ConfigOption(name = "Recipe Cost", desc = "Show what it costs to craft the item from its recipe (bazaar insta-buy, lowest BIN or NPC shop prices of the ingredients), and how much that saves against buying it. Hold Shift for the ingredients.")
        @ConfigEditorBoolean
        public boolean recipeCost = true;

        @Expose
        @ConfigOption(name = "Recipe Cost: Include Sub-Crafts", desc = "Price an ingredient that can itself be crafted at the cheaper of buying it and crafting it from its own recipe (up to 4 levels deep). Off: ingredients are priced at their market price.")
        @ConfigEditorBoolean
        public boolean recipeSubCrafts = false;

        @Expose
        @ConfigOption(name = "Price Paid", desc = "Remember what you paid for items you buy on the auction house and show it in their tooltip, like NoFrills.")
        @ConfigEditorBoolean
        public boolean pricePaid = false;

        @Expose
        @ConfigOption(name = "Show in Item List", desc = "Show the prices when you hover items in Sky2M's Item List and its recipe cards (works outside SkyBlock too).")
        @ConfigEditorBoolean
        public boolean itemList = true;

        @Expose
        @ConfigOption(name = "Bazaar, Auction House & Pets Menus", desc = "Also price the items in the Bazaar, Auction House and Pets menus that have no item data of their own (pets by name, rarity and level; Bazaar buttons by name), and on an auction compare its price with the lowest BIN.")
        @ConfigEditorBoolean
        public boolean menus = true;
    }

    public static final class CollectionTooltips {
        @Expose
        @ConfigOption(name = "Museum", desc = "Show whether the item is donated to your museum. Open your Museum's category menus once to fill this in; it's saved per profile.")
        @ConfigEditorBoolean
        public boolean museum = false;

        @Expose
        @ConfigOption(name = "Accessories", desc = "Show whether you're missing an accessory, already have it, or whether it's an upgrade or downgrade of the one you have from the same family. Open each page of your Accessory Bag once to fill this in; it's saved per profile.")
        @ConfigEditorBoolean
        public boolean accessories = false;
    }

    public static final class ItemRarity {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show a background behind SkyBlock items in your inventory, containers and hotbar using the item's rarity color.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Style", desc = "The shape of the item rarity background.")
        @ConfigEditorDropdown
        public Sky2MItemBackgrounds.Style style = Sky2MItemBackgrounds.Style.SQUARE;

        @Expose
        @ConfigOption(name = "Opacity", desc = "How opaque the item rarity background is.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 1f, minStep = 0.05f)
        public float opacity = 0.5f;
    }

    public static final class PlayerSize {
        @Expose
        @ConfigOption(name = "Scale Yourself", desc = "Change your own player's size (third person, inventory preview).")
        @ConfigEditorBoolean
        public boolean self = false;

        @Expose @ConfigOption(name = "Your Width", desc = "X scale (1 = normal).") @ConfigEditorSlider(minValue = 0.1f, maxValue = 3f, minStep = 0.05f) public float selfX = 0.6f;
        @Expose @ConfigOption(name = "Your Height", desc = "Y scale (1 = normal, negative = upside down).") @ConfigEditorSlider(minValue = -1f, maxValue = 3f, minStep = 0.05f) public float selfY = 0.6f;
        @Expose @ConfigOption(name = "Your Depth", desc = "Z scale (1 = normal).") @ConfigEditorSlider(minValue = 0.1f, maxValue = 3f, minStep = 0.05f) public float selfZ = 0.6f;

        @Expose
        @ConfigOption(name = "Scale Others", desc = "Change the size of every other real player (NPCs are left alone).")
        @ConfigEditorBoolean
        public boolean others = false;

        @Expose @ConfigOption(name = "Others Width", desc = "X scale (1 = normal).") @ConfigEditorSlider(minValue = 0.1f, maxValue = 3f, minStep = 0.05f) public float othersX = 0.6f;
        @Expose @ConfigOption(name = "Others Height", desc = "Y scale (1 = normal, negative = upside down).") @ConfigEditorSlider(minValue = -1f, maxValue = 3f, minStep = 0.05f) public float othersY = 0.6f;
        @Expose @ConfigOption(name = "Others Depth", desc = "Z scale (1 = normal).") @ConfigEditorSlider(minValue = 0.1f, maxValue = 3f, minStep = 0.05f) public float othersZ = 0.6f;
    }

    public static final class Clips {
        /**
         * The "download the Sky2M Clipping mod?" question was answered. A new name (it was askedDownload, for the old
         * recorder program) so everyone is asked again about the mod.
         */
        @Expose
        public boolean askedClippingMod = false;

        @Expose
        @ConfigOption(name = "Enabled", desc = "Keep the last few seconds of the game recorded, and save them as a video with the Save Clip key (Windows only). Recording is done by the Sky2M Clipping mod, which Sky2M offers to download into your mods folder (restart after); it uses your graphics card's video encoder, so it costs very little FPS. Needs the OpenGL graphics backend.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Save Clip Key", desc = "Saves the last Clip Length seconds as a video (also /s2 clip).")
        @ConfigEditorKeybind(defaultKey = CLIP_KEY)
        public int saveKey = CLIP_KEY;

        @Expose
        @ConfigOption(name = "Clip Length", desc = "How many seconds each clip keeps.")
        @ConfigEditorSlider(minValue = 10f, maxValue = 120f, minStep = 5f)
        public int seconds = 30;

        @Expose
        @ConfigOption(name = "Quality", desc = "The clip's height. Bigger looks sharper and makes bigger files.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.clips.Clips.Quality quality = com.epic60869.sky2m.features.misc.clips.Clips.Quality.P1080;

        @Expose
        @ConfigOption(name = "Frame Rate", desc = "Frames per second in clips.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.clips.Clips.Fps fps = com.epic60869.sky2m.features.misc.clips.Clips.Fps.FPS60;

        @Expose
        @ConfigOption(name = "Sound", desc = "What you hear in clips. Sky2M Clipping records everything your computer plays, so Game only and Game + Discord record that too; No sound records none.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.misc.clips.Clips.Sound sound = com.epic60869.sky2m.features.misc.clips.Clips.Sound.GAME;

        @Expose
        @ConfigOption(name = "Microphone", desc = "Also record your microphone (your default recording device).")
        @ConfigEditorBoolean
        public boolean mic = false;

        @Expose
        @ConfigOption(name = "Game Volume", desc = "The game's volume in clips (100 = as it is).")
        @ConfigEditorSlider(minValue = 0f, maxValue = 200f, minStep = 5f)
        public int gameVolume = 100;

        @Expose
        @ConfigOption(name = "Discord Volume", desc = "Discord's volume in clips, with Game + Discord.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 200f, minStep = 5f)
        public int discordVolume = 100;

        @Expose
        @ConfigOption(name = "Everything Volume", desc = "The volume of everything your computer plays, with Everything You Hear.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 200f, minStep = 5f)
        public int desktopVolume = 100;

        @Expose
        @ConfigOption(name = "Microphone Volume", desc = "Your microphone's volume in clips.")
        @ConfigEditorSlider(minValue = 0f, maxValue = 200f, minStep = 5f)
        public int micVolume = 100;

        @ConfigOption(name = "Clips Folder", desc = "Open the folder clips are saved in (Videos\\Sky2M Clips).")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable openFolder = () -> com.epic60869.sky2m.features.misc.clips.Clips.openFolder();

        @ConfigOption(name = "Download Sky2M Clipping", desc = "Download the Sky2M Clipping mod for this Minecraft version from its GitHub releases into your mods folder. Restart the game after.")
        @ConfigEditorButton(buttonText = "DOWNLOAD")
        public Runnable download = () -> com.epic60869.sky2m.features.misc.clips.Clips.download();
    }

    public static final class Fonts {
        /** "Default" (Minecraft's font), "Inter", or a .ttf file name in config/sky2m/fonts. */
        @Expose
        @ConfigOption(name = "Font", desc = "The font all text is drawn in: Default (Minecraft's), Inter (built in, like Caxton's default) or a .ttf from the fonts folder. Characters a font doesn't have, like Hypixel's icons, still use Minecraft's.")
        @com.epic60869.sky2m.features.misc.CustomFonts.FontDropdown
        public String font = "Default";

        @Expose
        @ConfigOption(name = "Size", desc = "How big Inter or a .ttf font is drawn (Minecraft's text is about 8; 9 matches it best). Press Apply after changing it.")
        @ConfigEditorSlider(minValue = 6f, maxValue = 16f, minStep = 0.5f)
        public float size = 9f;

        @Expose
        @ConfigOption(name = "Sharpness", desc = "How finely Inter or a .ttf font is rendered: higher is sharper on big GUI scales and uses more memory. Press Apply after changing it.")
        @ConfigEditorSlider(minValue = 1f, maxValue = 8f, minStep = 1f)
        public float sharpness = 6f;

        @Expose
        @ConfigOption(name = "Height", desc = "Moves Inter's or a .ttf font's letters down (or up when negative) to line up with Minecraft's. Press Apply after changing it.")
        @ConfigEditorSlider(minValue = -3f, maxValue = 3f, minStep = 0.5f)
        public float height = 1f;

        @ConfigOption(name = "Apply", desc = "Load the font again with the settings above.")
        @ConfigEditorButton(buttonText = "APPLY")
        public Runnable apply = () -> com.epic60869.sky2m.features.misc.CustomFonts.reload();

        @ConfigOption(name = "Fonts Folder", desc = "Open the folder for your fonts (config\\sky2m\\fonts). Put .ttf files there; they show in the Font dropdown a few seconds later (after closing this menu).")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable openFolder = () -> com.epic60869.sky2m.features.misc.CustomFonts.openFolder();
    }

    public static final class PlayAsMob {
        @ConfigOption(name = "Choose Your Look", desc = "Pick a mob, or a model Sky2M staff gave you, in /s2 cosmetics (Model). Every Sky2M player near you sees it. Also on shadowisabot.com and with /s2 playasmob.")
        @ConfigEditorButton(buttonText = "CHOOSE")
        public Runnable choose = () -> com.epic60869.sky2m.features.sbc.SbcCosmetics.openScreen("model");

        /** The mob you picked ("" for none): mobs are free, so they work without the Sky2M server too. */
        @Expose
        public String mob = "";

        @Expose
        @ConfigOption(name = "Show My Look", desc = "Draw yourself as the mob or model you chose (third person and your inventory preview). Off shows your normal skin to you only; other Sky2M players still see your look.")
        @ConfigEditorBoolean
        public boolean showOwn = true;

        @Expose
        @ConfigOption(name = "Show Others' Looks", desc = "Draw other Sky2M players as the mob or model they chose.")
        @ConfigEditorBoolean
        public boolean showOthers = true;

        @Expose
        @ConfigOption(name = "In Dungeons", desc = "Also draw other players' looks in dungeons. Off by default, so a teammate drawn as a mob isn't mistaken for an enemy.")
        @ConfigEditorBoolean
        public boolean inDungeons = false;

        @Expose
        @ConfigOption(name = "Use Player Size", desc = "Your Player Size (Scale Yourself, Scale Others) also stretches mobs. Models always follow it.")
        @ConfigEditorBoolean
        public boolean useSize = true;

        @Expose
        @ConfigOption(name = "Animate Models", desc = "Turn the head and swing the arms and legs of models as players look around and walk (bones named head, left/right arm and left/right leg).")
        @ConfigEditorBoolean
        public boolean animate = true;
    }

    public static final class HitColour {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Recolour the flash entities get when they take damage (vanilla is red).")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Colour", desc = "The hit flash colour. Opacity is how strong the flash is; chroma works too.")
        @ConfigEditorColour
        public String colour = "0:150:255:0:0";

        @Expose
        @ConfigOption(name = "Players", desc = "Use the colour on real players (off = they keep the red flash).")
        @ConfigEditorBoolean
        public boolean players = true;

        @Expose
        @ConfigOption(name = "Mobs", desc = "Use the colour on mobs and NPCs (off = they keep the red flash).")
        @ConfigEditorBoolean
        public boolean mobs = true;

        @Expose
        @ConfigOption(name = "Only Entities You Hit", desc = "Only recolour entities you hit in the last 2 seconds (and yourself): ones you attacked, and ones hurt in front of you just after you used a right-click weapon like Soul Whip or Flaming Flay. Other hurt entities keep the red flash.")
        @ConfigEditorBoolean
        public boolean onlyYourHits = false;
    }

    public static final class OwnNameTag {
        @Expose
        @ConfigOption(name = "View Own Name Tag in F5", desc = "Show your own name tag above your head in third person, like other players see it.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Stats Above Your Head", desc = "On SkyBlock, a line above your name tag in third person with your networth and skill average (your selected profile, loaded in the background every 10 minutes). Shows your name tag too.")
        @ConfigEditorBoolean
        public boolean stats = false;

        @Expose
        @ConfigOption(name = "Show Networth", desc = "\"NW 37.47B\" in the line above your head (SkyHelper's networth, museum included).")
        @ConfigEditorBoolean
        public boolean networth = true;

        @Expose
        @ConfigOption(name = "Show Skill Average", desc = "\"Skill Avg 52.4\" in the line above your head.")
        @ConfigEditorBoolean
        public boolean skillAverage = true;

        @Expose
        @ConfigOption(name = "Custom Text", desc = "Your own text at the start of the line above your head (up to 40 characters), like a title or your guild. Other Sky2M users see it too. Leave it empty for none.")
        @ConfigEditorText
        public String customText = "";

        @Expose
        @ConfigOption(name = "Show Leaderboard Above Head", desc = "A line above your stats with your amount and placement on a leaderboard (gold, silver or bronze for the top 3, aqua otherwise). Pick the board by right-clicking it in /s2 leaderboard (collections, slayer XP or kill times). Other Sky2M users can see it too.")
        @ConfigEditorBoolean
        public boolean showCollectionLeaderboard = false;

        /** The board shown over your head ("collection|WHEAT", "slayer|zombie", "pb|Revenant Horror V"), picked in /s2 leaderboard. */
        @Expose
        public String headBoard = "";

        /** The old Collection ID setting: still read until a board is picked in /s2 leaderboard. */
        @Expose
        public String collectionLeaderboard = "WHEAT";
    }

    public static final class SpeedDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Show your speed (100% is normal walking speed, 400% the cap) on a HUD, like SkyblockAddons. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;
    }

    public static final class CoinsChartSettings {
        @Expose
        @ConfigOption(name = "Enabled", desc = "HUD with your purse change per minute as a session graph and coins per hour (net purse change, so spending lowers it). Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Slayer Profit Line", desc = "Add the last slayer boss's drops value, quest cost and profit (needs Slayer Boss Profit).")
        @ConfigEditorBoolean
        public boolean slayerRoi = true;

        @Expose
        @ConfigOption(name = "Powder Goal Line", desc = "Add the time to reach a powder goal at this session's powder per hour (needs the Powder Tracker).")
        @ConfigEditorBoolean
        public boolean powderGoal = true;

        @Expose
        @ConfigOption(name = "Powder Goal Type", desc = "Which powder the goal is for.")
        @ConfigEditorDropdown
        public com.epic60869.sky2m.features.economy.CoinsChart.PowderType powderType = com.epic60869.sky2m.features.economy.CoinsChart.PowderType.GEMSTONE;

        @Expose
        @ConfigOption(name = "Powder Goal", desc = "The powder amount you are saving up to.")
        @ConfigEditorSlider(minValue = 100000, maxValue = 20000000, minStep = 100000)
        public int powderTarget = 2000000;
    }

    /** The settings of the old Held Item Model (replaced by Skysoft's Held Item). */
    public static final class LegacyHeldItemModel {
        @Expose public boolean enabled = false;
        @Expose public float x = 0f;
        @Expose public float y = 0f;
        @Expose public float z = 0f;
        @Expose public float scale = 1f;
        @Expose public float rotationX = 0f;
        @Expose public float rotationY = 0f;
        @Expose public float rotationZ = 0f;
        @Expose public float swingSpeed = 1f;
        @Expose public boolean ignoreMiningEffects = false;
    }

    public static final class Random {
        @Expose
        @ConfigOption(name = "Low Fire", desc = "Lower the burning overlay on your screen so it covers less of the view.")
        @ConfigEditorBoolean
        public boolean lowFire = false;

        @Expose
        @ConfigOption(name = "Fire Height", desc = "How far to lower the fire overlay (0 = vanilla, 1 = off the screen).")
        @ConfigEditorSlider(minValue = 0, maxValue = 1, minStep = 0.05f)
        public float fireOffset = 0.3f;

        @Expose
        @ConfigOption(name = "Hide Explosions", desc = "Hide explosion particles (TNT, Bonzo staff, Wither impact and other server explosions).")
        @ConfigEditorBoolean
        public boolean hideExplosions = false;

        @Expose
        @ConfigOption(name = "Hide Block Break Particles", desc = "Hide the particles when a block breaks (yours or anyone's) and the bits that fly off a block while you mine it, like SkyHanni's.")
        @ConfigEditorBoolean
        public boolean hideBlockBreakParticles = false;

        @Expose
        @ConfigOption(name = "Remove All Fog", desc = "Remove every kind of fog, everywhere: the distance fog, and the fog in water, lava, powder snow, darkness and blindness. Only affects your own screen.")
        @ConfigEditorBoolean
        public boolean removeAllFog = false;
    }

    /** Firmament's storage overlay options (Firmament's StorageOverlay.TConfig). */
    public static final class StorageOverlaySettings {
        @Expose
        @ConfigOption(name = "Storage Overlay", desc = "Firmament's storage overlay (ported from Firmament): /storage, your Ender Chest pages and backpacks open as one scrollable view of every page, with your inventory and a search box. Click a page to open it; the open page works like the normal menu. \"Edit Pages\" shows the normal Storage menu. /s2 storage opens it even with this off.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Storage Value Breakdown", desc = "Show the estimated total value and most valuable items when an Ender Chest page or backpack is open.")
        @ConfigEditorBoolean
        public boolean valueBreakdown = false;

        @Expose @ConfigOption(name = "Dark Mode", desc = "Draw the storage overlay's backgrounds, slots and scroll bar dark instead of Minecraft's light grey.") @ConfigEditorBoolean public boolean darkMode = false;
        @Expose @ConfigOption(name = "Dark Mode Shade", desc = "How dark Dark Mode is: the colour the overlay's textures are tinted with (darker is darker).") @ConfigEditorColour public String darkModeShade = "0:255:70:70:78";
        @Expose @ConfigOption(name = "Outline Active Page", desc = "Put a border around the selected storage page in the storage overlay.") @ConfigEditorBoolean public boolean outlineActivePage = false;
        @Expose @ConfigOption(name = "Outline Colour", desc = "Change the colour of the border around your selected storage page.") @ConfigEditorColour public String outlineActivePageColour = "0:255:255:255:0";
        @Expose @ConfigOption(name = "Inactive Page Tooltips", desc = "Show item tooltips when hovering over items on pages other than the active one.") @ConfigEditorBoolean public boolean inactivePageTooltips = false;
        @Expose @ConfigOption(name = "Columns", desc = "Max columns used by the storage overlay and overview.") @ConfigEditorSlider(minValue = 1, maxValue = 10, minStep = 1) public int columns = 3;
        @Expose @ConfigOption(name = "Storage Height", desc = "The height of the scrollable storage panel.") @ConfigEditorSlider(minValue = 80, maxValue = 3000, minStep = 10) public int height = 3 * 18 * 6;
        @Expose @ConfigOption(name = "Retain Scroll Position", desc = "Retain scroll position when closing storage overlay and overview.") @ConfigEditorBoolean public boolean retainScroll = true;
        @Expose @ConfigOption(name = "Scroll Speed", desc = "Scroll speed inside of the storage overlay and overview.") @ConfigEditorSlider(minValue = 1, maxValue = 50, minStep = 1) public int scrollSpeed = 10;
        @Expose @ConfigOption(name = "Invert Scroll", desc = "Invert the mouse wheel scrolling in the storage overlay.") @ConfigEditorBoolean public boolean inverseScroll = false;
        @Expose @ConfigOption(name = "Padding", desc = "Padding inside of the storage overview.") @ConfigEditorSlider(minValue = 1, maxValue = 20, minStep = 1) public int padding = 5;
        @Expose @ConfigOption(name = "Margin", desc = "Margin inside of the storage overview.") @ConfigEditorSlider(minValue = 1, maxValue = 60, minStep = 1) public int margin = 20;
        @Expose @ConfigOption(name = "Block Scrolling on Items", desc = "Disables scrolling the storage overlay screen while you are hovering over an item. Useful if you have a tooltip scrolling mod.") @ConfigEditorBoolean public boolean itemsBlockScroll = false;
        /** Block Scrolling on Items was on by default before 1.3.4; it is switched off once for settings saved then. */
        @Expose public boolean itemsBlockScrollReset = false;
        @Expose @ConfigOption(name = "Highlight Search Results", desc = "Highlight the search results in the storage overlay.") @ConfigEditorBoolean public boolean highlightSearchResults = true;
        @Expose @ConfigOption(name = "Highlight Search Colour", desc = "Change the colour of the highlighted search result.") @ConfigEditorColour public String highlightSearchResultsColour = "0:255:0:176:0";
    }

    public static final class MouseReset {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Enable automatic mouse reset for selected menus.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Accessory Bag", desc = "Reset the cursor when the Accessory Bag opens.")
        @ConfigEditorBoolean
        public boolean accessoryBag = true;

        @Expose
        @ConfigOption(name = "Ender Chest", desc = "Reset the cursor when an Ender Chest opens.")
        @ConfigEditorBoolean
        public boolean enderChest = true;

        @Expose
        @ConfigOption(name = "Backpack", desc = "Reset the cursor when a Backpack opens.")
        @ConfigEditorBoolean
        public boolean backpack = true;
    }

    public static final class Discord {
        @ConfigOption(name = "Open Discord", desc = "Open Sky2M's personal Discord linking screen.")
        @ConfigEditorButton(buttonText = "OPEN")
        public Runnable open = () -> openDiscord();
    }

    private static void openCustom() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> Sky2MCustom.open(mc, mc.gui.screen()));
    }

    private static void openNotes() {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath().resolve("config");
        mc.execute(() -> mc.gui.setScreen(new Sky2MNotesScreen(dir)));
    }

    private static void openCommandKeys() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(com.epic60869.sky2m.commandkeys.CommandKeys.getConfigScreen(mc.gui.screen())));
    }

    private static void openRngEditor() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Sky2MRngHudScreen(mc.gui.screen())));
    }

    private static void openHudEditor() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Sky2MHudEditorScreen(mc.gui.screen())));
    }

    /**
     * MoulConfig's own button editor, except that HUD Overview also matches every registered HUD's name in the
     * settings search: HUDs are registered at runtime, so they can't be options (or search tags) of their own.
     */
    /** Rebuilds the settings' editors (the Font dropdown lists the fonts folder as it is now). */
    public static void rebuildEditors() {
        if (managed != null) managed.rebuildConfigProcessor();
    }

    private static io.github.notenoughupdates.moulconfig.gui.GuiOptionEditor buttonEditor(
        io.github.notenoughupdates.moulconfig.processor.ProcessedOption option, ConfigEditorButton button) {
        StructuredText text = StructuredText.of(button.buttonText());
        boolean hudOverview = option instanceof io.github.notenoughupdates.moulconfig.processor.ProcessedOption.HasField field
            && field.getField().getDeclaringClass() == Gui.class && field.getField().getName().equals("hudOverview");
        if (!hudOverview) {
            return new io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorButton(option, button.runnableId(), text, option.getConfig());
        }
        return new io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorButton(option, button.runnableId(), text, option.getConfig()) {
            @Override
            public boolean fulfillsSearch(String word) {
                if (super.fulfillsSearch(word)) return true;
                for (var hud : com.epic60869.sky2m.features.core.Sky2MHuds.elements()) {
                    if (hud.name().toLowerCase(java.util.Locale.ROOT).contains(word)) return true;
                }
                return false;
            }
        };
    }


    private static void openDiscord() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Sky2MDiscordScreen(mc.gui.screen())));
    }

    @Override
    public StructuredText getTitle() {
        // "Sky2M Mod v1.2.3": the installed version, from fabric.mod.json.
        String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("sky2m")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("");
        return StructuredText.of("§dSky2M Mod" + (version.isEmpty() ? "" : " §7v" + version));
    }

    // Readable settings: a long description makes its box taller instead of shrinking the text, and an option's name
    // gets the box's full width instead of a third of it (MoulConfig's defaults shrink both until they fit).
    @Override
    public io.github.notenoughupdates.moulconfig.DescriptionRendereringBehaviour getDescriptionBehaviour(
        io.github.notenoughupdates.moulconfig.processor.ProcessedOption option) {
        return io.github.notenoughupdates.moulconfig.DescriptionRendereringBehaviour.EXPAND_PANEL;
    }

    @Override
    public io.github.notenoughupdates.moulconfig.TitleRenderingBehaviour getTitleRenderingBehaviour(
        io.github.notenoughupdates.moulconfig.processor.ProcessedOption option) {
        return io.github.notenoughupdates.moulconfig.TitleRenderingBehaviour.WIDE_CENTERED;
    }

    private static final Gson SNAPSHOT_GSON = new com.google.gson.GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private static String lastSaved;
    private static int saveCheckTicks;

    private static String snapshot() {
        try {
            return managed == null ? null : SNAPSHOT_GSON.toJson(managed.getInstance());
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveIfChanged() {
        if (managed == null) return;
        String now = snapshot();
        if (now == null || now.equals(lastSaved)) return;
        lastSaved = now;
        try {
            managed.saveToFile();
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Writes the bundled default {@code name} (assets/sky2m/defaults/, Sky2M's recommended settings and HUD positions)
     * to {@code target} when it doesn't exist yet: only new players get it, existing configs are never touched.
     */
    public static void copyBundledDefault(Path target, String name) {
        if (Files.exists(target)) return;
        try (var in = Sky2MConfig.class.getResourceAsStream("/assets/sky2m/defaults/" + name)) {
            if (in == null) return;
            Files.createDirectories(target.getParent());
            Files.copy(in, target);
            System.out.println("[Sky2M] New install: starting from the default " + name);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't write the default " + name + ": " + e.getMessage());
        }
    }

    public static Sky2MConfig load(Path path) {
        try {
            migrateLegacy(path);
            // A new player (no config, and none carried over from SkyBalls) starts from Sky2M's chosen defaults.
            copyBundledDefault(path, "sky2m-mod.json");
            migrateConfigShape(path);
        } catch (Exception e) {
            System.err.println("[Sky2M] Legacy config migration failed: " + e.getMessage());
        }

        FileHolder holder = new FileHolder(path);

        var builder = new io.github.notenoughupdates.moulconfig.managed.ManagedConfigBuilder<>(holder.file, Sky2MConfig.class);
        builder.customProcessor(ConfigEditorButton.class, Sky2MConfig::buttonEditor);
        builder.customProcessor(com.epic60869.sky2m.features.misc.CustomFonts.FontDropdown.class, (option, a) ->
            new io.github.notenoughupdates.moulconfig.gui.editors.GuiOptionEditorDropdown(option, com.epic60869.sky2m.features.misc.CustomFonts.choices(), false));
        managed = new ManagedConfig<>(builder);
        // 26.3: mouse keybinds saved as the plain button number move to -100 + it (A-F read as mouse buttons).
        com.epic60869.sky2m.custom.util.Input.migrateMouseBinds(managed.getInstance());

        // Always materialize the current config after loading. This is important
        // for newly-added settings such as Mouse Reset: older Sky2M config files
        // may not contain the new nested section yet, and leaving defaults only
        // in memory makes them appear to reset after a restart.
        try {
            Files.createDirectories(path.getParent());
            managed.saveToFile();
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to persist config after load: " + e.getMessage());
        }

        // MoulConfig calls saveNow() when its GUI closes, which only runs these runnables.
        // Without this, changes made in /s2 were lost unless something else saved later.
        managed.getInstance().saveRunnables.add(managed::saveToFile);

        // Also save whenever any setting changes. Relying on the GUI-close hook alone lost changes
        // (for example the item rarity style) when the screen was closed in ways that skip it.
        lastSaved = snapshot();
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Serialising the whole config to spot a change: every second while a menu (the settings) is open,
            // every 5 seconds otherwise (commands change settings too, but rarely).
            int every = client.gui.screen() != null ? 20 : 100;
            if (++saveCheckTicks % every == 0) saveIfChanged();
        });
        // A change made just before quitting would otherwise wait for the next check.
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveIfChanged());

        // Block Scrolling on Items stopped the storage overlay's wheel as soon as an item scrolled under the mouse.
        // It was on by default until 1.3.4, and settings saved before kept it on: switch it off once.
        StorageOverlaySettings storage = managed.getInstance().inventory.storageOverlaySettings;
        if (storage != null && !storage.itemsBlockScrollReset) {
            storage.itemsBlockScroll = false;
            storage.itemsBlockScrollReset = true;
        }

        return managed.getInstance();
    }

    public static Sky2MConfig current() {
        return managed == null ? null : managed.getInstance();
    }

    /** The saved config file as JSON, for settings cloud sync (saves first so it's up to date). */
    public static JsonObject exportJson() {
        if (managed == null) return null;
        try {
            managed.saveToFile();
            return LEGACY_GSON.fromJson(Files.readString(managed.getFile().toPath(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't read the config for upload: " + e.getMessage());
            return null;
        }
    }

    /**
     * Replaces every setting with {@code settings} (from settings cloud sync). The current file is kept as
     * sky2m-mod.json.bak, and put back if the new settings can't be loaded.
     */
    public static boolean importJson(JsonObject settings) {
        if (managed == null || settings == null) return false;
        Path path = managed.getFile().toPath();
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        try {
            managed.saveToFile();
            Files.copy(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(path, LEGACY_GSON.toJson(settings), StandardCharsets.UTF_8);
            migrateConfigShape(path);
            managed.reloadFromFile();
            com.epic60869.sky2m.custom.util.Input.migrateMouseBinds(managed.getInstance());
        } catch (Exception e) {
            System.err.println("[Sky2M] Couldn't load downloaded settings: " + e.getMessage());
            try {
                Files.copy(backup, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                managed.reloadFromFile();
            } catch (Exception ignored) {}
            return false;
        }
        managed.getInstance().saveRunnables.add(managed::saveToFile);
        // The open editor shows the old instance; build a new one next time.
        editor = null;
        managed.saveToFile();
        lastSaved = snapshot();
        return true;
    }

    /**
     * The config editor, kept between openings so /s2 opens where you left it (same category, scroll position and
     * open sections). MoulConfig's openConfigGui() builds a new editor every time, which always started at the top.
     */
    private static io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor<Sky2MConfig> editor;

    public static void openGui() {
        if (managed == null) return;
        if (editor == null) editor = managed.getEditor();
        io.github.notenoughupdates.moulconfig.common.IMinecraft.INSTANCE.openWrappedScreen(editor);
    }

    /** Opens the settings searched for {@code search} (What's New's settings buttons). */
    public static void openGui(String search) {
        if (managed == null) return;
        if (editor == null) editor = managed.getEditor();
        editor.search(search);
        io.github.notenoughupdates.moulconfig.common.IMinecraft.INSTANCE.openWrappedScreen(editor);
    }

    /**
     * Opens the settings over {@code parent} (the title screen's S2 button). MoulConfig's own wrapper closes to no
     * screen, so Esc goes back to {@code parent} here instead, keeping the title screen's state (and its buttons).
     */
    public static void openGuiOver(net.minecraft.client.gui.screens.Screen parent) {
        if (managed == null) return;
        if (editor == null) editor = managed.getEditor();
        var context = new io.github.notenoughupdates.moulconfig.gui.GuiContext(
            new io.github.notenoughupdates.moulconfig.gui.GuiElementComponent(editor));
        Minecraft.getInstance().gui.setScreen(new io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent(
            net.minecraft.network.chat.Component.empty(), context, parent) {
            @Override
            public void onClose() {
                if (getGuiContext().onBeforeClose() == io.github.notenoughupdates.moulconfig.gui.CloseEventListener.CloseAction.NO_OBJECTIONS_TO_CLOSE) {
                    Minecraft.getInstance().gui.setScreen(parent);
                }
            }
        });
    }

    public static void saveCurrent(Sky2MConfig config) {
        if (managed != null && managed.getInstance() == config) {
            managed.saveToFile();
            return;
        }

        Path path = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config").resolve("sky2m-mod.json");
        config.save(path);
    }

    public void save(Path path) {
        if (managed != null && managed.getInstance() == this) {
            managed.saveToFile();
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, LEGACY_GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Sky2M] Failed to save config: " + e.getMessage());
        }
    }

    private static void migrateLegacy(Path path) throws IOException {
        if (Files.notExists(path)) return;

        String raw = Files.readString(path, StandardCharsets.UTF_8);
        JsonObject old = LEGACY_GSON.fromJson(raw, JsonObject.class);
        if (old == null || old.has("general")) return;

        Sky2MConfig migrated = new Sky2MConfig();
        if (old.has("firstBootAcknowledged")) migrated.general.firstBootAcknowledged = old.get("firstBootAcknowledged").getAsBoolean();

        if (old.has("farmingRngEnabled")) migrated.farming.rng.enabled = old.get("farmingRngEnabled").getAsBoolean();
        if (old.has("farmingRngBackground")) migrated.farming.rng.background = old.get("farmingRngBackground").getAsBoolean();
        if (old.has("farmingRngScale")) migrated.farming.rng.scale = old.get("farmingRngScale").getAsFloat();
        if (old.has("farmingRngX")) migrated.farming.rng.x = old.get("farmingRngX").getAsInt();
        if (old.has("farmingRngY")) migrated.farming.rng.y = old.get("farmingRngY").getAsInt();
        if (old.has("mouseLockEnabled")) migrated.farming.mouseLock.enabled = old.get("mouseLockEnabled").getAsBoolean();
        if (old.has("mouseLockGroundOnly")) migrated.farming.mouseLock.groundOnly = old.get("mouseLockGroundOnly").getAsBoolean();


        Path backup = path.resolveSibling(path.getFileName() + ".legacy-backup");
        Files.move(path, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // Write the migrated object as ordinary JSON. ManagedConfig will load it
        // immediately on the next line.
        Files.writeString(path, LEGACY_GSON.toJson(migrated), StandardCharsets.UTF_8);
    }

    private static void migrateConfigShape(Path path) throws IOException {
        if (Files.notExists(path)) return;
        JsonObject root = LEGACY_GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
        if (root == null) return;
        boolean changed = false;

        // Slayers > Boss Profit Message (on/off) became the Boss Profit section (chat message, HUD, ...).
        if (root.has("slayers") && root.get("slayers").isJsonObject()) {
            JsonObject slayers = root.getAsJsonObject("slayers");
            if (slayers.has("bossProfit") && slayers.get("bossProfit").isJsonPrimitive()) {
                JsonObject profit = new JsonObject();
                profit.addProperty("chat", slayers.get("bossProfit").getAsBoolean());
                slayers.add("bossProfit", profit);
                changed = true;
            }
        }

        if (root.has("pets") && root.get("pets").isJsonObject()) {
            JsonObject pets = root.getAsJsonObject("pets");
            if (pets.has("display") && pets.get("display").isJsonPrimitive()) {
                JsonObject display = new JsonObject();
                display.addProperty("enabled", pets.get("display").getAsBoolean());
                if (pets.has("overflowLevels")) display.add("overflowLevels", pets.remove("overflowLevels"));
                if (pets.has("autoDisplay")) display.add("autoDisplay", pets.remove("autoDisplay"));
                if (pets.has("x")) display.add("x", pets.remove("x"));
                if (pets.has("y")) display.add("y", pets.remove("y"));
                pets.add("display", display);
                changed = true;
            }
        }

        if (!root.has("misc") || !root.get("misc").isJsonObject()) {
            JsonObject misc = new JsonObject();
            root.add("misc", misc);
            changed = true;
        }
        JsonObject misc = root.getAsJsonObject("misc");

        // The Enchanting tab became the Experimental Table section in Misc.
        if (root.has("enchanting") && root.get("enchanting").isJsonObject()) {
            if (!misc.has("experimentalTable")) misc.add("experimentalTable", root.get("enchanting"));
            root.remove("enchanting");
            changed = true;
        }

        if (root.has("farming") && root.get("farming").isJsonObject()) {
            JsonObject farming = root.getAsJsonObject("farming");
            if (farming.has("commissions")) {
                JsonObject mining = root.has("mining") && root.get("mining").isJsonObject()
                    ? root.getAsJsonObject("mining") : new JsonObject();
                if (!mining.has("commissions")) mining.add("commissions", farming.remove("commissions"));
                root.add("mining", mining);
                changed = true;
            }
        }

        // The settings reorganisation: sections that moved keep their saved settings.
        // The Pets tab became a sub-category of Misc.
        if (root.has("pets") && root.get("pets").isJsonObject()) {
            if (!misc.has("pets")) misc.add("pets", root.get("pets"));
            root.remove("pets");
            changed = true;
        }
        // Screenshot Sharing and its host became the Screenshots section (Litterbox hosts are gone: back to the default).
        if (misc.has("screenshotSharing")) {
            JsonObject screenshots = childObject(misc, "screenshots");
            screenshots.add("enabled", misc.remove("screenshotSharing"));
            if (misc.has("screenshotHost") && "CATBOX".equals(misc.get("screenshotHost").getAsString())) screenshots.addProperty("host", "CATBOX");
            changed = true;
        }
        if (misc.remove("screenshotHost") != null) changed = true;
        // Storage Overlay's on/off moved into its settings section.
        if (misc.has("storageOverlay") && misc.get("storageOverlay").isJsonPrimitive()) {
            childObject(misc, "storageOverlaySettings").add("enabled", misc.remove("storageOverlay"));
            changed = true;
        }
        // Price Paid moved into Item Price Tooltip.
        if (misc.has("pricePaid")) {
            childObject(misc, "priceTooltip").add("pricePaid", misc.remove("pricePaid"));
            changed = true;
        }
        // Collection Tracker and its Elite rank became one section.
        if (misc.has("collectionTracker") && misc.get("collectionTracker").isJsonPrimitive()) {
            JsonObject tracker = new JsonObject();
            tracker.add("enabled", misc.remove("collectionTracker"));
            if (misc.has("collectionTrackerRank")) tracker.add("eliteRank", misc.remove("collectionTrackerRank"));
            misc.add("collectionTracker", tracker);
            changed = true;
        }
        // Toggle Sprint and its HUD became one section.
        if (misc.has("toggleSprint") && misc.get("toggleSprint").isJsonPrimitive()) {
            JsonObject sprint = new JsonObject();
            sprint.add("enabled", misc.remove("toggleSprint"));
            if (misc.has("toggleSprintHud")) sprint.add("hud", misc.remove("toggleSprintHud"));
            misc.add("toggleSprint", sprint);
            changed = true;
        }
        // Removed: the warp shortcut list (every warp works now) and Auto Welcome.
        if (misc.remove("warpShortcutList") != null) changed = true;
        if (misc.remove("autoWelcome") != null) changed = true;
        // Combat > Diana Rare Mobs moved to Mayors > Diana.
        if (root.has("combat") && root.get("combat").isJsonObject() && root.getAsJsonObject("combat").has("diana")) {
            JsonObject mayors = childObject(root, "mayors");
            var diana = root.getAsJsonObject("combat").remove("diana");
            if (!mayors.has("diana")) mayors.add("diana", diana);
            changed = true;
        }
        // Dragons and relics, terminals and devices, and the 3x3 platform highlight moved into Dungeons > F7/M7.
        if (root.has("dungeons") && root.get("dungeons").isJsonObject()) {
            JsonObject dungeons = root.getAsJsonObject("dungeons");
            for (String key : new String[]{"witherDragons", "terminals", "platformHighlight"}) {
                if (!dungeons.has(key)) continue;
                JsonObject f7 = childObject(dungeons, "f7");
                if (!f7.has(key)) f7.add(key, dungeons.remove(key));
                else dungeons.remove(key);
                changed = true;
            }
        }

        // Farming > Farming Profit Tracker became Profit Trackers > Farming.
        if (root.has("farming") && root.get("farming").isJsonObject() && root.getAsJsonObject("farming").has("profitTracker")) {
            JsonObject old = root.getAsJsonObject("farming").remove("profitTracker").getAsJsonObject();
            JsonObject farmingTracker = childObject(childObject(root, "profitTrackers"), "farming");
            if (old.has("enabled")) farmingTracker.add("enabled", old.get("enabled"));
            if (old.has("period")) farmingTracker.add("period", old.get("period"));
            JsonObject settings = childObject(farmingTracker, "settings");
            for (String key : new String[]{"pauseAfter", "pauseAfterSeconds", "maximumItems"}) {
                if (old.has(key)) settings.add(key, old.get(key));
            }
            changed = true;
        }

        // SkyHanni's category layout: sections moved between categories keep their saved settings.
        if (LayoutMigration.apply(root)) changed = true;
        JsonObject inventory = childObject(root, "inventory");

        // Materialize Mouse Reset defaults in older configs so the setting
        // is persisted instead of falling back to an in-memory default.
        if (!inventory.has("mouseReset") || !inventory.get("mouseReset").isJsonObject()) {
            JsonObject mouseReset = new JsonObject();
            mouseReset.addProperty("enabled", true);
            mouseReset.addProperty("accessoryBag", true);
            mouseReset.addProperty("enderChest", true);
            mouseReset.addProperty("backpack", true);
            inventory.add("mouseReset", mouseReset);
            changed = true;
        } else {
            JsonObject mouseReset = inventory.getAsJsonObject("mouseReset");
            if (!mouseReset.has("enabled")) { mouseReset.addProperty("enabled", true); changed = true; }
            if (!mouseReset.has("accessoryBag")) { mouseReset.addProperty("accessoryBag", true); changed = true; }
            if (!mouseReset.has("enderChest")) { mouseReset.addProperty("enderChest", true); changed = true; }
            if (!mouseReset.has("backpack")) { mouseReset.addProperty("backpack", true); changed = true; }
        }

        // Changed defaults, applied once to configs saved before them.
        JsonObject general = childObject(root, "general");
        int defaults = general.has("defaultsVersion") ? general.get("defaultsVersion").getAsInt() : 0;
        if (defaults < 1) {
            // Experimental Table > Prevent Misclicks is on by default.
            childObject(inventory, "experimentalTable").addProperty("preventMisclicks", true);
            general.addProperty("defaultsVersion", 1);
            changed = true;
        }
        if (defaults < 2) {
            // Fishing > Alerts > Offer Sharing Found Hotspots is off by default: players asked how to get rid of it.
            childObject(childObject(root, "fishing"), "alerts").addProperty("shareHotspots", false);
            general.addProperty("defaultsVersion", 2);
            changed = true;
        }
        if (defaults < 3) {
            // Dungeons > Cross-Mod Bridge is on by default, with all three sockets.
            JsonObject bridge = childObject(childObject(root, "dungeons"), "crossModBridge");
            for (String key : new String[]{"enabled", "odin", "noamm", "devonian"}) bridge.addProperty(key, true);
            general.addProperty("defaultsVersion", 3);
            changed = true;
        }
        if (defaults < 4) {
            // Experimental Table solvers were saved as off (class defaults were false while the shipped defaults say on).
            JsonObject table = childObject(inventory, "experimentalTable");
            for (String key : new String[]{"chronomatron", "superpairs", "ultrasequencer"}) table.addProperty(key, true);
            general.addProperty("defaultsVersion", 4);
            changed = true;
        }
        if (defaults < 5) {
            // Profile Icons In Chat is on by default from 1.0.4; switch it on once for settings saved before.
            childObject(childObject(root, "chat"), "social").addProperty("profileIcons", true);
            childObject(childObject(root, "combat"), "cocoonAlert").addProperty("enabled", true);
            general.addProperty("defaultsVersion", 5);
            changed = true;
        }
        if (defaults < 6) {
            // The cocoon title is back to important mobs only; every cocoon still gets its label.
            childObject(childObject(root, "combat"), "cocoonAlert").addProperty("onlyImportant", true);
            general.addProperty("defaultsVersion", 6);
            changed = true;
        }
        if (defaults < 7) {
            // Misc > Fonts: the built-in Inter is now the real Inter .ttf, which matches Minecraft's text at size 9 and
            // sharpness 6; settings still on the first defaults (10 and 4) get the new ones.
            JsonObject fonts = childObject(childObject(root, "misc"), "fonts");
            if (!fonts.has("size") || fonts.get("size").getAsFloat() == 10f) fonts.addProperty("size", 9f);
            if (!fonts.has("sharpness") || fonts.get("sharpness").getAsFloat() == 4f) fonts.addProperty("sharpness", 6f);
            general.addProperty("defaultsVersion", 7);
            changed = true;
        }

        if (changed) Files.writeString(path, LEGACY_GSON.toJson(root), StandardCharsets.UTF_8);
    }

    /** {@code parent.key} as an object, created if it's missing. */
    private static JsonObject childObject(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) parent.add(key, new JsonObject());
        return parent.getAsJsonObject(key);
    }

    /**
     * Moves saved settings from the old category layout to SkyHanni's (About, GUI, Garden, Crimson Isle, ...,
     * Inventory, Events, ..., Misc). Only uses Gson, so it can be checked outside the game. Idempotent: a setting is
     * moved only when its old path exists, and never over a value already at the new path.
     */
    static final class LayoutMigration {
        /** {old path, new path}, dot separated, in the order they are applied. */
        static final String[][] MOVES;

        static {
            java.util.List<String[]> moves = new java.util.ArrayList<>();
            moves.add(new String[]{"misc.updateNotifications", "general.updateNotifications"});
            moves.add(new String[]{"general.hudTheme", "gui.hudTheme"});
            for (String key : new String[]{"customScoreboard", "tabListExtras", "statusBars", "serverInfoDisplay",
                "speedDisplay", "inventoryHud", "radialMenu", "crosshair", "slotLabels"}) {
                moves.add(new String[]{"misc." + key, "gui." + key});
            }
            for (String key : new String[]{"kuudra", "ashfang"}) moves.add(new String[]{"combat." + key, "crimsonIsle." + key});
            for (String key : new String[]{"bazaar", "priceTooltip", "economy", "collectionTooltips", "enchantParsing",
                "itemRarity", "enchantedBookLabels", "revertMasterStars", "packDisabler", "shardLevelUpHighlight",
                "abilityTimers", "itemQuality", "itemCooldowns", "tooltipScroll", "calendarTimeToRealTime",
                "storageOverlaySettings", "inventoryButtons", "slotLocking", "wardrobeHotkeys", "loadoutHighlight",
                "mouseReset", "experimentalTable", "itemList", "signCalculator", "signEnterConfirms", "searchOverlay",
                "signAutofill", "auctionGuard", "sacksValue", "itemPickupLog", "itemNotification"}) {
                moves.add(new String[]{"misc." + key, "inventory." + key});
            }
            moves.add(new String[]{"general.itemCustom", "inventory.itemCustom"});
            moves.add(new String[]{"mayors.diana", "events.diana"});
            moves.add(new String[]{"mayors.mayor", "events.mayor"});
            moves.add(new String[]{"profitTrackers", "misc.profitTrackers"});
            moves.add(new String[]{"clips", "misc.clips"});
            MOVES = moves.toArray(new String[0][]);
        }

        private LayoutMigration() {}

        /** Applies every move to {@code root}; true if anything changed. */
        static boolean apply(JsonObject root) {
            boolean changed = false;
            for (String[] move : MOVES) changed |= move(root, move[0], move[1]);
            // The Mayors category is gone: Diana and Mayor & Election are in Events now.
            if (root.has("mayors") && (!root.get("mayors").isJsonObject() || root.getAsJsonObject("mayors").size() == 0)) {
                root.remove("mayors");
                changed = true;
            }
            return changed;
        }

        private static boolean move(JsonObject root, String from, String to) {
            String[] source = from.split("\\.");
            JsonObject sourceParent = existing(root, source);
            String key = source[source.length - 1];
            if (sourceParent == null || !sourceParent.has(key)) return false;
            com.google.gson.JsonElement value = sourceParent.remove(key);
            String[] target = to.split("\\.");
            JsonObject targetParent = root;
            for (int i = 0; i < target.length - 1; i++) {
                if (!targetParent.has(target[i]) || !targetParent.get(target[i]).isJsonObject()) targetParent.add(target[i], new JsonObject());
                targetParent = targetParent.getAsJsonObject(target[i]);
            }
            // A value already at the new path wins; the stale old one is dropped (MoulConfig would drop it anyway).
            if (!targetParent.has(target[target.length - 1])) targetParent.add(target[target.length - 1], value);
            return true;
        }

        /** The object holding the last part of {@code path}, or null if any part on the way is missing. */
        private static JsonObject existing(JsonObject root, String[] path) {
            JsonObject at = root;
            for (int i = 0; i < path.length - 1; i++) {
                if (!at.has(path[i]) || !at.get(path[i]).isJsonObject()) return null;
                at = at.getAsJsonObject(path[i]);
            }
            return at;
        }
    }

    private static final class FileHolder {
        private final java.io.File file;
        private FileHolder(Path path) {
            this.file = path.toFile();
        }
    }
}
