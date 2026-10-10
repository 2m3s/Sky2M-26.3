package com.epic60869.sky2m.features.misc.crosshair;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs.Crosshair;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Custom Crosshair: the crosshair HUD layer is wrapped, so with this on the custom crosshair is drawn instead of
 * vanilla's, and with it off vanilla's runs untouched (no mixin). F1, spectator mode and the F3 3D crosshair are left to
 * vanilla. The attack indicator is drawn again from vanilla's sprites when it's set to Crosshair.
 * <p>
 * Ported from Killer560's Mod (crosshair/CustomCrosshairFeature), MIT License, Copyright (c) 2026 Killer560. The Image
 * style and the Valorant, CS2 and picture imports are Sky2M'.
 */
public final class CustomCrosshair {
    private static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve("sky2m").resolve("crosshairs");
    private static final Identifier ATTACK_FULL = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_full");
    private static final Identifier ATTACK_BACKGROUND = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_background");
    private static final Identifier ATTACK_PROGRESS = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_progress");
    private static final Identifier IMAGE_ID = Identifier.fromNamespaceAndPath("sky2m", "custom_crosshair");

    /** Smoothed movement spread, in crosshair units. */
    private static float spread;
    private static long lastNanos;
    private static String imageLoaded = "";
    private static long imageModified;
    private static int imageWidth, imageHeight;
    private static long imageCheckedAt;

    private CustomCrosshair() {}

    private static Crosshair config() {
        Sky2MConfig config = Sky2MConfig.current();
        return config == null ? null : config.misc.crosshair;
    }

