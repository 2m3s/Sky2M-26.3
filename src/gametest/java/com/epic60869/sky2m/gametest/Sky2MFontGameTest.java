package com.epic60869.sky2m.gametest;

import com.epic60869.sky2m.Sky2MConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Misc > Fonts: picking Inter must change how text is drawn. Measures a line of text in Minecraft's font, picks Inter,
 * waits for the font reload, measures again (Inter is narrower) and screenshots the title screen, then goes back to
 * the Default font. Run with `gradlew runClientGameTest`; the screenshot is build/run/clientGameTest/screenshots/30-font-inter.
 */
public class Sky2MFontGameTest implements FabricClientGameTest {
    private static final String SAMPLE = "Sky2M Fonts: The quick brown fox";
    /** Hypixel's stat and status symbols: they must stay as Minecraft (and Hypixel's resource pack) draw them. */
    private static final String SYMBOLS = "❤❈✦❁☣☠⚔✎⫽✯⸕⏣☘";

    @Override
    public void runTest(ClientGameTestContext ctx) {
        int before = ctx.computeOnClient(mc -> mc.font.width(SAMPLE));
        int symbolsBefore = ctx.computeOnClient(mc -> mc.font.width(SYMBOLS));
        pick(ctx, "Inter");
        int inter = ctx.computeOnClient(mc -> mc.font.width(SAMPLE));
        int symbolsInter = ctx.computeOnClient(mc -> mc.font.width(SYMBOLS));
        System.out.println("[Sky2M gametest] symbol width: Default " + symbolsBefore + ", Inter " + symbolsInter);
        if (symbolsInter != symbolsBefore) throw new AssertionError("Inter changed Hypixel's stat symbols (" + symbolsInter + " vs " + symbolsBefore + ")");
        System.out.println("[Sky2M gametest] screenshot 30-font-inter -> " + ctx.takeScreenshot("30-font-inter"));
        pick(ctx, "Default");
        int after = ctx.computeOnClient(mc -> mc.font.width(SAMPLE));
        System.out.println("[Sky2M gametest] font width: Default " + before + ", Inter " + inter + ", Default again " + after);
        if (inter == before) throw new AssertionError("Picking Inter didn't change the font (width " + inter + ")");
        if (after != before) throw new AssertionError("Going back to Default didn't restore Minecraft's font (" + after + " vs " + before + ")");
        // A first start asks about Sky2M Clipping; tests have to end on the title screen.
        ctx.setScreen(net.minecraft.client.gui.screens.TitleScreen::new);
        ctx.waitTicks(5);
    }

    private static void pick(ClientGameTestContext ctx, String font) {
        java.util.concurrent.CompletableFuture<?> reload = ctx.computeOnClient(mc -> {
            Sky2MConfig.current().misc.fonts.font = font;
            return mc.reloadResourcePacks();
        });
        ctx.waitFor(mc -> reload.isDone(), 2400);
        ctx.waitTicks(40);
    }
}
