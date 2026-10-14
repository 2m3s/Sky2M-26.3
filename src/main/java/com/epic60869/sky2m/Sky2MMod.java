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

public final class Sky2MMod implements ClientModInitializer {

    private Sky2MConfig config;

    @Override
    public void onInitializeClient() {
        Minecraft minecraft = Minecraft.getInstance();
        Path configDir = minecraft.gameDirectory.toPath().resolve("config");
        config = Sky2MConfig.load(configDir.resolve("sky2m-mod.json"));
        // Register key mappings during client initialization, before GameOptions is initialized.
        Sky2MKeyMappings.init();
        Sky2MRecipeCommand.init();
        Sky2MCraftHelper.init(configDir);

        FarmingRngTracker.get().register();
        Sky2MRngHud.register(config);
        Sky2MCommissionHud.register(config);
        // CommandKeys port: /s2 keys. Key mappings must be registered during client init.
        com.epic60869.sky2m.commandkeys.CommandKeys.init();
        com.epic60869.sky2m.commandkeys.Sky2MCommandKeysMigration.migrate(configDir);
        com.epic60869.sky2m.commandkeys.CommandKeys.getKeybinds().forEach(net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper::registerKeyMapping);
        ClientCommandRegistrationCallback.EVENT.register(com.epic60869.sky2m.commandkeys.command.Commands::register);
        ClientTickEvents.END_CLIENT_TICK.register(com.epic60869.sky2m.commandkeys.CommandKeys::afterClientTick);
        Sky2MStorageSearch.init(configDir);
        Sky2MCustom.init(configDir);
        Sky2MDateCalculator.init();

        // Shared infrastructure for the skill features.
        com.epic60869.sky2m.features.core.Sky2MLocation.init();
        com.epic60869.sky2m.features.core.Sky2MChat.init();
        com.epic60869.sky2m.features.core.Sky2MAlerts.init();
        Sky2MFoxyScare.init();
        Sky2MLeaderboards.init();
        Sky2MPartyFinder.init();
        com.epic60869.sky2m.features.core.Sky2MHuds.init(configDir);
        com.epic60869.sky2m.features.core.Sky2MWorldRender.init();

        com.epic60869.sky2m.features.combat.CombatFeatures.init();
        com.epic60869.sky2m.features.combat.BestiaryOverlay.init();
        com.epic60869.sky2m.features.combat.ZealotCounter.init(configDir);
        com.epic60869.sky2m.features.combat.DianaRareMobs.init();
        com.epic60869.sky2m.features.combat.PartyCoordWaypoints.init();
        com.epic60869.sky2m.features.combat.DianaBurrows.init();
        com.epic60869.sky2m.features.combat.DianaSphinx.init();
        com.epic60869.sky2m.features.combat.DianaProfitTracker.init(configDir);
        com.epic60869.sky2m.features.combat.DianaTracker.init(configDir);
        com.epic60869.sky2m.features.combat.DianaMobHealth.init();
        com.epic60869.sky2m.features.combat.DianaAchievements.init(configDir);
        com.epic60869.sky2m.features.combat.InquisitorGamble.init();
        com.epic60869.sky2m.features.misc.profit.SessionTracker.init(configDir);
        com.epic60869.sky2m.features.misc.profit.ProfitTracker.init(configDir);
        com.epic60869.sky2m.features.combat.DianaLobbyCompromised.init();
        com.epic60869.sky2m.features.slayer.SlayerFeatures.init();
        com.epic60869.sky2m.features.slayer.EndermanSlayer.init();
        com.epic60869.sky2m.features.slayer.BlazeSlayer.init();
        com.epic60869.sky2m.features.slayer.RngMeterValue.init();
        com.epic60869.sky2m.features.slayer.SlayerTimes.init(configDir);
        com.epic60869.sky2m.features.slayer.SlayerPbHud.init();
        com.epic60869.sky2m.features.slayer.SlayerMinibossAlert.init();
        com.epic60869.sky2m.features.slayer.SlayerBossProfit.init(configDir);
        com.epic60869.sky2m.features.garden.GardenFeatures.init();
        com.epic60869.sky2m.features.fishing.FishingFeatures.init();
        com.epic60869.sky2m.features.fishing.FishingHookTimer.init();
        com.epic60869.sky2m.features.dungeons.SecretChime.init();
        com.epic60869.sky2m.features.mining.MiningFeatures.init();
        com.epic60869.sky2m.features.mining.CrystalHollowsWaypoints.init();
        com.epic60869.sky2m.features.mining.PickaxeAbility.init();
        com.epic60869.sky2m.features.mining.DrillFuel.init();
        com.epic60869.sky2m.features.mining.PristineRecord.init(configDir);
        com.epic60869.sky2m.features.portfolio.Portfolio.init(configDir);
        com.epic60869.sky2m.features.portfolio.CollectionPortfolio.init(configDir);
        com.epic60869.sky2m.features.portfolio.BazaarNotifications.init();
        com.epic60869.sky2m.features.portfolio.BazaarBookmarks.init();
        com.epic60869.sky2m.features.skills.SkillFeatures.init();
        com.epic60869.sky2m.features.skills.SkillProgress.init();
        // Hunting (SkyHanni's Hunting category).
        com.epic60869.sky2m.features.hunting.IslandGraph.init();
        com.epic60869.sky2m.features.hunting.AttributeShards.init(configDir);
        com.epic60869.sky2m.features.hunting.ShardTrackerDisplay.init();
        com.epic60869.sky2m.features.hunting.HuntingProfitTracker.init(configDir);
        com.epic60869.sky2m.features.hunting.HuntingFeatures.init();
        com.epic60869.sky2m.features.hunting.SafariFeatures.init();
        com.epic60869.sky2m.features.dungeons.Sky2MDungeons.init();
        com.epic60869.sky2m.features.dungeons.DungeonFeatures.init(configDir);
        com.epic60869.sky2m.features.misc.PartyCommands.init();
        com.epic60869.sky2m.features.misc.ItemNotification.init();
        com.epic60869.sky2m.features.misc.DiscordPresence.init();
        com.epic60869.sky2m.features.helditem.HeldItem.init(configDir);
        com.epic60869.sky2m.features.misc.ScrollableTooltips.init();
        com.epic60869.sky2m.features.misc.ToggleSprint.init();
        com.epic60869.sky2m.features.misc.WarpShortcuts.init();
        com.epic60869.sky2m.features.misc.PricePaid.init(configDir);
        com.epic60869.sky2m.features.misc.CollectionTracker.init(configDir);
        Sky2MStaff.init();
        Sky2MUpdateChecker.init();
        com.epic60869.sky2m.reports.Reports.init();
        Sky2MToggleCommands.init();
        com.epic60869.sky2m.features.misc.CopyChat.init();
        com.epic60869.sky2m.features.misc.KeyConflicts.init();
        com.epic60869.sky2m.features.misc.SlotLocking.init();
        com.epic60869.sky2m.features.misc.WardrobeHotkeys.init();
        com.epic60869.sky2m.features.misc.storage.StorageOverlay.init(configDir);
        com.epic60869.sky2m.features.misc.ItemEmojis.init(configDir);
        com.epic60869.sky2m.features.misc.HypixelButton.init();
        com.epic60869.sky2m.features.misc.AutoJoinSkyblock.init();
        com.epic60869.sky2m.features.misc.SmartDisconnect.init();
        com.epic60869.sky2m.features.misc.ServerInfo.init();
        com.epic60869.sky2m.features.misc.HideStatusEffects.init();
        com.epic60869.sky2m.features.misc.KeepTerrainLoaded.init(configDir);
        com.epic60869.sky2m.features.misc.Trail.init();
        com.epic60869.sky2m.features.misc.gif.GifPlayer.init();
        com.epic60869.sky2m.features.misc.gif.DvdScreensaver.init();
        com.epic60869.sky2m.features.misc.crosshair.CustomCrosshair.init();
        com.epic60869.sky2m.features.misc.InventoryHud.init();
        com.epic60869.sky2m.features.misc.ItemPickupLog.init();
        com.epic60869.sky2m.features.misc.ChatTranslator.init();
        com.epic60869.sky2m.features.profileviewer.ProfileViewer.init();
        com.epic60869.sky2m.features.misc.SearchOverlay.register();
        com.epic60869.sky2m.features.itemlist.ItemList.init();
        com.epic60869.sky2m.features.slayer.SlayerTargetHighlight.init();
        com.epic60869.sky2m.features.slayer.EggHitsDisplay.init();
        com.epic60869.sky2m.features.misc.EtherwarpOverlay.init();
        com.epic60869.sky2m.features.misc.ScreenshotShare.init();
        com.epic60869.sky2m.features.misc.JoinCommands.init();
        com.epic60869.sky2m.features.misc.InventoryButtons.init();
        com.epic60869.sky2m.features.misc.SpeedDisplay.init();
        com.epic60869.sky2m.features.dungeons.SecretsDisplay.init();
        com.epic60869.sky2m.features.dungeons.CaseOpening.init();
        com.epic60869.sky2m.features.dungeons.DungeonChestProfit.init();
        com.epic60869.sky2m.features.dungeons.ChestHistory.init();
        Sky2MChangelog.init();
        Sky2MWhatsNew.init();
        Sky2MNopoFeatures.init(configDir);
        Sky2MNick.init(config);
        Sky2MMouseLock.init(config);
        com.epic60869.sky2m.features.sbc.Sbc.init();
        com.epic60869.sky2m.features.sbc.BestFriends.init();
        Sky2MGlobalChat.init();
        Sky2MCurrentChat.init(configDir);
        Sky2MPriceTooltip.init();
        com.epic60869.sky2m.features.misc.MuseumTooltip.init(configDir);
        com.epic60869.sky2m.features.misc.AccessoryTooltip.init(configDir);
        com.epic60869.sky2m.features.misc.EnchantParser.init();
        com.epic60869.sky2m.features.misc.SackTracker.init(configDir);
        // Sacks Value HUD (after SackTracker, whose sack contents it prices).
        com.epic60869.sky2m.features.misc.SacksValue.init();
        com.epic60869.sky2m.features.misc.StashCompact.init();
        com.epic60869.sky2m.features.garden.visitor.GardenVisitors.init(configDir);
        com.epic60869.sky2m.features.misc.ItemCooldowns.init();
        com.epic60869.sky2m.features.misc.AbilityTimers.init();
        com.epic60869.sky2m.features.misc.CustomScoreboard.init();
        com.epic60869.sky2m.features.dungeons.RagAxe.init();
        com.epic60869.sky2m.features.dungeons.MageBeam.init();
        com.epic60869.sky2m.features.dungeons.TerminalTimers.init();
        com.epic60869.sky2m.features.dungeons.RunHistory.init();
        com.epic60869.sky2m.features.dungeons.TermismScreen.registerCommand();
        com.epic60869.sky2m.sim.Sky2MSim.init();
        com.epic60869.sky2m.features.dungeons.party.PartyDungeonData.init();
        com.epic60869.sky2m.features.dungeons.party.ModConflictWarnings.init();
        com.epic60869.sky2m.features.dungeons.party.TeamMelody.init();
        com.epic60869.sky2m.features.dungeons.bridge.BridgeFeature.register();
        com.epic60869.sky2m.features.dungeons.bridge.BridgeFeature.registerCommand();
        com.epic60869.sky2m.features.misc.EventCalendar.init();
        com.epic60869.sky2m.features.garden.PestHighlight.init();
        com.epic60869.sky2m.features.garden.CropFeverEffects.init();
        com.epic60869.sky2m.features.dungeons.BonusKillAlerts.init();
        com.epic60869.sky2m.features.dungeons.TerminalProgress.init();
        com.epic60869.sky2m.features.dungeons.DeathTracker.init(configDir);
        com.epic60869.sky2m.features.dungeons.ClassCounters.init();
        com.epic60869.sky2m.features.dungeons.KeyAlerts.init();
        com.epic60869.sky2m.features.dungeons.DragonSplits.init(configDir);
        com.epic60869.sky2m.features.dungeons.CataCalculator.init(configDir);
        com.epic60869.sky2m.features.dungeons.DupeClass.init();
        com.epic60869.sky2m.features.mining.ScathaTracker.init(configDir);
        com.epic60869.sky2m.features.mining.GemstoneTracker.init(configDir);
        com.epic60869.sky2m.features.mining.OrderedWaypoints.init(configDir);
        com.epic60869.sky2m.features.slayer.SlayerPhaseTimer.init();
        com.epic60869.sky2m.features.slayer.VoidgloomLasers.init();
        com.epic60869.sky2m.features.combat.DamageNumbers.init();
        com.epic60869.sky2m.features.combat.LowStats.init();
        com.epic60869.sky2m.features.misc.CustomSounds.init();
        com.epic60869.sky2m.features.misc.CarryTracker.init(configDir);
        com.epic60869.sky2m.features.misc.MayorDisplay.init();
        com.epic60869.sky2m.features.misc.NetworthHistory.init(configDir);
        com.epic60869.sky2m.features.misc.ItemQuality.init();
        com.epic60869.sky2m.features.misc.ChatFilters.init();
        com.epic60869.sky2m.features.misc.ChatNameLinks.init();
        com.epic60869.sky2m.features.misc.Sky2MAchievements.init(configDir);
        com.epic60869.sky2m.features.misc.waypoints.AreaWaypoints.init(configDir);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // On 26.3 typed text only arrives while SDL text input is on, which vanilla turns on for its own text boxes.
        // MoulConfig's (the settings search, text options) never do, so it's on for as long as its screen is open.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent)) return;
            client.textInputManager().startTextInput(screen);
            net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen)
                .register(removed -> client.textInputManager().stopTextInput(removed));
        });

        registerCommands();

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
    }

    private int partyTransfer(String player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getConnection().sendCommand(("p transfer " + player).trim());
        return 1;
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> commandTree(String name) {
        var root = ClientCommands.literal(name)
            .executes(context -> openMenu())
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
            .then(ClientCommands.literal("gui").executes(context -> openHudEditor()))
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
