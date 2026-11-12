// The greenhouse location, crop detection and crop table are ported from Skyblocker's GreenhousePaste and
// GreenhouseCrops (https://github.com/SkyblockerMod/Skyblocker, LGPL-3.0).
package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /s2 greenhouse paste, shown on your greenhouse plot like Skyblocker's: each spot of the 10x10 greenhouse that still
 * needs a crop gets a box in that crop's colour with its name above, a wrong crop gets a red box, and a mutation spot
 * (to be left empty) a green outline. Spots already planted right show nothing. The greenhouse is the 10x10 in the
 * middle of the plot with the Carpenter, found once you stand on that plot; what's planted is read twice a second from
 * the crop armour stands' heads and, for vanilla crops, the blocks.
 */
public final class GreenhouseWorld {
    private static final int SIZE = 10;
    private static final int Y = 73;
    private static final float[] RED = {1f, 0.2f, 0.2f};
    private static final float[] GREEN = {0.2f, 1f, 0.3f};

    /** A crop SkyMutations can place: the text in its head's name, or its block, or its head texture id. */
    record Crop(String name, String standName, Block block, String texture) {
        boolean isHead() {
            return block == null;
        }
    }

    /** By SkyMutations name. Skyblocker's GreenhouseCrops. */
    static final Map<String, Crop> CROPS = new LinkedHashMap<>();
    /** Parts of big crops that aren't the crop's own head. */
    private static final Set<String> IGNORE_NAMES = Set.of("PlantboyRoots", "godseedPillar");

    static {
        String[][] heads = {
            {"Ashwreath", "ashwreath"}, {"Choconut", "Choconut"}, {"Dustgrain", "Dustgrain"}, {"Gloomgourd", "Gloomgourd"},
            {"Lonelily", "Lonelilly"}, {"Scourroot", "Scourroot"}, {"Shadevine", "shadevine"}, {"Veilshroom", "Veilshroom"},
            {"Witherbloom", "Witherbloom"}, {"Chocoberry", "Chocoberry"}, {"Cindershade", "Cindershade"}, {"Coalroot", "Coalroot"},
            {"Creambloom", "Creambloom"}, {"Duskbloom", "duskbloom"}, {"Thornshade", "thornshade"}, {"Blastberry", "blastberry"},
            {"Cheesebite", "cheesebite"}, {"Chloronite", "chloronite"}, {"Do Not Eat Shroom", "donoteatshroom"},
            {"Fleshtrap", "fleshtrap"}, {"Magic Jellybean", "magicjellybean"}, {"Noctilume", "noctilume"},
            {"Snoozling", "snoozlingFlower"}, {"Soggybud", "soggybud"}, {"Chorus Fruit", "chorusFruit"},
            {"PlantBoy Advance", "Plantboy"}, {"Puffercloud", "puffercloud"}, {"Shellfruit", "shellfruit"},
            {"Startlevine", "startlevine"}, {"Stoplight Petal", "stoplightpetal"}, {"Thunderling", "thunderling"},
            {"Turtlellini", "turtlellini"}, {"Zombud", "zombud"}, {"All In Aloe", "allinaloe"}, {"Devourer", "devourer"},
            {"Glasscorn", "glasscorn"}, {"Godseed", "godseed"}, {"Jerryflower", "jerryseed"}, {"Phantomleaf", "phantomleaf"},
            {"Timestalk", "timestalk"}, {"Cocoa Beans", "coco"}, {"Wild Rose", "wildrose"}, {"Moonflower", "moonflower"},
            {"Sunflower", "sunflower"}, {"Cactus", "cactus"}, {"Melon Seeds", "melon"}, {"Pumpkin Seeds", "pumpkin"},
        };
        for (String[] h : heads) add(new Crop(h[0], h[1], null, null));
        // Their heads' names don't say what they are, so these go by texture.
        add(new Crop("Brown Mushroom", "brownmushroom", null, "7019992b5d440f85d2b05148aa9b85f450985d5f16ae960d1cdb32e06e3c896f"));
        add(new Crop("Red Mushroom", "redmushroom", null, "162f0e8d32f3db02ffdf1a16efb21d614a3f0417d49138fdc18bfe52ead705b9"));
        add(new Crop("Helianthus", "helianthus", null, "a98ac9224491a9dd480531bd971591724ce29921ec8f141461276f1bf3a59ed3"));
        add(new Crop("Fermento", "fermento", null, "cb41daeb57d2ae62c66e58eb6debb2a7d446e34541a771350728c9db15beafba"));
        add(new Crop("Squash", "squash", null, "36ae076649ef22f60e8511831c68fd2b6ea63c32164dab33a8aebc18ff2a54c8"));
        add(new Crop("Cropie", "cropie", null, "dd01cba23ede2cd2895107f0c0258e971d2485538fe9649ef2853bd26e6232dc"));
        add(new Crop("Wheat Seeds", "wheat", Blocks.WHEAT, null));
        add(new Crop("Carrot", "carrot", Blocks.CARROTS, null));
        add(new Crop("Potato", "potato", Blocks.POTATOES, null));
        add(new Crop("Nether Wart", "netherwart", Blocks.NETHER_WART, null));
        add(new Crop("Sugar Cane", "sugarcane", Blocks.SUGAR_CANE, null));
        add(new Crop("Dead Plants", "deadplant", Blocks.DEAD_BUSH, null));
        add(new Crop("Fire", "fire", Blocks.FIRE, null));
    }

