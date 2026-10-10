package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.config.SkyblockerConfig;
import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonMap;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonMapLabels;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonMapTexture;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonScore;
import com.epic60869.sky2m.sb.skyblock.dungeon.device.ArrowAlign;
import com.epic60869.sky2m.sb.skyblock.dungeon.device.LightsOn;
import com.epic60869.sky2m.sb.skyblock.dungeon.device.SimonSays;
import com.epic60869.sky2m.sb.skyblock.dungeon.device.TargetPractice;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.CreeperBeams;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.DungeonBlaze;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.IceFill;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.IcePath;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.ThreeWeirdos;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.TicTacToe;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.Trivia;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.boulder.Boulder;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.waterboard.WaterboardOneFlow;
import com.epic60869.sky2m.sb.skyblock.dungeon.puzzle.waterboard.WaterboardPreviewer;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import com.epic60869.sky2m.sb.skyblock.waypoint.FairySouls;
import com.epic60869.sky2m.sb.utils.container.ContainerSolverManager;
import com.epic60869.sky2m.sb.utils.render.LevelRenderExtractionCallback;
import com.epic60869.sky2m.sb.utils.scheduler.MessageScheduler;
import com.epic60869.sky2m.sb.utils.scheduler.Scheduler;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Starts Sky2M's port of Skyblocker's dungeon and experimentation features (map, puzzle
 * solvers, secret waypoints, terminals, devices, experiments) and keeps the port's config in
 * sync with Sky2M's own settings.
 */
public final class Sky2MDungeons {
    private static int ticks;

    private Sky2MDungeons() {}

