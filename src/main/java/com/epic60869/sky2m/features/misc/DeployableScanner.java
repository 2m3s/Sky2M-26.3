package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.custom.util.Compat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The power orbs and flares buffing you right now, found the way SkyblockAddons' deployable display finds them: by
 * looking at the armour stands around you, not by waiting for you to use one. So other players' orbs and flares count
 * too (they buff you just the same), and a timer is right even after a server switch. A power orb has a name tag with
 * its time left ("Overflux 52s") and buffs you within 18 blocks; a flare has none, so it's timed from when it
 * appeared (they last three minutes) and buffs you within 40 blocks.
 */
public final class DeployableScanner {
    private static final Pattern ORB = Pattern.compile("^(Radiant|Mana Flux|Overflux|Plasmaflux)\\D*?(\\d+)s$");
    private static final double ORB_RANGE = 18, FLARE_RANGE = 40;
    private static final long FLARE_MS = 180_000L;
    /** The best of each kind is shown: higher tier first. */
    private static final List<String> ORB_ORDER = List.of("Plasmaflux", "Overflux", "Mana Flux", "Radiant");
    /** Flare head textures, as SkyblockAddons recognises them. A firework on the stand's head is a flare too. */
    private static final Map<String, String> FLARE_TEXTURES = Map.of(
        "22e2bf6c1ec330247927ba63479e5872ac66b06903c86c82b52dac9f1c971458", "Warning Flare",
        "9d2bf9864720d87fd06b84efa80b795c48ed539b16523c3b1f1990b40c003f6b", "Alert Flare",
        "c0062cc98ebda72a6a4b89783adcef2815b483a01d73ea87b3df76072a89d13b", "SOS Flare");
    private static final List<String> FLARE_ORDER = List.of("SOS Flare", "Alert Flare", "Warning Flare", "Flare");

    public record Active(String name, long endsAt) {}

    private static final Map<Integer, Long> FLARE_SEEN = new HashMap<>();
    private static List<Active> active = List.of();
    private static int ticks;

    private DeployableScanner() {}

    /** Called every client tick; scans twice a second. */
    public static void tick(Minecraft mc) {
        if (++ticks % 10 != 0) return;
        if (mc.player == null || mc.level == null || !Compat.isOnSkyblock()) {
            active = List.of();
            FLARE_SEEN.clear();
            return;
        }
        long now = System.currentTimeMillis();
        Active orb = null, flare = null;
        for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, mc.player.getBoundingBox().inflate(FLARE_RANGE))) {
            double distance = stand.distanceTo(mc.player);
            if (stand.hasCustomName()) {
                Matcher m = ORB.matcher(ChatFormatting.stripFormatting(stand.getCustomName().getString()).trim());
                if (m.matches() && distance <= ORB_RANGE) {
                    Active found = new Active(m.group(1).equals("Radiant") ? "Radiant Orb" : m.group(1), now + Long.parseLong(m.group(2)) * 1000);
                    if (orb == null || rank(ORB_ORDER, found.name()) < rank(ORB_ORDER, orb.name())) orb = found;
                }
                continue;
            }
            String flareName = flareName(stand.getItemBySlot(EquipmentSlot.HEAD));
            if (flareName == null) continue;
            long seen = FLARE_SEEN.computeIfAbsent(stand.getId(), id -> now - stand.tickCount * 50L);
            Active found = new Active(flareName, seen + FLARE_MS);
            if (found.endsAt() <= now) continue;
            if (flare == null || rank(FLARE_ORDER, found.name()) < rank(FLARE_ORDER, flare.name())) flare = found;
        }
        FLARE_SEEN.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        List<Active> list = new ArrayList<>();
        if (orb != null) list.add(orb);
        if (flare != null) list.add(flare);
        active = list;
    }

    private static int rank(List<String> order, String name) {
        int i = order.indexOf(name.replace(" Orb", ""));
        return i < 0 ? order.size() : i;
    }

    private static String flareName(ItemStack head) {
        if (head.isEmpty()) return null;
        if (head.is(Items.FIREWORK_ROCKET)) return "Flare";
        if (!head.is(Items.PLAYER_HEAD)) return null;
        String value = Compat.getHeadTexture(head);
        if (value == null || value.isEmpty()) return null;
        try {
            String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> e : FLARE_TEXTURES.entrySet()) if (json.contains(e.getKey())) return e.getValue();
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    /** The HUD lines ("Overflux: 52s"), or empty when nothing is buffing you. */
    public static List<Component> lines() {
        long now = System.currentTimeMillis();
        List<Component> lines = new ArrayList<>();
        active.stream().sorted(Comparator.comparingLong(Active::endsAt)).forEach(a -> {
            long left = Math.max(0, a.endsAt() - now) / 1000;
            String time = left >= 60 ? (left / 60) + "m " + (left % 60) + "s" : left + "s";
            lines.add(Component.literal(a.name() + ": ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(time).withStyle(left <= 10 ? ChatFormatting.RED : ChatFormatting.WHITE)));
        });
        return lines;
    }
}
