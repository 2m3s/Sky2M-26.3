package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.LegacyMaterials;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pack Disabler: SkyBlock items drawn the way they looked before Hypixel's 2026 resource pack. Hypixel's pack still
 * loads (SkyBlock needs it, and the client tells the server the truth about it); only the model each SkyBlock item is
 * drawn with changes, and only for models in Hypixel's own namespace. An old look is a vanilla item (drawn by your own
 * resource packs), an old player-head skin, or, for items that never had one, a texture of Killer560's.
 * <p>
 * Ported from Killer560's Mod (packdisabler/), MIT License, Copyright (c) 2026 Killer560, including its item table
 * (made from Hypixel's item API and the NotEnoughUpdates-REPO, MIT) and its textures. Old 1.8 item ids go through
 * Sky2M' LegacyMaterials. The models that change with an item's state follow Noamm's PackDisabler (CC0-1.0). If
 * Noamm's PackDisabler is installed, this stands back and lets it do the job.
 */
public final class PackDisabler {
    private static final String HYPIXEL = "hypixel_skyblock";
    private static final Identifier PLAYER_HEAD = Identifier.withDefaultNamespace("player_head");

    /** One item's old look: a head skin hash, a modern vanilla id, a 1.8 "id:damage", or a texture of our own. */
    private record Look(String skin, String vanilla, String legacy, String own) {}

    private static volatile Map<String, Look> looks;
    private static final Map<String, Optional<Identifier>> MODELS = new ConcurrentHashMap<>();
    private static final Map<String, Optional<ResolvableProfile>> PROFILES = new ConcurrentHashMap<>();
    private static Boolean noamm;

    private PackDisabler() {}

    private static boolean active() {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null || !config.misc.packDisabler) return false;
        if (noamm == null) noamm = FabricLoader.getInstance().isModLoaded("packdisabler");
        return !noamm;
    }

    private static Map<String, Look> looks() {
        Map<String, Look> map = looks;
        if (map != null) return map;
        synchronized (PackDisabler.class) {
            if (looks != null) return looks;
            Map<String, Look> loaded = new HashMap<>();
            try (InputStream in = PackDisabler.class.getResourceAsStream("/assets/sky2m/skyblock/item_looks.json")) {
                if (in != null) {
                    JsonObject items = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("items");
                    for (Map.Entry<String, JsonElement> e : items.entrySet()) {
                        JsonObject o = e.getValue().getAsJsonObject();
                        loaded.put(e.getKey(), new Look(str(o, "s"), str(o, "i"), str(o, "l"), str(o, "o")));
                    }
                }
            } catch (Exception e) {
                System.err.println("[Sky2M] Pack Disabler couldn't read its item table: " + e.getMessage());
            }
            looks = loaded;
            return loaded;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    /** The model to draw {@code stack} with; {@code current} unless it's one of Hypixel's and we know the old look. */
    public static Identifier model(ItemStack stack, Identifier current) {
        if (current == null || !HYPIXEL.equals(current.getNamespace()) || !active()) return current;
        try {
            CompoundTag tag = Compat.customDataView(stack);
            String id = tag.getStringOr("id", "");
            if (id.isEmpty()) return tag.contains("quiver_arrow") ? Identifier.withDefaultNamespace("arrow") : current;
            Identifier model = MODELS.computeIfAbsent(id, PackDisabler::lookModel).orElse(null);
            if (model == null) return current;
            Identifier dynamic = dynamicModel(id, stack, tag, current);
            return dynamic != null ? dynamic : model;
        } catch (Exception e) {
            return current;
        }
    }

    /** The head skin for a SkyBlock item now drawn as a player head (its old look was a head). */
    public static ResolvableProfile profile(ItemStack stack, ResolvableProfile current) {
        if (stack == null || stack.isEmpty() || (current != null && stack.is(Items.PLAYER_HEAD))) return current;
        Identifier model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null || !HYPIXEL.equals(model.getNamespace()) || !active()) return current;
        try {
            String id = Compat.customDataView(stack).getStringOr("id", "");
            if (id.isEmpty()) return current;
            ResolvableProfile old = PROFILES.computeIfAbsent(id, PackDisabler::lookProfile).orElse(null);
            return old != null ? old : current;
        } catch (Exception e) {
            return current;
        }
    }

    private static Optional<Identifier> lookModel(String id) {
        Look look = look(id);
        if (look == null) return Optional.empty();
        if (look.skin() != null) return Optional.of(PLAYER_HEAD);
        Item item = vanillaItem(look);
        if (item != null) return Optional.ofNullable(item.components().get(DataComponents.ITEM_MODEL));
        if (look.own() != null) return Optional.of(Identifier.fromNamespaceAndPath("sky2m", "packdisabler/" + look.own()));
        return Optional.empty();
    }

    private static Optional<ResolvableProfile> lookProfile(String id) {
        Look look = look(id);
        if (look == null || look.skin() == null) return Optional.empty();
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + look.skin() + "\"}}}";
        String texture = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        // The same skin always gets the same id, so the game's skin cache treats it as one head.
        UUID uuid = UUID.nameUUIDFromBytes(look.skin().getBytes(StandardCharsets.UTF_8));
        return Optional.of(ResolvableProfile.createResolved(new GameProfile(uuid, "sky2m", Compat.propertyMapWithTexture(texture))));
    }

    /** The row for "INK_SACK:3", or NEU's "INK_SACK-3". */
    private static Look look(String id) {
        Map<String, Look> map = looks();
        Look look = map.get(id);
        if (look == null && id.indexOf('-') >= 0) look = map.get(id.replace('-', ':'));
        return look;
    }

    private static Item vanillaItem(Look look) {
        if (look.vanilla() != null) {
            Identifier id = Identifier.tryParse("minecraft:" + look.vanilla());
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item != null && item != Items.AIR) return item;
        }
        if (look.legacy() != null) {
            // "minecraft:ink_sack:3", "minecraft:gold_sword"
            String legacy = look.legacy().startsWith("minecraft:") ? look.legacy().substring("minecraft:".length()) : look.legacy();
            int colon = legacy.lastIndexOf(':');
            String name = colon > 0 ? legacy.substring(0, colon) : legacy;
            int damage = 0;
            try {
                if (colon > 0) damage = Integer.parseInt(legacy.substring(colon + 1));
            } catch (NumberFormatException ignored) {}
            if (name.equals("paper") || name.equals("skull") || name.equals("skull_item")) return null;
            Identifier id = Identifier.tryParse(LegacyMaterials.modern(name, damage));
            Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
            if (item != null && item != Items.AIR && item != Items.PAPER && item != Items.PLAYER_HEAD) return item;
        }
        return null;
    }

    /** Items whose Hypixel model changes with their state; adapted from Noamm's PackDisabler (CC0-1.0). */
    private static Identifier dynamicModel(String id, ItemStack stack, CompoundTag tag, Identifier current) {
        switch (id) {
            case "CARNIVAL_SHOVEL" -> {
                String path = current.getPath();
                return switch (path.substring(path.lastIndexOf('/') + 1)) {
                    case "carnival_shovel_iron" -> Identifier.withDefaultNamespace("iron_shovel");
                    case "carnival_shovel_gold" -> Identifier.withDefaultNamespace("golden_shovel");
                    case "carnival_shovel_diamond" -> Identifier.withDefaultNamespace("diamond_shovel");
                    default -> null;
                };
            }
            case "FIREDUST_DAGGER", "BURSTFIRE_DAGGER", "HEARTFIRE_DAGGER" -> {
                return tag.getIntOr("td_attune_mode", -1) == 1 ? Identifier.withDefaultNamespace("golden_sword") : null;
            }
            case "MAWDUST_DAGGER", "BURSTMAW_DAGGER", "HEARTMAW_DAGGER" -> {
                return tag.getIntOr("td_attune_mode", -1) == 3 ? Identifier.withDefaultNamespace("diamond_sword") : null;
            }
            case "VOIDEDGE_KATANA", "VORPAL_KATANA", "ATOMSPLIT_KATANA" -> {
                Minecraft mc = Minecraft.getInstance();
                return mc.player != null && mc.player.getCooldowns().isOnCooldown(stack) ? Identifier.withDefaultNamespace("golden_sword") : null;
            }
            case "FUNGI_CUTTER", "FUNGI_CUTTER_2", "FUNGI_CUTTER_3" -> {
                String mode = tag.getStringOr("fungi_cutter_mode", "");
                return "RED".equals(mode) ? Identifier.withDefaultNamespace("red_mushroom")
                    : "BROWN".equals(mode) ? Identifier.withDefaultNamespace("brown_mushroom") : null;
            }
            default -> {
                return null;
            }
        }
    }
}
