# Third-party notices

## Firmament storage overlay

Sky2M's storage overlay (the `com.epic60869.sky2m.features.misc.storage` package and the Sky2MCustomGuiScreenMixin, Sky2MCustomGuiContainerMixin, Sky2MSlotCoordsMixin and Sky2MScreenChangeMixin mixins) is ported from Firmament:

- https://github.com/FirmamentMC/Firmament (branch mc-26.1)
- Copyright Linnea Gräf <nea@nea.moe> and Firmament Contributors
- Relevant source: src/main/kotlin/features/inventory/storageoverlay/, src/main/kotlin/util/customgui/, src/main/java/moe/nea/firmament/mixins/customgui/ and mixins/ScreenChangeEventPatch.java
- Code license: GNU General Public License v3.0 or later (GPL-3.0-or-later). Because of this, Sky2M as a whole is distributed under GPL-3.0-or-later (see LICENSE).
- Assets: the storage overlay sprites under assets/sky2m/textures/gui/sprites/storageoverlay/ are Firmament's, licensed under Creative Commons Attribution 4.0 (CC-BY-4.0), by Linnea Gräf and Firmament Contributors.

Each ported source file keeps a Firmament attribution and SPDX header. The Kotlin code was rewritten in Java; Firmament's MoulConfig search field and button are vanilla widgets here, and its storage data is saved in config/sky2m/storage-overlay.json.

## NopoMod emoji assets

Sky2M's integrated chat-emoji renderer uses the emoji sprite set and emoji-name data from NopoMod.

Upstream project: https://github.com/NopoTheGamer/NopoMod
Pinned source commit: 3243c18910c1b281f6585cd4657b349df4c60d50

The Gradle build fetches those upstream assets into the generated resource directory rather than copying the binary sprites into this repository. The upstream project's LICENSE applies to those assets/source materials.

The Sky2M feature implementation itself is a separate Java implementation and does not copy NopoMod's Kotlin feature architecture.

The pet display's overflow-level calculation from the tab list (adding the XP of the capped levels to the "+N XP" overflow line and recomputing the level on the legendary curve, and the progress line format) follows NopoMod's `features/pets/PetDisplay.kt` and `OverflowPetLevels.kt`, licensed under the GNU Lesser General Public License v2.1.

The "Took N bosses to drop" slayer tracker (the kill and drop patterns, the one-second wait before counting a drop, counting Rift drops as Vampire slayer and skipping unknown drops after a world change) follows NopoMod's `features/slayer/BossesSinceDrop.kt`, licensed under the GNU Lesser General Public License v2.1.

## Skyblocker

Sky2M's /s2 custom item and armor customization (the `com.epic60869.sky2m.custom` package and its mixins) is ported from Skyblocker:

- https://github.com/SkyblockerMod/Skyblocker
- Version: v6.10.4+26.2
- Relevant source: src/main/java/de/hysky/skyblocker/skyblock/item/custom/, the GUI utilities it uses under src/main/java/de/hysky/skyblocker/utils/, and the DataComponentHolder, DyedItemColor, ItemStack and EquipmentLayerRenderer mixins
- Assets: the customization screen sprites under assets/sky2m/textures/gui/ and the related en_us translations

Skyblocker is licensed under the GNU Lesser General Public License v3.0 (LGPL-3.0).

Each ported source file keeps a Skyblocker attribution line. Sky2M replaces Skyblocker's config, NEU repository and scheduler dependencies with its own small implementations.

Sky2M's Item Price Tooltip follows Skyblocker's AvgBinTooltip, LBinTooltip and NpcPriceTooltip (src/main/java/de/hysky/skyblocker/skyblock/item/tooltip/adders/) and reads auction prices from the same API (hysky.de). The container menu mixin that notifies slot listeners (used by the Chronomatron solver) follows Skyblocker's AbstractContainerMenuMixin.

