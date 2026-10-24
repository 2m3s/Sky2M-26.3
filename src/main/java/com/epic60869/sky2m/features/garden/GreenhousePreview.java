package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /s2 greenhouse paste: like Skyblocker's greenhouse paste, but as a preview screen. Copy a layout link (or code) from
 * SkyMutations, run the command and the 10x10 greenhouse layout is shown, with the mutation spots (left empty for the
 * mutation to grow) outlined in gold. /s2 greenhouse opens the last pasted layout again.
 *
 * The SkyMutations code is an LZ-string (encoded URI component) JSON array of [x, z, "crop name", value] entries, where
 * value 0 marks a desired mutation spot and 1 a crop to plant.
 */
public final class GreenhousePreview {
    private static final int SIZE = 10;
    /** Layout [column][row] of crop names: "" = nothing, null never. Mutation spots are MUTATION. */
    private static String[][] layout;
    private static final String MUTATION = "\u0000mutation";

    private GreenhousePreview() {}

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("greenhouse")
                    .executes(c -> {
                        open();
                        return 1;
                    })
                    .then(ClientCommands.literal("paste").executes(c -> {
                        paste();
                        return 1;
                    }))));
            }
        });
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.farming.garden.greenhousePreview;
    }

    private static void message(String text, ChatFormatting colour) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Compat.PREFIX.get().append(Component.literal(text).withStyle(colour)));
    }

    private static void paste() {
        if (!enabled()) {
            message("Greenhouse Preview is off (Garden > Garden).", ChatFormatting.RED);
            return;
        }
        String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
        String[][] parsed = parse(extractCode(clipboard));
        if (parsed == null) {
            message("Copy a layout link from SkyMutations first; the clipboard isn't a greenhouse layout.", ChatFormatting.RED);
            return;
        }
        layout = parsed;
        open();
    }

    private static void open() {
        if (!enabled()) {
            message("Greenhouse Preview is off (Garden > Garden).", ChatFormatting.RED);
            return;
        }
        if (layout == null) {
            message("No layout yet: copy one from SkyMutations and run /s2 greenhouse paste.", ChatFormatting.RED);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new PreviewScreen()));
    }

    /** Pulls the code out of a link ("layout=..." or ".../share/...") or accepts a bare code. */
    static String extractCode(String clipboard) {
        String trimmed = clipboard == null ? "" : clipboard.strip();
        int i = trimmed.indexOf("layout=");
        if (i >= 0) return endOfCode(trimmed.substring(i + "layout=".length()));
        i = trimmed.indexOf("/share/");
        if (i >= 0) return endOfCode(trimmed.substring(i + "/share/".length()));
        return trimmed;
    }

    private static String endOfCode(String code) {
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean valid = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '=' || c == '+' || c == '$';
            if (!valid) return code.substring(0, i);
        }
        return code;
    }

    /** The layout grid [x][z], or null if the code isn't a SkyMutations layout. */
    private static String[][] parse(String encoded) {
        if (encoded.isEmpty()) return null;
        try {
            String json = decompressFromEncodedURIComponent(encoded);
            if (json == null || json.isEmpty()) return null;
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            String[][] grid = new String[SIZE][SIZE];
            for (String[] column : grid) java.util.Arrays.fill(column, "");
            for (JsonElement element : array) {
                JsonArray entry = element.getAsJsonArray();
                int x = entry.get(0).getAsInt();
                int z = entry.get(1).getAsInt();
                if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) continue;
                grid[x][z] = entry.get(3).getAsInt() == 0 ? MUTATION : entry.get(2).getAsString();
            }
            return grid;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- lz-string decompressFromEncodedURIComponent ----

    private static final String URI_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-$";

    private static final class Bits {
        final String input;
        int val;
        int position = 32;
        int index = 1;

        Bits(String input) {
            this.input = input;
            this.val = at(0);
        }

        int at(int i) {
            return i < input.length() ? Math.max(0, URI_ALPHABET.indexOf(input.charAt(i))) : 0;
        }

        int read(int n) {
            int bits = 0;
            for (int i = 0; i < n; i++) {
                int resb = val & position;
                position >>= 1;
                if (position == 0) {
                    position = 32;
                    val = at(index++);
                }
                bits |= (resb > 0 ? 1 : 0) << i;
            }
            return bits;
        }
    }

    static String decompressFromEncodedURIComponent(String input) {
        String s = input.replace(' ', '+');
        Bits b = new Bits(s);
        List<String> dict = new ArrayList<>();
        for (int i = 0; i < 3; i++) dict.add(String.valueOf(i));
        int enlargeIn = 4, dictSize = 4, numBits = 3;
        StringBuilder result = new StringBuilder();

        String c;
        switch (b.read(2)) {
            case 0 -> c = String.valueOf((char) b.read(8));
            case 1 -> c = String.valueOf((char) b.read(16));
            default -> {
                return "";
            }
        }
        dict.add(c);
        String w = c;
        result.append(c);

        while (true) {
            if (b.index > s.length() + 1) return null;
            int code = b.read(numBits);
            switch (code) {
                case 0 -> {
                    dict.add(String.valueOf((char) b.read(8)));
                    dictSize++;
                    code = dictSize - 1;
                    enlargeIn--;
                }
                case 1 -> {
                    dict.add(String.valueOf((char) b.read(16)));
                    dictSize++;
                    code = dictSize - 1;
                    enlargeIn--;
                }
                case 2 -> {
                    return result.toString();
                }
                default -> {}
            }
            if (enlargeIn == 0) {
                enlargeIn = 1 << numBits;
                numBits++;
            }
            String entry;
            if (code < dict.size() && dict.get(code) != null) entry = dict.get(code);
            else if (code == dictSize) entry = w + w.charAt(0);
            else return null;
            result.append(entry);
            dict.add(w + entry.charAt(0));
            dictSize++;
            enlargeIn--;
            w = entry;
            if (enlargeIn == 0) {
                enlargeIn = 1 << numBits;
                numBits++;
            }
        }
    }

    // ---- preview screen ----

    private static String[][] rotated(String[][] grid) {
        String[][] out = new String[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) out[SIZE - 1 - z][x] = grid[x][z];
        }
        return out;
    }

    private static String[][] mirrored(String[][] grid) {
        String[][] out = new String[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) out[SIZE - 1 - x][z] = grid[x][z];
        }
        return out;
    }

    private static final class PreviewScreen extends Screen {
        private static final int CELL = 22;

        PreviewScreen() {
            super(Component.literal("Greenhouse Preview"));
        }

        private int left() {
            return width / 2 - CELL * SIZE / 2;
        }

        private int top() {
            return height / 2 - CELL * SIZE / 2 - 4;
        }

        @Override
        protected void init() {
            int y = top() + CELL * SIZE + 8;
            int x = width / 2 - 123;
            addRenderableWidget(Button.builder(Component.literal("Rotate left"), b -> layout = rotated(rotated(rotated(layout))))
                .bounds(x, y, 80, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Rotate right"), b -> layout = rotated(layout))
                .bounds(x + 83, y, 80, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Mirror"), b -> layout = mirrored(layout))
                .bounds(x + 166, y, 80, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 40, y + 24, 80, 20).build());
        }

        private static int colour(String name) {
            float hue = (name.hashCode() & 0x7FFFFFFF) % 360 / 360f;
            return 0xFF000000 | java.awt.Color.HSBtoRGB(hue, 0.55f, 0.6f) & 0xFFFFFF;
        }

        private static String abbreviation(String name) {
            String[] words = name.trim().split("\\s+");
            if (words.length >= 2) return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase();
            return name.length() <= 2 ? name : name.substring(0, 2);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            super.extractRenderState(g, mouseX, mouseY, delta);
            int left = left();
            int top = top();
            g.text(font, "Greenhouse Preview (top = north)", width / 2 - font.width("Greenhouse Preview (top = north)") / 2, top - 14, 0xFFFFFFFF, true);
            g.fill(left - 2, top - 2, left + CELL * SIZE + 2, top + CELL * SIZE + 2, 0xD0101010);
            String hovered = null;
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    int cx = left + x * CELL;
                    int cy = top + z * CELL;
                    String name = layout[x][z];
                    g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, 0xFF2A2A2A);
                    if (name.equals(MUTATION)) {
                        g.outline(cx + 1, cy + 1, CELL - 2, CELL - 2, 0xFFFFAA00);
                    } else if (!name.isEmpty()) {
                        g.fill(cx + 1, cy + 1, cx + CELL - 1, cy + CELL - 1, colour(name));
                        String label = abbreviation(name);
                        g.text(font, label, cx + CELL / 2 - font.width(label) / 2, cy + CELL / 2 - 4, 0xFFFFFFFF, true);
                    }
                    if (mouseX >= cx && mouseX < cx + CELL && mouseY >= cy && mouseY < cy + CELL) {
                        hovered = name.equals(MUTATION) ? "Mutation spot (leave empty)" : name.isEmpty() ? null : name;
                    }
                }
            }
            if (hovered != null) {
                g.setComponentTooltipForNextFrame(font, List.of(Component.literal(hovered)), mouseX, mouseY);
            }
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
