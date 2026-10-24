package com.epic60869.sky2m.features.qol;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Edits the plain text of a chat component while keeping each remaining character's style (colours, click and hover
 * events): used by the chat rules' replace action and the player chat format.
 */
public final class TextEdit {
    /** Replaces the characters {@code start} (inclusive) to {@code end} (exclusive) with {@code replacement}. */
    public record Edit(int start, int end, String replacement) {}

    private TextEdit() {}

    /** The component's text with no formatting codes, exactly as {@link #apply} indexes it. */
    public static String plain(Component message) {
        StringBuilder sb = new StringBuilder();
        message.visit(text -> {
            sb.append(text);
            return Optional.empty();
        });
        return sb.toString();
    }

    /** {@code message} with the (non-overlapping, any order) edits applied; the same component if there are none. */
    public static Component apply(Component message, List<Edit> edits) {
        if (edits.isEmpty()) return message;
        List<Edit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(a.start(), b.start()));
        List<Style> styles = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        message.visit((style, s) -> {
            for (int i = 0; i < s.length(); i++) styles.add(style);
            text.append(s);
            return Optional.empty();
        }, Style.EMPTY);

        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style runStyle = null;
        int editIndex = 0;
        int i = 0;
        while (i < text.length()) {
            if (editIndex < sorted.size() && sorted.get(editIndex).start() <= i) {
                Edit edit = sorted.get(editIndex++);
                if (edit.start() < i) continue; // overlapping, skip
                Style editStyle = styles.get(i);
                if (!edit.replacement().isEmpty()) {
                    if (runStyle != null && !run.isEmpty()) out.append(Component.literal(run.toString()).withStyle(runStyle));
                    run.setLength(0);
                    runStyle = null;
                    out.append(Component.literal(edit.replacement()).withStyle(editStyle));
                }
                i = Math.max(i, Math.min(text.length(), edit.end()));
                continue;
            }
            Style style = styles.get(i);
            if (runStyle != null && !style.equals(runStyle)) {
                out.append(Component.literal(run.toString()).withStyle(runStyle));
                run.setLength(0);
            }
            runStyle = style;
            run.append(text.charAt(i));
            i++;
        }
        if (runStyle != null && !run.isEmpty()) out.append(Component.literal(run.toString()).withStyle(runStyle));
        return out;
    }
}
