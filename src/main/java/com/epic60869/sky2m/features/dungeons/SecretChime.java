package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.List;
import java.util.Set;

/**
 * Dungeons > Secrets > Secret Chime and Secret Boxes, ported from Odin's Secret Clicked
 * (https://github.com/odtheking/Odin, features/impl/dungeon/SecretClicked.kt and events/EventDispatcher.kt,
 * BSD-3-Clause): a sound and a highlight box when you get a secret. That's clicking a chest, lever, wither essence or
 * redstone key, picking up a dungeon item (or one vanishing within 6 blocks of you), or a secret bat dying. A box turns
 * red when the chest was locked.
 */
public final class SecretChime {
    private static final String WITHER_ESSENCE = "2865274b-3097-394e-8149-ec629c72d850";
    private static final String REDSTONE_KEY = "fed95410-aba1-39df-9b95-1d4f361eb66e";
    private static final List<String> ITEMS = List.of("Health Potion VIII Splash Potion", "Healing Potion 8 Splash Potion",
        "Healing Potion VIII Splash Potion", "Healing VIII Splash Potion", "Healing 8 Splash Potion", "Decoy",
        "Inflatable Jerry", "Spirit Leap", "Trap", "Training Weights", "Defuse Kit", "Dungeon Chest Key",
        "Treasure Talisman", "Revive Stone", "Architect's First Draft", "Secret Dye", "Candycomb");
    private static final Set<Object> BATS = Set.of(SoundEvents.BAT_HURT, SoundEvents.BAT_DEATH);
    private static long lastPlayed;

    /** A secret's box, until when it shows, and whether its chest turned out to be locked. */
    private static final class Box {
        final AABB box;
        final long until;
        boolean locked;

        Box(AABB box, long until) {
            this.box = box;
            this.until = until;
        }
    }

    private static final List<Box> BOXES = new CopyOnWriteArrayList<>();
    private static Object lastLevel;

    private SecretChime() {}

    private static FeatureConfigs.Secrets config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.secrets;
    }

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) onInteract(hit.getBlockPos());
            return InteractionResult.PASS;
        });
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity item) onItemGone(item);
        });
        Sky2MChat.onChat(message -> {
            if (message.text().equals("That chest is locked!") && !BOXES.isEmpty()) BOXES.getLast().locked = true;
        });
        Sky2MWorldRender.register(SecretChime::render);
    }

    private static boolean boxing(FeatureConfigs.Secrets c) {
        return c != null && c.secretBoxes && Sky2MLocation.inDungeon() && (c.secretBoxInBoss || !DungeonManager.isInBoss());
    }

    private static void box(AABB box) {
        FeatureConfigs.Secrets c = config();
        if (!boxing(c)) return;
        for (Box existing : BOXES) if (existing.box.intersects(box)) return;
        BOXES.add(new Box(box, System.currentTimeMillis() + Math.round(c.secretBoxSeconds * 1000)));
    }

    private static void render(PrimitiveCollector collector) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            BOXES.clear();
        }
        long now = System.currentTimeMillis();
        BOXES.removeIf(b -> b.until < now);
        FeatureConfigs.Secrets c = config();
        if (BOXES.isEmpty() || !boxing(c)) return;
        float[] normal = colour(c.secretBoxColour, new float[]{1f, 0.67f, 0f, 0.8f});
        float[] locked = colour(c.secretBoxLockedColour, new float[]{1f, 0.33f, 0.33f, 0.8f});
        for (Box b : BOXES) {
            float[] colour = b.locked ? locked : normal;
            float[] rgb = {colour[0], colour[1], colour[2]};
            // Odin's Filled Outline style.
            collector.submitFilledBox(b.box, rgb, colour[3] * 0.5f, c.secretBoxThroughWalls);
            collector.submitOutlinedBox(b.box, rgb, colour[3], 2f, c.secretBoxThroughWalls);
        }
    }

    /** RGBA 0-1 from a MoulConfig colour string. */
    private static float[] colour(String value, float[] fallback) {
        try {
            int argb = com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB();
            return new float[]{((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f};
        } catch (Exception e) {
            return fallback;
        }
    }

    /** The block's own shape (a lever or a skull is smaller than a block). */
    private static AABB blockBox(BlockState state, BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        var shape = state.getShape(mc.level, pos);
        return shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
    }

    /** In a dungeon's rooms (or the boss too, with Chime In Boss). */
    private static boolean active(FeatureConfigs.Secrets c) {
        return c != null && c.secretChime && Sky2MLocation.inDungeon() && (c.secretChimeInBoss || !DungeonManager.isInBoss());
    }

    private static void onInteract(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        FeatureConfigs.Secrets c = config();
        if (!active(c) && !boxing(c) || mc.level == null || mc.player == null) return;
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST) || state.is(Blocks.LEVER)) {
            box(blockBox(state, pos));
            if (active(c)) chime();
        } else if (state.getBlock() instanceof SkullBlock && mc.player.getEyePosition().distanceToSqr(Vec3.atLowerCornerOf(pos)) <= 23
            && mc.level.getBlockEntity(pos) instanceof SkullBlockEntity skull && skull.getOwnerProfile() != null) {
            var id = skull.getOwnerProfile().partialProfile().id();
            if (id != null && (id.toString().equals(WITHER_ESSENCE) || id.toString().equals(REDSTONE_KEY))) {
                box(blockBox(state, pos));
                if (active(c)) chime();
            }
        }
    }

    /** You picked up an item (ClientPacketListenerMixin). */
    public static void onItemPickup(ItemEntity item) {
        onItemGone(item);
    }

    private static void onItemGone(ItemEntity item) {
        Minecraft mc = Minecraft.getInstance();
        FeatureConfigs.Secrets c = config();
        if (c == null || !Sky2MLocation.inDungeon() || DungeonManager.isInBoss() || mc.player == null || item.distanceTo(mc.player) > 6) return;
        String name = item.getItem().getHoverName().getString().toLowerCase();
        for (String secret : ITEMS) {
            if (name.contains(secret.toLowerCase())) {
                if (c.secretBoxItems) box(new AABB(item.blockPosition()));
                if (active(c)) chime();
                return;
            }
        }
    }

    /** A sound from the server (ClientPacketListenerMixin): a secret bat getting hit or dying is quiet (0.1). */
    public static void onSound(SoundEvent sound, double x, double y, double z, float volume) {
        if (volume != 0.1f || !BATS.contains(sound) || !Sky2MLocation.inDungeon() || DungeonManager.isInBoss()) return;
        box(new AABB(BlockPos.containing(x, y, z)));
        if (active(config())) chime();
    }

    private static void chime() {
        // A pickup can be seen twice (the pickup packet and the item leaving).
        if (System.currentTimeMillis() - lastPlayed <= 10) return;
        lastPlayed = System.currentTimeMillis();
        play();
    }

    /** Plays the chime as set (also the settings' Test button). */
    public static void play() {
        FeatureConfigs.Secrets c = config();
        if (c == null) return;
        Identifier id = Identifier.tryParse(c.secretChimeSound == null || c.secretChimeSound.isBlank() ? "entity.blaze.hurt" : c.secretChimeSound.trim());
        if (id == null) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), c.secretChimePitch, c.secretChimeVolume)));
    }
}
