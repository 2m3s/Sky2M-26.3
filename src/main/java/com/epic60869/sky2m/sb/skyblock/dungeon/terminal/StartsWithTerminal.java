package com.epic60869.sky2m.sb.skyblock.dungeon.terminal;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import net.minecraft.world.item.ItemStack;

import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.epic60869.sky2m.sb.utils.container.ContainerSolver;
import com.epic60869.sky2m.sb.utils.container.SimpleContainerSolver;
import com.epic60869.sky2m.sb.utils.container.StackDisplayModifier;
import com.epic60869.sky2m.sb.utils.render.gui.ColorHighlight;

public final class StartsWithTerminal extends SimpleContainerSolver implements TerminalSolver, StackDisplayModifier {
	private List<Integer> clickedSlotIds = List.of();

	public StartsWithTerminal() {
		super("^What starts with: '([A-Z])'\\?$");
	}

	@Override
	public boolean isEnabled() {
		this.clickedSlotIds = new ArrayList<>();
		return SkyblockerConfigManager.get().dungeons.terminals.solveStartsWith;
	}

	@Override
	public List<ColorHighlight> getColors(Int2ObjectMap<ItemStack> slots) {
		ContainerSolver.trimEdges(slots, 6);

		String prefix = this.groups[0];
		List<ColorHighlight> highlights = new ArrayList<>();

		for (Int2ObjectMap.Entry<ItemStack> slot : slots.int2ObjectEntrySet()) {
			ItemStack stack = slot.getValue();
			boolean clicked = this.clickedSlotIds.contains(slot.getIntKey());

			if (!clicked && stack.getHoverName().getString().startsWith(prefix)) {
				highlights.add(ColorHighlight.green(slot.getIntKey()));
			}
		}
		return highlights;
	}

	@Override
	public boolean onClickSlot(int slot, ItemStack stack, int screenId, int button) {
		boolean clicked = this.clickedSlotIds.contains(slot);

		// Block clicks on already clicked items and invalid items
		if (clicked || stack == null || stack.isEmpty()) {
			return this.shouldBlockIncorrectClicks();
		}

		String prefix = this.groups[0];

		// Block click if its the wrong solution or if its correct mark the item as clicked
		if (!stack.getHoverName().getString().startsWith(prefix)) {
			return this.shouldBlockIncorrectClicks();
		} else {
			this.clickedSlotIds.add(slot);
		}

		return false;
	}

	@Override
	public ItemStack modifyDisplayStack(int slotIndex, ItemStack stack) {
		// 5 rows * 9 = 45
		return slotIndex >= 45 || stack.getHoverName().getString().startsWith(this.groups[0]) ? stack : ItemStack.EMPTY;
	}
}