    public static void init() {
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR,
            vanilla -> (graphics, delta) -> render(graphics, delta, vanilla));
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta, HudElement vanilla) {
        Crosshair c = config();
        Minecraft mc = Minecraft.getInstance();
        if (c == null || !c.enabled || mc.player == null
            || !com.epic60869.sky2m.features.core.Sky2MLocation.skyblockOnlyAllows()
            || (mc.gameMode != null && mc.gameMode.getPlayerMode() == GameType.SPECTATOR)
            || mc.debugEntries.isCurrentlyEnabled(DebugScreenEntries.THREE_DIMENSIONAL_CROSSHAIR)) {
            vanilla.extractRenderState(graphics, delta);
            return;
        }
        if (mc.gui.hud.isHidden()) return;
        if (!mc.options.getCameraType().isFirstPerson() && !c.thirdPerson) return;
        LocalPlayer player = mc.player;
        float dynamic = dynamicSpread(c, player, delta.getGameTimeDeltaPartialTick(false));

        int main = CrosshairCodes.argb(c.colour);
        if (c.colourOnEntity && mc.crosshairPickEntity != null) main = CrosshairCodes.argb(c.entityColour);
        else if (c.colourOnBlock && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) main = CrosshairCodes.argb(c.blockColour);
        int dot = c.separateDotColour ? CrosshairCodes.argb(c.dotColour) : main;

        var window = mc.getWindow();
        int guiScale = Math.max(1, (int) window.getGuiScale());
        float unit = (c.sizeMode == Crosshair.SizeMode.PIXELS ? 1f : guiScale) * c.scale;
        graphics.nextStratum();
        if (c.style == Crosshair.Style.IMAGE) {
            drawImage(graphics, c, window.getWidth() / 2f, window.getHeight() / 2f, guiScale, unit);
        } else {
            CrosshairRenderer.draw(graphics, c, window.getWidth() / 2f, window.getHeight() / 2f, guiScale, unit, dynamic,
                main, dot, CrosshairCodes.argb(c.outlineColour));
        }
        if (c.attackIndicator) attackIndicator(graphics, mc, player);
    }

    /** Movement spread (smoothed) plus the swing kick, in crosshair units. */
    private static float dynamicSpread(Crosshair c, LocalPlayer player, float partial) {
        long now = System.nanoTime();
        float dt = lastNanos == 0L ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        float target = 0f;
        Vec3 v = player.getDeltaMovement();
        boolean moving = v.x * v.x + v.z * v.z > 0.0004;
        if (c.spreadJumping && !player.onGround()) target = c.spreadAmount;
        if (c.spreadSprinting && moving && player.isSprinting()) target = c.spreadAmount;
        if (c.spreadMoving && moving) target = Math.max(target, c.spreadAmount * 0.5f);
        spread += (target - spread) * (1f - (float) Math.exp(-dt * 18f));
        if (Math.abs(spread - target) < 0.01f) spread = target;
        float total = spread;
        if (c.recoil) {
            float swing = player.getSwingAnimation(partial);
            if (swing > 0f) total += c.recoilAmount * (float) Math.sin(swing * Math.PI);
        }
        return total;
    }

    /** Vanilla's attack indicator under the crosshair: same sprites, place and rule. */
    private static void attackIndicator(GuiGraphicsExtractor graphics, Minecraft mc, LocalPlayer player) {
        if (mc.options.attackIndicator().get() != AttackIndicatorStatus.CROSSHAIR) return;
        float strength = player.getAttackStrengthScale(0f);
        boolean full = mc.crosshairPickEntity instanceof LivingEntity && strength >= 1f
            && player.getCurrentItemAttackStrengthDelay() > 5f && mc.crosshairPickEntity.isAlive();
        int y = graphics.guiHeight() / 2 - 7 + 16;
        int x = graphics.guiWidth() / 2 - 8;
        if (full) {
            graphics.blitSprite(RenderPipelines.CROSSHAIR, ATTACK_FULL, x, y, 16, 16);
        } else if (strength < 1f) {
            graphics.blitSprite(RenderPipelines.CROSSHAIR, ATTACK_BACKGROUND, x, y, 16, 4);
            graphics.blitSprite(RenderPipelines.CROSSHAIR, ATTACK_PROGRESS, 16, 4, 0, 0, x, y, (int) (strength * 17f), 4);
        }
    }

    // ------------------------------------------------------------------------------------------------ image

    private static void drawImage(GuiGraphicsExtractor graphics, Crosshair c, float centreX, float centreY, int guiScale, float unit) {
        if (!loadImage(c.imageFile)) return;
        int w = Math.max(1, Math.round(imageWidth * unit));
        int h = Math.max(1, Math.round(imageHeight * unit));
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.identity();
        pose.scale(1f / guiScale, 1f / guiScale);
        graphics.blit(RenderPipelines.GUI_TEXTURED, IMAGE_ID, Math.round(centreX - w / 2f), Math.round(centreY - h / 2f),
            0, 0, w, h, imageWidth, imageHeight, imageWidth, imageHeight);
        pose.popMatrix();
    }

    /** Loads the picture into the texture when it's changed (name or file); looks at the file once a second at most. */
    private static boolean loadImage(String name) {
        String wanted = name == null ? "" : name.trim();
        long now = System.currentTimeMillis();
        if (wanted.isEmpty()) return false;
        if (wanted.equals(imageLoaded) && now - imageCheckedAt < 1000) return imageWidth > 0;
        imageCheckedAt = now;
        Path file = FOLDER.resolve(wanted).normalize();
        if (!file.startsWith(FOLDER) || !Files.isRegularFile(file)) {
            imageLoaded = wanted;
            imageWidth = 0;
            return false;
        }
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (wanted.equals(imageLoaded) && modified == imageModified && imageWidth > 0) return true;
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(in);
                imageWidth = image.getWidth();
                imageHeight = image.getHeight();
                Minecraft.getInstance().getTextureManager().register(IMAGE_ID, new DynamicTexture(() -> "sky2m crosshair", image));
            }
            imageLoaded = wanted;
            imageModified = modified;
            return true;
        } catch (Exception e) {
            imageLoaded = wanted;
            imageWidth = 0;
            return false;
        }
    }

    public static void openFolder() {
        try {
            Files.createDirectories(FOLDER);
        } catch (Exception ignored) {}
        com.mojang.blaze3d.Blaze3D.openPath(FOLDER);
    }

    // ------------------------------------------------------------------------------------------------ import, export, presets

    public static void importFromClipboard() {
        Crosshair c = config();
        if (c == null) return;
        String text = Minecraft.getInstance().keyboardHandler.getClipboard().trim();
        if (text.isEmpty()) {
            message("Copy a crosshair code (or a PNG's path) first, then click Import.", 0xFF5555);
            return;
        }
        String kind = CrosshairCodes.apply(c, text);
        if (kind != null) {
            message("Imported your " + kind + " crosshair.", 0x55FF55);
            return;
        }
        // A picture: its path (as copied from Explorer, maybe in quotes), or a file already in the folder.
        String path = text.replaceAll("^\"|\"$", "");
        if (path.toLowerCase(Locale.ROOT).endsWith(".png")) {
            try {
                Files.createDirectories(FOLDER);
                Path source = Path.of(path);
                Path target = FOLDER.resolve(source.getFileName().toString());
                if (Files.isRegularFile(source) && !source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.isRegularFile(target)) {
                    c.style = Crosshair.Style.IMAGE;
                    c.imageFile = target.getFileName().toString();
                    imageLoaded = "";
                    message("Using " + c.imageFile + " as your crosshair.", 0x55FF55);
                    return;
                }
            } catch (Exception ignored) {}
        }
        message("That isn't a crosshair code Sky2M knows (Valorant, CS2 or KXH1) or a PNG.", 0xFF5555);
    }

    public static void exportToClipboard() {
        Crosshair c = config();
        if (c == null) return;
        Minecraft.getInstance().keyboardHandler.setClipboard(CrosshairCodes.export(c));
        message("Copied your crosshair code. Import it in Sky2M or Killer560's Mod.", 0x55FF55);
    }

    public static void applyPreset() {
        Crosshair c = config();
        if (c == null) return;
        resetLook(c);
        switch (c.preset) {
            case VANILLA -> {
                c.length = 7f;
                c.gap = 0f;
                c.dot = true;
                c.outline = false;
                c.invert = true;
            }
            case GREEN_PRO -> {
                c.colour = CrosshairCodes.colour(0xFF00FF00, false);
                c.length = 3f;
                c.gap = 1f;
            }
            case DOT -> {
                c.style = Crosshair.Style.DOT;
                c.dotSize = 2f;
                c.colour = CrosshairCodes.colour(0xFFFF3030, false);
            }
            case CIRCLE_DOT -> {
                c.style = Crosshair.Style.CIRCLE;
                c.dot = true;
                c.circleRadius = 5f;
                c.colour = CrosshairCodes.colour(0xFF00FFFF, false);
            }
            case T_SHAPE -> {
                c.style = Crosshair.Style.T_SHAPE;
                c.colour = CrosshairCodes.colour(0xFFFFFF55, false);
                c.length = 5f;
            }
            case X -> {
                c.style = Crosshair.Style.X;
                c.gap = 1.5f;
            }
            case VALORANT -> {
                c.colour = CrosshairCodes.colour(0xFF00FFFF, false);
                c.length = 2.5f;
                c.gap = 1.5f;
                c.dot = true;
                c.outlineColour = CrosshairCodes.colour(0xC0000000, false);
            }
            default -> {} // Classic: the defaults, a white cross with a black outline
        }
        message("Applied the " + c.preset + " crosshair.", 0x55FF55);
    }

    /** Every look setting back to its default (not Enabled, the preset picked, or the picture). */
    private static void resetLook(Crosshair c) {
        Crosshair defaults = new Crosshair();
        for (Field field : Crosshair.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getAnnotation(com.google.gson.annotations.Expose.class) == null) continue;
            String name = field.getName();
            if (name.equals("enabled") || name.equals("preset") || name.equals("imageFile")) continue;
            try {
                field.set(c, field.get(defaults));
            } catch (IllegalAccessException ignored) {}
        }
    }

    private static void message(String text, int colour) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(Component.literal(text).withColor(colour)));
    }
}
