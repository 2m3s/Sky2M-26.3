package com.epic60869.sky2m.items;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.epic60869.sky2m.sb.utils.Location;
import com.epic60869.sky2m.sb.utils.Utils;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import com.mojang.blaze3d.platform.InputConstants;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.epic60869.sky2m.gui.Sky2MStorageSearchScreen;
import com.epic60869.sky2m.misc.Sky2MCustom;

/**
 * Global Hypixel SkyBlock storage search.
 *
 * It learns Ender Chest and Backpack pages as the player opens them, keeps the
 * last known contents locally, and provides a fast searchable index. It never
 * moves or clicks items by itself.
 */
public final class Sky2MStorageSearch {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "sky2m-storage-search.json";

    private static final Pattern ENDER_CHEST = Pattern.compile("(?i)ender\\s+chest.*?(?:#|\\(|\\s)(\\d+)(?:\\)|\\s|$)");
    private static final Pattern BACKPACK = Pattern.compile("(?i)(?:small|medium|large|greater|jumbo)?\\s*backpack.*?(?:#|\\(|\\s)(\\d+)(?:\\)|\\s|$)");

    private static final long CAPTURE_INTERVAL_MS = 400L;
    private static final long SAVE_INTERVAL_MS = 1200L;
    private static final long INVENTORY_CAPTURE_INTERVAL_MS = 500L;

    private static final Map<String, Page> pages = new LinkedHashMap<>();
    private static final Map<String, Page> inventoryPages = new LinkedHashMap<>();
    private static Path configDir;
    private static boolean initialized;
    private static long lastCapture;
    private static long lastInventoryCapture;
    private static long lastSave;
    private static boolean dirty;
    private static boolean previousOpenKey;
    private static Result pendingHighlight;

    private record Page(String type, int number, String label, String blob, long updatedMs) {}

