package com.epic60869.sky2m.sb.skyblock.dungeon.puzzle;

import java.util.Set;

import com.mojang.brigadier.Command;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import com.epic60869.sky2m.sb.events.DungeonEvents;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sb.utils.Constants;
import com.epic60869.sky2m.sb.utils.Resettable;
import com.epic60869.sky2m.sb.utils.Tickable;
import com.epic60869.sky2m.sb.utils.render.Renderable;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public abstract class DungeonPuzzle implements Tickable, Renderable, Resettable {
	protected final String puzzleName;
	private final Set<String> roomNames;
	private boolean shouldSolve;

	public DungeonPuzzle(String puzzleName, String... roomName) {
		this(puzzleName, Set.of(roomName));
	}

	public DungeonPuzzle(String puzzleName, Set<String> roomNames) {
		this.puzzleName = puzzleName;
		this.roomNames = roomNames;
		DungeonEvents.PUZZLE_MATCHED.register(room -> {
			if (this.roomNames.contains(room.getName())) {
				room.addSubProcess(this);
				shouldSolve = true;
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, _) -> dispatcher.register(literal("s2").then(literal("dungeons").then(literal("puzzle").then(literal(puzzleName).then(literal("solve").executes(context -> {
			Room currentRoom = DungeonManager.getCurrentRoom();
			if (currentRoom != null) {
				reset();
				currentRoom.addSubProcess(this);
				context.getSource().sendFeedback(Constants.PREFIX.get().append("§aSolving " + puzzleName + " puzzle in the current room."));
			} else {
				context.getSource().sendError(Constants.PREFIX.get().append("§cCurrent room is null."));
			}
			return Command.SINGLE_SUCCESS;
		})))))));
		ClientPlayConnectionEvents.JOIN.register(this);
	}

	public boolean shouldSolve() {
		return shouldSolve;
	}

	@Override
	public void reset() {
		shouldSolve = false;
	}
}
