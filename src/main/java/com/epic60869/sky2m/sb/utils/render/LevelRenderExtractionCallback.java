// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils.render;

import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/** Fired by Sky2MWorldRender each tick with a gizmo-backed collector. */
@FunctionalInterface
public interface LevelRenderExtractionCallback {
	Event<LevelRenderExtractionCallback> EVENT = EventFactory.createArrayBacked(LevelRenderExtractionCallback.class, callbacks -> collector -> {
		for (LevelRenderExtractionCallback callback : callbacks) callback.onExtract(collector);
	});

	void onExtract(PrimitiveCollector collector);
}
