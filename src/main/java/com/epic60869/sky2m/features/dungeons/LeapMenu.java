package com.epic60869.sky2m.features.dungeons;

import com.mojang.blaze3d.platform.InputConstants;
import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonClass;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonPlayerManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces the Spirit Leap / Infinileap menu with four boxes, one per teammate, placed and coloured by class.
 * Layout, sizes and sorting follow Odin's LeapMenu (https://github.com/odtheking/Odin, BSD-3-Clause).
 */
public final class LeapMenu {
    private static final int BOX_WIDTH = 200;
    private static final int BOX_HEIGHT = 75;
    private static final int GAP = 24;
    private static final Pattern LEAPED = Pattern.compile("^You have teleported to (\\w{1,16})!$");

    private record Teammate(String name, DungeonClass dungeonClass, boolean alive) {}

    private LeapMenu() {}

    public static void init() {
        // Keybinds: press a box's key to leap to that teammate.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
                if (!isActive(container)) return true;
                int quadrant = quadrantFor(event.key());
                if (quadrant < 0) return true;
                leapTo(container, quadrant);
                return false;
            });
        });
        Sky2MChat.onChat(message -> {
            FeatureConfigs.LeapMenu config = config();
            if (config == null || !config.announce || !Sky2MLocation.inDungeon()) return;
            Matcher m = LEAPED.matcher(message.text());
            if (m.matches()) {
                var connection = Minecraft.getInstance().getConnection();
                if (connection != null) connection.sendCommand("pc Leaped to " + m.group(1) + "!");
            }
        });
    }

    private static FeatureConfigs.LeapMenu config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.leapMenu;
    }

    /** Whether the leap menu replaces this screen. */
    public static boolean isActive(AbstractContainerScreen<?> screen) {
        FeatureConfigs.LeapMenu config = config();
        if (config == null || !config.enabled || !Sky2MLocation.inDungeon()) return false;
        String title = screen.getTitle().getString();
        if (!title.equals("Spirit Leap") && !title.equals("Teleport to Player")) return false;
        for (Teammate t : layout()) if (t != null) return true;
        return false;
    }

    /** Teammates (without you) sorted into the four corners: top left, top right, bottom left, bottom right. */
    private static Teammate[] layout() {
        FeatureConfigs.LeapMenu config = config();
        Teammate[] result = new Teammate[4];
        if (config == null) return result;
        var self = Minecraft.getInstance().player;
        String selfName = self == null ? "" : self.getGameProfile().name();

        List<Teammate> players = new ArrayList<>();
        for (DungeonPlayerManager.DungeonPlayer p : DungeonPlayerManager.getPlayers()) {
            if (p == null || p.name().equals(selfName)) continue;
            players.add(new Teammate(p.name(), p.dungeonClass(), p.alive()));
        }

        // Odin's sorting: each class takes its chosen corner, extras fill the free ones.
        List<Teammate> secondRound = new ArrayList<>();
        for (Teammate t : players) {
            int corner = corner(config, t.dungeonClass()).ordinal();
            if (result[corner] == null) result[corner] = t;
            else secondRound.add(t);
        }
        for (int i = 0; i < 4 && !secondRound.isEmpty(); i++) {
            if (result[i] == null) result[i] = secondRound.removeFirst();
        }
        return result;
    }

    private static FeatureConfigs.LeapCorner corner(FeatureConfigs.LeapMenu config, DungeonClass dungeonClass) {
        return switch (dungeonClass) {
            case ARCHER -> config.archerCorner;
            case BERSERK -> config.berserkCorner;
            case HEALER -> config.healerCorner;
            case MAGE -> config.mageCorner;
            case TANK -> config.tankCorner;
            default -> FeatureConfigs.LeapCorner.BOTTOM_RIGHT;
        };
    }

    /** The class's Leap Menu colour (opaque ARGB), also used by the teammate highlight. */
    public static int classColour(DungeonClass dungeonClass) {
        FeatureConfigs.LeapMenu config = config();
        return config == null ? 0xFFFFFFFF : color(config, dungeonClass);
    }

    private static int color(FeatureConfigs.LeapMenu config, DungeonClass dungeonClass) {
        String value = switch (dungeonClass) {
            case ARCHER -> config.archerColor;
            case BERSERK -> config.berserkColor;
            case HEALER -> config.healerColor;
            case MAGE -> config.mageColor;
            case TANK -> config.tankColor;
            default -> "0:255:255:255:255";
        };
        try {
            return ARGB.opaque(com.epic60869.sky2m.custom.util.ChromaColours.parse(value).getEffectiveColourRGB());
        } catch (Exception e) {
            return 0xFFFFFFFF;
        }
    }

    public static void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        FeatureConfigs.LeapMenu config = config();
        if (config == null) return;
        Minecraft mc = Minecraft.getInstance();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int halfW = width / 2;
        int halfH = height / 2;
        graphics.fill(0, 0, width, height, 0x80000000);

        float scale = Math.min(1f, Math.min((halfW - GAP - 4) / (float) BOX_WIDTH, (halfH - GAP - 4) / (float) BOX_HEIGHT));
        Teammate[] teammates = layout();
        for (int i = 0; i < 4; i++) {
            Teammate t = teammates[i];
            if (t == null) continue;
            int col = i % 2, row = i / 2;
            boolean hovered = (col == 0 ? mouseX < halfW : mouseX >= halfW) && (row == 0 ? mouseY < halfH : mouseY >= halfH);
            float grow = hovered ? 1.05f : 1f;
            int classColor = color(config, t.dungeonClass());

            graphics.pose().pushMatrix();
            graphics.pose().translate(col == 0 ? halfW - GAP : halfW + GAP, row == 0 ? halfH - GAP : halfH + GAP);
            graphics.pose().scale(scale * grow, scale * grow);
            int x = col == 0 ? -BOX_WIDTH : 0;
            int y = row == 0 ? -BOX_HEIGHT : 0;

            int background = config.coloredBoxes ? ARGB.color(200, classColor) : 0xC0262626;
            graphics.fill(x, y, x + BOX_WIDTH, y + BOX_HEIGHT, background);
            graphics.outline(x, y, BOX_WIDTH, BOX_HEIGHT, hovered ? 0xFFFFFFFF : classColor);

            int face = (int) (BOX_HEIGHT * 0.76);
            PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(t.name());
            if (info != null) PlayerFaceExtractor.extractRenderState(graphics, info.getSkin(), x + 9, y + 9, face);

            int textColor = config.coloredBoxes ? 0xFF1A1A1A : classColor;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + 15 + face, y + BOX_HEIGHT / 2.5f);
            graphics.pose().scale(1.5f, 1.5f);
            graphics.text(mc.font, t.name(), 0, 0, textColor, !config.coloredBoxes);
            graphics.pose().popMatrix();
            graphics.text(mc.font, t.alive() ? t.dungeonClass().displayName() : "DEAD",
                x + 15 + face, y + (int) (BOX_HEIGHT / 1.55), t.alive() ? 0xFFFFFFFF : 0xFFFF5555, true);
            // The key that leaps here, in the corner of the box.
            String key = keyName(i);
            if (!key.isEmpty()) {
                String label = "[" + key + "]";
                graphics.text(mc.font, label, x + BOX_WIDTH - 6 - mc.font.width(label), y + 6, config.coloredBoxes ? 0xFF1A1A1A : 0xFFAAAAAA, !config.coloredBoxes);
            }
            graphics.pose().popMatrix();
        }
        graphics.centeredText(mc.font, Component.literal("Spirit Leap").withStyle(ChatFormatting.GRAY), halfW, 6, 0xFFFFFFFF);
    }

    /** Which box a key leaps to (0 top left, 1 top right, 2 bottom left, 3 bottom right), or -1. */
    private static int quadrantFor(int key) {
        FeatureConfigs.LeapMenu config = config();
        if (config == null || key == InputConstants.UNKNOWN.getValue()) return -1;
        if (key == config.keyTopLeft) return 0;
        if (key == config.keyTopRight) return 1;
        if (key == config.keyBottomLeft) return 2;
        if (key == config.keyBottomRight) return 3;
        return -1;
    }

    private static String keyName(int quadrant) {
        FeatureConfigs.LeapMenu config = config();
        if (config == null) return "";
        int key = switch (quadrant) {
            case 0 -> config.keyTopLeft;
            case 1 -> config.keyTopRight;
            case 2 -> config.keyBottomLeft;
            default -> config.keyBottomRight;
        };
        if (key == InputConstants.UNKNOWN.getValue()) return "";
        String name = com.epic60869.sky2m.custom.util.Input.keyName(key);
        return name == null ? "" : name.toUpperCase(java.util.Locale.ROOT);
    }

    /** Leaps to the teammate in the clicked quadrant. */
    public static void click(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        int quadrant = (mouseY >= screen.height / 2.0 ? 2 : 0) + (mouseX >= screen.width / 2.0 ? 1 : 0);
        leapTo(screen, quadrant);
    }

    private static void leapTo(AbstractContainerScreen<?> screen, int quadrant) {
        Teammate t = layout()[quadrant];
        if (t == null) return;
        if (!t.alive()) {
            Sky2MAlerts.chat(Component.literal("This player is dead, you can not leap to them.").withStyle(ChatFormatting.RED));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory() || !slot.getItem().is(Items.PLAYER_HEAD)) continue;
            String name = ChatFormatting.stripFormatting(slot.getItem().getHoverName().getString()).trim();
            String last = name.substring(name.lastIndexOf(' ') + 1);
            if (!last.equalsIgnoreCase(t.name())) continue;
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, 0, ContainerInput.PICKUP, mc.player);
            Sky2MAlerts.chat(Component.literal("Teleporting to " + t.name() + ".").withStyle(ChatFormatting.GRAY));
            return;
        }
    }
}
