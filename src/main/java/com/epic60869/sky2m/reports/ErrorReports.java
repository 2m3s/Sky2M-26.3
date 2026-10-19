package com.epic60869.sky2m.reports;

import net.minecraft.client.Minecraft;

import java.util.function.BiConsumer;

/**
 * Bug reports filed by the Error Reporter ({@link com.epic60869.sky2m.features.core.Sky2MErrors}): the same submit and
 * game info attachment as /s2 bugreport, so they reach staff as an ordinary bug report thread.
 */
public final class ErrorReports {
    private ErrorReports() {}

    /** Files a bug report; {@code done} gets (sent, problem) on the game thread. */
    public static void submitBug(String title, String description, BiConsumer<Boolean, String> done) {
        String d = description.strip();
        if (d.length() > Reports.DESCRIPTION_MAX) d = d.substring(0, Reports.DESCRIPTION_MAX);
        d = d + ReportAttachment.build(Reports.DESCRIPTION_MAX - d.length());
        Reports.submit(Reports.Type.BUG, title, d).whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
            if (error != null || result == null) done.accept(false, "");
            else done.accept(result.ok(), result.message());
        }));
    }
}
