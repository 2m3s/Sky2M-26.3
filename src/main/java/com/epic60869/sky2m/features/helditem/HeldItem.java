// Ported from Skysoft (https://github.com/Akinsoft/Skysoft): the /skysoft helditem command (SkysoftCommands.kt).
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Misc > Held Item, ported from Skysoft: moves, turns and scales the item in your hand, changes its swing speed and
 * style, and can show an item's vanilla texture instead of Hypixel's pack model; globally or per SkyBlock item, set
 * in the held item editor (/s2 helditem).
 */
public final class HeldItem {
    private HeldItem() {}

    public static void init(Path configDir) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config != null) {
            config.misc.heldItem.repairLoadedValues();
            migrateHeldItemModel(config, configDir);
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("helditem")
                    .executes(c -> Compat.queueOpenScreen(HeldItemEditorScreen.create()))));
            }
        });
    }

    /**
     * Copies the old Held Item Model settings once: its global values and the items saved with /s2 helditem save
     * (sky2m-held-items.json). Its swing movement and No Swing / No Re-equip options have no Skysoft equivalent.
     */
    private static void migrateHeldItemModel(Sky2MConfig config, Path configDir) {
        HeldItemConfig held = config.misc.heldItem;
        if (held.migratedFromHeldItemModel) return;
        held.migratedFromHeldItemModel = true;
        Sky2MConfig.LegacyHeldItemModel old = config.misc.heldItemModel;
        if (old != null) {
            held.enabled = old.enabled;
            held.settings.ignoresMiningEffects = old.ignoreMiningEffects;
            copy(held.global, old.x, old.y, old.z, old.scale, old.rotationX, old.rotationY, old.rotationZ, old.swingSpeed);
        }
        Path file = configDir.resolve("sky2m-held-items.json");
        try {
            if (Files.exists(file)) {
                Map<String, LegacyTransform> items = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<Map<String, LegacyTransform>>() {}.getType());
                if (items != null) {
                    items.forEach((id, t) -> {
                        if (t == null || id == null || id.isBlank()) return;
                        HeldItemTransform transform = new HeldItemTransform();
                        copy(transform, t.x, t.y, t.z, t.scale, t.rotationX, t.rotationY, t.rotationZ, t.swingSpeed);
                        held.itemTransforms.put(HeldItemConfig.normalized(id), transform);
                    });
                }
            }
        } catch (Exception e) {
            System.err.println("[Sky2M] Could not copy the old held item transforms: " + e);
        }
        held.repairLoadedValues();
        config.misc.heldItemModel = null; // copied; drop it from the saved config
        Sky2MConfig.saveCurrent(config);
    }

    private static void copy(HeldItemTransform t, float x, float y, float z, float scale, float rotationX, float rotationY,
                             float rotationZ, float swingSpeed) {
        t.x = x;
        t.y = y;
        t.z = z;
        t.scale = scale;
        t.rotationX = rotationX;
        t.rotationY = rotationY;
        t.rotationZ = rotationZ;
        t.swingSpeed = swingSpeed;
        t.repairLoadedValues();
    }

    private static final class LegacyTransform {
        float x, y, z, scale = 1f, rotationX, rotationY, rotationZ, swingSpeed = 1f;
    }
}
