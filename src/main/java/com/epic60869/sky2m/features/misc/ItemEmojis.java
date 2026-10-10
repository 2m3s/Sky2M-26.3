package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MNopoFeatures;
import com.epic60869.sky2m.custom.RepoItems;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel item emojis in every chat (all, party, guild, private and Sky2M chat), like the SkyHelper Discord: ":summoning_eye:" shows the Summoning Eye's icon.
 * Every SkyBlock item works by its id in lower case. Client side only: the message itself stays ":summoning_eye:".
 *
 * The icons are SkyBlock's real item pictures, not whatever the plain item looks like without Hypixel's resource pack
 * (the Summoning Eye is paper underneath): each is downloaded once from Coflnet (sky.coflnet.com/static/icon/ID) and
 * kept in config/sky2m/item-icons, so they work in singleplayer and with any pack. Until an icon has downloaded,
 * the item itself is drawn.
 *
 * In chat an emoji is a blank 9 pixel gap (the sky2m:item_emoji font) that remembers its item, and the icon is
 * drawn into the gap as the line is drawn (Sky2MChatLineMixin). Hovering it shows the item's name.
 *
 * Also the emoji autocomplete: a short, cached list of matches (typing ":" no longer lists thousands of emojis, which
 * lagged), with each emoji's picture next to its name.
 */
public final class ItemEmojis {
    private static final Pattern EMOJI = Pattern.compile(":([a-z0-9_]+):");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final int MAX_SUGGESTIONS = 50;
    /** Width of the picture drawn before each suggestion, plus its gap. */
    public static final int PREVIEW_WIDTH = 11;
    private static final String GAP = "";
    private static final FontDescription GAP_FONT = new FontDescription.Resource(Identifier.fromNamespaceAndPath("sky2m", "item_emoji"));
    private static final String ICON_URL = "https://sky.coflnet.com/static/icon/";

    /** Emoji name ("summoning_eye") -> SkyBlock item id ("SUMMONING_EYE"). */
    private static final Map<String, String> ITEMS = new HashMap<>();
    private static final Map<String, ItemStack> STACKS = new HashMap<>();
    /** Every emoji shortcode (":name:"), sorted; built once, rebuilt when the item list loads. */
    private static List<String> allSuggestions;
    private static int nopoCountAtBuild = -1;

    /** A downloaded icon, as a texture. */
    private record Icon(Identifier texture, int width, int height) {}

    private static final Map<String, Icon> ICONS = new ConcurrentHashMap<>();
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static Path iconDir;

    private ItemEmojis() {}

    public static void init(Path configDir) {
        iconDir = configDir.resolve("sky2m").resolve("item-icons");
        RepoItems.runAfterItemsLoaded(() -> Minecraft.getInstance().execute(ItemEmojis::loadItems));
    }

    private static void loadItems() {
        ITEMS.clear();
        for (String id : RepoItems.allIds()) {
            String name = id.toLowerCase(Locale.ROOT);
            if (NAME.matcher(name).matches()) ITEMS.put(name, id);
        }
        allSuggestions = null;
    }

