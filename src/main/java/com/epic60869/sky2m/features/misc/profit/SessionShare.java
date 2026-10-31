package com.epic60869.sky2m.features.misc.profit;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.epic60869.sky2m.chat.Sky2MGlobalChat;
import com.epic60869.sky2m.social.Sky2MLogin;
import com.epic60869.sky2m.price.Sky2MPriceTooltip;
import com.epic60869.sky2m.features.FeatureConfigs;
import com.epic60869.sky2m.features.sbc.Sbc;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uploads finished /s2 session records to the Sky2M server when "Share Sessions to Website" is on (default off) and the
 * player linked their account with /s2 link. One entry per activity per day; the server keeps them private until the
 * player makes them public on shadowisabot.com/link. Sending the same day again just updates it.
 */
public final class SessionShare {
    /** Entries per packet; the server's packet limit is 10 KB. */
    private static final int BATCH = 5;
    /** How many of the newest entries are sent after logging in (the server allows 12 packets a minute). */
    private static final int RECENT_ENTRIES = 40;
    private static final long MIN_MS = 60_000L;
    private static final int MAX_DROPS = 8;

    private static boolean wasEnabled;
    private static int ticks;

    private SessionShare() {}

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (++ticks % 40 != 0 || mc.player == null) return;
            boolean on = enabled();
            if (on && !wasEnabled) onEnabled();
            wasEnabled = on;
        });
    }

    private static FeatureConfigs.SessionTrackerSettings config() {
        Sky2MConfig c = Sky2MConfig.current();
        return c == null ? null : c.misc.profitTrackers.session;
    }

    public static boolean enabled() {
        FeatureConfigs.SessionTrackerSettings c = config();
        return c != null && c.shareToWebsite;
    }

    /** The first time sharing is switched on: say exactly what happens, once, then send what is already recorded. */
    private static void onEnabled() {
        FeatureConfigs.SessionTrackerSettings c = config();
        if (c != null && !c.shareNoticeShown) {
            c.shareNoticeShown = true;
            Sbc.say(Component.literal("Session sharing is on. Finished tracker sessions (activity, date, time, profit and your top drops) are uploaded to the Sky2M server once your account is linked (/s2 link). They stay private until you make them public at shadowisabot.com/link. Switch it off in the Session Tracker settings; /s2 link delete removes everything stored.")
                .withStyle(ChatFormatting.YELLOW));
        }
        uploadRecent();
    }

    // ------------------------------------------------------------------------------------------------ uploading

    /** After login (and when sharing is switched on): today's and the newest past days' records. */
    public static void uploadRecent() {
        if (!enabled()) return;
        List<SessionTracker.Session> sessions = SessionTracker.recentForShare(10);
        List<JsonObject> entries = new ArrayList<>();
        for (SessionTracker.Session s : sessions) entries.addAll(entries(s, s == SessionTracker.session(-1)));
        if (entries.size() > RECENT_ENTRIES) entries = entries.subList(0, RECENT_ENTRIES);
        send(entries);
    }

    /** One session's records: when a day ends, when you leave a server, or as the game closes. */
    static void upload(SessionTracker.Session session, boolean live) {
        if (!enabled() || session == null) return;
        send(entries(session, live));
    }

    private static void send(List<JsonObject> entries) {
        if (entries.isEmpty()) return;
        for (int i = 0; i < entries.size(); i += BATCH) {
            JsonArray array = new JsonArray();
            for (JsonObject entry : entries.subList(i, Math.min(entries.size(), i + BATCH))) array.add(entry);
            JsonObject packet = Sbc.packet("trackerSessions");
            packet.add("sessions", array);
            if (Sky2MLogin.loggedIn()) Sky2MGlobalChat.send(packet);
            else if (Sky2MGlobalChat.currentConnection() != null) Sky2MLogin.whenLoggedIn(() -> Sky2MGlobalChat.send(packet));
        }
    }

    // ------------------------------------------------------------------------------------------------ entries

    private static List<JsonObject> entries(SessionTracker.Session s, boolean live) {
        List<JsonObject> out = new ArrayList<>();
        for (ProfitTracker.Preset preset : SessionTracker.ACTIVITIES) {
            JsonObject entry = entry(s, preset, live);
            if (entry != null) out.add(entry);
        }
        return out;
    }

    /** One activity of one day, or null when there is too little to show. */
    private static JsonObject entry(SessionTracker.Session s, ProfitTracker.Preset preset, boolean live) {
        ProfitTracker.Stats st = s.get(preset);
        if (st == null || !s.has(preset) || st.activeMs < MIN_MS || s.day <= 0) return null;
        long started = s.firstActive(preset) > 0 ? s.firstActive(preset) : s.started;
        long ended = Math.max(live ? System.currentTimeMillis() : s.ended, started + st.activeMs);
        FeatureConfigs.SessionTrackerSettings c = config();
        FeatureConfigs.ProfitPriceSource source = c == null || c.priceSource == null ? FeatureConfigs.ProfitPriceSource.INSTANT_SELL : c.priceSource;

        List<Map.Entry<String, Long>> items = new ArrayList<>(st.items.entrySet());
        List<JsonObject> drops = new ArrayList<>();
        for (Map.Entry<String, Long> e : items) {
            if (e.getValue() == null || e.getValue() <= 0) continue;
            double unit = ProfitItemPrices.price(e.getKey(), source);
            JsonObject d = new JsonObject();
            d.addProperty("id", e.getKey());
            d.addProperty("name", ProfitTrackerHud.name(e.getKey()));
            d.addProperty("count", e.getValue());
            d.addProperty("value", Math.round(Math.max(0, unit) * e.getValue()));
            drops.add(d);
        }
        drops.sort((a, b) -> Long.compare(b.get("value").getAsLong(), a.get("value").getAsLong()));
        JsonArray dropArray = new JsonArray();
        for (JsonObject d : drops.subList(0, Math.min(MAX_DROPS, drops.size()))) dropArray.add(d);

        JsonObject o = new JsonObject();
        o.addProperty("id", s.day + "-" + preset.name());
        o.addProperty("activity", SessionTracker.name(preset));
        o.addProperty("startedAt", started);
        o.addProperty("endedAt", ended);
        o.addProperty("durationMs", Math.min(st.activeMs, 24L * 3_600_000L));
        o.addProperty("profit", Math.round(SessionTracker.profit(preset, st, null)));
        o.addProperty("coins", Math.round(st.coins));
        o.addProperty("actions", st.actions);
        o.add("drops", dropArray);
        return o;
    }
}
