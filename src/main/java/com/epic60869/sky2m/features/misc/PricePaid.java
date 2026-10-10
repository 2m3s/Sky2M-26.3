package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Price Paid tooltip, like NoFrills': when you confirm an auction purchase, Sky2M remembers what you paid for that
 * exact item (by its item UUID) and shows "Price Paid: ..." in its tooltip from then on.
 * Stored in config/sky2m/price-paid.json.
 */
public final class PricePaid {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern COST = Pattern.compile("^Cost: ([\\d,]+) coins?");
    private static Map<String, Long> paid = new HashMap<>();
    private static Path file;

    private PricePaid() {}

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.misc.priceTooltip.pricePaid;
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("price-paid.json");
        try {
            if (Files.exists(file)) {
                Map<String, Long> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<Map<String, Long>>() {}.getType());
                if (loaded != null) paid = new HashMap<>(loaded);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read price-paid.json: " + e.getMessage());
        }
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!enabled() || !Compat.isOnSkyblock()) return;
            String uuid = Compat.uuid(stack);
            Long price = uuid.isEmpty() ? null : paid.get(uuid);
            if (price == null) return;
            lines.add(Component.literal("Price Paid: ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(String.format(Locale.ENGLISH, "%,d Coins", price)).withStyle(ChatFormatting.GOLD)));
        });
    }

    /** Called for every slot click in a container screen. */
    public static void onSlotClicked(AbstractContainerScreen<?> screen, Slot slot) {
        if (!enabled() || slot == null || !ChatFormatting.stripFormatting(screen.getTitle().getString()).trim().equals("Confirm Purchase")) return;
        ItemStack button = slot.getItem();
        if (!button.is(Items.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.GREEN))) return;
        long cost = cost(button);
        String uuid = boughtItemUuid(screen);
        if (cost <= 0 || uuid.isEmpty()) return;
        paid.put(uuid, cost);
        save();
    }

    private static long cost(ItemStack button) {
        ItemLore lore = button.get(DataComponents.LORE);
        if (lore == null) return -1;
        for (Component line : lore.lines()) {
            Matcher m = COST.matcher(ChatFormatting.stripFormatting(line.getString()).trim());
            if (m.find()) return Long.parseLong(m.group(1).replace(",", ""));
        }
        return -1;
    }

    /** The item being bought: the one item in the menu (not your inventory) that has a SkyBlock UUID. */
    private static String boughtItemUuid(AbstractContainerScreen<?> screen) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        for (Slot s : screen.getMenu().slots) {
            if (player != null && s.container == player.getInventory()) continue;
            String uuid = Compat.uuid(s.getItem());
            if (!uuid.isEmpty()) return uuid;
        }
        return "";
    }

    private static void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(paid), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save price-paid.json: " + e.getMessage());
        }
    }
}
