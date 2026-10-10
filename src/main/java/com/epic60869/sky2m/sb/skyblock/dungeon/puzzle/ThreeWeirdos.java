package com.epic60869.sky2m.sb.skyblock.dungeon.puzzle;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;

import com.epic60869.sky2m.sb.annotations.Init;
import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.Room;
import com.epic60869.sky2m.sb.utils.render.RenderHelper;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;

public class ThreeWeirdos extends DungeonPuzzle {
	@SuppressWarnings("unused")
	private static final ThreeWeirdos INSTANCE = new ThreeWeirdos();
	protected static final Pattern PATTERN = Pattern.compile("^\\[NPC] ([A-Z][a-z]+): (?:The reward is(?: not in my chest!|n't in any of our chests\\.)|My chest (?:doesn't have the reward\\. We are all telling the truth\\.|has the reward and I'm telling the truth!)|At least one of them is lying, and the reward is not in [A-Z][a-z]+'s chest!|Both of them are telling the truth\\. Also, [A-Z][a-z]+ has the reward in their chest!)$");
	private static final float[] GREEN_COLOR_COMPONENTS = new float[]{0, 1, 0};
	private static @Nullable BlockPos pos;
	static @Nullable AABB boundingBox;

	private ThreeWeirdos() {
		super("three-weirdos", "three-chests");
		com.epic60869.sky2m.features.core.Sky2MChat.onGameMessage((message, overlay) -> {
			ClientLevel world = Minecraft.getInstance().level;
			if (overlay || !shouldSolve() || !SkyblockerConfigManager.get().dungeons.puzzleSolvers.solveThreeWeirdos || world == null || !DungeonManager.isCurrentRoomMatched()) return;

			Matcher matcher = PATTERN.matcher(ChatFormatting.stripFormatting(message.getString()));
			if (!matcher.matches()) return;
			String name = matcher.group(1);
			Room room = DungeonManager.getCurrentRoom();
			if (room == null || !room.isMatched()) return;

			checkForNPC(world, room, new BlockPos(13, 69, 24), name);
			checkForNPC(world, room, new BlockPos(15, 69, 25), name);
			checkForNPC(world, room, new BlockPos(17, 69, 24), name);
		});
		UseBlockCallback.EVENT.register((_, _, _, blockHitResult) -> {
			if (blockHitResult.getType() == HitResult.Type.BLOCK && blockHitResult.getBlockPos().equals(pos)) {
				pos = null;
			}
			return InteractionResult.PASS;
		});
	}

	@Init
	public static void init() {
	}

	private void checkForNPC(ClientLevel world, Room room, BlockPos relative, String name) {
		BlockPos npcPos = room.relativeToActual(relative);
		List<ArmorStand> npcs = world.getEntitiesOfClass(
				ArmorStand.class,
				AABB.encapsulatingFullBlocks(npcPos, npcPos),
				entity -> entity.getName().getString().equals(name)
		);
		if (!npcs.isEmpty()) {
			pos = room.relativeToActual(relative.offset(1, 0, 0));
			boundingBox = RenderHelper.getBlockBoundingBox(world, pos);
			npcs.forEach(entity -> entity.setCustomName(Component.literal(name).withStyle(ChatFormatting.GREEN)));
		}
	}

	@Override
	public void tick(Minecraft client) {}

	@Override
	public void extractRendering(PrimitiveCollector collector) {
		if (shouldSolve() && boundingBox != null) {
			collector.submitFilledBox(boundingBox, GREEN_COLOR_COMPONENTS, 0.5f, false);
		}
	}

	@Override
	public void reset() {
		super.reset();
		pos = null;
	}
}
