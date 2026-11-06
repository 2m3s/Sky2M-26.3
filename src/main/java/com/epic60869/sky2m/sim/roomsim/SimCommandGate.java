package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.features.sbc.SbcStaff;
import com.epic60869.sky2m.sim.util.ModChat;
import com.epic60869.sky2m.sim.util.ModLog;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who may use which sim command, in one place.
 *
 * <p><b>Party leader only.</b> In a sim party ({@link SimParty}) the host runs the run: on a guest's client every sim
 * command refuses with {@link #LEADER_ONLY}, and the host's server refuses the matching guest actions as well
 * ({@link #guestActionAllowed}), so a modified client gets nowhere either. {@code /speed}, {@code /pc},
 * {@code /simparty}, the read-only {@code /simscore} and {@code /simwhere} stay open to guests, and so do
 * {@code /simwardrobe} and {@code /simpets}: a guest's wardrobe, equipment, pet and Pet Rule are his item choices
 * and go to the host with him (his stat numbers do not - {@link SimParty}).
 *
 * <p><b>Staff only, even solo.</b> {@code /simstats} (and the Player Stats screen), {@code /fly}, {@code /simrevive}
 * and {@code /summon} need {@link SbcStaff#isStaff()} ({@code /s2 stafflogin}). A staff party guest may use them too:
 * they go to the host as actions and act on the guest alone (his own flight, stats, revive, a mob at his feet).
 *
 * <p>The checks run when a command EXECUTES, not in {@code requires()}: Fabric copies client commands into the
 * suggestion tree once, when the server's command tree arrives on join, while the sim hello that makes a client a
 * guest and the staff login both arrive after that. A {@code requires()} gate would also turn the refusal into the
 * server's "Unknown command" instead of saying why.
 *
 * <p>The gates are applied after every sim command is registered ({@link #register}, last in the sim's registration),
 * by merging a copy of each gated command tree whose every command is wrapped back onto the dispatcher - so the
 * commands themselves need no checks of their own and a new subcommand is gated with its root.
 */
public final class SimCommandGate {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");

    public static final String LEADER_ONLY = "Only the party leader can use sim commands.";
    public static final String STAFF_ONLY = "Staff only - log in with /s2 stafflogin.";

    /** Who a command is for. */
    public enum Access {
        /** Anyone in the sim, party guests included. */
        EVERYONE,
        /** The host of a sim party, or anyone playing solo. */
        LEADER,
        /** Staff ({@link SbcStaff#isStaff()}), and in a party only the host. */
        STAFF
    }

    /**
     * Every gated client command, by its path (space-separated for a single subcommand). Commands not listed keep
     * their own {@code requires()} and stay open to guests: {@code /speed}, {@code /simparty} (whose host-only
     * subcommands already refuse a guest), {@code /simscore} itself and {@code /simwhere} (both read-only).
     */
    static final Map<String, Access> COMMANDS = new LinkedHashMap<>();

    static {
        for (String name : new String[]{"start", "simitem", "item", "simkeys", "boss", "simterms", "simclass",
                "simloadout", "simpreset", "simdummy", "next", "back", "map", "simbuild", "simpuzzle",
                "simscore paul"}) {
            COMMANDS.put(name, Access.LEADER);
        }
        for (String name : new String[]{"simstats", "fly", "simrevive", "summon"}) {
            COMMANDS.put(name, Access.STAFF);
        }
    }

    private SimCommandGate() {
    }

    /** The gate on this command path ("simscore paul"), or {@link Access#EVERYONE} when it is not gated. */
    public static Access access(String path) {
        return COMMANDS.getOrDefault(path, Access.EVERYONE);
    }

    /** Registers the gates. Must be the LAST sim command registration, so every command it gates exists. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> gateAll(dispatcher));
    }

    /** Applies {@link #COMMANDS} to {@code dispatcher}. */
    public static <S> void gateAll(CommandDispatcher<S> dispatcher) {
        for (Map.Entry<String, Access> e : COMMANDS.entrySet()) {
            if (!gate(dispatcher, e.getKey(), e.getValue())) {
                LOGGER.warn("Sim command gate: /{} is not registered", e.getKey());
            }
        }
    }

    // ------------------------------------------------------------------ decisions

    /** Whether this client may lead: not a guest in somebody else's sim party (solo or the host). */
    public static boolean isLeaderOrSolo(Minecraft client) {
        return !SimState.isGuest(client);
    }

    /** Whether this player has staff access ({@code /s2 stafflogin}). */
    public static boolean isStaff() {
        return SbcStaff.isStaff();
    }

    /** Why a command for {@code access} is refused, or null when it may run. Pure, for the tests. */
    public static String refusal(Access access, boolean guest, boolean staff) {
        if (access == null || access == Access.EVERYONE) {
            return null;
        }
        if (guest) {
            // Staff commands work for a staff guest too: they act on him alone, through the host (SimParty).
            return access == Access.STAFF && staff ? null : LEADER_ONLY;
        }
        if (access == Access.STAFF && !staff) {
            return STAFF_ONLY;
        }
        return null;
    }

    /** Why a command for {@code access} is refused right now, or null when it may run. Client thread. */
    public static String refusal(Access access) {
        return refusal(access, !isLeaderOrSolo(Minecraft.getInstance()), isStaff());
    }

    /** Whether a command for {@code access} may run now; says why in chat when not. Client thread. */
    public static boolean allowed(Access access) {
        String why = refusal(access);
        if (why == null) {
            return true;
        }
        ModChat.send("Sim", ModChat.bad(why));
        return false;
    }

    /**
     * The host's server: whether a party guest's {@link SimParty.Action} is a gameplay input it may still send.
     * Everything command-driven (give, revive, start and anything unknown) is the leader's alone.
     */
    public static boolean guestActionAllowed(String action) {
        return switch (action == null ? "" : action) {
            // profile: class, wardrobe and pet only - the host ignores the guest's stat numbers (SimParty).
            // wardrobe: equipping one of his own wardrobe slots (SimGear.equipSlot).
            case "profile", "ultimate", "beam", "terminator", "speed", "wardrobe", "item_left" -> true;
            default -> false;
        };
    }

    /** {@link #guestActionAllowed(String)}, plus the staff commands for a guest whose client says he is staff. */
    public static boolean guestActionAllowed(String action, boolean staff) {
        if (staff && action != null && switch (action) {
            case "staff_fly", "staff_summon", "revive" -> true;
            default -> false;
        }) {
            return true;
        }
        return guestActionAllowed(action);
    }

    // ------------------------------------------------------------------ the tree

    /**
     * Wraps every command under {@code path} with the {@code access} check.
     *
     * @return false when there is no such command
     */
    public static <S> boolean gate(CommandDispatcher<S> dispatcher, String path, Access access) {
        CommandNode<S> parent = dispatcher.getRoot();
        CommandNode<S> node = null;
        for (String part : path.trim().split(" +")) {
            if (node != null) {
                parent = node;
            }
            node = parent.getChild(part);
            if (node == null) {
                return false;
            }
        }
        if (node == null) {
            return false;
        }
        // Merged onto the existing node by name: addChild replaces each command and keeps requires(), suggestions
        // and every child the copy does not carry.
        parent.addChild(gated(node, access).build());
        return true;
    }

    private static <S> ArgumentBuilder<S, ?> gated(CommandNode<S> node, Access access) {
        ArgumentBuilder<S, ?> copy = node.createBuilder();
        Command<S> body = node.getCommand();
        if (body != null) {
            copy.executes(ctx -> allowed(access) ? body.run(ctx) : 0);
        }
        if (node.getRedirect() == null) {
            for (CommandNode<S> child : node.getChildren()) {
                copy.then(gated(child, access));
            }
        }
        return copy;
    }
}
