package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import com.epic60869.sky2m.mixin.Sky2MChatComponentAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Pest Spawn Timer, Pest Spawn alert and Pest Spawn Sound (https://github.com/hannibal002/SkyHanni,
 * LGPL-2.1: PestSpawnTimer, PestSpawn, PestSpawnSound). Unlike SkyHanni, the cooldown never reads the tab list's
 * Pests widget: it always counts down the cooldown time set in the settings from the last pest spawn.
 */
public final class PestTimer {
    // GROSS! A  Pest has appeared in Plot - S 4!   (the pest glyph between "A" and "Pest" may be missing)
    private static final List<Pattern> ONE_PEST = List.of(
        Pattern.compile("^\\w+! A \\S* ?Pest has appeared in Plot - (?<plot>.*)!"),
        Pattern.compile("^\\w+! A \\S* ?Pest has appeared in (?<plot>The Barn)!"));
    // YUCK! 4  Pest have spawned in Plot - 14!
    private static final List<Pattern> MULTIPLE_PESTS = List.of(
        Pattern.compile("^\\w+! (?<amount>\\d) \\S* ?Pests? have spawned in Plot - (?<plot>.*)!"),
        Pattern.compile("^\\w+! (?<amount>\\d) \\S* ?Pests? have spawned in (?<plot>The Barn)!"));
    // GROSS! While you were offline,  Pest spawned in Plots 12, 9, 5, 11 and 3!
    private static final Pattern OFFLINE_PESTS = Pattern.compile("^\\w+! While you were offline, \\S* ?Pests? spawned in Plots (?<plots>.*)!");
    private static final Pattern CLICK_TO_TP = Pattern.compile("\\s*CLICK HERE to teleport to the plot!");

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Sky2M pest sounds");
        t.setDaemon(true);
        return t;
    });

    // Spawn timer state (PestSpawnTimer).
    private static long lastPestSpawnTime; // 0 = far past
    private static long pestCooldownEndTime; // 0 = far past
    private static long lastCropBrokenTime;
    private static long longestCropBrokenTime;
    private static final List<Long> PEST_SPAWN_TIMES = new ArrayList<>();
    private static boolean hasWarned;
    private static boolean hasReminderShown;
    private static boolean shouldRender;
    private static boolean shouldRepeatWarning;
    private static long lastPlayedSound;
    private static List<Component> display = List.of();
    private static boolean wasInGarden;
    private static int ticks;
    private static int lastCooldownSetting = -1;
    private static String lastChatNotice;

    // Spawn sound state (PestSpawnSound).
    private static long lastPestSpawnSound;

    private PestTimer() {}

    private static FeatureConfigs.Garden garden() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.garden;
    }

    private static FeatureConfigs.PestTimer config() {
        FeatureConfigs.Garden g = garden();
        return g == null ? null : g.pestTimer;
    }

    private static FeatureConfigs.PestSpawn spawnConfig() {
        FeatureConfigs.Garden g = garden();
        return g == null ? null : g.pestSpawn;
    }

    public static void init() {
        Sky2MChat.onChat(PestTimer::onChat);
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> overlay || !blockMessage(message));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (level.isClientSide() && Sky2MLocation.inGarden()) onCropClick();
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());

        Sky2MHuds.setting("pest_timer", () -> config() != null && config().enabled);
        Sky2MHuds.register("pest_timer", "Pest Spawn Timer", () -> shouldRender && Sky2MLocation.inGarden(), () -> display,
            List.of(Component.literal("§eLast pest spawned: §b8s ago"), Component.literal("§ePest Cooldown: §b1m 8s")),
            383, 93);
    }

    // ---------------------------------------------------------------------------------------------- PestSpawn

    private static boolean blockedNext;

    private static void onChat(Sky2MChat.Message message) {
        if (!Sky2MLocation.inGarden()) return;
        String text = message.text();
        boolean blocked = false;
        for (Pattern p : ONE_PEST) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                spawn(1, List.of(m.group("plot")));
                blocked = true;
            }
        }
        for (Pattern p : MULTIPLE_PESTS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                spawn(Integer.parseInt(m.group("amount")), List.of(m.group("plot")));
                blocked = true;
            }
        }
        Matcher offline = OFFLINE_PESTS.matcher(text);
        if (offline.find()) {
            spawn(null, List.of(offline.group("plots").split(", | and ")));
        }
        if (CLICK_TO_TP.matcher(text).matches() && lastPestSpawnTime != 0 && System.currentTimeMillis() - lastPestSpawnTime < 1000) {
            blocked = true;
        }
        FeatureConfigs.PestSpawn c = spawnConfig();
        blockedNext = blocked && c != null && c.chatMessageFormat != FeatureConfigs.PestSpawn.ChatMessageFormat.HYPIXEL;
    }

    /** Called right after {@link #onChat} for the same message (the chat packet is read first). */
    private static boolean blockMessage(Component message) {
        boolean block = blockedNext;
        blockedNext = false;
        return block;
    }

    private static void spawn(Integer amount, List<String> plotNames) {
        PestFinder.onPestSpawn(amount, plotNames);
        onPestSpawn();
        if (amount == null) return;
        FeatureConfigs.PestSpawn c = spawnConfig();
        if (c == null) return;
        String plotName = plotNames.isEmpty() ? "" : plotNames.getFirst();
        String pestName = amount == 1 ? "Pest" : "Pests";
        String message = "§e" + amount + " §a" + pestName + " Spawned in §b" + plotName + "§a!";

        if (c.showTitle) title(message, 7_000);

        if (c.chatMessageFormat == FeatureConfigs.PestSpawn.ChatMessageFormat.COMPACT) {
            String tpName = plotName.equals("The Barn") ? "barn" : plotName;
            MutableComponent line = Component.literal(message).withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/plottp " + tpName))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("§eClick to run /plottp " + tpName + "!"))));
            Sky2MAlerts.chat(line);
        }
    }

    // ---------------------------------------------------------------------------------------------- PestSpawnTimer

    private static void onPestSpawn() {
        FeatureConfigs.PestTimer c = config();
        shouldRepeatWarning = false;
        long now = System.currentTimeMillis();
        long spawnTime = now - lastPestSpawnTime;

        if (lastPestSpawnTime != 0 && c != null) {
            if (longestCropBrokenTime <= c.averagePestSpawnTimeout * 1000L) {
                PEST_SPAWN_TIMES.add(spawnTime);
            }
            if (c.pestSpawnChatMessage) {
                Sky2MAlerts.chat(Component.literal("§ePests spawned in §b" + format(spawnTime)));
            }
        }

        hasWarned = false;
        hasReminderShown = false;
        longestCropBrokenTime = 0;
        lastPestSpawnTime = now;
        setCooldownFromSpawn();
    }

    /** The cooldown always ends the set time after the last pest spawn. */
    private static void setCooldownFromSpawn() {
        FeatureConfigs.Garden g = garden();
        if (g == null || lastPestSpawnTime == 0) return;
        pestCooldownEndTime = lastPestSpawnTime + Math.round(g.pestCooldownSeconds) * 1000L;
    }

    private static void onCropClick() {
        long now = System.currentTimeMillis();
        long timeDiff = lastCropBrokenTime == 0 ? 0 : now - lastCropBrokenTime;
        if (timeDiff > longestCropBrokenTime) longestCropBrokenTime = timeDiff;
        lastCropBrokenTime = now;
    }

    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean inGarden = mc.player != null && Sky2MLocation.inGarden();
        if (inGarden && !wasInGarden) onIslandJoin();
        wasInGarden = inGarden;
        if (!inGarden) return;
        FeatureConfigs.Garden g = garden();
        FeatureConfigs.PestTimer c = config();
        if (g == null || c == null) return;

        // The cooldown setting changed: move the end of the current cooldown with it.
        int setting = Math.round(g.pestCooldownSeconds);
        if (setting != lastCooldownSetting) {
            lastCooldownSetting = setting;
            setCooldownFromSpawn();
        }

        if (shouldRepeatWarning) {
            if (inWardrobeOrLoadouts(mc)) {
                shouldRepeatWarning = false;
            } else {
                repeatSound();
            }
        }
        ticks++;
        if (ticks % 5 == 0) shouldRender = shouldRender();
        if (ticks % 20 == 0) onSecondPassed();
    }

    private static void onIslandJoin() {
        shouldRepeatWarning = false;
        longestCropBrokenTime = lastCropBrokenTime == 0 ? 0 : System.currentTimeMillis() - lastCropBrokenTime;
        lastPestSpawnTime = 0;
        pestCooldownEndTime = 0;
    }

    private static void onSecondPassed() {
        if (!isEnabled()) return;
        FeatureConfigs.PestTimer c = config();
        display = drawDisplay();
        long now = System.currentTimeMillis();
        if (shouldRepeatWarning) {
            if (pestCooldownEndTime <= now) {
                shouldRepeatWarning = false;
            } else {
                countdownWarn(pestCooldownEndTime - now);
            }
        }

        if (hasWarned || !c.cooldownOverWarning) return;
        if (pestCooldownEndTime == 0) return;
        if (pestCooldownEndTime <= now) {
            cooldownExpired();
            return;
        }
        if (hasReminderShown) return;
        if (pestCooldownEndTime - (c.cooldownWarningTime + 1) * 1000L <= now) {
            cooldownReminder(pestCooldownEndTime);
        } else {
            shouldRepeatWarning = false;
        }
    }

    private static List<Component> drawDisplay() {
        FeatureConfigs.PestTimer c = config();
        long now = System.currentTimeMillis();
        Map<FeatureConfigs.PestTimer.TextEntry, Component> lineMap = new EnumMap<>(FeatureConfigs.PestTimer.TextEntry.class);

        String lastPestSpawned = lastPestSpawnTime == 0
            ? "§cNo pest spawned since joining."
            : "§eLast pest spawned: §b" + format(now - lastPestSpawnTime) + " ago";
        lineMap.put(FeatureConfigs.PestTimer.TextEntry.PEST_TIMER, Component.literal(lastPestSpawned));

        String cooldownValue;
        if (pestCooldownEndTime == 0) cooldownValue = "§cUnknown";
        else if (pestCooldownEndTime <= now) cooldownValue = "§aReady!";
        else cooldownValue = format(pestCooldownEndTime - now);
        lineMap.put(FeatureConfigs.PestTimer.TextEntry.PEST_COOLDOWN, Component.literal("§ePest Cooldown: §b" + cooldownValue));

        long average = averageSpawnTime();
        if (average != 0) {
            lineMap.put(FeatureConfigs.PestTimer.TextEntry.AVERAGE_PEST_SPAWN, Component.literal("§eAverage time to spawn: §b" + format(average)));
        }

        List<Component> lines = new ArrayList<>();
        for (FeatureConfigs.PestTimer.TextEntry entry : c.pestDisplay) {
            Component line = lineMap.get(entry);
            if (line != null) lines.add(line);
        }
        return lines;
    }

    private static long averageSpawnTime() {
        if (PEST_SPAWN_TIMES.isEmpty()) return 0;
        long sum = 0;
        for (long t : PEST_SPAWN_TIMES) sum += t;
        return sum / PEST_SPAWN_TIMES.size();
    }

    private static boolean shouldRender() {
        if (!isEnabled()) return false;
        FeatureConfigs.PestTimer c = config();
        if (c.onlyWhenHolding.isEmpty()) return true;
        Minecraft mc = Minecraft.getInstance();
        ItemStack held = mc.player == null ? ItemStack.EMPTY : mc.player.getMainHandItem();
        for (FeatureConfigs.PestTimer.HeldItem item : c.onlyWhenHolding) {
            boolean holding = switch (item) {
                case FARMING_TOOL -> com.epic60869.sky2m.Sky2MMouseLock.isFarmingTool(held);
                case VACUUM -> itemCategory(held).endsWith("VACUUM");
                case LASSO -> itemCategory(held).endsWith("LASSO");
            };
            if (holding) return true;
        }
        return false;
    }

    /** The item category from the last lore line ("§9§lRARE VACUUM" -> "RARE VACUUM"), or "". */
    private static String itemCategory(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null || lore.lines().isEmpty()) return "";
        String last = Sky2MLocation.strip(lore.lines().getLast().getString()).trim();
        return last.replaceAll("^a ", "").replaceAll(" a$", "").trim();
    }

    private static boolean inWardrobeOrLoadouts(Minecraft mc) {
        if (mc.gui.screen() == null) return false;
        String title = mc.gui.screen().getTitle().getString();
        return title.startsWith("Wardrobe") || title.startsWith("Loadouts") || title.startsWith("Equipment Wardrobe");
    }

    private static void cooldownExpired() {
        shouldRepeatWarning = false;
        title("§cPest Cooldown Has Expired!", 3_000);
        notice("§cPest spawn cooldown has expired!");
        playUserSound();
        hasWarned = true;
    }

    private static void cooldownReminder(long endTime) {
        FeatureConfigs.PestTimer c = config();
        notice("§cPest spawn cooldown expires in " + format(endTime - System.currentTimeMillis()));
        hasWarned = true;
        hasReminderShown = true;

        if (c.repeatWarning) {
            countdownWarn(endTime - System.currentTimeMillis());
            shouldRepeatWarning = true;
            return;
        }
        title("§cPest Cooldown Expires Soon!", 3_000);
        playUserSound();
    }

    private static void countdownWarn(long timeLeft) {
        String text = "§cPest spawn cooldown expires in " + format(timeLeft);
        title(text, 1_000);
        notice(text);
    }

    private static void repeatSound() {
        FeatureConfigs.PestTimer c = config();
        if (!c.enabled || !Sky2MLocation.inGarden()) return;
        if (System.currentTimeMillis() - lastPlayedSound >= c.sound.repeatDuration * 50L) {
            lastPlayedSound = System.currentTimeMillis();
            playUserSound();
        }
    }

    private static boolean isEnabled() {
        FeatureConfigs.PestTimer c = config();
        return c != null && c.enabled && Sky2MLocation.inGarden();
    }

    /** Test button and the cooldown warning sound. */
    public static void playUserSound() {
        FeatureConfigs.PestTimer c = config();
        if (c == null) return;
        playSound(c.sound.name, c.sound.pitch);
    }

    /**
     * A chat notice that replaces the previous one from the pest timer, like SkyHanni's message id: a countdown
     * doesn't fill the chat with one line a second.
     */
    private static void notice(String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            String previous = lastChatNotice;
            if (previous != null) {
                Sky2MChatComponentAccessor chat = (Sky2MChatComponentAccessor) mc.gui.hud.getChat();
                List<GuiMessage> all = chat.sky2m$allMessages();
                for (int i = 0; i < Math.min(all.size(), 20); i++) {
                    if (all.get(i).content().getString().endsWith(previous)) {
                        all.remove(i);
                        chat.sky2m$refreshTrimmedMessages();
                        break;
                    }
                }
            }
            lastChatNotice = Sky2MLocation.strip(Component.literal(text).getString());
            mc.gui.hud.getChat().addClientSystemMessage(com.epic60869.sky2m.custom.util.Compat.PREFIX.get().append(Component.literal(text)));
        });
    }

    private static void title(String text, long durationMs) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            mc.gui.hud.setTimes(0, (int) (durationMs / 50), 5);
            mc.gui.hud.setTitle(Component.literal(text));
            mc.gui.hud.setSubtitle(Component.empty());
        });
    }

    // ---------------------------------------------------------------------------------------------- PestSpawnSound

    /**
     * Hypixel's pest spawn sound (a bass note at pitch 1.4920635, volume 1, close to you): kept, muted or replaced.
     * Returns true to cancel the sound.
     */
    public static boolean onPlaySound(String soundName, float volume, float pitch, double distance) {
        if (!Sky2MLocation.inGarden()) return false;
        if (!(soundName.equals("block.note_block.bass") && distance < 15.0 && volume == 1.0f && pitch == 1.4920635f)) return false;
        FeatureConfigs.PestSpawn c = spawnConfig();
        if (c == null) return false;
        boolean cancel = switch (c.soundMode) {
            case DEFAULT -> false;
            case MUTED -> true;
            case CUSTOM -> {
                repeatSpawnSound();
                yield true;
            }
            case PLUMBER -> {
                plumberSpawnSound();
                yield true;
            }
        };
        if (c.soundMode != FeatureConfigs.PestSpawn.SoundMode.DEFAULT) lastPestSpawnSound = System.currentTimeMillis();
        return cancel;
    }

    /** Test button: the custom spawn sound. */
    public static void repeatSpawnSound() {
        if (System.currentTimeMillis() - lastPestSpawnSound < 5_000) return;
        FeatureConfigs.PestSpawn c = spawnConfig();
        if (c == null) return;
        for (int i = 0; i < c.sound.repeatAmount; i++) {
            SCHEDULER.schedule(() -> playSound(c.sound.name, c.sound.pitch), (long) i * c.sound.repeatFrequency, TimeUnit.MILLISECONDS);
        }
    }

    private static void plumberSpawnSound() {
        if (System.currentTimeMillis() - lastPestSpawnSound < 5_000) return;
        String name = spawnConfig().sound.name;
        float e = 0.890899f, cNote = 0.707107f, g = 1.059463f, lowG = 0.529732f;
        long[] delays = {0, 166, 333, 333, 166, 333, 666};
        float[] notes = {e, e, e, cNote, e, g, lowG};
        long at = 0;
        for (int i = 0; i < notes.length; i++) {
            at += delays[i];
            float pitch = notes[i];
            SCHEDULER.schedule(() -> playSound(name, pitch), at, TimeUnit.MILLISECONDS);
        }
    }

    /** Plays a sound by name ("block.note_block.pling" or "namespace:path") as a warning (full volume). */
    static void playSound(String name, float pitch) {
        Identifier id = Identifier.tryParse(name == null ? "" : name.trim());
        if (id == null) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), pitch, 1f));
            } catch (Exception ignored) {
            }
        });
    }

    /** "List of Sounds" button: SkyHanni's sound list page. */
    public static void openSoundsList() {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create("https://misode.github.io/sounds/"));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------------------------------------- time format

    /** SkyHanni's Duration.format(): "1m 8s", "4m 32s", "1h 2m 3s"; under a second "0.5s". */
    static String format(long millis) {
        if (millis < 0) return "Soon";
        if (millis < 1000) return "0." + (millis / 100) + "s";
        long days = millis / 86_400_000L;
        long hours = millis / 3_600_000L % 24;
        long minutes = millis / 60_000L % 60;
        long seconds = millis / 1000L % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) out.append(String.format(java.util.Locale.US, "%,d", days)).append("d ");
        if (hours > 0) out.append(hours).append("h ");
        if (minutes > 0) out.append(minutes).append("m ");
        if (seconds > 0) out.append(seconds).append("s ");
        return out.toString().trim();
    }
}
