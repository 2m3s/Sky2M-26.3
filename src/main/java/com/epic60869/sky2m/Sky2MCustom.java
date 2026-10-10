package com.epic60869.sky2m;

import com.epic60869.sky2m.custom.CustomAnimatedHelmetTextures;
import com.epic60869.sky2m.custom.CustomArmorAnimatedDyes;
import com.epic60869.sky2m.custom.CustomArmorDyeColors;
import com.epic60869.sky2m.custom.CustomArmorTrims;
import com.epic60869.sky2m.custom.CustomConfigManager;
import com.epic60869.sky2m.custom.CustomHelmetTextures;
import com.epic60869.sky2m.custom.CustomItemNames;
import com.epic60869.sky2m.custom.RepoDyeColors;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.SkyblockItemModels;
import com.epic60869.sky2m.custom.screen.CustomizeScreen;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.custom.util.GuiEquipmentRenderer;
import com.epic60869.sky2m.mixin.Sky2MCustomDataAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Entry point for Sky2M's port of Skyblocker's /skyblocker custom item and armor
 * customization (see the {@code custom} package). Everything is client-side and keyed
 * to the item's Hypixel UUID, so the server item itself is never modified.
 */
public final class Sky2MCustom {
    private Sky2MCustom() {}

    public static void init(Path configDir) {
        CustomConfigManager.init(configDir);
        Compat.init();
        GuiEquipmentRenderer.init();

        RepoItems.init();
        RepoDyeColors.init();
        CustomAnimatedHelmetTextures.init();
        CustomHelmetTextures.init();
        SkyblockItemModels.init();

        CustomItemNames.init();
        CustomArmorDyeColors.init();
        CustomArmorTrims.init();
        CustomArmorAnimatedDyes.init();
        CustomizeScreen.initThings();
    }

    /** Opens the customization screen on the armor tab, like /skyblocker custom. */
    public static void open(Minecraft mc, Screen parent) {
        // The armor tab previews the local player, so the screen needs a world.
        if (mc.player == null || mc.level == null) return;
        mc.execute(() -> mc.gui.setScreen(new CustomizeScreen(parent, false)));
    }

    private static Object hypixelConnection;
    private static String hypixelBrand;
    private static boolean hypixelServer;

    /**
     * Whether the client is connected to Hypixel. Checks the server brand Hypixel sends as well as
     * the address, since the address can carry a port, an alias or come through a proxy. Worked out once per
     * connection: it's asked for every item drawn (Sky2MDataComponentHolderMixin).
     */
    public static boolean isHypixel(Minecraft mc) {
        try {
            var connection = mc.getConnection();
            if (connection == null) return false;
            String brand = connection.serverBrand();
            if (connection != hypixelConnection || !java.util.Objects.equals(brand, hypixelBrand)) {
                hypixelConnection = connection;
                hypixelBrand = brand;
                hypixelServer = (brand != null && brand.toLowerCase(Locale.ROOT).contains("hypixel"))
                    || (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null
                        && mc.getCurrentServer().ip.toLowerCase(Locale.ROOT).contains("hypixel"));
            }
            if (hypixelServer) return true;
            return com.epic60869.sky2m.features.core.Sky2MLocation.onSkyblock();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The Hypixel item UUID stored in the item's custom data, or an empty string. */
    public static String uuid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? "" : ((Sky2MCustomDataAccessor) (Object) data).sky2m$getTag().getStringOr("uuid", "");
    }
}
