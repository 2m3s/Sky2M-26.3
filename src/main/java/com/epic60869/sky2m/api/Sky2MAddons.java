package com.epic60869.sky2m.api;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The installed {@link Sky2MAddon}s, found once from the {@code "sky2m-addon"} entrypoint. Only the add-ons in
 * {@link #ALLOWED} are loaded: any other mod offering the entrypoint is ignored.
 */
public final class Sky2MAddons {
    public static final String ENTRYPOINT = "sky2m-addon";

    /** Mod ids allowed to add to /s2 > Add-ons: only the private Sky2M Personal add-on. */
    public static final java.util.Set<String> ALLOWED = java.util.Set.of("sky2m-personal");

    /** An add-on and the mod it comes from. */
    public record Entry(String modId, String version, Sky2MAddon addon) {}

    private static List<Entry> addons;

    private Sky2MAddons() {}

    public static synchronized List<Entry> list() {
        if (addons != null) return addons;
        List<Entry> out = new ArrayList<>();
        for (EntrypointContainer<Sky2MAddon> container : FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT, Sky2MAddon.class)) {
            var meta = container.getProvider().getMetadata();
            if (!ALLOWED.contains(meta.getId())) {
                System.err.println("[Sky2M] Ignoring add-on " + meta.getId() + ": only " + ALLOWED + " can add to /s2 > Add-ons");
                continue;
            }
            try {
                out.add(new Entry(meta.getId(), meta.getVersion().getFriendlyString(), container.getEntrypoint()));
            } catch (RuntimeException | LinkageError e) {
                System.err.println("[Sky2M] Add-on " + meta.getId() + " couldn't be loaded: " + e);
            }
        }
        out.sort(Comparator.comparing(e -> e.addon().name(), String.CASE_INSENSITIVE_ORDER));
        addons = List.copyOf(out);
        System.out.println("[Sky2M] Add-ons: " + (addons.isEmpty() ? "none" : addons.stream().map(Entry::modId).toList()));
        return addons;
    }
}
