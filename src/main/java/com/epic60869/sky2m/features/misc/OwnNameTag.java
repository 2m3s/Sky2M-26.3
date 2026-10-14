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

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Misc > Visual > Own Name Tag: your own name tag above your head in third person (vanilla hides it), and a line above
 * it with your networth and skill average ("NW 37.47B   Skill Avg 52.4"). Both come from your selected profile, loaded
 * like the profile viewer in the background at most every 10 minutes; rendering only reads the cached values. Drawn by
 * {@code Sky2MOwnNameTagMixin}. Only your own player: other players' networth would need their whole profile and museum.
 */
public final class OwnNameTag {
    /** The line above the name tag, put into the player's render state when it's extracted. */
    public static final RenderStateDataKey<Component> LINE = RenderStateDataKey.create(() -> "sky2m:own_name_tag_line");

    private static final long REFRESH_MS = 10 * 60_000L, RETRY_MS = 2 * 60_000L;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile long networth = -1, lastTry;
    private static volatile double skillAverage = -1;
    private static volatile UUID owner;

    private OwnNameTag() {}

    private static Sky2MConfig.OwnNameTag config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.ownNameTag;
    }

    /** Whether to show {@code entity}'s name tag although vanilla wouldn't: you, in third person. */
    public static boolean showName(Entity entity) {
        Sky2MConfig.OwnNameTag c = config();
        Minecraft mc = Minecraft.getInstance();
        return c != null && (c.enabled || c.stats) && entity == mc.player && !mc.options.getCameraType().isFirstPerson()
            && !mc.gui.hud.isHidden() && !entity.isInvisible();
    }

    /** The stats line above {@code entity}'s name tag, or null. */
    public static Component line(Entity entity) {
        Sky2MConfig.OwnNameTag c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.stats || entity != mc.player || !Sky2MLocation.onSkyblock()) return null;
        refresh(mc);
        MutableComponent line = Component.empty();
        boolean any = false;
        if (c.networth && networth >= 0) {
            line.append(Component.literal("NW " + CombatFeatures.formatCoins(networth)).withStyle(ChatFormatting.GOLD));
            any = true;
        }
        if (c.skillAverage && skillAverage >= 0) {
            if (any) line.append(Component.literal("   "));
            line.append(Component.literal(String.format(Locale.ROOT, "Skill Avg %.1f", skillAverage)).withStyle(ChatFormatting.GREEN));
            any = true;
        }
        return any ? line : null;
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
