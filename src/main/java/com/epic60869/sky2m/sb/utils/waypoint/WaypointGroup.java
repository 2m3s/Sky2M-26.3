// Ported from Skyblocker (https://github.com/SkyblockerMod/Skyblocker), licensed under LGPL-3.0.
package com.epic60869.sky2m.sb.utils.waypoint;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.epic60869.sky2m.sb.config.SkyblockerConfigManager;
import com.epic60869.sky2m.sb.config.configs.UIAndVisualsConfig;
import com.epic60869.sky2m.sb.utils.Location;
import com.epic60869.sky2m.sb.utils.render.primitive.PrimitiveCollector;

public class WaypointGroup {
	public static final Waypoint.Type DEFAULT_TYPE = Waypoint.Type.WAYPOINT;
	public static final Codec<WaypointGroup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(WaypointGroup::name),
			Codec.STRING.fieldOf("island").xmap(Location::from, Location::id).forGetter(WaypointGroup::island),
			NamedWaypoint.CODEC.listOf().fieldOf("waypoints").forGetter(WaypointGroup::waypoints),
			Codec.BOOL.lenientOptionalFieldOf("ordered", false).forGetter(WaypointGroup::ordered),
			Codec.BOOL.lenientOptionalFieldOf("renderThroughWalls", true).forGetter(WaypointGroup::renderThroughWalls),
			Waypoint.Type.CODEC.lenientOptionalFieldOf("waypointType", DEFAULT_TYPE).forGetter(WaypointGroup::waypointType)
	).apply(instance, WaypointGroup::new));
	public static final Codec<WaypointGroup> SKYTILS_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(WaypointGroup::name),
			Codec.STRING.fieldOf("island").xmap(Location::from, Location::id).forGetter(WaypointGroup::island),
			NamedWaypoint.SKYTILS_CODEC.listOf().fieldOf("waypoints").forGetter(WaypointGroup::waypoints)
	).apply(instance, WaypointGroup::new));
	public static final Codec<WaypointGroup> COLEWEIGHT_CODEC = NamedWaypoint.COLEWEIGHT_CODEC.listOf().xmap(coleWeightWaypoints -> new WaypointGroup("Coleweight", Location.UNKNOWN, coleWeightWaypoints, true, true, DEFAULT_TYPE), WaypointGroup::waypoints);
	public static final Codec<WaypointGroup> SKYBLOCKER_LEGACY_ORDERED_CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("name").forGetter(WaypointGroup::name),
			Codec.BOOL.fieldOf("enabled").forGetter(group -> !group.waypoints().isEmpty() && group.waypoints().stream().allMatch(Waypoint::isEnabled)),
			NamedWaypoint.SKYBLOCKER_LEGACY_ORDERED_CODEC.listOf().fieldOf("waypoints").forGetter(WaypointGroup::waypoints)
	).apply(instance, (name, enabled, waypoints) -> {
		waypoints.forEach(enabled ? Waypoint::setMissing : Waypoint::setFound);
		return new WaypointGroup(name, Location.UNKNOWN, waypoints, true, true, DEFAULT_TYPE);
	}));

	private final String name;
	private final Location island;
	private final List<NamedWaypoint> waypoints;
	private final boolean ordered;
	private final boolean renderThroughWalls;
	private final Waypoint.Type waypointType;
	private transient int currentIndex = 0;

	public WaypointGroup(String name, Location island) {
		this(name, island, List.of(), false, true, SkyblockerConfigManager.get().uiAndVisuals.waypoints.waypointType);
	}

	public WaypointGroup(String name, Location island, List<NamedWaypoint> waypoints) {
		this(name, island, waypoints, false, true, DEFAULT_TYPE);
	}

	public WaypointGroup(String name, Location island, List<NamedWaypoint> waypoints, boolean ordered, boolean renderThroughWalls, Waypoint.Type waypointType) {
		this.name = name;
		this.island = island;
		// Set ordered first since convertWaypoint depends on it
		this.ordered = ordered;
		this.renderThroughWalls = renderThroughWalls;
		this.waypointType = waypointType;
		this.waypoints = waypoints.stream().map(this::convertWaypoint).collect(Collectors.toList());
	}

	public String name() {
		return name;
	}

	public Location island() {
		return island;
	}

	public List<NamedWaypoint> waypoints() {
		return waypoints;
	}

	public boolean ordered() {
		return ordered;
	}

	public boolean renderThroughWalls() {
		return renderThroughWalls;
	}

	public Waypoint.Type waypointType() {
		return waypointType;
	}

	public int currentIndex() {
		return currentIndex;
	}

	public void setCurrentIndex(int currentIndex) {
		this.currentIndex = currentIndex;
	}

	/**
	 * Resets the current ordered waypoint index on world change.
	 */
	public void resetCurrentIndex() {
		setCurrentIndex(0);
	}

	public WaypointGroup withName(String name) {
		return new WaypointGroup(name, island, waypoints, ordered, renderThroughWalls, waypointType);
	}

	public WaypointGroup withIsland(Location island) {
		return new WaypointGroup(name, island, waypoints, ordered, renderThroughWalls, waypointType);
	}

	public WaypointGroup withOrdered(boolean ordered) {
		return new WaypointGroup(name, island, waypoints, ordered, renderThroughWalls, waypointType);
	}

	public WaypointGroup withRenderThroughWalls(boolean renderThroughWalls) {
		return new WaypointGroup(name, island, waypoints, ordered, renderThroughWalls, waypointType);
	}

	public WaypointGroup withWaypointType(Waypoint.Type waypointType) {
		return new WaypointGroup(name, island, waypoints, ordered, renderThroughWalls, waypointType);
	}

	public WaypointGroup filterWaypoints(Predicate<NamedWaypoint> predicate) {
		return new WaypointGroup(name, island, waypoints.stream().filter(predicate).toList(), ordered, renderThroughWalls, waypointType);
	}

	public WaypointGroup sortWaypoints(Comparator<NamedWaypoint> comparator) {
		return new WaypointGroup(name, island, waypoints.stream().sorted(comparator).toList(), ordered, renderThroughWalls, waypointType);
	}

	/**
	 * Returns a deep copy of this {@link WaypointGroup} with a mutable waypoints list for editing.
	 */
	public WaypointGroup deepCopy() {
		return new WaypointGroup(name, island, waypoints.stream().map(NamedWaypoint::copy).collect(Collectors.toList()), ordered, renderThroughWalls, waypointType);
	}

	public NamedWaypoint createWaypoint(BlockPos pos) {
		String name = "Waypoint " + (waypoints.size() + 1);
		return (ordered ? new OrderedNamedWaypoint(pos, name, new float[]{0f, 1f, 0f}) : new NamedWaypoint(pos, name, new float[]{0f, 1f, 0f})).withThroughWalls(renderThroughWalls).withTypeSupplier(this::waypointType);
	}

	/**
	 * Converts the given waypoint to the correct type based on whether this group is ordered or not.
	 */
	public NamedWaypoint convertWaypoint(NamedWaypoint waypoint) {
		if (ordered) {
			return (waypoint instanceof OrderedNamedWaypoint ? waypoint : new OrderedNamedWaypoint(waypoint)).withThroughWalls(renderThroughWalls()).withTypeSupplier(this::waypointType);
		} else {
			return (waypoint instanceof OrderedNamedWaypoint ? new NamedWaypoint(waypoint) : waypoint).withThroughWalls(renderThroughWalls()).withTypeSupplier(this::waypointType);
		}
	}

	public void tick() {
		if (Minecraft.getInstance().player == null || !ordered || waypoints.isEmpty()) return;
		for (int i = 0; i < waypoints.size(); i++) {
			NamedWaypoint waypoint = waypoints.get(i);
			UIAndVisualsConfig.Waypoints config = SkyblockerConfigManager.get().uiAndVisuals.waypoints;
			int nextIndex = (currentIndex + 1) % waypoints.size();
			int previousIndex = (currentIndex - 1 + waypoints.size()) % waypoints.size();
			boolean notBackwards = config.allowGoingBackwards || i == nextIndex;
			boolean notSkipping = config.allowSkippingWaypoints || i == nextIndex || i == previousIndex;
			if (notBackwards && notSkipping && waypoint.pos.closerToCenterThan(Minecraft.getInstance().player.position(), config.waypointActivationRadius)) {
				currentIndex = i;
			}
		}
		int previousIndex = (currentIndex - 1 + waypoints.size()) % waypoints.size();
		int nextIndex = (currentIndex + 1) % waypoints.size();
		for (int i = 0; i < waypoints.size(); i++) {
			NamedWaypoint waypoint = waypoints.get(i);
			if (waypoint instanceof OrderedNamedWaypoint orderedNamedWaypoint) {
				orderedNamedWaypoint.index = i;
				if (i == previousIndex) {
					orderedNamedWaypoint.relativeIndex = OrderedNamedWaypoint.RelativeIndex.PREVIOUS;
				} else if (i == nextIndex) {
					orderedNamedWaypoint.relativeIndex = OrderedNamedWaypoint.RelativeIndex.NEXT;
				} else if (i == currentIndex) {
					orderedNamedWaypoint.relativeIndex = OrderedNamedWaypoint.RelativeIndex.CURRENT;
				} else {
					orderedNamedWaypoint.relativeIndex = OrderedNamedWaypoint.RelativeIndex.NONE;
				}
			}
		}
	}

	public void extractRendering(PrimitiveCollector collector) {
		for (NamedWaypoint waypoint : waypoints) {
			if (waypoint.shouldRender()) {
				waypoint.extractRendering(collector);
			}
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof WaypointGroup other)) return false;
		return ordered == other.ordered && renderThroughWalls == other.renderThroughWalls
			&& java.util.Objects.equals(name, other.name) && java.util.Objects.equals(island, other.island)
			&& java.util.Objects.equals(waypoints, other.waypoints) && waypointType == other.waypointType;
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(name, island, waypoints, ordered, renderThroughWalls, waypointType);
	}

	@Override
	public String toString() {
		return "WaypointGroup[name=" + name + ", island=" + island + ", waypoints=" + waypoints + ", ordered=" + ordered
			+ ", renderThroughWalls=" + renderThroughWalls + ", waypointType=" + waypointType + "]";
	}
}
