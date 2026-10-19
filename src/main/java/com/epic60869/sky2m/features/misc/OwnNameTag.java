package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.combat.CombatFeatures;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.profileviewer.Networth;
import com.epic60869.sky2m.features.profileviewer.ProfileApi;
import com.epic60869.sky2m.features.profileviewer.ProfileStats;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import com.epic60869.sky2m.features.sbc.SbcNameTags;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Misc > Visual > Own Name Tag: your own name tag above your head in third person (vanilla hides it), with up to two
 * lines above it: your networth and skill average ("NW 37.47B   Skill Avg 52.4"), and above that your standing on the
 * leaderboard you picked by right-clicking it in /s2 leaderboard ("Wheat 12,345,678 #4": gold, silver or bronze for
 * the top 3, aqua otherwise). The stats come from your selected profile, loaded like the profile viewer in the
 * background at most every 10 minutes; rendering only reads the cached values. Other Sky2M players' lines come from
 * the server ({@link SbcNameTags}). Drawn by {@code Sky2MOwnNameTagMixin}.
 */
public final class OwnNameTag {
    /** The line above the name tag, put into the player's render state when it's extracted. */
    public static final RenderStateDataKey<Component> LINE = RenderStateDataKey.create(() -> "sky2m:own_name_tag_line");
    /** The leaderboard line above the stats line. */
    public static final RenderStateDataKey<Component> BOARD_LINE = RenderStateDataKey.create(() -> "sky2m:own_name_tag_board");

    private static final long REFRESH_MS = 10 * 60_000L, RETRY_MS = 2 * 60_000L;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile long networth = -1, lastTry;
    private static volatile double skillAverage = -1;
    private static volatile UUID owner;
    private static volatile long lastPublish;

    private OwnNameTag() {}

    private static Sky2MConfig.OwnNameTag config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.ownNameTag;
    }

    /** Whether to show {@code entity}'s name tag although vanilla wouldn't: you, in third person. */
    public static boolean showName(Entity entity) {
        Sky2MConfig.OwnNameTag c = config();
        Minecraft mc = Minecraft.getInstance();
        return c != null && (c.enabled || c.stats || headBoard(c) != null || !customText(c).isEmpty()) && entity == mc.player && !mc.options.getCameraType().isFirstPerson()
            && !mc.gui.hud.isHidden() && !entity.isInvisible();
    }

    /** The stats line above {@code entity}'s name tag, or null. */
    public static Component line(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity instanceof Player player && player != mc.player) return SbcNameTags.line(player.getUUID());
        Sky2MConfig.OwnNameTag c = config();
        String custom = c == null ? "" : customText(c);
        String board = c == null ? null : headBoard(c);
        if (c == null || (!c.stats && board == null && custom.isEmpty()) || entity != mc.player || !Sky2MLocation.onSkyblock()) return null;
        if (c.stats) refresh(mc);
        MutableComponent line = Component.empty();
        boolean any = false;
        if (!custom.isEmpty()) {
            line.append(Component.literal(custom).withStyle(ChatFormatting.LIGHT_PURPLE));
            any = true;
        }
        if (c.stats && c.networth && networth >= 0) {
            if (any) line.append(Component.literal("   "));
            line.append(Component.literal("NW " + CombatFeatures.formatCoins(networth)).withStyle(ChatFormatting.GOLD));
            any = true;
        }
        if (c.stats && c.skillAverage && skillAverage >= 0) {
            if (any) line.append(Component.literal("   "));
            line.append(Component.literal(String.format(Locale.ROOT, "Skill Avg %.1f", skillAverage)).withStyle(ChatFormatting.GREEN));
            any = true;
        }
        long now = System.currentTimeMillis();
        if (now - lastPublish >= 30_000L) {
            lastPublish = now;
            SbcNameTags.publish(line.getString(), board == null ? "" : board);
        }
        return any ? line : null;
    }

    /** The leaderboard line above {@code entity}'s stats line, or null: yours from the board you picked, others' shared. */
    public static Component boardLine(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity instanceof Player player && player != mc.player) return SbcNameTags.boardLine(player.getUUID());
        Sky2MConfig.OwnNameTag c = config();
        if (c == null || entity != mc.player || headBoard(c) == null || !Sky2MLocation.onSkyblock()) return null;
        return SbcNameTags.boardLine(mc.player.getUUID());
    }

    /** Boards the server can show over your head: collections, slayer XP and slayer kill times. */
    public static boolean canShowOverHead(String boardKey) {
        return boardKey != null && (boardKey.startsWith("collection|") || boardKey.startsWith("slayer|") || boardKey.startsWith("pb|"));
    }

    /**
     * Shows {@code boardKey} over your head ("collection|WHEAT", "slayer|zombie", "pb|Revenant Horror V"), or stops when
     * it's the one already shown. Returns whether a board is shown now.
     */
    public static boolean toggleHeadBoard(String boardKey) {
        Sky2MConfig.OwnNameTag c = config();
        if (c == null || !canShowOverHead(boardKey)) return false;
        boolean showing = !(boardKey.equals(headBoard(c)));
        c.headBoard = showing ? boardKey : "";
        c.showCollectionLeaderboard = showing;
        Sky2MConfig.saveCurrent(Sky2MConfig.current());
        lastPublish = 0;
        return showing;
    }

    /** The board shown over your head, or null (an older config's Collection ID counts as "collection|ID"). */
    public static String headBoard(Sky2MConfig.OwnNameTag c) {
        if (!c.showCollectionLeaderboard) return null;
        if (c.headBoard != null && canShowOverHead(c.headBoard)) return c.headBoard;
        String legacy = c.collectionLeaderboard == null ? "" : c.collectionLeaderboard.trim().toUpperCase(Locale.ROOT);
        return legacy.isEmpty() ? null : "collection|" + legacy;
    }

    /** The board shown over your head, for the leaderboard screen's marker, or null. */
    public static String headBoard() {
        Sky2MConfig.OwnNameTag c = config();
        return c == null ? null : headBoard(c);
    }

    /** The player's own name tag text: one line, no colour codes, at most 40 characters. */
    private static String customText(Sky2MConfig.OwnNameTag c) {
        String text = c.customText == null ? "" : ChatFormatting.stripFormatting(c.customText);
        text = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").trim();
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    /** Loads your profile in the background when the cached stats are old (or someone else's, after an account switch). */
    private static void refresh(Minecraft mc) {
        UUID uuid = mc.player.getUUID();
        if (!uuid.equals(owner)) {
            owner = uuid;
            networth = -1;
            skillAverage = -1;
            lastTry = 0;
        }
        long now = System.currentTimeMillis();
        if (now - lastTry < (skillAverage < 0 ? RETRY_MS : REFRESH_MS) || !BUSY.compareAndSet(false, true)) return;
        lastTry = now;
        boolean wantNetworth = config() != null && config().networth;
        ProfileApi.player(mc.player.getGameProfile().name()).thenCompose(player -> {
            ProfileApi.Profile profile = player.profiles().getFirst();
            skillAverage = ProfileStats.skillAverage(profile.member());
            if (!wantNetworth) return CompletableFuture.<Networth.Result>completedFuture(null);
            // Museum included like the profile viewer, or left out if it won't load.
            return ProfileApi.aux(ProfileApi.Aux.MUSEUM, profile.id()).exceptionally(error -> null)
                .thenCompose(museum -> Networth.skyHelper(profile.profile(), profile.member(), museum, player.uuid()));
        }).whenComplete((result, error) -> {
            if (result != null && uuid.equals(owner)) networth = result.total();
            BUSY.set(false);
        });
    }
}
