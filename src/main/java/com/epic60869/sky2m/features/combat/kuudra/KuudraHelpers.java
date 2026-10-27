package com.epic60869.sky2m.features.combat.kuudra;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kuudra's in-world and HUD helpers, ported from Odin's Nether module (BSD-3-Clause, https://github.com/odtheking/Odin):
 * SupplyHelper (pick-up and drop-off waypoints, supply times), NoPre (pre-spot alert and crate priority, shown to you
 * only, nothing is sent to the party), BuildHelper (unfinished piles, builders, build percentage, stun alert),
 * FreshTools (fresh timers) and KuudraInfo (hitbox and health).
 */
final class KuudraHelpers {
    private static final Pattern PICKUP = Pattern.compile("(?:\\[[^]]*] )?(\\w{1,16}) recovered one of Elle's supplies! \\((\\d)/(\\d)\\)");
    private static final Pattern PARTY_NO = Pattern.compile("^Party > (?:\\[[^]]*] )?(\\w{1,16}): No ?(Triangle|X|Equals|Slash|xCannon|Square|Shop)!$");
    private static final String PRE = "[NPC] Elle: Head over to the main platform, I will join you when I get a bite!";
    private static final String START = "[NPC] Elle: Not again!";
    private static final float[] YELLOW = {1f, 1f, 0.33f}, RED = {1f, 0.2f, 0.2f}, GREEN = {0.2f, 1f, 0.2f}, DARK_RED = {0.67f, 0f, 0f},
        KUUDRA = {1f, 0.3f, 0.1f};

    private static KuudraSupply preSpot = KuudraSupply.NONE;
    private static KuudraSupply missing = KuudraSupply.NONE;
    private static long supplyStart;
    private static boolean stunAlerted;

    private KuudraHelpers() {}

    private static KuudraConfig config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.crimsonIsle.kuudra;
    }

    static void init() {
        Sky2MChat.onChat(message -> onChat(message.text().trim()));
        KuudraState.onPhase(phase -> {
            if (phase == 1) supplyStart = System.currentTimeMillis();
            if (phase == 1) stunAlerted = false;
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            preSpot = KuudraSupply.NONE;
            missing = KuudraSupply.NONE;
            stunAlerted = false;
        });
        Sky2MWorldRender.register(KuudraHelpers::render);

        Sky2MHuds.setting("kuudra_build", () -> config() != null && config().buildHud);
        Sky2MHuds.register("kuudra_build", "Kuudra Build Helper",
            () -> config() != null && config().buildHud && KuudraState.inKuudra && KuudraState.phase == 2,
            () -> List.of(label("Freshers: ", colourBuilders(KuudraState.freshers.size())),
                label("Builders: ", colourBuilders(KuudraState.builders)),
                label("Build: ", colourBuild(KuudraState.buildPercent) + "%")),
            List.of(label("Freshers: ", "1"), label("Builders: ", "3"), label("Build: ", "62%")), 8, 200);

        Sky2MHuds.setting("kuudra_fresh", () -> config() != null && config().freshTimer);
        Sky2MHuds.register("kuudra_fresh", "Kuudra Fresh Timer",
            () -> config() != null && config().freshTimer && KuudraState.inKuudra && KuudraState.phase == 2,
            KuudraHelpers::freshLines,
            List.of(label("Player1: ", "9s"), label("Player2: ", "5s")), 8, 240);

        Sky2MHuds.setting("kuudra_health", () -> config() != null && config().healthHud);
        Sky2MHuds.register("kuudra_health", "Kuudra Health",
            () -> config() != null && config().healthHud && KuudraState.inKuudra && KuudraState.kuudra instanceof LivingEntity,
            () -> KuudraState.kuudra instanceof LivingEntity living
                ? List.of(Component.literal(healthText(living.getHealth())))
                : List.of(),
            List.of(Component.literal("99.975M/240M")), 8, 260);
    }

    private static Component label(String name, String value) {
        return Component.literal(name).withStyle(ChatFormatting.AQUA).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static List<Component> freshLines() {
        List<Component> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        KuudraState.freshers.forEach((name, started) -> {
            long left = KuudraState.FRESH_MS - (now - started);
            if (left > 0) out.add(Component.literal(name + ": ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(String.format(Locale.US, "%.1fs", left / 1000.0)).withStyle(ChatFormatting.WHITE)));
        });
        return out;
    }

    private static String colourBuild(int build) {
        return String.valueOf(build);
    }

    private static String colourBuilders(int builders) {
        return String.valueOf(builders);
    }

    // ---------------------------------------------------------------------------------------------------- chat

    private static void onChat(String text) {
        KuudraConfig c = config();
        if (c == null || !KuudraState.inKuudra) return;
        Minecraft mc = Minecraft.getInstance();
        if (text.equals(PRE) && mc.player != null) {
            double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
            if (KuudraSupply.TRIANGLE.pickUpCloserThan(x, y, z, 15)) preSpot = KuudraSupply.TRIANGLE;
            else if (KuudraSupply.X.pickUpCloserThan(x, y, z, 30)) preSpot = KuudraSupply.X;
            else if (KuudraSupply.EQUALS.pickUpCloserThan(x, y, z, 15)) preSpot = KuudraSupply.EQUALS;
            else if (KuudraSupply.SLASH.pickUpCloserThan(x, y, z, 15)) preSpot = KuudraSupply.SLASH;
            else preSpot = KuudraSupply.NONE;
            if (c.preSpotAlert) {
                Sky2MAlerts.chat(preSpot == KuudraSupply.NONE
                    ? Component.literal("Didn't register your pre-spot because you didn't get there in time.").withStyle(ChatFormatting.RED)
                    : Component.literal("Pre-spot: " + preSpot.label).withStyle(ChatFormatting.AQUA));
            }
            return;
        }
        if (text.equals(START)) {
            if (c.preSpotAlert) checkPre(c);
            return;
        }
        Matcher no = PARTY_NO.matcher(text);
        if (no.matches()) {
            missing = KuudraSupply.byLabel(no.group(2));
            showPriority(c);
            return;
        }
        if (c.supplyTimes && KuudraState.phase == 1) {
            Matcher pickup = PICKUP.matcher(text);
            if (pickup.find()) {
                Sky2MAlerts.chat(Component.literal(pickup.group(1) + " recovered a supply in ").withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(KuudraSplits.format(System.currentTimeMillis() - supplyStart)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal(" (" + pickup.group(2) + "/" + pickup.group(3) + ")").withStyle(ChatFormatting.DARK_GRAY)));
            }
        }
    }

    /** At the start: is your pre crate, and the second crate that goes with it, there? */
    private static void checkPre(KuudraConfig c) {
        if (preSpot == KuudraSupply.NONE) return;
        boolean second = false, pre = false;
        for (KuudraState.Giant giant : KuudraState.giants) {
            double x = giant.x(), y = 76, z = giant.z();
            if (preSpot.pickUpCenterCloserThan(x, y, z, 18)) pre = true;
            else if (preSpot == KuudraSupply.TRIANGLE && KuudraSupply.SHOP.pickUpCenterCloserThan(x, y, z, 18)) second = true;
            else if (preSpot == KuudraSupply.X && KuudraSupply.XCANNON.pickUpCenterCloserThan(x, y, z, 16)) second = true;
            else if (preSpot == KuudraSupply.SLASH && KuudraSupply.SQUARE.pickUpCenterCloserThan(x, y, z, 20)) second = true;
        }
        if (second && pre) return;
        String message;
        if (!pre) {
            message = "No " + preSpot.label + "!";
            missing = preSpot;
        } else {
            message = switch (preSpot) {
                case TRIANGLE -> "No Shop!";
                case X -> "No xCannon!";
                case SLASH -> "No Square!";
                default -> "";
            };
            if (message.isEmpty()) return;
            missing = KuudraSupply.byLabel(message.substring(3, message.length() - 1));
        }
        Sky2MAlerts.title(Component.literal(message).withStyle(ChatFormatting.RED, ChatFormatting.BOLD), Component.empty());
        showPriority(c);
    }

    private static void showPriority(KuudraConfig c) {
        if (!c.cratePriority) return;
        String priority = cratePriority(missing, c.advancedCratePriority);
        if (priority.isEmpty()) return;
        Sky2MAlerts.title(Component.literal(priority).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), Component.empty(), false);
        Sky2MAlerts.chat(Component.literal("Crate Priority: ").withStyle(ChatFormatting.AQUA).append(Component.literal(priority).withStyle(ChatFormatting.WHITE)));
    }

    /** Odin's NoPre.cratePriority. */
    private static String cratePriority(KuudraSupply missing, boolean advanced) {
        String pull = advanced ? "Pull Square and X Cannon. Next: collect Shop" : "Pull Square. Next: collect Shop";
        String altCannon = advanced ? "Go Shop" : "Go X Cannon";
        return switch (missing) {
            case SHOP -> switch (preSpot) {
                case TRIANGLE, X -> "Go X Cannon";
                case EQUALS, SLASH -> "Go Square, place on Shop";
                default -> "";
            };
            case TRIANGLE -> switch (preSpot) {
                case TRIANGLE -> pull;
                case X -> "Go X Cannon";
                case EQUALS -> altCannon;
                case SLASH -> "Go Square, place on Triangle";
                default -> "";
            };
            case EQUALS -> switch (preSpot) {
                case TRIANGLE -> altCannon;
                case X -> "Go X Cannon";
                case EQUALS -> pull;
                case SLASH -> "Go Square, place on Equals";
                default -> "";
            };
            case SLASH -> switch (preSpot) {
                case TRIANGLE -> "Go Square, place on Slash";
                case X -> "Go X Cannon";
                case EQUALS -> altCannon;
                case SLASH -> pull;
                default -> "";
            };
            case SQUARE -> switch (preSpot) {
                case TRIANGLE, EQUALS -> "Go Shop";
                case X, SLASH -> "Go X Cannon";
                default -> "";
            };
            case XCANNON -> switch (preSpot) {
                case TRIANGLE, EQUALS -> "Go Shop";
                case SLASH, X -> "Go Square, place on X Cannon";
                default -> "";
            };
            case X -> switch (preSpot) {
                case TRIANGLE -> "Go X Cannon";
                case X -> pull;
                case EQUALS -> altCannon;
                case SLASH -> "Go Square, place on X";
                default -> "";
            };
            default -> "";
        };
    }

    // ---------------------------------------------------------------------------------------------------- world

    private static void render(PrimitiveCollector collector) {
        KuudraConfig c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !KuudraState.inKuudra || mc.player == null) return;
        Vec3 camera = mc.gameRenderer.mainCamera().position();
        int phase = KuudraState.phase;

        if (phase == 1) {
            if (c.supplyDropOff) {
                for (KuudraSupply s : KuudraSupply.values()) {
                    if (s == KuudraSupply.NONE || s == KuudraSupply.SQUARE || !s.active) continue;
                    waypoint(collector, camera, s.dropOff, s == missing ? GREEN : RED, "Place Here!");
                }
            }
            if (c.supplyPickUp) {
                for (KuudraState.Giant g : KuudraState.giants) {
                    double angle = Math.toRadians(g.yaw() + 130);
                    BlockPos pos = new BlockPos((int) (g.x() + 3.7 * Math.cos(angle)), 73, (int) (g.z() + 3.7 * Math.sin(angle)));
                    waypoint(collector, camera, pos, YELLOW, "Pick Up!");
                }
            }
            if (c.supplySpotLabels) {
                for (KuudraSupply s : KuudraSupply.values()) {
                    if (s == KuudraSupply.NONE) continue;
                    Vec3 at = Vec3.atCenterOf(s.pickUp);
                    collector.submitText(Component.literal(s.label).withStyle(ChatFormatting.YELLOW), at, (float) Math.max(2, camera.distanceTo(at) / 8), true);
                }
            }
        }

        if (phase == 2) {
            if (c.pileWaypoints) {
                for (KuudraState.Pile pile : KuudraState.piles) {
                    waypoint(collector, camera, BlockPos.containing(pile.x(), pile.y(), pile.z()), DARK_RED, pile.name());
                    if (c.hidePileTag && pile.entity() != null) pile.entity().setCustomNameVisible(false);
                }
            }
            if (c.buildOnBallista) {
                Vec3 ballista = new Vec3(-101.5, 82, -105.5);
                float scale = (float) Math.max(3, camera.distanceTo(ballista) / 8);
                collector.submitText(Component.literal("Build " + KuudraState.buildPercent + "%").withStyle(ChatFormatting.AQUA), ballista, scale, false);
                collector.submitText(Component.literal("Builders " + KuudraState.builders).withStyle(ChatFormatting.AQUA), ballista.add(0, -1, 0), scale, false);
            }
            if (c.stunPercent > 0 && KuudraState.tier >= 3 && KuudraState.buildPercent >= c.stunPercent && !stunAlerted) {
                stunAlerted = true;
                Sky2MAlerts.title(Component.literal("Go to stun").withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD), Component.empty());
            }
        }

        Entity kuudra = KuudraState.kuudra;
        if (kuudra != null && kuudra.isAlive()) {
            if (c.hitbox) collector.submitOutlinedBox(kuudra.getBoundingBox(), KUUDRA, 3f, true);
            if (c.healthInWorld && kuudra instanceof LivingEntity living) {
                Vec3 at = kuudra.position().add(kuudra.getLookAngle().scale(13)).add(0, 10, 0);
                collector.submitText(Component.literal(healthText(living.getHealth())), at, (float) Math.max(4, camera.distanceTo(at) / 6), true);
            }
        }
    }

    private static void waypoint(PrimitiveCollector collector, Vec3 camera, BlockPos pos, float[] colour, String label) {
        collector.submitFilledBoxWithBeaconBeam(pos, colour, 0.5f, true);
        Vec3 at = Vec3.atBottomCenterOf(pos).add(0, 1.8, 0);
        collector.submitText(Component.literal(label), at, (float) Math.max(1, camera.distanceTo(at) / 7), true);
    }

    /** Odin's KuudraInfo health text: out of 100K, or scaled to 240M for tier 5 under 25K. */
    static String healthText(float health) {
        KuudraConfig c = config();
        float k = health / 1000f;
        if (health <= 25000 && c != null && c.scaledHealth && KuudraState.tier == 5) {
            return String.format(Locale.US, "%.1fM/240M", k * 9.6f);
        }
        return String.format(Locale.US, "%.1fK/100K", k);
    }
}
