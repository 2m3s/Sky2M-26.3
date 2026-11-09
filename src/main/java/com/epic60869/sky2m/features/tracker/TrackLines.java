package com.epic60869.sky2m.features.tracker;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Helpers shared by /s2 hour ({@link DungeonProfitHour}), /s2 tracker ({@link DungeonDropTracker}) and /s2 rnglist
 * ({@link RngTracker}), which came from the private Sky2M Personal add-on: text without colour codes, the tab list, and
 * moving the add-on's save files to Sky2M's config folder the first time.
 */
public final class TrackLines {
    static final Logger LOG = LoggerFactory.getLogger("sky2m");

    private TrackLines() {}

    public static FeatureConfigs.Trackers config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.profitTrackers.trackers;
    }

    public static String strip(String text) {
        String out = ChatFormatting.stripFormatting(text);
        return out == null ? "" : out;
    }

    /** The item's lore lines without colour codes. */
    public static List<String> lore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        ItemLore lore = stack == null ? null : stack.get(DataComponents.LORE);
        if (lore != null) for (Component line : lore.lines()) out.add(strip(line.getString()));
        return out;
    }

    /** The tab list's lines without colour codes. */
    public static List<String> tabLines() {
        List<String> out = new ArrayList<>();
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) return out;
        for (PlayerInfo info : connection.getListedOnlinePlayers()) {
            Component name = info.getTabListDisplayName();
            if (name != null) out.add(strip(name.getString()).trim());
        }
        return out;
    }

    /** {@code file}, after copying the Sky2M Personal add-on's old save there if this one doesn't exist yet. */
    static Path movedFrom(Path file, Path old) {
        try {
            if (!Files.exists(file) && Files.exists(old)) {
                Files.createDirectories(file.getParent());
                Files.copy(old, file, StandardCopyOption.COPY_ATTRIBUTES);
                LOG.info("Moved {} to {}", old, file);
            }
        } catch (Exception e) {
            LOG.warn("Couldn't move {} to {}: {}", old, file, e.toString());
        }
        return file;
    }
}
