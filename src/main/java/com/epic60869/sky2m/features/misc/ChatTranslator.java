package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import com.epic60869.sky2m.custom.util.Compat;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Click to translate, like SkyHanni's Translator: player chat messages can be clicked to translate them into your
 * language, and /s2 translate &lt;language&gt; &lt;message&gt; translates your own message and copies it, ready to
 * paste. Uses Google Translate's free endpoint (the same as SkyHanni); only what you click or type is sent.
 */
public final class ChatTranslator {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** "[289] ✭ [MVP++] ☘ Name: message", "Party > Name: message", "Guild > [VIP] Name [Rank]: message", "From Name: message". */
    private static final Pattern PLAYER_CHAT = Pattern.compile("^(?:\\[\\d+\\] .*?|(?:Party|Guild|Officer|Co-op) > .*?|(?:From|To) .*?)\\b(?<name>\\w{1,16})(?: \\[[^]]+])?: (?<message>.+)$");

    private ChatTranslator() {}

    private static FeatureConfigs.Translator config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.chat.translator;
    }

    public static void init() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((component, overlay) -> {
            FeatureConfigs.Translator c = config();
            if (overlay || c == null || !c.enabled || !Compat.isOnSkyblock()) return component;
            String text = ChatFormatting.stripFormatting(component.getString());
            if (text == null) return component;
            Matcher m = PLAYER_CHAT.matcher(text.trim());
            if (!m.matches() || m.group("message").isBlank()) return component;
            String message = m.group("message").trim();
            // A clicked command can be at most 256 characters.
            if (message.length() > 200) message = message.substring(0, 200);
            String clicked = message;
            // Each part keeps its own click (an item link, a [Party Finder] button); the rest translates.
            MutableComponent out = component.copy();
            out.withStyle(style -> style.getClickEvent() != null ? style : style
                .withClickEvent(new ClickEvent.RunCommand("/s2 translatechat " + clicked))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to translate into " + c.language).withStyle(ChatFormatting.GRAY))));
            return out;
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            for (String root : Compat.COMMAND_ROOTS) {
                dispatcher.register(ClientCommands.literal(root)
                    .then(ClientCommands.literal("translatechat").then(ClientCommands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            FeatureConfigs.Translator c = config();
                            String language = c == null ? "en" : c.language.code;
                            translate(StringArgumentType.getString(ctx, "message"), language, false);
                            return 1;
                        })))
                    .then(ClientCommands.literal("translate").then(ClientCommands.argument("language", StringArgumentType.word())
                        .then(ClientCommands.argument("message", StringArgumentType.greedyString()).executes(ctx -> {
                            translate(StringArgumentType.getString(ctx, "message"), languageCode(StringArgumentType.getString(ctx, "language")), true);
                            return 1;
                        })))));
            }
        });
    }

    /** "es", "spanish" or "Spanish" -> "es". */
    private static String languageCode(String typed) {
        for (FeatureConfigs.Translator.Language l : FeatureConfigs.Translator.Language.values()) {
            if (l.code.equalsIgnoreCase(typed) || l.label.equalsIgnoreCase(typed)) return l.code;
        }
        return typed.toLowerCase(Locale.ROOT);
    }

    private static void translate(String text, String target, boolean copy) {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + target + "&dt=t&q="
            + URLEncoder.encode(text, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", "Sky2M").GET().build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null || response.statusCode() != 200) {
                say(Component.literal("Couldn't translate that right now.").withStyle(ChatFormatting.RED));
                return;
            }
            try {
                JsonArray root = JsonParser.parseString(response.body()).getAsJsonArray();
                StringBuilder out = new StringBuilder();
                for (JsonElement part : root.get(0).getAsJsonArray()) out.append(part.getAsJsonArray().get(0).getAsString());
                String from = root.size() > 2 && !root.get(2).isJsonNull() ? root.get(2).getAsString() : "?";
                String translated = out.toString();
                if (copy) Minecraft.getInstance().keyboardHandler.setClipboard(translated);
                say(Component.literal("[" + from + " → " + target + "] ").withStyle(ChatFormatting.DARK_AQUA)
                    .append(Component.literal(translated).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(copy ? " (copied)" : "").withStyle(ChatFormatting.GRAY)));
            } catch (Exception e) {
                say(Component.literal("Couldn't read the translation.").withStyle(ChatFormatting.RED));
            }
        }));
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.gui.hud.getChat().addClientSystemMessage(Compat.PREFIX.get().append(text));
    }
}
