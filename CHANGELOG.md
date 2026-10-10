# Changelog

All notable changes to Sky2M for Minecraft 26.3 are listed here, newest first. The 26.3 build has its own version numbers, starting again at 1.0; the 26.2 build keeps its own changelog.

## 1.0 — 2026-10-10

The first Sky2M release: SkyBalls is now Sky2M. Commands are /s2 and /s2m (/sb and /skyballs are gone), chat messages start with [S2M], and your SkyBalls settings, keybinds and data are copied over the first time you start it. Remove the old SkyBalls jar from your mods folder.

### Added
- Terminal Solver Enabled (Dungeons > F7/M7 > Terminals and Devices, on by default): switch the terminal solver off, so terminals look and click like normal.
- SkyBlock Only (General), from Killer560's Mod: one switch that pauses every Sky2M feature outside SkyBlock (the lobby, other games and servers). Singleplayer still works, and there's a few seconds' grace while switching servers so nothing flickers.
- Custom Crosshair (Misc > Visual > Custom Crosshair), from Killer560's Mod: design your own crosshair (cross, T, X, dot, circle, cross and circle, or a picture), every size, colour (chroma too), outline and rotation, colours when looking at a mob or block, and spread when you move, sprint, jump or swing. Import a Valorant crosshair code, a CS2 share code, a KXH1 code or a PNG from your clipboard, export yours as a code, or start from a preset.
- Pack Disabler (Misc > Items & Tooltips), from Killer560's Mod: SkyBlock items drawn the way they looked before Hypixel's 2026 resource pack, as their old vanilla item (so your own resource packs draw them), their old head, or a new texture for items that never had one. Hypixel's pack still loads; only item looks change. Off while Noamm's PackDisabler is installed.
- Revert Master Stars (Misc > Items & Tooltips), like Killer560's Mod: Master Stars shown the old way, as that many of the item's stars in red instead of the new numbered star.
- GIF Player (Misc > HUDs > GIF Player), from Killer560's Mod: every GIF in config/sky2m/gifs plays on loop as its own HUD you can move and resize in /s2 gui, with a speed setting, a size limit and a list of GIFs to hide.
- DVD Screensaver (Misc > Visual > DVD Screensaver), from Killer560's Mod: text or a GIF bouncing around your screen like an old DVD player, with up to 10 at once, new colours on walls or corners, and a sound and message when it hits a corner exactly.
- Trail (Misc > Visual > Trail), from Killer560's Mod: a trail of squares behind you as you move, in one colour on the ground and another in the air, with its length, square size, lifetime and fade.
- Auto Join SkyBlock (Misc > Hypixel & Commands), like SkyHanni: when you join Hypixel you go straight into SkyBlock instead of staying in the main lobby. Only once per join, so /lobby still works.
- Enchanted Book Labels (Misc > Items & Tooltips, on by default): two letters in the top right of enchanted books naming their enchantment (Sh for Sharpness, UW for Ultimate Wise, in pink for Ultimate enchantments), so you can tell them apart without hovering. Books with several enchantments aren't labelled.
- Discord Rich Presence (Misc > Discord & Screenshots, on by default): your Discord profile shows you're playing Sky2M, with where you are on SkyBlock and for how long. Needs the Discord app open; Show Location in Discord turns the location off.
- Collection Portfolio (Misc > Collections, on by default): your collections are saved every 30 minutes on SkyBlock so /s2 collections can show how much each one went up over the last day, week or month. Needs your Collections API on.
- Livid Solver (Dungeons, F5/M5), from Odin: once the wool above the arena shows which Livid is real, a chat line names her and she's boxed in a colour you choose (not while you're blind), plus a HUD counting down her invulnerability.
- Terracotta Timer (Dungeons, F6/M6), from Odin: seconds until each terracotta respawns, over its flower pot.
- Blessing Display (Dungeons), from Odin: a HUD with the dungeon's blessings and their levels from the tab list, each with its own toggle and colour. Move it in /s2 gui.
- Relic Place Timer (Dungeons > F7/M7 > Dragons and Relics), from NoammAddons: once all five relics are placed, who placed each one and how long after the Wither King appeared, with your PB for your relic.
- Spawn Timer also shows your priority dragon's timer big in the middle of the screen, like NoammAddons.
- Garden Visitors (Farming > Visitors), from SkyHanni: the Visitor Shopping List (what your visitors need, with prices, how many you have in your sacks, "Craftable!" and the items new visitors usually want; also in the visitor's menu, the Bazaar, your inventory and number signs, and optionally in the Bazaar Alley and farming areas), the Visitor Timer (visitors waiting and time until the next one, with the sixth visitor estimate, a warning and an optional ping), prices in the offer's tooltip (each item, coins per copper and per Garden Experience, and the base crops needed), the Reward Warning (a chat message and a warning over the visitor for rare rewards, and blocking refusing or accepting by reward, copper price, coin loss or never-accepted visitors; hold the bypass key to click anyway), status highlights on the visitors (new, items ready, accepted, refused), arrival title and chat message, hidden visitor chat and Hypixel's arrival message, compact reward messages, an accept hotkey, a Supercraft button, Drops Statistics, Charmed Visitors, the Jacob/Anita NPC fix, and highlighting or blocking visitors outside the Garden. Visitor names, rarities and wanted items come from SkyHanni's repo.
- Enchant Parsing (Misc > Enchant Parsing), from SkyHanni: enchants in tooltips coloured by level (perfect enchants in moving chroma by default, then great, good and poor), ultimate enchants bold, sorted, laid out Normal, Compressed or Stacked, with stacking enchants' progress and an option to hide enchant descriptions. Enchant levels come from SkyHanni's repo.
- Stash Messages (Chat > Stash Messages), from SkyHanni: Hypixel's stash reminder becomes one line you can click to pick up (or view) your stash, repeats of the same counts are hidden, and so is "One or more items didn't fit in your inventory...".
- Hook & Bobber (Fishing), from SkyHanni: the Fishing Bobber Timer (how long your bobber has been out, from the cast or from when it lands) and the Fishing Hook Display (Hypixel's reel-in countdown and "!!!" bigger on your screen, with the original hidden). Move and resize them in /s2 gui.
- Bestiary Overlay (Combat > Bestiary Overlay), from SkyHanni: in the Bestiary menu, each family's kills and progress to max or to the next tier (hover for the details), sorted by global progress, total kills or kills needed, and each category's families found and completed, with clickable Number Format, Display Type, Number Type (Roman or normal) and Hide Maxed buttons. Maxed families and categories are highlighted green, and if Overall Progress is hidden the overlay asks you to turn it on and highlights its Eye of Ender red. Move and resize it in /s2 gui.
- Highlight Withers (Dungeons > Mobs), like NoammAddons' Wither ESP but not ESP: a box around the F7/M7 boss withers you can see, hidden behind walls, in a colour you choose.
- Highlight Teammates (Dungeons > Mobs): your dungeon teammates glow in their class colour, the same colours as the Leap Menu.
- Highlight Active Pet (Misc > Pets): your summoned pet is highlighted in the Pets menu. Its colour can be changed.
- Sack amounts are remembered (from the sack menus and the "[Sacks]" messages) for the visitor shopping list.
- Protect Item key (Options > Controls > Sky2M, unbound by default): runs /s2 protect on the item you're holding, or on the item under your mouse in any menu.
- Estimated Item Value (Misc > Item Price Tooltip, on by default), like SkyHanni's: below the lowest BIN and 3 day average, the item's price plus what's applied to it: recombobulator, hot and fuming potato books, enchantments, master stars, gemstones, Art of War and Art of Peace, scrolls, dyes, skins, rune, drill parts, Wood Singularity, Farming for Dummies, Polarvoid, Transmission Tuners, Mana Disintegrators, Jalapeño, Etherwarp, Book of Stats, Divan's Powder Coating and talisman enrichment, each at its bazaar insta-buy price or lowest BIN.
- Bazaar Order Colours (Misc > Bazaar, on by default), like Bazaar Utils: in your Bazaar orders menu each order's slot is green when it's the best price, yellow when another order matches its price, and red when it's been outbid. Fully filled orders aren't coloured.
- Highlight Infested Plots (Farming > Garden > Pest Finder, on by default): plots with pests are red in Configure Plots (/desk).
- Plot Teleport Panel (Farming > Garden > Pest Finder, on by default), like Skyblocker's Garden Plots widget: in the Garden, a map of your plots to the right of your inventory. Click a plot to /plottp there; plots with pests are red with their pest count, locked plots grey and the plot you're in outlined, and "TP to Pests" warps to the nearest plot with pests.
- Shard Level Up Highlight (Misc, on by default): attribute shards you own enough of to level up or unlock are highlighted green in the Hunting Box and the Attribute Menu (open the Hunting Box first so the menu knows how many you have).
- Show Pet Level (Misc > Pets, on by default): each pet's level on its slot in the Pets menu, in gold when it's maxed.
- Spirit Bear (Dungeons > Timers and Alerts, on by default), from Odin: in the F4 and M4 boss, a HUD with the spirits killed (25 on F4, 30 on M4), then the countdown until the Spirit Bear spawns once the last one dies, then "Alive!". Move it in /s2 gui.
- What's New (General > What's New After Updates, on by default), like SkyHanni: after Sky2M updates, a chat message with a [What's New] button opens a list of the new features, each with an ON/OFF toggle and a button to its settings, plus All On / All Off. /s2 whatsnew opens it any time.
- Party Finder Stats (Dungeons > Party Finder Stats), from Odin's Better Party Finder: when someone joins your group from the Party Finder, their Catacombs level, class levels, secrets (and per run), watcher kills, floor times (hover Normal or Master), magical power, armour (hover each piece) and missing key items (Wither Blade, Terminator, Golden and Ender Dragon) are shown in chat, only to you, with a [KICK] button. Optional Auto Kick by S+ time on a floor, secrets, Catacombs level and magical power, remembering who it kicked. /s2 cata <name> shows anyone's.
- Auto Requeue (Dungeons > Auto Requeue), from NoammAddons: when a run ends, the party goes into another run of the same floor after a delay you set, unless someone left. /s2 requeue cancel skips the next one.
- Item Pickup Log (Misc > HUDs > Item Pickup Log), from SkyHanni: a HUD of the items you just picked up (+) and lost (-), sacks included, with SkyHanni's options: compact lines and numbers, sacks, shards, coins, the coin value of what you picked up, alignment, layout and how long lines stay. Moving items around your inventory isn't logged.
- Translator (Chat > Translator), from SkyHanni: click a player's chat message to translate it into your language. The translation only shows in your chat; nothing is sent. /s2 translate <language> <message> translates your own message and copies it, ready to paste.
- Bazaar & AH Search (Misc > Inventory & Storage), like Skyblocker: clicking Search in the Bazaar or Auction House opens a search box instead of the sign, suggesting item names as you type (only bazaar items in the Bazaar) and your last searches.
- Inventory HUD (Misc > HUDs > Inventory HUD), from Killer560's Mod: your inventory as a HUD you can move and resize in /s2 gui.
- Slayer Minibosses (Slayers, on by default): the slayer HUD also lists the minibosses near you, and "SLAYER BOSS" or "SLAYER MINIBOSS" is written above their name and health.
- Right-click a HUD in /s2 gui to open its settings, like SkyHanni. Middle-click (or shift and right-click) turns its background on or off.
- Discord App ID (Misc > Discord & Screenshots): Discord Rich Presence needs a Discord application's ID to show anything; paste yours here.

### Changed
- The /s2 menu is reorganised: the categories are in a clearer order (General, Chat, Online, Dungeons, Slayers, Combat, Mayors, Farming, Mining, Fishing, ...) and their settings are grouped into dropdowns and sub-dropdowns. Misc is split into Items & Tooltips, Inventory & Storage, HUDs, Collections, Visual, Hypixel & Commands and Discord & Screenshots; Puzzle Solvers has a dropdown per puzzle, and Secrets, Timers, Score, Mobs, Chest Profit, Diana, the slayers, Fishing and more are grouped the same way. Your settings are kept.
- Offer Sharing Found Hotspots (Fishing > Alerts > Hotspots & Wormholes) is now off by default (and switched off once on update), and its chat message has a [Turn off] button.
- Shard Level Up Highlight remembers your shard amounts between sessions and matches shards by item, so the Attribute Menu works without opening the Hunting Box every time. If it doesn't know your amounts yet, it says to open the Hunting Box once.
- Performance: storage pages are decoded once instead of every few seconds (the recipe HUD and storage searches stuttered with a lot of storage), and the scoreboard, tab list, sidebar and HUD text are read with less work every tick and frame.
- Show Pet Level has its own Show Overflow Pet Level toggle (Misc > Pets), instead of following the Pet Display's Overflow Pet Levels setting.
- Door Highlight shows wither and blood doors through walls (filled and outlined), and only once they show on your dungeon map, like Odin: a door appears when a room next to it is opened.
- Spirit Bear, the Livid Solver, the Terracotta Timer and the Blessing Display are on the Dungeons page itself, so you don't have to look through the sub-categories for them.
- M7 dragon priority follows NoammAddons for the Minister update, where dragons spawn one after another: your priority is the dragon that spawns first, and the new Solo Priority (Healer or Tank) takes the second one, on the first two dragons only unless First Dragon Only is off. The Split Power and Easy Power settings are gone.
- Dungeon chest profit is now NoFrills' Dungeon Chest Value and Croesus Solver: the reward chest's value over the chest, Croesus's runs coloured unopened, rerolled (Kismet Feather), opened or opened with a key, with their floor on them, and in a run's chests the most profitable one highlighted (pink for very valuable chests or a dye) and the second best too (aqua when it's still worth a Dungeon Chest Key), with each chest's value in its tooltip. Its colours and the high-profit threshold can be changed.
- Goldor's death tick timer counts every 60 ticks (it was 50, which drifted), starts from Storm's death too and stops when the core opens, like Odin. The Goldor Tick Period setting is gone.
- /s2 protect marks protected items with a smaller star only; the green box around them is gone.
- The Deployable Timers HUD works without Item Cooldowns turned on, and also times Warning and Alert Flares.
- SkyBalls is now Sky2M: commands are /s2 and /s2m, chat messages start with [S2M], and the old /sb, /skyballs and /sbc commands are gone (/sbc is /s2c). Settings, keybinds and data in config/skyballs are copied to config/sky2m once.
- Update checks look at the Sky2M releases on GitHub (2m3s/Sky2M-26.2 and 2m3s/Sky2M-26.3).
- Party commands (!warp, !pt, !f7, ...) are always run, even when Sky2M thinks someone else leads: if you don't, Hypixel says so instead of the command silently doing nothing.
- Deployable Timers finds power orbs and flares placed near you (yours or anyone's), like SkyblockAddons, instead of only ones you placed.
- Slot Locking: binds can be finished by clicking the second slot, bound slots get a border, and Always Show Binds shows every bind in your inventory (Killer560's).

### Fixed
- /s2 recipe showed 0/0 for every ingredient when you already had the item: the ingredients now show what crafting it takes, with "You already have N" above them.
- Server Info Display: TPS showed "--" on Hypixel.
- Tooltip Scroll: the mouse wheel didn't move tooltips that fit on screen, like normal item tooltips. Those now move when the screen isn't using the wheel.
- Storage overlay: the mouse wheel didn't scroll it while another mod's item list (SkyBlock Item List) was under the mouse.
- The game lagged when a bestiary message (or any burst of chat) came in: compact chat compared every new line with your whole chat history (huge with More Chat History) and rebuilt the chat when the bestiary's two separator lines matched. It now only looks at the last 5 seconds and never compacts separator lines, and nickname replacement skips names that aren't in the message.
- Terminals: the vanilla terminal menu no longer shows for a split second when a terminal opens or Hypixel reopens it.
- Starred mob boxes no longer go on teammates standing next to a starred mob (often the Mage and Tank).
- /s2 log showed the 26.2 changelog for 26.3 versions.
- Deployable Timers HUD never showed power orbs (Overflux, Plasmaflux, Mana Flux, Radiant) or flares: they have no cooldown in their lore, so the timer was never started. Flares now last their real 3 minutes (the SOS Flare showed 30s), and power orbs' timers follow the time left on the orb's name tag.
- Bazaar order notifications compared buy orders with the lowest sell offer (and sell offers with the top buy order), so buy orders were nearly always called outbid. Hypixel's API lists buy orders under "sell_summary"; they're now read the right way round.
- The boss-target hotkey could target someone else's slayer boss; the slayer HUD now follows the boss you targeted.
- Hypixel Button didn't show over custom menus (Sky2M's and other mods' screens); it now draws on top of them.
- Terracotta Timer (F6/M6): the timer stayed on the flower pot; it's above the terracotta once it's grown.
- Discord Rich Presence never showed: it had no Discord application to connect to. Set Discord App ID.

## SkyBalls 1.1 — 2026-10-04

### Added
- Pest Spawn Timer (Farming > Garden > Pest Spawn Timer, on by default, while holding a farming tool), from SkyHanni: time since the last pest spawned, the pest cooldown and the average time pests take to spawn (leaving out spawns where you were AFK). The cooldown counts down the Pest Cooldown Time you set from each pest spawn; it doesn't read the tab list. Optional warnings a few seconds before the cooldown ends and when it's over (title, chat and a sound you choose), repeating until you open your wardrobe or loadouts, and a chat message with how long each spawn took. Show it only while holding a farming tool, vacuum or lasso.
- Warp to infested plots (Farming > Garden > Pest Finder), from SkyHanni: a Teleport Hotkey and /shtpinfested (/s2tpinfested if SkyHanni is installed) warp you to the nearest plot with pests with /plottp. Optionally even when you're already in one, or back to the Garden when there are no pests. Which plots have pests is tracked from the sidebar, the tab list's Pests widget, the Configure Plots menu and the pest spawn, kill and "no pests" messages; open Configure Plots once so renamed plots and the Barn are known.
- Mute Overflow Drop Sound (Farming > Garden, on by default): no nether portal sound with "OVERFLOW! Your ... has just dropped a Tool Exp Capsule!".
- Loadout Highlight (Misc, on by default), from SkyHanni: the loadout you have equipped is highlighted in the Loadouts menu, in a colour you choose.
- Party commands: !pt, !promote and !demote take the start of a name (!pt nix transfers to NixJussid), and there's a new !kick (!kick nix kicks NixJussid, never you), from the party members Sky2M has seen in party messages and /p list. If several members start with it, it says so in party chat instead.
- Pest Spawn alert (Farming > Garden > Pest Spawn), from SkyHanni: a title naming how many pests spawned and in which plot, Hypixel's spawn message kept, replaced by a compact one you can click to /plottp there, or hidden, and the spawn sound kept, muted, replaced by your own sound or by the Plumber tune.
- Skill Progress is now SkyHanni's full version (Skill Progress settings, off by default): the progress display with level, skill icon, XP gained and progress or percentage and actions left, aligned over a progress bar (plain colour, chroma, or textured like the XP bar with five extra textures); the Skill ETA display with XP/hour, time to the next level or your goal, and the session timer (click to reset); the All Skills display (hover for XP, click to open /skills); overflow levels past the cap, with the overflow level-up message; custom goal levels with /shskills goal <skill> <level> (also /shskills levelwithxp and xpforlevel; /sbskills if SkyHanni is installed); overflow and goal progress in the Your Skills menu's tooltips; XP from Jerry Boxes, gifts and Lily-splosions; and hiding the skill XP in the action bar.

### Removed
- The old Pest Cooldown HUD and "Pests Spawned!" alert; the Pest Spawn Timer and Pest Spawn alert replace them, and keep your Pest Cooldown Time.

## SkyBalls 1.0.1 — 2026-10-04

### Fixed
- Sky2M chat, ranks, login, bug reports, crash reports, screenshots, the event calendar, capes and Discord linking use the new server, shadowisabot.com (tastyfish.org no longer runs it, which caused "Couldn't check for bug reports: ... malformed JSON").
- Nickname colours now show for you and other players in Sky2M chat, Hypixel chat, tab and nametags: a nickname update with an empty (null) custom colour or font used to be dropped entirely, so the nickname appeared without its colour. Colour names are also read in any spelling ("Dark Blue", "dark_blue", "DarkBlue"), custom colours with or without "#", and a "Plain" nickname with no colour keeps the colour the name had.
- When the server turns Discord down, the Discord screen says so with the server's reason instead of staying on "Checking Discord connection...", and a failed Discord DM gives a clear message.

## SkyBalls 1.0 — 2026-10-03

The Minecraft 26.3 build starts again at 1.0, and checks for updates on its own releases (2m3s/sky2m-26.3).

### Added
- Skill Progress (Skills), from SkyHanni: the skill you're getting XP in with its level, a progress bar, percent and XP to the next level, XP per hour and the time until you level up.
- Party Coord Waypoints (Mayors > Diana, on by default), like SkyHanni: coordinates a party member sends ("x: -30, y: 87, z: 126") get a beacon waypoint with their name and distance, until you reach it or for a minute.
- Dungeon Chest Profit (Dungeons > Chest Profit): a reward chest's value minus its cost above the chest; at Croesus, each chest's profit with the best one highlighted, and the list of runs tinted green (unopened), yellow (one chest opened) or red (all claimed).
- Highlight Bats (Dungeons > Mobs), and /s2 debug starred to see what the starred mob highlight finds.
- Blood Camp Beam Time: the mob kill countdown (and its box) turns red at the time to shoot your Mage beam, 0.2s by default, like Odin and NoammAddons.
- /pt Name runs /p transfer Name. Minecraft's advancement and recipe popups are always hidden.
- Hide Block Break Particles (Misc > Random), like SkyHanni's: no particles when a block breaks, yours or anyone's, and none flying off the block you're mining.

### Changed
- Diana Profit Tracker is laid out like SBO's Diana loot tracker: every rare drop always listed with its count, lootshares, rate per mob it drops from and value, then your other drops, coins, burrows, mobs, profit (and per hour) and playtime.
- Arrow Counter only shows while you hold a bow or crossbow (Arrow Counter Only With Bow, on by default).
- Shorter dropdown labels in the settings, so they're no longer shrunk to fit (e.g. "Own + Party", "Insta-Sell", "24h + sec", "In Quest"). What each choice means is in the option's description.
- [item] in plain chat now reads "[Heroic Hyperion](sb:HYPERION)" instead of a coded blob, so players without Sky2M can read it; Sky2M players still see the hoverable item. Old [item] links still work.

### Fixed
- /s2 protect's box and star no longer draw over tooltips and the item on your cursor.
- Pet Display: a pet favourited in the Pets menu (⭐) never showed its held item on the icon, as its name didn't match the tab list's.
- Settings search (and other MoulConfig text boxes) couldn't be typed in on 26.3.
- !pt Name transfers the party to Name; leader commands work when the game started while you were already in a party (it checks /p list).
- Starred mob boxes no longer need a clear line of sight to the mob's head (they're hidden behind walls anyway).
- Party Commands (!warp, !pt, !f7, !fps, !ping, the Diana ones...) didn't answer anyone with a Hypixel emblem (☘, ☣...) before their name, so they never worked for you. Your own !warp, !allinvite and !f7 / !m7 work too now while you're leader.
- Blaze puzzle solver: blazes' names now have a symbol before "Blaze", which it didn't expect, so it found none. The kill order now follows where the blazes are (low in the room: highest HP first), like Skyblocker.
- Pet Display and other item icons: items Hypixel lists with old 1.8 names (Raw Fish, Ink Sack dyes, Lily Pad, Dandelion, wool, glass...) showed as barriers or the wrong item; newer paper-based items (Winding Ivy, Sloth Claws...) use Hypixel's model or a downloaded icon instead of paper. A held "Saddle" is the pet item, not the plain saddle.
- Shared items (Sky2M chat, [item], [inv]) were drawn as paper even when their icon was available.
- [inv]: clicking an item in a shared inventory shows it big with its tooltip (Esc goes back to the inventory).
- [item] with a pet, enchanted book or rune (ids with ";") showed as raw text instead of the item.
- /s2 who: a presence update from the server no longer empties the online list, and the screen says so when the server doesn't answer instead of loading forever. Errors from the Sky2M server are now shown instead of being dropped silently.
