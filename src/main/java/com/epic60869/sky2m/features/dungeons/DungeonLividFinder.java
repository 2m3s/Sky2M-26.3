package com.epic60869.sky2m.features.dungeons;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.features.core.Sky2MWorldRender;
import com.epic60869.sky2m.features.core.SkyHanniRepo;
import com.epic60869.sky2m.sb.events.WorldEvents;
import com.epic60869.sky2m.sb.skyblock.dungeon.DungeonBoss;
import com.epic60869.sky2m.sb.skyblock.dungeon.secrets.DungeonManager;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.properties.Property;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Livid Finder (https://github.com/hannibal002/SkyHanni, LGPL-2.1: features/dungeon/DungeonLividFinder.kt).
 * In the F5/M5 boss, the wool block above the arena turns the real Livid's colour. The Livids are told apart by their
 * skin (SkyHanni's repo dungeons/LividSolver.json) or their nametag colour; the real one gets a box, a "Livid" label and
 * a line from your crosshair, and the fake ones (and their nametags) can be hidden. Not while you're blind.
 */
public final class DungeonLividFinder {
    private static final BlockPos BLOCK_LOCATION = new BlockPos(6, 109, 43);

    /** REGEX-TEST: §2﴾ §2§lLivid§r§r §a7M§c❤ §2﴿ */
    private static final Pattern ARMOR_STAND_NAME = Pattern.compile("§(?<colorCode>.)﴾ (?:§e§5 )?§.§lLivid.*");
    /** REGEX-TEST: Doctor Livid */
    private static final Pattern LIVID_NAME = Pattern.compile("^(?<name>\\w+) Livid$");

    private static final Map<String, ChatFormatting> TEXTURE_TO_COLOR = new HashMap<>();
    private static Map<String, ChatFormatting> nameToColor = new LinkedHashMap<>(Map.of(
        "Vendetta", ChatFormatting.WHITE,
        "Doctor", ChatFormatting.GRAY,
        "Crossed", ChatFormatting.LIGHT_PURPLE,
        "Purple", ChatFormatting.DARK_PURPLE,
        "Scream", ChatFormatting.BLUE,
        "Hockey", ChatFormatting.RED,
        "Arcade", ChatFormatting.YELLOW,
        "Smile", ChatFormatting.GREEN,
        "Frog", ChatFormatting.DARK_GREEN));

    private static RemotePlayer livid;
    private static final Set<RemotePlayer> FAKE_LIVIDS = new HashSet<>();
    private static ChatFormatting color;
    private static boolean wasInBoss;
    private static Level lastLevel;
    private static int ticks;

    private DungeonLividFinder() {}

    private static FeatureConfigs.LividFinder config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.dungeons.lividFinder;
    }

    private static boolean enabled() {
        FeatureConfigs.LividFinder config = config();
        return config != null && config.enabled;
    }

    public static void init() {
        SkyHanniRepo.load("dungeons/LividSolver", DungeonLividFinder::readRepo);
        WorldEvents.BLOCK_STATE_UPDATE.register(DungeonLividFinder::onBlockChange);
        ClientTickEvents.END_CLIENT_TICK.register(DungeonLividFinder::tick);
        Sky2MWorldRender.register(DungeonLividFinder::render);
    }

    private static void readRepo(JsonObject json) {
        TEXTURE_TO_COLOR.clear();
        Map<String, ChatFormatting> names = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("livids").entrySet()) {
            String key = entry.getKey();
            if (key.length() < 2) continue;
            ChatFormatting repoColor = ChatFormatting.getByCode(key.charAt(1));
            if (repoColor == null) continue;
            JsonObject info = entry.getValue().getAsJsonObject();
            TEXTURE_TO_COLOR.put(info.get("skin").getAsString(), repoColor);
            names.put(info.get("name").getAsString(), repoColor);
        }
        if (!names.isEmpty()) nameToColor = names;
    }

    private static boolean inLividBossRoom() {
        return Sky2MLocation.inDungeon() && Sky2MLocation.dungeonFloor().endsWith("5") && DungeonManager.getBoss() == DungeonBoss.LIVID;
    }

    private static void tick(Minecraft mc) {
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            color = null;
            livid = null;
            FAKE_LIVIDS.clear();
        }
        boolean inBoss = inLividBossRoom();
        // Entering the boss room: the wool starts red (Hockey).
        if (inBoss && !wasInBoss) color = ChatFormatting.RED;
        wasInBoss = inBoss;
        if (++ticks % 20 != 0 || !enabled() || !inBoss || color == null) return;

        for (RemotePlayer entity : lividEntities(mc)) {
            ChatFormatting lividColor = colorOf(entity);
            if (lividColor == null) {
                Matcher m = LIVID_NAME.matcher(entity.getName().getString());
                String texture = skinTexture(entity);
                if (m.matches() && texture != null) {
                    ChatFormatting nameColor = nameToColor.get(m.group("name"));
                    if (nameColor != null) {
                        TEXTURE_TO_COLOR.put(texture, nameColor);
                        lividColor = nameColor;
                    }
                }
            }
            if (lividColor == null) continue;
            if (lividColor == color) livid = entity;
            else FAKE_LIVIDS.add(entity);
        }
    }

    private static void onBlockChange(BlockPos pos, BlockState old, BlockState updated) {
        if (!inLividBossRoom() || !pos.equals(BLOCK_LOCATION)) return;
        ChatFormatting newColor = woolColor(updated.getBlock());
        if (newColor == null) return;
        color = newColor;
        livid = null;
        FAKE_LIVIDS.clear();

        Minecraft mc = Minecraft.getInstance();
        for (RemotePlayer mob : lividEntities(mc)) {
            if (isLividColor(mob, ChatFormatting.RED) && newColor != ChatFormatting.RED) {
                FAKE_LIVIDS.add(mob);
                continue;
            }
            if (isLividColor(mob, newColor)) {
                livid = mob;
                FAKE_LIVIDS.remove(mob);
            }
        }
    }

    /** NPC players (not real ones) named "X Livid" or wearing a known Livid skin. Only searched in the F5/M5 boss. */
    private static List<RemotePlayer> lividEntities(Minecraft mc) {
        List<RemotePlayer> result = new ArrayList<>();
        if (mc.level == null) return result;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof RemotePlayer player) || player.getUUID().version() == 4) continue;
            String texture = skinTexture(player);
            if (LIVID_NAME.matcher(player.getName().getString()).matches() || (texture != null && TEXTURE_TO_COLOR.containsKey(texture))) {
                result.add(player);
            }
        }
        return result;
    }

    private static ChatFormatting colorOf(RemotePlayer player) {
        String texture = skinTexture(player);
        return texture == null ? null : TEXTURE_TO_COLOR.get(texture);
    }

    private static String skinTexture(RemotePlayer player) {
        for (Property property : player.getGameProfile().properties().get("textures")) {
            if (property.name().equals("textures")) return property.value();
        }
        return null;
    }

    private static String formatted(Component component) {
        return com.epic60869.sky2m.features.sbc.SbcItems.legacy(component).replace("§r", "");
    }

    private static boolean isLividColor(RemotePlayer player, ChatFormatting color) {
        String code = color.toString();
        return formatted(player.getName()).startsWith(code + "﴾ " + code + "§lLivid");
    }

    /** SkyHanni's ColoredBlockCompat wool colours. */
    private static ChatFormatting woolColor(Block block) {
        if (block == Blocks.WOOL.white()) return ChatFormatting.WHITE;
        if (block == Blocks.WOOL.orange() || block == Blocks.WOOL.brown()) return ChatFormatting.GOLD;
        if (block == Blocks.WOOL.magenta() || block == Blocks.WOOL.pink()) return ChatFormatting.LIGHT_PURPLE;
        if (block == Blocks.WOOL.lightBlue()) return ChatFormatting.AQUA;
        if (block == Blocks.WOOL.yellow()) return ChatFormatting.YELLOW;
        if (block == Blocks.WOOL.lime()) return ChatFormatting.GREEN;
        if (block == Blocks.WOOL.gray() || block == Blocks.WOOL.lightGray()) return ChatFormatting.GRAY;
        if (block == Blocks.WOOL.cyan()) return ChatFormatting.DARK_AQUA;
        if (block == Blocks.WOOL.purple()) return ChatFormatting.DARK_PURPLE;
        if (block == Blocks.WOOL.blue()) return ChatFormatting.BLUE;
        if (block == Blocks.WOOL.green()) return ChatFormatting.DARK_GREEN;
        if (block == Blocks.WOOL.red()) return ChatFormatting.RED;
        if (block == Blocks.WOOL.black()) return ChatFormatting.DARK_GRAY;
        return null;
    }

    private static boolean isBlind(Minecraft mc) {
        MobEffectInstance blindness = mc.player == null ? null : mc.player.getEffect(MobEffects.BLINDNESS);
        return blindness != null && blindness.getDuration() > 10;
    }

    /** Hide Wrong Livids: the fake Livids and their nametags aren't drawn (only once the real one is known). */
    public static boolean hide(Entity entity) {
        FeatureConfigs.LividFinder config = config();
        if (config == null || !config.hideWrong || livid == null || !inLividBossRoom()) return false;
        if (entity instanceof RemotePlayer player && FAKE_LIVIDS.contains(player)) return true;
        if (entity instanceof ArmorStand stand && stand.getCustomName() != null) {
            Matcher m = ARMOR_STAND_NAME.matcher(formatted(stand.getCustomName()));
            if (m.matches()) return ChatFormatting.getByCode(m.group("colorCode").charAt(0)) != color;
        }
        return false;
    }

    private static void render(PrimitiveCollector collector) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled() || !inLividBossRoom() || isBlind(mc) || livid == null || mc.player == null) return;
        FeatureConfigs.LividFinder config = config();
        ChatFormatting shown = config.colorOverride.color != null ? config.colorOverride.color : color;
        if (shown == null || net.minecraft.network.chat.TextColor.fromLegacyFormat(shown) == null || !livid.isAlive() || !mc.player.hasLineOfSight(livid)) return;

        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 location = livid.getPosition(partial);
        AABB box = livid.getBoundingBox().move(location.subtract(livid.position()));
        int rgb = net.minecraft.network.chat.TextColor.fromLegacyFormat(shown).getValue();
        float[] colour = {((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f};

        collector.submitText(Component.literal("Livid").withStyle(shown), location.add(0, livid.getBbHeight() + 0.5, 0), 1.5f, false);
        collector.submitFilledBox(box, colour, 0.5f, false);
        collector.submitLineFromCursor(location.add(0, livid.getBbHeight() / 2, 0), colour, 1f, 3f);
    }

    /** Color Override: forces the highlight to one colour (Default uses the real Livid's). */
    public enum LividColorHighlight {
        DEFAULT(null, "Default"),
        BLACK(ChatFormatting.BLACK), DARK_BLUE(ChatFormatting.DARK_BLUE), DARK_GREEN(ChatFormatting.DARK_GREEN),
        DARK_AQUA(ChatFormatting.DARK_AQUA), DARK_RED(ChatFormatting.DARK_RED), DARK_PURPLE(ChatFormatting.DARK_PURPLE),
        GOLD(ChatFormatting.GOLD), GRAY(ChatFormatting.GRAY), DARK_GRAY(ChatFormatting.DARK_GRAY), BLUE(ChatFormatting.BLUE),
        GREEN(ChatFormatting.GREEN), AQUA(ChatFormatting.AQUA), RED(ChatFormatting.RED), LIGHT_PURPLE(ChatFormatting.LIGHT_PURPLE),
        YELLOW(ChatFormatting.YELLOW), WHITE(ChatFormatting.WHITE);

        public final ChatFormatting color;
        private final String label;

        LividColorHighlight(ChatFormatting color) {
            this(color, prettyName(color));
        }

        LividColorHighlight(ChatFormatting color, String label) {
            this.color = color;
            this.label = label;
        }

        private static String prettyName(ChatFormatting color) {
            StringBuilder out = new StringBuilder();
            for (String word : color.name().toLowerCase(java.util.Locale.ROOT).split("_")) {
                if (!out.isEmpty()) out.append(' ');
                out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return out.toString();
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
