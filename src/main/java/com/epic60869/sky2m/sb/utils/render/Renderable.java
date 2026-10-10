package com.epic60869.sky2m.sb.utils.render;

import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;

public interface Renderable {
	void extractRendering(PrimitiveCollector collector);
}
