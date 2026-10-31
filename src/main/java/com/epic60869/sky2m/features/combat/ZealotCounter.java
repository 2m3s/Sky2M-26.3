package com.epic60869.sky2m.features.combat;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zealot tracker in the style of SkyHanni's trackers: kills and Summoning Eyes, shown either for
 * this session or in total. The mode is switched by clicking it while an inventory is open.
 *
 * <p>Only your own kills count. A Zealot counts if you hit it in the last few seconds (melee hits,
 * your own arrows, or being near where your Wither Impact landed) and you gained Combat XP when it
 * died, which Hypixel only gives for your own kills.
 */
public final class ZealotCounter {
    private static final List<String> END_LOCATIONS = List.of("The End", "Dragon's Nest", "Void Sepulture", "Zealot Bruiser Hideout", "Void Slate");
    private static final List<String> WITHER_BLADES = List.of("Hyperion", "Astraea", "Scylla", "Valkyrie", "Necron's Blade");
    private static final long HIT_MEMORY_MS = 5000;
    private static final double WITHER_IMPACT_RANGE = 10;
    private static final Gson GSON = new Gson();

    /** Entity id → when you last hit it. */
    private static final Map<Integer, Long> HIT_BY_YOU = new HashMap<>();
    /** Entity ids already counted, so the death packet and the health check never count one Zealot twice. */
    private static final Set<Integer> COUNTED = new HashSet<>();
    private static int witherImpactTicks;
    /** Zealots that died after you hit them, waiting for your Combat XP to confirm the kill: id -> time of death. */
    private static final Map<Integer, Long> PENDING = new HashMap<>();
    /** Pending kills already counted towards the last Summoning Eye's "It took" message. */
    private static final Set<Integer> BEFORE_EYE = new HashSet<>();
    /** When you last gained Combat XP (Hypixel shows "+X Combat" in the action bar only for your own kills). */
    private static long lastCombatXp;
    private static final long XP_WINDOW_MS = 1500;
    private static final java.util.regex.Pattern COMBAT_XP = java.util.regex.Pattern.compile("\\+[\\d,.]+ Combat \\(");
    /** Hypixel Wither Impact feedback, e.g. "Your Implosion hit 3 enemies for 1,023,504.2 damage." */
    private static final Pattern IMPLOSION_HITS = Pattern.compile("Your\\s+Implosion\\s+hit\\s+(\\d+)\\s+enemies?\\s+for\\s+[\\d,.]+(?:\\s+damage)?\\.?$", Pattern.CASE_INSENSITIVE);
    private static final long IMPLOSION_CONFIRM_MS = 1800;
    private static int pendingImplosionHits;
    private static long pendingImplosionAt;
    private static final Pattern TAB_COUNTER_VALUE = Pattern.compile("([\\d,]+)\\s*/\\s*([\\d,]+)");
    /** Hypixel TAB row: "Special Zealots: 123 / 400"; the first number is kills since the last eye. */
    private static final Pattern SPECIAL_ZEALOTS_TAB = Pattern.compile("special\\s+zealots\\s*:\\s*([\\d,]+)\\s*/\\s*400", Pattern.CASE_INSENSITIVE);
    /** True while Hypixel's authoritative tab-list pity counter is available. */
    private static boolean tabPityDetected;
    private static int tabPityTicks;
    /** Last observed Hypixel Special Zealots counter, used to reconcile TAB updates with local detections. */
    private static int lastObservedTabPity = -1;
    /** Previous TAB count captured when Hypixel resets the counter after spawning a Special Zealot. */
    private static int tabPityBeforeReset = -1;
    private static long tabPityResetAt;
    private static final long TAB_RESET_DROP_WINDOW_MS = 3000;
    /** Locally counted kills that TAB has not caught up with yet. Prevents delayed TAB updates double-counting them. */
    private static int localUnconfirmedKills;
    private static final long UPTIME_IDLE_MS = 10_000L;
    private static long uptimeAccumulated;
    private static long uptimeSegmentStart;
    private static long uptimeLastKill;
    private static boolean uptimeRunning;

