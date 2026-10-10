// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.utils.render.title;

import com.epic60869.sky2m.features.core.Sky2MAlerts;
import net.minecraft.network.chat.Component;

public final class TitleContainer {
	private TitleContainer() {}

	public static void addTitleAndPlaySound(Title title, int ticks) {
		Sky2MAlerts.title(title.getText(), Component.empty());
	}
}
