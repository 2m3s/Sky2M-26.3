package com.epic60869.sky2m.features.garden.helpers;

import com.epic60869.sky2m.Sky2MSkyblockTime;
import com.epic60869.sky2m.Sky2MMouseLock;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.garden.Crops;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.epic60869.sky2m.sim.util.SimItemIds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stereo Harmony display (SkyHanni's StereoHarmonyDisplay and VinylType, LGPL-2.1,
 * https://github.com/hannibal002/SkyHanni), the vinyl helper (Skyblocker's StereoHarmonyHelper and VacuumCache,
 * LGPL-3.0, https://github.com/SkyblockerMod/Skyblocker) and the Atmospheric Filter display (SkyHanni's
 * AtmosphericFilterDisplay and SkyblockSeason). The playing vinyl is read from the Stereo Harmony menu and from the
 * "Now Playing" line of the Vacuum in your inventory; it is remembered for the session.
 */
final class StereoAndFilter {
    private static final String STEREO_HUD = "garden_stereo_harmony";
    private static final String FILTER_HUD = "garden_atmospheric_filter";
    private static final Pattern NOW_PLAYING = Pattern.compile("^Now Playing: (?<vinyl>[^(]+?)(?: \\(.*\\))?$");
    private static final Pattern WHEN_PLAYING = Pattern.compile("^When playing, (?<pest>.+?) Pests.*");

    /** Vinyl name to {pest, crop} (SkyHanni's VinylType and Skyblocker's CROP_BY_VINYL). */
    private static final Map<String, String[]> VINYLS = Map.ofEntries(
        Map.entry("Pretty Fly", new String[]{"Fly", "Wheat"}),
        Map.entry("Cricket Choir", new String[]{"Cricket", "Carrot"}),
        Map.entry("Cicada Symphony", new String[]{"Locust", "Potato"}),
        Map.entry("Rodent Revolution", new String[]{"Rat", "Pumpkin"}),
        Map.entry("Buzzin' Beats", new String[]{"Mosquito", "Sugar Cane"}),
        Map.entry("Earthworm Ensemble", new String[]{"Earthworm", "Melon Slice"}),
        Map.entry("DynaMITES", new String[]{"Mite", "Cactus"}),
        Map.entry("Wings of Harmony", new String[]{"Moth", "Cocoa Beans"}),
        Map.entry("Slow and Groovy", new String[]{"Slug", "Mushroom"}),
        Map.entry("Not Just a Pest", new String[]{"Beetle", "Nether Wart"}),
        Map.entry("Firefly in the Hole", new String[]{"Firefly", "Moonflower"}),
        Map.entry("Imagine Dragonflies", new String[]{"Dragonfly", "Sunflower"}),
        Map.entry("Pray For Me", new String[]{"Praying Mantis", "Wild Rose"}));
    private static final Map<String, String> CROP_BY_PEST = Map.ofEntries(
        Map.entry("Fly", "Wheat"), Map.entry("Mosquito", "Sugar Cane"), Map.entry("Cricket", "Carrot"),
        Map.entry("Locust", "Potato"), Map.entry("Earthworm", "Melon Slice"), Map.entry("Rat", "Pumpkin"),
        Map.entry("Moth", "Cocoa Beans"), Map.entry("Beetle", "Nether Wart"), Map.entry("Mite", "Cactus"),
        Map.entry("Slug", "Mushroom"), Map.entry("Firefly", "Moonflower"), Map.entry("Dragonfly", "Sunflower"),
        Map.entry("Praying Mantis", "Wild Rose"));
    /** Farming tool collection id to the crop name above. */
    private static final Map<String, String> CROP_BY_COLLECTION = Map.ofEntries(
        Map.entry("WHEAT", "Wheat"), Map.entry("CARROT_ITEM", "Carrot"), Map.entry("POTATO_ITEM", "Potato"),
        Map.entry("NETHER_STALK", "Nether Wart"), Map.entry("PUMPKIN", "Pumpkin"), Map.entry("MELON", "Melon Slice"),
        Map.entry("INK_SACK:3", "Cocoa Beans"), Map.entry("SUGAR_CANE", "Sugar Cane"), Map.entry("CACTUS", "Cactus"),
        Map.entry("MUSHROOM_COLLECTION", "Mushroom"), Map.entry("WILD_ROSE", "Wild Rose"),
        Map.entry("DOUBLE_PLANT", "Sunflower"), Map.entry("MOONFLOWER", "Moonflower"));

    private static volatile String playing;
    private static int tick;

    private StereoAndFilter() {}

