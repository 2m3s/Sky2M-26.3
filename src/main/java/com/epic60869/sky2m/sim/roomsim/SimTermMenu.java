package com.epic60869.sky2m.sim.roomsim;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * A terminal's chest menu on the sim's integrated server: a plain chest window titled like Hypixel's, whose clicks
 * never move an item. Every click is handed to the {@link SimTermBoard} instead, which changes the slots; vanilla's
 * click handling then sends the client whatever differs from what it predicted (the pane it thought it picked up
 * goes back), the same correction Hypixel's server makes. So the client sees an ordinary server menu and the
 * mod's terminal solvers, timers and sounds treat it as the real one.
 *
 * <p>Middle clicks (Odin's solver clicks with them) count as left clicks; shift clicks, drags, number keys and the
 * player's own inventory do nothing. {@link SimTerms} ticks the open menus (Melody moves) and closes a solved one
 * a tick later, outside the click packet's handling.
 */
final class SimTermMenu extends ChestMenu {

    /** Menus open right now, ticked and checked by {@link SimTerms}. Server thread only. */
    static final List<SimTermMenu> OPEN = new ArrayList<>();

    final SimTermBoard board;
    final Player player;
    final SimTerms.Terminal terminal;
    final long openedAt = System.currentTimeMillis();
    boolean reported;

    SimTermMenu(int id, Inventory inventory, SimTermBoard board, SimTerms.Terminal terminal) {
        this(id, inventory, board, terminal, new SimpleContainer(board.rows() * 9));
    }

    private SimTermMenu(int id, Inventory inventory, SimTermBoard board, SimTerms.Terminal terminal, SimpleContainer container) {
        super(type(board.rows()), id, inventory, container, board.rows());
        this.board = board;
        this.player = inventory.player;
        this.terminal = terminal;
        board.fill(container);
        OPEN.add(this);
    }

    private static MenuType<ChestMenu> type(int rows) {
        return switch (rows) {
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    @Override
    public void clicked(int slot, int button, ContainerInput input, Player who) {
        if (slot < 0 || slot >= board.rows() * 9 || board.solved()) {
            return;
        }
        if (input == ContainerInput.CLONE) {
            board.click(slot, 0);
        } else if (input == ContainerInput.PICKUP) {
            board.click(slot, button == 1 ? 1 : 0);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player who, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player who) {
        return true;
    }

    @Override
    public void removed(Player who) {
        super.removed(who);
        OPEN.remove(this);
    }
}
