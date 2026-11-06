package com.epic60869.sky2m.features.economy;

import com.epic60869.sky2m.price.PriceService;
import com.epic60869.sky2m.price.Sky2MRecipeCommand;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Price History Graph (/s2 pricegraph, or the Price Graph key over an item): an item's price over the last day, week or
 * month from Coflnet's public price history (only the item id is sent). Auction items show the lowest and average sale
 * price per hour; Bazaar items the buy and sell price.
 */
public final class PriceGraphScreen extends Screen {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final String[] RANGES = {"day", "week", "month"};
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US);
    private static final int TOP_COLOUR = 0xFFFFAA00, BOTTOM_COLOUR = 0xFF55FFFF;

    private record Point(long time, double top, double bottom) {}

    private final Screen parent;
    private final String id;
    private final boolean bazaar;
    private int range = 1;
    private volatile List<Point> points;
    private volatile String error;

    public PriceGraphScreen(Screen parent, String id) {
        super(Component.literal("Price History"));
        this.parent = parent;
        this.id = id;
        this.bazaar = PriceService.bazaar().buy().containsKey(id) || PriceService.bazaar().sell().containsKey(id);
        load();
    }

    @Override
    protected void init() {
        int x = width / 2 - 154;
        for (int i = 0; i < RANGES.length; i++) {
            int r = i;
            addRenderableWidget(Button.builder(Component.literal(Character.toUpperCase(RANGES[i].charAt(0)) + RANGES[i].substring(1)), b -> {
                range = r;
                load();
            }).bounds(x + i * 78, height - 26, 74, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 3 * 78, height - 26, 74, 20).build());
    }

    private void load() {
        points = null;
        error = null;
        String url = bazaar
            ? "https://sky.coflnet.com/api/bazaar/" + id + "/history/" + RANGES[range]
            : "https://sky.coflnet.com/api/item/price/" + id + "/history/" + RANGES[range];
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Sky2M").build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, failure) -> {
            if (failure != null || response.statusCode() != 200) {
                error = "Couldn't load the price history" + (response == null ? "." : " (HTTP " + response.statusCode() + ").");
                return;
            }
            try {
                List<Point> out = new ArrayList<>();
                JsonElement root = JsonParser.parseString(response.body());
                JsonArray list = root.isJsonArray() ? root.getAsJsonArray() : new JsonArray();
                for (JsonElement e : list) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String when = o.has("time") ? o.get("time").getAsString() : o.has("timestamp") ? o.get("timestamp").getAsString() : null;
                    if (when == null) continue;
                    long time = LocalDateTime.parse(when.length() > 19 ? when.substring(0, 19) : when).toInstant(ZoneOffset.UTC).toEpochMilli();
                    double top = number(o, bazaar ? "buy" : "avg"), bottom = number(o, bazaar ? "sell" : "min");
                    if (top > 0 || bottom > 0) out.add(new Point(time, top, bottom));
                }
                out.sort((a, b) -> Long.compare(a.time, b.time));
                if (out.isEmpty()) error = "No price history for this item.";
                points = out;
            } catch (Exception e) {
                error = "Couldn't read the price history.";
            }
        });
    }

    private static double number(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        g.centeredText(font, Sky2MRecipeCommand.displayName(id) + " - last " + RANGES[range], width / 2, 8, 0xFFFFD35A);
        g.centeredText(font, bazaar ? "Bazaar: buy price (orange) and sell price (aqua)" : "Auction House: average (orange) and lowest (aqua) sale per hour",
            width / 2, 20, 0xFF9AA5B8);
        int left = 50, right = width - 20, top = 34, bottom = height - 40;
        g.fill(left, top, right, bottom, 0xC0121722);
        g.outline(left, top, right - left, bottom - top, 0xFF3B465B);
        List<Point> data = points;
        if (data == null || data.isEmpty()) {
            g.centeredText(font, error != null ? error : "Loading...", (left + right) / 2, (top + bottom) / 2, 0xFFAAAAAA);
            return;
        }
        double min = Double.MAX_VALUE, max = 0;
        for (Point p : data) {
            for (double v : new double[]{p.top, p.bottom}) {
                if (v <= 0) continue;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (max <= min) max = min + 1;
        long t0 = data.getFirst().time, t1 = Math.max(t0 + 1, data.getLast().time);
        // Price axis: top, middle and bottom.
        for (int i = 0; i <= 2; i++) {
            double v = max - (max - min) * i / 2;
            int y = top + 4 + (bottom - top - 8) * i / 2;
            g.fill(left, y, right, y + 1, 0x303B465B);
            String label = shortNumber(v);
            g.text(font, label, left - 4 - font.width(label), y - 4, 0xFFAAAAAA, false);
        }
        int[] prevTop = null, prevBottom = null;
        Point hovered = null;
        int hoverX = -1;
        for (Point p : data) {
            int x = left + 2 + (int) ((right - left - 4) * (p.time - t0) / (double) (t1 - t0));
            int[] a = p.top > 0 ? new int[]{x, yOf(p.top, min, max, top, bottom)} : null;
            int[] b = p.bottom > 0 ? new int[]{x, yOf(p.bottom, min, max, top, bottom)} : null;
            if (a != null && prevTop != null) line(g, prevTop, a, TOP_COLOUR);
            if (b != null && prevBottom != null) line(g, prevBottom, b, BOTTOM_COLOUR);
            if (a != null) prevTop = a;
            if (b != null) prevBottom = b;
            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom
                && (hovered == null || Math.abs(x - mouseX) < Math.abs(hoverX - mouseX))) {
                hovered = p;
                hoverX = x;
            }
        }
        if (hovered != null) {
            g.fill(hoverX, top, hoverX + 1, bottom, 0x80FFFFFF);
            String when = WHEN.format(LocalDateTime.ofEpochSecond(hovered.time / 1000, 0, ZoneOffset.UTC)) + " UTC";
            g.setComponentTooltipForNextFrame(font, List.of(Component.literal(when),
                Component.literal((bazaar ? "Buy: " : "Average: ") + String.format(Locale.US, "%,.1f", hovered.top)).withColor(TOP_COLOUR & 0xFFFFFF),
                Component.literal((bazaar ? "Sell: " : "Lowest: ") + String.format(Locale.US, "%,.1f", hovered.bottom)).withColor(BOTTOM_COLOUR & 0xFFFFFF)),
                mouseX, mouseY);
        }
    }

    private static int yOf(double v, double min, double max, int top, int bottom) {
        return bottom - 4 - (int) ((bottom - top - 8) * (v - min) / (max - min));
    }

    /** A 2px line from a to b, as small squares along it. */
    private static void line(GuiGraphicsExtractor g, int[] a, int[] b, int colour) {
        int steps = Math.max(1, Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])));
        for (int i = 0; i <= steps; i++) {
            int x = a[0] + (b[0] - a[0]) * i / steps, y = a[1] + (b[1] - a[1]) * i / steps;
            g.fill(x, y, x + 2, y + 2, colour);
        }
    }

    private static String shortNumber(double v) {
        if (v >= 1e9) return String.format(Locale.US, "%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.US, "%.2fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.US, "%.1fk", v / 1e3);
        return String.format(Locale.US, "%.1f", v);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
