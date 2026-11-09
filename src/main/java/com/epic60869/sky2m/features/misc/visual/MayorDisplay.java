package com.epic60869.sky2m.features.misc.visual;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mayors > Mayor & Election: the mayor and minister with their perks, and while an election is open who's winning,
 * from Hypixel's keyless election endpoint (refreshed every 10 minutes on SkyBlock). The perk names also feed
 * {@code MayorUtils}, so the score calculator knows when Paul's EZPZ is active.
 */
public final class MayorDisplay {
    private static final String URL = "https://api.hypixel.net/v2/resources/skyblock/election";
    private static final long REFRESH_MS = 10 * 60 * 1000L;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private record Perk(String name, String description) {}

    private record Candidate(String name, List<Perk> perks, long votes) {}

    private record Election(Candidate mayor, Candidate minister, List<Candidate> running, int year) {}

    private static volatile Election election;
    private static volatile long fetchedAt;
    private static final AtomicBoolean FETCHING = new AtomicBoolean();
    private static int ticks;

    private MayorDisplay() {}

    private static FeatureConfigs.MayorDisplay config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.events.mayor;
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 200 == 0 && Sky2MLocation.onSkyblock() && System.currentTimeMillis() - fetchedAt > REFRESH_MS) fetch(null);
        });
        Sky2MHuds.register("mayor_display", "Mayor & Election", () -> config() != null && config().hud && Sky2MLocation.onSkyblock(),
            MayorDisplay::lines,
            List.of(Component.literal("Mayor: ").withStyle(ChatFormatting.GRAY).append(Component.literal("Diana").withStyle(ChatFormatting.GOLD)),
                Component.literal(" • Mythological Ritual").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("Minister: ").withStyle(ChatFormatting.GRAY).append(Component.literal("Paul").withStyle(ChatFormatting.AQUA)),
                Component.literal(" • EZPZ").withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("Leading: ").withStyle(ChatFormatting.GRAY).append(Component.literal("Aatrox 41%").withStyle(ChatFormatting.GREEN))),
            10, 60);
        Sky2MHuds.setting("mayor_display", () -> config() != null && config().hud);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof AbstractContainerScreen<?> container && isCalendar(container)
                && (election == null || System.currentTimeMillis() - fetchedAt > REFRESH_MS)) fetch(null);
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.gui.screen() instanceof AbstractContainerScreen<?> container && isCalendar(container)) placeCalendarMinister(container);
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("mayor").executes(c -> {
                    Minecraft.getInstance().execute(MayorDisplay::printToChat);
                    return 1;
                })));
            }
        });
    }

    /** The mayor's and minister's perk names, or none before the first fetch. */
    public static Set<String> activePerks() {
        Election e = election;
        Set<String> out = new LinkedHashSet<>();
        if (e == null) return out;
        if (e.mayor() != null) for (Perk p : e.mayor().perks()) out.add(p.name());
        if (e.minister() != null) for (Perk p : e.minister().perks()) out.add(p.name());
        return out;
    }

    private static boolean isCalendar(AbstractContainerScreen<?> container) {
        return container.getTitle().getString().startsWith("Calendar and Events");
    }

    /**
     * Hypixel's Calendar menu only shows the mayor, so the minister goes in the slot to its right (if that's filler), as a
     * real item on this client: the menu draws its slot, hover highlight and tooltip like any other. Clicking it clicks
     * the filler Hypixel still has there. Put back every tick, since Hypixel refreshes the menu's items.
     */
    private static void placeCalendarMinister(AbstractContainerScreen<?> container) {
        FeatureConfigs.MayorDisplay c = config();
        Election e = election;
        if (c == null || !c.calendarMinister || e == null || e.minister() == null) return;
        List<Slot> slots = container.getMenu().slots;
        int menuSize = slots.size() - 36;
        for (int i = 0; i < menuSize - 1; i++) {
            if (!Sky2MLocation.strip(slots.get(i).getItem().getHoverName().getString()).startsWith("Mayor ")) continue;
            Slot right = slots.get(i + 1);
            if (i % 9 == 8) return;
            ItemStack minister = ministerItem(e);
            ItemStack now = right.getItem();
            if ((isFiller(now) || now.has(DataComponents.CUSTOM_NAME) && now.getHoverName().getString().startsWith("Minister "))
                && !ItemStack.isSameItemSameComponents(now, minister)) {
                right.set(minister);
            }
            return;
        }
    }

    private static ItemStack ministerStack;
    private static Election ministerFor;
    private static ItemStack ministerHead;

    /** The minister's head, named and with lore laid out like Hypixel's own "Mayor ..." item in the Calendar menu. */
    private static ItemStack ministerItem(Election e) {
        ItemStack head = headOf(e.minister().name());
        if (ministerStack != null && ministerFor == e && ministerHead == head) return ministerStack;
        ItemStack stack = head.isEmpty() ? new ItemStack(Items.PLAYER_HEAD) : head.copy();
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Minister " + e.minister().name())
            .withStyle(s -> s.withColor(ChatFormatting.YELLOW).withItalic(false)));
        List<Component> lore = new ArrayList<>();
        String with = e.mayor() == null ? "" : " and serves alongside Mayor " + e.mayor().name();
        for (String line : wrap("§7The minister was the runner-up of the last election" + with + ".")) lore.add(loreLine(line));
        lore.add(Component.empty());
        lore.add(loreLine("§8§m" + " ".repeat(20)));
        for (Perk p : e.minister().perks()) {
            lore.add(Component.empty());
            lore.add(loreLine("§e" + Sky2MLocation.strip(p.name())));
            for (String line : wrap("§7" + p.description())) lore.add(loreLine(line));
        }
        lore.add(Component.empty());
        for (String line : wrap("§8The minister's perk is available to all players until the closing of the next elections.")) lore.add(loreLine(line));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        ministerStack = stack;
        ministerFor = e;
        ministerHead = head;
        return stack;
    }

    private static Component loreLine(String legacy) {
        return SbcItems.parseLegacy(legacy).withStyle(s -> s.withItalic(false));
    }

    /** Word-wraps a §-coloured line at about Hypixel's lore width, carrying the colour onto the next line. */
    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String colour = "";
        int visible = 0;
        for (String word : text.split(" ")) {
            int len = Sky2MLocation.strip(word).length();
            if (visible > 0 && visible + 1 + len > 30) {
                out.add(line.toString());
                line = new StringBuilder(colour);
                visible = 0;
            }
            if (visible > 0) {
                line.append(' ');
                visible++;
            }
            line.append(word);
            visible += len;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("§[0-9a-fk-or]").matcher(line);
            while (m.find()) colour = m.group();
        }
        if (visible > 0) out.add(line.toString());
        return out;
    }

    private static final java.util.Map<String, ItemStack> HEADS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern TEXTURE = java.util.regex.Pattern.compile("Value:\\\\?\"([A-Za-z0-9+/=]+)\\\\?\"");

    /** The mayor's head from the NEU repo (their "PAUL_MAYOR_MONSTER" item), or EMPTY while it loads. */
    private static ItemStack headOf(String name) {
        String id = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "") + "_MAYOR_MONSTER";
        ItemStack head = HEADS.get(id);
        if (head != null) return head;
        HEADS.put(id, ItemStack.EMPTY);
        RepoItems.runAsync(() -> {
            try {
                JsonObject item = JsonParser.parseString(RepoItems.neuRepoFile("items/" + id + ".json")).getAsJsonObject();
                java.util.regex.Matcher m = TEXTURE.matcher(item.has("nbttag") ? item.get("nbttag").getAsString() : "");
                if (m.find()) {
                    String texture = m.group(1);
                    Minecraft.getInstance().execute(() -> HEADS.put(id, Compat.createSkull(texture)));
                }
            } catch (Exception ignored) {}
        });
        return ItemStack.EMPTY;
    }

    private static boolean isFiller(ItemStack stack) {
        return stack.isEmpty() || stack.getItem().toString().endsWith("stained_glass_pane") || stack.is(Items.GLASS_PANE);
    }

    private static void fetch(Runnable then) {
        if (!FETCHING.compareAndSet(false, true)) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15)).header("User-Agent", "Sky2M").build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
            FETCHING.set(false);
            fetchedAt = System.currentTimeMillis();
            if (error == null && response.statusCode() == 200) {
                try {
                    election = parse(JsonParser.parseString(response.body()).getAsJsonObject());
                } catch (Exception ignored) {}
            }
            // No data yet (Paul's +10 on the score depends on it): try again in 30 seconds, not 10 minutes.
            if (election == null) fetchedAt = System.currentTimeMillis() - REFRESH_MS + 30_000L;
            if (then != null) Minecraft.getInstance().execute(then);
        });
    }

    private static Election parse(JsonObject root) {
        JsonObject mayor = obj(root, "mayor");
        Candidate mayorC = mayor == null ? null : candidate(mayor);
        Candidate minister = null;
        JsonObject min = mayor == null ? null : obj(mayor, "minister");
        if (min != null) {
            JsonObject perk = obj(min, "perk");
            minister = new Candidate(str(min, "name"), perk == null ? List.of() : List.of(new Perk(str(perk, "name"), str(perk, "description"))), 0);
        }
        List<Candidate> running = new ArrayList<>();
        JsonObject current = obj(root, "current");
        int year = 0;
        if (current != null) {
            year = current.has("year") ? current.get("year").getAsInt() : 0;
            JsonArray list = current.has("candidates") && current.get("candidates").isJsonArray() ? current.getAsJsonArray("candidates") : new JsonArray();
            for (JsonElement e : list) if (e.isJsonObject()) running.add(candidate(e.getAsJsonObject()));
            running.sort(Comparator.comparingLong(Candidate::votes).reversed());
        }
        return new Election(mayorC, minister, running, year);
    }

    private static Candidate candidate(JsonObject o) {
        List<Perk> perks = new ArrayList<>();
        JsonArray list = o.has("perks") && o.get("perks").isJsonArray() ? o.getAsJsonArray("perks") : new JsonArray();
        for (JsonElement e : list) if (e.isJsonObject()) perks.add(new Perk(str(e.getAsJsonObject(), "name"), str(e.getAsJsonObject(), "description")));
        return new Candidate(str(o, "name"), perks, o.has("votes") ? o.get("votes").getAsLong() : 0);
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static long totalVotes(List<Candidate> running) {
        long total = 0;
        for (Candidate c : running) total += c.votes();
        return Math.max(1, total);
    }

    private static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        Election e = election;
        FeatureConfigs.MayorDisplay c = config();
        if (e == null || c == null) return out;
        if (e.mayor() != null) {
            out.add(Component.literal("Mayor: ").withStyle(ChatFormatting.GRAY).append(Component.literal(e.mayor().name()).withStyle(ChatFormatting.GOLD)));
            if (c.perks) for (Perk p : e.mayor().perks()) out.add(Component.literal(" • " + Sky2MLocation.strip(p.name())).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (e.minister() != null) {
            out.add(Component.literal("Minister: ").withStyle(ChatFormatting.GRAY).append(Component.literal(e.minister().name()).withStyle(ChatFormatting.AQUA)));
            if (c.perks) for (Perk p : e.minister().perks()) out.add(Component.literal(" • " + Sky2MLocation.strip(p.name())).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (c.election && !e.running().isEmpty()) {
            long total = totalVotes(e.running());
            Candidate lead = e.running().getFirst();
            out.add(Component.literal("Leading: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(lead.name() + " " + Math.round(lead.votes() * 100.0 / total) + "%").withStyle(ChatFormatting.GREEN)));
            for (int i = 1; i < e.running().size(); i++) {
                Candidate other = e.running().get(i);
                out.add(Component.literal("  " + other.name() + " " + Math.round(other.votes() * 100.0 / total) + "%").withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        return out;
    }

    /** The Show In Chat button and /s2 mayor. */
    public static void printToChat() {
        if (election == null || System.currentTimeMillis() - fetchedAt > REFRESH_MS) {
            Sky2MAlerts.chat(Component.literal("Loading the election...").withStyle(ChatFormatting.GRAY));
            fetch(MayorDisplay::print);
        } else {
            print();
        }
    }

    private static void print() {
        Election e = election;
        if (e == null) {
            Sky2MAlerts.chat(Component.literal("Couldn't load the election from Hypixel.").withStyle(ChatFormatting.RED));
            return;
        }
        if (e.mayor() != null) {
            Sky2MAlerts.chat(Component.literal("Mayor: ").withStyle(ChatFormatting.GRAY).append(Component.literal(e.mayor().name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));
            for (Perk p : e.mayor().perks()) Sky2MAlerts.chat(perkLine(p));
        }
        if (e.minister() != null) {
            Sky2MAlerts.chat(Component.literal("Minister: ").withStyle(ChatFormatting.GRAY).append(Component.literal(e.minister().name()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
            for (Perk p : e.minister().perks()) Sky2MAlerts.chat(perkLine(p));
        }
        if (e.running().isEmpty()) {
            Sky2MAlerts.chat(Component.literal("No election is open right now.").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        long total = totalVotes(e.running());
        Sky2MAlerts.chat(Component.literal("Election" + (e.year() > 0 ? " (year " + e.year() + ")" : "") + ", hover a perk for what it does:").withStyle(ChatFormatting.YELLOW));
        for (Candidate c : e.running()) {
            MutableComponent line = Component.literal(String.format(Locale.US, " %s %d%% ", c.name(), Math.round(c.votes() * 100.0 / total)))
                .withStyle(c == e.running().getFirst() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
            for (Perk p : c.perks()) {
                line.append(Component.literal("[" + Sky2MLocation.strip(p.name()) + "] ").withStyle(s -> s.withColor(ChatFormatting.GRAY)
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(Sky2MLocation.strip(p.description()))))));
            }
            Sky2MAlerts.chat(line);
        }
    }

    private static Component perkLine(Perk p) {
        return Component.literal(" • " + Sky2MLocation.strip(p.name())).withStyle(s -> s.withColor(ChatFormatting.YELLOW)
            .withHoverEvent(new HoverEvent.ShowText(Component.literal(Sky2MLocation.strip(p.description())))))
            .append(Component.literal(" " + Sky2MLocation.strip(p.description())).withStyle(ChatFormatting.DARK_GRAY));
    }
}