    private static int totalKills, totalEyes, sinceEye;
    private static String lastEyeMessage = "";
    /** Kills the last Summoning Eye took, shown on its drop message; -1 before the first eye this session. */
    private static int lastEyeKills = -1;
    private static long lastEyeTime;
    private static int sessionKills, sessionEyes;
    private static boolean showSession;
    private static Path file;

    private ZealotCounter() {}

    /**
     * Hypixel writes "RARE DROP!" in bold; a player typing or pasting the same words into chat can't make them bold,
     * so this tells the real drop apart from a copy of it.
     */
    private static boolean boldDropTag(net.minecraft.network.chat.Component component) {
        return component.visit((style, part) -> (part.contains("RARE DROP!") && style.isBold()) || part.contains("§lRARE DROP!")
            ? java.util.Optional.of(Boolean.TRUE) : java.util.Optional.empty(), net.minecraft.network.chat.Style.EMPTY).isPresent();
    }

    private static boolean enabled() {
        Sky2MConfig c = Sky2MConfig.current();
        return c != null && c.combat.zealotCounter && inEnd();
    }

    public static void init(Path configDir) {
        file = configDir.resolve("sky2m-zealots.json");
        load();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> save());
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> save());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
            COUNTED.clear();
            HIT_BY_YOU.clear();
            lastObservedTabPity = -1;
            tabPityBeforeReset = -1;
            tabPityResetAt = 0;
            localUnconfirmedKills = 0;
            tabPityDetected = false;
            pendingImplosionHits = 0;
            pendingImplosionAt = 0;
        });
        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            // Only Hypixel's own drop line, not party/guild chat or other mods repeating it. The same line arriving
            // twice at once (the packet handled twice, or a mod re-sending it) is counted once.
            if (!text.startsWith("RARE DROP! Summoning Eye") || !boldDropTag(message.component())) return;
            long now = System.currentTimeMillis();
            if (text.equals(lastEyeMessage) && now - lastEyeTime < 1000) return;
            lastEyeMessage = text;
            lastEyeTime = now;
            totalEyes++;
            sessionEyes++;
            // TAB can reset to 0 as soon as a Special Zealot spawns, before its eye drop message arrives.
            // If a reset happened just now, use the value TAB showed immediately before the reset.
            // Otherwise use the live TAB value, or the local estimate if TAB is unavailable.
            boolean justReset = tabPityBeforeReset >= 0 && now - tabPityResetAt <= TAB_RESET_DROP_WINDOW_MS;
            lastEyeKills = justReset ? tabPityBeforeReset : (tabPityDetected ? sinceEye : sinceEye + PENDING.size());
            if (justReset) {
                tabPityBeforeReset = -1;
                tabPityResetAt = 0;
            }
            BEFORE_EYE.addAll(PENDING.keySet());
            if (!tabPityDetected) sinceEye = 0;
            save();
        });
        // "RARE DROP! Summoning Eye (Kills: 100)": how many Zealots that eye took, added to Hypixel's drop line.
        ClientReceiveMessageEvents.MODIFY_GAME.register((component, overlay) -> {
            if (overlay || lastEyeKills < 0 || System.currentTimeMillis() - lastEyeTime > 1000) return component;
            String text = Sky2MLocation.strip(component.getString()).trim();
            if (!text.startsWith("RARE DROP! Summoning Eye") || !boldDropTag(component)) return component;
            return component.copy()
                .append(Component.literal(" (Kills: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(fmt(lastEyeKills)).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY));
        });

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && enabled() && entity instanceof Enderman) HIT_BY_YOU.put(entity.getId(), System.currentTimeMillis());
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && enabled()) {
                String held = Sky2MLocation.strip(com.epic60869.sky2m.custom.util.Compat.realName(player.getItemInHand(hand)).getString());
                // The teleport lands a tick or two later, so mark Zealots around you for a few ticks.
                if (WITHER_BLADES.stream().anyMatch(held::contains)) witherImpactTicks = 4;
            }
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(ZealotCounter::tick);
        Sky2MChat.onActionBar(message -> {
            if (enabled() && COMBAT_XP.matcher(message.text()).find()) lastCombatXp = System.currentTimeMillis();
        });
        // Implosion reports how many enemies the blast actually hit. In the End's Zealot
        // areas, keep that count as a fallback for Zealots that die/despawn before the entity
        // tracker can observe their death. Combat XP confirms the kills before applying it.
        Sky2MChat.onChat(message -> {
            if (!enabled()) return;
            String text = Sky2MLocation.strip(message.text()).trim();
            Matcher implosion = IMPLOSION_HITS.matcher(text);
            if (!implosion.find()) return;
            int hits;
            try { hits = Math.min(20, Math.max(0, Integer.parseInt(implosion.group(1)))); }
            catch (NumberFormatException ignored) { return; }
            if (hits == 0) return;
            pendingImplosionHits = Math.max(pendingImplosionHits, hits);
            pendingImplosionAt = System.currentTimeMillis();
        });

        com.epic60869.sky2m.features.core.Sky2MHuds.setting("zealots", () -> Sky2MConfig.current() != null && Sky2MConfig.current().combat.zealotCounter);
        Sky2MHuds.register("zealots", "Zealot Tracker", ZealotCounter::enabled,
            // While an inventory is open the tracker is drawn over it instead, with the clickable mode switch.
            () -> Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> ? List.of() : lines(false),
            List.of(title(), kv("Total Kills: ", "1,234"), kv("Since last eye: ", "321"), kv("Summoning Eyes: ", "4")),
            8, 300);

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof AbstractContainerScreen<?>)) return;
            ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> renderInInventory(graphics));
            ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !clickModeSwitch(event.x(), event.y()));
        });
    }

    public static boolean inEnd() {
        if (Sky2MLocation.areaIs("The End")) return true;
        String location = Sky2MLocation.location();
        for (String name : END_LOCATIONS) if (location.contains(name)) return true;
        return false;
    }

    // ----- Display -----

    private static Component title() {
        return Component.literal("Zealot Tracker").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD);
    }

    private static Component kv(String key, String value) {
        return Component.literal(key).withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static String fmt(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static List<Component> lines(boolean withModeSwitch) {
        List<Component> lines = new ArrayList<>();
        lines.add(title());
        lines.add(kv(showSession ? "Kills: " : "Total Kills: ", fmt(showSession ? sessionKills : totalKills)));
        lines.add(kv("Since last eye: ", fmt(sinceEye)));
        lines.add(kv("Uptime: ", formatUptime(uptimeMillis())));
        lines.add(kv("Summoning Eyes: ", fmt(showSession ? sessionEyes : totalEyes)));
        if (withModeSwitch) lines.add(modeLine());
        return lines;
    }

    private static final String MODE_LABEL = "Display Mode: ";
    private static final String TOTAL = "[Total]";
    private static final String SESSION = "[This Session]";

    private static Component modeLine() {
        MutableComponent line = Component.literal(MODE_LABEL).withStyle(ChatFormatting.GRAY);
        line.append(Component.literal(TOTAL).withStyle(showSession ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW));
        line.append(Component.literal(" "));
        line.append(Component.literal(SESSION).withStyle(showSession ? ChatFormatting.YELLOW : ChatFormatting.DARK_GRAY));
        return line;
    }

    private static void renderInInventory(GuiGraphicsExtractor graphics) {
        if (com.epic60869.sky2m.features.misc.storage.StorageOverlay.hidesOtherHuds()) return;
        if (!enabled()) return;
        List<Component> lines = lines(true);
        int[] pos = Sky2MHuds.screenPosition("zealots", lines);
        var placement = Sky2MHuds.placement("zealots");
        Sky2MHuds.render(graphics, lines, pos[0], pos[1], placement.scale, placement.background);
    }

    /** Switches between total and session when the matching "[...]" on the mode line is clicked. */
    private static boolean clickModeSwitch(double mouseX, double mouseY) {
        if (com.epic60869.sky2m.features.misc.storage.StorageOverlay.hidesOtherHuds()) return false;
        if (!enabled()) return false;
        List<Component> lines = lines(true);
        int[] pos = Sky2MHuds.screenPosition("zealots", lines);
        float scale = Sky2MHuds.placement("zealots").scale;
        double localX = (mouseX - pos[0]) / scale - Sky2MHuds.PADDING;
        double localY = (mouseY - pos[1]) / scale - Sky2MHuds.PADDING;
        int modeRow = lines.size() - 1;
        if (localY < modeRow * Sky2MHuds.LINE_HEIGHT || localY >= modeRow * Sky2MHuds.LINE_HEIGHT + 9) return false;
        var font = Minecraft.getInstance().font;
        int totalStart = font.width(MODE_LABEL);
        int totalEnd = totalStart + font.width(TOTAL);
        int sessionStart = totalEnd + font.width(" ");
        int sessionEnd = sessionStart + font.width(SESSION);
        if (localX >= totalStart && localX < totalEnd) showSession = false;
        else if (localX >= sessionStart && localX < sessionEnd) showSession = true;
        else return false;
        save();
        return true;
    }

    // ----- Kill detection -----

    private static boolean isZealot(Minecraft mc, Entity entity) {
        return entity instanceof Enderman && !mc.level.getEntitiesOfClass(ArmorStand.class, entity.getBoundingBox().inflate(0.5, 3, 0.5),
            stand -> stand.hasCustomName() && stand.getCustomName().getString().contains("Zealot")).isEmpty();
    }

    private static boolean hitByYou(Entity entity) {
        Long at = HIT_BY_YOU.get(entity.getId());
        return at != null && System.currentTimeMillis() - at <= HIT_MEMORY_MS;
    }

    private static void tick(Minecraft mc) {
        updateUptime(System.currentTimeMillis());
        // The tab list is walked three times per read: only with the counter on, and a few times a second (the row
        // changes about once a second).
        Sky2MConfig tabConfig = Sky2MConfig.current();
        if (tabConfig == null || !tabConfig.combat.zealotCounter) tabPityDetected = false;
        else if (++tabPityTicks % 5 == 0) updateTabPity(mc);
        if (mc.player == null || mc.level == null || !enabled()) {
            HIT_BY_YOU.clear();
            COUNTED.clear();
            PENDING.clear();
            BEFORE_EYE.clear();
            witherImpactTicks = 0;
            return;
        }
        long now = System.currentTimeMillis();

        // A matching Combat XP tick confirms the recent Implosion hit count. Count it only
        // in the configured End/Zealot areas (enabled() already checks this) and consume nearby
        // entity-based pending detections first to avoid double-counting the same blast.
        if (pendingImplosionHits > 0) {
            if (lastCombatXp >= pendingImplosionAt - 250 && lastCombatXp <= pendingImplosionAt + IMPLOSION_CONFIRM_MS) {
                int inferred = pendingImplosionHits;
                int alreadyPending = Math.min(inferred, PENDING.size());
                inferred -= alreadyPending;
                if (inferred > 0) {
                    for (int i = 0; i < inferred; i++) count(false, pendingImplosionAt);
                }
                pendingImplosionHits = 0;
                pendingImplosionAt = 0;
            } else if (now - pendingImplosionAt > IMPLOSION_CONFIRM_MS) {
                pendingImplosionHits = 0;
                pendingImplosionAt = 0;
            }
        }

        if (witherImpactTicks > 0) {
            witherImpactTicks--;
            for (Enderman enderman : mc.level.getEntitiesOfClass(Enderman.class, mc.player.getBoundingBox().inflate(WITHER_IMPACT_RANGE), e -> true)) {
                HIT_BY_YOU.put(enderman.getId(), now);
            }
        }
        // Your arrows mark the Zealots they reach.
        for (AbstractArrow arrow : mc.level.getEntitiesOfClass(AbstractArrow.class, mc.player.getBoundingBox().inflate(64),
                a -> a.getOwner() == mc.player)) {
            for (Enderman enderman : mc.level.getEntitiesOfClass(Enderman.class, arrow.getBoundingBox().inflate(1.5), e -> true)) {
                HIT_BY_YOU.put(enderman.getId(), now);
            }
        }
        // Zealots whose health reached zero; this also catches several killed at once.
        for (Enderman enderman : mc.level.getEntitiesOfClass(Enderman.class, mc.player.getBoundingBox().inflate(32), e -> true)) {
            if (enderman.isDeadOrDying()) countIfYours(mc, enderman);
        }
        HIT_BY_YOU.values().removeIf(at -> now - at > HIT_MEMORY_MS);

        // A kill only counts once your Combat XP confirms it, which rules out Zealots someone else
        // killed right next to you. Several kills in one Wither Impact share the same XP message.
        PENDING.entrySet().removeIf(entry -> {
            long diedAt = entry.getValue();
            if (lastCombatXp >= diedAt - 250 && lastCombatXp <= diedAt + XP_WINDOW_MS) {
                count(BEFORE_EYE.remove(entry.getKey()), diedAt);
                return true;
            }
            if (now - diedAt > XP_WINDOW_MS) {
                BEFORE_EYE.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /** Called when the server says an entity died. */
    public static void onEntityDeath(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (!(entity instanceof Enderman) || mc.player == null || mc.level == null || !enabled()) return;
        countIfYours(mc, entity);
    }

    private static void countIfYours(Minecraft mc, Entity entity) {
        if (COUNTED.contains(entity.getId()) || !hitByYou(entity) || !isZealot(mc, entity)) return;
        COUNTED.add(entity.getId());
        if (COUNTED.size() > 4096) COUNTED.clear();
        PENDING.put(entity.getId(), System.currentTimeMillis());
    }

    /**
     * Reads Hypixel's live Zealot/Eye pity counter from the ordered TAB entries. This is more
     * reliable than counting visible entities because it includes kills from every supported method.
     */
    private static void updateTabPity(Minecraft mc) {
        tabPityDetected = false;
        if (mc.getConnection() == null) return;

        Integer pity = null;
        // Hypixel's actual TAB row is "Special Zealots: <kills> / 400". Match this exact label first;
        // don't require the word "pity", "counter" or "kills" to appear in the row.
        for (var entry : mc.getConnection().getOnlinePlayers()) {
            Component row = com.epic60869.sky2m.custom.util.Compat.rawTabName(entry);
            if (row == null) continue;
            String text = Sky2MLocation.strip(row.getString()).toLowerCase(Locale.ROOT);
            Matcher matcher = SPECIAL_ZEALOTS_TAB.matcher(text);
            if (matcher.find()) {
                pity = parseTabNumber(matcher.group(1));
                break;
            }
        }

        // Fall back to explicitly labelled pity rows on other TAB layouts.
        for (var entry : mc.getConnection().getOnlinePlayers()) {
            Component row = com.epic60869.sky2m.custom.util.Compat.rawTabName(entry);
            if (row == null) continue;
            String text = Sky2MLocation.strip(row.getString()).toLowerCase(Locale.ROOT);
            if (!text.contains("pity") || !(text.contains("zealot") || text.contains("eye"))) continue;
            Matcher matcher = TAB_COUNTER_VALUE.matcher(text);
            if (matcher.find()) {
                pity = parseTabNumber(matcher.group(1));
                break;
            }
        }

        // Some TAB layouts name the row as a Zealot/Eye counter rather than explicitly calling it pity.
        if (pity == null) {
            for (var entry : mc.getConnection().getOnlinePlayers()) {
                Component row = com.epic60869.sky2m.custom.util.Compat.rawTabName(entry);
                if (row == null) continue;
                String text = Sky2MLocation.strip(row.getString()).toLowerCase(Locale.ROOT);
                if (!(text.contains("zealot") || text.contains("summoning eye"))
                    || !(text.contains("counter") || text.contains("kills") || text.contains("eye pity"))
                    || text.contains("chance") || text.contains("odds")) continue;
                Matcher matcher = TAB_COUNTER_VALUE.matcher(text);
                if (matcher.find()) {
                    pity = parseTabNumber(matcher.group(1));
                    break;
                }
            }
        }

        if (pity != null && pity >= 0) {
            sinceEye = pity;
            tabPityDetected = true;

            // TAB is authoritative for "since last eye", while local detection keeps the total responsive.
            // When TAB advances, first use its increase to confirm locally counted kills that it had not shown yet.
            // Only any increase beyond those local estimates is added as recovered/missed kills.
            if (lastObservedTabPity < 0) {
                lastObservedTabPity = pity;
            } else if (pity >= lastObservedTabPity) {
                int gained = pity - lastObservedTabPity;
                if (gained > 0) {
                    int confirmedLocal = Math.min(gained, localUnconfirmedKills);
                    localUnconfirmedKills -= confirmedLocal;
                    int missedLocally = gained - confirmedLocal;
                    if (missedLocally > 0) {
                        totalKills += missedLocally;
                        sessionKills += missedLocally;
                        if (totalKills % 25 < missedLocally) save();
                    }
                }
                lastObservedTabPity = pity;
            } else {
                // Hypixel can reset this counter when a Special Zealot spawns, not only when an eye drops.
                // Preserve the previous displayed value briefly so the imminent eye-drop line reports that count.
                tabPityBeforeReset = lastObservedTabPity;
                tabPityResetAt = System.currentTimeMillis();
                lastObservedTabPity = pity;
            }
        }
    }

    private static int parseTabNumber(String value) {
        try {
            return Integer.parseInt(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static void count(boolean beforeEye, long killedAt) {
        // Keep the main-branch detector responsive: count confirmed local kills immediately.
        // TAB later reconciles these estimates and only adds kills that local detection missed.
        totalKills++;
        sessionKills++;
        localUnconfirmedKills++;
        if (!tabPityDetected && !beforeEye) sinceEye++;
        if (totalKills % 25 == 0) save();
        recordKillTime(killedAt);
    }

    private static void recordKillTime(long killedAt) {
        if (killedAt < uptimeLastKill) killedAt = uptimeLastKill;
        updateUptime(killedAt);
        if (!uptimeRunning) {
            uptimeSegmentStart = killedAt;
            uptimeRunning = true;
        }
        uptimeLastKill = killedAt;
    }

    private static void updateUptime(long now) {
        if (!uptimeRunning || now - uptimeLastKill < UPTIME_IDLE_MS) return;
        uptimeAccumulated += Math.max(0, uptimeLastKill + UPTIME_IDLE_MS - uptimeSegmentStart);
        uptimeRunning = false;
    }

    private static long uptimeMillis() {
        long now = System.currentTimeMillis();
        updateUptime(now);
        if (!uptimeRunning) return uptimeAccumulated;
        return uptimeAccumulated + Math.max(0, Math.min(now, uptimeLastKill + UPTIME_IDLE_MS) - uptimeSegmentStart);
    }

    private static String formatUptime(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds / 60) % 60;
        long remainingSeconds = seconds % 60;
        return hours > 0
            ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainingSeconds)
            : String.format(Locale.US, "%02d:%02d", seconds / 60, remainingSeconds);
    }

    // ----- Storage -----

    private static void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            totalKills = root.has("totalKills") ? root.get("totalKills").getAsInt() : 0;
            sinceEye = root.has("sinceEye") ? root.get("sinceEye").getAsInt() : 0;
            totalEyes = root.has("eyes") ? root.get("eyes").getAsInt() : 0;
            showSession = root.has("showSession") && root.get("showSession").getAsBoolean();
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to load zealot tracker: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("totalKills", totalKills);
            root.addProperty("sinceEye", sinceEye);
            root.addProperty("eyes", totalEyes);
            root.addProperty("showSession", showSession);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[Sky2M] Failed to save zealot tracker: " + e.getMessage());
        }
    }
}
