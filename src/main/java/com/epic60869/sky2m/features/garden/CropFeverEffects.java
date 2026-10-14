package com.epic60869.sky2m.features.garden;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.core.Sky2MAlerts;
import com.epic60869.sky2m.features.core.Sky2MChat;
import com.epic60869.sky2m.features.core.Sky2MLocation;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Farming > Crop Fever Effects, ported from SkyOcean (MIT code, https://github.com/meowdding/SkyOcean,
 * features/garden/cropfever/CropFeverEffects.kt and CoinRainOverlay.kt): when you catch the CROP FEVER in the Garden,
 * a jackpot sound, music for the fever, the screen cycling through colours (SkyOcean's hue shift shader) and coins
 * raining down. It all stops when the fever is cured or you leave. SkyOcean's own sounds aren't free to reuse, so these
 * are Minecraft's: a fanfare, a rising bell jackpot and a short note block tune of Sky2M's own, looped.
 */
public final class CropFeverEffects {
    private static final String START = "WOAH! You caught a case of the CROP FEVER for 60 seconds!";
    private static final String END = "GONE! Your CROP FEVER has been cured!";
    private static final long FEVER_MS = 61_000;

    /** Hypixel's coin heads, as SkyOcean lists them (iron, gold, diamond, emerald, redstone and lapis coins). */
    private static final String[] COIN_TEXTURES = {
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTYwMDMwOTQ4MywKICAicHJvZmlsZUlkIiA6ICI1OTgyOWY1ZGY3MmM0ZmFlOTBmOGVhYmM0MjFjMzJkYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJQZXBwZXJEcmlua2VyIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzE2YjkwZjRmYTNlYzEwNmJmZWYyMWYzYjc1ZjU0MWExOGU0NzU3Njc0ZjdkNTgyNTBmYTdlNzQ5NTJmMDg3ZGMiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTcxOTg2ODk5MTUyNCwKICAicHJvZmlsZUlkIiA6ICIxMTM1Njg1ZTk3ZGE0ZjYyYTliNDQ3MzA0NGFiZjQ0MSIsCiAgInByb2ZpbGVOYW1lIiA6ICJNYXJpb1dsZXMiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvY2NiY2NlMjJhZjU1OWVkNmJhNjAzODg0NWRiMzhjY2JjYTJlNjJiNzdiODdhMjZhMDY2NTcxMDljZTBlZmJhNiIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTYwMTQ0OTA2NDY5NSwKICAicHJvZmlsZUlkIiA6ICI5ZDEzZjcyMTcxM2E0N2U0OTAwZTMyZGVkNjBjNDY3MyIsCiAgInByb2ZpbGVOYW1lIiA6ICJUYWxvZGFvIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzc0MGQ2ZTM2MmJjN2VlZTRmOTExZGJkMDQ0NjMwN2U3NDU4ZDEwNTBkMDlhZWU1MzhlYmNiMDI3M2NmNzU3NDIiCiAgICB9CiAgfQp9",
        "ewogICJ0aW1lc3RhbXAiIDogMTYxMzQwODkzNjg0OCwKICAicHJvZmlsZUlkIiA6ICI5MWYwNGZlOTBmMzY0M2I1OGYyMGUzMzc1Zjg2ZDM5ZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTdG9ybVN0b3JteSIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9lOWQ2MTViZDI3ZGU4ZTU4MGYzOTEzYTFhNDM5ODgwNDI5NTYxN2E2ZjcwYWY1MzJiNDYxMzdkYTVmMGU1ZTJkIiwKICAgICAgIm1ldGFkYXRhIiA6IHsKICAgICAgICAibW9kZWwiIDogInNsaW0iCiAgICAgIH0KICAgIH0KICB9Cn0=",
        "ewogICJ0aW1lc3RhbXAiIDogMTU5ODg0NzE5NTU3NiwKICAicHJvZmlsZUlkIiA6ICI0MWQzYWJjMmQ3NDk0MDBjOTA5MGQ1NDM0ZDAzODMxYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNZWdha2xvb24iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDdjNGY2NjMwNTk3YTQ5YWQyMjNkMTJjZjY0OGFmMjI4M2QzNGE2NWJmOWRkMDU3ZDE5OGQyOTgwNzc5YzM0IgogICAgfQogIH0KfQ==",
        "ewogICJ0aW1lc3RhbXAiIDogMTU5OTIxNDA2OTUyMCwKICAicHJvZmlsZUlkIiA6ICI0MWQzYWJjMmQ3NDk0MDBjOTA5MGQ1NDM0ZDAzODMxYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJNZWdha2xvb24iLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWMwZTkxNDQ3NmUxYjE1ZGEyYTkxZjQ1Njk2ZGQyMTc2NjlkNGRhYzRmYTYyMTY1MDkyOWJhY2UwM2RlMjI1NCIKICAgIH0KICB9Cn0="};

    private record Note(int tick, Holder<SoundEvent> sound, int key, float volume) {}

    /** The jackpot: a bell arpeggio rising over two octaves (note block keys 0-24). */
    private static final List<Note> JACKPOT = List.of(
        new Note(0, SoundEvents.NOTE_BLOCK_BELL, 6, 1f), new Note(2, SoundEvents.NOTE_BLOCK_BELL, 10, 1f),
        new Note(4, SoundEvents.NOTE_BLOCK_BELL, 13, 1f), new Note(6, SoundEvents.NOTE_BLOCK_BELL, 18, 1f),
        new Note(8, SoundEvents.NOTE_BLOCK_BELL, 22, 1f), new Note(10, SoundEvents.NOTE_BLOCK_CHIME, 18, 1f),
        new Note(10, SoundEvents.NOTE_BLOCK_CHIME, 22, 1f), new Note(13, SoundEvents.NOTE_BLOCK_CHIME, 24, 1f));

    /** Sky2M's fever tune: 64 ticks of plucked harp over a bouncing bass, looped while the fever lasts. */
    private static final List<Note> TUNE = tune();
    private static final int TUNE_TICKS = 64;

    private static List<Note> tune() {
        List<Note> out = new ArrayList<>();
        int[] melody = {13, 17, 20, 17, 22, 20, 17, 13, 15, 18, 22, 18, 20, 18, 15, 12};
        int[] bass = {6, 6, 11, 11, 8, 8, 4, 4};
        for (int i = 0; i < melody.length; i++) out.add(new Note(i * 4, SoundEvents.NOTE_BLOCK_HARP, melody[i], 0.55f));
        for (int i = 0; i < bass.length; i++) out.add(new Note(i * 8, SoundEvents.NOTE_BLOCK_BASS, bass[i], 0.7f));
        for (int i = 0; i < 16; i++) out.add(new Note(i * 4 + 2, SoundEvents.NOTE_BLOCK_HAT, 12, 0.3f));
        return out;
    }

    private record Coin(float x, long spawned, float speed, ItemStack item) {}

    private static final List<Coin> coins = new ArrayList<>();
    private static List<ItemStack> coinItems;
    private static boolean active;
    private static long startedAt;
    private static int feverTicks, jackpotTick = -1;

    private CropFeverEffects() {}

    private static FeatureConfigs.CropFever config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.farming.cropFever;
    }

    public static boolean active() {
        return active;
    }

    public static void init() {
        Sky2MChat.onChat(message -> {
            String text = message.text().trim();
            if (text.equals(START) && Sky2MLocation.inGarden()) start();
            else if (text.equals(END)) stop();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> stop());
        Sky2MLocation.onAreaChange(area -> {
            if (!"Garden".equals(area)) stop();
        });
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sky2m", "crop_fever_coins"), (g, delta) -> renderCoins(g));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root).then(ClientCommands.literal("cropfever")
                    .then(ClientCommands.literal("start").executes(c -> {
                        Minecraft.getInstance().execute(() -> start(true));
                        return 1;
                    }))
                    .then(ClientCommands.literal("stop").executes(c -> {
                        Minecraft.getInstance().execute(CropFeverEffects::stop);
                        return 1;
                    }))));
            }
        });
    }

    private static void start() {
        start(false);
    }

    /** {@code preview}: /s2 cropfever start, which works anywhere (with the feature on). */
    private static void start(boolean preview) {
        FeatureConfigs.CropFever c = config();
        if (c == null || !c.enabled || active) {
            if (preview && (c == null || !c.enabled)) Sky2MAlerts.chat(net.minecraft.network.chat.Component.literal("Turn on Farming > Crop Fever Effects first."));
            return;
        }
        active = true;
        startedAt = System.currentTimeMillis();
        feverTicks = 0;
        jackpotTick = c.rngSound ? 0 : -1;
        if (c.startingSound) Sky2MAlerts.play(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.2f, 0.8f);
        if (c.hueShift) CropFeverShader.apply(c.hueSpeed.id);
    }

    public static void stop() {
        if (!active) return;
        active = false;
        coins.clear();
        jackpotTick = -1;
        CropFeverShader.clear();
    }

    private static void tick() {
        if (!active) return;
        FeatureConfigs.CropFever c = config();
        if (c == null || !c.enabled || System.currentTimeMillis() - startedAt > FEVER_MS) {
            stop();
            return;
        }
        if (jackpotTick >= 0) {
            for (Note n : JACKPOT) if (n.tick() == jackpotTick) play(n);
            if (++jackpotTick > 20) jackpotTick = -1;
        }
        // The tune starts once the jackpot has played.
        if (c.backgroundMusic && feverTicks >= 20) {
            int t = (feverTicks - 20) % TUNE_TICKS;
            for (Note n : TUNE) if (n.tick() == t) play(n);
        }
        feverTicks++;
        if (c.coinRain && System.currentTimeMillis() - startedAt < c.coinRainSeconds * 1000L) spawnCoins(c);
    }

    private static void play(Note n) {
        float pitch = (float) Math.pow(2, (n.key() - 12) / 12.0);
        Sky2MAlerts.play(n.sound().value(), pitch, n.volume());
    }

    private static void spawnCoins(FeatureConfigs.CropFever c) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        if (coinItems == null) {
            coinItems = new ArrayList<>();
            for (String texture : COIN_TEXTURES) coinItems.add(Compat.createSkull(texture));
        }
        int count = c.coinMultiplier.amount > 0 ? c.coinMultiplier.amount : Math.max(1, (int) (width * height / (480f * 270f)));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            float speed = height * (0.9f + random.nextFloat() * 0.2f);
            coins.add(new Coin(random.nextFloat() * width, System.currentTimeMillis(), speed, coinItems.get(random.nextInt(coinItems.size()))));
        }
    }

    private static void renderCoins(GuiGraphicsExtractor g) {
        if (coins.isEmpty()) return;
        int height = g.guiHeight();
        long now = System.currentTimeMillis();
        for (Iterator<Coin> it = coins.iterator(); it.hasNext(); ) {
            Coin coin = it.next();
            float y = coin.speed() * ((now - coin.spawned()) / 1000f);
            if (y > height * 1.2f) {
                it.remove();
                continue;
            }
            g.pose().pushMatrix();
            g.pose().translate(coin.x(), y);
            g.pose().scale(3f, 3f);
            g.item(coin.item(), -8, -(int) (height * 0.05));
            g.pose().popMatrix();
        }
    }
}