    private static void add(Crop crop) {
        CROPS.put(crop.name(), crop);
    }

    /** What the pasted layout wants, in world order [x][z]: null = don't care, "" = mutation spot, else a crop name. */
    private static String[][] target;
    /** What's planted [x][z], "" = nothing. */
    private static String[][] current = new String[SIZE][SIZE];
    /** The greenhouse's low corner (x, z) at y 73, or null until you've stood on its plot. */
    private static BlockPos corner;
    private static long nextLocate;
    private static long nextScan;

    private GreenhouseWorld() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        Sky2MWorldRender.register(GreenhouseWorld::render);
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.farming.garden.greenhousePreview && c.farming.garden.greenhouseInWorld;
    }

    /** Whether /s2 greenhouse paste shows in the world (else it opens the grid screen). */
    static boolean inWorld() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.farming.garden.greenhouseInWorld;
    }

    /** Shows this layout (GreenhousePreview's [x][z] grid) on the greenhouse. True when it's on screen now. */
    static boolean show(String[][] layout) {
        target = toWorld(layout);
        current = emptyGrid();
        nextScan = 0;
        if (corner == null) corner = findGreenhouse();
        return corner != null;
    }

    static void hide() {
        target = null;
    }

    static boolean showing() {
        return target != null;
    }

    /**
     * The layout in world order: SkyMutations' x runs the other way to the world's, as in Skyblocker
     * ({@code targetGreenhouse[9 - x][z]}). Public for the game test.
     */
    public static String[][] toWorld(String[][] layout) {
        String[][] out = new String[SIZE][SIZE];
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                String name = layout[x][z];
                out[SIZE - 1 - x][z] = name == null || name.isEmpty() ? null : name.equals(GreenhousePreview.MUTATION) ? "" : name;
            }
        }
        return out;
    }

    private static String[][] emptyGrid() {
        String[][] grid = new String[SIZE][SIZE];
        for (String[] column : grid) java.util.Arrays.fill(column, "");
        return grid;
    }

    // ------------------------------------------------------------------------------------------------ finding it

    private static void tick() {
        if (target == null || !enabled() || !Sky2MLocation.inGarden()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
        if (corner == null && now >= nextLocate) {
            nextLocate = now + 2000;
            corner = findGreenhouse();
        }
        if (corner != null && now >= nextScan) {
            nextScan = now + 500;
            scan();
        }
    }

    /** The low corner of the plot you're on, in the 5x5 garden of 96-block plots (the centre plot is around 0, 0). */
    private static BlockPos plotCorner(BlockPos at) {
        return new BlockPos((int) ((at.getX() + 240) / 96.0) * 96 - 240, 0, (int) ((at.getZ() + 240) / 96.0) * 96 - 240);
    }

    /** The greenhouse's corner, if the plot you're on is the one with the Carpenter. */
    private static BlockPos findGreenhouse() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !Sky2MLocation.inGarden()) return null;
        BlockPos plot = plotCorner(mc.player.blockPosition());
        AABB box = new AABB(plot.getX(), 60, plot.getZ(), plot.getX() + 96, 90, plot.getZ() + 96);
        for (Entity entity : mc.level.getEntities((Entity) null, box)) {
            Component name = entity.getCustomName();
            if (name != null && name.getString().contains("Carpenter")) return new BlockPos(plot.getX() + 43, Y, plot.getZ() + 43);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ what's planted

    private static void scan() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        String[][] grid = emptyGrid();
        List<ArmorStand> stands = new ArrayList<>();
        AABB all = new AABB(corner.getX() - 1, Y, corner.getZ() - 1, corner.getX() + SIZE + 1, Y + 6, corner.getZ() + SIZE + 1);
        for (Entity e : mc.level.getEntities((Entity) null, all)) if (e instanceof ArmorStand stand) stands.add(stand);
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                AABB column = new AABB(corner.getX() + x, Y, corner.getZ() + z, corner.getX() + x + 1, Y + 6, corner.getZ() + z + 1);
                grid[x][z] = cropAt(mc, column, corner.offset(x, 1, z), stands);
            }
        }
        bigCrops(grid, stands);
        current = grid;
    }

    private static String cropAt(Minecraft mc, AABB column, BlockPos above, List<ArmorStand> stands) {
        for (ArmorStand stand : stands) {
            if (!stand.getBoundingBox().intersects(column)) continue;
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty()) continue;
            String crop = cropForHead(head.getHoverName().getString(), textureId(Compat.getHeadTexture(head)));
            if (crop != null) return crop;
        }
        Block block = mc.level.getBlockState(above).getBlock();
        for (Crop crop : CROPS.values()) if (!crop.isHead() && crop.block() == block) return crop.name();
        return "";
    }

    /** The crop a head is: by the text in its name, else by its texture id. "" for a big crop's other parts. Public for the game test. */
    public static String cropForHead(String headName, String texture) {
        if (IGNORE_NAMES.contains(headName)) return "";
        // Case matters, as in Skyblocker ("coco" mustn't match other names).
        for (Crop crop : CROPS.values()) if (headName.contains(crop.standName())) return crop.name();
        if (texture == null || texture.isEmpty()) return null;
        for (Crop crop : CROPS.values()) if (texture.equals(crop.texture())) return crop.name();
        return null;
    }

    /** The texture id in a head's base64 "textures" value (the end of its texture URL), or "". Public for the game test. */
    public static String textureId(String base64) {
        if (base64 == null || base64.isEmpty()) return "";
        try {
            String json = new String(Base64.getDecoder().decode(base64.trim()), StandardCharsets.UTF_8);
            int at = json.indexOf("/texture/");
            if (at < 0) return "";
            int end = at + "/texture/".length();
            while (end < json.length() && Character.isLetterOrDigit(json.charAt(end))) end++;
            return json.substring(at + "/texture/".length(), end);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** Crops bigger than one spot: PlantBoy Advance 2x2 (its head is in the middle), Snoozling and Godseed 3x3. */
    private static void bigCrops(String[][] grid, List<ArmorStand> stands) {
        for (ArmorStand stand : stands) {
            ItemStack head = stand.getItemBySlot(EquipmentSlot.HEAD);
            String name = head.isEmpty() ? "" : head.getHoverName().getString();
            if (!name.contains("Plantboy") || name.contains("Advance")) continue;
            int x = (int) Math.floor(stand.getX() - 0.2) - corner.getX();
            int z = (int) Math.floor(stand.getZ() - 0.2) - corner.getZ();
            for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) mark(grid, x + dx, z + dz, "PlantBoy Advance");
        }
        String[][] found = new String[SIZE][];
        for (int x = 0; x < SIZE; x++) found[x] = grid[x].clone();
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                // Skyblocker: Snoozling's head is at the middle of its front row, Godseed's at the middle.
                if ("Snoozling".equals(found[x][z])) {
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -2; dz <= 0; dz++) mark(grid, x + dx, z + dz, "Snoozling");
                } else if ("Godseed".equals(found[x][z])) {
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) mark(grid, x + dx, z + dz, "Godseed");
                }
            }
        }
    }

    private static void mark(String[][] grid, int x, int z, String crop) {
        if (x >= 0 && x < SIZE && z >= 0 && z < SIZE) grid[x][z] = crop;
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /** What a spot needs: nothing, a crop to plant, the wrong crop taken out, or a mutation spot kept clear. Public for the game test. */
    public enum Need { NOTHING, PLANT, REMOVE, KEEP_CLEAR }

    public static Need need(String wanted, String planted) {
        if (wanted == null) return Need.NOTHING;
        boolean empty = planted == null || planted.isEmpty();
        if (wanted.isEmpty()) return empty ? Need.KEEP_CLEAR : Need.REMOVE;
        if (wanted.equals(planted)) return Need.NOTHING;
        // Either mushroom will do, as in Skyblocker.
        if (wanted.endsWith(" Mushroom") && planted != null && planted.endsWith(" Mushroom")) return Need.NOTHING;
        return empty ? Need.PLANT : Need.REMOVE;
    }

    private static void render(com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector collector) {
        if (target == null || corner == null || !enabled() || !Sky2MLocation.inGarden()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // Only on the greenhouse's plot.
        double px = mc.player.getX(), pz = mc.player.getZ();
        if (px < corner.getX() - 43 || px > corner.getX() + 53 || pz < corner.getZ() - 43 || pz > corner.getZ() + 53) return;
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                String wanted = target[x][z];
                BlockPos pos = corner.offset(x, 1, z);
                switch (need(wanted, current[x][z])) {
                    case NOTHING -> {}
                    case KEEP_CLEAR -> collector.submitOutlinedBox(new AABB(pos.getX(), pos.getY() - 0.05, pos.getZ(),
                        pos.getX() + 1, pos.getY() + 0.1, pos.getZ() + 1), GREEN, 0.4f, 3f, false);
                    case REMOVE -> collector.submitOutlinedBox(new AABB(pos), RED, 0.6f, 4f, true);
                    case PLANT -> {
                        collector.submitFilledBox(new AABB(pos).deflate(0.15), colour(wanted), 0.35f, false);
                        collector.submitText(Component.literal(wanted).withStyle(ChatFormatting.WHITE),
                            new Vec3(pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5), 0.6f, false);
                    }
                }
            }
        }
    }

    /** A steady colour for each crop, as in the /s2 greenhouse grid. */
    static float[] colour(String name) {
        float hue = (name.hashCode() & 0x7FFFFFFF) % 360 / 360f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.55f, 0.85f);
        return new float[]{(rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f};
    }

    /** For /s2 greenhouse paste's message. */
    static void say(String text, ChatFormatting colour) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Compat.PREFIX.get().append(Component.literal(text).withStyle(colour)));
    }
}
