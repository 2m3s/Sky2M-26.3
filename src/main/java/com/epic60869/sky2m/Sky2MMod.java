package com.epic60869.sky2m;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.arguments.StringArgumentType;

import java.nio.file.Path;
import com.epic60869.sky2m.chat.Sky2MAliases;
import com.epic60869.sky2m.chat.Sky2MCurrentChat;
import com.epic60869.sky2m.chat.Sky2MGlobalChat;
import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.config.Sky2MConfigCode;
import com.epic60869.sky2m.config.Sky2MSetup;
import com.epic60869.sky2m.debug.Sky2MDebug;
import com.epic60869.sky2m.debug.Sky2MModOverlap;
import com.epic60869.sky2m.debug.Sky2MSelfTest;
import com.epic60869.sky2m.fun.Sky2MFoxy;
import com.epic60869.sky2m.fun.Sky2MFoxyScare;
import com.epic60869.sky2m.gui.Sky2MHudEditorScreen;
import com.epic60869.sky2m.gui.Sky2MNotesScreen;
import com.epic60869.sky2m.gui.Sky2MStorageSearchScreen;
import com.epic60869.sky2m.hud.Sky2MCommissionHud;
import com.epic60869.sky2m.hud.Sky2MHealth;
import com.epic60869.sky2m.hud.Sky2MRngHud;
import com.epic60869.sky2m.input.Sky2MKeyMappings;
import com.epic60869.sky2m.input.Sky2MMouseLock;
import com.epic60869.sky2m.input.Sky2MMouseReset;
import com.epic60869.sky2m.input.Sky2MToggleCommands;
import com.epic60869.sky2m.items.Sky2MStorageSearch;
import com.epic60869.sky2m.misc.Sky2MCustom;
import com.epic60869.sky2m.misc.Sky2MNopoFeatures;
import com.epic60869.sky2m.misc.Sky2MTabWidgetManager;
import com.epic60869.sky2m.nick.Sky2MNick;
import com.epic60869.sky2m.nick.Sky2MNickCommand;
import com.epic60869.sky2m.price.Sky2MCraftHelper;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.price.Sky2MRecipeCommand;
import com.epic60869.sky2m.social.Sky2MLeaderboards;
import com.epic60869.sky2m.social.Sky2MPartyFinder;
import com.epic60869.sky2m.social.Sky2MPlayerNotes;
import com.epic60869.sky2m.social.Sky2MProfiles;
import com.epic60869.sky2m.social.Sky2MRivals;
import com.epic60869.sky2m.social.Sky2MStaff;
import com.epic60869.sky2m.tools.RngHud;
import com.epic60869.sky2m.tools.Sky2MCalculator;
import com.epic60869.sky2m.tools.Sky2MDateCalculator;
import com.epic60869.sky2m.update.Sky2MChangelog;
import com.epic60869.sky2m.update.Sky2MUpdateChecker;
import com.epic60869.sky2m.update.Sky2MWhatsNew;

public final class Sky2MMod implements ClientModInitializer {

    private Sky2MConfig config;

    private static long startupT0;
    private static long lapT;
    private static volatile long startupMs = -1;

    /** How long startup took in ms, or -1 before it finished. */
    public static long startupMillis() {
        return startupMs;
    }

    /** Logs how long the init since the previous lap took, when it was 25ms or more. */
    private static void lap(String name) {
        long now = System.nanoTime();
        long ms = (now - lapT) / 1_000_000L;
        if (ms >= 25) System.out.println("[Sky2M] startup: " + name + " took " + ms + "ms");
        lapT = System.nanoTime();
    }

