package com.epic60869.sky2m.sim;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.sim.livemap.LiveMapFeature;
import com.epic60869.sky2m.sim.roomsim.SimMenuScreen;
import com.epic60869.sky2m.sim.roomsim.SimState;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

/**
 * Dungeons > Dungeon Sim: Killer560's Dungeon Sim and Map Designer (the {@code sim} package), started up for Sky2M.
 *
 * <p>Everything in the package stands down outside the Sim's own world: its map, wither doors and puzzle solvers are
 * on only while a Sim session is active (Sky2M's own versions run on Hypixel), and its commands (/map, /start,
 * /summon, /fly, ...) only exist inside it. Killer560's cheat-build parts (auto-clear, auto routes, the interactive
 * map's walking, Fairy Door) aren't ported.
 */
public final class Sky2MSim {

    private Sky2MSim() {
    }

    public static void init() {
        com.epic60869.sky2m.sim.roomsim.SimAbilities.register();
        com.epic60869.sky2m.sim.roomsim.SimActionBar.register();
        com.epic60869.sky2m.sim.roomsim.SimSpiritSceptre.register();
        com.epic60869.sky2m.sim.roomsim.SimTerminator.register();
        com.epic60869.sky2m.sim.roomsim.SimWorld.register();
        com.epic60869.sky2m.sim.roomsim.SimBuildQueue.register();
        com.epic60869.sky2m.sim.roomsim.SimRun.registerStartCommand();
        com.epic60869.sky2m.sim.roomsim.SimBreakerState.register();
        com.epic60869.sky2m.sim.roomsim.SimItemLore.register();
        com.epic60869.sky2m.sim.roomsim.SimPauseEntry.register();
        com.epic60869.sky2m.sim.roomsim.SimSurvival.register();
        com.epic60869.sky2m.sim.roomsim.FloorSizeLog.register();
        // The room library can be huge: read it ~20 s after launch (400 ticks) so it doesn't compete with start-up.
        // Anything that needs it sooner still loads it on demand (RoomLibrary.get/has/names).
        final int[] libraryDelay = {400};
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (libraryDelay[0] > 0 && --libraryDelay[0] == 0) com.epic60869.sky2m.sim.roomsim.RoomLibrary.loadAsync();
        });
        com.epic60869.sky2m.sim.roomsim.SimItems.register();
        com.epic60869.sky2m.sim.roomsim.SimBuilder.register();
        com.epic60869.sky2m.sim.roomsim.SimDoors.register();
        com.epic60869.sky2m.sim.roomsim.SimMobs.register();
        com.epic60869.sky2m.sim.roomsim.SimSecretItems.register();
        com.epic60869.sky2m.sim.roomsim.SimRun.register();
        com.epic60869.sky2m.sim.roomsim.SimSidebar.register();
        com.epic60869.sky2m.sim.roomsim.SimTabList.register();
        com.epic60869.sky2m.sim.roomsim.SimClass.register();
        com.epic60869.sky2m.sim.roomsim.SimSpeed.register();
        // Before SimMimic: it counts every chest click as a secret, and Fabric stops at the first non-PASS.
        com.epic60869.sky2m.sim.roomsim.puzzles.SimTeleportMazePuzzle.registerChestGuard();
        com.epic60869.sky2m.sim.roomsim.SimMimic.register();
        com.epic60869.sky2m.sim.roomsim.SimScoreCommand.register();
        com.epic60869.sky2m.sim.roomsim.SimRoomCycle.register();
        com.epic60869.sky2m.sim.roomsim.SimLoadout.register();
        com.epic60869.sky2m.sim.roomsim.SimSecrets.register();
        com.epic60869.sky2m.sim.roomsim.SimRoomLevers.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimCreeperPuzzle.registerRender();
        com.epic60869.sky2m.sim.roomsim.SimWhere.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimBlazePuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimCreeperPuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimQuizPuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimTicTacToePuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimWaterPuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimBoulderPuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimTeleportMazePuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimIceFillPuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimIcePathPuzzle.register();
        com.epic60869.sky2m.sim.roomsim.puzzles.SimPuzzles.register();

        // What the Sim's world reads its run from: the dungeon state, the map scan, the party and the helpers.
        com.epic60869.sky2m.sim.secrets.DungeonState.register();
        com.epic60869.sky2m.sim.util.SkyblockGate.register();
        com.epic60869.sky2m.sim.leapmenu.PartyTracker.register();
        com.epic60869.sky2m.sim.witherdoors.WitherDoorsFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.BoulderSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.QuizSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.IceFillSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.WeirdosSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.WaterSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.BeamsSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.BlazeSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.TicTacToeSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.TeleportMazeSolverFeature.register();
        com.epic60869.sky2m.sim.puzzlesolvers.IcePathSolverFeature.register();
        LiveMapFeature.register();

        // The Dungeon Sim draws the selected Dungeon Map style through DungeonMapStyles (hooked from sb.skyblock.dungeon.DungeonMap); no separate sim map.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("sim").executes(c -> {
                    openMenu();
                    return 1;
                })));
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen) || !titleButtonEnabled()) return;
            Screens.getWidgets(screen).add(Button.builder(Component.literal("Dungeon Sim"),
                    b -> com.epic60869.sky2m.sim.compat.McCompat.setScreen(client, new SimMenuScreen(screen)))
                .bounds(6, 6, 80, 20)
                .tooltip(Tooltip.create(Component.literal("Practise dungeon rooms and floors in your own world (Sky2M)")))
                .build());
        });
    }

    private static boolean titleButtonEnabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.dungeons.dungeonSim.titleButton;
    }

    /** Opens the Sim's menu. Picking a room there refuses (with a message) while you're on a server. */
    public static void openMenu() {
        Minecraft client = Minecraft.getInstance();
        Compat.queueOpenScreen(new SimMenuScreen(com.epic60869.sky2m.sim.compat.McCompat.screen(client)));
    }
}
