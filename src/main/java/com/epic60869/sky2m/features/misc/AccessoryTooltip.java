package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.Sky2MStorageSearch;
import com.epic60869.sky2m.custom.RepoItems;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Accessory tooltip: a 1:1 port of Skyblocker's AccessoriesHelper and AccessoryTooltip (LGPL-3.0,
 * https://github.com/SkyblockerMod/Skyblocker). Says whether you're missing an accessory, have collected it, or
 * whether it's an upgrade, upgradable or a downgrade within its family. As in Skyblocker, "Upgradable" means you own
 * this accessory and a higher tier of it exists ("(1/3)": you have tier 1, the family's best is tier 3). Families and
 * tiers come from the accessory list Skyblocker uses (Aaron's Mod data via hysky.de); what you own is read, per
 * SkyBlock profile, from your Accessory Bag pages.
 */
public final class AccessoryTooltip {
    private static final String ACCESSORIES_URL = "https://hysky.de/api/accessories";
    static final Pattern ACCESSORY_BAG_TITLE = Pattern.compile("Accessory Bag(?: \\((?<page>\\d+)/\\d+\\))?");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Gson GSON = new Gson();
    private static final Set<String> EMPTY = Set.of();
    private static final Predicate<String> NON_EMPTY = s -> !s.isEmpty();
    private static final Predicate<Accessory> HAS_FAMILY = Accessory::hasFamily;
    private static final ToIntFunction<Accessory> ACCESSORY_TIER = Accessory::tier;

    public static final int COLLECTED_COLOUR = TextColor.GREEN.getValue();
    public static final int UPGRADE_COLOUR = 0x218BFF;
    public static final int UPGRADABLE_COLOUR = 0xF8D048;
    public static final int DOWNGRADE_COLOUR = TextColor.GRAY.getValue();
    public static final int MISSING_COLOUR = TextColor.RED.getValue();

    public static volatile Map<String, Accessory> ACCESSORY_DATA = Map.of();
    /** Profile id -> what's in that profile's Accessory Bag. */
    private static final Map<String, ProfileAccessoryData> COLLECTED_ACCESSORIES = new ConcurrentHashMap<>();
    private static Path file;

    private AccessoryTooltip() {}

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m").resolve("accessory-bag.json");
        load();
        RepoItems.runAsync(AccessoryTooltip::loadAccessories);

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (Compat.isOnSkyblock() && enabled() && !profileId().isEmpty() && screen instanceof ContainerScreen genericContainerScreen) {
                Matcher matcher = ACCESSORY_BAG_TITLE.matcher(genericContainerScreen.getTitle().getString());

                if (matcher.matches()) {
                    ScreenEvents.afterTick(screen).register(s -> {
                        ChestMenu handler = genericContainerScreen.getMenu();
                        int page = matcher.group("page") != null ? Integer.parseInt(matcher.group("page")) : 1;

                        collectAccessories(handler.slots.subList(0, handler.getRowCount() * 9), page);
                    });
                }
            }
        });

        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            if (!enabled() || !Compat.isOnSkyblock()) return;
            addToTooltip(stack, lines);
        });
    }

    private static boolean enabled() {
        Sky2MConfig config = Sky2MConfig.current();
        return config != null && config.misc.collectionTooltips.accessories;
    }

    private static String profileId() {
        return Sky2MStorageSearch.currentProfile();
    }

    private static void collectAccessories(List<Slot> slots, int page) {
        List<String> accessoryIds = slots.stream()
            .map(Slot::getItem)
            .map(Compat::neuName)
            .filter(NON_EMPTY)
            .toList();

        List<String> recombobulated = slots.stream()
            .map(Slot::getItem)
            .filter(stack -> Compat.getCustomData(stack).getIntOr("rarity_upgrades", 0) > 0)
            .map(Compat::neuName)
            .filter(NON_EMPTY)
            .toList();

        ProfileAccessoryData data = COLLECTED_ACCESSORIES.computeIfAbsent(profileId(), p -> ProfileAccessoryData.createDefault());
        Set<String> previous = data.pages().getOrDefault(page, EMPTY);
        Set<String> now = new HashSet<>(accessoryIds);
        if (now.equals(previous) && data.recombobulatedAccessories().containsAll(recombobulated)) return;
        data.recombobulatedAccessories().removeAll(previous); // Remove previous accessories.
        data.recombobulatedAccessories().addAll(recombobulated);
        data.pages().put(page, now);
        save();
    }

    public static Report calculateReport4Accessory(String accessoryId) {
        if (!ACCESSORY_DATA.containsKey(accessoryId) || profileId().isEmpty()) return new Report(AccessoryReport.INELIGIBLE, "");

        Accessory accessory = ACCESSORY_DATA.get(accessoryId);

        //Ignore rift-only accessories
        if (accessory.origin().orElse("").equals("RIFT")) return new Report(AccessoryReport.INELIGIBLE, "");

        Set<Accessory> collectedAccessories = getCollectedAccessories();

        // If the accessory doesn't belong to a family
        if (accessory.family().isEmpty()) {
            //If the player has this accessory or player doesn't have this accessory
            return collectedAccessories.contains(accessory) ? new Report(AccessoryReport.HAS_HIGHEST_TIER, "") : new Report(AccessoryReport.MISSING, "");
        }

        FamilyReport report = calculateFamilyReport(accessory, collectedAccessories);
        int highestTierInFamily = report.highestInFamily().tier();

        //If the player hasn't collected any accessory in same family
        if (report.highestCollectedInFamily().isEmpty()) return new Report(AccessoryReport.MISSING, String.format("(%d/%d)", accessory.tier(), highestTierInFamily));

        int highestTierCollectedInFamily = report.highestCollectedInFamily().get().tier();

        //If this accessory is the highest tier, and the player has the highest tier accessory in this family
        //This accounts for multiple accessories with the highest tier
        if (accessory.tier() == highestTierInFamily && highestTierCollectedInFamily == highestTierInFamily) return new Report(AccessoryReport.HAS_HIGHEST_TIER, "");

        //If this accessory is a higher tier than all the other collected accessories in the same family
        if (accessory.tier() > highestTierCollectedInFamily) return new Report(AccessoryReport.IS_GREATER_TIER, String.format("(%d→%d/%d)", highestTierCollectedInFamily, accessory.tier(), highestTierInFamily));

        //If this accessory is a lower tier than one already obtained from same family
        if (accessory.tier() < highestTierCollectedInFamily) return new Report(AccessoryReport.OWNS_BETTER_TIER, String.format("(%d→%d/%d)", highestTierCollectedInFamily, accessory.tier(), highestTierInFamily));

        //If there is an accessory in the same family that has a higher tier
        if (accessory.tier() < highestTierInFamily) return new Report(AccessoryReport.HAS_GREATER_TIER, String.format("(%d/%d)", highestTierCollectedInFamily, highestTierInFamily));

        return new Report(AccessoryReport.MISSING, String.format("(%d/%d)", accessory.tier(), highestTierInFamily));
    }

    public static FamilyReport calculateFamilyReport(Accessory accessory, Set<Accessory> collectedAccessories) {
        if (accessory.family().isEmpty()) throw new IllegalArgumentException("accessory family cannot be empty");
        Predicate<Accessory> hasSameFamily = accessory::hasSameFamily;
        return new FamilyReport(
            ACCESSORY_DATA.values().stream()
                .filter(HAS_FAMILY)
                .filter(hasSameFamily)
                .max(Comparator.comparingInt(ACCESSORY_TIER))
                .orElse(accessory),
            collectedAccessories.stream()
                .filter(HAS_FAMILY)
                .filter(hasSameFamily)
                .max(Comparator.comparingInt(ACCESSORY_TIER))
        );
    }

    public static Set<Accessory> getCollectedAccessories() {
        Map<String, Accessory> data = ACCESSORY_DATA;
        return COLLECTED_ACCESSORIES.computeIfAbsent(profileId(), p -> ProfileAccessoryData.createDefault()).pages().values().stream()
            .flatMap(Set::stream)
            .filter(data::containsKey)
            .map(data::get)
            .collect(Collectors.toSet());
    }

    public static boolean hasAccessory(String accessoryId) {
        return COLLECTED_ACCESSORIES.computeIfAbsent(profileId(), p -> ProfileAccessoryData.createDefault()).pages().values().stream()
            .anyMatch(set -> set.contains(accessoryId));
    }

    public static boolean isRecombobulated(String accessoryId) {
        return hasAccessory(accessoryId) && COLLECTED_ACCESSORIES.computeIfAbsent(profileId(), p -> ProfileAccessoryData.createDefault()).recombobulatedAccessories().contains(accessoryId);
    }

    // ---------------------------------------------------------------- tooltip (Skyblocker's AccessoryTooltip)

    private static void addToTooltip(ItemStack stack, List<Component> lines) {
        final String internalID = Compat.neuName(stack);
        if (internalID.isEmpty() || !ACCESSORY_DATA.containsKey(internalID)) return;
        Report report = calculateReport4Accessory(internalID);

        if (report.report() != AccessoryReport.INELIGIBLE) {
            MutableComponent title = Component.literal("Accessory: ").withColor(0xF57542);

            Component stateText = switch (report.report()) {
                case HAS_HIGHEST_TIER -> Component.literal("✔ Collected").withColor(COLLECTED_COLOUR);
                case IS_GREATER_TIER -> Component.literal("✦ Upgrade ").withColor(UPGRADE_COLOUR).append(Component.literal(report.detail()).withColor(0xF8F8FF));
                case HAS_GREATER_TIER -> Component.literal("↑ Upgradable ").withColor(UPGRADABLE_COLOUR).append(Component.literal(report.detail()).withColor(0xF8F8FF));
                case OWNS_BETTER_TIER -> Component.literal("↓ Downgrade ").withColor(DOWNGRADE_COLOUR).append(Component.literal(report.detail()).withColor(0xF8F8FF));
                case MISSING -> Component.literal("✖ Missing ").withColor(MISSING_COLOUR).append(Component.literal(report.detail()).withColor(0xF8F8FF));

                //Should never be the case
                default -> Component.literal("? Unknown").withStyle(ChatFormatting.GRAY);
            };

            lines.add(title.append(stateText));
        }
    }

    // ---------------------------------------------------------------- accessory data

    private static void loadAccessories() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ACCESSORIES_URL)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Sky2M/1.0").GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Sky2M] Accessory list download failed: HTTP " + response.statusCode());
                return;
            }
            Map<String, Accessory> loaded = new HashMap<>();
            for (var entry : JsonParser.parseString(response.body()).getAsJsonObject().entrySet()) {
                JsonObject o = entry.getValue().getAsJsonObject();
                String id = o.has("id") ? o.get("id").getAsString() : entry.getKey();
                loaded.put(entry.getKey(), new Accessory(id,
                    o.has("family") ? Optional.of(o.get("family").getAsString()) : Optional.empty(),
                    o.has("tier") ? o.get("tier").getAsInt() : 0,
                    o.has("origin") ? Optional.of(o.get("origin").getAsString()) : Optional.empty(),
                    !o.has("enrichable") || o.get("enrichable").getAsBoolean(),
                    !o.has("recombobulatable") || o.get("recombobulatable").getAsBoolean()));
            }
            ACCESSORY_DATA = loaded;
        } catch (Exception e) {
            System.err.println("[Sky2M] Accessory list download failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- saving

    /** Reads accessory-bag.json; also the older layout ({profile: {page: [ids]}}) from before recombobulated accessories were kept. */
    private static void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var profile : root.entrySet()) {
                JsonObject o = profile.getValue().getAsJsonObject();
                JsonObject pagesJson = o.has("pages") && o.get("pages").isJsonObject() ? o.getAsJsonObject("pages") : o;
                ProfileAccessoryData data = ProfileAccessoryData.createDefault();
                for (var page : pagesJson.entrySet()) {
                    if (!page.getValue().isJsonArray()) continue;
                    Set<String> ids = new HashSet<>();
                    for (JsonElement id : page.getValue().getAsJsonArray()) ids.add(id.getAsString());
                    data.pages().put(Integer.parseInt(page.getKey()), ids);
                }
                if (o.has("recombobulatedAccessories") && o.get("recombobulatedAccessories").isJsonArray()) {
                    for (JsonElement id : o.getAsJsonArray("recombobulatedAccessories")) data.recombobulatedAccessories().add(id.getAsString());
                }
                COLLECTED_ACCESSORIES.put(profile.getKey(), data);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not read accessory-bag.json: " + e.getMessage());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        COLLECTED_ACCESSORIES.forEach((profile, data) -> {
            JsonObject pages = new JsonObject();
            data.pages().forEach((page, ids) -> {
                JsonArray array = new JsonArray();
                ids.stream().sorted().forEach(array::add);
                pages.add(String.valueOf(page), array);
            });
            JsonArray recombobulated = new JsonArray();
            data.recombobulatedAccessories().stream().sorted().forEach(recombobulated::add);
            JsonObject o = new JsonObject();
            o.add("pages", pages);
            o.add("recombobulatedAccessories", recombobulated);
            root.add(profile, o);
        });
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not save accessory-bag.json: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- types

    private record ProfileAccessoryData(Map<Integer, Set<String>> pages, Set<String> recombobulatedAccessories) {
        private static ProfileAccessoryData createDefault() {
            return new ProfileAccessoryData(new ConcurrentHashMap<>(), ConcurrentHashMap.newKeySet());
        }
    }

    /**
     * @author AzureAaron
     * @implSpec <a href="https://github.com/AzureAaron/aaron-mod/blob/1.20/src/main/java/net/azureaaron/mod/commands/MagicalPowerCommand.java#L475">Aaron's Mod</a>
     */
    public record Accessory(String id, Optional<String> family, int tier, Optional<String> origin, boolean enrichable, boolean recombobulatable) {
        public boolean hasFamily() {
            return family.isPresent();
        }

        public boolean hasSameFamily(Accessory other) {
            return other.family().equals(this.family);
        }
    }

    public record FamilyReport(Accessory highestInFamily, Optional<Accessory> highestCollectedInFamily) {}

    public record Report(AccessoryReport report, String detail) {}

    public enum AccessoryReport {
        HAS_HIGHEST_TIER, //You've collected the highest tier - Collected
        IS_GREATER_TIER, //This accessory is an upgrade from the one in the same family that you already have - Upgrade -- Shows you what tier this accessory is in its family
        HAS_GREATER_TIER, //This accessory has a higher tier upgrade - Upgradable -- Shows you the highest tier accessory you've collected in that family
        OWNS_BETTER_TIER, //You've collected an accessory in this family with a higher tier - Downgrade -- Shows you the highest tier accessory you've collected in that family
        MISSING, //You don't have any accessories in this family - Missing
        INELIGIBLE
    }
}
