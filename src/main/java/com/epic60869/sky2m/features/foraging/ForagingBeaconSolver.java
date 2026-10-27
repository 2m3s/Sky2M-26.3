package com.epic60869.sky2m.features.foraging;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Moonglade Beacon solver (SkyHanni's ForagingBeaconSolver, LGPL-2.1): in the Tune Frequency and Upgrade Signal
 * Strength menus it reads the reference colour, speed and pitch, then highlights the controls that already match and
 * labels the others with how many clicks they are off. The reference speed is measured from how fast the reference
 * slot moves, the reference pitch from the bass note block sounds.
 */
public final class ForagingBeaconSolver {
    private static final Pattern MENU = Pattern.compile("Upgrade Signal Strength|Tune Frequency");
    private static final Pattern CURRENT_COLOR = Pattern.compile("Current color: (?<v>[\\w ]+)");
    private static final Pattern CURRENT_SPEED = Pattern.compile("Current speed: (?<v>\\d+)");
    private static final Pattern CURRENT_PITCH = Pattern.compile("Current pitch: (?<v>\\w+)");

    /** The order of the beacon's colours, which is also the order of the stained glass panes. */
    private static final String[] COLORS = {"White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink", "Cyan",
        "Purple", "Blue", "Brown", "Green", "Red"};
    /** Server ticks per slot move for speeds 1 to 5. */
    private static final int[] SPEED_TICKS = {52, 42, 32, 22, 12};
    private static final String[] PITCHES = {"Low", "Normal", "High"};
    private static final float[] PITCH_VALUES = {0.0952381f, 0.7936508f, 1.4920635f};

    private static final int MATCH_FIRST = 10;
    private static final int MATCH_LAST = 16;
    private static final int CHANGE_FIRST = 28;
    private static final int CHANGE_LAST = 34;
    private static final int COLOR_SLOT = 46;
    private static final int SPEED_SLOT = 48;
    private static final int PITCH_SLOT = 50;
    private static final int PAUSE_SLOT = 52;

    private static final long PITCH_MARGIN_MS = 50;
    private static final int GREEN = 0xC855FF55;

    private static boolean inMenu;
    private static boolean upgradingStrength;
    private static int serverTicks;
    private static ItemStack[] seen = new ItemStack[0];
    private static Tune normal = new Tune(false);
    private static Tune enchanted = new Tune(true);

    private ForagingBeaconSolver() {}

    private static FeatureConfigs.Foraging config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.foraging;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(ForagingBeaconSolver::tick);
        ClientTickEvents.END_CLIENT_TICK.register(ForagingBeaconSolver::tickReady);
    }

    // ---------------------------------------------------------------------------------------------- ready alert

    /*
     * Beacon Ready alert, a port of SkyHanni's ForagingBeaconWarning (hannibal002/SkyHanni, LGPL-2.1): the Moonglade
     * Beacon widget in the tab list shows "Cooldown: AVAILABLE" once it can be used again.
     */
    private static final Pattern COOLDOWN = Pattern.compile("^\\s*Cooldown: (?<v>.+)$");
    private static boolean wasAvailable = true;
    private static int readyTicks;

    private static void tickReady(Minecraft mc) {
        if (mc.player == null || mc.getConnection() == null || ++readyTicks % 20 != 0) return;
        FeatureConfigs.Foraging c = config();
        if (c == null || !c.beaconReadyAlert || !Sky2MLocation.onSkyblock()) return;
        boolean inWidget = false;
        Boolean available = null;
        for (net.minecraft.client.multiplayer.PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
            String line = com.epic60869.sky2m.sim.util.TabText.plain(info);
            if (line == null) continue;
            String text = Sky2MLocation.strip(line).trim();
            if (text.contains("Moonglade Beacon")) {
                inWidget = true;
                continue;
            }
            if (!inWidget) continue;
            Matcher m = COOLDOWN.matcher(text);
            if (m.matches()) {
                available = m.group("v").trim().equalsIgnoreCase("AVAILABLE");
                break;
            }
        }
        // Without the widget nothing is known: don't alert.
        if (available == null) return;
        if (available && !wasAvailable) {
            com.epic60869.sky2m.features.core.Sky2MAlerts.title(Component.literal("§aBeacon Ready"), Component.literal("§7Your Moonglade Beacon cooldown is over"));
            com.epic60869.sky2m.features.core.Sky2MAlerts.chat(Component.literal("§aYour Moonglade Beacon is ready to use."));
        }
        wasAvailable = available;
    }

    private static boolean active() {
        FeatureConfigs.Foraging c = config();
        return inMenu && c != null && c.beaconSolver;
    }

    // ---------------------------------------------------------------------------------------------- state

    private static void tick(Minecraft mc) {
        boolean wasIn = inMenu;
        boolean now = false;
        if (mc.player != null && Sky2MLocation.onSkyblock() && mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            Matcher m = MENU.matcher(Sky2MLocation.strip(screen.getTitle().getString()).trim());
            if (m.matches()) {
                now = true;
                upgradingStrength = m.group().startsWith("Upgrade");
            }
        }
        inMenu = now;
        if (now && !wasIn) {
            serverTicks = 0;
            normal = new Tune(false);
            enchanted = new Tune(true);
            seen = new ItemStack[0];
        } else if (!now && wasIn) {
            normal = new Tune(false);
            enchanted = new Tune(true);
            seen = new ItemStack[0];
            return;
        }
        if (!active()) return;
        serverTicks++;
        var menu = ((AbstractContainerScreen<?>) mc.gui.screen()).getMenu();
        if (seen.length != menu.slots.size()) seen = Arrays.copyOf(seen, menu.slots.size());
        // Slot updates arrive as new stacks, so a changed stack is a slot update.
        for (Slot slot : menu.slots) {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack == seen[slot.index]) continue;
            seen[slot.index] = stack;
            if (stack.isEmpty()) continue;
            (stack.hasFoil() ? enchanted : normal).readSlot(slot.index, stack);
        }
    }

    /** Called for every sound the server plays to you; only the note block bass notes of the menu matter. */
    public static void onSound(String name, float pitch) {
        if (!active() || !name.equals("block.note_block.bass")) return;
        int p = -1;
        for (int i = 0; i < PITCH_VALUES.length; i++) if (Math.abs(PITCH_VALUES[i] - pitch) < 1e-4f) p = i;
        if (p < 0) return;
        if (!upgradingStrength) {
            normal.handlePitch(p);
            return;
        }
        // Two beacons play at once: give the note to the set whose reference note is due.
        Tune best = null;
        for (Tune t : new Tune[] {normal, enchanted}) {
            if (t.untilRef() <= PITCH_MARGIN_MS && t.untilOurs() > t.untilRef() && (best == null || t.untilRef() < best.untilRef())) best = t;
        }
        if (best != null) best.handlePitch(p);
    }

    // ---------------------------------------------------------------------------------------------- clicks

    /** True to cancel the click. Left clicks become middle clicks, and clicking an already correct control is blocked. */
    public static boolean onSlotClicked(AbstractContainerScreen<?> screen, Slot slot, ContainerInput input, int button) {
        if (!active() || slot == null || slot.container instanceof net.minecraft.world.entity.player.Inventory) return false;
        FeatureConfigs.Foraging c = config();
        Minecraft mc = Minecraft.getInstance();
        Integer offset = normal.offsetBySlot(slot.index);
        if (offset == null && upgradingStrength) offset = enchanted.offsetBySlot(slot.index);
        if (c.beaconPreventOverClicking && offset != null && offset == 0 && !ctrlDown(mc)) {
            Component msg = Component.literal("§cOver-click prevented §7(hold §eControl§7 to bypass)");
            mc.gui.hud.setOverlayMessage(msg, false);
            return true;
        }
        if (c.beaconMiddleClick && input == ContainerInput.PICKUP && button == 0 && mc.gameMode != null) {
            mc.gameMode.handleContainerInput(screen.getMenu().containerId, slot.index, 2, ContainerInput.CLONE, mc.player);
            return true;
        }
        return false;
    }

    private static boolean ctrlDown(Minecraft mc) {
        return InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
            || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
    }

    // ---------------------------------------------------------------------------------------------- rendering

    /** Behind the slot's item: the controls that already match the reference. */
    public static void renderSlotBackground(GuiGraphicsExtractor g, Slot slot) {
        if (!active() || slot.container instanceof net.minecraft.world.entity.player.Inventory || !slot.hasItem()) return;
        if (normal.shouldHighlight(slot) || enchanted.shouldHighlight(slot)) g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, GREEN);
    }

    /** On the slot: how many clicks the control is off by. */
    public static void renderSlotLabel(GuiGraphicsExtractor g, Slot slot) {
        if (!active() || slot.container instanceof net.minecraft.world.entity.player.Inventory || !slot.hasItem()) return;
        Integer offset = normal.label(slot.index);
        if (offset == null) offset = enchanted.label(slot.index);
        if (offset == null) return;
        String text = String.valueOf(offset);
        var font = Minecraft.getInstance().font;
        g.pose().pushMatrix();
        g.pose().translate(slot.x + 17, slot.y + 17);
        g.text(font, text, -font.width(text), -8, 0xFF55FF55, true);
        g.pose().popMatrix();
    }

    /** The reference panel, left of the menu (the pose is already at the menu's corner). */
    public static void renderPanel(GuiGraphicsExtractor g) {
        if (!active()) return;
        List<String> lines = new ArrayList<>();
        normal.addLines(lines);
        if (upgradingStrength) enchanted.addLines(lines);
        var font = Minecraft.getInstance().font;
        int width = 0;
        for (String l : lines) width = Math.max(width, font.width(l));
        int x = -width - 8;
        int y = 0;
        g.fill(x - 3, y - 3, x + width + 3, y + lines.size() * 10 + 1, 0xA0000000);
        for (String l : lines) {
            g.text(font, l, x, y, 0xFFFFFFFF, true);
            y += 10;
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        List<String> out = new ArrayList<>();
        if (lore != null) for (Component line : lore.lines()) out.add(Sky2MLocation.strip(line.getString()).trim());
        return out;
    }

    private static String loreValue(ItemStack stack, Pattern pattern) {
        for (String line : lore(stack)) {
            Matcher m = pattern.matcher(line);
            if (m.find()) return m.group("v").trim();
        }
        return null;
    }

    /** The pane's colour index, or -1. */
    private static int paneColor(ItemStack stack) {
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (!path.endsWith("_stained_glass_pane")) return -1;
        String name = path.substring(0, path.length() - "_stained_glass_pane".length());
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i].toLowerCase(Locale.ROOT).replace(' ', '_').equals(name)) return i;
        return -1;
    }

    private static int indexOf(String[] names, String name) {
        if (name == null) return -1;
        for (int i = 0; i < names.length; i++) if (names[i].equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** Shortest signed distance from ours to the reference around a cycle of this size. */
    private static Integer cycleOffset(int ref, int ours, int size) {
        if (ref < 0 || ours < 0) return null;
        int offset = ((ref - ours) + size) % size;
        return offset > size / 2 ? offset - size : offset;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ---------------------------------------------------------------------------------------------- one beacon

    /** One set of controls: the normal one, or the enchanted one shown while upgrading signal strength. */
    private static final class Tune {
        private static final long UNKNOWN = Long.MIN_VALUE;

        final boolean isEnchanted;
        int refColor = -1, ourColor = -1;
        int refSpeed = -1, ourSpeed = -1;
        int refPitch = -1, ourPitch = -1;
        long nextRefPitch = UNKNOWN, nextOurPitch = UNKNOWN;
        final List<Integer> buffer = new ArrayList<>();
        int lastServerTick;
        int currentRefSlot = MATCH_FIRST;
        final List<Integer> recentTicks = new ArrayList<>();

        Tune(boolean enchanted) {
            this.isEnchanted = enchanted;
        }

        private int colorSlot() { return COLOR_SLOT + slotOffsetValue(); }
        private int speedSlot() { return SPEED_SLOT + slotOffsetValue(); }
        private int pitchSlot() { return PITCH_SLOT + slotOffsetValue(); }
        private int pauseSlot() { return PAUSE_SLOT + slotOffsetValue(); }

        private int slotOffsetValue() {
            return upgradingStrength && !isEnchanted ? -9 : 0;
        }

        long untilRef() {
            return nextRefPitch == UNKNOWN ? Long.MAX_VALUE : nextRefPitch - now();
        }

        long untilOurs() {
            return nextOurPitch == UNKNOWN ? Long.MAX_VALUE : nextOurPitch - now();
        }

        void handlePitch(int pitch) {
            if (isEnchanted && !upgradingStrength) return;
            if (untilRef() > PITCH_MARGIN_MS || untilRef() > untilOurs()) return;
            if (buffer.size() >= 6) buffer.remove(0);
            buffer.add(pitch);
            if (buffer.stream().distinct().count() > 2) buffer.clear();
            if (buffer.size() < 3) return;
            int[] counts = new int[PITCHES.length];
            for (int p : buffer) counts[p]++;
            int best = 0;
            for (int i = 1; i < counts.length; i++) if (counts[i] > counts[best]) best = i;
            refPitch = best;
        }

        void readSlot(int index, ItemStack stack) {
            // Reference and own rows: the pane colour in each slot, and which slot is lit.
            boolean match = index >= MATCH_FIRST && index <= MATCH_LAST;
            boolean change = index >= CHANGE_FIRST && index <= CHANGE_LAST;
            int color = paneColor(stack);
            if ((match || change) && color >= 0) {
                if (match) {
                    refColor = color;
                    updateMatchSlot(index);
                } else {
                    ourColor = color;
                }
            }
            if (isEnchanted && !upgradingStrength) return;
            if (index == colorSlot()) {
                ourColor = indexOf(COLORS, loreValue(stack, CURRENT_COLOR));
            } else if (index == speedSlot()) {
                String v = loreValue(stack, CURRENT_SPEED);
                int speed = -1;
                if (v != null) {
                    try {
                        speed = Integer.parseInt(v) - 1;
                    } catch (NumberFormatException ignored) {
                    }
                }
                ourSpeed = speed >= 0 && speed < SPEED_TICKS.length ? speed : -1;
                nextOurPitch = ourSpeed < 0 ? UNKNOWN : now() + SPEED_TICKS[ourSpeed] * 50L;
            } else if (index == pitchSlot()) {
                ourPitch = indexOf(PITCHES, loreValue(stack, CURRENT_PITCH));
            }
        }

        /** The reference row's lit slot moved: the ticks since the last move give the reference speed. */
        private void updateMatchSlot(int slot) {
            if (slot == currentRefSlot) return;
            currentRefSlot = slot;
            int diff = serverTicks - lastServerTick;
            if (diff <= 0) return;
            recentTicks.add(diff);
            lastServerTick = serverTicks;
            if (upgradingStrength && recentTicks.size() < 3) return;
            checkReferenceSpeed();
        }

        private void checkReferenceSpeed() {
            if (recentTicks.isEmpty()) return;
            List<Integer> recent = new ArrayList<>(recentTicks.subList(Math.max(0, recentTicks.size() - 10), recentTicks.size()));
            List<Integer> sorted = new ArrayList<>(recent);
            sorted.sort(null);
            double median = sorted.size() % 2 == 0
                ? (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0
                : sorted.get(sorted.size() / 2);
            double sum = 0;
            int count = 0;
            for (int t : recent) {
                if (t >= median * 0.8 && t <= median * 1.2) {
                    sum += t;
                    count++;
                }
            }
            if (count == 0) return;
            double avg = sum / count;
            int best = 0;
            for (int i = 1; i < SPEED_TICKS.length; i++) {
                if (Math.abs(SPEED_TICKS[i] - avg) < Math.abs(SPEED_TICKS[best] - avg)) best = i;
            }
            refSpeed = best;
            nextRefPitch = now() + SPEED_TICKS[best] * 50L;
        }

        Integer offsetBySlot(int slot) {
            if (slot == colorSlot()) return cycleOffset(refColor, ourColor, COLORS.length);
            if (slot == speedSlot()) return cycleOffset(refSpeed, ourSpeed, SPEED_TICKS.length);
            if (slot == pitchSlot()) return cycleOffset(refPitch, ourPitch, PITCHES.length);
            return null;
        }

        boolean shouldHighlight(Slot slot) {
            if (isEnchanted && !upgradingStrength) return false;
            if (slot.index == pitchSlot()) {
                return refPitch >= 0 && indexOf(PITCHES, loreValue(slot.getItem(), CURRENT_PITCH)) == refPitch;
            }
            Integer offset = offsetBySlot(slot.index);
            return offset != null && offset == 0;
        }

        Integer label(int slot) {
            if (isEnchanted && !upgradingStrength) return null;
            Integer offset = offsetBySlot(slot);
            return offset == null || offset == 0 ? null : offset;
        }

        void addLines(List<String> out) {
            if (isEnchanted) out.add(" ");
            out.add(isEnchanted ? "§aEnchanted Tuning" : "§d§lMoonglade Beacon Solver");
            out.add("§7Ref Color: " + (refColor < 0 ? "§eUnknown" : "§a" + COLORS[refColor]));
            out.add("§7Ref Speed: " + (refSpeed < 0 ? "§eCalculating.." : "§aSpeed " + (refSpeed + 1)));
            out.add("§7Ref Pitch: " + (refPitch < 0 ? "§eUnknown" : "§a" + PITCHES[refPitch]));
        }
    }
}
