package com.epic60869.sky2m;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.Random;

/**
 * The Foxy jumpscare: a pixel-art pirate fox animatronic (eyepatch, hook, torn fur over a metal endoskeleton) sprints
 * in from the left of a dark room, lunges at the screen with its jaw dropping open, then the screen cuts to static.
 * It's drawn over the HUD and any open menu without taking input, so you can keep moving and clicking through it.
 */
public final class Sky2MFoxyScare {
    private static final long RUN_MS = 270L;
    private static final long LUNGE_MS = 690L;
    private static final long END_MS = 1000L;

    // His head, then the lower jaw that drops away from it. One character per pixel.
    private static final String[] HEAD = {
        "...rR......................Rr...",
        "...rRR....................RRr...",
        "...rRER..................RE.r...",
        "...rREER................REEMr...",
        "...rREERR..............RREERr...",
        "...rREEERR............RREEERr...",
        "..rRREEERRRRRRRRRRRRRRRREPPPRr..",
        "..rRRRERRRRRRRRRRRRPPPPPPERRRr..",
        ".rRRRRRRRRRRRPPPPPPRRRRRRRRRRRr.",
        ".rRRRRRRRRPPPRRRRRRRRRRRRRRRRRr.",
        ".rRPPPPPPPRRRRRRRRRRRRRKKKKKRRr.",
        ".rRPPPPPPPRRRRRRRRRRRRKKYYYKKRr.",
        ".rRPPPPPPPRRRRRRRRRRRRKYYKYYKRr.",
        ".rRPPPPPPPRRRRRRRRRRRRKYKKKYKRr.",
        ".rRPPPPPPPRRRRRRRRRRRRKKYYYKKRr.",
        ".rRPPPPPPPRRRRRRRRRRRRRKKKKKRRr.",
        ".rRRRRRRRRRTTTTTTTTTTRRRRRmMMRr.",
        ".rRRRRRRRRTTTTNNNNTTTTRRRmMMmRr.",
        ".rrRRRRRRTTTTTNNNNTTTTTRRMMmRrr.",
        "..rRRRRRRTTTTTTTTTTTTTTRRRmMRr..",
        "..rrRRRRTTTTTTTTTTTTTTTTRRRRrr..",
        "...rrRRRtTTTTTTTTTTTTTTtRRRrr...",
        "....rrRRtWTWTWTWWTWTWTWtRRrr....",
        ".....rrrKWKWKWKWWKWKWKWKrrr.....",
    };
    private static final String[] JAW = {
        ".......rtWKWKWKWWKWKWKWtr.......",
        ".......rtTWTWTWTTWTWTWTtr.......",
        "........rtTTTTTTTTTTTTtr........",
        ".........rtTTTTTTTTTTtr.........",
        "..........rrttttttttrr..........",
        "...........rrrrrrrrrr...........",
    };
    private static final String[] HOOK = {
        "..MMMM..",
        ".M....M.",
        "M......M",
        "M.......",
        "M.......",
        ".M......",
        "..MMm...",
        "...mm...",
        "..rRRr..",
        ".rRRRRr.",
        ".rRRRRr.",
        ".rRRRRr.",
    };
    private static final Map<Character, Integer> PALETTE = Map.ofEntries(
        Map.entry('R', 0xFFA3321F),  // fur
        Map.entry('r', 0xFF5E1A12),  // fur shade
        Map.entry('E', 0xFF3A1414),  // inside the ears
        Map.entry('T', 0xFFC9A06E),  // muzzle
        Map.entry('t', 0xFF8C6A45),  // muzzle shade
        Map.entry('N', 0xFF1B1212),  // nose
        Map.entry('K', 0xFF0A0707),  // eye socket, mouth
        Map.entry('Y', 0xFFF4C51C),  // eye
        Map.entry('P', 0xFF121012),  // eyepatch
        Map.entry('W', 0xFFE9E2CC),  // teeth
        Map.entry('M', 0xFF8A8D93),  // endoskeleton, hook
        Map.entry('m', 0xFF45474C)   // endoskeleton shade
    );

    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath("sky2m", "foxy");
    private static final Random random = new Random();
    private static long startedAt = -1L;
    private static int width;
    private static int height;

    private Sky2MFoxyScare() {}

    public static void init() {
        HudElementRegistry.addLast(HUD_ID, (graphics, delta) -> {
            // The HUD is drawn under menus; with one open it's drawn after the menu instead.
            if (Minecraft.getInstance().gui.screen() == null) render(graphics);
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> render(graphics)));
    }

    public static void start() {
        startedAt = System.currentTimeMillis();
    }

    public static boolean isPlaying() {
        return startedAt >= 0 && System.currentTimeMillis() - startedAt < END_MS;
    }

