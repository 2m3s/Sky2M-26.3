/*
 * From Killer560's Mod (https://github.com/Killer560-OG/killer560s-mod), mobskin/MobSkinRender. MIT License,
 * Copyright (c) 2026 Killer560; see THIRD_PARTY_NOTICES.md.
 */
package com.epic60869.sky2m.features.misc.playasmob;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcCosmetics;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Play as Mob on screen: every player is drawn as the look they picked in /s2 cosmetics (or on the website), which the
 * Sky2M server sends with the cosmetics table - a mob, or a 3D model Sky2M staff gave them.
 * <p>
 * A mob: when {@code EntityRenderDispatcher} has extracted a player's render state ({@code Sky2MPlayAsMobDispatcherMixin}),
 * this extracts the mob's state from a private stand-in entity of that type and hands that to the level renderer
 * instead, with everything that makes it look like the player copied across: position, body and head turn, pitch, walk
 * animation, light, invisibility, hurt flash, fire, crouch and arm swing, nametag and shadow. A model keeps the player's
 * own state; {@code Sky2MPlayAsMobRendererMixin} swaps the player model for it inside the player renderer.
 * <p>
 * Render state only: stand-ins are never added to the level or ticked, and real players, their hitboxes and everything
 * other features read stay players, on this client alone. A mob whose renderer throws once is marked broken for the
 * session and draws the normal player, never a crash. Other players' looks follow Show Others' Looks, and are not shown
 * in dungeons (a teammate drawn as a zombie would be mistaken for an enemy) unless In Dungeons is on.
 */
public final class PlayAsMobRender {

    private static final Logger LOGGER = LoggerFactory.getLogger("sky2m-playasmob");
    private static final Set<String> BROKEN = ConcurrentHashMap.newKeySet();
    private static final int MAX_STAND_INS = 64;

    private record StandIn(EntityType<?> type, Level level, LivingEntity entity) {
    }

    /** A model to draw and how the website placed it. */
    public record Drawn(CustomModels.Model model, CustomModels.ServerModelPlacement placement) {
    }

    private static final Map<UUID, StandIn> STAND_INS = new HashMap<>();
    private static int nextStandInId = -1_000_000;

    /** Mob states made this frame, and whether each is yours (Scale Yourself) or another player's (Scale Others). */
    private static final Map<LivingEntityRenderState, Boolean> MOB_STATES = new WeakHashMap<>();

    private PlayAsMobRender() {
    }

