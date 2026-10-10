package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.misc.PartyCommands;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Finder stats, like Odin's Better Party Finder (https://github.com/odtheking/Odin, BSD-3-Clause): when someone
 * joins your dungeon group, their Catacombs level, class levels, secrets, floor times, magical power, armour and missing
 * key items are shown in chat (only you see it), with a [KICK] button. Optionally kicks players under your requirements.
 * /s2 cata <name> shows the same for anyone. Profiles come from a Hypixel API proxy (Odin's by default), since
 * Sky2M has no API key of its own.
 */
public final class PartyFinderStats {
    // Party Finder > [MVP+] Name joined the dungeon group! (Mage Level 30)
    private static final Pattern JOINED = Pattern.compile("^Party Finder > (?:\\[[^]]{1,7}] ?)?(?<name>\\w{1,16}) joined the dungeon group! \\(.*\\)$");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final long CACHE_MS = 5 * 60 * 1000L;
    /** Catacombs XP needed for each level 1 to 50; every level after 50 takes another 200M. */
    private static final long[] CATA_XP = {50, 75, 110, 160, 230, 330, 470, 670, 950, 1340, 1890, 2665, 3760, 5260, 7380, 10300,
        14400, 20000, 27600, 38000, 52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000, 1065000, 1410000,
        1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000, 15000000, 19000000, 24000000, 30000000,
        38000000, 48000000, 60000000, 75000000, 93000000, 116250000};
    private static final String[][] CLASSES = {{"healer", "Healer", "d"}, {"mage", "Mage", "b"}, {"berserk", "Berserk", "c"},
        {"archer", "Archer", "6"}, {"tank", "Tank", "a"}};
    /** {label, short label, item ids that count}: Odin's "Missing" line. */
    private static final String[][] KEY_ITEMS = {{"Wither Blade", "§5Blade", "HYPERION,ASTRAEA,SCYLLA,VALKYRIE"},
        {"Terminator", "§cTerm", "TERMINATOR"}};
    private static final String[][] KEY_PETS = {{"Golden Dragon", "§6GDrag", "GOLDEN_DRAGON"}, {"Ender Dragon", "§5EDrag", "ENDER_DRAGON"}};

    private record Cached(Stats stats, long at) {}
    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> KICKED = ConcurrentHashMap.newKeySet();

    private PartyFinderStats() {}

    private static FeatureConfigs.PartyFinderStats config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.partyFinderStats;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            FeatureConfigs.PartyFinderStats c = config();
            if (c == null || (!c.enabled && !c.autoKick)) return;
            Matcher m = JOINED.matcher(message.text().trim());
            if (!m.matches()) return;
            String name = m.group("name");
            if (name.equalsIgnoreCase(Minecraft.getInstance().getUser().getName())) return;
            if (c.autoKick && c.kickCache && KICKED.contains(name.toLowerCase(Locale.ROOT)) && PartyCommands.maybeLeader()) {
                kick(name, c.informKicked ? "kicked before" : null);
                say(Component.literal("Kicked " + name + ": you kicked them before. /s2 cata clearkicks to forget.").withStyle(ChatFormatting.YELLOW));
                return;
            }
            lookup(name).whenComplete((stats, error) -> Minecraft.getInstance().execute(() -> {
                if (error != null || stats == null) {
                    say(Component.literal("Couldn't load " + name + "'s stats: " + reason(error)).withStyle(ChatFormatting.RED));
                    return;
                }
                if (c.enabled) show(stats, c.kickButton);
                if (c.autoKick && PartyCommands.maybeLeader()) autoKick(stats, c);
            }));
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("cata")
                    .then(ClientCommands.literal("clearkicks").executes(ctx -> {
                        KICKED.clear();
                        say(Component.literal("Forgot everyone Party Finder Stats kicked.").withStyle(ChatFormatting.YELLOW));
                        return 1;
                    }))
                    .then(ClientCommands.argument("player", StringArgumentType.word()).executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "player");
                        say(Component.literal("Loading " + name + "'s dungeon stats...").withStyle(ChatFormatting.GRAY));
                        lookup(name).whenComplete((stats, error) -> Minecraft.getInstance().execute(() -> {
                            if (error != null || stats == null) say(Component.literal("Couldn't load " + name + "'s stats: " + reason(error)).withStyle(ChatFormatting.RED));
                            else show(stats, false);
                        }));
                        return 1;
                    }))));
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ lookup

    private record Floor(long sPlusMs, long bestMs, int comps) {}

    private record Stats(String name, double cataXp, double[] classXp, String selectedClass, long secrets, int runs, int watcherKills,
                         Floor[] normal, Floor[] master, int magicalPower, boolean inventoryApi, List<String[]> armour, List<String[]> missing) {
        double cata() {
            return level(cataXp);
        }
    }

    private static String reason(Throwable error) {
        if (error == null) return "no SkyBlock profile";
        Throwable t = error.getCause() != null ? error.getCause() : error;
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static CompletableFuture<Stats> lookup(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MS) return CompletableFuture.completedFuture(cached.stats());
        return get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name).thenCompose(body -> {
            JsonObject profile = JsonParser.parseString(body).getAsJsonObject();
            if (!profile.has("id")) throw new IllegalStateException("no such player");
            String uuid = profile.get("id").getAsString().replace("-", "");
            String realName = profile.has("name") ? profile.get("name").getAsString() : name;
            FeatureConfigs.PartyFinderStats c = config();
            String base = c == null || c.apiUrl.isBlank() ? "https://api.odtheking.com/hypixel/" : c.apiUrl.trim();
            if (!base.endsWith("/")) base += "/";
            return get(base + "get/" + uuid).thenApply(data -> parse(realName, uuid, JsonParser.parseString(data).getAsJsonObject()));
        }).whenComplete((stats, error) -> {
            if (stats != null) CACHE.put(key, new Cached(stats, System.currentTimeMillis()));
        });
    }

    private static CompletableFuture<String> get(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", "Sky2M").build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            if (r.statusCode() == 404 || r.statusCode() == 204) throw new IllegalStateException("no such player");
            if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
            return r.body();
        });
    }

    private static Stats parse(String name, String uuid, JsonObject root) {
        JsonObject member = null;
        JsonArray profiles = root.has("profiles") && root.get("profiles").isJsonArray() ? root.getAsJsonArray("profiles") : new JsonArray();
        for (JsonElement p : profiles) {
            JsonObject profile = p.getAsJsonObject();
            JsonObject m = obj(obj(profile, "members"), uuid);
            if (m == null) continue;
            if (member == null || bool(profile, "selected")) member = m;
        }
        if (member == null) return null;
        JsonObject dungeons = obj(member, "dungeons");
        JsonObject types = obj(dungeons, "dungeon_types");
        JsonObject cata = obj(types, "catacombs"), master = obj(types, "master_catacombs");
        JsonObject classes = obj(dungeons, "player_classes");
        double[] classXp = new double[CLASSES.length];
        for (int i = 0; i < CLASSES.length; i++) classXp[i] = num(obj(classes, CLASSES[i][0]), "experience");
        int runs = 0;
        for (JsonObject type : new JsonObject[]{cata, master}) {
            JsonObject comps = obj(type, "tier_completions");
            if (comps != null) for (Map.Entry<String, JsonElement> e : comps.entrySet()) if (!e.getKey().equals("total") && !e.getKey().equals("0")) runs += e.getValue().getAsInt();
        }
        int watcher = 0;
        JsonObject watcherKills = obj(cata, "watcher_kills");
        if (watcherKills != null) for (Map.Entry<String, JsonElement> e : watcherKills.entrySet()) watcher += e.getValue().getAsInt();
        JsonObject inventory = obj(member, "inventory");
        boolean inventoryApi = inventory != null && obj(inventory, "ender_chest_contents") != null;
        int mp = (int) num(obj(member, "accessory_bag_storage"), "highest_magical_power");
        Set<String> ids = inventoryApi ? itemIds(inventory) : Set.of();
        List<String[]> missing = new ArrayList<>();
        if (inventoryApi) {
            for (String[] item : KEY_ITEMS) {
                boolean has = false;
                for (String id : item[2].split(",")) has |= ids.stream().anyMatch(i -> i.contains(id));
                if (!has) missing.add(item);
            }
        }
        Set<String> pets = new HashSet<>();
        JsonObject petsData = obj(member, "pets_data");
        JsonArray petList = petsData != null && petsData.has("pets") && petsData.get("pets").isJsonArray() ? petsData.getAsJsonArray("pets") : new JsonArray();
        for (JsonElement p : petList) if (p.isJsonObject() && p.getAsJsonObject().has("type")) pets.add(p.getAsJsonObject().get("type").getAsString());
        for (String[] pet : KEY_PETS) if (!pets.contains(pet[2])) missing.add(pet);
        return new Stats(name, num(cata, "experience"), classXp, str(dungeons, "selected_dungeon_class"), (long) num(dungeons, "secrets"),
            runs, watcher, floors(cata), floors(master), mp, inventoryApi, inventoryApi ? armour(inventory) : List.of(), missing);
    }

    private static Floor[] floors(JsonObject type) {
        Floor[] out = new Floor[8];
        for (int f = 0; f <= 7; f++) {
            String k = String.valueOf(f);
            out[f] = new Floor((long) num(obj(type, "fastest_time_s_plus"), k), (long) num(obj(type, "fastest_time"), k), (int) num(obj(type, "tier_completions"), k));
        }
        return out;
    }

    /** {name, lore} of each armour piece, helmet first. */
    private static List<String[]> armour(JsonObject inventory) {
        List<CompoundTag> items = items(obj(inventory, "inv_armor"));
        List<String[]> out = new ArrayList<>();
        for (int i = items.size() - 1; i >= 0; i--) {
            CompoundTag display = items.get(i).getCompoundOrEmpty("tag").getCompoundOrEmpty("display");
            String itemName = display.getStringOr("Name", "");
            if (itemName.isEmpty()) continue;
            StringBuilder lore = new StringBuilder(itemName);
            ListTag lines = display.getListOrEmpty("Lore");
            for (int l = 0; l < lines.size(); l++) lore.append('\n').append(lines.getStringOr(l, ""));
            out.add(new String[]{itemName, lore.toString()});
        }
        return out;
    }

    /** Every SkyBlock item id in the inventory, ender chest, backpacks and wardrobe. */
    private static Set<String> itemIds(JsonObject inventory) {
        List<JsonObject> blobs = new ArrayList<>();
        for (String k : new String[]{"inv_contents", "ender_chest_contents", "wardrobe_contents", "inv_armor"}) {
            JsonObject b = obj(inventory, k);
            if (b != null) blobs.add(b);
        }
        JsonObject backpacks = obj(inventory, "backpack_contents");
        if (backpacks != null) for (Map.Entry<String, JsonElement> e : backpacks.entrySet()) if (e.getValue().isJsonObject()) blobs.add(e.getValue().getAsJsonObject());
        JsonObject bags = obj(inventory, "bag_contents");
        if (bags != null) for (Map.Entry<String, JsonElement> e : bags.entrySet()) if (e.getValue().isJsonObject()) blobs.add(e.getValue().getAsJsonObject());
        Set<String> ids = new HashSet<>();
        for (JsonObject blob : blobs) {
            for (CompoundTag item : items(blob)) {
                String id = item.getCompoundOrEmpty("tag").getCompoundOrEmpty("ExtraAttributes").getStringOr("id", "");
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    private static List<CompoundTag> items(JsonObject blob) {
        List<CompoundTag> out = new ArrayList<>();
        if (blob == null || !blob.has("data")) return out;
        try {
            byte[] bytes = Base64.getDecoder().decode(blob.get("data").getAsString());
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("i");
            for (int i = 0; i < list.size(); i++) {
                Tag t = list.get(i);
                if (t instanceof CompoundTag c) out.add(c);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    private static double num(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static double level(double xp) {
        int level = 0;
        for (long need : CATA_XP) {
            if (xp < need) return level + xp / need;
            xp -= need;
            level++;
        }
        return level + xp / 200_000_000d;
    }

    // ------------------------------------------------------------------------------------------------ display

    private static void show(Stats s, boolean kickButton) {
        double classAvg = 0;
        for (double xp : s.classXp()) classAvg += Math.min(50, level(xp));
        classAvg /= s.classXp().length;
        String line = "§8§m" + " ".repeat(12) + "§r";
        MutableComponent out = Component.literal(line + " §b" + s.name() + " " + line + "\n");

        out.append(hover("§7Cata: §e" + fmt(s.cata(), 2), "§7Catacombs Level\n§7XP: §b" + big(s.cataXp())));
        double avg = s.runs() > 0 ? (double) s.secrets() / s.runs() : 0;
        out.append(hover(" §8| §7Secrets: §e" + big(s.secrets()) + " §8(§b" + fmt(avg, 1) + "§8)",
            "§7Total Secrets: §e" + big(s.secrets()) + "\n§7Total Runs: §b" + s.runs() + "\n§7Per Run: §a" + fmt(avg, 2)));
        out.append(hover(" §8| §7Blood: §c" + big(s.watcherKills()), "§7Watcher kills: §c" + big(s.watcherKills())));
        out.append("\n§7Classes: ");
        for (int i = 0; i < CLASSES.length; i++) {
            String colour = "§" + CLASSES[i][2];
            boolean selected = CLASSES[i][0].equals(s.selectedClass());
            out.append(hover(colour + (selected ? "§n" : "") + fmt(level(s.classXp()[i]), 1) + "§r",
                colour + CLASSES[i][1] + (selected ? " §7(selected)" : "") + "\n§7XP: §b" + big(s.classXp()[i])));
            if (i < CLASSES.length - 1) out.append("§8/");
        }
        out.append(" §8(§7Avg: §a" + fmt(classAvg, 1) + "§8)\n");
        out.append("§7Floors: ").append(hover("§6Normal", floorHover(s.normal(), "§6§lNormal Floors", "§eF")));
        out.append(" §8| ").append(hover("§cMaster", floorHover(s.master(), "§c§lMaster Floors", "§cM")));
        out.append(" §8| §7MP: " + (s.magicalPower() > 0 ? "§d" + big(s.magicalPower()) : "§8?"));
        out.append(" §8| §7F7: " + time(s.normal()[7]) + " §7M7: " + time(s.master()[7]) + "\n");
        if (!s.inventoryApi()) out.append("§7Armor: §cInventory API off\n");
        else if (!s.armour().isEmpty()) {
            out.append("§7Armor: ");
            String[] icons = {"⛑", "🛡", "👖", "👢"};
            for (int i = 0; i < s.armour().size(); i++) {
                out.append(hover("§f" + icons[Math.min(i, 3)], s.armour().get(i)[1]));
                if (i < s.armour().size() - 1) out.append(" §8| ");
            }
            out.append("\n");
        }
        if (!s.missing().isEmpty()) {
            out.append("§7Missing: ");
            for (int i = 0; i < s.missing().size(); i++) {
                out.append(hover("§c✖ " + s.missing().get(i)[1], "§cMissing " + s.missing().get(i)[0]));
                if (i < s.missing().size() - 1) out.append(" §8| ");
            }
            out.append("\n");
        }
        if (kickButton) {
            out.append(Component.literal("[KICK]").withStyle(st -> st.withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/party kick " + s.name()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Kick " + s.name() + " from the party")))));
            out.append(" ");
        }
        out.append("§8§m" + " ".repeat(kickButton ? 22 : 30));
        say(out);
    }

    private static String floorHover(Floor[] floors, String title, String prefix) {
        StringBuilder sb = new StringBuilder(title);
        for (int f = 1; f <= 7; f++) sb.append('\n').append(prefix).append(f).append(": ").append(time(floors[f])).append(" §8(§b").append(floors[f].comps()).append("§8)");
        return sb.toString();
    }

    private static String time(Floor f) {
        if (f.sPlusMs() > 0) return "§a" + clock(f.sPlusMs());
        if (f.bestMs() > 0) return "§7" + clock(f.bestMs());
        return "§8None";
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    private static MutableComponent hover(String text, String tip) {
        return Component.literal(text).withStyle(st -> st.withHoverEvent(new HoverEvent.ShowText(Component.literal(tip))));
    }

    private static String fmt(double v, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", v);
    }

    private static String big(double v) {
        if (v >= 1e9) return fmt(v / 1e9, 2) + "B";
        if (v >= 1e6) return fmt(v / 1e6, 2) + "M";
        if (v >= 1e4) return fmt(v / 1e3, 1) + "k";
        return String.valueOf((long) v);
    }

    // ------------------------------------------------------------------------------------------------ auto kick

    private static void autoKick(Stats s, FeatureConfigs.PartyFinderStats c) {
        List<String> reasons = new ArrayList<>();
        Floor floor = (c.masterMode ? s.master() : s.normal())[c.floor];
        String label = (c.masterMode ? "M" : "F") + c.floor;
        if (c.maxPbSeconds > 0) {
            if (floor.sPlusMs() <= 0) reasons.add("no S+ on " + label);
            else if (floor.sPlusMs() / 1000 > c.maxPbSeconds) reasons.add(label + " PB " + clock(floor.sPlusMs()) + " > " + clock(c.maxPbSeconds * 1000L));
        }
        if (c.minSecretsK > 0 && s.secrets() < c.minSecretsK * 1000L) reasons.add("secrets " + big(s.secrets()) + " < " + c.minSecretsK + "k");
        if (c.minCata > 0 && s.cata() < c.minCata) reasons.add("cata " + fmt(s.cata(), 1) + " < " + c.minCata);
        if (s.inventoryApi() || s.magicalPower() > 0) {
            if (c.minMagicalPower > 0 && s.magicalPower() < c.minMagicalPower) reasons.add("MP " + s.magicalPower() + " < " + c.minMagicalPower);
        }
        if (!s.inventoryApi() && c.apiOffKick) reasons.add("inventory API off");
        if (reasons.isEmpty()) return;
        String why = String.join(", ", reasons);
        KICKED.add(s.name().toLowerCase(Locale.ROOT));
        kick(s.name(), c.informKicked ? why : null);
        say(Component.literal("Kicked " + s.name() + ": " + why).withStyle(ChatFormatting.YELLOW));
    }

    private static void kick(String name, String why) {
        if (why != null) PartyCommands.partyChat("Kicked " + name + " (" + why + ")");
        PartyCommands.queueCommand("party kick " + name);
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(text));
    }
}
