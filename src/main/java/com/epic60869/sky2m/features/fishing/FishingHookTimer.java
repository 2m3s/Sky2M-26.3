package com.epic60869.sky2m.features.fishing;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.core.Sky2MHuds;
import com.epic60869.sky2m.features.sbc.SbcItems;
import com.google.gson.annotations.Expose;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SkyHanni's Fishing Bobber Timer (how long your bobber has been out, from the cast or from when it lands in the
 * water/lava) and Fishing Hook Display (Hypixel's countdown over the bobber until you can reel in, "3.0" ... "!!!",
 * bigger and on your screen, with the armor stand hidden), from features/fishing (LGPL-2.1).
 */
public final class FishingHookTimer {
    /** Hypixel's timer stand over the bobber: "§e§l3.0" counting down, then "§c§l!!!". */
    private static final Pattern TIMER = Pattern.compile("§e§l(?<time>\\d+(?:\\.\\d+)?)|(?:§.)*(?<alert>!!!)");

    private static int bobberId = -1;
    private static long deployTime = -1;
    private static boolean wasInLiquid;
    private static int timerStandId = -1;
    private static Component timerText;

    private FishingHookTimer() {}

    private static Config config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.fishing.hookTimer;
    }

    public static void init() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(FishingHookTimer::tick);

        Sky2MHuds.setting("fishing_bobber_timer", () -> config() != null && config().bobberTimer.enabled);
        Sky2MHuds.register("fishing_bobber_timer", "Fishing Bobber Timer",
            () -> config() != null && config().bobberTimer.enabled && deployTime >= 0 && holdingRod(),
            () -> List.of(SbcItems.parseLegacy("§aBobber: §f" + format(System.currentTimeMillis() - deployTime))),
            List.of(SbcItems.parseLegacy("§aBobber: §f12.3s")),
            10, 10);
        Sky2MHuds.setting("fishing_hook_display", () -> config() != null && config().hookDisplay.enabled);
        Sky2MHuds.register("fishing_hook_display", "Fishing Hook Display",
            () -> config() != null && config().hookDisplay.enabled && timerText != null && holdingRod(),
            () -> timerText == null ? List.of() : List.of(timerText),
            List.of(SbcItems.parseLegacy("§e§l2.4")),
            225, 150);
    }

    private static void reset() {
        bobberId = -1;
        deployTime = -1;
        wasInLiquid = false;
        timerStandId = -1;
        timerText = null;
    }

    private static void tick(Minecraft mc) {
        Player player = mc.player;
        if (player == null || mc.level == null || !Compat.isOnSkyblock()) {
            if (bobberId != -1) reset();
            return;
        }
        Config config = config();
        if (config == null) return;
        FishingHook bobber = player.fishing;
        if (bobber == null) {
            // Reeled in (a catch) or gone.
            if (bobberId != -1) reset();
            return;
        }
        if (bobber.getId() != bobberId) {
            // A new cast.
            bobberId = bobber.getId();
            wasInLiquid = false;
            timerStandId = -1;
            timerText = null;
            deployTime = config.bobberTimer.startOnLiquidTouch ? -1 : System.currentTimeMillis();
        }
        boolean inLiquid = bobber.isInWater() || bobber.isInLava();
        if (inLiquid && !wasInLiquid && config.bobberTimer.startOnLiquidTouch) deployTime = System.currentTimeMillis();
        wasInLiquid = inLiquid;

        if (config.hookDisplay.enabled) readTimerStand(mc, bobber, config.hookDisplay);
    }

    /** The timer stand closest to the bobber. */
    private static void readTimerStand(Minecraft mc, FishingHook bobber, HookDisplay config) {
        Component best = null;
        int bestId = -1;
        double bestDistance = Double.MAX_VALUE;
        for (ArmorStand stand : mc.level.getEntitiesOfClass(ArmorStand.class, bobber.getBoundingBox().inflate(2.5, 3, 2.5), Entity::hasCustomName)) {
            Component name = stand.getCustomName();
            if (name == null) continue;
            Matcher m = TIMER.matcher(SbcItems.legacy(name).replace("§r", ""));
            if (!m.matches()) continue;
            double distance = stand.distanceToSqr(bobber);
            if (distance >= bestDistance) continue;
            bestDistance = distance;
            bestId = stand.getId();
            best = m.group("alert") != null ? SbcItems.parseLegacy(config.customAlertText.replace("&", "§")) : name;
        }
        timerStandId = bestId;
        timerText = best;
    }

    /** Hide the original armor stand while our display shows it (Sky2MHideFireballsMixin). */
    public static boolean hideStand(Entity entity) {
        if (timerStandId == -1 || entity.getId() != timerStandId) return false;
        Config config = config();
        return config != null && config.hookDisplay.enabled && config.hookDisplay.hideArmorStand && holdingRod();
    }

    private static boolean holdingRod() {
        Player player = Minecraft.getInstance().player;
        return player != null && player.getMainHandItem().getItem() instanceof FishingRodItem;
    }

    private static String format(long millis) {
        if (millis < 60_000) return String.format(Locale.US, "%.1fs", millis / 1000.0);
        return (millis / 60_000) + "m " + String.format(Locale.US, "%.1fs", (millis % 60_000) / 1000.0);
    }

    public static final class Config {
        @Expose
        @ConfigOption(name = "Fishing Bobber Timer", desc = "")
        @Accordion
        public BobberTimer bobberTimer = new BobberTimer();

        @Expose
        @ConfigOption(name = "Fishing Hook Display", desc = "")
        @Accordion
        public HookDisplay hookDisplay = new HookDisplay();
    }

    /** SkyHanni's FishingBobberTimerConfig. */
    public static final class BobberTimer {
        @Expose
        @ConfigOption(name = "Fishing Bobber Timer", desc = "Show a timer for how long the fishing bobber has been deployed. Move it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Start on Liquid Touch", desc = "Start the timer when the bobber touches the water/lava, instead of when it is cast.")
        @ConfigEditorBoolean
        public boolean startOnLiquidTouch = true;
    }

    /** SkyHanni's FishingHookDisplayConfig. */
    public static final class HookDisplay {
        @Expose
        @ConfigOption(name = "Enabled", desc = "Display the Hypixel timer until the fishing hook can be pulled out of the water/lava, only bigger and on your screen. Move and resize it in /s2 gui.")
        @ConfigEditorBoolean
        public boolean enabled = false;

        @Expose
        @ConfigOption(name = "Custom Alert", desc = "Replaces the default §c§l!!! §7Hypixel alert with your own custom one.")
        @ConfigEditorText
        public String customAlertText = "&c&l!!!";

        @Expose
        @ConfigOption(name = "Hide Armor Stand", desc = "Hide the original armor stand from Hypixel when the display is enabled.")
        @ConfigEditorBoolean
        public boolean hideArmorStand = true;
    }
}