    private static Sky2MConfig.PlayAsMob config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.playAsMob;
    }

    /** Whether {@code player}'s look is drawn at all right now. */
    private static boolean shows(AbstractClientPlayer player) {
        Sky2MConfig.PlayAsMob cfg = config();
        if (cfg == null) return false;
        if (player == Minecraft.getInstance().player) return cfg.showOwn;
        UUID uuid = player.getUUID();
        // Real players only: Hypixel's NPCs use version 2 UUIDs.
        return cfg.showOthers && uuid != null && uuid.version() == 4 && (cfg.inDungeons || !Sky2MLocation.inDungeon());
    }

    /**
     * Your mob: the one you picked, saved in your settings, so it shows even when the Sky2M server is offline or doesn't
     * know about looks yet. Otherwise the server's (a mob picked on the website).
     */
    private static String ownMob() {
        Sky2MConfig.PlayAsMob cfg = config();
        if (cfg != null && cfg.mob != null && !cfg.mob.isEmpty()) return cfg.mob;
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? "" : SbcCosmetics.mobFor(mc.player.getUUID());
    }

    /** The model drawn in place of {@code state}'s player model, once downloaded and loaded, or null. */
    public static Drawn modelFor(Object state) {
        if (!(state instanceof AvatarRenderState avatar)) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Entity entity = mc.level.getEntity(avatar.id);
        if (!(entity instanceof AbstractClientPlayer player) || !shows(player)) return null;
        return modelForPlayer(player);
    }

    /** The downloaded model selected by a player, for the first-person hand renderer. */
    public static Drawn modelForPlayer(AbstractClientPlayer player) {
        if (player == null || !shows(player)) return null;
        // A model bundled with the mod ("builtin:generic_man"), picked in /s2 cosmetics and kept in your settings.
        String bundled = player == Minecraft.getInstance().player ? ownMob() : SbcCosmetics.mobFor(player.getUUID());
        if (bundled != null && bundled.startsWith(BuiltinModels.PREFIX)) return BuiltinModels.drawn(bundled);
        SbcCosmetics.ModelRef ref = SbcCosmetics.modelFor(player.getUUID());
        if (ref == null) return null;
        Path file = ServerModels.file(ref);
        if (file == null) return null;
        CustomModels.Model model = CustomModels.get(file);
        return model == null ? null : new Drawn(model, new CustomModels.ServerModelPlacement(ref.scale(), ref.turn(), ref.fit()));
    }

    /** Replace the vanilla first-person arm with the selected textured model arm, preserving the hand pose and item renderer. */
    public static boolean renderFirstPersonArm(HumanoidArm arm, PoseStack pose, SubmitNodeCollector collector, int light) {
        Drawn drawn = modelForPlayer(Minecraft.getInstance().player);
        return drawn != null && CustomModels.drawFirstPersonArm(drawn.model(), arm == HumanoidArm.LEFT, pose, collector, light);
    }

    /** Where the fist is in the held item's frame (camera aligned, blocks, for the right hand; x mirrors for the left). */
    private static final float HELD_HAND_X = 0.06f, HELD_HAND_Y = -0.10f, HELD_HAND_Z = -0.12f;
    /** The arm's direction from the shoulder to the fist in that frame: forward, up and toward the middle. */
    private static final float HELD_ARM_X = -0.35f, HELD_ARM_Y = 0.45f, HELD_ARM_Z = -0.82f;
    /** The fist in the arm's own pixels (x for the right arm; the left mirrors it), and a twist about the arm, in degrees. */
    private static final float FIST_X = 6f, FIST_Y = 10f, FIST_Z = 0.5f, HELD_ARM_ROLL = 0f;

    /**
     * The selected model's arm reaching in from the corner of the screen to the fist holding the item, drawn just before
     * vanilla draws the item in the same pose, so it follows the equip, swing, eat and use transforms of the item.
     * Vanilla shows no arm while an item is held; with a custom model the hand is part of the look.
     */
    public static void renderFirstPersonHeldArm(AbstractClientPlayer player, HumanoidArm arm, PoseStack pose,
                                                SubmitNodeCollector collector, int light) {
        if (player == null || player != Minecraft.getInstance().player || player.isInvisible()) return;
        Drawn drawn = modelForPlayer(player);
        if (drawn == null) return;
        boolean left = arm == HumanoidArm.LEFT;
        float side = left ? -1f : 1f;
        pose.pushPose();
        try {
            org.joml.Vector3f along = new org.joml.Vector3f(HELD_ARM_X * side, HELD_ARM_Y, HELD_ARM_Z).normalize();
            pose.translate(HELD_HAND_X * side, HELD_HAND_Y, HELD_HAND_Z);
            // The arm hangs along +y in its own space, shoulder first: turn that to run shoulder to fist.
            pose.rotate(new org.joml.Quaternionf().rotationTo(0f, 1f, 0f, along.x, along.y, along.z));
            pose.rotate(com.mojang.math.Axis.YP.rotationDegrees(HELD_ARM_ROLL * side));
            // Move the arm's own fist (x -6 px for the right arm, +6 for the left) to the origin.
            pose.translate(FIST_X * side / 16f, -FIST_Y / 16f, -FIST_Z / 16f);
            CustomModels.drawFirstPersonArm(drawn.model(), left, pose, collector, light);
        } catch (Throwable t) {
            // The item still draws; only the arm is skipped.
        } finally {
            pose.popPose();
        }
    }

    /** {@code playerState} as the level renderer should draw it: unchanged unless that player plays as a mob. */
    public static EntityRenderState swap(EntityRenderDispatcher dispatcher, Entity entity, EntityRenderState playerState,
                                         float partialTick) {
        if (!(playerState instanceof LivingEntityRenderState ps) || !(entity instanceof AbstractClientPlayer player)) {
            return playerState;
        }
        String id = null;
        try {
            if (!shows(player)) return playerState;
            id = PlayAsMobs.sanitize(player == Minecraft.getInstance().player ? ownMob() : SbcCosmetics.mobFor(player.getUUID()));
            if (id == null || BROKEN.contains(id)) return playerState;
            LivingEntity mob = standInFor(player.getUUID(), id, entity.level());
            if (mob == null) return playerState;
            EntityRenderer<? super LivingEntity, ?> renderer = dispatcher.getRenderer(mob);
            EntityRenderState made = renderer.createRenderState(mob, partialTick);
            if (!(made instanceof LivingEntityRenderState ms)) {
                markBroken(id, "its render state is not a living one");
                return playerState;
            }
            dress(ps, ms);
            MOB_STATES.put(ms, player == Minecraft.getInstance().player);
            return ms;
        } catch (Throwable t) {
            if (id != null) markBroken(id, t.toString());
            return playerState;
        }
    }

    private static void markBroken(String id, String why) {
        if (BROKEN.add(id)) LOGGER.error("[Play as Mob] {} cannot be drawn, showing the player instead: {}", id, why);
    }

    /** One stand-in per player, remade when their mob or the level changes. */
    private static LivingEntity standInFor(UUID owner, String id, Level level) {
        EntityType<?> type = PlayAsMobs.type(id);
        if (type == null || level == null) return null;
        StandIn s = STAND_INS.get(owner);
        if (s != null && s.type() == type && s.level() == level) return s.entity();
        if (STAND_INS.size() >= MAX_STAND_INS) STAND_INS.clear();
        Entity made = type.create(level, EntitySpawnReason.LOAD);
        if (!(made instanceof LivingEntity living)) {
            markBroken(id, "not a living entity");
            return null;
        }
        // getId() throws on an entity never given an id, and every renderer reads it. Negative ids never meet a
        // server's, and the stand-in is never in the level to be looked up by it.
        living.setId(nextStandInId--);
        STAND_INS.put(owner, new StandIn(type, level, living));
        return living;
    }

    /** Copies onto the mob's state everything that makes it move and look like the player. */
    private static void dress(LivingEntityRenderState ps, LivingEntityRenderState ms) {
        ms.x = ps.x;
        ms.y = ps.y;
        ms.z = ps.z;
        ms.ageInTicks = ps.ageInTicks;
        ms.distanceToCameraSq = ps.distanceToCameraSq;
        ms.isInvisible = ps.isInvisible;
        ms.isDiscrete = ps.isDiscrete;
        ms.displayFireAnimation = ps.displayFireAnimation;
        ms.lightCoords = ps.lightCoords;
        ms.outlineColor = ps.outlineColor;
        ms.nameTag = ps.nameTag;
        ms.scoreText = ps.scoreText;
        ms.shadowRadius = ps.shadowRadius;
        ms.shadowPieces.clear();
        ms.shadowPieces.addAll(ps.shadowPieces);

        ms.bodyRot = ps.bodyRot;
        ms.yRot = ps.yRot;
        ms.xRot = ps.xRot;
        ms.deathTime = ps.deathTime;
        ms.walkAnimationPos = ps.walkAnimationPos;
        ms.walkAnimationSpeed = ps.walkAnimationSpeed;
        ms.wornHeadAnimationPos = ps.wornHeadAnimationPos;
        ms.scale = ps.scale;
        ms.ticksSinceKineticHitFeedback = ps.ticksSinceKineticHitFeedback;
        ms.isUpsideDown = ps.isUpsideDown;
        ms.isFullyFrozen = ps.isFullyFrozen;
        ms.isInWater = ps.isInWater;
        ms.isAutoSpinAttack = ps.isAutoSpinAttack;
        ms.hasRedOverlay = ps.hasRedOverlay;
        ms.isInvisibleToPlayer = ps.isInvisibleToPlayer;
        ms.bedOrientation = ps.bedOrientation;
        ms.pose = ps.pose;
        ms.nameTagAttachment = ms.nameTagAttachment != null ? ms.nameTagAttachment : ps.nameTagAttachment;

        if (ms instanceof HumanoidRenderState mh && ps instanceof HumanoidRenderState ph) {
            mh.isCrouching = ph.isCrouching;
            mh.swimAmount = ph.swimAmount;
            mh.isVisuallySwimming = ph.isVisuallySwimming;
            mh.isFallFlying = ph.isFallFlying;
            mh.isPassenger = ph.isPassenger;
        }
        if (ms instanceof ArmedEntityRenderState ma && ps instanceof ArmedEntityRenderState pa) {
            ma.currentSwing = pa.currentSwing;
            ma.swingAnimation = pa.swingAnimation;
            ma.mainArm = pa.mainArm;
        }
    }

    /**
     * Player Size on a mob, applied by {@code Sky2MPlayAsMobRendererMixin} right after a living renderer's own
     * {@code scale} - the same point Sky2MPlayerSizeMixin stretches the player model at: Scale Yourself on yours,
     * Scale Others on other players'.
     */
    public static void applySize(LivingEntityRenderState state, PoseStack pose) {
        Boolean own = MOB_STATES.get(state);
        if (own == null) return;
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.playAsMob.useSize) return;
        Sky2MConfig.PlayerSize size = config.misc.playerSize;
        float x, y, z;
        if (own) {
            if (!size.self) return;
            x = size.selfX;
            y = size.selfY;
            z = size.selfZ;
        } else {
            if (!size.others) return;
            x = size.othersX;
            y = size.othersY;
            z = size.othersZ;
        }
        if (y < 0) pose.translate(0f, y * 2, 0f);
        pose.scale(x, y, z);
    }
}
