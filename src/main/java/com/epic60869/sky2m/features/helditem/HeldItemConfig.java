// Ported from Skysoft (https://github.com/Akinsoft/Skysoft), src/main/kotlin/com/skysoft/config/HeldItemConfig.kt, with
// the Held Item Update Fix toggle from config/FixesConfig.kt.
// SPDX-License-Identifier: LGPL-3.0-only
package com.epic60869.sky2m.features.helditem;

import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorButton;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Misc > Held Item: the global transform, per-item transforms and texture choices, and where the editor sits. */
public final class HeldItemConfig {
    public static final int AUTO_EDITOR_POSITION = -1;

    @Expose
    @ConfigOption(name = "Enabled", desc = "Apply held item position, scale, swing, and texture settings.")
    @ConfigEditorBoolean
    public boolean enabled = false;

    @Expose
    @ConfigOption(name = "Settings", desc = "Held item settings.")
    @Accordion
    public Settings settings = new Settings();

    @Expose public HeldItemTransform global = new HeldItemTransform();
    @Expose public Map<String, HeldItemTransform> itemTransforms = new LinkedHashMap<>();
    @Expose public HeldItemTransform.TextureMode globalTextureMode = HeldItemTransform.TextureMode.PACK;
    @Expose public Map<String, HeldItemTransform.TextureMode> itemTextureModes = new LinkedHashMap<>();
    @Expose public int editorX = AUTO_EDITOR_POSITION;
    @Expose public int editorY = AUTO_EDITOR_POSITION;
    /** The old Held Item Model settings were copied over. */
    @Expose public boolean migratedFromHeldItemModel = false;

    public static final class Settings {
        @ConfigOption(name = "Editor", desc = "Open the held item editor (/s2 helditem).")
        @ConfigEditorButton(buttonText = "Open")
        public Runnable openEditor = HeldItemEditorScreen::open;

        @Expose
        @ConfigOption(name = "Ignore Mining Effects", desc = "Keep swing duration unchanged by Haste and Mining Fatigue.")
        @ConfigEditorBoolean
        public boolean ignoresMiningEffects = false;

        @Expose
        @ConfigOption(name = "Held Item Update Fix", desc = "Keep the held item steady when Hypixel updates it (ability cooldowns, lore changes) instead of playing the re-equip animation, and keep mining the same block through those updates.")
        @ConfigEditorBoolean
        public boolean updateFix = true;
    }

    public HeldItemTransform transformFor(String itemId) {
        if (itemId == null) return global;
        HeldItemTransform item = itemTransforms.get(normalized(itemId));
        return item != null ? item : global;
    }

    public HeldItemTransform customize(String itemId) {
        return itemTransforms.computeIfAbsent(normalized(itemId), id -> global.copyValues());
    }

    public boolean usesVanillaTexture(String itemId) {
        return textureModeFor(itemId) == HeldItemTransform.TextureMode.VANILLA;
    }

    public boolean toggleGlobalTexture() {
        globalTextureMode = globalTextureMode.toggled();
        return true;
    }

    public boolean toggleItemTexture(String itemId) {
        String id = normalized(itemId);
        if (id.isEmpty()) return false;
        itemTextureModes.put(id, textureModeFor(id).toggled());
        return true;
    }

    public boolean hasGlobalCustomization() {
        return !global.isDefault() || globalTextureMode != HeldItemTransform.TextureMode.PACK;
    }

    public boolean resetGlobalCustomization() {
        boolean changed = hasGlobalCustomization();
        global.reset();
        globalTextureMode = HeldItemTransform.TextureMode.PACK;
        return changed;
    }

    public boolean hasItemCustomization(String itemId) {
        return itemId != null && (itemTransforms.containsKey(normalized(itemId)) || itemTextureModes.containsKey(normalized(itemId)));
    }

    public boolean removeItemCustomization(String itemId) {
        boolean transform = itemTransforms.remove(normalized(itemId)) != null;
        boolean texture = itemTextureModes.remove(normalized(itemId)) != null;
        return transform || texture;
    }

    /** What an undo step restores: the global settings, or one item's. */
    sealed interface CustomizationSnapshot permits GlobalSnapshot, ItemSnapshot {}

    record GlobalSnapshot(HeldItemTransform.Snapshot transform, HeldItemTransform.TextureMode textureMode) implements CustomizationSnapshot {}

    record ItemSnapshot(String itemId, HeldItemTransform.Snapshot transform, HeldItemTransform.TextureMode textureMode) implements CustomizationSnapshot {}

    CustomizationSnapshot snapshotCustomization(String itemId) {
        if (itemId == null) return new GlobalSnapshot(global.snapshot(), globalTextureMode);
        String id = normalized(itemId);
        HeldItemTransform transform = itemTransforms.get(id);
        return new ItemSnapshot(id, transform == null ? null : transform.snapshot(), itemTextureModes.get(id));
    }

    void restoreCustomization(CustomizationSnapshot snapshot) {
        if (snapshot instanceof GlobalSnapshot g) {
            global.restore(g.transform());
            globalTextureMode = g.textureMode();
        } else if (snapshot instanceof ItemSnapshot i) {
            if (i.transform() != null) itemTransforms.put(i.itemId(), i.transform().toTransform());
            else itemTransforms.remove(i.itemId());
            if (i.textureMode() != null) itemTextureModes.put(i.itemId(), i.textureMode());
            else itemTextureModes.remove(i.itemId());
        }
    }

    public void repairLoadedValues() {
        if (global == null) global = new HeldItemTransform();
        if (settings == null) settings = new Settings();
        if (globalTextureMode == null) globalTextureMode = HeldItemTransform.TextureMode.PACK;
        global.repairLoadedValues();
        Map<String, HeldItemTransform> transforms = new LinkedHashMap<>();
        if (itemTransforms != null) {
            itemTransforms.forEach((id, transform) -> {
                String key = normalized(id);
                if (!key.isEmpty() && transform != null) {
                    transform.repairLoadedValues();
                    transforms.put(key, transform);
                }
            });
        }
        itemTransforms = transforms;
        Map<String, HeldItemTransform.TextureMode> textures = new LinkedHashMap<>();
        if (itemTextureModes != null) {
            itemTextureModes.forEach((id, mode) -> {
                String key = normalized(id);
                if (!key.isEmpty() && mode != null) textures.put(key, mode);
            });
        }
        itemTextureModes = textures;
        if (editorX < AUTO_EDITOR_POSITION) editorX = AUTO_EDITOR_POSITION;
        if (editorY < AUTO_EDITOR_POSITION) editorY = AUTO_EDITOR_POSITION;
    }

    private HeldItemTransform.TextureMode textureModeFor(String itemId) {
        if (itemId != null) {
            HeldItemTransform.TextureMode mode = itemTextureModes.get(normalized(itemId));
            if (mode != null) return mode;
        }
        return globalTextureMode;
    }

    static String normalized(String itemId) {
        return Objects.requireNonNullElse(itemId, "").trim().toUpperCase(Locale.US);
    }
}
