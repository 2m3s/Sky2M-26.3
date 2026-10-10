package com.epic60869.sky2m.features.core;

import com.epic60869.sky2m.sb.utils.render.primitive.GizmoPrimitiveCollector;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-world boxes, lines and text. Renderers are called once per frame, just before the level
 * extractor hands its per-frame gizmos to the level renderer (see Sky2MLevelExtractorMixin), so
 * lines from the camera and boxes on moving entities follow the camera smoothly.
 */
public final class Sky2MWorldRender {
    private static final List<Consumer<PrimitiveCollector>> RENDERERS = new CopyOnWriteArrayList<>();
    private static final PrimitiveCollector COLLECTOR = new GizmoPrimitiveCollector();

    private Sky2MWorldRender() {}

    public static void init() {}

    public static void register(Consumer<PrimitiveCollector> renderer) {
        RENDERERS.add(renderer);
    }

    /** Called with the per-frame gizmo collector active. */
    public static void renderFrame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || RENDERERS.isEmpty() || !Sky2MLocation.skyblockOnlyAllows()) return;
        for (Consumer<PrimitiveCollector> renderer : RENDERERS) {
            try {
                renderer.accept(COLLECTOR);
            } catch (Exception e) {
                com.epic60869.sky2m.features.sbc.SbcCrashReports.report(e, "world renderer");
            }
        }
    }
}