    public static void init() {
        syncConfig();

        // Skyblocker runs these through its @Init annotation processor.
        FairySouls.init();
        com.epic60869.sky2m.sb.skyblock.waypoint.Waypoints.init();
        ContainerSolverManager.init();
        DungeonManager.init();
        DungeonPlayerManager.init();
        DungeonScore.init();
        ScoreCalculator.init();
        SelfClass.init();
        OdinTerminals.init();
        OdinDevices.init();
        DungeonMap.init();
        DungeonMapLabels.init();
        DungeonMapTexture.init();
        Boulder.init();
        CreeperBeams.init();
        DungeonBlaze.init();
        IceFill.init();
        IcePath.init();
        ThreeWeirdos.init();
        TicTacToe.init();
        Trivia.init();
        WaterboardOneFlow.init();
        WaterboardPreviewer.init();
        ArrowAlign.init();
        LightsOn.init();
        SimonSays.init();
        TargetPractice.init();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Scheduler.INSTANCE.tick();
            MessageScheduler.INSTANCE.tick();
            if (++ticks % 20 == 0) syncConfig();
        });
        Sky2MWorldRender.register(collector -> LevelRenderExtractionCallback.EVENT.invoker().onExtract(collector));
    }

    /** Copies Sky2M's toggles into the Skyblocker config sections the ported code reads. */
    public static void syncConfig() {
        Sky2MConfig sky2m = Sky2MConfig.current();
        if (sky2m == null) return;
        FeatureConfigs.Dungeons d = sky2m.dungeons;
        SkyblockerConfig sb = SkyblockerConfigManager.get();

        sb.dungeons.dungeonMap.enableMap = d.map.enabled;
        sb.dungeons.dungeonMap.fancyMap = d.map.fancy;
        sb.dungeons.dungeonMap.showRoomLabels = d.map.roomLabels;
        sb.dungeons.dungeonMap.backgroundBlur = d.map.background;
        int mapSize = Math.round(128 * d.map.scale);
        sb.dungeons.dungeonMap.mapX = com.epic60869.sky2m.features.core.Sky2MHuds.mapX(d.map.x, mapSize);
        sb.dungeons.dungeonMap.mapY = com.epic60869.sky2m.features.core.Sky2MHuds.mapY(d.map.y, mapSize);
        sb.dungeons.dungeonMap.mapScaling = d.map.scale;

        sb.dungeons.puzzleSolvers.solveTicTacToe = d.puzzles.ticTacToe;
        sb.dungeons.puzzleSolvers.solveThreeWeirdos = false; // Odin solver in OdinPuzzleSolvers
        sb.dungeons.puzzleSolvers.creeperSolver = false; // Odin solver in OdinPuzzleSolvers
        // Only Odin's Water Board solver draws the lever line; Skyblocker's one-flow solver drew a second one.
        sb.dungeons.puzzleSolvers.waterboardOneFlow = false;
        // Skyblocker's previews still run next to Odin's solver.
        sb.dungeons.puzzleSolvers.previewWaterPath = d.puzzles.waterPreviewPath;
        sb.dungeons.puzzleSolvers.previewLeverEffects = d.puzzles.waterPreviewLevers;
        sb.dungeons.puzzleSolvers.blazeSolver = false; // Odin solver in OdinPuzzleSolvers
        sb.dungeons.puzzleSolvers.solveBoulder = false; // Odin solver in OdinPuzzleSolvers
        sb.dungeons.puzzleSolvers.solveIceFill = false; // Odin solver in OdinPuzzleSolvers
        sb.dungeons.puzzleSolvers.solveSilverfish = d.puzzles.silverfish;
        sb.dungeons.puzzleSolvers.solveTrivia = false; // Odin solver in OdinPuzzleSolvers
        sb.dungeons.puzzleSolvers.solveTeleportMaze = false; // Odin solver in OdinPuzzleSolvers

        sb.dungeons.secretWaypoints.enableSecretWaypoints = d.secrets.secretWaypoints;
        sb.dungeons.secretWaypoints.waypointType = d.secrets.waypointType;
        // Sky2M's own door highlight replaces Skyblocker's blood-rush door box (it used to come on whenever
        // Sky2M's was off, so turning everything off still left a door highlight).
        sb.dungeons.doorHighlight.enableDoorHighlight = false;

        // Terminals are always solved by OdinTerminals (Odin or NoammAddons style); Skyblocker's highlights stay off.
        sb.dungeons.terminals.solveColor = false;
        sb.dungeons.terminals.solveOrder = false;
        sb.dungeons.terminals.solveStartsWith = false;
        sb.dungeons.terminals.solveSameColor = false;
        boolean odinDevices = d.f7.terminals.odinDevices;
        sb.dungeons.devices.solveSimonSays = d.f7.terminals.simonSays && !odinDevices;
        sb.dungeons.devices.solveLightsOn = d.f7.terminals.lightsOn;
        sb.dungeons.devices.solveArrowAlign = d.f7.terminals.arrowAlign && !odinDevices;
        sb.dungeons.devices.solveTargetPractice = d.f7.terminals.targetPractice && !odinDevices;

        sb.helpers.experiments.enableChronomatronSolver = sky2m.misc.experimentalTable.chronomatron;
        sb.helpers.experiments.enableSuperpairsSolver = sky2m.misc.experimentalTable.superpairs;
        sb.helpers.experiments.enableUltrasequencerSolver = sky2m.misc.experimentalTable.ultrasequencer;
        sb.helpers.experiments.blockIncorrectClicks = sky2m.misc.experimentalTable.preventMisclicks;

        // Dungeon terminals remain display-only; experiment misclick blocking is user-configurable above.
        sb.dungeons.terminals.blockIncorrectClicks = false;
        sb.dungeons.mimicMessage.sendMimicMessage = false;
        sb.dungeons.princeMessage.sendPrinceMessage = false;
        sb.dungeons.batMessage.sendBatMessage = false;
        // Score alerts come from Sky2M's ScoreCalculator (NoammAddons' calculation) instead.
        sb.dungeons.dungeonScore.enableDungeonScore270Title = false;
        sb.dungeons.dungeonScore.enableDungeonScore270Sound = false;
        sb.dungeons.dungeonScore.enableDungeonScore270Message = false;
        sb.dungeons.dungeonScore.enableDungeonScore300Title = false;
        sb.dungeons.dungeonScore.enableDungeonScore300Sound = false;
        sb.dungeons.dungeonScore.enableDungeonScore300Message = false;
        sb.dungeons.dungeonScore.enableDungeonCryptsMessage = false;
    }
}
