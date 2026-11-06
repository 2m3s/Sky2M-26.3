package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.ModLog;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mort, and Hypixel's ready-up: a run starts when the whole party says it is ready.
 *
 * <p>On Hypixel you stand in the Entrance with the gate shut, right-click Mort, and click "Ready" in his menu; the
 * server prints "name is now ready!" to the party, and once everyone is ready it counts down ("Starting in 5
 * seconds." ... "Starting in 1 second.") and opens the gate, with Mort's lines. The mod's run-start detection reads
 * exactly those lines (Run History, splits, Death Recap and the class cooldowns from "Starting in 1 second.", the
 * Score Calculator and Skyblocker's DungeonScore from Mort's), so the sim prints the same ones, to every player in
 * the party, from the server.
 *
 * <p>Mort is a {@link Mannequin} (a player-shaped entity with no AI) that cannot be hurt or pushed and is never saved
 * with the world; every generated floor places a fresh one beside the entrance gate ({@link #place}). His menu is a
 * server menu, so a party guest gets it too. {@code /start} is the shortcut: it readies everyone and starts.
 *
 * <p>The menu also has Hypixel's class picks (2026-11-01): the five classes above the Ready toggle, the selected one
 * glowing, each with its class level. Picking one before the start loads that class's preset ({@link SimPresets}) -
 * a guest's too, since the menu is the server's.
 */
public final class SimMort {

    private static final Logger LOGGER = ModLog.get("sky2m-roomsim");

    /** On the server copy of Mort only (tags are not synced), so he is found again when the floor is rebuilt. */
    private static final String TAG = "sky2m_sim_mort";

    /** Who is ready, by player UUID. Any thread. */
    private static final Set<UUID> READY = ConcurrentHashMap.newKeySet();

    /** Mort menus open right now, so a ready change redraws everyone's. Server thread only. */
    private static final List<MortMenu> OPEN = new ArrayList<>();

    private static volatile UUID mortId;

    private SimMort() {
    }

    static void register() {
        SimPresets.register();
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide()) {
                // The tag is server-side, so the client knows him by his name. SUCCESS makes Fabric send the interact
                // packet and skip the rest of the click, so a held ability item does not also fire at him.
                return entity instanceof Mannequin && entity.getCustomName() != null
                        && "Mort".equals(entity.getCustomName().getString())
                        && SimState.canAct(net.minecraft.client.Minecraft.getInstance())
                        ? InteractionResult.SUCCESS : InteractionResult.PASS;
            }
            if (!isMort(entity)) {
                return InteractionResult.PASS;
            }
            if (player instanceof ServerPlayer sp && hand == InteractionHand.MAIN_HAND) {
                open(sp);
            }
            return InteractionResult.SUCCESS;
        });
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                !level.isClientSide() && isMort(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
        // Someone leaving the party is no longer ready; the rest may now all be.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.player != null && READY.remove(handler.player.getUUID())) {
                server.execute(() -> changed(server));
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pendingDoor != null && SimState.isActive()) {
                placePending(server.overworld());
            }
            if (!OPEN.isEmpty() && server.getTickCount() % 20 == 0) {
                for (MortMenu menu : new ArrayList<>(OPEN)) {
                    menu.redraw(server);
                }
            }
        });
    }

    private static boolean isMort(Entity entity) {
        return entity != null && (entity instanceof MortEntity || entity.entityTags().contains(TAG)
                || entity.getUUID().equals(mortId));
    }

    // ------------------------------------------------------------------ placing him

    /**
     * Puts Mort in the Entrance beside its gate, replacing the last floor's. Server thread; called once the floor
     * is standing. {@code door} is {@link SimBuilder#entranceDoor()}, {@code room} the Entrance's tile centre.
     */
    static void place(ServerLevel level, BlockPos door, BlockPos room) {
        clear(level);
        if (level == null || door == null || room == null) {
            return;
        }
        // Not now: the build runs while the player waits in the holding area, so the Entrance is not loaded, and an
        // entity added there could be lost with its chunk. He is put down when a player is near (tick below), the
        // way the room mobs are (SimMobs.queueRoomMob). Placing him straight away is why he was often missing.
        pendingDoor = door.immutable();
        pendingRoom = room.immutable();
    }

    /** Where Mort still has to go: the entrance gate and the Entrance's centre. Server thread. */
    private static BlockPos pendingDoor, pendingRoom;

    /** Puts Mort down once the Entrance is live. Server thread, every tick. */
    private static void placePending(ServerLevel level) {
        BlockPos door = pendingDoor;
        BlockPos room = pendingRoom;
        if (door == null || room == null || !level.isPositionEntityTicking(door)) {
            return;
        }
        // Across the map, not in 3D: the gate's y is not the floor's (the entrance door on a built floor sat 116
        // blocks under it), which also kept the old floor search below from ever finding the floor.
        boolean near = false;
        for (ServerPlayer p : level.players()) {
            double hx = p.getX() - (door.getX() + 0.5);
            double hz = p.getZ() - (door.getZ() + 0.5);
            near |= hx * hx + hz * hz < 48 * 48;
        }
        if (!near) {
            return;
        }
        pendingDoor = null;
        pendingRoom = null;
        // From the gate back into the room: the axis the door sits on.
        int dx = Integer.signum(room.getX() - door.getX());
        int dz = Integer.signum(room.getZ() - door.getZ());
        if (Math.abs(room.getX() - door.getX()) < Math.abs(room.getZ() - door.getZ())) {
            dx = 0;
        } else {
            dz = 0;
        }
        if (dx == 0 && dz == 0) {
            dz = 1;
        }
        // Four blocks in from the gate and three to its side, where Hypixel stands him: next to the gate, not in it.
        // An Entrance with a pit or a wall there gets the nearest spot that has a floor, then the room's middle.
        Integer y = null;
        int x = 0;
        int z = 0;
        outer:
        for (int in : new int[]{4, 3, 5, 2, 6}) {
            for (int side : new int[]{3, -3, 2, -2, 4, -4, 0}) {
                x = door.getX() + dx * in + dz * side;
                z = door.getZ() + dz * in + dx * side;
                y = floorAt(level, x, z, door.getY());
                if (y != null) {
                    break outer;
                }
            }
        }
        if (y == null) {
            x = room.getX();
            z = room.getZ();
            y = floorAt(level, x, z, door.getY());
        }
        if (y == null) {
            LOGGER.warn("Sim Mort: no floor near the entrance gate {} - not placed", door);
            return;
        }
        MortEntity mort = new MortEntity(level);
        // Facing into the room, the way the party comes to him.
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        mort.snapTo(x + 0.5, y, z + 0.5, yaw, 0f);
        mort.setYHeadRot(yaw);
        mort.setYBodyRot(yaw);
        mort.setNoGravity(true);
        mort.setPermanentlyInvulnerable(true);
        mort.setSilent(true);
        mort.addTag(TAG);
        mort.setCustomName(Component.literal("Mort").withStyle(ChatFormatting.AQUA));
        mort.setCustomNameVisible(true);
        if (level.addFreshEntity(mort)) {
            mortId = mort.getUUID();
            LOGGER.info("Sim Mort: placed at {},{},{} beside the entrance gate {}", x, y, z, door);
        }
    }

    /**
     * The standable block at this column nearest a dungeon room's floor (captured y 69, where the Entrance is walked
     * in): solid underneath, two air above. {@code nearY} (the gate's y) is not used: it is not the floor's.
     */
    private static Integer floorAt(ServerLevel level, int x, int z, int nearY) {
        int base = SimAltitude.toWorld(69);
        for (int d = 0; d <= 14; d++) {
            for (int y : new int[]{base - d, base + d}) {
                BlockPos feet = new BlockPos(x, y, z);
                if (level.getBlockState(feet.below()).isSolid() && level.getBlockState(feet).isAir()
                        && level.getBlockState(feet.above()).isAir()) {
                    return y;
                }
            }
        }
        return null;
    }

    /** Takes Mort off the floor and forgets who was ready: every rebuild, before the old floor goes. Server thread. */
    static void clear(ServerLevel level) {
        READY.clear();
        SimPresets.reset();
        mortId = null;
        pendingDoor = null;
        pendingRoom = null;
        if (level == null) {
            return;
        }
        List<Entity> doomed = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof MortEntity || e.entityTags().contains(TAG)) {
                doomed.add(e);
            }
        }
        for (Entity e : doomed) {
            e.discard();
        }
    }

    /** Mort himself: a mannequin nothing can hurt or shove, and that is never written to the save. */
    private static final class MortEntity extends Mannequin {
        MortEntity(Level level) {
            super(level);
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }

        @Override
        public boolean isPushable() {
            return false;
        }

        // Nothing moves him: a fishing rod's pull, knockback and pushes all end up here.
        @Override
        public void setDeltaMovement(net.minecraft.world.phys.Vec3 motion) {
            super.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        }

        @Override
        public boolean shouldBeSaved() {
            return false;
        }
    }

    // ------------------------------------------------------------------ ready state

    public static boolean isReady(UUID id) {
        return id != null && READY.contains(id);
    }

    /** Forgets who was ready, for a new map or leaving the sim. */
    public static void resetReady() {
        READY.clear();
    }

    /** {@code /start}: everyone in the party is ready. Server thread. */
    static void readyAll(MinecraftServer server) {
        for (ServerPlayer p : SimParty.players(server)) {
            READY.add(p.getUUID());
        }
        redrawAll(server);
    }

    /** A party guest's {@code /start}, sent to the host: the same shortcut the host's own is. Server thread. */
    static void startFor(ServerPlayer sp) {
        if (SimRun.hasStarted() || !SimState.isGeneratedFloor()) {
            return;
        }
        MinecraftServer server = sp.level().getServer();
        readyAll(server);
        changed(server);
    }

    private static boolean allReady(MinecraftServer server) {
        List<ServerPlayer> party = SimParty.players(server);
        if (party.isEmpty()) {
            return false;
        }
        for (ServerPlayer p : party) {
            if (!READY.contains(p.getUUID())) {
                return false;
            }
        }
        return true;
    }

    /** One player's toggle, with Hypixel's party line. Server thread. */
    private static void toggle(ServerPlayer sp) {
        MinecraftServer server = sp.level().getServer();
        if (SimRun.hasStarted()) {
            sp.sendSystemMessage(Component.literal("The dungeon has already started!").withStyle(ChatFormatting.RED));
            return;
        }
        String name = sp.getGameProfile().name();
        Component line;
        if (READY.remove(sp.getUUID())) {
            line = Component.literal(name + " is no longer ready!").withStyle(ChatFormatting.RED);
        } else {
            READY.add(sp.getUUID());
            line = Component.literal(name + " is now ready!").withStyle(ChatFormatting.GREEN);
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(line);
        }
        changed(server);
    }

    /**
     * After any change: everyone ready starts the countdown, anyone not ready stops one that is under way. The run
     * itself is {@link SimRun}'s, on the host's client thread, where the gate and the clock are.
     */
    private static void changed(MinecraftServer server) {
        redrawAll(server);
        boolean all = allReady(server);
        SimAbilities.onClient(() -> {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (all) {
                if (!SimRun.isArmed() && !SimRun.hasStarted()) {
                    SimRun.begin(client, SimBuilder.entranceDoor());
                }
            } else if (SimRun.isArmed() && !SimRun.isRunning() && !SimRun.hasStarted()) {
                SimRun.cancel();
            }
        });
    }

    // ------------------------------------------------------------------ the menu

    /** Hypixel's floor title: "The Catacombs - Floor VII", "Master Mode The Catacombs - Floor VII", "... - Entrance". */
    private static String title() {
        String floor = SimState.floorLabel();
        if (floor == null || floor.isEmpty() || floor.startsWith("E")) {
            return "The Catacombs - Entrance";
        }
        int n;
        try {
            n = Integer.parseInt(floor.substring(1));
        } catch (NumberFormatException e) {
            n = 7;
        }
        String[] roman = {"", "I", "II", "III", "IV", "V", "VI", "VII"};
        String r = n >= 1 && n < roman.length ? roman[n] : String.valueOf(n);
        return (floor.startsWith("M") ? "Master Mode " : "") + "The Catacombs - Floor " + r;
    }

    private static void open(ServerPlayer sp) {
        if (!SimAbilities.simServer(sp)) {
            return;
        }
        if (!SimState.isGeneratedFloor()) {
            sp.sendSystemMessage(Component.literal("[NPC] ").withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("Mort").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(": A run needs a whole floor.").withStyle(ChatFormatting.WHITE)));
            return;
        }
        UUID self = sp.getUUID();
        sp.openMenu(new SimpleMenuProvider((id, inv, player) -> new MortMenu(id, inv, self),
                Component.literal(title())));
    }

    private static void redrawAll(MinecraftServer server) {
        for (MortMenu menu : new ArrayList<>(OPEN)) {
            menu.redraw(server);
        }
    }

    private static final int ROWS = 6;
    /** The party's heads along the second row, their ready state under each, the toggle and Close below. */
    private static final int[] HEAD_SLOTS = {11, 12, 13, 14, 15};
    private static final int TOGGLE_SLOT = 40;
    private static final int CLOSE_SLOT = 49;

    /**
     * The class picks on the row above the toggle, in Hypixel's order and with its icons: Healer (a healing potion),
     * Mage (a blaze rod), Berserk (an iron sword), Archer (a bow), Tank (a leather chestplate). Picking one loads that
     * class's preset ({@link SimPresets#apply}); after the start it is refused, as on Hypixel.
     */
    private static final int[] CLASS_SLOTS = {29, 30, 31, 32, 33};
    private static final SimStats.DungeonClass[] CLASS_ORDER = {SimStats.DungeonClass.HEALER,
            SimStats.DungeonClass.MAGE, SimStats.DungeonClass.BERSERK, SimStats.DungeonClass.ARCHER,
            SimStats.DungeonClass.TANK};

    /** The class in a Mort menu slot, or null. */
    public static SimStats.DungeonClass classAt(int slot) {
        for (int i = 0; i < CLASS_SLOTS.length; i++) {
            if (CLASS_SLOTS[i] == slot) {
                return CLASS_ORDER[i];
            }
        }
        return null;
    }

    /** One player's class pick from Mort's menu, with Hypixel's lines. Server thread. */
    private static void pickClass(ServerPlayer sp, SimStats.DungeonClass c) {
        if (SimRun.hasStarted()) {
            sp.sendSystemMessage(Component.literal("You can't change your class once the dungeon has started!")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (SimParty.dungeonClass(sp) == c && SimPresets.appliedFor(sp) == c) {
            sp.sendSystemMessage(Component.literal("You already have the " + c.label + " class selected!")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        SimPresets.apply(sp, c);
        sp.sendSystemMessage(Component.literal("You selected the ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(c.label).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" class!").withStyle(ChatFormatting.GREEN)));
        redrawAll(sp.level().getServer());
    }

    private static ItemStack classItem(SimStats.DungeonClass c, boolean selected, int level) {
        ItemStack stack = switch (c) {
            case HEALER -> net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.SPLASH_POTION,
                    net.minecraft.world.item.alchemy.Potions.HEALING);
            case MAGE -> new ItemStack(Items.BLAZE_ROD);
            case BERSERK -> new ItemStack(Items.IRON_SWORD);
            case ARCHER -> new ItemStack(Items.BOW);
            case TANK -> new ItemStack(Items.LEATHER_CHESTPLATE);
        };
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(c.label)
                .withStyle(s -> s.withItalic(false).withColor(selected ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Class Level: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY))
                .append(Component.literal(String.valueOf(level)).withStyle(ChatFormatting.YELLOW)));
        lore.add(Component.empty());
        lore.add(Component.literal("Loads your " + c.label + " preset: armour, equipment,")
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
        lore.add(Component.literal("pets, Pet Rules and hotbar.")
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
        lore.add(Component.empty());
        lore.add(Component.literal(selected ? "SELECTED" : "Click to select!").withStyle(s -> s.withItalic(false)
                .withColor(selected ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        // The selected one glows, like Hypixel's.
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, selected);
        stack.set(DataComponents.TOOLTIP_DISPLAY, net.minecraft.world.item.component.TooltipDisplay.DEFAULT
                .withHidden(DataComponents.POTION_CONTENTS, true).withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
        return stack;
    }

    private static final class MortMenu extends ChestMenu {
        private final SimpleContainer items;
        private final UUID owner;

        MortMenu(int id, Inventory inv, UUID owner) {
            this(id, inv, owner, new SimpleContainer(ROWS * 9));
        }

        private MortMenu(int id, Inventory inv, UUID owner, SimpleContainer items) {
            super(MenuType.GENERIC_9x6, id, inv, items, ROWS);
            this.items = items;
            this.owner = owner;
            if (inv.player instanceof ServerPlayer sp) {
                redraw(sp.level().getServer());
            }
            OPEN.add(this);
        }

        void redraw(MinecraftServer server) {
            ItemStack filler = named(Items.STAINED_GLASS_PANE.pick(DyeColor.BLACK), Component.literal(" "),
                    List.of());
            for (int i = 0; i < ROWS * 9; i++) {
                items.setItem(i, filler.copy());
            }
            List<ServerPlayer> party = SimParty.players(server);
            int start = (HEAD_SLOTS.length - Math.min(party.size(), HEAD_SLOTS.length)) / 2;
            for (int i = 0; i < party.size() && start + i < HEAD_SLOTS.length; i++) {
                ServerPlayer p = party.get(i);
                boolean ready = READY.contains(p.getUUID());
                int slot = HEAD_SLOTS[start + i];
                items.setItem(slot, head(p, ready));
                items.setItem(slot + 9, named(Items.STAINED_GLASS_PANE.pick(ready ? DyeColor.LIME : DyeColor.RED),
                        Component.literal(ready ? "Ready" : "Not Ready")
                                .withStyle(ready ? ChatFormatting.GREEN : ChatFormatting.RED),
                        List.of()));
            }
            ServerPlayer self = server.getPlayerList().getPlayer(owner);
            if (self != null) {
                SimParty.Member member = SimParty.member(self);
                SimStats.DungeonClass picked = member.profile().selected();
                for (int i = 0; i < CLASS_SLOTS.length; i++) {
                    SimStats.DungeonClass c = CLASS_ORDER[i];
                    items.setItem(CLASS_SLOTS[i], classItem(c, c == picked, member.profile().classLevel(c)));
                }
            }
            boolean mine = READY.contains(owner);
            items.setItem(TOGGLE_SLOT, named(Items.DYED_TERRACOTTA.pick(mine ? DyeColor.LIME : DyeColor.RED),
                    Component.literal(mine ? "Ready" : "Not Ready").withStyle(mine ? ChatFormatting.GREEN
                            : ChatFormatting.RED),
                    List.of(Component.literal(mine ? "Click to unready!" : "Click to ready up!")
                            .withStyle(ChatFormatting.YELLOW))));
            items.setItem(CLOSE_SLOT, named(Items.BARRIER, Component.literal("Close").withStyle(ChatFormatting.RED),
                    List.of()));
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (player instanceof ServerPlayer sp && sp.getUUID().equals(owner)) {
                SimStats.DungeonClass picked = classAt(slot);
                if (slot == TOGGLE_SLOT) {
                    toggle(sp);
                } else if (picked != null) {
                    pickClass(sp, picked);
                } else if (slot == CLOSE_SLOT) {
                    sp.closeContainer();
                    return;
                }
            }
            // Never moves anything: put the client's predicted pickup back.
            sendAllDataToRemote();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            OPEN.remove(this);
        }
    }

    private static ItemStack head(ServerPlayer p, boolean ready) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(p.getGameProfile()));
        SimStats.DungeonClass c = SimParty.dungeonClass(p);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(p.getGameProfile().name())
                .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.AQUA)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Class: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY))
                .append(Component.literal(c.label + " " + SimParty.classLevel(p)).withStyle(ChatFormatting.YELLOW)));
        lore.add(Component.empty());
        lore.add(Component.literal(ready ? "Ready" : "Not Ready")
                .withStyle(s -> s.withItalic(false).withColor(ready ? ChatFormatting.GREEN : ChatFormatting.RED)));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    private static ItemStack named(net.minecraft.world.item.Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, name.copy().withStyle(s -> s.withItalic(false)));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (Component l : lore) {
                lines.add(l.copy().withStyle(s -> s.withItalic(false)));
            }
            stack.set(DataComponents.LORE, new ItemLore(lines));
        }
        return stack;
    }
}
