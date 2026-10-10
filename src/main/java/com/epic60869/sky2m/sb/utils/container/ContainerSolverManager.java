package com.epic60869.sky2m.sb.utils.container;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import com.epic60869.sky2m.sb.annotations.Init;
import com.epic60869.sky2m.sb.skyblock.dungeon.terminal.ColorTerminal;
import com.epic60869.sky2m.sb.skyblock.dungeon.terminal.LightsOnTerminal;
import com.epic60869.sky2m.sb.skyblock.dungeon.terminal.OrderTerminal;
import com.epic60869.sky2m.sb.skyblock.dungeon.terminal.SameColorTerminal;
import com.epic60869.sky2m.sb.skyblock.dungeon.terminal.StartsWithTerminal;
import com.epic60869.sky2m.sb.skyblock.experiment.ChronomatronSolver;
import com.epic60869.sky2m.sb.skyblock.experiment.SuperpairsSolver;
import com.epic60869.sky2m.sb.skyblock.experiment.UltrasequencerSolver;
import com.epic60869.sky2m.sb.utils.Utils;
import com.epic60869.sky2m.sb.utils.render.gui.ColorHighlight;

/**
 * Manager class for {@link SimpleContainerSolver}s like terminal solvers and experiment solvers. To add a new gui solver, extend {@link SimpleContainerSolver} and register it in {@link #ContainerSolverManager()}.
 */
public class ContainerSolverManager {
	// Sky2M registers only the solvers it ports: terminals and experimentation tables.
	private static final List<ContainerSolver> solvers = new ArrayList<>(List.of(
			new ColorTerminal(),
			new OrderTerminal(),
			new StartsWithTerminal(),
			new LightsOnTerminal(),
			new ChronomatronSolver(),
			new SuperpairsSolver(),
			UltrasequencerSolver.INSTANCE,
			SameColorTerminal.INSTANCE
	));
	private static @Nullable ContainerSolver currentSolver = null;
	private static @Nullable List<ColorHighlight> highlights;
	/**
	 * Useful for keeping track of a solver's state in a Screen instance, such as if Hypixel closes & reopens a screen after every click (as they do with terminals).
	 */
	private static int screenId = 0;

	private ContainerSolverManager() {}

	public static @Nullable ContainerSolver getCurrentSolver() {
		return currentSolver;
	}

	public static void registerSolver(ContainerSolver solver) {
		solvers.add(solver);
	}

	@Init
	public static void init() {
		ScreenEvents.BEFORE_INIT.register((_, screen, _, _) -> {
			if (screen instanceof AbstractContainerScreen<?> containerScreen) {
				ScreenEvents.remove(screen).register(_ -> clearScreen());
				onSetScreen(containerScreen);
			} else {
				clearScreen();
			}
		});
	}

	public static void onSetScreen(AbstractContainerScreen<?> screen) {
		String screenName = screen.getTitle().getString();
		for (ContainerSolver solver : solvers) {
			// Checks if the solver should be processed.
			if ((Utils.isOnSkyblock() || !solver.skyblockOnly()) && solver.isEnabled()) {
				// Checks if the solver matches the screen.
				if ((solver instanceof RegexContainerMatcher containerMatcher && containerMatcher.test(screenName)) || solver.test(screen)) {
					// Checks if the solver should be processed for this screen type.
					if (screen instanceof ContainerScreen || !solver.chestScreensOnly()) {
						++screenId;
						currentSolver = solver;
						currentSolver.start(screen);
						markHighlightsDirty();
						return;
					}
				}
			}
		}
		clearScreen();
	}

	public static void clearScreen() {
		if (currentSolver != null) {
			currentSolver.reset();
			currentSolver = null;
		}
	}

	public static void markHighlightsDirty() {
		highlights = null;

		if (currentSolver != null) {
			currentSolver.markDirty();
		}
	}

	/**
	 * @return Whether the click should be disallowed.
	 */
	public static boolean onSlotClick(int slot, ItemStack stack, int button) {
		return currentSolver != null && currentSolver.onClickSlot(slot, stack, screenId, button);
	}

	public static void onExtract(GuiGraphicsExtractor context, AbstractContainerScreen<?> handledScreen, List<Slot> slots) {
		if (currentSolver == null) return;

		if (currentSolver.chestInventoryOnly() && handledScreen.getMenu() instanceof ChestMenu chestMenu) {
			slots = slots.subList(0, chestMenu.getRowCount() * 9);
		}

		if (highlights == null) highlights = currentSolver.getColors(slotMap(slots));
		for (ColorHighlight highlight : highlights) {
			Slot slot = slots.get(highlight.slot());
			int color = highlight.color();
			context.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, color);
		}
	}

	public static Int2ObjectMap<ItemStack> slotMap(List<Slot> slots) {
		Int2ObjectMap<ItemStack> slotMap = new Int2ObjectRBTreeMap<>();
		for (int i = 0; i < slots.size(); i++) {
			slotMap.put(i, slots.get(i).getItem());
		}
		return slotMap;
	}
}
