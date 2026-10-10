package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Inquisitor Gamble: when an Inquisitor you dug up dies, or you lootshare one, it's shot at on screen. A pixel
 * Inquisitor appears, a crosshair sways over its heart while the tension ticks up, and the gun fires: if a Chimera
 * dropped the bullet goes through the heart, otherwise it misses the body.
 *
 * <p>Nothing gives the Chimera away before the shot: while the animation plays (and for a moment after a Chimera line,
 * in case the Inquisitor's death is seen just after it) Sky2M's HUDs are hidden, and the Chimera chat lines, the
 * drop announcer, the rare drop animations and achievement popups are held back until it's over.
 */
public final class InquisitorGamble {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("sky2m", "inquisitor_gamble");

    private static final long POP_MS = 400L;
    private static final long SHOT_MS = 1_900L;
    private static final long END_MS = 4_300L;
    private static final long FADE_MS = 600L;
    /** A Chimera this long before the Inquisitor died still counts (the drop line can come first). */
    private static final long CHIMERA_BEFORE_MS = 3_000L;
    /** Figure scale: one "pixel" of the Inquisitor. */
    private static final float PX = 3.2f;

    // Heart, and where a miss lands (beside the arm), in figure pixels from the chest.
    private static final float HEART_X = 0, HEART_Y = -5;
    private static final float MISS_X = 15, MISS_Y = -9;

    private static long startedAt = -1;
    private static boolean lootshare;
    private static Boolean forced;
    private static boolean fired;
    private static boolean hit;
    private static int lastTickSound = -1;
    private static long lastChimeraAt = -1;
    private static long lastYourInqAt = -1;
    /** Waiting a moment after a Chimera line for the gamble to start. */
    private static long holdUntil;
    private static final long HOLD_FOR_TRIGGER_MS = 1_500L;
    /** Reveals held back until the animation is over. */
    private static final List<Runnable> held = new ArrayList<>();

    private InquisitorGamble() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.mayors.diana.inquisitorGamble;
    }

    public static void init() {
        HudElementRegistry.addLast(ID, (graphics, delta) -> render(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        // Hypixel's "RARE DROP! Enchanted Book (Chimera 1)" (and anything else naming Chimera) waits for the shot.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            String text = com.epic60869.sky2m.features.core.Sky2MLocation.strip(message.getString()).trim();
            checkLine(text);
            if (!holding() || !text.contains("Chimera")) return true;
            Minecraft mc = Minecraft.getInstance();
            held.add(() -> {
                if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(message);
            });
            return false;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("inqgamble")
                    .executes(c -> preview(null))
                    .then(ClientCommands.argument("result", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("hit", "miss"), b))
                        .executes(c -> preview(StringArgumentType.getString(c, "result"))))));
            }
        });
    }

    private static int preview(String result) {
        Minecraft.getInstance().execute(() -> {
            Boolean outcome = result == null ? Boolean.valueOf(Math.random() < 0.5) : Boolean.valueOf(result.equalsIgnoreCase("hit"));
            begin(false, outcome);
        });
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ holding reveals

    /** The animation is playing, or a Chimera just dropped and it may be about to. */
    public static boolean holding() {
        if (!enabled()) return false;
        long now = System.currentTimeMillis();
        return (startedAt >= 0 && now - startedAt < END_MS) || now < holdUntil;
    }

    /** A chat line: a Chimera drop in the Hub holds reveals for a moment in case the gamble starts. */
    public static void checkLine(String text) {
        if (text.startsWith("RARE DROP! Enchanted Book (Chimera") || text.startsWith("RARE DROP! Chimera")) holdForTrigger();
    }

    private static void holdForTrigger() {
        if (!enabled() || !DianaTracker.inHub()) return;
        holdUntil = Math.max(holdUntil, System.currentTimeMillis() + HOLD_FOR_TRIGGER_MS);
    }

    /** Runs {@code reveal} now, or after the animation if it's holding. */
    public static void afterReveal(Runnable reveal) {
        if (holding()) held.add(reveal);
        else reveal.run();
    }

    private static void release() {
        if (held.isEmpty() || holding()) return;
        List<Runnable> now = new ArrayList<>(held);
        held.clear();
        for (Runnable r : now) {
            try {
                r.run();
            } catch (Exception e) {
                System.err.println("[Sky2M] Inquisitor Gamble: " + e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ triggers

    /** You dug up an Inquisitor. */
    static void onYourInquisitor() {
        lastYourInqAt = System.currentTimeMillis();
    }

    /** An Inquisitor died near you. Yours (dug up in the last 10 minutes) and not a lootshare: shoot. */
    static void onInquisitorDeath(boolean lootshareNow) {
        if (lootshareNow) return;
        if (lastYourInqAt < 0 || System.currentTimeMillis() - lastYourInqAt > 10 * 60_000L) return;
        lastYourInqAt = -1;
        start(false);
    }

    /** You lootshared an Inquisitor. */
    static void onInquisitorLootshare() {
        start(true);
    }

    static void onChimera() {
        lastChimeraAt = System.currentTimeMillis();
        holdForTrigger();
    }

    private static void start(boolean ls) {
        if (!enabled()) return;
        Minecraft.getInstance().execute(() -> {
            long now = System.currentTimeMillis();
            // LOOT SHARE can come just after the death: the same shot, now a lootshare.
            if (startedAt >= 0 && now - startedAt < SHOT_MS) {
                lootshare |= ls;
                return;
            }
            begin(ls, null);
        });
    }

    private static void begin(boolean ls, Boolean outcome) {
        startedAt = System.currentTimeMillis();
        lootshare = ls;
        forced = outcome;
        fired = false;
        hit = false;
        lastTickSound = -1;
        Sky2MAlerts.play(SoundEvents.WITHER_SPAWN, 1.6f, 0.35f);
    }

    // ------------------------------------------------------------------------------------------------ sounds and the shot

    private static void tick() {
        release();
        if (startedAt < 0) return;
        long t = System.currentTimeMillis() - startedAt;
        if (t >= END_MS) {
            startedAt = -1;
            return;
        }
        if (!fired && t >= POP_MS && t < SHOT_MS) {
            // A heartbeat that speeds up and climbs while you wait.
            int beat = (int) ((t - POP_MS) / Math.max(100, 320 - (t - POP_MS) / 6));
            if (beat != lastTickSound) {
                lastTickSound = beat;
                float progress = (t - POP_MS) / (float) (SHOT_MS - POP_MS);
                Sky2MAlerts.play(SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.6f + progress * 0.9f, 0.8f);
            }
        }
        if (!fired && t >= SHOT_MS) {
            fired = true;
            hit = forced != null ? forced : lastChimeraAt >= startedAt - CHIMERA_BEFORE_MS;
            Sky2MAlerts.play(SoundEvents.GENERIC_EXPLODE.value(), 1.8f, 0.5f);
            Sky2MAlerts.play(SoundEvents.CROSSBOW_SHOOT, 0.7f, 1.0f);
            if (hit) {
                Sky2MAlerts.play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                Sky2MAlerts.play(SoundEvents.PLAYER_LEVELUP, 1.4f, 1.0f);
            } else {
                Sky2MAlerts.play(SoundEvents.VILLAGER_NO, 0.9f, 1.0f);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing

    private static void render(GuiGraphicsExtractor g) {
        if (startedAt < 0) return;
        long t = System.currentTimeMillis() - startedAt;
        if (t >= END_MS) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        float alpha = t > END_MS - FADE_MS ? (END_MS - t) / (float) FADE_MS : 1f;
        long sinceShot = t - SHOT_MS;
        boolean afterShot = fired && sinceShot >= 0;

        // Dim the world a little behind the scene.
        g.fill(0, 0, w, h, ARGB.color((int) (70 * alpha), 0, 0, 0));

        // Screen shake right after the shot.
        float shakeX = 0, shakeY = 0;
        if (afterShot && sinceShot < 250) {
            float k = 1f - sinceShot / 250f;
            shakeX = Mth.sin(sinceShot * 0.9f) * 5f * k;
            shakeY = Mth.cos(sinceShot * 1.3f) * 4f * k;
        }

        float cx = w / 2f + shakeX, cy = h / 2f + shakeY;
        float pop = t < POP_MS ? easeOutBack(t / (float) POP_MS) : 1f;
        // A hit knocks the Inquisitor back.
        float recoil = afterShot && hit ? Math.min(1f, sinceShot / 180f) * Math.max(0f, 1f - (sinceShot - 180f) / 600f) : 0f;

        // Header.
        Component header = Component.literal(lootshare ? "LOOTSHARE" : "YOUR INQUISITOR").withStyle(lootshare ? ChatFormatting.AQUA : ChatFormatting.GOLD, ChatFormatting.BOLD);
        drawCentered(g, font, header, cx, cy - 32 * PX, 1.6f, ARGB.color(alpha, 0xFFFFFFFF));
        if (!afterShot) {
            String dots = ".".repeat((int) (t / 300 % 4));
            drawCentered(g, font, Component.literal("Rolling for Chimera" + dots).withStyle(ChatFormatting.GRAY), cx, cy - 27 * PX, 1f, ARGB.color(alpha, 0xFFFFFFFF));
        }

        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(pop * PX, pop * PX);
        g.pose().translate(recoil * 3f, recoil * -1f);
        g.pose().rotate(recoil * 0.12f);
        drawInquisitor(g, font, t, alpha, afterShot && hit, sinceShot);
        g.pose().popMatrix();

        // Where the crosshair is: swaying over the heart, tightening, then snapping to where the bullet goes.
        float targetX = hit || !afterShot ? HEART_X : MISS_X;
        float targetY = hit || !afterShot ? HEART_Y : MISS_Y;
        float aimX, aimY;
        if (!afterShot) {
            float progress = Mth.clamp((t - POP_MS) / (float) (SHOT_MS - POP_MS), 0f, 1f);
            float sway = 11f * (1f - progress * 0.85f);
            aimX = HEART_X + Mth.sin(t / 170f) * sway + Mth.sin(t / 53f) * sway * 0.25f;
            aimY = HEART_Y + Mth.cos(t / 230f) * sway * 0.8f;
        } else {
            aimX = targetX;
            aimY = targetY;
        }
        float ax = cx + aimX * PX, ay = cy + aimY * PX;
        if (!afterShot || sinceShot < 500) drawCrosshair(g, ax, ay, t, ARGB.color(alpha * (afterShot ? 1f - sinceShot / 500f : 1f), 0xFFFF3030));

        // The gun, bottom right, kicking up when it fires.
        float gunX = w - 70f, gunY = h - 40f;
        float kick = afterShot ? Math.max(0f, 1f - sinceShot / 220f) : 0f;
        drawGun(g, gunX, gunY, kick, alpha);

        if (afterShot) {
            float muzzleX = gunX - 38f, muzzleY = gunY - 10f - kick * 8f;
            float impactX = cx + targetX * PX, impactY = cy + targetY * PX;
            // Muzzle flash and the bullet's trail.
            if (sinceShot < 90) {
                g.fill((int) muzzleX - 7, (int) muzzleY - 7, (int) muzzleX + 7, (int) muzzleY + 7, ARGB.color(alpha, 0xFFFFE070));
                g.fill((int) muzzleX - 4, (int) muzzleY - 4, (int) muzzleX + 4, (int) muzzleY + 4, ARGB.color(alpha, 0xFFFFFFFF));
            }
            if (sinceShot < 160) drawTracer(g, muzzleX, muzzleY, impactX, impactY, sinceShot / 160f, alpha);
            if (!hit) {
                // It flies on past the body.
                if (sinceShot < 300) drawTracer(g, impactX, impactY, impactX + (impactX - muzzleX) * 0.6f, impactY + (impactY - muzzleY) * 0.6f, sinceShot / 300f, alpha * 0.6f);
                drawPuff(g, impactX, impactY, sinceShot, alpha);
            } else {
                drawBurst(g, impactX, impactY, sinceShot, alpha);
            }
            // A white flash.
            if (sinceShot < 140) g.fill(0, 0, w, h, ARGB.color((1f - sinceShot / 140f) * 0.55f * alpha, 0xFFFFFFFF));
            drawResult(g, font, cx, cy + 22 * PX, sinceShot, alpha);
        }
    }

    /** The Inquisitor in figure pixels, chest at (0, 0). */
    private static void drawInquisitor(GuiGraphicsExtractor g, Font font, long t, float alpha, boolean shot, long sinceShot) {
        int hood = ARGB.color(alpha, 0xFF2A1238), robe = ARGB.color(alpha, 0xFF3F1D57), arm = ARGB.color(alpha, 0xFF32164A);
        int leg = ARGB.color(alpha, 0xFF1C0C27), trim = ARGB.color(alpha, 0xFFB8912E), face = ARGB.color(alpha, 0xFF120818);
        float breathe = Mth.sin(t / 260f) * 0.4f;

        // Shadow.
        g.fill(-9, 21, 9, 22, ARGB.color(alpha * 0.4f, 0xFF000000));
        // Legs.
        g.fill(-4, 8, 0, 21, leg);
        g.fill(0, 8, 4, 21, leg);
        // Robe.
        g.fill(-5, -10, 5, 9, robe);
        g.fill(-5, 7, 5, 9, trim);
        g.fill(-1, -10, 1, 7, trim);
        // Arms, raised a little as it breathes.
        g.pose().pushMatrix();
        g.pose().translate(0, breathe);
        g.fill(-9, -10, -5, 5, arm);
        g.fill(5, -10, 9, 5, arm);
        g.fill(-9, 4, -5, 6, trim);
        g.fill(5, 4, 9, 6, trim);
        g.pose().popMatrix();
        // Hood and face.
        g.fill(-5, -21, 5, -10, hood);
        g.fill(-3, -18, 3, -12, face);
        int eye = ARGB.color(alpha, (t / 400) % 5 == 0 ? 0xFF7A0000 : 0xFFFF2020);
        g.fill(-2, -16, -1, -15, eye);
        g.fill(1, -16, 2, -15, eye);
        // Crown.
        g.fill(-4, -23, 4, -21, trim);
        g.fill(-4, -25, -3, -23, trim);
        g.fill(-0, -26, 1, -23, trim);
        g.fill(3, -25, 4, -23, trim);

        // The heart, beating faster as the shot comes.
        if (!shot) {
            float speed = 180f - Math.min(120f, t / 20f);
            float beat = 1f + Math.max(0f, Mth.sin(t / speed)) * 0.25f;
            g.pose().pushMatrix();
            g.pose().translate(HEART_X, HEART_Y);
            g.pose().scale(0.55f * beat, 0.55f * beat);
            Component heart = Component.literal("❤");
            g.text(font, heart, -font.width(heart) / 2, -font.lineHeight / 2, ARGB.color(alpha, 0xFFFF2244), false);
            g.pose().popMatrix();
        } else {
            // A hole where the heart was.
            g.fill(-2, -7, 2, -3, ARGB.color(alpha, 0xFF050005));
            g.fill(-1, -8, 1, -2, ARGB.color(alpha, 0xFF050005));
        }

        // Nametag and health.
        g.pose().pushMatrix();
        g.pose().translate(0, -31);
        g.pose().scale(0.32f, 0.32f);
        Component name = Component.literal("✿ ").withStyle(ChatFormatting.DARK_GREEN)
            .append(Component.literal("Exalted Minos Inquisitor").withStyle(ChatFormatting.RED));
        g.text(font, name, -font.width(name) / 2, 0, ARGB.color(alpha, 0xFFFFFFFF), true);
        String hp = shot ? "0" : "40M";
        Component health = Component.literal(hp).withStyle(shot ? ChatFormatting.RED : ChatFormatting.GREEN)
            .append(Component.literal("/").withStyle(ChatFormatting.WHITE))
            .append(Component.literal("40M").withStyle(ChatFormatting.GREEN))
            .append(Component.literal("❤").withStyle(ChatFormatting.RED));
        g.text(font, health, -font.width(health) / 2, 11, ARGB.color(alpha, 0xFFFFFFFF), true);
        g.pose().popMatrix();
    }

    private static void drawCrosshair(GuiGraphicsExtractor g, float x, float y, long t, int colour) {
        int ix = Math.round(x), iy = Math.round(y);
        int gap = 4 + (int) (Mth.sin(t / 90f) * 1.5f + 1.5f);
        int len = 7;
        g.fill(ix - gap - len, iy - 1, ix - gap, iy + 1, colour);
        g.fill(ix + gap, iy - 1, ix + gap + len, iy + 1, colour);
        g.fill(ix - 1, iy - gap - len, ix + 1, iy - gap, colour);
        g.fill(ix - 1, iy + gap, ix + 1, iy + gap + len, colour);
        g.outline(ix - gap - 2, iy - gap - 2, (gap + 2) * 2, (gap + 2) * 2, colour);
        g.fill(ix, iy, ix + 1, iy + 1, colour);
    }

    /** A little pixel pistol pointing up and to the left. */
    private static void drawGun(GuiGraphicsExtractor g, float x, float y, float kick, float alpha) {
        g.pose().pushMatrix();
        g.pose().translate(x, y + kick * 6f);
        g.pose().rotate(-0.35f - kick * 0.35f);
        int metal = ARGB.color(alpha, 0xFF3A3F47), dark = ARGB.color(alpha, 0xFF1E2126), grip = ARGB.color(alpha, 0xFF6B4226), shine = ARGB.color(alpha, 0xFF8A93A0);
        g.fill(-44, -8, 6, 2, metal);
        g.fill(-44, -8, 6, -6, shine);
        g.fill(-48, -6, -44, 0, dark);
        g.fill(-4, 2, 8, 24, grip);
        g.fill(-2, 4, 6, 22, ARGB.color(alpha, 0xFF855533));
        g.fill(-14, 2, -6, 6, dark);
        g.fill(-12, 6, -8, 12, dark);
        g.pose().popMatrix();
    }

    /** A bright streak from one point to another, drawn as a rotated bar. */
    private static void drawTracer(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, float progress, float alpha) {
        float dx = x2 - x1, dy = y2 - y1;
        float len = Mth.sqrt(dx * dx + dy * dy);
        if (len < 1) return;
        float head = len * Math.min(1f, progress * 1.6f);
        float tail = len * Math.max(0f, progress * 1.6f - 0.6f);
        g.pose().pushMatrix();
        g.pose().translate(x1, y1);
        g.pose().rotate((float) Math.atan2(dy, dx));
        g.fill((int) tail, -1, (int) head, 1, ARGB.color(alpha * (1f - progress * 0.5f), 0xFFFFE9A0));
        g.fill((int) Math.max(tail, head - 6), -1, (int) head, 1, ARGB.color(alpha, 0xFFFFFFFF));
        g.pose().popMatrix();
    }

    /** The heart bursting: red bits flying out and falling. */
    private static void drawBurst(GuiGraphicsExtractor g, float x, float y, long since, float alpha) {
        float s = since / 1000f;
        for (int i = 0; i < 22; i++) {
            float angle = i * 2.39996f;
            float speed = 60f + (i * 37 % 50);
            float px = x + Mth.cos(angle) * speed * s;
            float py = y + Mth.sin(angle) * speed * s + 140f * s * s;
            float life = Math.max(0f, 1f - s / 1.4f);
            int size = 2 + i % 3;
            int colour = i % 4 == 0 ? 0xFFFF77AA : i % 3 == 0 ? 0xFF8B0000 : 0xFFFF2244;
            g.fill((int) px, (int) py, (int) px + size, (int) py + size, ARGB.color(alpha * life, colour));
        }
        // A ring spreading out.
        if (since < 400) {
            int r = (int) (4 + since / 12f);
            g.outline((int) x - r, (int) y - r, r * 2, r * 2, ARGB.color(alpha * (1f - since / 400f), 0xFFFF77AA));
        }
    }

    /** A puff of dust where the bullet went past. */
    private static void drawPuff(GuiGraphicsExtractor g, float x, float y, long since, float alpha) {
        float s = since / 1000f;
        for (int i = 0; i < 10; i++) {
            float angle = i * 2.39996f;
            float d = 6f + 22f * s;
            float px = x + Mth.cos(angle) * d, py = y + Mth.sin(angle) * d - 10f * s;
            float life = Math.max(0f, 1f - s / 0.9f);
            g.fill((int) px - 2, (int) py - 2, (int) px + 2, (int) py + 2, ARGB.color(alpha * life * 0.8f, 0xFFB0B0B0));
        }
    }

    private static void drawResult(GuiGraphicsExtractor g, Font font, float cx, float y, long since, float alpha) {
        float pop = since < 350 ? easeOutBack(since / 350f) : 1f;
        if (hit) {
            int rainbow = Mth.hsvToRgb((since / 900f) % 1f, 0.45f, 1f);
            Component big = Component.literal("CHIMERA!").withStyle(ChatFormatting.BOLD);
            float wobble = Mth.sin(since / 120f) * 0.05f;
            drawCentered(g, font, big, cx, y, 3.2f * pop + wobble, ARGB.color(alpha, ARGB.srgbLerp(0.5f, 0xFFFF55FF, rainbow)));
            drawCentered(g, font, Component.literal("Right through the heart").withStyle(ChatFormatting.LIGHT_PURPLE), cx, y + 16, 1.1f * pop, ARGB.color(alpha, 0xFFFFFFFF));
        } else {
            Component big = Component.literal("MISSED!").withStyle(ChatFormatting.BOLD);
            float droop = Math.min(1f, since / 900f);
            drawCentered(g, font, big, cx, y + droop * 3, 3.0f * pop, ARGB.color(alpha, 0xFFFF4040));
            drawCentered(g, font, Component.literal("No Chimera this time").withStyle(ChatFormatting.GRAY), cx, y + 16, 1.1f * pop, ARGB.color(alpha, 0xFFFFFFFF));
        }
    }

    private static void drawCentered(GuiGraphicsExtractor g, Font font, Component text, float x, float y, float scale, int colour) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, -font.lineHeight / 2, colour, true);
        g.pose().popMatrix();
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f;
        float v = x - 1f;
        return 1f + c3 * v * v * v + c1 * v * v;
    }
}
