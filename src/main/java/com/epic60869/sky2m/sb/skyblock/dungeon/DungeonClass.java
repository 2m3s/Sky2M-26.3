package com.epic60869.sky2m.sb.skyblock.dungeon;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.minecraft.util.ARGB;

import com.epic60869.sky2m.sb.skyblock.entity.MobGlow;
import com.epic60869.sky2m.sb.skyblock.tabhud.util.Ico;
import com.epic60869.sky2m.sb.utils.FlexibleItemStack;

public enum DungeonClass {
	UNKNOWN("Unknown", MobGlow.NO_GLOW, Ico.BARRIER),
	HEALER("Healer", 0x820DD1, Ico.POTION),
	MAGE("Mage", 0x36C6E3, Ico.B_ROD),
	BERSERK("Berserk", 0xFA5B16, Ico.IRON_SWORD),
	ARCHER("Archer", 0xED240E, Ico.BOW),
	TANK("Tank", 0x138717, Ico.L_CHESTPLATE);

	private static final Map<String, DungeonClass> CLASSES_BY_DISPLAY_NAME = Arrays.stream(values())
			.collect(Collectors.toUnmodifiableMap(DungeonClass::displayName, Function.identity()));

	private final String name;
	private final int color;
	private final int glowColor;
	private final FlexibleItemStack icon;

	DungeonClass(String name, int color, FlexibleItemStack icon) {
		this.name = name;
		this.color = ARGB.opaque(color);
		this.glowColor = color;
		this.icon = icon;
	}

	public String displayName() {
		return this.name;
	}

	public String apiName() {
		return this.name().toLowerCase(Locale.ENGLISH);
	}

	/// {@return the color of the class in ARGB format}
	public int color() {
		return this.color;
	}

	/// {@return the color of the class in RGB format}
	public int glowColor() {
		return this.glowColor;
	}

	public FlexibleItemStack icon() {
		return icon;
	}

	/// {@return the class whose display name matches {@code name} or {@link #UNKNOWN}}
	public static DungeonClass from(String name) {
		return CLASSES_BY_DISPLAY_NAME.getOrDefault(name, UNKNOWN);
	}
}