Sky2M's museum and accessory tooltips (`features/misc/MuseumTooltip` and `features/misc/AccessoryTooltip`) follow Skyblocker's MuseumTooltip, MuseumItemCache, AccessoryTooltip and AccessoriesHelper (src/main/java/de/hysky/skyblocker/skyblock/museum/, skyblock/accessories/ and skyblock/item/tooltip/adders/): the tooltip text and colours, the accessory family and tier report, and reading your accessories from the Accessory Bag. The accessory list comes from the same API (hysky.de) and the museum item data from NEU's constants/museum.json. Instead of Skyblocker's signed-in Hypixel API proxy, donated museum items are read from the museum menus.

Mouse Lock (`Sky2MMouseLock`) follows Skyblocker's garden mouse lock (src/main/java/de/hysky/skyblocker/skyblock/garden/LowerSensitivity.java): its farming tool list (FarmingHudWidget.FARMING_TOOLS) and the barn area where the lock pauses.

## CommandKeys

Sky2M's /s2 keys GUI and macro system is a port of TerminalMC/CommandKeys (the `com.epic60869.sky2m.commandkeys` package, its mixins, the `sky2m_commandkeys` assets and translations, and the MultiLineEditBox entry in `sky2m.classtweaker`).

- https://github.com/TerminalMC/CommandKeys
- Branch: mc26.2
- License: Apache License 2.0 (a copy is included at assets/sky2m_commandkeys/LICENSE.txt)

Changes made for Sky2M: repackaged, mod ID changed to `sky2m_commandkeys`, the command moved to /s2 keys, the multi-platform service loader replaced with a Fabric implementation, and a one-time import of Sky2M's previous command key macros added. Each modified source file keeps its original license header with a note of these changes.

Sky2M's rarity item backgrounds, calendar date calculator and commission HUD style are ported from Skyblocker (see above).

## Skyblocker dungeon and experimentation features

Sky2M's dungeon map, puzzle solvers, secret waypoints, room detection, terminal and device solvers, and experimentation table solvers (the `com.epic60869.sky2m.sb` package and the `assets/sky2m/dungeons` room data) are ported from Skyblocker v6.10.4+26.2 (LGPL-3.0), keeping Skyblocker's source structure. Skyblocker's config, location, scheduler and rendering classes are replaced by small Sky2M stand-ins, and its custom world renderer is replaced by one built on Minecraft's gizmos.

## SkyHanni repository data

Several Sky2M features use chat and item patterns, slayer XP and spawn costs, and the sea creature list published in SkyHanni's data repository (https://github.com/hannibal002/SkyHanni-REPO, MIT License). The sea creature list is downloaded at runtime; the patterns and slayer values are included in Sky2M's source.

## SkyHanni GUI Position Editor

Sky2M's /s2 gui position editor is an adaptation of the SkyHanni GUI position editor interaction model, including draggable HUD boxes, hover information, keyboard movement, and scroll-wheel scaling.

- https://github.com/hannibal002/SkyHanni
- Relevant source: src/main/java/at/hannibal2/skyhanni/config/core/config/gui/GuiPositionEditor.kt and src/main/java/at/hannibal2/skyhanni/data/GuiEditManager.kt
- License: GNU Lesser General Public License v2.1 (LGPL-2.1)

Sky2M's implementation is independently adapted to Sky2M's own Java/Fabric 26.x HUD system and does not add SkyHanni as a runtime dependency.

## SkyHanni Current Chat Display

Sky2M's current chat display (`Sky2MCurrentChat.java`) follows SkyHanni's CurrentChatDisplay (src/main/java/at/hannibal2/skyhanni/features/chat/CurrentChatDisplay.kt): the channel-change message patterns and the channel names and colours. SkyHanni is licensed under the GNU Lesser General Public License v2.1 (LGPL-2.1).

## SkyHanni Enderman and Blaze slayer features

Sky2M's Enderman Slayer and Blaze Slayer features (`features/slayer/EndermanSlayer.java`, `features/slayer/BlazeSlayer.java`, the phase numbers in `features/slayer/SlayerFeatures.java`, and the Sky2MSlayerPacketsMixin and Sky2MHideFireballsMixin mixins) are ported from SkyHanni:

- https://github.com/hannibal002/SkyHanni (branch beta)
- Relevant source: src/main/java/at/hannibal2/skyhanni/features/slayer/enderman/ (EndermanSlayerFeatures, EndermanSlayerHideParticles, LineToVoidgloomSeraph), features/slayer/blaze/ (BlazeSlayerClearView, BlazeSlayerDaggerHelper, BlazeSlayerFirePitsWarning, FirePillarDisplay, HellionShield, HellionShieldHelper), and the Enderman and Blaze phase splits in features/combat/damageindicator/DamageIndicatorManager.kt
- The Nukekubi skull texture comes from SkyHanni-REPO's constants/Skulls.json (MIT License)
- License: GNU Lesser General Public License v2.1 (LGPL-2.1)

The Kotlin code was rewritten in Java on Sky2M's own HUD, world renderer and config. Entity colour tints are drawn as glowing outlines, and the landed Yang Glyph is found by checking the blocks around the thrown one instead of from block change packets.

## Short warp commands

Sky2M's Warp Shortcuts (`features/misc/WarpShortcuts.java`) follow Skysoft's WarpAliases (https://github.com/Akinsoft/Skysoft, src/main/kotlin/com/skysoft/features/misc/WarpAliases.kt, LGPL-3.0) and SkyHanni's ShortenWarpCommand and GardenWarpCommands (https://github.com/hannibal002/SkyHanni, LGPL-2.1): the warp names (Skysoft's list and SkyHanni-REPO's constants/Warps.json, MIT License) and the Garden's /home, /barn and /tp commands.

## SkyBlock Overhaul (SBO)

Sky2M's Diana burrows, warp keys and Sphinx solver (`features/combat/DianaBurrows.java` and `features/combat/DianaSphinx.java`) are ported from SkyBlock Overhaul:

- https://github.com/SkyblockOverhaul/SBO (commit f5663baed4dd7748c80ba0fe52164bfb404ae4d5)
- Relevant source: src/main/kotlin/net/sbo/mod/diana/burrows/ (BurrowDetector, ParticleTypes), diana/guesses/ (ArrowGuessBurrow, GuessEntry, PreciseGuessBurrow), diana/sphinx/ (SphinxSolver, SphinxSession, SphinxQuestions), utils/events/DianaEvents.kt, utils/math/ (PolynomialFitter, Matrix, RaycastUtils), utils/waypoint/WaypointManager.kt and WarpPoint.kt, and the settings text in settings/categories/Diana.kt
- License: Apache License 2.0. SBO credits its arrow guess to SidOfThe7Cs's work in SkyHanni (https://github.com/hannibal002/SkyHanni/pull/4916, LGPL-2.1) and its raycast helpers to SkyHanni's RaycastUtils.

The Kotlin code was rewritten in Java. Changes: waypoints are drawn with Sky2M's world renderer, the guess colours and beacon beam are Sky2M settings, and the warp keys use Sky2M's warp settings.

Sky2M's Diana achievements, mob HP display, rare drop announcer and Diana party commands are also ported from SBO (commit f5663baed4dd7748c80ba0fe52164bfb404ae4d5, Apache License 2.0):

- `features/combat/DianaAchievements.java` and `DianaAchievementsScreen.java`: diana/achievements/ (AchievementManager, Achievement) and guis/AchievementsGUI.kt. The achievement names, descriptions, rarities and ids are SBO's. The ones that need SBO's own server (mob kill totals, the kills leaderboard, Enderman Slayer 9) and "Download SBO" are left out.
- `features/combat/DianaMobHealth.java`: diana/DianaMobDetect.kt (the mythological mob nametags, King Minos's hits and the low HP alert).
- `features/combat/DianaTracker.java` (the drop side): diana/DianaTracker.kt and the lootshare tracking in utils/Helper.kt (the RARE DROP handling, lootshared drops and rare mobs, the since counters, back-to-back drops, the best Magic Find and the loot announcer).
- `features/combat/DianaPartyCommands.java` and the !demote, !carrot and !time commands in `features/misc/PartyCommands.java`: general/PartyCommands.kt.

## Diana profit tracker and Lobby Compromised

The Diana profit tracker (`features/combat/DianaProfitTracker.java`) follows SkyHanni's DianaProfitTracker (https://github.com/hannibal002/SkyHanni, LGPL-2.1), with SkyHanni-REPO's constants/DianaDrops.json list (MIT License). The mythological creature tracker (the mob side of `features/combat/DianaTracker.java`) follows SkyHanni's MythologicalCreatureTracker (src/main/java/at/hannibal2/skyhanni/features/event/diana/MythologicalCreatureTracker.kt, LGPL-2.1): the creature counts and percentages, the creatures since each rare one, the count added to the "You dug out" message, and the dig-out pattern from GriffinBurrowHelper, with the creature list from SkyHanni-REPO's constants/events/Diana.json (MIT License). Lobby Compromised (`features/combat/DianaLobbyCompromised.java`) is ported from Skysoft's DianaLobbyCompromisedWatcher (https://github.com/Akinsoft/Skysoft, LGPL-3.0), reading your party from chat instead of the Hypixel mod API.

## SkyOcean

Sky2M's search-keybind and recipe-command workflows are adapted from the corresponding SkyOcean features:

- https://github.com/meowdding/SkyOcean
- Item search: src/main/kotlin/me/owdding/skyocean/features/item/search/ItemSearch.kt
- Recipe command: src/main/kotlin/me/owdding/skyocean/commands/CraftHelperCommand.kt
- Recipe autocomplete: src/main/kotlin/me/owdding/skyocean/utils/suggestions/RecipeNameSuggestionProvider.kt

Sky2M's /s2 search (Sky2MStorageSearchScreen and the island chest, museum and highlight code in Sky2MStorageSearch) is a Java port of SkyOcean's item search: features/item/search/screen/ItemSearchScreen.kt, SearchCategory.kt and SortModes.kt, features/item/search/matcher/ItemMatcher.kt, features/item/search/search/ReferenceItemFilter.kt, features/item/search/highlight/ItemHighlighter.kt, features/misc/ChestTracker.kt, data/profile/IslandChestStorage.kt and features/item/sources/. Its museum reading follows SkyblockAPI's MuseumAPI (https://github.com/SkyblockAPI/SkyblockAPI, src/main/kotlin/tech/thatgravyboat/skyblockapi/api/profile/items/museum/MuseumAPI.kt, MIT License, Copyright (c) ThatGravyBoat). SkyOcean's Olympus UI widgets are drawn with vanilla GUI calls.

Sky2M's /s2 recipe craft helper is a Java port of SkyOcean's craft helper (src/main/kotlin/me/owdding/skyocean/features/recipe/crafthelper/ and commands/CraftHelperCommand.kt): the recipe tree with leftover carry-over, the have/need evaluation, and the tree display. SkyOcean's code is licensed under the MIT License (SkyOcean License v1, section 1); Sky2M reads recipes from the NEU repository instead of SkyOcean's repo library.

Copyright notice for the ported code: Copyright (c) meowdding / SkyOcean contributors. Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Stella

Sky2M's dungeon secret routes (`features/dungeons/DungeonRoutes.java`) follow Stella's secret-route system: the route file format (steps of waypoints and a path line), the waypoint types and labels, the room coordinate scheme (the blue terracotta roof corner as the origin, with the corner that holds it giving the rotation), step advancing on secret pickup, and automatic recording.

- https://github.com/Eclipse-5214/stella
- Relevant source: src/main/kotlin/co/stellarskys/stella/features/secrets/SecretRoutes.kt, features/secrets/utils/routes/ (RouteRegistry.kt, RoutePlayer.kt, RouteRecorder.kt, WaypointType.kt) and api/dungeons/map/Room.kt
- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

Stella's default routes ("generated by Stella, recorded by Luckkytigger") are not bundled with Sky2M. They are downloaded at runtime from Stella's route server (https://ether.stellarskys.co/routes/default.json) and cached in the Sky2M config folder.

## Odin

Several Sky2M dungeon features follow Odin's implementations:

- https://github.com/odtheking/Odin (main branch, Minecraft 26.1.2)
- Relevant source: `utils/skyblock/SplitsManager.kt`, `features/impl/skyblock/Splits.kt`, `utils/PersonalBest.kt`, `features/impl/dungeon/InvincibilityTimer.kt`, `LeapMenu.kt`, `BloodCamp.kt` (including its Watcher and blood mob head textures, mob skull data by DocilElm), `DoorHighlight.kt`, `PositionalMessages.kt`, the puzzle solvers in `puzzlesolvers/` (Ice Fill, Boulder, Creeper Beams, Three Weirdos, Quiz, Teleport Maze, Water Board and Blaze) with their data files `ice-fill-floors.json`, `boulder-solutions.json`, `creeper-beams-solutions.json`, `quiz-answers.json` and `water-solutions.json` (copied to `assets/sky2m/puzzles/`), and `render/PlayerSize.kt`

Ported to Java in `features/dungeons/DungeonFeatures.java` (splits, split PBs, mask timers), `LeapMenu.java`, `BloodCamp.java`, `BloodCampSkulls.java`, `DoorHighlight.java`, `PositionalMessages.java`, `OdinPuzzleSolvers.java`, `OdinTerminals.java` (terminal solvers from `features/impl/boss/TerminalSolver.kt` and `utils/skyblock/dungeon/terminals/`), `OdinDevices.java` (`SimonSays.kt`, `ArrowAlign.kt`, `ArrowsDevice.kt`), `mixin/Sky2MPlayerSizeMixin.java`, `mixin/Sky2MToggleSprintMixin.java` (`AutoSprint.kt` / `LocalPlayerMixin.java`), and `features/dungeons/SpiritBear.java` (`features/impl/boss/SpiritBear.kt`). Each file names Odin in its class comment.

Odin is licensed under the BSD 3-Clause License:

```
BSD 3-Clause License

Copyright (c) 2025, odtheking

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## NoammAddons

Sky2M's dungeon map ("NoammAddons (Legit)" style) and dungeon score calculator are ported from NoammAddons:

- https://github.com/Noamm9/NoammAddons (26.2 branch)
- Relevant source: `features/impl/dungeon/map/MapRenderer.kt`, `MapConfig.kt`, `utils/dungeons/map/handlers/HotbarMapColorParser.kt`, `MapUpdater.kt`, `ScoreCalculation.kt`, `utils/dungeons/map/utils/MapUtils.kt`, `utils/dungeons/map/core/`, `features/impl/dungeon/ScoreCalculator.kt`, and the checkmark and marker textures in `textures/gui/dungeonmap/` (copied to `assets/sky2m/textures/gui/dungeonmap/`)

Ported to Java in `features/dungeons/NoammMap.java` and `features/dungeons/ScoreCalculator.java`. The M7 dragon priority (the dragon that spawns first, Solo Priority, First Dragon Only) and the relic place timer in `features/dungeons/WitherDragons.java` follow NoammAddons' `floor7/dragons/DragonCheck.kt` and `floor7/M7Relics.kt`. NoammAddons is dedicated to the public domain under CC0 1.0 Universal.

## Skysoft Held Item

Sky2M's Held Item (the `com.epic60869.sky2m.features.helditem` package and the Sky2MHeldItemMixin, Sky2MSwingSpeedMixin, Sky2MHeldItemTextureMixin and Sky2MHeldItemMiningMixin mixins) is a Java port of Skysoft's Held Item feature (https://github.com/Akinsoft/Skysoft, commit 79259743734d614a75f9d64bb8fa54163bfcfc1d): src/main/kotlin/com/skysoft/features/helditem/, config/HeldItemConfig.kt, the Held Item Update Fix from config/FixesConfig.kt, the ItemInHandRenderer, ItemModelResolver, LivingEntity swing and MultiPlayerGameMode mixins, and the pixel controls it draws with (utils/gui/PixelButtonRenderer.kt, PixelControlRenderer.kt, OverlayPanelStyle.kt, TextElision.kt, utils/SnapshotHistory.kt).

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Skysoft Diana Rare Mob Sharing and Lootshare Helper

Sky2M's Diana rare mob sharing and lootshare helper (`features/combat/DianaRareMobs.java`) is a Java port of Skysoft's (https://github.com/Akinsoft/Skysoft, commit 79259743734d614a75f9d64bb8fa54163bfcfc1d): src/main/kotlin/com/skysoft/features/event/diana/ (DianaRareMobSharing, DianaRareMobTarget, DianaRareMobShare, DianaRareMobPartyMessages, DianaRareMobSupport, DianaRareMobRenderer, DianaRareMobEntityMatcher, DianaRareMobSignalSelector, DianaRareMobTitleRenderer, DianaDugMobParser, DianaMythologicalPetRequirement, DianaLootshareReadyMarkers, DianaLootshareReadyMessage), the mob matching and damage attribution from features/combat/ (SkyBlockMobEntityMatcher, SkyBlockMobTextParser, SkyBlockMobTracker, DamageSplashText, DamageSplashAttribution, CocoonMessageParser), data/skyblock/SkyBlockMobNames.kt and SkyBlockPlayerDeathParser.kt, and the party message queue in utils/chat/SkysoftPartyShare.kt. The party chat formats are kept, so shares and "Loot share secured!" work between Sky2M and Skysoft players.

Sky2M reads party membership from Hypixel's party messages instead of the Hypixel mod API, reads your pet from the tab list, and draws labels with its own world renderer.

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Skysoft Tooltip Scroll

Sky2M's tooltip scroll (`features/misc/ScrollableTooltips.java`, `Sky2MTooltipMixin`, `Sky2MTooltipScrollMixin`) is a Java port of Skysoft's Tooltip Scroll (src/main/kotlin/com/skysoft/gui/tooltip/TooltipViewport.kt and TooltipPanSession.kt, com/skysoft/config/TooltipScrollConfig.kt, and the tooltip positioner / mouse scroll mixins): the pan session with smooth movement, the keyboard and mouse wheel controls, and the settings.

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Skyblocker Accessory Tooltip

Sky2M's accessory tooltip (`features/misc/AccessoryTooltip.java`) is a 1:1 port of Skyblocker's AccessoriesHelper and AccessoryTooltip (https://github.com/SkyblockerMod/Skyblocker), with accessory data from Aaron's Mod via hysky.de.

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## NoFrills

Sky2M's M7 dragon and relic features (`features/dungeons/WitherDragons` and Sky2MWitherDragonsMixin) are ported from NoFrills' Wither Dragons and Relic Highlight:

- https://github.com/WhatYouThing/NoFrills (commit 1ecf22d3b9cf49a22dcf592e142fc67688e64424)
- Relevant source: src/main/java/nofrills/features/dungeons/WitherDragons.java and RelicHighlight.java
- License: GNU General Public License v3.0 (GPL-3.0)

NoFrills' dragon boxes and priorities come from Odin's WitherDragonEnum. The arrow-hit tracker was not ported.

## Skysoft Hide Status Effects

Sky2M's Hide Status Effects (`features/misc/HideStatusEffects.java` and the Sky2MEffectsInInventoryMixin mixin) is ported from Skysoft's (https://github.com/Akinsoft/Skysoft, commit 79259743734d614a75f9d64bb8fa54163bfcfc1d, mixin/EffectsInInventoryMixin.java and the Hide Status Effects option in config/GuiFeatureConfig.kt).

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Skysoft Server Info Display, Real Time Display and Profit Trackers

Sky2M's Server Info Display and Real Time Display (`features/misc/ServerInfo.java` and the Sky2MServerInfoPacketMixin mixin) are ported from Skysoft (https://github.com/Akinsoft/Skysoft, commit 79259743734d614a75f9d64bb8fa54163bfcfc1d): features/misc/ServerInfoDisplay.kt, ServerPingTracker.kt, ServerTpsEstimator.kt, ServerTpsProvider.kt, RealTimeDisplay.kt, config/ServerInfoDisplayConfig.kt, config/RealTimeDisplayConfig.kt and mixin/ServerInfoPacketMixin.java. The Profit Trackers (`features/misc/profit/ProfitTracker.java` and `ProfitTrackerHud.java`) follow Skysoft's Profit Tracker: features/profit/ (ProfitTracker.kt, ProfitTrackerTarget.kt, ProfitTrackerPresets.kt, ProfitTrackerRenderable.kt, ProfitTrackerHud.kt, ProfitReplenishCosts.kt, ProfitCraftingReconciliation.kt, FarmingKernelProfit.kt, SlayerQuestCostCapture.kt), config/ProfitTrackerConfig.kt, utils/gui/OverlayTextStyle.kt and OverlayPanelStyle.kt, data/skyblock/ParsedGardenPestKill.kt, and assets/skysoft/data/profit_tracker_presets.json, copied as assets/sky2m/data/profit_tracker_presets.json.

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Skysoft Keep Terrain Loaded and Slayer Target Highlighting

Sky2M's Keep Terrain Loaded (`features/misc/KeepTerrainLoaded.java` and the Sky2MKeepTerrainLoadedMixin, Sky2MKeepTerrainChunkCacheMixin and Sky2MClientPacketListenerAccessor mixins) is ported from Skysoft (https://github.com/Akinsoft/Skysoft, commit 79259743734d614a75f9d64bb8fa54163bfcfc1d): features/misc/KeepTerrainLoaded.kt, mixin/KeepTerrainLoadedPacketMixin.java, mixin/KeepTerrainLoadedClientChunkCacheMixin.java and the island list in config/WorldFeatureConfig.kt. Boss Highlight (`features/slayer/SlayerTargetHighlight.java`) follows Skysoft's features/slayer/SlayerTargetHighlighting.kt and config/SlayerFeatureConfig.kt.

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Odin Chat Commands

Sky2M's Party Commands !fps, !ping, !tps, !f1-!f7, !m1-!m7 and !t1-!t5 (`features/misc/PartyCommands.java`) follow Odin's ChatCommands, and the Etherwarp Overlay (`features/misc/EtherwarpOverlay.java`) is ported from Odin's src/main/kotlin/com/odtheking/odin/features/impl/render/Etherwarp.kt (with Bloom's voxel traversal) (https://github.com/odtheking/Odin, commit ae6a1994b633f5b8e59bf1a88b50e5eab6a1b15f, src/main/kotlin/com/odtheking/odin/features/impl/skyblock/ChatCommands.kt).

- License: BSD 3-Clause License

## NoFrills Egg Hits Display

Sky2M's Egg Hits Display (`features/slayer/EggHitsDisplay.java`) is ported from NoFrills (https://github.com/WhatYouThing/NoFrills, commit 1ecf22d3b9cf49a22dcf592e142fc67688e64424, src/main/java/nofrills/features/slayer/EggHitsDisplay.java).

- License: GNU General Public License v3.0 (GPL-3.0)

## Feesh

Sky2M's fishing features (`features/fishing/FishingFeatures.java`, `FishingData.java` and `FishingRecords.java`) are ported from Feesh (https://github.com/Sleepy-Panda/Feesh, commit 885e356eac0da59345aed844f6e24b364978fde1): the sea creature, rare drop and selectable lists in constants/SeaCreatures.kt, constants/RareDrops.kt, settings/models/ and utils/enums/; features/alerts/ (RareCatchAlert.kt, RareDropAlert.kt, PlayerDeathAlert.kt, TrophyFishDiscoveredAlert.kt, TrophyFrogDiscoveredAlert.kt, PetLevelUpAlert.kt, HotspotGoneAlert.kt, WormholeGoneAlert.kt, LootshareAlert.kt); features/chat/ (CompactCatchMessages.kt, RareCatchMessage.kt, RareDropMessage.kt, PlayerDeathMessage.kt, HotspotFoundMessage.kt); features/overlays/DeployablesTimer.kt and FishingFestivalTracker.kt; features/rendering/RareMobHighlight.kt; and utils/ColorUtils.kt, EntityUtils.kt and HotspotUtils.kt.

- Copyright Sleepy-Panda and Feesh contributors
- License: Apache License 2.0

## Skysoft Pet Icon

The pet display's icon (`PetIconRenderer.java`) follows Skysoft's pet display (https://github.com/Akinsoft/Skysoft, commit 79259743734d614a75f9d64bb8fa54163bfcfc1d): features/pets/PetDisplayRenderer.kt, utils/renderables/decorators/CircularLayoutRenderable.kt and the defaults in config/features/pets/display/visual/.

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Skyblocker Quick Navigation

Sky2M's Inventory Buttons (`features/misc/InventoryButtons.java` and the Sky2MQuickNavMixin mixin) are ported from Skyblocker's Quick Navigation (https://github.com/SkyblockerMod/Skyblocker, commit dc1176a7523f7065c5dcbe30e79c4452402ddc71): skyblock/quicknav/QuickNav.java, QuickNavButton.java, QuickNavConfirmationButton.java, mixins/QuickNavMixin.java, mixins/QuickNavScreenMixin.java and the default buttons in config/configs/QuickNavigationConfig.java.

- License: GNU Lesser General Public License v3.0 (LGPL-3.0)

## Odin Room Clear, Secret Clicked and Storm PY Timer

Sky2M's Room Clear Alert (`features/dungeons/RoomClearAlert.java`), Secret Chime and Secret Boxes (`features/dungeons/SecretChime.java`) and Storm PY Timer (in `features/dungeons/DungeonFeatures.java`) are ported from Odin (https://github.com/odtheking/Odin, commit 174500c1c3ba1349b0b4379642bed765da31e1f7): features/impl/dungeon/RoomClear.kt, features/impl/dungeon/SecretClicked.kt, the secret pickup detection in events/EventDispatcher.kt and utils/skyblock/dungeon/DungeonUtils.kt, and features/impl/boss/TickTimers.kt.

- Copyright (c) 2025, odtheking
- License: BSD 3-Clause License

## Killer560's Mod

Several Sky2M features are ported from Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod, commit 3744aa3). Only the mod's legit behaviour is ported; each ported file names its source in its class comment.

- Trail: `features/misc/Trail.java` (trail/)
- Revert Master Stars: `features/misc/RevertMasterStars.java`, the idea of revertmasterstars/ (itself from QUOI's RevertMasterStars), written anew
- GIF Player and DVD Screensaver: `features/misc/gif/` (gifplayer/, dvd/)
- Custom Crosshair: `features/misc/crosshair/` (crosshair/), including its KXH1 share code layout
- Pack Disabler: `features/misc/PackDisabler.java`, the Sky2MPackDisablerModelMixin and Sky2MPackDisablerHeadMixin mixins (packdisabler/), its item table `assets/sky2m/skyblock/item_looks.json` (made from Hypixel's item API and the NotEnoughUpdates-REPO, MIT) and its item textures under `assets/sky2m/textures/item/packdisabler/`. Its state-dependent models follow Noamm's PackDisabler (https://github.com/Noamm9/PackDisabler, CC0-1.0).
- SkyBlock Only: `Sky2MLocation.skyblockOnlyAllows` (util/SkyblockGate)

```
MIT License

Copyright (c) 2026 Killer560

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