    static void init() {
        Sky2MHuds.setting(STEREO_HUD, () -> {
            HelperConfig c = HelperUtil.cfg();
            return c != null && c.stereo.display;
        });
        Sky2MHuds.register(STEREO_HUD, "Stereo Harmony",
            () -> {
                HelperConfig c = HelperUtil.cfg();
                return c != null && c.stereo.display && Sky2MLocation.inGarden() && (playing != null || !c.stereo.hideWhenNone);
            },
            StereoAndFilter::stereoLines,
            List.of(SbcItems.parseLegacy("§ePlaying: §aWings of Harmony"), SbcItems.parseLegacy("§ePest: §cMoth §7(Cocoa Beans)")),
            5, 120);
        Sky2MHuds.setting(FILTER_HUD, () -> {
            HelperConfig c = HelperUtil.cfg();
            return c != null && c.filter.enabled;
        });
        Sky2MHuds.register(FILTER_HUD, "Atmospheric Filter",
            () -> {
                HelperConfig c = HelperUtil.cfg();
                return c != null && c.filter.enabled && Sky2MLocation.onSkyblock() && (Sky2MLocation.inGarden() || c.filter.outsideGarden);
            },
            StereoAndFilter::filterLines,
            List.of(SbcItems.parseLegacy("§dSpring§7: §7Gain §6+25☘ Farming Fortune§7.")),
            5, 140);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            HelperConfig c = HelperUtil.cfg();
            if (mc.player == null || c == null || !(c.stereo.display || c.stereo.helper) || !Sky2MLocation.onSkyblock()) return;
            if (++tick % 40 != 0) return;
            // The Vacuum in your inventory says what it is playing (even outside the Garden).
            for (int i = 0; i < 36; i++) {
                if (readVinyl(mc.player.getInventory().getItem(i))) return;
            }
        });
    }

    /** Sets the playing vinyl from an item's "Now Playing:" line; true if the item had one. */
    private static boolean readVinyl(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (String line : HelperUtil.lore(stack)) {
            Matcher m = NOW_PLAYING.matcher(line);
            if (!m.matches()) continue;
            String name = m.group("vinyl").trim();
            playing = VINYLS.containsKey(name) ? name : null;
            return true;
        }
        return false;
    }

    private static List<Component> stereoLines() {
        String vinyl = playing;
        if (vinyl == null) return List.of(SbcItems.parseLegacy("§ePlaying: §7Nothing"));
        String[] info = VINYLS.get(vinyl);
        return List.of(SbcItems.parseLegacy("§ePlaying: §a" + vinyl),
            SbcItems.parseLegacy("§ePest: §c" + info[0] + " §7(" + info[1] + ")"));
    }

    // ------------------------------------------------------------------------------------------ menu

    static void updateMenu(AbstractContainerScreen<?> screen, String title) {
        if (!title.equals("Stereo Harmony") || !Sky2MLocation.onSkyblock()) return;
        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() > 4 && readVinyl(slots.get(4).getItem())) return;
        // No "Now Playing" line: nothing is playing.
        if (slots.size() > 4 && !slots.get(4).getItem().isEmpty()) playing = null;
    }

    static void drawMenu(AbstractContainerScreen<?> screen, String title, GuiGraphicsExtractor graphics) {
        HelperConfig c = HelperUtil.cfg();
        if (c == null || !c.stereo.helper || !title.equals("Stereo Harmony") || !Sky2MLocation.onSkyblock()) return;
        String crop = heldCrop();
        if (crop == null) return;
        List<Slot> slots = screen.getMenu().slots;
        for (int i = 0; i < Math.min(GardenHelpers.containerSize(screen), slots.size()); i++) {
            Slot slot = slots.get(i);
            boolean isPlaying = false;
            String pest = null;
            for (String line : HelperUtil.lore(slot.getItem())) {
                Matcher m = WHEN_PLAYING.matcher(line);
                if (m.matches()) pest = m.group("pest").trim();
                if (line.contains("PLAYING")) isPlaying = true;
            }
            if (pest == null || !crop.equals(CROP_BY_PEST.get(pest))) continue;
            GardenHelpers.outlineSlot(graphics, screen, slot, isPlaying ? 0xFFFFFF55 : 0xFF55FF55);
            GardenHelpers.fillSlot(graphics, screen, slot, isPlaying ? 0x55FFFF55 : 0x5555FF55);
        }
    }

    /** The crop (as the vinyl menu names it) of the farming tool you hold, or null. */
    private static String heldCrop() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        ItemStack held = mc.player.getMainHandItem();
        if (!Sky2MMouseLock.isFarmingTool(held)) return null;
        String collection = Crops.toolCollection(SimItemIds.skyblockId(held), mc.level.getGameTime());
        return collection == null ? null : CROP_BY_COLLECTION.get(collection);
    }

    // ------------------------------------------------------------------------------------------ filter

    private static final String[] SEASONS = {"§dSpring", "§6Summer", "§eAutumn", "§9Winter"};
    private static final String[] PERKS = {"§7Gain §6+25☘ Farming Fortune§7.", "§7Gain §3+20☘ Farming Wisdom§7.",
        "§4Pests §7spawn §a15% §7more often.", "§7Visitors give §a5% §7more §cCopper§7."};
    private static final String[] SHORT_PERKS = {"§6+25☘", "§3+20☘", "§a15%+§4Pests", "§a5%+§cCopper"};

    private static List<Component> filterLines() {
        HelperConfig c = HelperUtil.cfg();
        if (c == null) return List.of();
        // A SkyBlock year is 372 days, 12 months of 31 days; it starts in Early Spring and every 3 months is a season.
        long days = Math.floorDiv(System.currentTimeMillis() - Sky2MSkyblockTime.SKYBLOCK_EPOCH.toEpochMilli(), 1_200_000L);
        int season = (int) (Math.floorMod(days, 372L) / 31 / 3);
        String perk = c.filter.abbreviatePerk ? SHORT_PERKS[season] : PERKS[season];
        String name = c.filter.abbreviateSeason ? SEASONS[season].substring(0, 6) : SEASONS[season];
        return List.of(SbcItems.parseLegacy(c.filter.onlyBuff ? perk : name + "§7: " + perk));
    }
}