    @Override
    public void onInitializeClient() {
        startupT0 = lapT = System.nanoTime();
        Minecraft minecraft = Minecraft.getInstance();
        Path configDir = minecraft.gameDirectory.toPath().resolve("config");
        config = Sky2MConfig.load(configDir.resolve("sky2m-mod.json"));
        // Register key mappings during client initialization, before GameOptions is initialized.
        Sky2MKeyMappings.init(); lap("Sky2MKeyMappings.init");
        Sky2MRecipeCommand.init(); lap("Sky2MRecipeCommand.init");
        Sky2MCraftHelper.init(configDir); lap("Sky2MCraftHelper.init");

        RngHud.get().register();
        Sky2MRngHud.register(config); lap("Sky2MRngHud.register");
        Sky2MCommissionHud.register(config); lap("Sky2MCommissionHud.register");
        // CommandKeys port: /s2 keys. Key mappings must be registered during client init.
        com.epic60869.sky2m.commandkeys.CommandKeys.init(); lap("CommandKeys.init");
        com.epic60869.sky2m.commandkeys.Sky2MCommandKeysMigration.migrate(configDir); lap("Sky2MCommandKeysMigration.migrate");
        com.epic60869.sky2m.commandkeys.CommandKeys.getKeybinds().forEach(net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper::registerKeyMapping);
        ClientCommandRegistrationCallback.EVENT.register(com.epic60869.sky2m.commandkeys.command.Commands::register);
        ClientTickEvents.END_CLIENT_TICK.register(com.epic60869.sky2m.commandkeys.CommandKeys::afterClientTick); lap("END_CLIENT_TICK.register");
        Sky2MStorageSearch.init(configDir); lap("Sky2MStorageSearch.init");
        Sky2MCustom.init(configDir); lap("Sky2MCustom.init");
        Sky2MDateCalculator.init(); lap("Sky2MDateCalculator.init");

        // Shared infrastructure for the skill features.
        com.epic60869.sky2m.features.core.Sky2MLocation.init(); lap("Sky2MLocation.init");
        com.epic60869.sky2m.features.core.Sky2MChat.init(); lap("Sky2MChat.init");
        com.epic60869.sky2m.features.core.Sky2MAlerts.init(); lap("Sky2MAlerts.init");
        Sky2MFoxyScare.init(); lap("Sky2MFoxyScare.init");
        Sky2MLeaderboards.init(); lap("Sky2MLeaderboards.init");
        Sky2MPartyFinder.init(); lap("Sky2MPartyFinder.init");
        com.epic60869.sky2m.features.core.Sky2MHuds.init(configDir); lap("Sky2MHuds.init");
        com.epic60869.sky2m.features.core.Sky2MWorldRender.init(); lap("Sky2MWorldRender.init");

        com.epic60869.sky2m.features.combat.CombatFeatures.init(); lap("CombatFeatures.init");
        com.epic60869.sky2m.features.combat.CocoonTracker.init(); lap("CocoonTracker.init");
        com.epic60869.sky2m.features.misc.visual.CustomFonts.init(); lap("CustomFonts.init");
        com.epic60869.sky2m.features.combat.BestiaryOverlay.init(); lap("BestiaryOverlay.init");
        com.epic60869.sky2m.features.combat.ZealotCounter.init(configDir); lap("ZealotCounter.init");
        com.epic60869.sky2m.features.combat.DianaRareMobs.init(); lap("DianaRareMobs.init");
        com.epic60869.sky2m.features.combat.PartyCoordWaypoints.init(); lap("PartyCoordWaypoints.init");
        com.epic60869.sky2m.features.combat.DianaBurrows.init(); lap("DianaBurrows.init");
        com.epic60869.sky2m.features.combat.DianaSphinx.init(); lap("DianaSphinx.init");
        com.epic60869.sky2m.features.combat.DianaProfitTracker.init(configDir); lap("DianaProfitTracker.init");
        com.epic60869.sky2m.features.combat.DianaTracker.init(configDir); lap("DianaTracker.init");
        com.epic60869.sky2m.features.combat.DianaMobHealth.init(); lap("DianaMobHealth.init");
        com.epic60869.sky2m.features.combat.kuudra.KuudraFeatures.init(configDir); lap("KuudraFeatures.init");
        com.epic60869.sky2m.features.combat.DianaAchievements.init(configDir); lap("DianaAchievements.init");
        com.epic60869.sky2m.features.misc.profit.SessionTracker.init(configDir); lap("SessionTracker.init");
        com.epic60869.sky2m.features.misc.profit.ProfitTracker.init(configDir); lap("ProfitTracker.init");
        com.epic60869.sky2m.features.combat.DianaLobbyCompromised.init(); lap("DianaLobbyCompromised.init");
        com.epic60869.sky2m.features.slayer.SlayerFeatures.init(); lap("SlayerFeatures.init");
        com.epic60869.sky2m.features.slayer.EndermanSlayer.init(); lap("EndermanSlayer.init");
        com.epic60869.sky2m.features.slayer.BlazeSlayer.init(); lap("BlazeSlayer.init");
        com.epic60869.sky2m.features.slayer.RngMeterValue.init(); lap("RngMeterValue.init");
        com.epic60869.sky2m.features.slayer.SlayerRngMeterDisplay.init(configDir); lap("SlayerRngMeterDisplay.init");
        com.epic60869.sky2m.features.slayer.SlayerTimes.init(configDir); lap("SlayerTimes.init");
        com.epic60869.sky2m.features.slayer.SlayerPbHud.init(); lap("SlayerPbHud.init");
        com.epic60869.sky2m.features.slayer.SlayerMinibossAlert.init(); lap("SlayerMinibossAlert.init");
        com.epic60869.sky2m.features.slayer.SlayerBossSpawnAlert.init(); lap("SlayerBossSpawnAlert.init");
        com.epic60869.sky2m.features.slayer.SlayerBossProfit.init(configDir); lap("SlayerBossProfit.init");
        com.epic60869.sky2m.features.garden.GardenFeatures.init(); lap("GardenFeatures.init");
        com.epic60869.sky2m.features.fishing.FishingFeatures.init(); lap("FishingFeatures.init");
        com.epic60869.sky2m.features.fishing.FishingHookTimer.init(); lap("FishingHookTimer.init");
        com.epic60869.sky2m.features.dungeons.SecretChime.init(); lap("SecretChime.init");
        com.epic60869.sky2m.features.mining.MiningFeatures.init(); lap("MiningFeatures.init");
        com.epic60869.sky2m.features.mining.CrystalHollowsWaypoints.init(); lap("CrystalHollowsWaypoints.init");
        com.epic60869.sky2m.features.mining.MiningEventHighlight.init(); lap("MiningEventHighlight.init");
        com.epic60869.sky2m.features.mining.PickaxeAbility.init(); lap("PickaxeAbility.init");
        com.epic60869.sky2m.features.mining.DrillFuel.init(); lap("DrillFuel.init");
        com.epic60869.sky2m.features.mining.PristineRecord.init(configDir); lap("PristineRecord.init");
        com.epic60869.sky2m.features.portfolio.Portfolio.init(configDir); lap("Portfolio.init");
        com.epic60869.sky2m.features.portfolio.CollectionPortfolio.init(configDir); lap("CollectionPortfolio.init");
        com.epic60869.sky2m.features.portfolio.BazaarNotifications.init(); lap("BazaarNotifications.init");
        com.epic60869.sky2m.features.portfolio.BazaarBookmarks.init(); lap("BazaarBookmarks.init");
        com.epic60869.sky2m.features.skills.SkillFeatures.init(); lap("SkillFeatures.init");
        com.epic60869.sky2m.features.foraging.ForagingBeaconSolver.init(); lap("ForagingBeaconSolver.init");
        com.epic60869.sky2m.features.foraging.ForagingExtras.init(); lap("ForagingExtras.init");
        com.epic60869.sky2m.features.skills.SkillProgress.init(); lap("SkillProgress.init");
        // Hunting (SkyHanni's Hunting category).
        com.epic60869.sky2m.features.hunting.IslandGraph.init(); lap("IslandGraph.init");
        com.epic60869.sky2m.features.hunting.AttributeShards.init(configDir); lap("AttributeShards.init");
        com.epic60869.sky2m.features.hunting.ShardTrackerDisplay.init(); lap("ShardTrackerDisplay.init");
        com.epic60869.sky2m.features.hunting.HuntingProfitTracker.init(configDir); lap("HuntingProfitTracker.init");
        com.epic60869.sky2m.features.hunting.HuntingFeatures.init(); lap("HuntingFeatures.init");
        com.epic60869.sky2m.features.hunting.SafariFeatures.init(); lap("SafariFeatures.init");
        com.epic60869.sky2m.features.events.EventsFeatures.init(configDir); lap("EventsFeatures.init");
        com.epic60869.sky2m.features.dungeons.Sky2MDungeons.init(); lap("Sky2MDungeons.init");
        com.epic60869.sky2m.features.dungeons.DungeonFeatures.init(configDir); lap("DungeonFeatures.init");
        com.epic60869.sky2m.features.misc.chat.PartyCommands.init(); lap("PartyCommands.init");
        com.epic60869.sky2m.features.misc.items.ItemNotification.init(); lap("ItemNotification.init");
        com.epic60869.sky2m.features.misc.social.DiscordPresence.init(); lap("DiscordPresence.init");
        com.epic60869.sky2m.features.helditem.HeldItem.init(configDir); lap("HeldItem.init");
        com.epic60869.sky2m.features.misc.system.ScrollableTooltips.init(); lap("ScrollableTooltips.init");
        com.epic60869.sky2m.features.misc.system.ToggleSprint.init(); lap("ToggleSprint.init");
        com.epic60869.sky2m.features.misc.system.WarpShortcuts.init(); lap("WarpShortcuts.init");
        com.epic60869.sky2m.features.misc.items.PricePaid.init(configDir); lap("PricePaid.init");
        com.epic60869.sky2m.features.economy.Economy.init(configDir); lap("Economy.init");
        com.epic60869.sky2m.features.misc.system.CollectionTracker.init(configDir); lap("CollectionTracker.init");
        Sky2MStaff.init(); lap("Sky2MStaff.init");
        Sky2MUpdateChecker.init(); lap("Sky2MUpdateChecker.init");
        com.epic60869.sky2m.reports.Reports.init(); lap("Reports.init");
        Sky2MToggleCommands.init(); lap("Sky2MToggleCommands.init");
        com.epic60869.sky2m.features.misc.chat.CopyChat.init(); lap("CopyChat.init");
        com.epic60869.sky2m.features.misc.chat.ChatEmotes.init(); lap("ChatEmotes.init");
        com.epic60869.sky2m.features.misc.system.KeyConflicts.init(); lap("KeyConflicts.init");
        com.epic60869.sky2m.features.core.HudEditorKey.init(); lap("HudEditorKey.init");
        com.epic60869.sky2m.features.misc.items.SlotLocking.init(); lap("SlotLocking.init");
        com.epic60869.sky2m.features.misc.items.WardrobeHotkeys.init(); lap("WardrobeHotkeys.init");
        com.epic60869.sky2m.features.misc.items.CustomWardrobe.init(); lap("CustomWardrobe.init");
        com.epic60869.sky2m.features.misc.social.SocialNames.init(); lap("SocialNames.init");
        com.epic60869.sky2m.features.misc.storage.StorageOverlay.init(configDir); lap("StorageOverlay.init");
        com.epic60869.sky2m.features.misc.items.ItemEmojis.init(configDir); lap("ItemEmojis.init");
        com.epic60869.sky2m.features.misc.system.HypixelButton.init(); lap("HypixelButton.init");
        com.epic60869.sky2m.features.misc.system.AutoJoinSkyblock.init(); lap("AutoJoinSkyblock.init");
        com.epic60869.sky2m.features.misc.system.SmartDisconnect.init(); lap("SmartDisconnect.init");
        com.epic60869.sky2m.features.misc.system.ServerInfo.init(); lap("ServerInfo.init");
        com.epic60869.sky2m.features.misc.visual.HideStatusEffects.init(); lap("HideStatusEffects.init");
        com.epic60869.sky2m.features.misc.system.KeepTerrainLoaded.init(configDir); lap("KeepTerrainLoaded.init");
        com.epic60869.sky2m.features.misc.visual.Trail.init(); lap("Trail.init");
        com.epic60869.sky2m.features.misc.clips.Clips.init(); lap("Clips.init");
        com.epic60869.sky2m.features.misc.gif.GifPlayer.init(); lap("GifPlayer.init");
        com.epic60869.sky2m.features.misc.gif.DvdScreensaver.init(); lap("DvdScreensaver.init");
        com.epic60869.sky2m.features.misc.crosshair.CustomCrosshair.init(); lap("CustomCrosshair.init");
        com.epic60869.sky2m.features.misc.items.InventoryHud.init(); lap("InventoryHud.init");
        com.epic60869.sky2m.features.misc.items.ItemPickupLog.init(); lap("ItemPickupLog.init");
        com.epic60869.sky2m.features.misc.chat.PetDropRarity.init(); lap("PetDropRarity.init");
        com.epic60869.sky2m.features.misc.chat.ChatTranslator.init(); lap("ChatTranslator.init");
        com.epic60869.sky2m.features.profileviewer.ProfileViewer.init(); lap("ProfileViewer.init");
        com.epic60869.sky2m.features.misc.system.SearchOverlay.register(); lap("SearchOverlay.register");
        com.epic60869.sky2m.features.itemlist.ItemList.init(); lap("ItemList.init");
        com.epic60869.sky2m.features.slayer.SlayerTargetHighlight.init(); lap("SlayerTargetHighlight.init");
        com.epic60869.sky2m.features.slayer.EggHitsDisplay.init(); lap("EggHitsDisplay.init");
        com.epic60869.sky2m.features.slayer.SlayerItemsOnGround.init(); lap("SlayerItemsOnGround.init");
        com.epic60869.sky2m.features.misc.visual.EtherwarpOverlay.init(); lap("EtherwarpOverlay.init");
        com.epic60869.sky2m.features.misc.social.ScreenshotShare.init(); lap("ScreenshotShare.init");
        com.epic60869.sky2m.features.misc.chat.JoinCommands.init(); lap("JoinCommands.init");
        com.epic60869.sky2m.features.misc.items.InventoryButtons.init(); lap("InventoryButtons.init");
        com.epic60869.sky2m.features.misc.visual.SpeedDisplay.init(); lap("SpeedDisplay.init");
        com.epic60869.sky2m.features.economy.CoinsChart.init(); lap("CoinsChart.init");
        com.epic60869.sky2m.features.qol.StatusBars.init(); lap("StatusBars.init");
        com.epic60869.sky2m.features.qol.TabListExtras.init(); lap("TabListExtras.init");
        com.epic60869.sky2m.features.qol.RadialMenu.init(); lap("RadialMenu.init");
        com.epic60869.sky2m.features.dungeons.SecretsDisplay.init(); lap("SecretsDisplay.init");
        com.epic60869.sky2m.features.dungeons.CaseOpening.init(); lap("CaseOpening.init");
        com.epic60869.sky2m.features.dungeons.DungeonChestProfit.init(); lap("DungeonChestProfit.init");
        com.epic60869.sky2m.features.dungeons.ChestHistory.init(); lap("ChestHistory.init");
        Sky2MChangelog.init(); lap("Sky2MChangelog.init");
        Sky2MWhatsNew.init(); lap("Sky2MWhatsNew.init");
        Sky2MSetup.init(); lap("Sky2MSetup.init");
        Sky2MRivals.init(); lap("Sky2MRivals.init");
        Sky2MPlayerNotes.init(); lap("Sky2MPlayerNotes.init");
        Sky2MAliases.init(); lap("Sky2MAliases.init");
        com.epic60869.sky2m.features.combat.RngCommand.init(); lap("RngCommand.init");
        com.epic60869.sky2m.features.misc.system.KeybindSheetScreen.register(); lap("KeybindSheetScreen.register");
        com.epic60869.sky2m.features.misc.social.ScreenshotGallery.register(); lap("ScreenshotGallery.register");
        com.epic60869.sky2m.features.core.Sky2MPerfScreen.register(); lap("Sky2MPerfScreen.register");
        com.epic60869.sky2m.features.core.Sky2MPerfWarnings.register(); lap("Sky2MPerfWarnings.register");
        com.epic60869.sky2m.features.core.Sky2MErrors.register(); lap("Sky2MErrors.register");
        com.epic60869.sky2m.features.core.Sky2MHudOverviewScreen.register(); lap("Sky2MHudOverviewScreen.register");
        Sky2MConfigCode.init(); lap("Sky2MConfigCode.init");
        Sky2MProfiles.init(); lap("Sky2MProfiles.init");
        Sky2MModOverlap.init(); lap("Sky2MModOverlap.init");
        Sky2MHealth.init(); lap("Sky2MHealth.init");
        Sky2MSelfTest.init(); lap("Sky2MSelfTest.init");
        Sky2MNopoFeatures.init(configDir); lap("Sky2MNopoFeatures.init");
        Sky2MNick.init(config); lap("Sky2MNick.init");
        Sky2MMouseLock.init(config); lap("Sky2MMouseLock.init");
        com.epic60869.sky2m.features.sbc.Sbc.init(); lap("Sbc.init");
        com.epic60869.sky2m.features.sbc.BestFriends.init(); lap("BestFriends.init");
        Sky2MGlobalChat.init(); lap("Sky2MGlobalChat.init");
        Sky2MCurrentChat.init(configDir); lap("Sky2MCurrentChat.init");
        Sky2MPriceTooltip.init(); lap("Sky2MPriceTooltip.init");
        com.epic60869.sky2m.features.misc.items.MuseumTooltip.init(configDir); lap("MuseumTooltip.init");
        com.epic60869.sky2m.features.misc.items.AccessoryTooltip.init(configDir); lap("AccessoryTooltip.init");
        com.epic60869.sky2m.features.misc.items.EnchantParser.init(); lap("EnchantParser.init");
        com.epic60869.sky2m.features.misc.items.SackTracker.init(configDir); lap("SackTracker.init");
        // Sacks Value HUD (after SackTracker, whose sack contents it prices).
        com.epic60869.sky2m.features.misc.items.SacksValue.init(); lap("SacksValue.init");
        com.epic60869.sky2m.features.misc.chat.StashCompact.init(); lap("StashCompact.init");
        com.epic60869.sky2m.features.misc.chat.CompactBestiary.init(); lap("CompactBestiary.init");
        com.epic60869.sky2m.features.misc.chat.SkyBlockXpChat.init(); lap("SkyBlockXpChat.init");
        com.epic60869.sky2m.features.garden.visitor.GardenVisitors.init(configDir); lap("GardenVisitors.init");
        com.epic60869.sky2m.features.misc.items.ItemCooldowns.init(); lap("ItemCooldowns.init");
        com.epic60869.sky2m.features.misc.items.AbilityTimers.init(); lap("AbilityTimers.init");
        com.epic60869.sky2m.features.misc.visual.CustomScoreboard.init(); lap("CustomScoreboard.init");
        com.epic60869.sky2m.features.dungeons.RagAxe.init(); lap("RagAxe.init");
        com.epic60869.sky2m.features.dungeons.MageBeam.init(); lap("MageBeam.init");
        com.epic60869.sky2m.features.dungeons.TerminalTimers.init(); lap("TerminalTimers.init");
        com.epic60869.sky2m.features.dungeons.BossPhases.init(); lap("BossPhases.init");
        com.epic60869.sky2m.features.dungeons.RunHistory.init(); lap("RunHistory.init");
        com.epic60869.sky2m.features.dungeons.TermismCommand.registerCommand(); lap("TermismScreen.registerCommand");
        com.epic60869.sky2m.sim.Sky2MSim.init(); lap("Sky2MSim.init");
        com.epic60869.sky2m.features.dungeons.party.PartyDungeonData.init(); lap("PartyDungeonData.init");
        com.epic60869.sky2m.features.dungeons.party.ModConflictWarnings.init(); lap("ModConflictWarnings.init");
        com.epic60869.sky2m.features.dungeons.party.TeamMelody.init(); lap("TeamMelody.init");
        com.epic60869.sky2m.features.dungeons.bridge.BridgeFeature.register(); lap("BridgeFeature.register");
        com.epic60869.sky2m.features.dungeons.bridge.BridgeFeature.registerCommand(); lap("BridgeFeature.registerCommand");
        com.epic60869.sky2m.features.misc.visual.EventCalendar.init(); lap("EventCalendar.init");
        com.epic60869.sky2m.features.garden.PestHighlight.init(); lap("PestHighlight.init");
        com.epic60869.sky2m.features.garden.CropFeverEffects.init(); lap("CropFeverEffects.init");
        com.epic60869.sky2m.features.garden.helpers.GardenHelpers.init(); lap("GardenHelpers.init");
        com.epic60869.sky2m.features.dungeons.BonusKillAlerts.init(); lap("BonusKillAlerts.init");
        com.epic60869.sky2m.features.dungeons.TerminalProgress.init(); lap("TerminalProgress.init");
        com.epic60869.sky2m.features.dungeons.DeathTracker.init(configDir); lap("DeathTracker.init");
        com.epic60869.sky2m.features.dungeons.ClassCounters.init(); lap("ClassCounters.init");
        com.epic60869.sky2m.features.dungeons.KeyAlerts.init(); lap("KeyAlerts.init");
        com.epic60869.sky2m.features.dungeons.DragonSplits.init(configDir); lap("DragonSplits.init");
        com.epic60869.sky2m.features.dungeons.CataCalculator.init(configDir); lap("CataCalculator.init");
        com.epic60869.sky2m.features.dungeons.DupeClass.init(); lap("DupeClass.init");
        com.epic60869.sky2m.features.mining.ScathaTracker.init(configDir); lap("ScathaTracker.init");
        com.epic60869.sky2m.features.mining.GemstoneTracker.init(configDir); lap("GemstoneTracker.init");
        com.epic60869.sky2m.features.mining.extras.MiningExtras.init(configDir); lap("MiningExtras.init");
        com.epic60869.sky2m.features.mining.dwarven.DwarvenMining.init(configDir); lap("DwarvenMining.init");
        com.epic60869.sky2m.features.mining.OrderedWaypoints.init(configDir); lap("OrderedWaypoints.init");
        com.epic60869.sky2m.features.slayer.VoidgloomLasers.init(); lap("VoidgloomLasers.init");
        com.epic60869.sky2m.features.combat.DamageNumbers.init(); lap("DamageNumbers.init");
        com.epic60869.sky2m.features.combat.extra.CombatExtras.init(configDir); lap("CombatExtras.init");
        com.epic60869.sky2m.features.combat.LowStats.init(); lap("LowStats.init");
        com.epic60869.sky2m.features.combat.ArmorStacks.init(); lap("ArmorStacks.init");
        com.epic60869.sky2m.features.misc.visual.CustomSounds.init(); lap("CustomSounds.init");
        com.epic60869.sky2m.features.misc.social.CarryTracker.init(configDir); lap("CarryTracker.init");
        com.epic60869.sky2m.features.misc.visual.MayorDisplay.init(); lap("MayorDisplay.init");
        com.epic60869.sky2m.features.misc.system.NetworthHistory.init(configDir); lap("NetworthHistory.init");
        com.epic60869.sky2m.features.misc.items.SackValueHistory.init(configDir); lap("SackValueHistory.init");
        com.epic60869.sky2m.features.misc.items.ItemQuality.init(); lap("ItemQuality.init");
        com.epic60869.sky2m.features.misc.chat.ChatFilters.init(); lap("ChatFilters.init");
        com.epic60869.sky2m.features.misc.chat.SpamFilters.init(); lap("SpamFilters.init");
        com.epic60869.sky2m.features.misc.chat.SideChat.init(); lap("SideChat.init");
        com.epic60869.sky2m.features.qol.ChatRules.init(); lap("ChatRules.init");
        com.epic60869.sky2m.features.qol.MentionAlerts.init(); lap("MentionAlerts.init");
        com.epic60869.sky2m.features.slayer.SlayerRngAdvisor.init(); lap("SlayerRngAdvisor.init");
        com.epic60869.sky2m.features.fishing.TrophyFishTracker.init(configDir); lap("TrophyFishTracker.init");
        com.epic60869.sky2m.features.misc.items.LoadoutHighlight.init(); lap("LoadoutHighlight.init");
        com.epic60869.sky2m.features.misc.chat.ChatNameLinks.init(); lap("ChatNameLinks.init");
        com.epic60869.sky2m.features.social.PartyTools.init(); lap("PartyTools.init");
        com.epic60869.sky2m.features.social.ChatWaypoints.init(); lap("ChatWaypoints.init");
        com.epic60869.sky2m.features.social.ChatProfileIcons.init(); lap("ChatProfileIcons.init");
        com.epic60869.sky2m.features.misc.social.Sky2MAchievements.init(configDir); lap("Sky2MAchievements.init");
        com.epic60869.sky2m.features.misc.waypoints.AreaWaypoints.init(configDir); lap("AreaWaypoints.init");
        com.epic60869.sky2m.features.misc.waypoints.FairySoulsFinder.init(configDir); lap("FairySoulsFinder.init");
        com.epic60869.sky2m.features.misc.items.ShoppingList.init(configDir); lap("ShoppingList.init");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick); lap("END_CLIENT_TICK.register");
        // On 26.3 typed text only arrives while SDL text input is on, which vanilla turns on for its own text boxes.
        // MoulConfig's (the settings search, text options) never do, so it's on for as long as its screen is open.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent)) return;
            client.textInputManager().startTextInput(screen);
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen)
                .register(removed -> client.textInputManager().stopTextInput(removed));
        });

        registerCommands();
        lap("registerCommands");

        startupMs = (System.nanoTime() - startupT0) / 1_000_000L;
        System.out.println("[Sky2M] startup: total init took " + startupMs + "ms");
        System.out.println("[Sky2M] Core mod loaded.");
    }

    private void registerCommands() {
        // /chat sj enters the Sky2M channel; switching to any other channel with /chat leaves it.
        // Done by intercepting the command rather than registering /chat, so Hypixel still gets /chat a, /chat g, ...
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            String lower = command.trim().toLowerCase(java.util.Locale.ROOT);
            if (lower.equals("chat sj") || lower.equals("chat sky2m")) {
                Minecraft.getInstance().execute(this::enterSky2MChat);
                return false;
            }
            if (lower.startsWith("chat ") && Sky2MGlobalChat.isInSky2MChannel()) {
                Sky2MGlobalChat.leaveSky2MChannel();
            }
            return true;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // /s2 and /sky2m.
            for (String root : com.epic60869.sky2m.custom.util.Compat.COMMAND_ROOTS) dispatcher.register(commandTree(root));
            // /s2c: shortcut for /s2 chat.
            for (String chat : new String[]{"s2c"}) {
                dispatcher.register(ClientCommands.literal(chat)
                    .executes(context -> enterSky2MChat())
                    .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> sendGlobalChat(StringArgumentType.getString(context, "message")))));
            }
            // /pt Name: shortcut for /p transfer Name.
            dispatcher.register(ClientCommands.literal("pt")
                .executes(context -> partyTransfer(""))
                .then(ClientCommands.argument("player", StringArgumentType.word())
                    .executes(context -> partyTransfer(StringArgumentType.getString(context, "player")))));
        });
        // After every /s2c subcommand is registered (some come from other classes): a message that starts with a
        // subcommand word ("/s2c block hi nix") matched that subcommand, didn't fit its arguments and wasn't sent.
        // Each subcommand also takes the rest of the line as a message; its own arguments are tried first, so
        // "/s2c block nix" still blocks nix.
        net.minecraft.resources.Identifier s2cFallback = net.minecraft.resources.Identifier.fromNamespaceAndPath("sky2m", "s2c_message_fallback");
        ClientCommandRegistrationCallback.EVENT.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, s2cFallback);
        ClientCommandRegistrationCallback.EVENT.register(s2cFallback, (dispatcher, registryAccess) -> {
            var s2c = dispatcher.getRoot().getChild("s2c");
            if (s2c == null) return;
            for (var sub : new java.util.ArrayList<>(s2c.getChildren())) {
                if (!(sub instanceof com.mojang.brigadier.tree.LiteralCommandNode<?>)) continue;
                String word = sub.getName();
                @SuppressWarnings("unchecked")
                var node = (com.mojang.brigadier.tree.CommandNode<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource>) sub;
                node.addChild(ClientCommands.argument("s2c_message", StringArgumentType.greedyString())
                    .executes(context -> sendGlobalChat(word + " " + StringArgumentType.getString(context, "s2c_message")))
                    .build());
            }
        });
    }

    private int partyTransfer(String player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getConnection().sendCommand(("p transfer " + player).trim());
        return 1;
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> commandTree(String name) {
        var root = ClientCommands.literal(name)
            .executes(context -> openMenu())
            .then(ClientCommands.literal("settings")
                .executes(context -> openMenu())
                .then(ClientCommands.argument("filter", StringArgumentType.greedyString())
                    .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                        java.util.List.of("about", "gui", "garden", "crimson isle", "kuudra", "fishing", "mining", "foraging",
                            "hunting", "safari", "combat", "slayer", "dungeon", "inventory", "events", "diana", "mayor",
                            "skills", "chat", "misc", "profit trackers", "pets", "storage", "waypoints", "hud", "alerts",
                            "tracker", "enabled"),
                        builder))
                    .executes(context -> openFilteredGui(StringArgumentType.getString(context, "filter"))))
                )
            .then(ClientCommands.literal("notes").executes(context -> openNotes()))
            .then(ClientCommands.literal("search").executes(context -> openStorageSearch()))
            .then(Sky2MRecipeCommand.command())
            .then(ClientCommands.literal("calc")
                .then(ClientCommands.argument("calculation", StringArgumentType.greedyString())
                    .executes(context -> calculate(StringArgumentType.getString(context, "calculation")))))
            .then(ClientCommands.literal("chat")
                .executes(context -> enterSky2MChat())
                .then(ClientCommands.literal("leave").executes(context -> leaveSky2MChat()))
                .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                    .executes(context -> sendGlobalChat(StringArgumentType.getString(context, "message")))))
            .then(Sky2MNickCommand.node())
            .then(ClientCommands.literal("discord").executes(context -> discord()))
            .then(ClientCommands.literal("gui")
                .executes(context -> openHudEditor())
                .then(ClientCommands.argument("filter", StringArgumentType.greedyString())
                    .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                        java.util.List.of("about", "gui", "garden", "crimson isle", "kuudra", "fishing", "mining", "foraging",
                            "hunting", "safari", "combat", "slayer", "dungeon", "inventory", "events", "diana", "mayor",
                            "skills", "chat", "misc", "profit trackers", "pets", "storage", "waypoints", "hud", "alerts",
                            "tracker", "enabled"),
                        builder))
                    .executes(context -> openFilteredGui(StringArgumentType.getString(context, "filter")))))
            .then(ClientCommands.literal("debug").executes(context -> Sky2MDebug.run())
                .then(ClientCommands.literal("starred").executes(context -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(com.epic60869.sky2m.features.dungeons.StarredMobs.debug()));
                    return 1;
                })));

        return root;
    }


    private int discord() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            String link = "https://discord.gg/7AAjvjxsby";
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("The Sky2M Discord Is: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(link).withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(link))))));
        });
        return 1;
    }

    private int openFilteredGui(String filter) {
        String query = filter == null ? "" : filter.trim();
        if (query.isEmpty() || query.equalsIgnoreCase("all")) return openMenu();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            // Use MoulConfig's built-in search, preserving the existing settings and save behaviour.
            Sky2MConfig.openGui(query);
        });
        return 1;
    }

    private int openMenu() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> Sky2MFoxy.openMenu(mc, Sky2MConfig::openGui));
        return 1;
    }








    private int enterSky2MChat() {
        Sky2MGlobalChat.enterSky2MChannel();
        return 1;
    }

    private int leaveSky2MChat() {
        Sky2MGlobalChat.leaveSky2MChannel();
        return 1;
    }

    private int sendGlobalChat(String message) {
        if (message != null && message.trim().startsWith("!")) {
            Sky2MGlobalChat.sendBotCommand(message);
        } else {
            Sky2MGlobalChat.send(message);
        }
        return 1;
    }

    private int openHudEditor() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new Sky2MHudEditorScreen(mc.gui.screen())));
        return 1;
    }

    private int calculate(String expression) {
        Minecraft mc = Minecraft.getInstance();
        try {
            String result = Sky2MCalculator.calculate(expression);
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(
                    net.minecraft.network.chat.Component.literal("§6[S2M] §f" + expression + " §7= §a" + result)
                );
            }
        } catch (IllegalArgumentException e) {
            if (mc.player != null) {
                mc.gui.hud.getChat().addClientSystemMessage(
                    net.minecraft.network.chat.Component.literal("§c[S2M] Calc error: §f" + e.getMessage())
                );
            }
        }
        return 1;
    }

    private int openStorageSearch() {
        // Queued so closing the chat doesn't close it again; opens off Hypixel too (it just shows what's cached).
        return com.epic60869.sky2m.custom.util.Compat.queueOpenScreen(new Sky2MStorageSearchScreen(null, ""));
    }

    private int openNotes() {
        Path configDir = Minecraft.getInstance().gameDirectory.toPath().resolve("config");
        Minecraft.getInstance().execute(() ->
            Minecraft.getInstance().gui.setScreen(new Sky2MNotesScreen(configDir)));
        return 1;
    }

    private void tick(Minecraft minecraft) {
        while (Sky2MKeyMappings.SEARCH.consumeClick()) {
            openStorageSearch();
        }
        while (Sky2MKeyMappings.SLAYER_BOSS_SELECT.consumeClick()) {
            com.epic60869.sky2m.features.slayer.SlayerFeatures.selectNextBossOwner();
        }
        Sky2MStorageSearch.tick(minecraft);
        Sky2MNopoFeatures.tick(minecraft);
        Sky2MTabWidgetManager.tick(minecraft);
        Sky2MMouseLock.tick(minecraft);
        Sky2MMouseReset.tick(minecraft);
        Sky2MGlobalChat.tick();
        Sky2MFoxy.tick(minecraft);
    }
}
