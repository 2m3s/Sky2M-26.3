package com.epic60869.sky2m.features.sbc;

import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drawing SkyBlock items that aren't in your inventory (items shared in Sky2M chat). Hypixel draws most items
 * through its resource pack, which only exists while you're on Hypixel; without it an item is just its base material
 * (paper, a barrier...). So: player heads are drawn with their skin, items get Hypixel's model when its pack is
 * loaded, and otherwise the item's picture is downloaded (the same icons and cache as the chat item emojis).
 */
public final class SbcItemIcons {
    private static final String ICON_URL = "https://sky.coflnet.com/static/icon/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    private record Icon(Identifier texture, int width, int height) {}

    private static final Map<String, Icon> ICONS = new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final Map<JsonObject, ItemStack> STACKS = new IdentityHashMap<>();
    private static final Map<String, ItemStack> ID_STACKS = new ConcurrentHashMap<>();

    private SbcItemIcons() {}

    /** The item as a stack (skull with its skin, or Hypixel's model when the pack has it), cached per item. */
    public static ItemStack stack(JsonObject item) {
        synchronized (STACKS) {
            if (STACKS.size() > 4000) STACKS.clear();
            return STACKS.computeIfAbsent(item, SbcItemIcons::build);
        }
    }

    private static ItemStack build(JsonObject item) {
        ItemStack stack = SbcItems.stack(item);
        String model = RepoItems.itemModel(Sbc.str(item, "id"));
        Identifier modelId = model == null ? null : Identifier.tryParse(model);
        if (modelId != null && hasModel(modelId)) stack.set(DataComponents.ITEM_MODEL, modelId);
        return stack;
    }

    /** Draws the item at x, y (16x16) with its count. */
    public static void draw(GuiGraphicsExtractor g, JsonObject item, int x, int y) {
        draw(g, stack(item), Sbc.str(item, "id"), x, y);
    }

    /** Draws a SkyBlock item by id (a pet's held item, a shared inventory's slot), however Hypixel draws it. */
    public static void drawId(GuiGraphicsExtractor g, String id, int x, int y) {
        ItemStack stack = ID_STACKS.get(id);
        if (stack == null) {
            stack = RepoItems.itemStack(id);
            // Not kept until the item list is in, or it would stay a barrier.
            if (RepoItems.itemsLoaded()) ID_STACKS.put(id, stack);
        }
        draw(g, stack, id, x, y);
    }

    /** Draws {@code stack} at x, y, or the downloaded picture of item {@code id} when the stack would be a stand-in. */
    public static void draw(GuiGraphicsExtractor g, ItemStack stack, String id, int x, int y) {
        // Heads and items given a real model look right as they are. Every stack has its base item's model
        // (paper's, for paper), so only a model that differs from the base item's counts.
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        boolean ownModel = model != null && !model.equals(stack.getPrototype().get(DataComponents.ITEM_MODEL)) && hasModel(model);
        boolean looksRight = stack.is(Items.PLAYER_HEAD) || ownModel || !isPlaceholder(stack);
        if (!looksRight && !id.isEmpty()) {
            Icon icon = icon(id);
            if (icon != null) {
                g.blit(RenderPipelines.GUI_TEXTURED, icon.texture(), x, y, 0, 0, 16, 16, icon.width(), icon.height(), icon.width(), icon.height());
                g.itemDecorations(Minecraft.getInstance().font, stack, x, y);
                return;
            }
        }
        g.item(stack, x, y);
        g.itemDecorations(Minecraft.getInstance().font, stack, x, y);
    }

    /** Paper, barriers and other stand-ins Hypixel replaces with its own textures. */
    private static boolean isPlaceholder(ItemStack stack) {
        return stack.is(Items.PAPER) || stack.is(Items.BARRIER) || stack.is(Items.FIREWORK_STAR) || stack.is(Items.PRISMARINE_SHARD)
            || stack.is(Items.STRUCTURE_VOID) || stack.is(Items.GHAST_TEAR) || stack.is(Items.FLINT);
    }

    private static boolean hasModel(Identifier modelId) {
        return RepoItems.hasItemModel(modelId);
    }

    /** The downloaded icon, or null while it downloads (or if there is none). */
    private static Icon icon(String id) {
        Icon icon = ICONS.get(id);
        if (icon == null && REQUESTED.add(id)) download(id);
        return icon;
    }

    private static void download(String id) {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("sky2m").resolve("item-icons");
        RepoItems.runAsync(() -> {
            try {
                Path file = dir.resolve(id.replace(':', '-') + ".png");
                byte[] bytes;
                if (Files.exists(file)) {
                    bytes = Files.readAllBytes(file);
                } else {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(ICON_URL + id)).timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Sky2M").GET().build();
                    HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200 || response.body().length == 0) return;
                    bytes = response.body();
                    Files.createDirectories(dir);
                    Files.write(file, bytes);
                }
                NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                Minecraft.getInstance().execute(() -> {
                    Identifier texture = Compat.id("pv_icon/" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_"));
                    Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "sky2m item icon " + id, image));
                    ICONS.put(id, new Icon(texture, image.getWidth(), image.getHeight()));
                });
            } catch (Exception ignored) {
                // Drawn as the plain item; tried again next launch.
            }
        });
    }
}
