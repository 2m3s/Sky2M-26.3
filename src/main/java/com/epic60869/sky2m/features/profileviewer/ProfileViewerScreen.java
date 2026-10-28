package com.epic60869.sky2m.features.profileviewer;


import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.profileviewer.ProfileItems.PvItem;
import com.epic60869.sky2m.features.profileviewer.ProfileStats.Level;
import com.epic60869.sky2m.features.profileviewer.ProfileStats.Pet;
import com.epic60869.sky2m.mixin.gui.Sky2MInventoryScreenInvoker;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /s2 pv: another player's SkyBlock profile, laid out like NotEnoughUpdates' profile viewer (and SBPV's NEU theme): a
 * dark frame with page tabs along the top, the player on the left, and pages for the basics and skills, dungeons,
 * slayers, inventories and pets. Profiles come from {@link ProfileApi}. Written for Sky2M; no code or textures are
 * taken from either mod.
 */
public final class ProfileViewerScreen extends Screen {
    static final int W = 430, H = 224, TAB = 26, SLOT = 18;
    // A rounded dark panel with a blue accent.
    static final int BG = 0xF0181A1F, BORDER = 0xFF2C313A, LIGHT = 0xFF2C313A, INSET = 0xFF111317, INSET_EDGE = 0xFF2A2F38,
        TAB_OFF = 0xE0181A1F, LABEL = 0xFF9AA3AE, VALUE = 0xFFFFFFFF, GOLD = 0xFFFFAA00, BAR_BG = 0xFF0B0D10, BAR = 0xFF2B9BDA,
        BAR_MAX = 0xFFFFAA00, BAR_OVERFLOW = 0xFFC77DFF, SLOT_BG = 0xFF22262D, ACCENT = 0xFF2B9BDA;
    /** The space between skill bars on the main page. */
    static final int SKILL_STEP = 18;

    enum Page {
        MAIN("Main", Items.PLAYER_HEAD), DUNGEONS("Dungeons", Items.WITHER_SKELETON_SKULL), SLAYERS("Slayers", Items.GOLDEN_SWORD),
        INVENTORIES("Inventories", Items.CHEST), PETS("Pets", Items.BONE), COLLECTIONS("Collections", Items.ITEM_FRAME),
        MINING("Mining & Foraging", Items.DIAMOND_PICKAXE), BESTIARY("Bestiary", Items.ZOMBIE_HEAD), CRIMSON("Crimson Isle", Items.BLAZE_POWDER),
        MUSEUM("Museum", Items.GOLD_BLOCK), RIFT("Rift", Items.ENDER_EYE), FARMING("Farming & Garden", Items.WHEAT),
        NETWORTH("Networth", Items.GOLD_INGOT), FISHING("Fishing", Items.FISHING_ROD), MISC("Misc", Items.BOOK);

        final String label;
        final ItemStack icon;

        Page(String label, net.minecraft.world.item.Item icon) {
            this.label = label;
            this.icon = new ItemStack(icon);
        }
    }

    /** {label, icon} of the inventory sub-pages. */
    private static final String[][] INVENTORIES = {{"Inventory", "chest"}, {"Armour & Equipment", "iron_chestplate"},
        {"Ender Chest", "ender_chest"}, {"Backpacks", "brown_shulker_box"}, {"Wardrobe", "armor_stand"},
        {"Accessories", "emerald"}, {"Personal Vault", "iron_door"}, {"Fishing Bag", "fishing_rod"}, {"Potion Bag", "potion"},
        {"Quiver", "arrow"}, {"Sacks", "bundle"}};

    private final String requested;
    private ProfileApi.Player player;
    private String error;
    private int profileIndex;
    private Page page = Page.MAIN;
    private int inventory;
    private int invPage;
    private int petPage;
    private RemotePlayer model;
    private float modelYaw = 200, modelPitch = -5;
    private boolean draggingModel;
    List<Component> tooltip;
    /** This frame's page arrows {x, y, w, h}, or null when there are none. */
    private int[] prevArrow, nextArrow;
    /** This frame's clickable areas on the extra pages. */
    final List<Hotspot> hotspots = new ArrayList<>();
    /** The extra pages' left-hand view, page within it, and scroll (reset when the page changes). */
    int subView, subPage, pageScroll;
    private final ProfilePages pages = new ProfilePages(this);

    record Hotspot(int x, int y, int w, int h, Runnable action) {}

    public ProfileViewerScreen(String name) {
        super(Component.literal(name + "'s Profile"));
        this.requested = name;
        ProfileApi.loadTables();
        // The Item List's NEU data has icons for sack items Hypixel's item list only gives a model for.
        com.epic60869.sky2m.features.itemlist.ItemListData.ensureLoaded();
        // NEU's items, for pet heads and every sack item's icon (already on disk when the Item List has been used).
        com.epic60869.sky2m.features.itemlist.ItemListData.ensureLoaded();
        // The networth's prices and item list, while the profile loads (usually already there from the game's start).
        com.epic60869.sky2m.features.profileviewer.networth.SkyHelperNetworth.warm();
        ProfileApi.player(name).whenComplete((p, e) -> Minecraft.getInstance().execute(() -> {
            if (p == null) error = ProfileApi.reason(e);
            else {
                player = p;
                if (model == null || !model.getUUID().equals(p.uuid())) buildModel();
                else equipModel();
                // Their museum (for the networth and Museum pages) while the main page is looked at.
                museumPrefetch = ProfileApi.aux(ProfileApi.Aux.MUSEUM, p.profiles().getFirst().id());
            }
        }));
        // Their name and skin as soon as the UUID is known (often straight away: cached, or they're in the tab list),
        // while the profiles load.
        ProfileApi.lookup(name).thenAccept(n -> Minecraft.getInstance().execute(() -> {
            if (player == null && early == null) {
                early = n;
                buildModel();
            }
        }));
    }

    /** The player's name and UUID before their profiles have loaded, for the model on the loading screen. */
    private ProfileApi.Name early;

    /** The selected profile's museum, asked for as soon as the profiles load. */
    private java.util.concurrent.CompletableFuture<JsonObject> museumPrefetch;

    @Override
    public void tick() {
        super.tick();
        // The networth in the background once the museum is in, so its page is ready when opened. Only after a
        // successful museum load: asking for a failed one fetches it again, which shouldn't happen every tick.
        if (player != null && profileIndex == 0 && museumPrefetch != null && museumPrefetch.isDone()
            && !museumPrefetch.isCompletedExceptionally()) pages.warmNetworth();
    }

    private java.util.UUID modelUuid() {
        return player != null ? player.uuid() : early == null ? null : early.uuid();
    }

    private String modelName() {
        return player != null ? player.name() : early == null ? requested : early.name();
    }

    ProfileApi.Profile profile() {
        return player == null ? null : player.profiles().get(profileIndex);
    }

    JsonObject member() {
        ProfileApi.Profile p = profile();
        return p == null ? null : p.member();
    }

    net.minecraft.client.gui.Font font() {
        return font;
    }

    /** The player's UUID, for the rank lookup. */
    java.util.UUID uuid() {
        return player == null ? null : player.uuid();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------------------------------------ frame

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int left = left(), top = top();
        tooltip = null;
        prevArrow = nextArrow = null;
        hotspots.clear();

        drawTabs(g, left, top, mouseX, mouseY);
        frame(g, left, top);
        if (open(Page.MAIN) || player == null) {
            g.text(font, title, left + 8, top + 6, GOLD, true);
        }

        if (player == null) {
            String text = error == null ? "Loading " + modelName() + "'s profile..." : "Couldn't load " + requested + ": " + error;
            int textX = left + W / 2;
            if (error == null && model != null) {
                // Their model and name already, where the main page puts them.
                int mx = left + 8, my = top + 18, mw = 100, mh = 150;
                inset(g, mx, my, mw, mh);
                drawModel(g, mx, my, mw, mh);
                g.centeredText(font, modelName(), mx + mw / 2, my + mh + 6, VALUE);
                textX = left + 118 + (W - 118) / 2;
            }
            g.centeredText(font, text, textX, top + H / 2 - 4, error == null ? LABEL : 0xFFFF5555);
        } else {
            switch (page) {
                case MAIN -> main(g, left, top, mouseX, mouseY);
                case DUNGEONS -> dungeons(g, left, top, mouseX, mouseY);
                case SLAYERS -> slayers(g, left, top, mouseX, mouseY);
                case INVENTORIES -> inventories(g, left, top, mouseX, mouseY);
                case PETS -> pets(g, left, top, mouseX, mouseY);
                default -> pages.draw(g, page, left, top, mouseX, mouseY);
            }
        }

        if (tooltip != null) g.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
    }

    private boolean open(Page p) {
        return page == p;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2 + TAB / 2;
    }

    /** Where a page's tab is: {x, y, w, h}. */
    private int[] tabRect(Page p, int left, int top) {
        return new int[]{left + 4 + p.ordinal() * (TAB + 2), top - TAB - 2, TAB, TAB - 2};
    }

    /** The page tabs: rounded tiles above the panel, the open one underlined in the accent. */
    private void drawTabs(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
        for (Page p : Page.values()) {
            int[] r = tabRect(p, left, top);
            boolean open = p == page, hover = in(mouseX, mouseY, r[0], r[1], r[2], r[3]);
            round(g, r[0], r[1], r[0] + r[2], r[1] + r[3], open ? 0xF0223040 : hover ? 0xF0262A31 : TAB_OFF, 3);
            if (open) g.fill(r[0] + 3, r[1] + r[3] - 2, r[0] + r[2] - 3, r[1] + r[3], ACCENT);
            g.item(p == Page.MAIN && player != null ? head() : p.icon, r[0] + 5, r[1] + 3);
            if (hover) tooltip = List.of(Component.literal(p.label));
        }
    }

    private void frame(GuiGraphicsExtractor g, int left, int top) {
        round(g, left, top, left + W, top + H, BG, 3);
        g.outline(left, top, W, H, BORDER);
        g.fill(left + 3, top, left + W - 3, top + 2, ACCENT);
    }

    /** A rectangle with its corners cut. */
    static void round(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int colour, int r) {
        g.fill(x1 + r, y1, x2 - r, y2, colour);
        g.fill(x1, y1 + r, x1 + r, y2 - r, colour);
        g.fill(x2 - r, y1 + r, x2, y2 - r, colour);
        g.fill(x1 + 1, y1 + 1, x1 + r, y1 + r, colour);
        g.fill(x2 - r, y1 + 1, x2 - 1, y1 + r, colour);
        g.fill(x1 + 1, y2 - r, x1 + r, y2 - 1, colour);
        g.fill(x2 - r, y2 - r, x2 - 1, y2 - 1, colour);
    }

    /** The frame: black outline with a light inner edge, like NEU's. */
    static void bevel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, BG);
        g.outline(x, y, w, h, BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, LIGHT);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, LIGHT);
    }

    static void inset(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        round(g, x, y, x + w, y + h, INSET, 2);
        g.outline(x, y, w, h, INSET_EDGE);
    }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    void line(GuiGraphicsExtractor g, int x, int y, String label, String value, int colour) {
        g.text(font, label, x, y, LABEL, false);
        g.text(font, value, x + font.width(label) + 4, y, colour, false);
    }

    private int statIndex, statLeft, statTop;

    /** A main-page stat: the next card in a two-column grid. */
    private void stat(GuiGraphicsExtractor g, int x, int y, String label, String value, int colour) {
        int col = statIndex % 2, row = statIndex / 2;
        statIndex++;
        chip(g, statLeft + col * 72, statTop + row * 25, 69, label, value, colour);
    }

    /** A stat card: the label small and grey, the value under it. */
    private void chip(GuiGraphicsExtractor g, int x, int y, int w, String label, String value, int colour) {
        round(g, x, y, x + w, y + 22, INSET, 2);
        g.pose().pushMatrix();
        g.pose().translate(x + 5, y + 3);
        g.pose().scale(0.75f, 0.75f);
        g.text(font, label.endsWith(":") ? label.substring(0, label.length() - 1) : label, 0, 0, LABEL, false);
        g.pose().popMatrix();
        g.text(font, value, x + 5, y + 11, colour, false);
    }

    /** A level bar with the name and level above it; hover for the XP. */
    void bar(GuiGraphicsExtractor g, int x, int y, int w, String name, Level level, ItemStack icon, int mouseX, int mouseY) {
        int textX = x;
        if (icon != null) {
            g.pose().pushMatrix();
            g.pose().translate(x, y - 1);
            g.pose().scale(0.75f, 0.75f);
            g.item(icon, 0, 0);
            g.pose().popMatrix();
            textX += 14;
        }
        g.text(font, name, textX, y, LABEL, true);
        String lv = level.maxed() ? "MAX " + level.level() : String.valueOf(level.level());
        boolean gold = level.maxed() || level.overflowed();
        g.text(font, lv, x + w - font.width(lv), y, gold ? GOLD : VALUE, true);
        int by = y + 10, bh = 4;
        int fill = (int) (w * Math.min(1, level.maxed() ? 1 : level.progress()));
        int colour = level.overflowed() ? BAR_OVERFLOW : gold ? BAR_MAX : BAR;
        round(g, x, by, x + w, by + bh, BAR_BG, 1);
        if (fill > 2) round(g, x, by, x + fill, by + bh, colour, 1);
        if (in(mouseX, mouseY, x, y - 1, w, 15)) {
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(name + " " + level.level()).withStyle(ChatFormatting.GOLD));
            if (level.overflowed()) tip.add(Component.literal("Overflow level, past the level cap").withStyle(ChatFormatting.LIGHT_PURPLE));
            tip.add(Component.literal("Total XP: " + ProfileStats.commas(level.xp())).withStyle(ChatFormatting.GRAY));
            if (!level.maxed()) tip.add(Component.literal("Progress: " + ProfileStats.shortNumber(level.xpIntoLevel()) + " / "
                + ProfileStats.shortNumber(level.xpForNext()) + String.format(Locale.ROOT, " (%.1f%%)", level.progress() * 100)).withStyle(ChatFormatting.GRAY));
            else tip.add(Component.literal("Maxed").withStyle(ChatFormatting.GOLD));
            tooltip = tip;
        }
    }

    static ItemStack icon(String id) {
        return new ItemStack(BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(id)).orElse(Items.BARRIER));
    }

    // ------------------------------------------------------------------------------------------------ main page

    private void main(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
        JsonObject m = member();
        ProfileApi.Profile p = profile();

        // The player, which you can turn by dragging.
        int mx = left + 8, my = top + 18, mw = 100, mh = 150;
        inset(g, mx, my, mw, mh);
        drawModel(g, mx, my, mw, mh);
        g.centeredText(font, player.name(), mx + mw / 2, my + mh + 6, VALUE);

        // The profile picker: click to switch between profiles.
        int py = my + mh + 18;
        g.fill(mx, py, mx + mw, py + 14, in(mouseX, mouseY, mx, py, mw, 14) ? 0xFF333333 : INSET);
        g.outline(mx, py, mw, 14, INSET_EDGE);
        String label = (player.profiles().size() > 1 ? "◀ " : "") + p.label() + (player.profiles().size() > 1 ? " ▶" : "");
        g.centeredText(font, label, mx + mw / 2, py + 3, p.selected() ? 0xFF55FF55 : VALUE);
        if (in(mouseX, mouseY, mx, py, mw, 14)) tooltip = List.of(Component.literal("Profile " + (profileIndex + 1) + " of " + player.profiles().size()),
            Component.literal(p.selected() ? "Their selected profile" : "Click to switch profile").withStyle(ChatFormatting.GRAY));

        // Stats, as a grid of cards.
        int sx = left + 118, sy = top + 22, step = 12;
        statIndex = 0;
        statLeft = sx - 4;
        statTop = top + 14;
        double sbLevel = ProfileStats.skyblockLevel(m);
        stat(g, sx, sy, "SkyBlock Level:", String.format(Locale.ROOT, "%.0f", Math.floor(sbLevel)), 0xFF55FFFF);
        stat(g, sx, sy += step, "Purse:", ProfileStats.shortNumber(ProfileStats.purse(m)), GOLD);
        double bank = ProfileStats.bank(p.profile());
        stat(g, sx, sy += step, "Bank:", bank < 0 ? "API off" : ProfileStats.shortNumber(bank), bank < 0 ? 0xFFFF5555 : GOLD);
        double personal = ProfileStats.personalBank(m);
        if (personal > 0) stat(g, sx, sy += step, "Personal Bank:", ProfileStats.shortNumber(personal), GOLD);
        stat(g, sx, sy += step, "Fairy Souls:", String.valueOf(ProfileStats.fairySouls(m)), 0xFFFF55FF);
        stat(g, sx, sy += step, "Magical Power:", ProfileStats.commas(ProfileStats.magicalPower(m)), 0xFFAA00AA);
        stat(g, sx, sy += step, "Power:", ProfileStats.selectedPower(m), VALUE);
        stat(g, sx, sy += step, "Skill Average:", String.format(Locale.ROOT, "%.2f", ProfileStats.skillAverage(m)), GOLD);
        Level cata = ProfileStats.catacombs(m);
        stat(g, sx, sy += step, "Catacombs:", String.format(Locale.ROOT, "%.2f", cata.exact()), 0xFFFF5555);
        StringBuilder slayers = new StringBuilder();
        for (String[] s : ProfileStats.SLAYERS) {
            if (s[0].equals("vampire")) continue;
            if (!slayers.isEmpty()) slayers.append('/');
            slayers.append(ProfileStats.slayer(m, s[0]).level());
        }
        stat(g, sx, sy += step, "Slayers:", slayers.toString(), VALUE);
        List<Pet> pets = ProfileStats.pets(m);
        Pet active = pets.stream().filter(Pet::active).findFirst().orElse(null);
        stat(g, sx, sy += step, "Pet:", active == null ? "None" : "[" + active.shownLevel() + "] " + active.name(),
            active == null ? LABEL : net.minecraft.network.chat.TextColor.fromLegacyFormat(ProfileStats.rarityColour(active.tier())).getValue() | 0xFF000000);
        long joined = (long) Json.num(Json.obj(m, "profile"), "first_join");
        if (joined > 0) stat(g, sx, sy += step, "Joined:", DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(joined)), VALUE);
        String mode = switch (p.gameMode()) {
            case "ironman" -> "Ironman";
            case "island" -> "Stranded";
            case "bingo" -> "Bingo";
            default -> "";
        };
        if (!mode.isEmpty()) stat(g, sx, sy += step, "Mode:", mode, LABEL);

        // Skills down the right.
        int kx = left + 268, ky = top + 8, kw = W - 268 - 10;
        for (String[] skill : ProfileStats.SKILLS) {
            if (skill[0].equals("hunting") && !ProfileStats.hasSkill(m, "hunting")) continue;
            bar(g, kx, ky, kw, skill[1], ProfileStats.skill(m, skill[0]), icon(skill[2]), mouseX, mouseY);
            ky += SKILL_STEP;
        }
        if (!ProfileStats.hasSkill(m, "farming") && !ProfileStats.hasSkill(m, "mining")) {
            g.text(font, "Skills API off", kx, ky, 0xFFFF5555, true);
        }
    }

    private ItemStack head() {
        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        if (player != null) head.set(net.minecraft.core.component.DataComponents.PROFILE, ResolvableProfile.createUnresolved(player.uuid()));
        return head;
    }

    /** The player model (turned by dragging), or their head before it's built. */
    private void drawModel(GuiGraphicsExtractor g, int mx, int my, int mw, int mh) {
        if (model != null) {
            EntityRenderState state = Sky2MInventoryScreenInvoker.invokeExtractRenderState(model);
            Quaternionf rotation = new Quaternionf().rotationXYZ(-modelPitch * Mth.DEG_TO_RAD, -modelYaw * Mth.DEG_TO_RAD, (float) Math.PI);
            g.entity(state, 62f, new Vector3f(0, model.getBbHeight() / 2f + 0.0625f, 0), rotation, null, mx, my, mx + mw, my + mh);
        } else {
            g.item(head(), mx + mw / 2 - 8, my + mh / 2 - 8);
        }
    }

    private void buildModel() {
        Minecraft mc = Minecraft.getInstance();
        java.util.UUID uuid = modelUuid();
        if (mc.level == null || uuid == null) return;
        GameProfile gameProfile = new GameProfile(uuid, modelName());
        model = new RemotePlayer(mc.level, gameProfile) {
            @Override
            public boolean isInvisibleTo(Player other) {
                return true;
            }

            @Override
            public void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack newStack) {}

            @Override
            public boolean isModelPartShown(PlayerModelPart part) {
                return part != PlayerModelPart.CAPE;
            }

            @Override
            public PlayerSkin getSkin() {
                return Minecraft.getInstance().playerSkinRenderCache().lookup(ResolvableProfile.createUnresolved(uuid))
                    .getNow(java.util.Optional.empty()).map(info -> info.playerSkin()).orElseGet(super::getSkin);
            }

            @Override
            public int getId() {
                return Compat.PLACEHOLDER_ID;
            }
        };
        model.yHeadRot = model.yHeadRotO = 0;
        equipModel();
    }

    /** Puts the profile's armour on the model. */
    private void equipModel() {
        if (model == null) return;
        List<PvItem> armour = ProfileItems.decode(Json.path(member(), "inventory.inv_armor"));
        EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        for (int i = 0; i < slots.length; i++) {
            PvItem item = i < armour.size() ? armour.get(i) : null;
            model.setItemSlot(slots[i], item == null ? ItemStack.EMPTY : item.stack());
        }
    }

    // ------------------------------------------------------------------------------------------------ dungeons

    private void dungeons(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
        JsonObject m = member();
        JsonObject dungeons = Json.obj(m, "dungeons");
        g.text(font, "Dungeons", left + 8, top + 6, GOLD, true);
        int x = left + 10, y = top + 22, w = 150;
        bar(g, x, y, w, "Catacombs", ProfileStats.catacombs(m), new ItemStack(Items.WITHER_SKELETON_SKULL), mouseX, mouseY);
        y += 24;
        String selected = Json.str(dungeons, "selected_dungeon_class");
        double classTotal = 0;
        for (String[] c : ProfileStats.CLASSES) {
            Level level = ProfileStats.dungeonClass(m, c[0]);
            classTotal += Math.min(50, level.exact());
            bar(g, x, y, w, c[1] + (c[0].equals(selected) ? " ★" : ""), level, icon(c[2]), mouseX, mouseY);
            y += 17;
        }
        y += 4;
        line(g, x, y, "Class Average:", String.format(Locale.ROOT, "%.2f", classTotal / ProfileStats.CLASSES.length), VALUE);
        long secrets = (long) Json.num(dungeons, "secrets");
        int runs = 0;
        for (String type : new String[]{"catacombs", "master_catacombs"}) {
            JsonObject comps = Json.path(dungeons, "dungeon_types." + type + ".tier_completions");
            if (comps != null) for (var e : comps.entrySet()) if (!e.getKey().equals("total")) runs += e.getValue().getAsInt();
        }
        line(g, x, y += 12, "Secrets:", ProfileStats.commas(secrets) + (runs > 0 ? String.format(Locale.ROOT, " (%.1f/run)", (double) secrets / runs) : ""), VALUE);
        line(g, x, y += 12, "Runs:", ProfileStats.commas(runs), VALUE);
        line(g, x, y += 12, "Magical Power:", ProfileStats.commas(ProfileStats.magicalPower(m)), 0xFFAA00AA);

        // Floor table.
        int tx = left + 178, ty = top + 10, tw = W - 178 - 10;
        inset(g, tx, ty, tw, H - 20);
        int[] cols = {tx + 6, tx + 44, tx + 90, tx + 142, tx + 194};
        String[] heads = {"Floor", "Runs", "Best", "S", "S+"};
        for (int i = 0; i < heads.length; i++) g.text(font, heads[i], cols[i], ty + 5, GOLD, true);
        int ry = ty + 18;
        JsonObject normal = Json.path(dungeons, "dungeon_types.catacombs"), master = Json.path(dungeons, "dungeon_types.master_catacombs");
        for (int f = 0; f <= 7; f++) ry = floorRow(g, cols, ry, f == 0 ? "Entrance" : "F" + f, normal, String.valueOf(f), 0xFF55FF55);
        for (int f = 1; f <= 7; f++) ry = floorRow(g, cols, ry, "M" + f, master, String.valueOf(f), 0xFFFF5555);
    }

    private int floorRow(GuiGraphicsExtractor g, int[] cols, int y, String name, JsonObject type, String key, int colour) {
        int comps = (int) Json.num(Json.obj(type, "tier_completions"), key);
        g.text(font, name, cols[0], y, comps > 0 ? colour : 0xFF555555, true);
        g.text(font, comps > 0 ? ProfileStats.commas(comps) : "-", cols[1], y, VALUE, true);
        g.text(font, time(Json.num(Json.obj(type, "fastest_time"), key)), cols[2], y, LABEL, true);
        g.text(font, time(Json.num(Json.obj(type, "fastest_time_s"), key)), cols[3], y, 0xFFFFFF55, true);
        g.text(font, time(Json.num(Json.obj(type, "fastest_time_s_plus"), key)), cols[4], y, GOLD, true);
        return y + 12;
    }

    static String time(double ms) {
        if (ms <= 0) return "-";
        long s = (long) ms / 1000;
        return s / 60 + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    // ------------------------------------------------------------------------------------------------ slayers

    private void slayers(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
        JsonObject m = member();
        g.text(font, "Slayers", left + 8, top + 6, GOLD, true);
        int cw = (W - 16 - 8) / 3, ch = (H - 24 - 4) / 2;
        long totalXp = 0;
        for (int i = 0; i < ProfileStats.SLAYERS.length; i++) {
            String[] s = ProfileStats.SLAYERS[i];
            int cx = left + 8 + (i % 3) * (cw + 4), cy = top + 20 + (i / 3) * (ch + 4);
            inset(g, cx, cy, cw, ch);
            Level level = ProfileStats.slayer(m, s[0]);
            totalXp += (long) level.xp();
            bar(g, cx + 6, cy + 6, cw - 12, s[1], level, icon(s[2]), mouseX, mouseY);
            int[] kills = ProfileStats.slayerKills(m, s[0]);
            int ky = cy + 28;
            int tiers = s[0].equals("zombie") || s[0].equals("spider") || s[0].equals("vampire") ? 5 : 4;
            for (int t = 0; t < tiers; t++) {
                line(g, cx + 6, ky, "Tier " + roman(t + 1) + ":", ProfileStats.commas(kills[t]), kills[t] > 0 ? VALUE : 0xFF555555);
                ky += 11;
            }
        }
        String total = "Total XP: " + ProfileStats.commas(totalXp);
        g.text(font, total, left + W - 8 - font.width(total), top + 6, LABEL, true);
    }

    private static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "V";
        };
    }

    // ------------------------------------------------------------------------------------------------ inventories

    private List<List<PvItem>> inventoryPages() {
        JsonObject inv = Json.obj(member(), "inventory");
        List<List<PvItem>> pages = new ArrayList<>();
        if (inv == null) return pages;
        switch (inventory) {
            case 0 -> {
                List<PvItem> items = ProfileItems.decode(Json.obj(inv, "inv_contents"));
                // The hotbar (0-8) goes under the rest, like your own inventory.
                List<PvItem> laid = new ArrayList<>();
                for (int i = 9; i < 36; i++) laid.add(i < items.size() ? items.get(i) : null);
                for (int i = 0; i < 9; i++) laid.add(i < items.size() ? items.get(i) : null);
                pages.add(laid);
            }
            case 1 -> {
                List<PvItem> laid = new ArrayList<>();
                List<PvItem> armour = ProfileItems.decode(Json.obj(inv, "inv_armor"));
                List<PvItem> equipment = ProfileItems.decode(Json.obj(inv, "equipment_contents"));
                // Two rows of four (see columns()): armour, then equipment.
                for (int i = 3; i >= 0; i--) laid.add(i < armour.size() ? armour.get(i) : null);
                for (int i = 0; i < 4; i++) laid.add(i < equipment.size() ? equipment.get(i) : null);
                pages.add(laid);
            }
            case 2 -> split(pages, ProfileItems.decode(Json.obj(inv, "ender_chest_contents")), 45);
            case 3 -> pages.addAll(ProfileItems.numbered(Json.obj(inv, "backpack_contents")));
            case 4 -> pages.addAll(wardrobe());
            case 5 -> split(pages, ProfileItems.decode(Json.path(inv, "bag_contents.talisman_bag")), 45);
            case 6 -> split(pages, ProfileItems.decode(Json.obj(inv, "personal_vault_contents")), 54);
            case 7 -> split(pages, ProfileItems.decode(Json.path(inv, "bag_contents.fishing_bag")), 45);
            case 8 -> split(pages, ProfileItems.decode(Json.path(inv, "bag_contents.potion_bag")), 45);
            case 9 -> split(pages, ProfileItems.decode(Json.path(inv, "bag_contents.quiver")), 45);
            default -> { }
        }
        pages.removeIf(page -> page.stream().allMatch(java.util.Objects::isNull) && pages.size() > 1);
        return pages;
    }

    /**
     * The wardrobe, laid out like the in-game one: each set a column (helmet to boots), 9 sets a page. Hypixel's
     * loadout rework moved the sets to {@code loadout.armor} (one entry per set, HELMET to BOOTS); only profiles nobody
     * has opened since still have the old {@code wardrobe_contents} (from Killer560's Mod, MIT).
     */
    List<List<PvItem>> wardrobe() {
        List<List<PvItem>> out = new ArrayList<>();
        JsonObject armour = Json.path(member(), "loadout.armor");
        int maxSet = -1;
        java.util.Map<Integer, List<PvItem>> sets = new java.util.TreeMap<>();
        if (armour != null) for (String key : armour.keySet()) {
            if (key.equals("equipped_set")) continue;
            int set;
            try {
                set = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                continue;
            }
            JsonObject pieces = Json.obj(armour, key);
            List<PvItem> four = new ArrayList<>();
            for (String piece : new String[]{"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"}) {
                List<PvItem> one = ProfileItems.decode(Json.obj(pieces, piece));
                four.add(one.isEmpty() ? null : one.getFirst());
            }
            sets.put(set, four);
            maxSet = Math.max(maxSet, set);
        }
        if (sets.isEmpty()) {
            split(out, ProfileItems.decode(Json.obj(Json.obj(member(), "inventory"), "wardrobe_contents")), 36);
            return out;
        }
        for (int start = 0; start <= maxSet; start += 9) {
            List<PvItem> page = new ArrayList<>(java.util.Collections.nCopies(36, (PvItem) null));
            for (int col = 0; col < 9; col++) {
                List<PvItem> four = sets.get(start + col);
                if (four != null) for (int row = 0; row < 4; row++) page.set(row * 9 + col, four.get(row));
            }
            out.add(page);
        }
        return out;
    }

    static void split(List<List<PvItem>> pages, List<PvItem> items, int size) {
        for (int i = 0; i < items.size(); i += size) pages.add(items.subList(i, Math.min(items.size(), i + size)));
    }

    private void inventories(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
        // Sub-pages down the left.
        int lx = left + 6, ly = top + 6;
        for (int i = 0; i < INVENTORIES.length; i++) {
            int y = ly + i * 19;
            boolean open = i == inventory, over = in(mouseX, mouseY, lx, y, 118, 18);
            g.fill(lx, y, lx + 118, y + 18, open ? 0xFF3A3A3A : over ? 0xFF2E2E2E : INSET);
            g.outline(lx, y, 118, 18, open ? 0xFF6A6A6A : INSET_EDGE);
            g.item(icon(INVENTORIES[i][1]), lx + 1, y + 1);
            g.text(font, INVENTORIES[i][0], lx + 20, y + 5, open ? GOLD : VALUE, true);
        }

        int gx = left + 134, gy = top + 10;
        if (Json.obj(member(), "inventory") == null) {
            g.text(font, "Their Inventory API is off.", gx, gy + 4, 0xFFFF5555, true);
            return;
        }
        if (inventory == 10) {
            sacks(g, gx, gy, mouseX, mouseY);
            return;
        }
        List<List<PvItem>> pages = inventoryPages();
        if (pages.isEmpty()) {
            g.text(font, "Nothing here.", gx, gy + 4, LABEL, true);
            return;
        }
        invPage = Math.clamp(invPage, 0, pages.size() - 1);
        List<PvItem> items = pages.get(invPage);
        String name = INVENTORIES[inventory][0] + (pages.size() > 1 ? " (" + (invPage + 1) + "/" + pages.size() + ")" : "");
        g.text(font, name, gx, gy, GOLD, true);
        int cols = columns();
        int rows = Math.max(1, (items.size() + cols - 1) / cols);
        int sy = gy + 12;
        for (int i = 0; i < rows * cols; i++) {
            int x = gx + (i % cols) * SLOT, y = sy + (i / cols) * SLOT + (inventory == 0 && i >= 27 ? 4 : 0);
            slot(g, i < items.size() ? items.get(i) : null, x, y, mouseX, mouseY);
        }
        if (pages.size() > 1) pageArrows(g, gx, sy + rows * SLOT + 8, cols * SLOT, mouseX, mouseY);
    }

    /** Slots per row: Armour & Equipment has only its four armour and four equipment slots, everything else nine. */
    private int columns() {
        return inventory == 1 ? 4 : 9;
    }

    private void sacks(GuiGraphicsExtractor g, int gx, int gy, int mouseX, int mouseY) {
        JsonObject counts = Json.path(member(), "inventory.sacks_counts");
        List<String[]> list = new ArrayList<>();
        if (counts != null) for (var e : counts.entrySet()) {
            long n = e.getValue().getAsLong();
            if (n > 0) list.add(new String[]{e.getKey(), String.valueOf(n)});
        }
        list.sort((a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
        int per = 54;
        int pages = Math.max(1, (list.size() + per - 1) / per);
        invPage = Math.clamp(invPage, 0, pages - 1);
        g.text(font, "Sacks" + (pages > 1 ? " (" + (invPage + 1) + "/" + pages + ")" : ""), gx, gy, GOLD, true);
        int sy = gy + 12;
        for (int i = 0; i < per; i++) {
            int x = gx + (i % 9) * SLOT, y = sy + (i / 9) * SLOT;
            g.fill(x, y, x + SLOT - 1, y + SLOT - 1, SLOT_BG);
            int index = invPage * per + i;
            if (index >= list.size()) continue;
            String[] sack = list.get(index);
            ItemStack stack = ProfileItems.icon(sack[0]);
            g.item(stack, x, y);
            String count = ProfileStats.shortNumber(Long.parseLong(sack[1]));
            g.pose().pushMatrix();
            g.pose().translate(x + 17 - font.width(count) * 0.6f, y + 12);
            g.pose().scale(0.6f, 0.6f);
            g.text(font, count, 0, 0, VALUE, true);
            g.pose().popMatrix();
            if (in(mouseX, mouseY, x, y, SLOT, SLOT)) tooltip = List.of(stack.getHoverName(),
                Component.literal(ProfileStats.commas(Long.parseLong(sack[1])) + " in sacks").withStyle(ChatFormatting.GRAY));
        }
        if (list.isEmpty()) g.text(font, "No sack items.", gx, sy + 4, LABEL, true);
        if (pages > 1) pageArrows(g, gx, sy + 6 * SLOT + 8, 9 * SLOT, mouseX, mouseY);
    }

    void slot(GuiGraphicsExtractor g, PvItem item, int x, int y, int mouseX, int mouseY) {
        g.fill(x, y, x + SLOT - 1, y + SLOT - 1, SLOT_BG);
        boolean over = in(mouseX, mouseY, x, y, SLOT - 1, SLOT - 1);
        if (item != null) {
            g.item(item.stack(), x, y);
            g.itemDecorations(font, item.stack(), x, y);
            if (over) tooltip = item.tooltip();
        }
        if (over) g.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0x40FFFFFF);
    }

    private void pageArrows(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
        int pw = font.width("◀ Prev"), nw = font.width("Next ▶");
        prevArrow = new int[]{x - 2, y - 2, pw + 4, 12};
        nextArrow = new int[]{x + w - nw - 2, y - 2, nw + 4, 12};
        g.text(font, "◀ Prev", x, y, in(mouseX, mouseY, prevArrow[0], prevArrow[1], prevArrow[2], prevArrow[3]) ? GOLD : VALUE, true);
        g.text(font, "Next ▶", x + w - nw, y, in(mouseX, mouseY, nextArrow[0], nextArrow[1], nextArrow[2], nextArrow[3]) ? GOLD : VALUE, true);
    }

    // ------------------------------------------------------------------------------------------------ pets

    private void pets(GuiGraphicsExtractor g, int left, int top, int mouseX, int mouseY) {
        List<Pet> pets = ProfileStats.pets(member());
        g.text(font, "Pets (" + pets.size() + ")", left + 8, top + 6, GOLD, true);
        int cols = 21, rows = 9, per = cols * rows;
        int pages = Math.max(1, (pets.size() + per - 1) / per);
        petPage = Math.clamp(petPage, 0, pages - 1);
        int gx = left + 10, gy = top + 20;
        for (int i = 0; i < per; i++) {
            int x = gx + (i % cols) * (SLOT + 1), y = gy + (i / cols) * (SLOT + 1);
            int index = petPage * per + i;
            g.fill(x, y, x + SLOT, y + SLOT, SLOT_BG);
            if (index >= pets.size()) continue;
            Pet pet = pets.get(index);
            if (pet.active()) g.outline(x, y, SLOT, SLOT, 0xFF55FF55);
            ItemStack stack = ProfileItems.icon(pet.itemId());
            if (stack.is(Items.BARRIER)) stack = new ItemStack(Items.BONE);
            g.item(stack, x + 1, y + 1);
            String lv = String.valueOf(pet.shownLevel());
            g.pose().pushMatrix();
            g.pose().translate(x + 1, y + 1);
            g.pose().scale(0.6f, 0.6f);
            g.text(font, lv, 0, 0, pet.overflowLevel() > 0 ? BAR_OVERFLOW : pet.level() >= pet.maxLevel() ? GOLD : VALUE, true);
            g.pose().popMatrix();
            if (in(mouseX, mouseY, x, y, SLOT, SLOT)) tooltip = petTooltip(pet);
        }
        if (pets.isEmpty()) g.text(font, "No pets.", gx, gy + 4, LABEL, true);
        if (pages > 1) pageArrows(g, gx, gy + rows * (SLOT + 1) + 6, cols * (SLOT + 1), mouseX, mouseY);
    }

    private static List<Component> petTooltip(Pet pet) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal("[Lvl " + pet.shownLevel() + "] " + pet.name()).withStyle(ProfileStats.rarityColour(pet.tier())));
        tip.add(Component.literal(pet.tier().charAt(0) + pet.tier().substring(1).toLowerCase(Locale.ROOT).replace('_', ' '))
            .withStyle(ProfileStats.rarityColour(pet.tier()), ChatFormatting.BOLD));
        tip.add(Component.literal("XP: " + ProfileStats.commas(pet.exp())).withStyle(ChatFormatting.GRAY));
        if (pet.level() < pet.maxLevel()) tip.add(Component.literal(String.format(Locale.ROOT, "Progress to %d: %.1f%%", pet.level() + 1, pet.progress() * 100)).withStyle(ChatFormatting.GRAY));
        else if (pet.overflowLevel() > 0) {
            tip.add(Component.literal(String.format(Locale.ROOT, "Progress to %d: %.1f%%", pet.overflowLevel() + 1, pet.progress() * 100)).withStyle(ChatFormatting.GRAY));
            tip.add(Component.literal("Overflow level, past the max of " + pet.maxLevel()).withStyle(ChatFormatting.LIGHT_PURPLE));
        } else tip.add(Component.literal("MAX LEVEL").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        if (!pet.heldItem().isEmpty()) {
            String held = com.epic60869.sky2m.custom.RepoItems.displayName(pet.heldItem());
            tip.add(Component.literal("Held: " + (held == null ? pet.heldItem() : held)).withStyle(ChatFormatting.GOLD));
        }
        if (pet.candy() > 0) tip.add(Component.literal("Pet Candy: " + pet.candy()).withStyle(ChatFormatting.GREEN));
        if (!pet.skin().isEmpty()) tip.add(Component.literal("Skin: " + pet.skin()).withStyle(ChatFormatting.LIGHT_PURPLE));
        if (pet.active()) tip.add(Component.literal("Active pet").withStyle(ChatFormatting.GREEN));
        return tip;
    }

    // ------------------------------------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x(), my = event.y();
        int left = left(), top = top();
        for (Page p : Page.values()) {
            int[] r = tabRect(p, left, top);
            if (in(mx, my, r[0], r[1], r[2], r[3])) {
                page = p;
                invPage = 0;
                subView = subPage = pageScroll = 0;
                return true;
            }
        }
        if (player == null) return super.mouseClicked(event, doubleClick);
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            for (Hotspot h : List.copyOf(hotspots)) {
                if (in(mx, my, h.x(), h.y(), h.w(), h.h())) {
                    h.action().run();
                    return true;
                }
            }
        }
        if (prevArrow != null && in(mx, my, prevArrow[0], prevArrow[1], prevArrow[2], prevArrow[3])) {
            turnPage(-1);
            return true;
        }
        if (nextArrow != null && in(mx, my, nextArrow[0], nextArrow[1], nextArrow[2], nextArrow[3])) {
            turnPage(1);
            return true;
        }
        switch (page) {
            case MAIN -> {
                int bx = left + 8, by = top + 18 + 150 + 18;
                if (in(mx, my, bx, by, 100, 14) && player.profiles().size() > 1) {
                    int n = player.profiles().size();
                    profileIndex = (profileIndex + (mx < bx + 50 && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT ? n - 1 : 1)) % n;
                    equipModel();
                    pages.reset();
                    return true;
                }
                if (in(mx, my, bx, top + 18, 100, 150)) {
                    draggingModel = true;
                    return true;
                }
            }
            case INVENTORIES -> {
                for (int i = 0; i < INVENTORIES.length; i++) {
                    if (in(mx, my, left + 6, top + 6 + i * 19, 118, 18)) {
                        inventory = i;
                        invPage = 0;
                        return true;
                    }
                }
            }
            default -> { }
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** Pages clamp themselves when drawn, so turning past the end does nothing. */
    private void turnPage(int by) {
        if (page == Page.PETS) petPage = Math.max(0, petPage + by);
        else invPage = Math.max(0, invPage + by);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingModel) {
            modelYaw += (float) dragX * 2.5f;
            modelPitch = Mth.clamp(modelPitch - (float) dragY * 2.5f, -50f, 50f);
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        draggingModel = false;
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page == Page.INVENTORIES || page == Page.PETS) {
            turnPage(-(int) Math.signum(scrollY));
            return true;
        }
        if (page.ordinal() > Page.PETS.ordinal()) {
            // Pages clamp their scroll when drawn.
            pageScroll = Math.max(0, pageScroll - (int) Math.signum(scrollY));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }
}