    private static void render(GuiGraphicsExtractor g) {
        if (!isPlaying()) return;
        long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
        width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        height = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        // A dark office with a flickering light.
        boolean flicker = random.nextInt(6) == 0;
        g.fill(0, 0, width, height, flicker ? 0xFF15161C : 0xFF07080C);

        if (elapsed < LUNGE_MS) {
            drawFoxy(g, elapsed);
        } else {
            drawStatic(g, elapsed);
        }
    }

    private static void drawFoxy(GuiGraphicsExtractor g, long elapsed) {
        float centreX;
        float scale; // his head's height as a share of the screen height
        float jawOpen;
        float shake;
        if (elapsed < RUN_MS) {
            // Sprinting in from the left, bobbing with each stride.
            float t = elapsed / (float) RUN_MS;
            centreX = width * (-0.25F + 0.75F * easeOut(t));
            scale = 0.45F + 0.45F * t;
            jawOpen = 0;
            shake = 0;
        } else {
            // At the screen: lunging closer, jaw dropping, everything shaking.
            float t = (elapsed - RUN_MS) / (float) (LUNGE_MS - RUN_MS);
            centreX = width / 2F;
            scale = 0.9F + 0.55F * easeOut(t);
            jawOpen = Math.min(1F, t * 2.2F);
            shake = 2F + 10F * t;
        }
        float bob = elapsed < RUN_MS ? (float) Math.abs(Math.sin(elapsed * 0.045D)) * height * 0.04F : 0;

        int rows = HEAD.length + JAW.length + 8;
        int px = Math.max(1, Math.round(height * scale / rows));
        int gap = Math.round(jawOpen * 8);
        int shakeX = shake > 0 ? random.nextInt(Math.round(shake) * 2 + 1) - Math.round(shake) : 0;
        int shakeY = shake > 0 ? random.nextInt(Math.round(shake) * 2 + 1) - Math.round(shake) : 0;
        int left = Math.round(centreX) - HEAD[0].length() * px / 2 + shakeX;
        int top = height / 2 - (HEAD.length + gap + JAW.length) * px / 2 - Math.round(bob) + shakeY;

        drawSprite(g, HEAD, left, top, px, elapsed);
        int jawTop = top + (HEAD.length + gap) * px;
        if (gap > 0) {
            // Inside the open mouth: darkness, with the metal jaw hinges at the sides.
            g.fill(left + 8 * px, top + HEAD.length * px, left + 24 * px, jawTop, 0xFF050303);
            g.fill(left + 8 * px, top + HEAD.length * px, left + 9 * px, jawTop, PALETTE.get('m'));
            g.fill(left + 23 * px, top + HEAD.length * px, left + 24 * px, jawTop, PALETTE.get('m'));
        }
        drawSprite(g, JAW, left, jawTop, px, elapsed);

        // His hook swings up from the bottom right as he lunges.
        if (elapsed > RUN_MS / 2) {
            float t = Math.min(1F, (elapsed - RUN_MS / 2F) / (LUNGE_MS - RUN_MS / 2F));
            int hookPx = Math.round(px * 1.2F);
            int hookX = left + (HEAD[0].length() - 2) * px;
            int hookY = height - Math.round(t * HOOK.length * hookPx * 1.1F);
            drawSprite(g, HOOK, hookX, hookY, hookPx, elapsed);
        }
    }

    private static void drawSprite(GuiGraphicsExtractor g, String[] sprite, int left, int top, int px, long elapsed) {
        // His eye glints between yellow and a hot orange.
        boolean glint = ((elapsed / 70L) & 1L) == 0L;
        for (int row = 0; row < sprite.length; row++) {
            String line = sprite[row];
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (c == '.') continue;
                int colour = c == 'Y' && glint ? 0xFFFF8A1C : PALETTE.get(c);
                int x = left + col * px;
                int y = top + row * px;
                g.fill(x, y, x + px, y + px, colour);
            }
        }
    }

    /** Static after the scare, like the cameras cutting out. */
    private static void drawStatic(GuiGraphicsExtractor g, long elapsed) {
        int block = 3;
        for (int y = 0; y < height; y += block) {
            for (int x = 0; x < width; x += block) {
                int v = random.nextInt(170);
                g.fill(x, y, x + block, y + block, 0xFF000000 | v << 16 | v << 8 | v);
            }
        }
        // Rolling dark bars.
        int bar = (int) ((elapsed / 4L) % Math.max(1, height));
        g.fill(0, bar, width, Math.min(height, bar + height / 10), 0x88000000);
    }

    private static float easeOut(float t) {
        return 1F - (1F - t) * (1F - t);
    }
}