    private static Sky2MConfig.Chat chat() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat;
    }

    public static boolean itemEmojisEnabled() {
        Sky2MConfig.Chat c = chat();
        return c != null && c.itemEmojis;
    }

    public static boolean autocompleteEnabled() {
        Sky2MConfig.Chat c = chat();
        return c != null && c.emojiAutocomplete && (c.chatEmoji || c.itemEmojis);
    }

    // ---------------------------------------------------------------- chat

    /** Any chat message: ":summoning_eye:" becomes a gap the item's icon is drawn into. Nopo emojis are left to Nopo's renderer. */
    public static Component replace(Component message) {
        if (!itemEmojisEnabled() || ITEMS.isEmpty() || !message.getString().contains(":")) return message;
        MutableComponent result = Component.empty();
        message.visit((style, value) -> {
            if (value == null || value.isEmpty()) return Optional.empty();
            Matcher m = EMOJI.matcher(value);
            int cursor = 0;
            while (m.find()) {
                String name = m.group(1);
                if (Sky2MNopoFeatures.isChatEmoji(name) || !ITEMS.containsKey(name)) continue;
                if (m.start() > cursor) result.append(Component.literal(value.substring(cursor, m.start())).withStyle(style));
                result.append(gap(name, style));
                request(name);
                cursor = m.end();
            }
            if (cursor < value.length()) result.append(Component.literal(value.substring(cursor)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return result;
    }

    private static Component gap(String name, Style style) {
        String id = ITEMS.get(name);
        String display = RepoItems.displayName(id);
        return Component.literal(GAP).withStyle(style
            .withFont(GAP_FONT)
            .withInsertion(":" + name + ":")
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(display == null ? id : display)
                .append(Component.literal("\n:" + name + ":").withStyle(ChatFormatting.DARK_GRAY)))));
    }

    /** Draws the icons into a chat line's emoji gaps (Sky2MChatLineMixin, after vanilla drew the line at x 0). */
    public static void drawChatIcons(GuiGraphicsExtractor graphics, FormattedCharSequence content, int textTop, float opacity) {
        if (ITEMS.isEmpty()) return;
        Font font = Minecraft.getInstance().font;
        List<int[]> spots = new ArrayList<>();
        List<String> names = new ArrayList<>();
        float[] x = {0};
        content.accept((index, style, codepoint) -> {
            String single = new String(Character.toChars(codepoint));
            if (single.equals(GAP) && style.getInsertion() != null && style.getInsertion().length() > 2) {
                String name = style.getInsertion().substring(1, style.getInsertion().length() - 1);
                if (ITEMS.containsKey(name)) {
                    spots.add(new int[]{(int) x[0]});
                    names.add(name);
                }
            }
            x[0] += font.width(FormattedCharSequence.forward(single, style));
            return true;
        });
        for (int i = 0; i < spots.size(); i++) {
            drawIcon(graphics, names.get(i), spots.get(i)[0], textTop - 1, 9, opacity);
        }
    }

    // ---------------------------------------------------------------- icons

    /** The emoji's picture at x, y, size x size: the downloaded icon, or the item itself until that has arrived. */
    private static void drawIcon(GuiGraphicsExtractor g, String name, int x, int y, int size, float opacity) {
        Icon icon = ICONS.get(name);
        if (icon != null) {
            g.blit(RenderPipelines.GUI_TEXTURED, icon.texture(), x, y, 0, 0, size, size, icon.width(), icon.height(),
                icon.width(), icon.height(), ARGB.white(opacity));
            return;
        }
        request(name);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / 16f, size / 16f);
        g.item(stack(name), 0, 0);
        g.pose().popMatrix();
    }

    /** Loads the icon from disk, or downloads it (once) if it isn't there yet. */
    private static void request(String name) {
        if (iconDir == null || ICONS.containsKey(name) || !REQUESTED.add(name)) return;
        String id = ITEMS.get(name);
        if (id == null) return;
        RepoItems.runAsync(() -> {
            try {
                Path file = iconDir.resolve(id.replace(':', '-') + ".png");
                byte[] bytes;
                if (Files.exists(file)) {
                    bytes = Files.readAllBytes(file);
                } else {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(ICON_URL + id))
                        .timeout(Duration.ofSeconds(15)).header("User-Agent", "Sky2M").GET().build();
                    HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() != 200 || response.body().length == 0) return;
                    bytes = response.body();
                    Files.createDirectories(iconDir);
                    Files.write(file, bytes);
                }
                NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                Minecraft.getInstance().execute(() -> {
                    Identifier texture = Identifier.fromNamespaceAndPath("sky2m", "item_icon/" + name);
                    Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "sky2m item icon " + name, image));
                    ICONS.put(name, new Icon(texture, image.getWidth(), image.getHeight()));
                });
            } catch (Exception e) {
                // Stays as the item itself; tried again next launch.
            }
        });
    }

    private static ItemStack stack(String name) {
        return STACKS.computeIfAbsent(name, n -> {
            ItemStack stack = RepoItems.itemStack(ITEMS.get(n));
            // With Hypixel's resource pack loaded (on Hypixel) the item looks right even before its icon downloads.
            String model = RepoItems.itemModel(ITEMS.get(n));
            Identifier modelId = model == null ? null : Identifier.tryParse(model);
            if (modelId != null && hasModel(modelId)) {
                stack = stack.copy();
                stack.set(DataComponents.ITEM_MODEL, modelId);
            }
            return stack;
        });
    }

    private static boolean hasModel(Identifier modelId) {
        try {
            var models = Minecraft.getInstance().getModelManager();
            return models.getItemModel(modelId) != models.getItemModel(Identifier.fromNamespaceAndPath("sky2m", "no_such_item_model"));
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- autocomplete

    private static List<String> allSuggestions() {
        int nopo = Sky2MNopoFeatures.chatEmojiNames().size();
        if (allSuggestions == null || nopo != nopoCountAtBuild) {
            TreeSet<String> names = new TreeSet<>();
            Sky2MConfig.Chat c = chat();
            if (c == null || c.chatEmoji) names.addAll(Sky2MNopoFeatures.chatEmojiNames());
            if (c == null || c.itemEmojis) names.addAll(ITEMS.keySet());
            List<String> list = new ArrayList<>(names.size());
            for (String n : names) list.add(":" + n + ":");
            allSuggestions = list;
            nopoCountAtBuild = nopo;
        }
        return allSuggestions;
    }

    /** Forget the cached list (a setting changed). */
    public static void invalidate() {
        allSuggestions = null;
    }

    /**
     * Up to 50 emojis for what's typed after ':' — names starting with it first, then names containing it — so the list
     * stays short and quick however many emojis there are.
     */
    public static List<String> suggestions(String typed) {
        String query = typed.startsWith(":") ? typed.substring(1).toLowerCase(Locale.ROOT) : typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        List<String> all = allSuggestions();
        for (String s : all) {
            if (s.startsWith(":" + query)) {
                out.add(s);
                if (out.size() >= MAX_SUGGESTIONS) return out;
            }
        }
        if (query.length() >= 2) {
            for (String s : all) {
                if (!s.startsWith(":" + query) && s.contains(query)) {
                    out.add(s);
                    if (out.size() >= MAX_SUGGESTIONS) return out;
                }
            }
        }
        return out;
    }

    /** Whether a suggestion is an emoji shortcode (gets a picture next to it). */
    public static boolean isEmojiSuggestion(String text) {
        if (text == null || text.length() < 3 || text.charAt(0) != ':' || text.charAt(text.length() - 1) != ':') return false;
        String name = text.substring(1, text.length() - 1);
        return Sky2MNopoFeatures.isChatEmoji(name) || ITEMS.containsKey(name);
    }

    /** Draws the emoji's picture (9x9) at x, y for the autocomplete list. */
    public static void drawPreview(GuiGraphicsExtractor g, String text, int x, int y) {
        String name = text.substring(1, text.length() - 1);
        Identifier nopo = Sky2MNopoFeatures.chatEmojiSprite(name);
        if (nopo != null) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, nopo, x, y, 9, 9);
            return;
        }
        if (ITEMS.containsKey(name)) drawIcon(g, name, x, y, 9, 1f);
    }
}
