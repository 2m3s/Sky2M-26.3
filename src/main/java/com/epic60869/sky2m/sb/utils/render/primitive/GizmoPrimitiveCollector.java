package com.epic60869.sky2m.sb.utils.render.primitive;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Sky2M's implementation of Skyblocker's PrimitiveCollector, drawn with Minecraft's gizmos
 * instead of Skyblocker's custom render pipelines. Must be called inside a gizmo collection.
 */
public final class GizmoPrimitiveCollector implements PrimitiveCollector {
	private static final double BEAM_TOP = 319;

	private static int argb(float[] rgb, float alpha) {
		return ARGB.colorFromFloat(alpha, rgb[0], rgb[1], rgb[2]);
	}

	private static void throughWalls(GizmoProperties properties, boolean throughWalls) {
		if (throughWalls) properties.setAlwaysOnTop();
	}

	@Override
	public void submitFilledBoxWithBeaconBeam(BlockPos pos, float[] colourComponents, float alpha, boolean throughWalls) {
		submitFilledBoxWithBeaconBeam(new AABB(pos), colourComponents, alpha, throughWalls);
	}

	@Override
	public void submitFilledBoxWithBeaconBeam(AABB box, float[] colourComponents, float alpha, boolean throughWalls) {
		submitFilledBox(box, colourComponents, alpha, throughWalls);
		Vec3 centre = box.getCenter();
		AABB beam = new AABB(centre.x - 0.2, box.maxY, centre.z - 0.2, centre.x + 0.2, Math.max(BEAM_TOP, box.maxY + 1), centre.z + 0.2);
		throughWalls(Gizmos.cuboid(beam, GizmoStyle.fill(argb(colourComponents, alpha * 0.6f))), throughWalls);
	}

	@Override
	public void submitFilledBox(BlockPos pos, float[] colourComponents, float alpha, boolean throughWalls) {
		submitFilledBox(new AABB(pos), colourComponents, alpha, throughWalls);
	}

	@Override
	public void submitFilledBox(Vec3 pos, Vec3 dimensions, float[] colourComponents, float alpha, boolean throughWalls) {
		submitFilledBox(new AABB(pos, pos.add(dimensions)), colourComponents, alpha, throughWalls);
	}

	@Override
	public void submitFilledBox(AABB box, float[] colourComponents, float alpha, boolean throughWalls) {
		throughWalls(Gizmos.cuboid(box, GizmoStyle.fill(argb(colourComponents, alpha))), throughWalls);
	}

	@Override
	public void submitOutlinedBox(BlockPos pos, float[] colourComponents, float lineWidth, boolean throughWalls) {
		submitOutlinedBox(new AABB(pos), colourComponents, 1f, lineWidth, throughWalls);
	}

	@Override
	public void submitOutlinedBox(AABB box, float[] colourComponents, float lineWidth, boolean throughWalls) {
		submitOutlinedBox(box, colourComponents, 1f, lineWidth, throughWalls);
	}

	@Override
	public void submitOutlinedBox(AABB box, float[] colourComponents, float alpha, float lineWidth, boolean throughWalls) {
		throughWalls(Gizmos.cuboid(box, GizmoStyle.stroke(argb(colourComponents, alpha), lineWidth)), throughWalls);
	}

	@Override
	public void submitLinesFromPoints(Vec3[] points, float[] colourComponents, float alpha, float lineWidth, boolean throughWalls) {
		int colour = argb(colourComponents, alpha);
		for (int i = 0; i + 1 < points.length; i++) {
			throughWalls(Gizmos.line(points[i], points[i + 1], colour, lineWidth), throughWalls);
		}
	}

	@Override
	public void submitLineFromCursor(Vec3 point, float[] colourComponents, float alpha, float lineWidth) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		// Start from the render camera (interpolated every frame), not the player's last tick position,
		// so the line doesn't jump around while moving.
		Camera camera = mc.gameRenderer.mainCamera();
		Vector3fc forward = camera.forwardVector();
		Vec3 start = camera.position().add(forward.x() * 0.5, forward.y() * 0.5, forward.z() * 0.5);
		Gizmos.line(start, point, argb(colourComponents, alpha), lineWidth).setAlwaysOnTop();
	}

	@Override
	public void submitQuad(Vec3[] points, float[] colourComponents, float alpha, boolean throughWalls) {
		if (points.length < 4) return;
		throughWalls(Gizmos.rect(points[0], points[1], points[2], points[3], GizmoStyle.fill(argb(colourComponents, alpha))), throughWalls);
	}

	@Override
	public void submitTexturedQuad(Vec3 pos, float width, float height, float textureWidth, float textureHeight, Vec3 renderOffset, Identifier texture, float[] shaderColour, float alpha, boolean throughWalls) {
		// Not supported by gizmos; draw a flat marker instead.
		submitFilledBox(new AABB(pos.subtract(width / 2, 0, width / 2), pos.add(width / 2, 0.02, width / 2)), shaderColour, alpha, throughWalls);
	}

	@Override
	public void submitBlockHologram(BlockPos pos, BlockState state, float alpha) {
		Gizmos.cuboid(new AABB(pos), GizmoStyle.strokeAndFill(ARGB.color(alpha, 0xFFFFFF), 1f, ARGB.color(alpha * 0.25f, 0xFFFFFF)));
	}

	@Override
	public void submitText(Component text, Vec3 pos, boolean throughWalls) {
		submitText(text, pos, 1f, 0f, throughWalls);
	}

	@Override
	public void submitText(Component text, Vec3 pos, float scale, boolean throughWalls) {
		submitText(text, pos, scale, 0f, throughWalls);
	}

	@Override
	public void submitText(Component text, Vec3 pos, float scale, float yOffset, boolean throughWalls) {
		TextColor colour = text.getStyle().getColor();
		if (colour == null) {
			for (Component sibling : text.getSiblings()) {
				if (sibling.getStyle().getColor() != null) {
					colour = sibling.getStyle().getColor();
					break;
				}
			}
		}
		int rgb = colour == null ? 0xFFFFFF : colour.getValue();
		TextGizmo.Style style = TextGizmo.Style.forColorAndCentered(ARGB.opaque(rgb)).withScale(TextGizmo.Style.DEFAULT_SCALE * scale);
		throughWalls(Gizmos.billboardText(text.getString(), pos.add(0, yOffset * 0.025 * scale, 0), style), throughWalls);
	}

	@Override
	public void submitCylinder(Vec3 centre, float radius, float height, int segments, int colour) {
		Gizmos.circle(centre, radius, GizmoStyle.stroke(colour));
		Gizmos.circle(centre.add(0, height, 0), radius, GizmoStyle.stroke(colour));
	}

	@Override
	public void submitSphere(Vec3 centre, float radius, int segments, int rings, int colour) {
		Gizmos.circle(centre, radius, GizmoStyle.stroke(colour));
	}

	@Override
	public void submitFilledCircle(Vec3 centre, float radius, int segments, int colour) {
		Gizmos.circle(centre, radius, GizmoStyle.fill(colour));
	}

	@Override
	public void submitOutlinedCircle(Vec3 centre, float radius, float thickness, int segments, int colour) {
		Gizmos.circle(centre, radius, GizmoStyle.stroke(colour, thickness));
	}
}
