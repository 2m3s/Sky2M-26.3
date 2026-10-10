package com.epic60869.sky2m.features.misc;

import com.epic60869.sky2m.Sky2MConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** /s2 calendar: every upcoming SkyBlock event with a countdown, and whether you get a reminder before it. */
public final class EventCalendarScreen extends Screen {
    private int scroll;

    public EventCalendarScreen() {
        super(Component.literal("SkyBlock Calendar"));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Reminder settings"), b -> Sky2MConfig.openGui())
            .bounds(width / 2 - 104, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 + 4, height - 26, 100, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int w = Math.min(380, width - 20);
        int left = (width - w) / 2;
        int top = 34;
        int bottom = height - 34;
        g.centeredText(font, "SkyBlock Calendar", width / 2, 8, 0xFFFFD35A);
        String sub = EventCalendar.skyblockDate + (EventCalendar.mayor.isEmpty() ? "" : "  ·  Mayor " + EventCalendar.mayor)
            + (EventCalendar.minister.isEmpty() ? "" : "  ·  Minister " + EventCalendar.minister);
        g.centeredText(font, sub, width / 2, 20, 0xFF9AA5B8);
        g.fill(left, top, left + w, bottom, 0xC0121722);
        g.outline(left, top, w, bottom - top, 0xFF3B465B);
        List<EventCalendar.Event> events = EventCalendar.events;
        if (events.isEmpty()) {
            g.centeredText(font, EventCalendar.loadedAt == 0 ? "Loading..." : "No events (is the Sky2M server down?)", width / 2, top + 20, 0xFFAAAAAA);
            return;
        }
        long now = System.currentTimeMillis();
        var config = Sky2MConfig.current() == null ? null : Sky2MConfig.current().misc.eventCalendar;
        int rows = (bottom - top - 4) / 12;
        scroll = Math.max(0, Math.min(scroll, events.size() - rows));
        int y = top + 4;
        for (int i = scroll; i < Math.min(events.size(), scroll + rows); i++) {
            EventCalendar.Event e = events.get(i);
            if (e.end() > 0 && e.end() < now) continue;
            String crops = e.crops().isEmpty() ? "" : " (" + String.join(", ", e.crops()) + ")";
            g.text(font, e.name() + crops, left + 6, y, 0xFF000000 | EventCalendar.colour(e.id()), false);
            String when = EventCalendar.countdown(e, now);
            g.text(font, when, left + w - 6 - font.width(when), y, now >= e.start() ? 0xFF55FF55 : 0xFFF3F6FF, false);
            if (config != null && config.reminders && EventCalendar.reminderMinutes(config, e) >= 0) {
                g.text(font, "•", left + w - 12 - font.width(when), y, 0xFFFFD35A, false);
            }
            y += 12;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY) * 3;
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
