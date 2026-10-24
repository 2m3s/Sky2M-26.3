package com.epic60869.sky2m.features.combat.kuudra;

import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What is going on in the current Kuudra run: the phase (from Elle's lines), tier, the supply giants, unfinished piles,
 * Kuudra, the ballista's build progress and Fresh Tools timers. Modelled on Odin's KuudraUtils (BSD-3-Clause,
 * https://github.com/odtheking/Odin). Phases: 0 waiting, 1 supplies, 2 build, 3 fuel and stun, 4 Kuudra, 5 over.
 */
final class KuudraState {
    private static final Pattern TIER = Pattern.compile("Kuudra's Hollow \\(T(\\d)\\)");
    private static final Pattern PROGRESS = Pattern.compile("PROGRESS: (\\d+)%");
    private static final Pattern BUILD = Pattern.compile("Building Progress (\\d+)% \\((\\d+) Players? Helping\\)");
    private static final Pattern PARTY_FRESH = Pattern.compile("^Party > (?:\\[[^]]*] )?(\\w{1,16}): FRESH$");
    private static final String OWN_FRESH = "Your Fresh Tools Perk bonus doubles your building speed for the next 10 seconds!";
    static final long FRESH_MS = 10_000L;

    record Giant(double x, double z, float yaw) {}
    record Pile(String name, double x, double y, double z, Entity entity) {}

    static volatile int phase;
    static volatile int tier;
    static volatile boolean inKuudra;
    static volatile Entity kuudra;
    static volatile int buildPercent;
    static volatile int builders;
    static volatile List<Giant> giants = List.of();
    static volatile List<Pile> piles = List.of();
    /** Name -> when their Fresh Tools started. */
    static final Map<String, Long> freshers = new ConcurrentHashMap<>();
    static volatile long phaseStartMs;

    private static final List<IntConsumer> PHASE_LISTENERS = new CopyOnWriteArrayList<>();
    private static int ticks;

    private KuudraState() {}

    static void onPhase(IntConsumer listener) { PHASE_LISTENERS.add(listener); }

    static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 4 == 0) tick(mc);
        });
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
    }

    static void reset() {
        phase = 0;
        tier = 0;
        inKuudra = false;
        kuudra = null;
        buildPercent = 0;
        builders = 0;
        giants = List.of();
        piles = List.of();
        freshers.clear();
        for (KuudraSupply s : KuudraSupply.values()) s.active = true;
    }

    private static void setPhase(int newPhase) {
        if (newPhase <= phase) return;
        phase = newPhase;
        phaseStartMs = System.currentTimeMillis();
        for (IntConsumer listener : PHASE_LISTENERS) {
            try {
                listener.accept(newPhase);
            } catch (Exception ignored) {}
        }
    }

    private static void onChat(String text) {
        if (!inKuudra) return;
        switch (text) {
            case "[NPC] Elle: Okay adventurers, I will go and fish up Kuudra!" -> setPhase(1);
            case "[NPC] Elle: OMG! Great work collecting my supplies!" -> setPhase(2);
            case "[NPC] Elle: Phew! The Ballista is finally ready! It should be strong enough to tank Kuudra's blows now!" -> setPhase(3);
            case "[NPC] Elle: POW! SURELY THAT'S IT! I don't think he has any more in him!" -> setPhase(4);
            case "KUUDRA DOWN!" -> setPhase(5);
            default -> {}
        }
        Matcher party = PARTY_FRESH.matcher(text);
        if (party.matches()) freshers.put(party.group(1), System.currentTimeMillis());
        if (text.equals(OWN_FRESH)) {
            Minecraft mc = Minecraft.getInstance();
            freshers.put(mc.player == null ? "You" : mc.player.getGameProfile().name(), System.currentTimeMillis());
        }
    }

    static boolean nameIsSelf(String name) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.getGameProfile().name().equalsIgnoreCase(name);
    }

    private static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || !Compat.isOnSkyblock()) {
            inKuudra = false;
            return;
        }
        for (String line : Sky2MLocation.scoreboard()) {
            Matcher m = TIER.matcher(line);
            if (m.find()) tier = Integer.parseInt(m.group(1));
        }
        if (tier == 0) {
            for (String line : Sky2MLocation.teamLines()) {
                Matcher m = TIER.matcher(line);
                if (m.find()) tier = Integer.parseInt(m.group(1));
            }
        }
        inKuudra = Sky2MLocation.area().toLowerCase(Locale.ROOT).startsWith("kuudra") || tier > 0;
        if (!inKuudra) return;

        List<Giant> newGiants = new ArrayList<>();
        List<Pile> newPiles = new ArrayList<>();
        Entity foundKuudra = null;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.monster.Giant) {
                if (entity instanceof LivingEntity living && living.getMainHandItem().getHoverName().getString().endsWith("Head")) {
                    newGiants.add(new Giant(entity.getX(), entity.getZ(), entity.getYRot()));
                }
            } else if (entity instanceof net.minecraft.world.entity.monster.cubemob.MagmaCube) {
                if (entity instanceof LivingEntity living && living.getMaxHealth() == 100000f) foundKuudra = entity;
            } else if (entity instanceof ArmorStand) {
                Component custom = entity.getCustomName();
                if (custom == null) continue;
                String name = Sky2MLocation.strip(custom.getString());
                if (PROGRESS.matcher(name).matches()) newPiles.add(new Pile(name, entity.getX(), entity.getY(), entity.getZ(), entity));
                if (phase == 2) {
                    Matcher build = BUILD.matcher(name);
                    if (build.find()) {
                        buildPercent = Integer.parseInt(build.group(1));
                        builders = Integer.parseInt(build.group(2));
                    }
                }
                if (phase == 1 && name.equals("✓ SUPPLIES RECEIVED ✓")) markReceived((int) entity.getX(), (int) entity.getZ());
            }
        }
        giants = newGiants;
        piles = newPiles;
        kuudra = foundKuudra;
    }

    private static void markReceived(int x, int z) {
        if (x == -98 && z == -112) KuudraSupply.SHOP.active = false;
        else if (x == -98 && z == -99) KuudraSupply.EQUALS.active = false;
        else if (x == -110 && z == -106) KuudraSupply.XCANNON.active = false;
        else if (x == -106 && z == -112) KuudraSupply.X.active = false;
        else if (x == -94 && z == -106) KuudraSupply.TRIANGLE.active = false;
        else if (x == -106 && z == -99) KuudraSupply.SLASH.active = false;
    }
}
