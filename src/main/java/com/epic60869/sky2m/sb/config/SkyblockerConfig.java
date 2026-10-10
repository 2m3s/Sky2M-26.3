// Sky2M stand-in for a Skyblocker class used by the ported dungeon/experiment code (Skyblocker is LGPL-3.0).
package com.epic60869.sky2m.sb.config;

import com.epic60869.sky2m.sb.config.configs.DungeonsConfig;
import com.epic60869.sky2m.sb.config.configs.HelperConfig;
import com.epic60869.sky2m.sb.config.configs.UIAndVisualsConfig;

public class SkyblockerConfig {
	public DungeonsConfig dungeons = new DungeonsConfig();
	public HelperConfig helpers = new HelperConfig();
	public UIAndVisualsConfig uiAndVisuals = new UIAndVisualsConfig();
}
