package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonScore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bats you kill with a Wither blade: Hypixel's "A Bat has been slain" line doesn't arrive for these, so the Bat Message
 * and the score never heard about them. A bat dying close to you just after you used a blade counts as yours, unless
 * Hypixel's line or a teammate's message turns up for it within a second (then that line has already counted it).
 */
public final class WitherBladeBats {
    private static final List<String> WITHER_BLADES = List.of("Hyperion", "Astraea", "Scylla", "Valkyrie");
    /** Wither Impact's teleport plus its blast. */
    private static final double RANGE = 12;
    /** Long enough for the bat's removal, about a second after it dies, if its death event never came. */
    private static final long USE_WINDOW_MS = 2500;
    private static final long LINE_WAIT_MS = 1000;
    /** More bats than this gone within {@link #LINE_WAIT_MS} wasn't you: 26 near the entrance at the start of a run on 2026-11-06. */
    private static final int MAX_KILLS_AT_ONCE = 3;
    private static final long MASS_REMOVAL_IGNORE_MS = 5000;

    private static long lastBladeUse;
    private static long ignoreUntil;
    /** Bats already looked at: a dying bat is seen at its death and again when it's removed. */
    private static final Set<Integer> SEEN = new HashSet<>();
    /** When each bat waiting on a line died. */
    private static final List<Long> PENDING = new ArrayList<>();

    private WitherBladeBats() {}

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            SEEN.clear();
            PENDING.clear();
            lastBladeUse = 0;
            ignoreUntil = 0;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && Sky2MLocation.inDungeon()) {
                String held = Sky2MLocation.strip(Compat.realName(player.getItemInHand(hand)).getString());
                if (WITHER_BLADES.stream().anyMatch(held::contains)) lastBladeUse = System.currentTimeMillis();
            }
            return InteractionResult.PASS;
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity.isRemoved() && Minecraft.getInstance().level == level) onBatGone(entity);
        });
        // Hypixel's line, or a teammate's message, has counted this bat already.
        Sky2MChat.onChat(message -> {
            if (!PENDING.isEmpty() && ScoreCalculator.isBatKill(Sky2MLocation.strip(message.text()).trim(), Minecraft.getInstance().getUser().getName())) {
                PENDING.removeFirst();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            long now = System.currentTimeMillis();
            while (!PENDING.isEmpty() && now - PENDING.getFirst() >= LINE_WAIT_MS) {
                PENDING.removeFirst();
                ScoreCalculator.countBat();
                DungeonScore.onBatKill(true);
            }
        });
    }

    /** A bat died (ClientPacketListenerMixin) or was removed. */
    public static void onBatGone(Entity entity) {
        if (!(entity instanceof Bat) || !Sky2MLocation.inDungeon() || com.epic60869.sky2m.sim.roomsim.SimState.isActive()) return;
        var player = Minecraft.getInstance().player;
        if (player == null || !SEEN.add(entity.getId())) return;
        long now = System.currentTimeMillis();
        if (now < ignoreUntil || !yourKill(player.distanceToSqr(entity), now - lastBladeUse)) return;
        PENDING.add(now);
        if (tooManyAtOnce(PENDING.size())) {
            PENDING.clear();
            ignoreUntil = now + MASS_REMOVAL_IGNORE_MS;
        }
    }

    /** Whether this many bats waiting on a line is too many to be your kills. Public for the game test. */
    public static boolean tooManyAtOnce(int waiting) {
        return waiting > MAX_KILLS_AT_ONCE;
    }

    /** Whether a bat this far away (squared), this long after you last used a blade, counts as yours. Public for the game test. */
    public static boolean yourKill(double distanceSqr, long sinceBladeUse) {
        return distanceSqr <= RANGE * RANGE && sinceBladeUse >= 0 && sinceBladeUse <= USE_WINDOW_MS;
    }
}