    public record Result(ItemStack stack, String name, String id, String lore,
                         String location, String key, String type, int number, int slot) {
        /** SkyOcean's search categories: where the item is kept. */
        public Category category() {
            return switch (type) {
                case "ENDER_CHEST", "BACKPACK" -> Category.STORAGE;
                case "CHEST" -> Category.ISLAND;
                case "MUSEUM" -> Category.MUSEUM;
                default -> Category.INVENTORY;
            };
        }

        /** The lines SkyOcean adds under the item's tooltip: where it is and what clicking does. */
        public List<Component> contextLines() {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(location).withStyle(ChatFormatting.GRAY));
            String click = switch (type) {
                case "ENDER_CHEST" -> "Click to open enderchest!";
                case "BACKPACK" -> "Click to open backpack!";
                case "CHEST" -> onPrivateIsland() ? "Click to highlight chest!" : "Click to warp to island and highlight chest!";
                case "MUSEUM" -> "Click to warp to museum!";
                default -> "Click to open your inventory!";
            };
            lines.add(Component.literal(click).withStyle(ChatFormatting.YELLOW));
            return lines;
        }
    }

    public enum Category {
        ALL("All", Items.COMPASS),
        STORAGE("Storage", Items.ENDER_CHEST),
        ISLAND("Island", Items.CHEST),
        MUSEUM("Museum", Items.GOLD_BLOCK),
        INVENTORY("Inventory", Items.LEATHER_CHESTPLATE);

        public final String label;
        public final Item icon;

        Category(String label, Item icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    /** Current SkyBlock profile id (from "Profile ID: ..."), or "" until Hypixel has told us. */
    private static String profile = "";

    /** The current SkyBlock profile id ("" until known); the storage overlay keeps its pages per profile too. */
    public static String currentProfile() {
        return profile;
    }

    /** Items to a compact string (compressed NBT, base64), or null without a world to encode against. */
    public static String encodeItems(List<ItemStack> stacks) {
        return encode(stacks);
    }

    /** The items from {@link #encodeItems}, or null if they can't be read yet (no world loaded). */
    public static List<ItemStack> decodeItems(String blob) {
        return decodeUncached(blob);
    }
    private static final String PROFILE_KEY = "_profile";
    private static final java.util.regex.Pattern PROFILE_ID = java.util.regex.Pattern.compile("^Profile ID: (?<id>[0-9a-fA-F-]+)$");

    private Sky2MStorageSearch() {}

    public static void init(Path dir) {
        configDir = dir;
        // The saved pages can be big: parse them off the render thread, then fill the maps on it (everything else reads them there).
        java.util.concurrent.CompletableFuture.supplyAsync(Sky2MStorageSearch::parseSaved)
            .thenAccept(loaded -> Minecraft.getInstance().execute(() -> applyLoaded(loaded)))
            .exceptionally(e -> {
                System.err.println("[Sky2M] Failed to load storage search cache: " + e.getMessage());
                Minecraft.getInstance().execute(() -> initialized = true);
                return null;
            });
        // Pages are stored per SkyBlock profile so different profiles (e.g. an ironman) never share storage.
        com.epic60869.sky2m.features.core.Sky2MChat.onChat(message -> {
            java.util.regex.Matcher m = PROFILE_ID.matcher(message.text().trim());
            if (m.matches()) {
                String id = m.group("id").toLowerCase(Locale.ROOT);
                if (!id.equals(profile)) {
                    profile = id;
                    dirty = true; // remember it for next launch
                }
            }
        });
        // The profile is kept between joins and launches (saved with the cache): Hypixel only says "Profile ID"
        // when you join SkyBlock, and waiting for it again meant storage was never saved or searchable.

        // SkyOcean's ChestTracker: remember which chest on the island was opened, and forget it when it's broken.
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && onPrivateIsland()) rememberClickedChest(level, hit.getBlockPos());
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            if (onPrivateIsland() && state.getBlock() instanceof ChestBlock) removeChestAt(pos);
        });
        com.epic60869.sky2m.features.core.Sky2MWorldRender.register(Sky2MStorageSearch::renderChestHighlights);
    }

    /** Key prefix for the current server and profile. Keys are "server|profile|type|number". */
    private static String profilePrefix(Minecraft mc) {
        String server = "unknown";
        if (isHypixel(mc)) return "hypixel|" + profile + "|";
        try {
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                server = mc.getCurrentServer().ip.toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {}
        return server + "|" + profile + "|";
    }

    private static boolean currentProfile(String key) {
        return !profile.isEmpty() && key.startsWith(profilePrefix(Minecraft.getInstance()));
    }

    public static void tick(Minecraft mc) {
        if (!initialized || mc.player == null) return;

        captureOpenStorage(mc);
        capturePlayerInventory(mc);
        applyPendingHighlight(mc);

        boolean ctrl = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(InputConstants.KEY_RCONTROL);
        boolean f = InputConstants.isKeyDown(InputConstants.KEY_F);
        boolean open = ctrl && f;

        if (open && !previousOpenKey && mc.gui.screen() == null && isHypixel(mc)) {
            open(mc, "");
        }
        previousOpenKey = open;

        if (dirty && System.currentTimeMillis() - lastSave >= SAVE_INTERVAL_MS) {
            save();
        }
    }

    public static void open(Minecraft mc, String query) {
        if (!isHypixel(mc)) return;
        Screen parent = mc.gui.screen();
        mc.gui.setScreen(new Sky2MStorageSearchScreen(parent, query == null ? "" : query));
    }

    /** Server brand, any address containing "hypixel" (with or without a port) or the SkyBlock sidebar. */
    public static boolean isHypixel(Minecraft mc) {
        return Sky2MCustom.isHypixel(mc);
    }

    public static List<Result> search(Minecraft mc, String query, boolean lore, boolean inventory) {
        List<Result> results = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

        for (Map.Entry<String, Page> entry : pages.entrySet()) {
            if (!currentProfile(entry.getKey())) continue;
            Page page = entry.getValue();
            List<ItemStack> contents = decode(page.blob());
            if (contents == null) continue;

            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (stack == null || stack.isEmpty()) continue;
                boolean storagePage = page.type().equals("ENDER_CHEST") || page.type().equals("BACKPACK");
                if (storagePage && !isSearchableStorageItem(stack)) continue;

                SearchText text = searchable(stack);
                if (!q.isEmpty()
                        && !text.name().contains(q)
                        && !text.id().contains(q)
                        && (!lore || !text.lore().contains(q))) {
                    continue;
                }

                int row = i / 9 + 1;
                int col = i % 9 + 1;
                String location = page.type().equals("MUSEUM") ? page.label()
                    : page.label() + " · slot " + (i + 1) + " (r" + row + " c" + col + ")";
                results.add(new Result(stack.copy(), text.displayName(), text.id(), text.lore(),
                        location, entry.getKey(), page.type(), page.number(), i));
            }
        }

        if (inventory) {
            for (Map.Entry<String, Page> entry : inventoryPages.entrySet()) {
                if (!currentProfile(entry.getKey())) continue;
                Page page = entry.getValue();
                List<ItemStack> contents = decode(page.blob());
                if (contents == null) continue;
                for (int i = 0; i < contents.size(); i++) {
                    ItemStack stack = contents.get(i);
                    if (stack == null || stack.isEmpty()) continue;
                    SearchText text = searchable(stack);
                    if (!q.isEmpty()
                            && !text.name().contains(q)
                            && !text.id().contains(q)
                            && (!lore || !text.lore().contains(q))) continue;
                    results.add(new Result(stack.copy(), text.displayName(), text.id(), text.lore(),
                            page.label() + " · " + inventoryLocation(i), entry.getKey(), "INVENTORY", 0, i));
                }
            }
        }

        results.sort(Comparator
                .comparing((Result r) -> r.name().toLowerCase(Locale.ROOT))
                .thenComparing(Result::location));
        return results;
    }

    public static int cachedStorageCount() {
        return (int) pages.keySet().stream().filter(Sky2MStorageSearch::currentProfile).count();
    }

    public static long oldestCacheAgeMs() {
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Page> entry : pages.entrySet()) {
            if (currentProfile(entry.getKey())) oldest = Math.min(oldest, entry.getValue().updatedMs());
        }
        if (oldest == Long.MAX_VALUE) return -1L;
        return Math.max(0L, System.currentTimeMillis() - oldest);
    }

    public static void openResult(Minecraft mc, Result result) {
        pendingHighlight = result;
        highlightUntil = System.currentTimeMillis() + HIGHLIGHT_MS;
        if ("CHEST".equals(result.type())) {
            mc.gui.setScreen(null);
            highlightedChests.clear();
            highlightedChests.add(chestBox(result.key()));
            if (!onPrivateIsland() && mc.player != null) mc.player.connection.sendCommand("warp island");
            return;
        }
        if ("MUSEUM".equals(result.type())) {
            mc.gui.setScreen(null);
            if (mc.player != null) mc.player.connection.sendCommand("warp museum");
            return;
        }
        if ("INVENTORY".equals(result.type())) {
            mc.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
            return;
        }
        if (result.type().equals("ENDER_CHEST") && result.number() > 0) {
            mc.gui.setScreen(null);
            if (mc.player != null && mc.player.connection != null) {
                mc.player.connection.sendCommand("ec " + result.number());
            } else {
                pendingHighlight = null;
            }
        } else if (result.type().equals("BACKPACK") && result.number() > 0) {
            mc.gui.setScreen(null);
            if (mc.player != null && mc.player.connection != null) {
                mc.player.connection.sendCommand("bp " + result.number());
            } else {
                pendingHighlight = null;
            }
        }
    }

    public static boolean shouldHighlight(ItemStack stack) {
        if (pendingHighlight == null || stack == null || stack.isEmpty()) return false;
        if (System.currentTimeMillis() > highlightUntil) {
            pendingHighlight = null;
            return false;
        }
        if (Minecraft.getInstance().gui.screen() instanceof Sky2MStorageSearchScreen) return false;
        ItemStack reference = pendingHighlight.stack();
        // Items without a SkyBlock id (vanilla blocks in a chest) can only be matched loosely.
        if (customData(reference).getStringOr("id", "").isEmpty()) return sameSearchItem(stack, reference);
        return referenceMatches(reference, stack);
    }

    /** Kept so the highlight lasts: SkyOcean clears it after a while, not after the first frame. */
    public static void consumeHighlight() {}

    private static final long HIGHLIGHT_MS = 60_000L;
    private static long highlightUntil;

    private static CompoundTag customData(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    /** SkyOcean's ReferenceItemFilter: same SkyBlock id, and the reference's enchantments, attributes and reforge. */
    public static boolean referenceMatches(ItemStack reference, ItemStack other) {
        CompoundTag ref = customData(reference);
        String id = ref.getStringOr("id", "");
        if (id.isEmpty()) return false;
        if (other == reference) return true;
        CompoundTag data = customData(other);
        if (!id.equals(data.getStringOr("id", ""))) return false;
        for (String key : List.of("attributes", "enchantments")) {
            if (ref.get(key) instanceof CompoundTag refMap) {
                if (!(data.get(key) instanceof CompoundTag otherMap) || !containsAll(refMap, otherMap)) return false;
            }
        }
        String modifier = ref.getStringOr("modifier", "");
        return modifier.isEmpty() || modifier.equals(data.getStringOr("modifier", ""));
    }

    /** SkyOcean's ItemMatcher: whether two stacks are the same item, so the search shows them as one. */
    public static boolean sameForStacking(ItemStack first, ItemStack second) {
        if (first.getItem() != second.getItem()) return false;
        CompoundTag a = customData(first);
        CompoundTag b = customData(second);
        for (String key : List.of("id", "enchantments", "attributes", "modifier")) {
            Tag x = a.get(key);
            Tag y = b.get(key);
            if (x instanceof CompoundTag cx && y instanceof CompoundTag cy) {
                if (!containsAll(cx, cy)) return false;
            } else if (!Objects.equals(x, y)) {
                return false;
            }
        }
        Component nameA = first.get(DataComponents.CUSTOM_NAME);
        Component nameB = second.get(DataComponents.CUSTOM_NAME);
        if (nameA == null || nameB == null) return nameA == null && nameB == null;
        return nameA.getString().equalsIgnoreCase(nameB.getString());
    }

    private static boolean containsAll(CompoundTag first, CompoundTag second) {
        for (String key : first.keySet()) {
            if (!Objects.equals(first.get(key), second.get(key))) return false;
        }
        return true;
    }

    private static boolean sameSearchItem(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        SearchText aa = searchable(a);
        SearchText bb = searchable(b);
        return aa.id().equals(bb.id())
                && aa.name().equals(bb.name())
                && aa.lore().equals(bb.lore());
    }

    private static void applyPendingHighlight(Minecraft mc) {
        // Do not move the native GLFW cursor here. On 26.2 that can race the
        // container's input/render path and crash when another mod replaces the
        // screen during an /ec or /bp command. The result remains selected by
        // the search UI and the opened container is left untouched.
        if (pendingHighlight == null || mc.gui.screen() == null) return;
        Result result = pendingHighlight;
        if ("INVENTORY".equals(result.type())) {
            return;
        }
        if (!(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)) return;
        // The highlight stays until it times out, like SkyOcean's.
    }

    private static String cleanTitle(String title) {
        return title.replaceAll("§[0-9A-FK-ORa-fk-or]", "")
            .replaceAll("\\s+", " ")
            .trim().toLowerCase(Locale.ROOT);
    }

    private static void capturePlayerInventory(Minecraft mc) {
        if (!isHypixel(mc) || mc.player == null || profile.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastInventoryCapture < INVENTORY_CAPTURE_INTERVAL_MS) return;
        lastInventoryCapture = now;
        List<ItemStack> contents = new ArrayList<>(mc.player.getInventory().getContainerSize());
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            contents.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        String key = inventoryCacheKey(mc);
        // Encoding is NBT + gzip + Base64 of 41 stacks: only when something in the inventory actually changed.
        if (key.equals(lastInventoryKey) && sameStacks(lastInventoryContents, contents)) return;
        String blob = encode(contents);
        if (blob == null) return;
        lastInventoryKey = key;
        lastInventoryContents = contents;
        Page old = inventoryPages.get(key);
        if (old == null || !old.blob().equals(blob)) {
            inventoryPages.put(key, new Page("INVENTORY", 0, "Your Inventory", blob, now));
            dirty = true;
        }
    }

    private static String lastInventoryKey;
    private static List<ItemStack> lastInventoryContents;

    private static boolean sameStacks(List<ItemStack> a, List<ItemStack> b) {
        if (a == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ItemStack x = a.get(i), y = b.get(i);
            if (x.getCount() != y.getCount() || !ItemStack.isSameItemSameComponents(x, y)) return false;
        }
        return true;
    }

    private static String inventoryCacheKey(Minecraft mc) {
        String uuid = mc.player == null ? "unknown" : mc.player.getUUID().toString();
        return profilePrefix(mc) + "INVENTORY|" + uuid;
    }

    private static void captureOpenStorage(Minecraft mc) {
        if (!isHypixel(mc) || profile.isEmpty()) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> container)) return;
        if (container.getMenu().slots.size() <= 36) return;

        long now = System.currentTimeMillis();
        if (now - lastCapture < CAPTURE_INTERVAL_MS) return;
        lastCapture = now;

        if (captureIslandChest(mc, container, now) || captureMuseum(container, now)) return;

        StorageTarget target = identify(cleanTitle(container.getTitle().getString()));
        if (target == null) return;

        List<Slot> slots = container.getMenu().slots;
        int count = Math.max(0, slots.size() - 36);
        if (count == 0) count = Math.min(54, slots.size());
        if (count <= 0) return;

        List<ItemStack> contents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ItemStack stack = slots.get(i).getItem();
            // Persist only real storage contents. GUI filler/navigation controls are
            // deliberately written as empty slots so they can never pollute search.
            contents.add(isSearchableStorageItem(stack) ? stack.copy() : ItemStack.EMPTY);
        }

        String key = cacheKey(mc, target.type(), target.number());
        Page old = pages.get(key);
        String blob = encode(contents);
        if (blob == null) return;

        if (old == null || !old.blob().equals(blob) || !old.label().equals(target.label())) {
            pages.put(key, new Page(target.type(), target.number(), target.label(), blob, now));
            dirty = true;
        }
    }

    // ---------------------------------------------------------------- island chests (SkyOcean's ChestTracker)

    private static final long CHEST_CLICK_MS = 5_000L;
    private static BlockPos clickedFirst;
    private static BlockPos clickedSecond;
    private static long clickedAt;
    private static Screen chestScreen;
    private static BlockPos chestFirst;
    private static BlockPos chestSecond;
    private static final List<AABB> highlightedChests = new ArrayList<>();

    private static boolean onPrivateIsland() {
        return Utils.getLocation() == Location.PRIVATE_ISLAND;
    }

    /** A double chest's first half holds slots 0-26 (vanilla's RIGHT half), as in SkyOcean. */
    private static void rememberClickedChest(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return;
        ChestType type = state.getValue(ChestBlock.TYPE);
        clickedFirst = pos.immutable();
        clickedSecond = null;
        if (type != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state)).immutable();
            if (type == ChestType.RIGHT) {
                clickedSecond = other;
            } else {
                clickedSecond = clickedFirst;
                clickedFirst = other;
            }
        }
        clickedAt = System.currentTimeMillis();
    }

    private static String chestKey(Minecraft mc, BlockPos first, BlockPos second) {
        String key = profilePrefix(mc) + "CHEST|" + first.getX() + "," + first.getY() + "," + first.getZ();
        return second == null ? key : key + ";" + second.getX() + "," + second.getY() + "," + second.getZ();
    }

    private static List<BlockPos> chestPositions(String key) {
        List<BlockPos> out = new ArrayList<>();
        for (String pos : key.substring(key.lastIndexOf('|') + 1).split(";")) {
            String[] xyz = pos.split(",");
            if (xyz.length == 3) out.add(new BlockPos(parseSigned(xyz[0]), parseSigned(xyz[1]), parseSigned(xyz[2])));
        }
        return out;
    }

    private static int parseSigned(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static AABB chestBox(String key) {
        List<BlockPos> positions = chestPositions(key);
        AABB box = new AABB(positions.getFirst());
        for (BlockPos pos : positions) box = box.minmax(new AABB(pos));
        return box;
    }

    private static void removeChestAt(BlockPos pos) {
        boolean removed = pages.entrySet().removeIf(e -> "CHEST".equals(e.getValue().type())
            && currentProfile(e.getKey()) && chestPositions(e.getKey()).contains(pos));
        if (removed) dirty = true;
    }

    /** Vanilla chest menus ("container.chest"/"container.chestDouble") and Minion Chests on your island. */
    private static boolean captureIslandChest(Minecraft mc, AbstractContainerScreen<?> container, long now) {
        if (!onPrivateIsland()) return false;
        boolean vanillaChest = container.getTitle().getContents() instanceof TranslatableContents contents
            && contents.getKey().startsWith("container.chest");
        boolean minionChest = !vanillaChest && "minion chest".equals(cleanTitle(container.getTitle().getString()));
        if (!vanillaChest && !minionChest) return false;
        if (container != chestScreen) {
            chestScreen = container;
            boolean recent = now - clickedAt <= CHEST_CLICK_MS;
            chestFirst = recent ? clickedFirst : null;
            chestSecond = recent ? clickedSecond : null;
            clickedFirst = null;
        }
        if (chestFirst == null) return true;
        if (minionChest) {
            removeChestAt(chestFirst);
            return true;
        }
        List<Slot> slots = container.getMenu().slots;
        int count = slots.size() - 36;
        List<ItemStack> contents = new ArrayList<>(count);
        boolean any = false;
        for (int i = 0; i < count; i++) {
            ItemStack stack = slots.get(i).getItem();
            any |= !stack.isEmpty();
            contents.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        String key = chestKey(mc, chestFirst, chestSecond);
        if (!any) {
            if (pages.remove(key) != null) dirty = true;
            return true;
        }
        String blob = encode(contents);
        if (blob == null) return true;
        Page old = pages.get(key);
        if (old == null || !old.blob().equals(blob)) {
            String label = "Chest at x: " + chestFirst.getX() + ", y: " + chestFirst.getY() + ", z: " + chestFirst.getZ();
            pages.put(key, new Page("CHEST", 0, label, blob, now));
            dirty = true;
        }
        return true;
    }

    private static void renderChestHighlights(PrimitiveCollector collector) {
        if (highlightedChests.isEmpty()) return;
        if (System.currentTimeMillis() > highlightUntil) {
            highlightedChests.clear();
            return;
        }
        if (!onPrivateIsland()) return;
        // SkyOcean draws the chests in rainbow.
        int rgb = java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000L) / 4000f, 0.8f, 1f);
        float[] colour = {(rgb >> 16 & 255) / 255f, (rgb >> 8 & 255) / 255f, (rgb & 255) / 255f};
        for (AABB box : highlightedChests) {
            collector.submitFilledBox(box, colour, 0.3f, true);
            collector.submitOutlinedBox(box, colour, 3f, true);
        }
    }

    // ---------------------------------------------------------------- museum (SkyblockAPI's MuseumAPI)

    private static final Pattern MUSEUM_TITLE = Pattern.compile("^Museum ➜ (.+)$");

    /** "Museum ➜ Combat" and the other category menus in the Hub: donated items that are kept in the museum. */
    private static boolean captureMuseum(AbstractContainerScreen<?> container, long now) {
        String title = ChatFormatting.stripFormatting(container.getTitle().getString());
        Matcher matcher = MUSEUM_TITLE.matcher(title == null ? "" : title.trim());
        if (!matcher.matches()) return false;
        if (Utils.getLocation() != Location.HUB) return true;
        String category = matcher.group(1).trim();
        Minecraft mc = Minecraft.getInstance();
        List<Slot> slots = container.getMenu().slots;
        for (int i = 0; i < slots.size() - 36; i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            name = name == null ? "" : name.trim();
            if (stack.is(Items.DYE.gray()) || stack.is(Items.DYE.lime())) {
                // Not donated, or donated but taken out: it isn't in the museum.
                removeMuseumItem(mc, name);
                continue;
            }
            String id = customData(stack).getStringOr("id", "");
            if (id.isEmpty()) continue; // GUI filler, arrows, armor set previews
            String key = profilePrefix(mc) + "MUSEUM|" + id;
            String blob = encode(List.of(stack.copy()));
            if (blob == null) continue;
            Page old = pages.get(key);
            String label = "Museum Category " + category;
            if (old == null || !old.blob().equals(blob) || !old.label().equals(label)) {
                pages.put(key, new Page("MUSEUM", 0, label, blob, now));
                dirty = true;
            }
        }
        return true;
    }

    private static void removeMuseumItem(Minecraft mc, String name) {
        String prefix = profilePrefix(mc) + "MUSEUM|";
        boolean removed = pages.entrySet().removeIf(e -> {
            if (!e.getKey().startsWith(prefix)) return false;
            List<ItemStack> items = decode(e.getValue().blob());
            if (items == null || items.isEmpty()) return false;
            String stored = ChatFormatting.stripFormatting(items.getFirst().getHoverName().getString());
            return stored != null && stored.trim().equalsIgnoreCase(name);
        });
        if (removed) dirty = true;
    }

    private static final List<String> RARITIES = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC",
        "DIVINE", "SPECIAL", "VERY SPECIAL", "ULTIMATE", "ADMIN");

    /** SkyBlock rarity from the lore's rarity line, COMMON = 0; -1 when there's none. */
    public static int rarity(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return -1;
        List<Component> lines = lore.lines();
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = ChatFormatting.stripFormatting(lines.get(i).getString());
            if (line == null) continue;
            line = line.replaceAll("[^A-Z ]", "").trim();
            if (line.startsWith("A ")) line = line.substring(2);
            for (int r = RARITIES.size() - 1; r >= 0; r--) {
                if (line.startsWith(RARITIES.get(r))) return r;
            }
        }
        return -1;
    }

    private static StorageTarget identify(String title) {
        String normalized = title == null ? "" : title.trim().replaceAll("\\s+", " ");
        Matcher ender = ENDER_CHEST.matcher(normalized);
        if (ender.find()) {
            int number = parseNumber(ender.group(1));
            return new StorageTarget("ENDER_CHEST", number, number > 0 ? "Ender Chest #" + number : "Ender Chest");
        }
        Matcher backpack = BACKPACK.matcher(normalized);
        if (backpack.find()) {
            int number = parseNumber(backpack.group(1));
            return new StorageTarget("BACKPACK", number, number > 0 ? "Backpack #" + number : "Backpack");
        }

        // Hypixel has changed the visible title formatting several times.
        // Never let a title variation prevent the cache from learning a page.
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("ender chest")) {
            int number = firstNumber(normalized);
            return new StorageTarget("ENDER_CHEST", number, number > 0 ? "Ender Chest #" + number : "Ender Chest");
        }
        if (lower.contains("backpack")) {
            int number = firstNumber(normalized);
            return new StorageTarget("BACKPACK", number, number > 0 ? "Backpack #" + number : "Backpack");
        }
        return null;
    }

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private static int firstNumber(String text) {
        Matcher m = DIGITS.matcher(text);
        return m.find() ? parseNumber(m.group()) : 0;
    }

    private record StorageTarget(String type, int number, String label) {}

    private record SearchText(String displayName, String name, String id, String lore) {}

    private static SearchText searchable(ItemStack stack) {
        String display = stack.getHoverName().getString();
        String name = display.toLowerCase(Locale.ROOT);

        String id = "";
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            try {
                id = customData.copyTag().getStringOr("id", "").toLowerCase(Locale.ROOT);
            } catch (Throwable ignored) {}
        }

        StringBuilder lore = new StringBuilder();
        ItemLore itemLore = stack.get(DataComponents.LORE);
        if (itemLore != null) {
            for (Component line : itemLore.lines()) {
                lore.append(line.getString()).append('\n');
            }
        }

        return new SearchText(display, name, id, lore.toString().toLowerCase(Locale.ROOT));
    }

    private static String inventoryLocation(int slot) {
        if (slot < 9) return "Inventory · Hotbar slot " + (slot + 1);
        if (slot < 36) {
            int row = (slot - 9) / 9 + 1;
            int col = (slot - 9) % 9 + 1;
            return "Inventory · r" + row + " c" + col;
        }
        if (slot == 40) return "Inventory · Offhand";
        if (slot >= 36 && slot <= 39) {
            return switch (slot) {
                case 36 -> "Inventory · Boots";
                case 37 -> "Inventory · Leggings";
                case 38 -> "Inventory · Chestplate";
                default -> "Inventory · Helmet";
            };
        }
        return "Inventory · slot " + (slot + 1);
    }

    private static boolean isSearchableStorageItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        String name = stack.getHoverName().getString()
                .replaceAll("§[0-9A-FK-ORa-fk-or]", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        String id = "";
        try {
            id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {}

        // SkyBlock storage GUIs use these as navigation/decorative controls.
        if (id.endsWith("stained_glass_pane") || id.equals("barrier")) return false;

        // Hypixel's page arrows are plain vanilla arrows without a SkyBlock item id.
        // Real SkyBlock arrows always carry one, so this never hides stored items.
        if (id.equals("arrow") && searchable(stack).id().isEmpty()) return false;

        // Names may be decorated with arrows or page counters ("» Next Page (2/9)").
        String words = name.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (words.contains("next page") || words.contains("previous page") || words.contains("prev page")
                || words.contains("first page") || words.contains("last page")) {
            return false;
        }

        return !(name.equals("go back")
                || name.equals("back")
                || name.equals("close")
                || name.equals("exit")
                || name.equals("first page") || name.equals("last page") || name.equals("previous page")
                || name.equals("next page")
                || name.equals("previous")
                || name.equals("next")
                || name.startsWith("first page") || name.startsWith("last page") || name.startsWith("previous page")
                || name.startsWith("next page")
                || name.startsWith("page ")
                || name.matches("page\\s*\\d+")
                || name.matches("[<>]\\s*page\\s*\\d*")
                || name.contains("click to go back")
                || name.contains("click to close")
                || name.contains("click to view")
                || name.contains("open first") || name.contains("open last") || name.contains("open previous")
                || name.contains("open next"));
    }

    private static String cacheKey(Minecraft mc, String type, int number) {
        return profilePrefix(mc) + type + "|" + number;
    }

    private record Loaded(Map<String, Page> pages, Map<String, Page> inventoryPages, String profile) {}

    private static Loaded parseSaved() {
        Map<String, Page> loadedPages = new LinkedHashMap<>();
        Map<String, Page> loadedInventory = new LinkedHashMap<>();
        String loadedProfile = null;
        if (configDir == null) return new Loaded(loadedPages, loadedInventory, null);

        Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) return new Loaded(loadedPages, loadedInventory, null);

        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String key : root.keySet()) {
                if (key.equals(PROFILE_KEY)) {
                    JsonObject saved = root.getAsJsonObject(key);
                    if (saved != null && saved.has("id")) loadedProfile = saved.get("id").getAsString();
                    continue;
                }
                JsonObject obj = root.getAsJsonObject(key);
                if (obj == null || !obj.has("blob")) continue;
                String type = obj.has("type") ? obj.get("type").getAsString() : "";
                int number = obj.has("number") ? obj.get("number").getAsInt() : 0;
                String label = obj.has("label") ? obj.get("label").getAsString() : type + " #" + number;
                long updated = obj.has("updated") ? obj.get("updated").getAsLong() : 0L;
                if (key.split("\\|", -1).length < 4) continue; // pre-profile cache entry (mixed profiles)
                // Older builds keyed by the exact address ("play.hypixel.net:25565|..."); all Hypixel addresses share one now.
                if (key.substring(0, key.indexOf('|')).contains("hypixel")) key = "hypixel" + key.substring(key.indexOf('|'));
                Page page = new Page(type, number, label, obj.get("blob").getAsString(), updated);
                if ("INVENTORY".equals(type)) loadedInventory.put(key, page);
                else loadedPages.put(key, page);
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load storage search cache: " + e.getMessage());
            loadedPages.clear();
        }
        return new Loaded(loadedPages, loadedInventory, loadedProfile);
    }

    /** Render thread: pages learnt before the file finished loading win over the saved ones. */
    private static void applyLoaded(Loaded loaded) {
        for (Map.Entry<String, Page> e : loaded.pages().entrySet()) pages.putIfAbsent(e.getKey(), e.getValue());
        for (Map.Entry<String, Page> e : loaded.inventoryPages().entrySet()) inventoryPages.putIfAbsent(e.getKey(), e.getValue());
        if (loaded.profile() != null && profile.isEmpty()) profile = loaded.profile();
        initialized = true;
    }

    private static void save() {
        if (configDir == null || !initialized) return; // not before the saved pages are in, or they would be overwritten
        try {
            Files.createDirectories(configDir);
            JsonObject root = new JsonObject();
            if (!profile.isEmpty()) {
                JsonObject saved = new JsonObject();
                saved.addProperty("id", profile);
                root.add(PROFILE_KEY, saved);
            }
            for (Map.Entry<String, Page> entry : pages.entrySet()) {
                Page page = entry.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("type", page.type());
                obj.addProperty("number", page.number());
                obj.addProperty("label", page.label());
                obj.addProperty("updated", page.updatedMs());
                obj.addProperty("blob", page.blob());
                root.add(entry.getKey(), obj);
            }
            for (Map.Entry<String, Page> entry : inventoryPages.entrySet()) {
                Page page = entry.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("type", page.type());
                obj.addProperty("number", page.number());
                obj.addProperty("label", page.label());
                obj.addProperty("updated", page.updatedMs());
                obj.addProperty("blob", page.blob());
                root.add(entry.getKey(), obj);
            }
            Files.writeString(configDir.resolve(FILE_NAME), GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
            lastSave = System.currentTimeMillis();
        } catch (IOException e) {
            System.err.println("[Sky2M] Failed to save storage search cache: " + e.getMessage());
        }
    }

    private static String encode(List<ItemStack> stacks) {
        RegistryAccess registryAccess = registryAccess();
        if (registryAccess == null) return null;

        try {
            HolderLookup.Provider provider = registryAccess;
            var ops = provider.createSerializationContext(NbtOps.INSTANCE);
            ListTag list = new ListTag();

            for (ItemStack stack : stacks) {
                CompoundTag tag = new CompoundTag();
                if (stack != null && !stack.isEmpty()) {
                    Tag encoded = ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
                    if (encoded instanceof CompoundTag compound) {
                        tag = compound;
                    }
                }
                list.add(tag);
            }

            CompoundTag root = new CompoundTag();
            root.put("items", list);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /** A saved Ender Chest page or backpack: its items are in menu slot order (navigation slots are empty). */
    public record StoragePage(String type, int number, String label, List<ItemStack> items) {}

    /** Every saved page on the current profile: Ender Chest pages first, then backpacks, by number. */
    public static List<StoragePage> storagePages() {
        List<StoragePage> out = new ArrayList<>();
        for (Map.Entry<String, Page> entry : new ArrayList<>(pages.entrySet())) {
            if (!currentProfile(entry.getKey())) continue;
            Page page = entry.getValue();
            if (!page.type().equals("ENDER_CHEST") && !page.type().equals("BACKPACK")) continue;
            List<ItemStack> items = decode(page.blob());
            if (items != null) out.add(new StoragePage(page.type(), page.number(), page.label(), copies(items)));
        }
        out.sort((x, y) -> x.type().equals(y.type()) ? Integer.compare(x.number(), y.number())
            : x.type().equals("ENDER_CHEST") ? -1 : 1);
        return out;
    }

    /** "ENDER_CHEST:3" / "BACKPACK:12" for a storage page menu title, or null. */
    public static String pageKey(String title) {
        StorageTarget target = identify(cleanTitle(title));
        return target == null ? null : target.type() + ":" + target.number();
    }

    /** Item counts by SkyBlock id across the cached Ender Chest and Backpack pages (used by the craft helper). */
    /** Every item on the current profile's saved Ender Chest and backpack pages. */
    public static List<ItemStack> storedStacks() {
        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Page> entry : new ArrayList<>(pages.entrySet())) {
            if (!currentProfile(entry.getKey()) || "MUSEUM".equals(entry.getValue().type())) continue;
            List<ItemStack> contents = decode(entry.getValue().blob());
            if (contents == null) continue;
            for (ItemStack stack : contents) if (stack != null && !stack.isEmpty()) stacks.add(stack.copy());
        }
        return stacks;
    }

    public static Map<String, Integer> storedItemCounts() {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (Map.Entry<String, Page> entry : new ArrayList<>(pages.entrySet())) {
            if (!currentProfile(entry.getKey()) || "MUSEUM".equals(entry.getValue().type())) continue;
            Map<String, Integer> pageCounts = pageCounts(entry.getValue().blob());
            if (pageCounts != null) pageCounts.forEach((id, n) -> counts.merge(id, n, Integer::sum));
        }
        return counts;
    }

    /**
     * Decoded pages and their item counts, by the page's saved data. Decoding means un-gzipping and parsing NBT, and the
     * craft helper and searches asked for every page again and again (the craft helper from the HUD, every few seconds),
     * which stuttered with a lot of storage. A page is only decoded again once its data changes.
     */
    private static final int DECODE_CACHE_SIZE = 512;
    private static final Map<String, List<ItemStack>> DECODED = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, List<ItemStack>> eldest) { return size() > DECODE_CACHE_SIZE; }
        });
    private static final Map<String, Map<String, Integer>> COUNTED = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, Integer>> eldest) { return size() > DECODE_CACHE_SIZE; }
        });

    private static Map<String, Integer> pageCounts(String blob) {
        if (blob == null || blob.isEmpty()) return null;
        Map<String, Integer> cached = COUNTED.get(blob);
        if (cached != null) return cached;
        List<ItemStack> contents = decode(blob);
        if (contents == null) return null;
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (ItemStack stack : contents) {
            if (stack == null || stack.isEmpty()) continue;
            String id = com.epic60869.sky2m.custom.util.Compat.neuName(stack);
            if (!id.isEmpty()) counts.merge(id, stack.getCount(), Integer::sum);
        }
        counts = java.util.Collections.unmodifiableMap(counts);
        COUNTED.put(blob, counts);
        return counts;
    }

    private static List<ItemStack> copies(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) out.add(stack == null ? ItemStack.EMPTY : stack.copy());
        return out;
    }

    /** The page's items, decoded once and shared: read them, don't change them (copy first). */
    private static List<ItemStack> decode(String blob) {
        if (blob == null || blob.isEmpty()) return null;
        List<ItemStack> cached = DECODED.get(blob);
        if (cached != null) return cached;
        List<ItemStack> decoded = decodeUncached(blob);
        if (decoded != null) {
            decoded = java.util.Collections.unmodifiableList(decoded);
            DECODED.put(blob, decoded);
        }
        return decoded;
    }

    private static List<ItemStack> decodeUncached(String blob) {
        if (blob == null || blob.isEmpty()) return null;
        RegistryAccess registryAccess = registryAccess();
        if (registryAccess == null) return null;

        try {
            HolderLookup.Provider provider = registryAccess;
            var ops = provider.createSerializationContext(NbtOps.INSTANCE);
            byte[] bytes = Base64.getDecoder().decode(blob);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("items");
            List<ItemStack> result = new ArrayList<>(list.size());

            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i);
                result.add(tag.isEmpty()
                        ? ItemStack.EMPTY
                        : ItemStack.CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private static RegistryAccess registryAccess() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) return mc.level.registryAccess();
        return mc.getConnection() != null ? mc.getConnection().registryAccess() : null;
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
