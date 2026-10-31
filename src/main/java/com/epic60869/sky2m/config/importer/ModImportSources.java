package com.epic60869.sky2m.config.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * The other mods whose settings Sky2M can import: where each keeps its config (relative to the game's config folder)
 * and which of its settings mean the same as a Sky2M setting. Only on/off settings are mapped, and only where the
 * other mod's setting and Sky2M's do the same thing; anything else is left out rather than guessed.
 *
 * <p>A source path is split on {@code /} when it has one (for keys with dots or spaces in them), else on {@code .}.
 * A segment that lands on a JSON array picks the element whose {@code "name"} is that segment (Odin and NoammAddons
 * keep their modules as a list of {@code {"name": ..., "enabled": ...}}).
 */
public final class ModImportSources {
    private ModImportSources() {}

    /** One importable mod. {@code map} is {@code {other mod's path, Sky2M path}} pairs. */
    public record Source(String id, String name, List<String> files, List<String[]> map) {}

    private static final List<Source> SOURCES = new ArrayList<>();

    public static List<Source> all() {
        return SOURCES;
    }

    public static Source byId(String id) {
        for (Source source : SOURCES) if (source.id().equalsIgnoreCase(id)) return source;
        return null;
    }

    private static final class Builder {
        private final String id;
        private final String name;
        private final List<String> files;
        private final List<String[]> map = new ArrayList<>();

        Builder(String id, String name, String... files) {
            this.id = id;
            this.name = name;
            this.files = List.of(files);
        }

        Builder m(String other, String sky2m) {
            map.add(new String[]{other, sky2m});
            return this;
        }

        /** The same tail under two different roots: {@code prefix + key} to {@code target + key}, for each key. */
        Builder same(String prefix, String target, String... keys) {
            for (String key : keys) m(prefix + key, target + key);
            return this;
        }

        void add() {
            SOURCES.add(new Source(id, name, files, List.copyOf(map)));
        }
    }

    static {
        new Builder("skyhanni", "SkyHanni", "skyhanni/config.json")
            .m("gui.customScoreboard.enabled", "gui.customScoreboard.enabled")
            .m("gui.customScoreboard.background.enabled", "gui.customScoreboard.background.enabled")
            .m("gui.customScoreboard.display.hideVanillaScoreboard", "gui.customScoreboard.hideHypixel")
            .m("gui.customScoreboard.display.useCustomLines", "gui.customScoreboard.customLines")
            .m("gui.customScoreboard.display.skyblockTime24hFormat", "gui.customScoreboard.lineOptions.time24h")
            .m("gui.customScoreboard.display.events.showAllActiveEvents", "gui.customScoreboard.lineOptions.showAllEvents")
            .m("gui.compactTabList.enabled", "gui.tabListExtras.compact")
            .same("gui.compactTabList.", "gui.tabListExtras.", "toggleTab", "hideAdverts", "hideFiresales", "hideTabBackground")
            .m("gui.compactTabList.advancedPlayerList.hidePlayerIcons", "gui.tabListExtras.hidePlayerIcons")
            .same("garden.visitors.", "farming.visitors.",
                "timer.enabled", "timer.newVisitorPing",
                "shoppingList.enabled", "shoppingList.onlyWhenClose", "shoppingList.inBazaarAlley", "shoppingList.inFarmingAreas",
                "shoppingList.showPrice", "shoppingList.showSackCount", "shoppingList.showSuperCraft", "shoppingList.itemPreview",
                "inventory.showPrice", "inventory.exactAmountAndTime", "inventory.copperPrice", "inventory.copperTime",
                "inventory.experiencePrice",
                "rewardWarning.notifyInChat", "rewardWarning.showOverName", "rewardWarning.preventRefusing",
                "rewardWarning.preventRefusingCopper", "rewardWarning.preventAcceptingCopper",
                "rewardWarning.preventRefusingLowLoss", "rewardWarning.preventAcceptingHighLoss",
                "rewardWarning.preventRefusingNew", "rewardWarning.optionOutline",
                "dropsStatistics.enabled", "dropsStatistics.displayNumbersFirst", "dropsStatistics.onlyOnBarn",
                "charmed.enabled", "notificationChat", "compactRewardChat", "notificationTitle", "hypixelArrivedMessage",
                "hideChat", "highlightVisitors")
            .m("garden.pests.pestSpawn.showTitle", "farming.garden.pestSpawn.showTitle")
            .same("garden.pests.", "farming.garden.", "pestFinder.alwaysTp", "pestFinder.backToGarden",
                "pestTimer.enabled", "pestTimer.cooldownOverWarning", "pestTimer.repeatWarning", "pestTimer.pestSpawnChatMessage")
            .m("garden.pests.spray.showNotSprayed", "farming.skyhanniGarden.pests.showNotSprayed")
            .m("garden.pests.stereoHarmony.hideWhenNone", "farming.gardenHelpers.stereo.hideWhenNone")
            .m("garden.composters.roundDown", "farming.gardenHelpers.composter.roundDown")
            .m("garden.anitaShop.extraFarmingFortune", "farming.gardenHelpers.anita.extraFarmingFortune")
            .same("garden.atmosphericFilterDisplay.", "farming.gardenHelpers.filter.", "onlyBuff", "abbreviateSeason", "abbreviatePerk", "outsideGarden")
            .m("garden.jacobContest.contestSummary.enabled", "farming.skyhanniGarden.jacob.summary")
            .m("garden.jacobContest.contestSummary.hideZeroCropStats", "farming.skyhanniGarden.jacob.hideZeroStats")
            .m("garden.jacobContest.zorroCapeProtection", "farming.skyhanniGarden.jacob.zorroProtection")
            .same("crimsonIsle.ashfang.", "crimsonIsle.ashfang.", "highlightBlazes", "freezeCooldown")
            .m("crimsonIsle.ashfang.gravityOrbs.enabled", "crimsonIsle.ashfang.gravityOrbs")
            .m("crimsonIsle.ashfang.blazingSouls.enabled", "crimsonIsle.ashfang.blazingSouls")
            .m("fishing.bobberTimer.enabled", "fishing.hookTimer.bobberTimer.enabled")
            .m("fishing.bobberTimer.startOnLiquidTouch", "fishing.hookTimer.bobberTimer.startOnLiquidTouch")
            .m("fishing.fishingHookDisplay.enabled", "fishing.hookTimer.hookDisplay.enabled")
            .m("fishing.fishingHookDisplay.hideArmorStand", "fishing.hookTimer.hookDisplay.hideArmorStand")
            .m("fishing.trophyFishing.goldenFishTimer.enabled", "fishing.more.goldenFishTimer")
            .m("fishing.barnTimer.enabled", "fishing.more.barnTimer")
            .m("fishing.seaCreatureTracker.enabled", "fishing.more.seaCreatureTracker")
            .m("fishing.lavaReplacement.enabled", "fishing.visuals.lavaReplacement")
            .m("fishing.lavaReplacement.everywhere", "fishing.visuals.lavaEverywhere")
            .same("fishing.", "fishing.extras.", "guessHotspotRadar", "lineToHotspot")
            .m("mining.powderTracker.enabled", "mining.powderTracker.enabled")
            .same("mining.orderedWaypoints.", "mining.orderedWaypoints.", "enabled", "fillBlock", "traceLine", "showDistance",
                "showName", "showAll", "autoUnload", "autoSkipForward")
            .m("mining.miningEvent.outsideMining", "mining.miningEvents.outsideMining")
            .m("mining.metalDetector.metalDetectorStopWhenAllTools", "mining.dwarvenSolvers.metalDetectorStopWhenAllTools")
            .m("mining.coldOverlay.enabled", "mining.glaciteCold.coldOverlay")
            .m("mining.fossilExcavator.solver.enabled", "mining.fossilExcavator.solver")
            .m("mining.glaciteMineshaft.corpseLocator.enabled", "mining.glaciteMineshaft.corpseFinder")
            .m("foraging.trees.cleanView.hideTreeBlocks", "foraging.extras.hideTreeBlocks")
            .m("foraging.trees.cleanView.hideRuneEffects", "foraging.extras.hideRuneEffects")
            .m("foraging.honeyhiveReminder.enabled", "foraging.extras.honeyhiveReminder")
            .m("foraging.mutePhantoms", "foraging.extras.mutePhantoms")
            .same("hunting.", "hunting.", "safari.namesInCenter", "safari.hideyhoFinder", "safari.checklist.enabled",
                "safari.checklist.hideCaught", "safari.checklist.showIcons", "safari.critterCapsules.hideOnGround",
                "shardTracker.enabled", "huntingProfitTracker.enabled", "huntingProfitTracker.showWhenPickup",
                "huntingProfitTracker.alwaysShow", "mobHighlight.birries.enabled", "mobHighlight.hideonleaf.enabled",
                "mobHighlight.hideonsun.enabled", "mobHighlight.invisibug.enabled", "lassoDisplay", "huntrapMisclick",
                "shulkerFinder", "fusionDisplay")
            .same("combat.", "combat.", "ghostCounter.enabled", "bestiary.enabled", "bestiary.hideMaxed", "bestiary.replaceRoman")
            .m("combat.endIsland.dragon.superiorNotify", "combat.dragons.superiorNotify")
            .m("combat.endIsland.enderNodeTracker.onlyPickaxe", "combat.enderNodes.onlyPickaxe")
            .m("slayer.endermen.beacon.highlightBeacon", "slayers.enderman.highlightBeacon")
            .same("slayer.endermen.", "slayers.enderman.", "phaseDisplay", "hideParticles", "lineToBoss")
            .m("slayer.blazes.hellion.coloredMobs", "slayers.blaze.coloredMobs")
            .m("slayer.blazes.hellion.daggers", "slayers.blaze.daggers")
            .same("slayer.blazes.", "slayers.blaze.", "firePitsWarning", "phaseDisplay", "clearView", "firePillarDisplay")
            .same("slayer.", "slayers.", "itemsOnGround.enabled", "rngMeterDisplay.enabled", "rngMeterDisplay.warnEmpty")
            .m("slayer.slayerTimeMessages.timeToKill", "slayers.personalBest.timeToKill")
            .m("slayer.slayerTimeMessages.questComplete", "slayers.personalBest.questComplete")
            .same("dungeon.lividFinder.", "dungeons.lividFinder.", "enabled", "hideWrong")
            .m("dungeon.cleanEnd.enabled", "dungeons.dungeonExtras.cleanEnd.enabled")
            .same("dungeon.partyFinder.", "dungeons.partyFinderHelper.", "markPaidCarries", "markNonPugs", "markBelowClassLevel",
                "markMissingClass", "showMissingClasses", "floorAsStackSize")
            .m("dungeon.partyFinder.markIneligibleGroups", "dungeons.partyFinderHelper.markIneligible")
            .m("dungeon.partyFinder.coloredClassLevel", "dungeons.partyFinderHelper.colouredClassLevel")
            .same("dungeon.creationCooldown.", "dungeons.dungeonExtras.creationCooldown.", "enabled", "showOutside", "entranceOnly")
            .m("inventory.estimatedItemValues.enabled", "inventory.priceTooltip.estimatedValue")
            .same("inventory.enchantParsing.", "inventory.enchantParsing.", "colorParsing", "boldPerfectEnchant",
                "advancedEnchantColors.useAdvancedUltimateColor", "advancedEnchantColors.useAdvancedPerfectColor",
                "advancedEnchantColors.useAdvancedGreatColor", "advancedEnchantColors.useAdvancedGoodColor",
                "advancedEnchantColors.useAdvancedPoorColor", "hideVanillaEnchants", "hideEnchantDescriptions",
                "stackingEnchantProgress")
            .m("inventory.customWardrobe.enabled", "inventory.customWardrobe.enabled")
            .m("inventory.chocolateFactory.timeTowerWarning", "events.chocolateFactory.timeTowerWarning")
            .same("inventory.itemPickupLog.", "inventory.itemPickupLog.", "enabled", "compactLines", "shorten", "shards",
                "coins", "coinValue.enabled")
            .m("inventory.skyblockGuide.missingTasks", "misc.levelGuide.missingTasks")
            .m("inventory.auctions.highlightAuctions", "inventory.highlightAuctions")
            .m("inventory.auctions.highlightAuctionsUnderbid", "inventory.highlightAuctionsUnderbid")
            .m("inventory.experimentationTable.addons.preventMisclicks", "inventory.experimentalTable.preventMisclicks")
            .m("event.diana.multiGuesses", "events.diana.burrows.multiGuesses")
            .m("event.winter.jyrreTimer.enabled", "events.winter.jyrreTimer")
            .m("event.winter.newYearCakeReminder", "events.winter.newYearCakeReminder")
            .m("event.gifting.giftingOpportunities.enabled", "events.winter.giftingOpportunities")
            .m("event.gifting.uniqueGiftCounter.enabled", "events.winter.uniqueGiftCounter")
            .m("event.hoppityEggs.strayTimer.enabled", "events.chocolateFactory.strayTimer")
            .m("event.hoppityEggs.unclaimedEggs.enabled", "events.hoppity.unclaimedEggs")
            .m("event.jerry.highlightJerries", "events.winter.highlightJerries")
            .m("event.jerry.lineJerries", "events.winter.lineJerries")
            .m("event.spook.primalFearTimer", "events.spook.primalFearTimer")
            .same("event.carnival.zombieShootout.", "events.carnival.zombieShootout.", "enabled", "highestOnly")
            .m("event.feast.fetchAutomatically", "events.harvestFeast.fetchAutomatically")
            .same("skillProgress.", "skills.", "skillProgressBarConfig.useTexturedBar", "skillProgressBarConfig.useChroma",
                "overflowConfig.enableInDisplay", "overflowConfig.enableInAllDisplay", "overflowConfig.enableInEtaDisplay",
                "overflowConfig.enableInProgressBar", "overflowConfig.enableInSkillMenuAsStackSize",
                "overflowConfig.enableInSkillMenuTooltip", "overflowConfig.enableInChat",
                "customGoalConfig.enableInDisplay", "customGoalConfig.enableInAllDisplay", "customGoalConfig.enableInETADisplay",
                "customGoalConfig.enableInProgressBar", "customGoalConfig.enableInSkillMenuTooltip", "customGoalConfig.enableInChat")
            .same("chat.filterType.stashMessages.", "chat.stashMessages.", "enabled", "hideDuplicateWarning.enabled",
                "hideDuplicateWarning.worldChangeReset", "hideAddedMessages", "useViewStash")
            .m("chat.filterType.warping", "chat.filters.warping")
            .m("chat.filterType.watchDog", "chat.filters.watchdog")
            .m("chat.rareDropMessages.petRarity", "chat.petRarity")
            .m("chat.copyChat", "chat.copyChat.enabled")
            .m("chat.compactBestiaryMessage", "chat.compactBestiary")
            .m("chat.currentChatDisplay", "chat.currentChatDisplay")
            .m("chat.skyBlockXPInChat", "chat.skyBlockXpInChat")
            .same("misc.commands.tabComplete.", "chat.commandAutofill.", "warps", "friends", "party", "guild", "vipVisits",
                "gfsSack", "showItem")
            .m("misc.autoJoinSkyblock", "misc.autoJoinSkyblock")
            .m("misc.hideExplosions", "misc.random.hideExplosions")
            .m("misc.hideFireOverlay", "misc.visualTweaks.hideFireOverlay")
            .add();

        new Builder("skyblocker", "Skyblocker", "skyblocker.json", "skyblocker/skyblocker.json", "skyblocker/config.json")
            .m("general.itemTooltip.enableNPCPrice", "inventory.priceTooltip.npcPrice")
            .m("general.itemTooltip.enableLowestBIN", "inventory.priceTooltip.lowestBin")
            .m("general.itemTooltip.enableBazaarPrice", "inventory.priceTooltip.bazaar")
            .m("general.itemTooltip.enableEstimatedItemValue", "inventory.priceTooltip.estimatedValue")
            .m("general.itemTooltip.enableMuseumInfo", "inventory.collectionTooltips.museum")
            .m("general.itemTooltip.enableStackingEnchantProgress", "inventory.enchantParsing.stackingEnchantProgress")
            .m("general.itemInfoDisplay.itemRarityBackgrounds", "inventory.itemRarity.enabled")
            .m("uiAndVisuals.itemCooldown.enableItemCooldowns", "inventory.itemCooldowns.enabled")
            .m("uiAndVisuals.storageOverlay.enabled", "inventory.storageOverlaySettings.enabled")
            .m("uiAndVisuals.showCustomizeButton", "inventory.itemCustom.showCustomizeButton")
            .m("uiAndVisuals.radialMenu.enabled", "gui.radialMenu.enabled")
            .m("uiAndVisuals.compactDamage.enabled", "combat.damageNumbers.compact")
            .m("helpers.enableWardrobeHelper", "inventory.wardrobeHotkeys.enabled")
            .m("helpers.experiments.enableChronomatronSolver", "inventory.experimentalTable.chronomatron")
            .m("helpers.experiments.enableSuperpairsSolver", "inventory.experimentalTable.superpairs")
            .m("helpers.experiments.enableUltrasequencerSolver", "inventory.experimentalTable.ultrasequencer")
            .m("helpers.fishing.hideOtherPlayersRods", "fishing.visuals.hideOtherBobbers")
            .m("dungeons.starredMobGlow", "dungeons.mobs.starredMobs")
            .m("dungeons.dungeonMap.enableMap", "dungeons.map.enabled")
            .m("dungeons.dungeonMap.fancyMap", "dungeons.map.fancy")
            .m("dungeons.dungeonMap.showRoomLabels", "dungeons.map.roomLabels")
            .m("dungeons.leapOverlay.enableLeapOverlay", "dungeons.leapMenu.enabled")
            .m("dungeons.leapOverlay.enableLeapMessage", "dungeons.leapMenu.announce")
            .m("dungeons.puzzleSolvers.solveTicTacToe", "dungeons.puzzles.ticTacToe")
            .m("dungeons.puzzleSolvers.solveThreeWeirdos", "dungeons.puzzles.threeWeirdos")
            .m("dungeons.puzzleSolvers.creeperSolver", "dungeons.puzzles.creeperBeams")
            .m("dungeons.puzzleSolvers.waterboardOneFlow", "dungeons.puzzles.waterBoard")
            .m("dungeons.puzzleSolvers.previewWaterPath", "dungeons.puzzles.waterPreviewPath")
            .m("dungeons.puzzleSolvers.previewLeverEffects", "dungeons.puzzles.waterPreviewLevers")
            .m("dungeons.puzzleSolvers.blazeSolver", "dungeons.puzzles.blaze")
            .m("dungeons.puzzleSolvers.solveBoulder", "dungeons.puzzles.boulder")
            .m("dungeons.puzzleSolvers.solveIceFill", "dungeons.puzzles.iceFill")
            .m("dungeons.puzzleSolvers.solveSilverfish", "dungeons.puzzles.silverfish")
            .m("dungeons.puzzleSolvers.solveTrivia", "dungeons.puzzles.trivia")
            .m("dungeons.puzzleSolvers.solveTeleportMaze", "dungeons.puzzles.teleportMaze")
            .m("dungeons.terminals.blockIncorrectClicks", "dungeons.f7.terminals.blockMisclicks")
            .m("dungeons.devices.solveSimonSays", "dungeons.f7.terminals.simonSays")
            .m("dungeons.devices.solveLightsOn", "dungeons.f7.terminals.lightsOn")
            .m("dungeons.devices.solveArrowAlign", "dungeons.f7.terminals.arrowAlign")
            .m("dungeons.devices.solveTargetPractice", "dungeons.f7.terminals.targetPractice")
            .m("dungeons.secretWaypoints.enableSecretWaypoints", "dungeons.secrets.secretWaypoints")
            .m("dungeons.secretWaypoints.adaptiveBoxSize", "dungeons.dungeonExtras.secretTypes.adaptiveBoxSize")
            .m("dungeons.secretWaypoints.showSecretText", "dungeons.dungeonExtras.secretTypes.showText")
            .m("dungeons.secretWaypoints.enableChestWaypoints", "dungeons.dungeonExtras.secretTypes.chest")
            .m("dungeons.secretWaypoints.enableItemWaypoints", "dungeons.dungeonExtras.secretTypes.item")
            .m("dungeons.secretWaypoints.enableBatWaypoints", "dungeons.dungeonExtras.secretTypes.bat")
            .m("dungeons.secretWaypoints.enableWitherWaypoints", "dungeons.dungeonExtras.secretTypes.wither")
            .m("dungeons.secretWaypoints.enableRedstoneKeyWaypoints", "dungeons.dungeonExtras.secretTypes.redstoneKey")
            .m("dungeons.secretWaypoints.enableLeverWaypoints", "dungeons.dungeonExtras.secretTypes.lever")
            .m("dungeons.secretWaypoints.enableSuperboomWaypoints", "dungeons.dungeonExtras.secretTypes.superboom")
            .m("dungeons.secretWaypoints.enableFairySoulWaypoints", "dungeons.dungeonExtras.secretTypes.fairySoul")
            .m("dungeons.secretWaypoints.enableStonkWaypoints", "dungeons.dungeonExtras.secretTypes.stonk")
            .m("dungeons.secretWaypoints.enableAotvWaypoints", "dungeons.dungeonExtras.secretTypes.aotv")
            .m("dungeons.secretWaypoints.enablePearlWaypoints", "dungeons.dungeonExtras.secretTypes.pearl")
            .m("dungeons.secretWaypoints.enablePrinceWaypoints", "dungeons.dungeonExtras.secretTypes.prince")
            .m("dungeons.secretWaypoints.enableDefaultWaypoints", "dungeons.dungeonExtras.secretTypes.other")
            .m("dungeons.highlightDoorKeys", "dungeons.secrets.keyHighlight")
            .m("dungeons.doorHighlight.enableDoorHighlight", "dungeons.secrets.doorHighlight")
            .m("dungeons.mimicMessage.sendMimicMessage", "dungeons.score.mimicMessage")
            .m("dungeons.princeMessage.sendPrinceMessage", "dungeons.score.princeMessage")
            .m("dungeons.batMessage.sendBatMessage", "dungeons.score.batMessage")
            .m("dungeons.dungeonScore.enableDungeonCryptsMessage", "dungeons.score.cryptsMessage")
            .m("dungeons.dungeonScore.enableScoreHUD", "dungeons.score.display")
            .m("mining.dwarvenMines.solveFetchur", "mining.dwarvenSolvers.fetchur")
            .m("mining.dwarvenMines.solvePuzzler", "mining.dwarvenSolvers.puzzler")
            .m("mining.crystalHollows.metalDetectorHelper", "mining.dwarvenSolvers.metalDetector")
            .m("mining.glacite.coldOverlay", "mining.glaciteCold.coldOverlay")
            .m("mining.glacite.fossilSolver", "mining.fossilExcavator.solver")
            .m("mining.glacite.enableCorpseFinder", "mining.glaciteMineshaft.corpseFinder")
            .m("mining.glacite.enableCorpseProfitTracker", "mining.glaciteMineshaft.corpseProfit")
            .m("mining.enableDrillFuel", "mining.features.drillFuelHud")
            .m("farming.pestHighlighter.enabled", "farming.pestHighlight.enabled")
            .m("farming.mouseLock.lockMouseTool", "farming.mouseLock.enabled")
            .m("farming.mouseLock.lockMouseGroundOnly", "farming.mouseLock.groundOnly")
            .m("hunting.moongladeMobs.highlightHideonleaf", "hunting.mobHighlight.hideonleaf.enabled")
            .m("hunting.torrhusMobs.highlightHideonsun", "hunting.mobHighlight.hideonsun.enabled")
            .m("slayers.bossSpawnAlert", "slayers.bossSpawnAlert.enabled")
            .m("chat.skyblockXpMessages", "chat.skyBlockXpInChat")
            .add();

        new Builder("nofrills", "NoFrills", "NoFrills/Configuration.json", "nofrills/Configuration.json", "nofrills/configuration.json")
            .m("priceTooltips.enabled", "inventory.priceTooltip.enabled")
            .m("priceTooltips.pricePaid", "inventory.priceTooltip.pricePaid")
            .m("priceTooltips.bazaar", "inventory.priceTooltip.bazaar")
            .m("priceTooltips.npc", "inventory.priceTooltip.npcPrice")
            .m("priceTooltips.auction", "inventory.priceTooltip.lowestBin")
            .m("slotBinding.enabled", "inventory.slotLocking.enabled")
            .m("wardrobeKeybinds.enabled", "inventory.wardrobeHotkeys.enabled")
            .m("loadoutKeybinds.enabled", "inventory.wardrobeHotkeys.loadoutEnabled")
            .m("inventoryButtons.enabled", "inventory.inventoryButtons.enabled")
            .m("etherwarpOverlay.enabled", "misc.etherwarpOverlay.enabled")
            .m("partyCommands.enabled", "misc.partyCommands.enabled")
            .m("chatRules.enabled", "chat.chatRules.enabled")
            .m("chatWaypoints.enabled", "chat.social.chatWaypoints")
            .m("disconnectFix.enabled", "misc.visualTweaks.disconnectFix")
            .m("mouseLock.enabled", "farming.mouseLock.enabled")
            .m("shardTracker.enabled", "hunting.shardTracker.enabled")
            .m("invisibugHighlight.enabled", "hunting.mobHighlight.invisibug.enabled")
            .m("cocoonAlert.enabled", "combat.cocoonAlert.enabled")
            .m("eggHitsDisplay.enabled", "slayers.eggHitsDisplay.enabled")
            .m("slayerMinibossHighlight.enabled", "slayers.targetHighlight.highlightMinibosses")
            .m("autoRequeue.enabled", "dungeons.autoRequeue.enabled")
            .m("terracottaTimer.enabled", "dungeons.terracottaTimer")
            .m("princeMessage.enabled", "dungeons.score.princeMessage")
            .m("mimicMessage.enabled", "dungeons.score.mimicMessage")
            .m("quickClose.enabled", "dungeons.dungeonExtras.quickClose.enabled")
            .m("croesusSolver.enabled", "dungeons.chestProfit.croesusSolver")
            .m("dungeonChestValue.enabled", "dungeons.chestProfit.chestValue")
            .m("dupeClassAlert.enabled", "dungeons.dupeClass.enabled")
            .m("starredMobHighlight.enabled", "dungeons.mobs.starredMobs")
            .m("lividSolver.enabled", "dungeons.lividFinder.enabled")
            .m("leapOverlay.enabled", "dungeons.leapMenu.enabled")
            .m("terminalSolvers.enabled", "dungeons.f7.terminals.enabled")
            .m("platformHighlight.enabled", "dungeons.f7.platformHighlight.enabled")
            .m("relicHighlight.enabled", "dungeons.f7.witherDragons.relicHighlight")
            .same("witherDragons.", "dungeons.f7.witherDragons.", "alert", "boxes", "hitboxes", "tracers", "timer", "health", "trackIceSpray")
            .m("witherDragons.trackArrowHits", "dungeons.f7.witherDragons.arrowsHit")
            .m("kuudraHitbox.enabled", "crimsonIsle.kuudra.hitbox")
            .m("kuudraChestValue.enabled", "crimsonIsle.kuudra.chestProfit")
            .m("freshTimer.enabled", "crimsonIsle.kuudra.freshTimer")
            .m("cratePriority.enabled", "crimsonIsle.kuudra.cratePriority")
            .add();

        new Builder("odin", "Odin", "odin/odin-config.json", "odin/config.json")
            .m("Spirit Bear/enabled", "dungeons.spiritBear")
            .m("Arrow Align/enabled", "dungeons.f7.terminals.arrowAlign")
            .m("Simon Says/enabled", "dungeons.f7.terminals.simonSays")
            .m("Terminal Solver/enabled", "dungeons.f7.terminals.enabled")
            .m("Terminal Times/enabled", "dungeons.timers.terminalTimers.enabled")
            .m("Leap Menu/enabled", "dungeons.leapMenu.enabled")
            .m("Terracotta Timer/enabled", "dungeons.terracottaTimer")
            .m("Door Highlight/enabled", "dungeons.secrets.doorHighlight")
            .m("Dungeon Map/enabled", "dungeons.map.enabled")
            .m("Livid Solver/enabled", "dungeons.lividFinder.enabled")
            .m("Tick Timers/enabled", "dungeons.timers.tickTimers")
            .m("Splits/enabled", "dungeons.timers.splits")
            .m("Mage Beam/enabled", "dungeons.mageBeam.enabled")
            .m("Ragnarock/enabled", "dungeons.timers.ragAxe.enabled")
            .m("Croesus/enabled", "dungeons.chestProfit.croesusSolver")
            .m("Pre-Spot Alert/enabled", "crimsonIsle.kuudra.preSpotAlert")
            .m("Build Helper/enabled", "crimsonIsle.kuudra.buildHud")
            .m("Chat Commands/enabled", "misc.partyCommands.enabled")
            .m("Etherwarp/enabled", "misc.etherwarpOverlay.enabled")
            .m("Wardrobe Keybinds/enabled", "inventory.wardrobeHotkeys.enabled")
            .m("Loadout Keybinds/enabled", "inventory.wardrobeHotkeys.loadoutEnabled")
            .m("Slot Binds/enabled", "inventory.slotLocking.enabled")
            .add();

        new Builder("noammaddons", "NoammAddons", "NoammAddons/config.json", "noammaddons/config.json")
            .m("config/Arrow Align/enabled", "dungeons.f7.terminals.arrowAlign")
            .m("config/Simon Says/enabled", "dungeons.f7.terminals.simonSays")
            .m("config/Terminal Solver/enabled", "dungeons.f7.terminals.enabled")
            .m("config/Leap Menu/enabled", "dungeons.leapMenu.enabled")
            .m("config/Leap Counter/enabled", "dungeons.f7.extras.leapCounter")
            .m("config/Dungeon Map/enabled", "dungeons.map.enabled")
            .m("config/Livid Solver/enabled", "dungeons.lividFinder.enabled")
            .m("config/Terracotta Timer/enabled", "dungeons.terracottaTimer")
            .m("config/Tick Timers/enabled", "dungeons.timers.tickTimers")
            .m("config/Run Splits/enabled", "dungeons.timers.splits")
            .m("config/Mage Beam/enabled", "dungeons.mageBeam.enabled")
            .m("config/Ragnarock/enabled", "dungeons.timers.ragAxe.enabled")
            .m("config/Mask Timers/enabled", "dungeons.timers.maskTimers")
            .m("config/Score Calculator/enabled", "dungeons.score.display")
            .m("config/Star Mob ESP/enabled", "dungeons.mobs.starredMobs")
            .m("config/Chest Profit/enabled", "dungeons.chestProfit.chestValue")
            .m("config/Auto Requeue/enabled", "dungeons.autoRequeue.enabled")
            .m("config/Wardrobe Keybinds/enabled", "inventory.wardrobeHotkeys.enabled")
            .m("config/Loadout Keybinds/enabled", "inventory.wardrobeHotkeys.loadoutEnabled")
            .m("config/Slot Binding/enabled", "inventory.slotLocking.enabled")
            .m("config/Storage Overlay/enabled", "inventory.storageOverlaySettings.enabled")
            .m("config/Item Rarity/enabled", "inventory.itemRarity.enabled")
            .m("config/Etherwarp/enabled", "misc.etherwarpOverlay.enabled")
            .add();

        new Builder("devonian", "Devonian", "devonianConfig.json", "devonian/devonianConfig.json")
            .m("config.boulderSolver", "dungeons.puzzles.boulder")
            .m("config.threeWeirdosSolver", "dungeons.puzzles.threeWeirdos")
            .m("config.blazeSolver", "dungeons.puzzles.blaze")
            .m("config.creeperBeamsSolver", "dungeons.puzzles.creeperBeams")
            .m("config.teleportMazeSolver", "dungeons.puzzles.teleportMaze")
            .m("config.triviaSolver", "dungeons.puzzles.trivia")
            .m("config.icePathSolver", "dungeons.puzzles.silverfish")
            .m("config.ticTacToeSolver", "dungeons.puzzles.ticTacToe")
            .m("config.waterBoardSolver", "dungeons.puzzles.waterBoard")
            .m("config.iceFillSolver", "dungeons.puzzles.iceFill")
            .m("config.simonSaysSolver", "dungeons.f7.terminals.simonSays")
            .m("config.arrowAlignSolver", "dungeons.f7.terminals.arrowAlign")
            .m("config.terminalSolvers", "dungeons.f7.terminals.enabled")
            .m("config.lividSolver", "dungeons.lividFinder.enabled")
            .m("config.terracottaTimer", "dungeons.terracottaTimer")
            .m("config.leapCounter", "dungeons.f7.extras.leapCounter")
            .m("config.customLeapGui", "dungeons.leapMenu.enabled")
            .m("config.dungeonMap", "dungeons.map.enabled")
            .m("config.dungeonScoreDisplay", "dungeons.score.display")
            .m("config.boxStarMob", "dungeons.mobs.starredMobs")
            .m("config.autoRequeueDungeons", "dungeons.autoRequeue.enabled")
            .m("config.croesusHighlightUnopened", "dungeons.chestProfit.croesusSolver")
            .m("config.hideHealerOrbs", "dungeons.dungeonExtras.clutter.healerOrbs")
            .m("config.hideFairy", "dungeons.dungeonExtras.clutter.healerFairy")
            .m("config.secretsHud", "dungeons.secrets.secretsDisplay")
            .m("config.hideSecretCountActionbar", "dungeons.secrets.hideSecretsInActionBar")
            .m("config.customMageBeam", "dungeons.mageBeam.enabled")
            .m("config.runSplits", "dungeons.timers.splits")
            .m("config.spiritBearTimer", "dungeons.spiritBear")
            .m("config.dragonBoxes", "dungeons.f7.witherDragons.boxes")
            .m("config.dragonSpawnTimer", "dungeons.f7.witherDragons.timer")
            .m("config.dragonSpawnAlert", "dungeons.f7.witherDragons.alert")
            .m("config.dragonHealth", "dungeons.f7.witherDragons.health")
            .m("config.dragonStackAimer", "dungeons.f7.witherDragons.arrowStack")
            .m("config.dragonHitCount", "dungeons.f7.witherDragons.arrowsHit")
            .m("config.partyFinderStats", "dungeons.partyFinderStats.enabled")
            .m("config.autoKick", "dungeons.partyFinderStats.autoKick")
            .m("config.etherwarpOverlay", "misc.etherwarpOverlay.enabled")
            .m("config.copyChat", "chat.copyChat.enabled")
            .m("config.compactChat", "chat.compactChat")
            .m("config.chatEmotes", "chat.chatEmotes")
            .m("config.chatWaypoint", "chat.social.chatWaypoints")
            .m("config.partyCommands", "misc.partyCommands.enabled")
            .m("config.speedDisplay", "gui.speedDisplay.enabled")
            .m("config.hideEntityFire", "misc.visualTweaks.hideEntityFire")
            .m("config.removeFireOverlay", "misc.visualTweaks.hideFireOverlay")
            .m("config.removeLightning", "misc.visualTweaks.hideLightning")
            .m("config.ownNameTag", "misc.ownNameTag.enabled")
            .m("config.petDisplay", "misc.pets.display.enabled")
            .m("config.slotBinding", "inventory.slotLocking.enabled")
            .m("config.wardrobeKeybinds", "inventory.wardrobeHotkeys.enabled")
            .m("config.loadoutKeybinds", "inventory.wardrobeHotkeys.loadoutEnabled")
            .m("config.estimatedValue", "inventory.priceTooltip.estimatedValue")
            .m("config.stackingEnchantProgress", "inventory.enchantParsing.stackingEnchantProgress")
            .m("config.itemRarityBackground", "inventory.itemRarity.enabled")
            .add();

        new Builder("skyblockaddons", "SkyblockAddons", "skyblockaddons/configurations.json")
            .m("features/SHOW_STACKING_ENCHANT_PROGRESS/value", "inventory.enchantParsing.stackingEnchantProgress")
            .m("features/ENCHANTMENT_LORE_PARSING/value", "inventory.enchantParsing.colorParsing")
            .m("features/ENCHANTMENT_LORE_PARSING/settings/HIDE_VANILLA_ENCHANTS", "inventory.enchantParsing.hideVanillaEnchants")
            .m("features/ITEM_PICKUP_LOG/value", "inventory.itemPickupLog.enabled")
            .m("features/SHOW_ITEM_COOLDOWNS/value", "inventory.itemCooldowns.enabled")
            .m("features/COMPACT_TAB_LIST/value", "gui.tabListExtras.compact")
            .m("features/FETCHUR_TODAY/value", "mining.dwarvenSolvers.fetchur")
            .m("features/DUNGEONS_MAP_DISPLAY/value", "dungeons.map.enabled")
            .m("features/DUNGEONS_SECRETS_DISPLAY/value", "dungeons.secrets.secretsDisplay")
            .m("features/CHAT_MESSAGE_COPYING/value", "chat.copyChat.enabled")
            .m("features/PET_DISPLAY/value", "misc.pets.display.enabled")
            .add();

        new Builder("skyocean", "SkyOcean", "skyocean/config.jsonc", "skyocean/config.json")
            .m("fishing.lavaReplacement", "fishing.visuals.lavaReplacement")
            .m("fishing.fixBobber", "fishing.visuals.fixBobber")
            .m("fishing.hideOtherBobbers", "fishing.visuals.hideOtherBobbers")
            .m("garden.crop_fever_effect.enabled", "farming.cropFever.enabled")
            .same("garden.crop_fever_effect.", "farming.cropFever.", "startingSound", "rngSound", "backgroundMusic", "coinRain")
            .m("garden.crop_fever_effect.hueShiftingShader", "farming.cropFever.hueShift")
            .m("inventory.inventoryButtons", "inventory.inventoryButtons.enabled")
            .m("inventory.sackValue.enabled", "inventory.sacksValue.enabled")
            .m("mining.puzzlerSolver", "mining.dwarvenSolvers.puzzler")
            .m("mining.metalDetectorSolver", "mining.dwarvenSolvers.metalDetector")
            .m("foraging.galatea.muteThePhantoms", "foraging.extras.mutePhantoms")
            .m("misc.revertMasterStars", "inventory.revertMasterStars")
            .m("misc.hideLightning", "misc.visualTweaks.hideLightning")
            .m("misc.hideEntityFire", "misc.visualTweaks.hideEntityFire")
            .m("misc.totemAnimation", "combat.rareDrops.totemAnimation")
            .add();

        new Builder("stella", "Stella", "stella/settings.json")
            .m("Dungeons/Score Alerts/scoreAlerts.alert270", "dungeons.score.alert270")
            .m("Dungeons/Score Alerts/scoreAlerts.alert300", "dungeons.score.alert300")
            .m("Dungeons/Score Alerts/forcePaul", "dungeons.score.forcePaul")
            .m("Dungeons/Leap Announce/leapAnnounce", "dungeons.leapMenu.announce")
            .m("Secrets/Waypoints/secretWaypoints", "dungeons.secrets.secretWaypoints")
            .m("StellaNav/Map/mapEnabled", "dungeons.map.enabled")
            .m("Msc./Pet Display/petDisplay", "misc.pets.display.enabled")
            .add();

        new Builder("sbo", "SkyBlockOverhaul (SBO)", "sbo/config.jsonc", "sbo/config.json")
            .same("Diana.", "events.diana.burrows.", "spadeGuess", "arrowGuess", "closeBurrowDetection")
            .m("Diana.showBeaconBeam", "events.diana.burrows.beaconBeam")
            .m("Diana.dontWarpIfBurrowClose", "events.diana.warp.dontWarpIfClose")
            .m("Diana.shareRareMob", "events.diana.rareMobSharing")
            .m("Diana.mythosMobHp", "events.diana.mobHealth.enabled")
            .m("Diana.sphinxSolver", "events.diana.sphinxSolver")
            .m("Diana.announceCocoon", "events.diana.drops.announceCocoon")
            .m("Diana.sendSinceMessage", "events.diana.drops.sinceMessages")
            .m("Diana.lootAnnouncerParty", "events.diana.drops.sendToParty")
            .m("Party Commands/warpCommand", "misc.partyCommands.warp")
            .m("Party Commands/allinviteCommand", "misc.partyCommands.allInvite")
            .m("Party Commands/transferCommand", "misc.partyCommands.transfer")
            .m("Party Commands/carrotCommand", "misc.partyCommands.carrot")
            .m("Party Commands/timeCommand", "misc.partyCommands.time")
            .m("Party Commands/tpsCommand", "misc.partyCommands.tps")
            .m("Party Commands/dianaPartyCommands", "misc.partyCommands.diana")
            .add();

        new Builder("customscoreboard", "Custom Scoreboard", "customscoreboard/config.jsonc", "customscoreboard/config.json")
            .same("", "gui.customScoreboard.", "enabled", "hideWhenTab", "hideWhenChat", "customLines", "actions")
            .m("hide_hypixel", "gui.customScoreboard.hideHypixel")
            .m("text_shadow", "gui.customScoreboard.textShadow")
            .m("outsideSkyBlock", "gui.customScoreboard.outsideSkyblock")
            .m("Background.enabled", "gui.customScoreboard.background.enabled")
            .m("line_modification.time24hFormat", "gui.customScoreboard.lineOptions.time24h")
            .m("line_modification.piggy", "gui.customScoreboard.lineOptions.showPiggy")
            .m("line_modification.hidePurseInDungeons", "gui.customScoreboard.lineOptions.hidePurseInDungeons")
            .m("line_modification.all_events", "gui.customScoreboard.lineOptions.showAllEvents")
            .m("line_modification.consecutive_separator", "gui.customScoreboard.lineOptions.condenseSeparators")
            .m("line_modification.separators_start_end", "gui.customScoreboard.lineOptions.hideSeparatorsAtEnds")
            .add();

        new Builder("rfu", "RFU", "rfu/settings.jsonc", "rfu/settings.json")
            .m("Sea Creatures/rareScDisplay", "fishing.rareSc.display")
            .m("Sea Creatures/rareScOnlyWhenFishing", "fishing.rareSc.displayOnlyFishing")
            .m("Sea Creatures/rareScSound", "fishing.rareSc.sound")
            .m("Sea Creatures/lootshareRange", "fishing.rareSc.lootshareRange")
            .m("Sea Creatures/filledLsRange", "fishing.rareSc.filledLootshareRange")
            .m("Sea Creatures/coloredShurikenBar", "fishing.rareSc.shurikenBar")
            .m("Sea Creatures/timeToKill", "fishing.extras.timeToKill")
            .m("Lava Fishing/diedJawbusSound", "fishing.extras.jawbusDeathSound")
            .m("General Fishing/deployableExpiredAlert", "fishing.alerts.deployables")
            .m("Other/petDisplay", "misc.pets.display.enabled")
            .add();

        new Builder("nopo", "NoPo", "nopo/config.json")
            .m("partyFinderKickButton.enabled", "dungeons.partyFinderStats.kickButton")
            .m("cocoonConfig.enabled", "combat.cocoonAlert.enabled")
            .add();

        new Builder("skyblockextras", "SkyblockExtras", "skyblockextras.json")
            .m("harvestFeastEnabled", "events.harvestFeast.display")
            .m("rngDropOverlayEnabled", "misc.rngHud.enabled")
            .m("rngDropOverlayBackgroundEnabled", "misc.rngHud.background")
            .m("petOverlayEnabled", "misc.pets.display.enabled")
            .m("petBackgroundEnabled", "misc.pets.display.background")
            .add();
    }
}
