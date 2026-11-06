package com.epic60869.sky2m.sim.roomsim;

import com.epic60869.sky2m.sim.util.SimItemIds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spirit Leap and Infinileap in a sim party: a right click opens Hypixel's "Spirit Leap" menu - a head for every
 * teammate with his name and class - and a click teleports you to him with Hypixel's "You have teleported to
 * <name>!" line. Server-side, so a party guest gets the same menu, and the mod's own Leap Menu feature
 * ({@code features/dungeons/LeapMenu}) replaces it exactly as it does on Hypixel: same title, heads named with the
 * player's IGN last, and the teammates' classes read from the sim's tab list ({@link SimTabList}). A Spirit Leap is
 * used up per leap; an Infinileap never is.
 */
public final class SimLeap {

    public static final String SPIRIT_LEAP_ID = "SPIRIT_LEAP";
    public static final String INFINILEAP_ID = "INFINITE_SPIRIT_LEAP";

    /** Hypixel's menu: three rows, the heads along the middle one. */
    private static final int[] HEAD_SLOTS = {11, 12, 13, 14, 15, 10, 16};

    private SimLeap() {
    }

    /**
     * The cooldown between leaps: Spirit Leap 5 s, InfiniLeap 2 s (wiki, research/misc-talismans.md). After a leap you
     * are immune to damage for 1 s, until you deal damage yourself ({@link SimItemEffects#leapImmunity}).
     */
    private static final int SPIRIT_LEAP_COOLDOWN_TICKS = 5 * 20;
    private static final int INFINILEAP_COOLDOWN_TICKS = 2 * 20;
    private static final String COOLDOWN_KEY = "Spirit Leap";

    /** The right click: opens the menu, or says there is nobody to leap to. Server thread. */
    static boolean open(ServerPlayer sp, boolean infinite) {
        if (SimDeath.isGhost(sp)) {
            SimDeath.sayDead(sp);
            return false;
        }
        int left = SimVitals.cooldownSeconds(sp, COOLDOWN_KEY);
        if (left > 0) {
            sp.sendSystemMessage(Component.literal("§cThis ability is on cooldown for " + left + "s."));
            return false;
        }
        List<ServerPlayer> mates = new ArrayList<>();
        for (ServerPlayer p : SimParty.players(sp.level().getServer())) {
            if (p != sp && p.level() == sp.level()) {
                mates.add(p);
            }
        }
        if (mates.isEmpty()) {
            sp.sendSystemMessage(Component.literal("There are no teammates to leap to!").withStyle(ChatFormatting.RED));
            return false;
        }
        SimpleContainer heads = new SimpleContainer(27);
        UUID[] targets = new UUID[27];
        for (int i = 0; i < mates.size() && i < HEAD_SLOTS.length; i++) {
            ServerPlayer m = mates.get(i);
            heads.setItem(HEAD_SLOTS[i], head(m));
            targets[HEAD_SLOTS[i]] = m.getUUID();
        }
        UUID self = sp.getUUID();
        sp.openMenu(new SimpleMenuProvider((id, inv, player) -> new LeapMenu(id, inv, heads, targets, infinite, self),
                Component.literal("Spirit Leap")));
        return true;
    }

    private static ItemStack head(ServerPlayer m) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(m.getGameProfile()));
        boolean dead = SimDeath.isGhost(m);
        SimStats.DungeonClass c = SimParty.dungeonClass(m);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(m.getGameProfile().name())
                .withStyle(s -> s.withItalic(false).withColor(dead ? ChatFormatting.RED : ChatFormatting.AQUA)));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.literal("Class: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY))
                .append(Component.literal(dead ? "DEAD" : c.label).withStyle(dead ? ChatFormatting.RED
                        : ChatFormatting.YELLOW)));
        lore.add(Component.empty());
        lore.add(Component.literal(dead ? "This player is dead!" : "Click to teleport!")
                .withStyle(s -> s.withItalic(false).withColor(dead ? ChatFormatting.RED : ChatFormatting.YELLOW)));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /** The menu: nothing in it can be taken; a click on a head is the leap. */
    private static final class LeapMenu extends ChestMenu {
        private final UUID[] targets;
        private final boolean infinite;
        private final UUID owner;

        LeapMenu(int id, Inventory inv, SimpleContainer heads, UUID[] targets, boolean infinite, UUID owner) {
            super(MenuType.GENERIC_9x3, id, inv, heads, 3);
            this.targets = targets;
            this.infinite = infinite;
            this.owner = owner;
        }

        @Override
        public void clicked(int slot, int button, ContainerInput input, Player player) {
            if (player instanceof ServerPlayer sp && sp.getUUID().equals(owner) && slot >= 0 && slot < targets.length
                    && targets[slot] != null) {
                ServerPlayer target = sp.level().getServer().getPlayerList().getPlayer(targets[slot]);
                leap(sp, target, infinite);
            }
            // Never moves anything: put the client's predicted pickup back.
            sendAllDataToRemote();
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }
    }

    private static void leap(ServerPlayer sp, ServerPlayer target, boolean infinite) {
        if (target == null || target.hasDisconnected() || target.level() != sp.level()) {
            sp.sendSystemMessage(Component.literal("That player is no longer here!").withStyle(ChatFormatting.RED));
            sp.closeContainer();
            return;
        }
        if (SimDeath.isGhost(target)) {
            sp.sendSystemMessage(Component.literal("This player is dead, you can not leap to them.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!infinite && !useOne(sp)) {
            sp.closeContainer();
            return;
        }
        sp.closeContainer();
        // The cooldown starts with the leap (the shared key: both items share Hypixel's one leap cooldown).
        // Set directly, not through SimVitals.cast, so a Mage's Efficient Spells does not shorten it.
        SimVitals.of(sp).cooldownUntil.put(COOLDOWN_KEY, SimVitals.now()
                + (infinite ? INFINILEAP_COOLDOWN_TICKS : SPIRIT_LEAP_COOLDOWN_TICKS));
        ServerLevel level = (ServerLevel) target.level();
        sp.teleportTo(level, target.getX(), target.getY(), target.getZ(), java.util.Set.of(), sp.getYRot(),
                sp.getXRot(), false);
        sp.fallDistance = 0;
        SimItemEffects.leapImmunity(sp);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1f, 1f);
        // Hypixel's line, which the Leap Menu's announce ("Leaped to <name>!") and the party features read.
        sp.sendSystemMessage(Component.literal("You have teleported to ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal(target.getGameProfile().name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("!").withStyle(ChatFormatting.LIGHT_PURPLE)));
    }

    /** Uses up one Spirit Leap from either hand (the one the menu was opened with). */
    private static boolean useOne(ServerPlayer sp) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = sp.getItemInHand(hand);
            if (SPIRIT_LEAP_ID.equals(SimItemIds.skyblockId(held))) {
                held.shrink(1);
                return true;
            }
        }
        sp.sendSystemMessage(Component.literal("You need a Spirit Leap in your hand!").withStyle(ChatFormatting.RED));
        return false;
    }
}
