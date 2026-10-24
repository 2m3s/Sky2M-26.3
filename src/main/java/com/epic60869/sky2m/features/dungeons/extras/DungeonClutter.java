package com.epic60869.sky2m.features.dungeons.extras;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Dungeon Object Hider (DungeonHideItems) and Clean End (DungeonCleanEnd), from
 * https://github.com/hannibal002/SkyHanni (LGPL-2.1). Entities are matched by name or, for the heads Hypixel draws orbs,
 * fairies, blessings and skulls with, by the skull textures in SkyHanni's repo (Skulls.json). They are skipped when
 * drawn (Sky2MHideFireballsMixin), so nothing about the entities themselves changes. Particles are not hidden.
 */
final class DungeonClutter {
    private static final Pattern ORB_NAME = Pattern.compile("^(?:DAMAGE|ABILITY DAMAGE|DEFENSE) \\d+s?$");
    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"[^\"]*/([0-9a-fA-F]+)\"");
    private static final Pattern CHESTS_SPAWNED = Pattern.compile("^\\s*(?:Master Mode )?The Catacombs - Floor .*$");
    /** The last boss line of each floor (Odin's splits): its death. F4 and F5 have none, the chests line ends those. */
    private static final Pattern BOSS_DEAD = Pattern.compile("^\\[BOSS] (?:Bonzo: Oh I'm dead!"
        + "|Scarf: Did you forget\\? I was taught by the best! Let's dance\\."
        + "|The Professor: What\\?! My Guardian power is unbeatable!"
        + "|Sadan: You did it\\. I understand now, you have earned my respect\\."
        + "|Necron: All this, for nothing\\.\\.\\."
        + "|(?:The )?Wither King: You\\.\\.\\. again\\?)$");

    private static final Map<String, String> SKULLS = new HashMap<>();
    /** Skeleton skull stand id -> position and when it last moved. */
    private static final Map<Integer, Vec3> LAST_POS = new HashMap<>();
    private static final Map<Integer, Long> LAST_MOVE = new HashMap<>();
    private static boolean bossDone, chestsSpawned;

    private DungeonClutter() {}

    static void init() {
        SkyHanniRepo.load("Skulls", json -> {
            Map<String, String> next = new HashMap<>();
            for (String key : json.keySet()) {
                if (json.get(key).isJsonPrimitive()) next.put(key, hash(json.get(key).getAsString()));
            }
            synchronized (SKULLS) {
                SKULLS.clear();
                SKULLS.putAll(next);
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        Sky2MLocation.onAreaChange(area -> reset());
        Sky2MChat.onChat(message -> {
            DungeonExtras.Config c = DungeonExtras.config();
            if (c == null || !c.cleanEnd.enabled || !Sky2MLocation.inDungeon()) return;
            String text = message.text();
            if (CHESTS_SPAWNED.matcher(text).matches()) {
                bossDone = true;
                chestsSpawned = true;
            } else if (BOSS_DEAD.matcher(text.trim()).matches()) {
                bossDone = true;
            }
        });
    }

    private static void reset() {
        bossDone = chestsSpawned = false;
        LAST_POS.clear();
        LAST_MOVE.clear();
    }

    /** The texture URL's hash of a base64 skin value (the part that is the same however Hypixel formats the JSON). */
    private static String hash(String base64) {
        if (base64 == null || base64.isEmpty()) return "";
        try {
            String json = new String(java.util.Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = URL.matcher(json);
            return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : base64;
        } catch (IllegalArgumentException e) {
            return base64;
        }
    }

    private static boolean isSkull(ItemStack stack, String key) {
        if (stack.isEmpty() || !stack.is(Items.PLAYER_HEAD)) return false;
        String wanted;
        synchronized (SKULLS) {
            wanted = SKULLS.get(key);
        }
        return wanted != null && !wanted.isEmpty() && wanted.equals(hash(Compat.getHeadTexture(stack)));
    }

    /** Whether this entity isn't drawn. Called for every entity every frame, so the cheap checks go first. */
    private static final java.util.Map<Integer, Boolean> HIDE_CACHE = new java.util.HashMap<>();
    private static long hideCacheTick = Long.MIN_VALUE;

    /** Asked for every entity every frame; the answer (name and head lookups) is worked out once per game tick. */
    static boolean hide(Entity entity) {
        DungeonExtras.Config c = DungeonExtras.config();
        if (c == null || !Sky2MLocation.inDungeon()) return false;
        if (!(entity instanceof ItemEntity || entity instanceof ArmorStand || c.cleanEnd.enabled)) return false;
        long tick = entity.level().getGameTime();
        if (tick != hideCacheTick) {
            HIDE_CACHE.clear();
            hideCacheTick = tick;
        }
        Boolean cached = HIDE_CACHE.get(entity.getId());
        if (cached == null) {
            cached = hideUncached(entity, c);
            HIDE_CACHE.put(entity.getId(), cached);
        }
        return cached;
    }

    private static boolean hideUncached(Entity entity, DungeonExtras.Config c) {
        if (c.cleanEnd.enabled && bossDone && hideForCleanEnd(entity, c.cleanEnd)) return true;
        DungeonExtras.Clutter k = c.clutter;
        if (entity instanceof ItemEntity item) {
            if (!k.reviveStones && !k.journalEntries) return false;
            String name = ChatFormatting.stripFormatting(item.getItem().getHoverName().getString());
            return k.reviveStones && name.equals("Revive Stone") || k.journalEntries && name.equals("Journal Entry");
        }
        if (!(entity instanceof ArmorStand stand)) return false;
        if (!(k.healerOrbs || k.healerFairy || k.blessings || k.superboom || k.reviveStones || k.premiumFlesh || k.skeletonSkulls || k.soulweaverSkulls)) return false;

        if (stand.hasCustomName()) {
            String name = ChatFormatting.stripFormatting(stand.getCustomName().getString());
            if (k.superboom && name.startsWith("Superboom TNT")) return true;
            if (k.blessings && name.startsWith("Blessing of ")) return true;
            if (k.reviveStones && name.equals("Revive Stone")) return true;
            if (k.premiumFlesh && name.equals("Premium Flesh")) return true;
            if (k.healerOrbs && ORB_NAME.matcher(name).matches()) return true;
        }
        ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
        if (head.isEmpty()) {
            return k.healerFairy && isSkull(stand.getItemBySlot(EquipmentSlot.MAINHAND), "DUNGEONS_HEALER_FAIRY");
        }
        if (k.skeletonSkulls && head.is(Items.SKELETON_SKULL)) return !isMoving(stand);
        if (!head.is(Items.PLAYER_HEAD)) return false;
        String headName = ChatFormatting.stripFormatting(head.getHoverName().getString());
        if (k.superboom && headName.equals("Superboom TNT")) return true;
        return k.blessings && isSkull(head, "DUNGEONS_BLESSING")
            || k.reviveStones && isSkull(head, "DUNGEONS_REVIVE_STONE")
            || k.premiumFlesh && isSkull(head, "DUNGEONS_PREMIUM_FLESH")
            || k.healerOrbs && (isSkull(head, "DUNGEONS_ABILITY_ORB") || isSkull(head, "DUNGEONS_SUPPORT_ORB") || isSkull(head, "DUNGEONS_DAMAGE_ORB"))
            || k.soulweaverSkulls && isSkull(head, "DUNGEONS_SOUL_WEAVER")
            || k.healerFairy && isSkull(stand.getItemBySlot(EquipmentSlot.MAINHAND), "DUNGEONS_HEALER_FAIRY");
    }

    /** SkyHanni draws a skeleton skull while it moves (the ones being thrown), so it stays visible and in your way. */
    private static boolean isMoving(ArmorStand stand) {
        long now = System.currentTimeMillis();
        Vec3 pos = stand.position();
        Vec3 last = LAST_POS.put(stand.getId(), pos);
        if (last != null && last.distanceToSqr(pos) > 1e-4) LAST_MOVE.put(stand.getId(), now);
        return now - LAST_MOVE.getOrDefault(stand.getId(), 0L) < 100;
    }

    private static boolean hideForCleanEnd(Entity entity, DungeonExtras.CleanEnd c) {
        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player) return false;
        if (c.f3Guardians && entity instanceof Guardian && mc.player != null && mc.player.isCrouching()
            && Sky2MLocation.dungeonFloor().endsWith("3")) return false;
        if (chestsSpawned && (entity instanceof ArmorStand && !entity.hasCustomName() || entity instanceof RemotePlayer)) return false;
        return true;
    }

    /** Mutes the boss music (note block sounds) between the boss's death and the chests. */
    static boolean cancelSound(String name) {
        DungeonExtras.Config c = DungeonExtras.config();
        return c != null && c.cleanEnd.enabled && bossDone && !chestsSpawned && name.startsWith("block.note_block.") && Sky2MLocation.inDungeon();
    }
}
