package com.epic60869.sky2m.features.misc.playasmob;

import com.epic60869.sky2m.config.Sky2MConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The 3D models Play as Mob draws: the ones Sky2M staff give out on the website, downloaded by {@link ServerModels}.
 * Reads Blockbench {@code .bbmodel} files (cubes and meshes, bones, embedded textures), glTF {@code .gltf}/{@code .glb}
 * and VRM {@code .vrm} ({@link GltfModels}) and {@code .stl} ({@link GeoModels}); the {@code .obj} and Bedrock
 * {@code .geo.json} readers are kept for files the server may accept later. A file is read once and kept while it is used.
 * <p>
 * Drawn in place of a player model inside {@code LivingEntityRenderer.submit} ({@code Sky2MPlayAsMobRendererMixin}),
 * so the player's position, body turn, size, light and hurt flash all apply. Bones named head, left/right arm and
 * left/right leg follow the player's head and swing as they walk; glTF and VRM models also bend at knee and elbow bones
 * (shin, forearm...), are smoothly skinned (each vertex can follow up to four bones) and walk like a person rather than
 * a Minecraft player. The items you hold are drawn in the model's hands: at a hand bone (right_hand, Mixamo's
 * RightHand, VRM's J_Bip_R_Hand...) or, without one, at the end of the arm, turning with it.
 */
public final class CustomModels {

    public static final String PREFIX = "model:";

    static final Logger LOGGER = LoggerFactory.getLogger("sky2m-playasmob");
    static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    private static final int MAX_FACES = 200_000;
    private static final float PLAYER_HEIGHT = 1.8f;

    static final int ANIM_NONE = 0, ANIM_HEAD = 1, ANIM_RIGHT_ARM = 2, ANIM_LEFT_ARM = 3, ANIM_RIGHT_LEG = 4,
            ANIM_LEFT_LEG = 5, ANIM_RIGHT_ELBOW = 6, ANIM_LEFT_ELBOW = 7, ANIM_RIGHT_KNEE = 8, ANIM_LEFT_KNEE = 9;

    /** One bone: rotates its faces and child bones around {@code pivot}. Index 0 is the root. */
    record Bone(int parent, float px, float py, float pz, float rx, float ry, float rz, int anim) {
    }

    /**
     * A quad (triangles repeat their last corner): 4 positions, 4 uvs, a normal, its bone. A skinned face also has, per
     * corner, up to 4 bones and their weights ({@code skinBones}/{@code skinWeights}, 16 each); otherwise they are null.
     */
    record Face(int bone, float[] pos, float[] uv, float nx, float ny, float nz, int[] skinBones, float[] skinWeights) {
    }

    public static final class Model {
        final List<Bone> bones;
        final Identifier[] textures;
        final List<List<Face>> facesByTexture;
        /** Rest-pose bounds, in model units. */
        final float minX, minY, minZ, maxX, maxY, maxZ;
        /** Blocks per model unit when not fitted: 1/16 for Blockbench pixels, 1 for OBJ. */
        final float unit;
        final boolean centre;
        /** How far the format's own front is turned from a player's (glTF faces +Z, a player -Z), in radians. */
        final float baseTurn;
        /** Walks like a person (glTF, VRM) rather than with a Minecraft player's big stiff swing (Blockbench mobs). */
        final boolean humanWalk;
        /** Where held items go: the right and left hand in model space and the bone each moves with (-1: no hand). */
        final float[] rightHand, leftHand;
        final int rightHandBone, leftHandBone;

        Model(List<Bone> bones, Identifier[] textures, List<List<Face>> facesByTexture, float[] bounds, float unit,
              boolean centre, float baseTurn, boolean humanWalk, float[] rightHand, int rightHandBone, float[] leftHand,
              int leftHandBone) {
            this.bones = bones;
            this.textures = textures;
            this.facesByTexture = facesByTexture;
            this.minX = bounds[0];
            this.minY = bounds[1];
            this.minZ = bounds[2];
            this.maxX = bounds[3];
            this.maxY = bounds[4];
            this.maxZ = bounds[5];
            this.unit = unit;
            this.centre = centre;
            this.baseTurn = baseTurn;
            this.humanWalk = humanWalk;
            this.rightHand = rightHand;
            this.rightHandBone = rightHandBone;
            this.leftHand = leftHand;
            this.leftHandBone = leftHandBone;
        }
    }

    private record Loaded(Model model, String error) {
    }

    /** Loaded models by file; each server model version is its own file, so a file never changes once loaded. */
    private static final Map<Path, Loaded> CACHE = new HashMap<>();
    private static int textureCounter;

    private CustomModels() {
    }

    /** Why a model file could not be loaded, after it was tried, or null. */
    public static String error(Path file) {
        Loaded l = CACHE.get(file);
        return l == null ? null : l.error();
    }

    /** The loaded model for a downloaded file, or null when it can't be read. Render thread only. */
    public static Model get(Path file) {
        Loaded known = CACHE.get(file);
        if (known != null) return known.model();
        if (CACHE.size() >= 32) {
            for (Loaded l : CACHE.values()) if (l.model() != null) release(l.model());
            CACHE.clear();
        }
        Loaded loaded;
        String name = file.getFileName().toString();
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("the file is over 128 MB");
            String lower = name.toLowerCase(Locale.ROOT);
            Model model;
            if (lower.endsWith(".obj")) model = loadObj(file);
            else if (lower.endsWith(".gltf") || lower.endsWith(".glb") || lower.endsWith(".vrm")) model = GltfModels.load(file);
            else if (lower.endsWith(".geo.json")) model = GeoModels.loadBedrock(file);
            else if (lower.endsWith(".stl")) model = GeoModels.loadStl(file);
            else model = loadBbModel(file);
            loaded = new Loaded(model, null);
        } catch (Exception e) {
            LOGGER.error("[Play as Mob] could not load model {}", name, e);
            loaded = new Loaded(null, e.getMessage() == null ? e.toString() : e.getMessage());
        }
        CACHE.put(file, loaded);
        return loaded.model();
    }

    private static void release(Model model) {
        for (Identifier id : model.textures) {
            if (id != null) Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws {@code model} for {@code state}. {@code pose} is the player model's space inside
     * {@code LivingEntityRenderer.submit}: turned with the body, sized, flipped (y down) and lifted 1.501 blocks.
     */
    public static void draw(Model model, ServerModelPlacement placement, AvatarRenderState state, PoseStack pose,
                            SubmitNodeCollector collector, int light, int overlay) {
        Sky2MConfig config = Sky2MConfig.current();
        if (config == null) return;
        Sky2MConfig.PlayAsMob cfg = config.misc.playAsMob;
        boolean fit = placement.fit();
        float height = model.maxY - model.minY;
        float s = placement.scale() * (fit && height > 1.0e-4f ? PLAYER_HEIGHT / height : model.unit);
        float turn = (float) Math.toRadians(Math.round(placement.turn() / 90f) * 90f) + model.baseTurn;

        pose.pushPose();
        // Back to y up with the feet at the origin (undoing the player model's flip and lift); front is -Z.
        pose.translate(0f, 1.501f, 0f);
        pose.scale(-1f, -1f, 1f);
        pose.rotate(Axis.YP.rotation(turn));
        pose.scale(s, s, s);
        if (fit) {
            pose.translate(model.centre ? -(model.minX + model.maxX) / 2f : 0f, -model.minY,
                    model.centre ? -(model.minZ + model.maxZ) / 2f : 0f);
        }

        if (cfg.animate && model.humanWalk) {
            // A person rises as their legs pass each other and dips at the widest stride: twice per step.
            float walkSpeed = Math.min(1f, state.walkAnimationSpeed);
            float bob = BOB * walkSpeed * (1f - Math.abs((float) Math.cos(state.walkAnimationPos * 0.6662f)));
            pose.translate(0f, bob / s, 0f);
        }

        Matrix4f[] boneMats = boneMatrices(model, state, cfg.animate, turn);
        for (int t = 0; t < model.textures.length; t++) {
            List<Face> faces = model.facesByTexture.get(t);
            if (faces.isEmpty() || model.textures[t] == null) continue;
            collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(model.textures[t]), (p, vc) -> {
                Vector3f v = new Vector3f();
                Vector3f n = new Vector3f();
                Vector3f moved = new Vector3f();
                Vector3f sum = new Vector3f();
                Vector3f nsum = new Vector3f();
                for (Face f : faces) {
                    if (f.skinBones() != null) {
                        // Linear blend skinning: each corner is moved by its bones, by weight, so joints bend smoothly.
                        for (int i = 0; i < 4; i++) {
                            sum.zero();
                            nsum.zero();
                            for (int k = 0; k < 4; k++) {
                                float w = f.skinWeights()[i * 4 + k];
                                if (w <= 0f) continue;
                                Matrix4f bm = boneMats[f.skinBones()[i * 4 + k]];
                                bm.transformPosition(moved.set(f.pos()[i * 3], f.pos()[i * 3 + 1], f.pos()[i * 3 + 2]));
                                sum.fma(w, moved);
                                bm.transformDirection(moved.set(f.nx(), f.ny(), f.nz()));
                                nsum.fma(w, moved);
                            }
                            if (nsum.lengthSquared() > 1.0e-12f) nsum.normalize();
                            vc.addVertex(p, sum.x, sum.y, sum.z).setColor(0xFFFFFFFF).setUv(f.uv()[i * 2], f.uv()[i * 2 + 1])
                                    .setOverlay(overlay).setLight(light).setNormal(p, nsum.x, nsum.y, nsum.z);
                        }
                        continue;
                    }
                    Matrix4f m = boneMats[f.bone()];
                    m.transformDirection(n.set(f.nx(), f.ny(), f.nz()));
                    for (int i = 0; i < 4; i++) {
                        m.transformPosition(v.set(f.pos()[i * 3], f.pos()[i * 3 + 1], f.pos()[i * 3 + 2]));
                        vc.addVertex(p, v.x, v.y, v.z).setColor(0xFFFFFFFF).setUv(f.uv()[i * 2], f.uv()[i * 2 + 1])
                                .setOverlay(overlay).setLight(light).setNormal(p, n.x, n.y, n.z);
                    }
                }
            });
        }
        heldItem(model, state.rightHandItemState, false, boneMats, s, turn, pose, collector, light, state.outlineColor);
        heldItem(model, state.leftHandItemState, true, boneMats, s, turn, pose, collector, light, state.outlineColor);
        pose.popPose();
    }

    /** Draws just this model's textured arm in the first-person hand pose used by Minecraft's item renderer. */
    public static boolean drawFirstPersonArm(Model model, boolean left, PoseStack pose,
                                             SubmitNodeCollector collector, int light) {
        int armAnim = left ? ANIM_LEFT_ARM : ANIM_RIGHT_ARM;
        int elbowAnim = left ? ANIM_LEFT_ELBOW : ANIM_RIGHT_ELBOW;
        AvatarRenderState rest = new AvatarRenderState();
        Matrix4f[] bones = boneMatrices(model, rest, false, model.baseTurn);
        Matrix4f front = new Matrix4f().rotateY(model.baseTurn);
        List<Face> armFaces = new ArrayList<>();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        Vector3f point = new Vector3f();
        for (List<Face> faces : model.facesByTexture) for (Face face : faces) {
            if (face.bone() < 0 || face.bone() >= model.bones.size()) continue;
            int anim = model.bones.get(face.bone()).anim();
            if (anim != armAnim && anim != elbowAnim) continue;
            armFaces.add(face);
            Matrix4f transform = new Matrix4f(front).mul(bones[face.bone()]);
            for (int i = 0; i < 4; i++) {
                transform.transformPosition(point.set(face.pos()[i * 3], face.pos()[i * 3 + 1], face.pos()[i * 3 + 2]));
                minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
                minZ = Math.min(minZ, point.z); maxZ = Math.max(maxZ, point.z);
            }
        }
        if (armFaces.isEmpty() || maxX - minX < 1.0e-4f || maxY - minY < 1.0e-4f || maxZ - minZ < 1.0e-4f) return false;

        float xRange = maxX - minX, yRange = maxY - minY, zRange = maxZ - minZ;
        Matrix4f[] transforms = new Matrix4f[bones.length];
        for (int i = 0; i < bones.length; i++) transforms[i] = new Matrix4f(front).mul(bones[i]);
        List<List<float[]>> vertices = new ArrayList<>(model.textures.length);
        for (int i = 0; i < model.textures.length; i++) vertices.add(new ArrayList<>());
        List<List<float[]>> normals = new ArrayList<>(model.textures.length);
        for (int i = 0; i < model.textures.length; i++) normals.add(new ArrayList<>());
        for (int t = 0; t < model.facesByTexture.size(); t++) {
            for (Face face : model.facesByTexture.get(t)) {
                int anim = face.bone() >= 0 && face.bone() < model.bones.size() ? model.bones.get(face.bone()).anim() : ANIM_NONE;
                if (anim != armAnim && anim != elbowAnim) continue;
                Matrix4f transform = transforms[face.bone()];
                float[][] mapped = new float[4][3];
                for (int i = 0; i < 4; i++) {
                    transform.transformPosition(point.set(face.pos()[i * 3], face.pos()[i * 3 + 1], face.pos()[i * 3 + 2]));
                    float across = (point.x - minX) / xRange;
                    // The model's frame (y up, its right at +x) into the arm's (y down, its right at -x): x and y both flip,
                    // so the arm keeps its handedness (a thumb stays on the thumb side) rather than being mirrored.
                    mapped[i][0] = left ? 8f - across * 4f : -4f - across * 4f;
                    mapped[i][1] = (maxY - point.y) / yRange * 12f;
                    mapped[i][2] = -2f + (point.z - minZ) / zRange * 4f;
                }
                Vector3f n = new Vector3f(mapped[1][0] - mapped[0][0], mapped[1][1] - mapped[0][1], mapped[1][2] - mapped[0][2])
                        .cross(mapped[2][0] - mapped[0][0], mapped[2][1] - mapped[0][1], mapped[2][2] - mapped[0][2]);
                if (n.lengthSquared() > 1.0e-12f) n.normalize(); else n.set(0f, 1f, 0f);
                vertices.get(t).add(new float[]{mapped[0][0], mapped[0][1], mapped[0][2], mapped[1][0], mapped[1][1], mapped[1][2],
                        mapped[2][0], mapped[2][1], mapped[2][2], mapped[3][0], mapped[3][1], mapped[3][2],
                        face.uv()[0], face.uv()[1], face.uv()[2], face.uv()[3], face.uv()[4], face.uv()[5], face.uv()[6], face.uv()[7]});
                normals.get(t).add(new float[]{n.x, n.y, n.z});
            }
        }
        pose.pushPose();
        pose.scale(1f / 16f, 1f / 16f, 1f / 16f);
        for (int t = 0; t < model.textures.length; t++) {
            if (vertices.get(t).isEmpty() || model.textures[t] == null) continue;
            int texture = t;
            collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(model.textures[t]), (p, vc) -> {
                List<float[]> faces = vertices.get(texture), faceNormals = normals.get(texture);
                for (int f = 0; f < faces.size(); f++) {
                    float[] v = faces.get(f), n = faceNormals.get(f);
                    for (int i = 0; i < 4; i++) {
                        vc.addVertex(p, v[i * 3], v[i * 3 + 1], v[i * 3 + 2]).setColor(0xFFFFFFFF)
                                .setUv(v[12 + i * 2], v[13 + i * 2]).setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(light).setNormal(p, n[0], n[1], n[2]);
                    }
                }
            });
        }
        pose.popPose();
        return true;
    }

    /**
     * Draws a held item in the model's hand, as the player model's held item layer does: {@code pose} is the model's
     * space, {@code s} its scale and {@code turn} its turn, so undoing those at the hand leaves the player model's own
     * frame (y down, front -Z), turned the way the hand's bone is.
     */
    private static void heldItem(Model model, ItemStackRenderState item, boolean left, Matrix4f[] boneMats, float s,
                                 float turn, PoseStack pose, SubmitNodeCollector collector, int light, int outline) {
        float[] hand = left ? model.leftHand : model.rightHand;
        int bone = left ? model.leftHandBone : model.rightHandBone;
        if (item == null || item.isEmpty() || hand == null || bone < 0 || bone >= boneMats.length) return;
        Matrix4f boneMat = boneMats[bone];
        Vector3f at = boneMat.transformPosition(new Vector3f(hand[0], hand[1], hand[2]));
        // The bone's turn from rest, moved from the model's frame (y up, turned) into the player model's (y down).
        Matrix4f turnOfBone = new Matrix4f().set3x3(boneMat).normalize3x3();
        Matrix4f handTurn = new Matrix4f().scale(-1f, -1f, 1f).rotateY(turn).mul(turnOfBone).rotateY(-turn).scale(-1f, -1f, 1f);
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        pose.scale(1f / s, 1f / s, 1f / s);
        pose.mulPose(new Matrix4f().rotateY(-turn));
        pose.mulPose(handTurn);
        // The player model's held item layer, from the fist rather than the shoulder.
        pose.mulPose(new Matrix4f().rotateX((float) Math.toRadians(-90)).rotateY((float) Math.PI));
        pose.translate((left ? -1f : 1f) / 16f, 2f / 16f, 0f);
        item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, outline);
        pose.popPose();
    }

    /** How a model is placed: set per model on the website (Model Scale, Turn and Fit to Player Height). */
    public record ServerModelPlacement(float scale, float turn, boolean fit) {
    }

    /** A person's walk, in radians (a Minecraft player swings its legs about 80 degrees and its arms about 57). */
    private static final float HIP_SWING = 0.5f, KNEE_BEND = 0.95f, KNEE_REST = 0.08f, SHOULDER_SWING = 0.35f,
            ELBOW_BEND = 0.3f, ELBOW_REST = 0.12f;
    /** How far a person rises between strides, in blocks. */
    private static final float BOB = 0.035f;
    /** How far an arm holding an item comes forward, in radians. */
    private static final float HELD_ITEM_RAISE = 0.31f;

    /** Each bone's transform from model space, with the head turned and the limbs swung when animating. */
    private static Matrix4f[] boneMatrices(Model model, AvatarRenderState state, boolean animate, float turn) {
        Matrix4f[] out = new Matrix4f[model.bones.size()];
        float walkPos = state.walkAnimationPos;
        float walkSpeed = Math.min(1f, state.walkAnimationSpeed);
        float swing = (float) Math.cos(walkPos * 0.6662f);
        float swingOpp = (float) Math.cos(walkPos * 0.6662f + Math.PI);
        // Positive while the right leg swings forward (the left one is planted), negative for the left.
        float stride = (float) Math.sin(walkPos * 0.6662f);
        for (int i = 0; i < out.length; i++) {
            Bone b = model.bones.get(i);
            Matrix4f m = b.parent() < 0 ? new Matrix4f() : new Matrix4f(out[b.parent()]);
            m.translate(b.px(), b.py(), b.pz());
            if (animate && b.anim() != ANIM_NONE) {
                // Angles in the player's frame (y up, front -Z), turned into the model's frame when Model Turn is set.
                Matrix4f anim = new Matrix4f();
                if (b.anim() == ANIM_HEAD) {
                    anim.rotateY((float) Math.toRadians(-state.yRot)).rotateX((float) Math.toRadians(-state.xRot));
                } else if (model.humanWalk) {
                    // A positive X turn swings a limb forward. Knees only bend backward and most while the leg swings
                    // through; elbows stay a little bent and bend more as the arm comes forward.
                    switch (b.anim()) {
                        case ANIM_RIGHT_ARM -> anim.rotateX(-swingOpp * SHOULDER_SWING * walkSpeed);
                        case ANIM_LEFT_ARM -> anim.rotateX(-swing * SHOULDER_SWING * walkSpeed);
                        case ANIM_RIGHT_LEG -> anim.rotateX(-swing * HIP_SWING * walkSpeed);
                        case ANIM_LEFT_LEG -> anim.rotateX(-swingOpp * HIP_SWING * walkSpeed);
                        case ANIM_RIGHT_KNEE -> anim.rotateX(-(KNEE_REST + KNEE_BEND * Math.max(0f, stride)) * walkSpeed);
                        case ANIM_LEFT_KNEE -> anim.rotateX(-(KNEE_REST + KNEE_BEND * Math.max(0f, -stride)) * walkSpeed);
                        case ANIM_RIGHT_ELBOW -> anim.rotateX((ELBOW_REST + ELBOW_BEND * Math.max(0f, swing)) * walkSpeed);
                        case ANIM_LEFT_ELBOW -> anim.rotateX((ELBOW_REST + ELBOW_BEND * Math.max(0f, -swing)) * walkSpeed);
                        default -> {
                        }
                    }
                } else {
                    switch (b.anim()) {
                        case ANIM_RIGHT_ARM -> anim.rotateX(-swingOpp * walkSpeed);
                        case ANIM_LEFT_ARM -> anim.rotateX(-swing * walkSpeed);
                        case ANIM_RIGHT_LEG -> anim.rotateX(-swing * 1.4f * walkSpeed);
                        case ANIM_LEFT_LEG -> anim.rotateX(-swingOpp * 1.4f * walkSpeed);
                        default -> {
                        }
                    }
                }
                if (model.humanWalk && state instanceof ArmedEntityRenderState holding
                        && (b.anim() == ANIM_RIGHT_ARM || b.anim() == ANIM_LEFT_ARM)) {
                    // An arm holding something comes up a little, as the player model's does (about 18 degrees).
                    ItemStackRenderState heldItem = b.anim() == ANIM_RIGHT_ARM ? holding.rightHandItemState : holding.leftHandItemState;
                    if (heldItem != null && !heldItem.isEmpty()) anim.rotateX(HELD_ITEM_RAISE);
                }
                // 26.3 describes the swing as currentSwing (which hand) plus swingAnimation (0 to 1).
                HumanoidArm swingArm = state instanceof ArmedEntityRenderState sw && sw.currentSwing != null && sw.mainArm != null
                        ? (sw.currentSwing.hand() == net.minecraft.world.InteractionHand.MAIN_HAND ? sw.mainArm : sw.mainArm.getOpposite())
                        : null;
                if (model.humanWalk && swingArm != null && state instanceof ArmedEntityRenderState armed && armed.swingAnimation > 0f
                        && ((swingArm == HumanoidArm.RIGHT && b.anim() == ANIM_RIGHT_ARM)
                        || (swingArm == HumanoidArm.LEFT && b.anim() == ANIM_LEFT_ARM))) {
                    // The player model's attack swing: the arm snaps forward and back (a positive turn is forward here)
                    // and crosses a little toward the middle.
                    float progress = 1f - (float) Math.pow(1f - armed.swingAnimation, 4);
                    float strike = (float) Math.sin(progress * Math.PI);
                    anim.rotateX(strike * 1.2f).rotateY((swingArm == HumanoidArm.RIGHT ? 1f : -1f) * strike * 0.18f);
                }
                m.mul(new Matrix4f().rotateY(-turn).mul(anim).rotateY(turn));
            }
            m.rotateZ(b.rz()).rotateY(b.ry()).rotateX(b.rx());
            m.translate(-b.px(), -b.py(), -b.pz());
            out[i] = m;
        }
        return out;
    }

    // ---------------------------------------------------------------- loading: shared

    /** Collects bones and faces while a file is read. */
    static final class Builder {
        final List<Bone> bones = new ArrayList<>();
        final List<List<Face>> faces = new ArrayList<>();
        int faceCount;
        private float[] rightHand, leftHand;
        private int rightHandBone = -1, leftHandBone = -1;

        /** A hand bone's position (model space) and the bone it moves with; the first one found for each side wins. */
        void hand(boolean left, int bone, float[] at) {
            if (left && leftHand == null) {
                leftHand = at;
                leftHandBone = bone;
            } else if (!left && rightHand == null) {
                rightHand = at;
                rightHandBone = bone;
            }
        }

        /**
         * Without a hand bone: the end of the arm. The lowest part of the forearm's (or else the arm's) faces, as the arms
         * hang at rest, which is where the hand is.
         */
        private void guessHand(boolean left) {
            int elbow = left ? ANIM_LEFT_ELBOW : ANIM_RIGHT_ELBOW, arm = left ? ANIM_LEFT_ARM : ANIM_RIGHT_ARM;
            int found = -1;
            for (int i = 0; i < bones.size() && found < 0; i++) if (bones.get(i).anim() == elbow) found = i;
            for (int i = 0; i < bones.size() && found < 0; i++) if (bones.get(i).anim() == arm) found = i;
            if (found < 0) return;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (List<Face> list : faces) for (Face f : list) if (f.bone() == found) for (int i = 0; i < 4; i++) {
                lo = Math.min(lo, f.pos()[i * 3 + 1]);
                hi = Math.max(hi, f.pos()[i * 3 + 1]);
            }
            if (lo > hi) return;
            // Include the palm/wrist area, rather than anchoring an item to just the bottom-most few vertices.
            float limit = lo + (hi - lo) * 0.30f;
            double x = 0, y = 0, z = 0;
            int n = 0;
            for (List<Face> list : faces) for (Face f : list) if (f.bone() == found) for (int i = 0; i < 4; i++) {
                if (f.pos()[i * 3 + 1] > limit) continue;
                x += f.pos()[i * 3];
                y += f.pos()[i * 3 + 1];
                z += f.pos()[i * 3 + 2];
                n++;
            }
            if (n > 0) hand(left, found, new float[]{(float) (x / n), (float) (y / n), (float) (z / n)});
        }

        Builder(int textures) {
            for (int i = 0; i < textures; i++) faces.add(new ArrayList<>());
            bones.add(new Bone(-1, 0, 0, 0, 0, 0, 0, ANIM_NONE));
        }

        int bone(int parent, float[] pivot, float[] rotDeg, int anim) {
            bones.add(new Bone(parent, pivot[0], pivot[1], pivot[2], (float) Math.toRadians(rotDeg[0]),
                    (float) Math.toRadians(rotDeg[1]), (float) Math.toRadians(rotDeg[2]), anim));
            return bones.size() - 1;
        }

        /** A face of 3 or 4 corners (positions in model space, uvs 0-1). */
        void face(int bone, int texture, float[][] corners, float[][] uvs) throws IOException {
            face(bone, texture, corners, uvs, null, null);
        }

        /**
         * A face whose corners can each follow up to 4 bones: {@code skinBones[c]} and {@code skinWeights[c]} per
         * corner, weights adding up to 1. {@code bone} is the one that matters most (used for the model's bounds).
         */
        void face(int bone, int texture, float[][] corners, float[][] uvs, int[][] skinBones, float[][] skinWeights)
                throws IOException {
            if (texture < 0 || texture >= faces.size()) return;
            if (++faceCount > MAX_FACES) throw new IOException("the model has over " + MAX_FACES + " faces");
            float[] pos = new float[12];
            float[] uv = new float[8];
            for (int i = 0; i < 4; i++) {
                int c = Math.min(i, corners.length - 1);
                pos[i * 3] = corners[c][0];
                pos[i * 3 + 1] = corners[c][1];
                pos[i * 3 + 2] = corners[c][2];
                uv[i * 2] = uvs[c][0];
                uv[i * 2 + 1] = uvs[c][1];
            }
            Vector3f a = new Vector3f(corners[1][0] - corners[0][0], corners[1][1] - corners[0][1], corners[1][2] - corners[0][2]);
            Vector3f b = new Vector3f(corners[2][0] - corners[0][0], corners[2][1] - corners[0][1], corners[2][2] - corners[0][2]);
            Vector3f n = a.cross(b);
            if (n.lengthSquared() > 1.0e-12f) n.normalize();
            else n.set(0, 1, 0);
            int[] sb = null;
            float[] sw = null;
            if (skinBones != null) {
                sb = new int[16];
                sw = new float[16];
                for (int i = 0; i < 4; i++) {
                    int c = Math.min(i, corners.length - 1);
                    for (int k = 0; k < 4 && k < skinBones[c].length; k++) {
                        sb[i * 4 + k] = skinBones[c][k];
                        sw[i * 4 + k] = skinWeights[c][k];
                    }
                }
            }
            faces.get(texture).add(new Face(bone, pos, uv, n.x, n.y, n.z, sb, sw));
        }

        Model build(Identifier[] textures, float unit, boolean centre) throws IOException {
            return build(textures, unit, centre, 0f);
        }

        Model build(Identifier[] textures, float unit, boolean centre, float baseTurn) throws IOException {
            return build(textures, unit, centre, baseTurn, false);
        }

        Model build(Identifier[] textures, float unit, boolean centre, float baseTurn, boolean humanWalk) throws IOException {
            if (faceCount == 0) throw new IOException("the model has no textured faces");
            if (rightHand == null) guessHand(false);
            if (leftHand == null) guessHand(true);
            return new Model(List.copyOf(bones), textures, faces, bounds(), unit, centre, baseTurn, humanWalk,
                    rightHand, rightHandBone, leftHand, leftHandBone);
        }

        /** Rest-pose bounds, with every bone's own rotation applied. */
        private float[] bounds() {
            Matrix4f[] mats = new Matrix4f[bones.size()];
            for (int i = 0; i < mats.length; i++) {
                Bone bn = bones.get(i);
                Matrix4f m = bn.parent() < 0 ? new Matrix4f() : new Matrix4f(mats[bn.parent()]);
                m.translate(bn.px(), bn.py(), bn.pz()).rotateZ(bn.rz()).rotateY(bn.ry()).rotateX(bn.rx())
                        .translate(-bn.px(), -bn.py(), -bn.pz());
                mats[i] = m;
            }
            float[] r = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            Vector3f v = new Vector3f();
            for (List<Face> list : faces) {
                for (Face f : list) {
                    for (int i = 0; i < 4; i++) {
                        mats[f.bone()].transformPosition(v.set(f.pos()[i * 3], f.pos()[i * 3 + 1], f.pos()[i * 3 + 2]));
                        r[0] = Math.min(r[0], v.x);
                        r[1] = Math.min(r[1], v.y);
                        r[2] = Math.min(r[2], v.z);
                        r[3] = Math.max(r[3], v.x);
                        r[4] = Math.max(r[4], v.y);
                        r[5] = Math.max(r[5], v.z);
                    }
                }
            }
            return r;
        }
    }

    static Identifier registerTexture(NativeImage image, String label) {
        Identifier id = Identifier.fromNamespaceAndPath("sky2m", "playasmob/model_" + (textureCounter++));
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "sky2m model " + label, image));
        return id;
    }

    static Identifier whiteTexture() {
        return colourTexture(0xFFFFFFFF);
    }

    /** A 1x1 texture of one ARGB colour, for models (or materials) with no image. */
    static Identifier colourTexture(int argb) {
        NativeImage image = new NativeImage(1, 1, false);
        image.setPixel(0, 0, argb);
        return registerTexture(image, "colour");
    }

    static Identifier textureFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return textureBytes(Files.readAllBytes(file), file.getFileName().toString());
        } catch (Exception e) {
            LOGGER.warn("[Play as Mob] could not read texture {}: {}", file, e.toString());
            return null;
        }
    }

    /** A texture from image bytes: PNG directly; JPEG and the other formats Java reads are converted to PNG first. */
    static Identifier textureBytes(byte[] bytes, String label) throws IOException {
        try {
            return registerTexture(NativeImage.read(bytes), label);
        } catch (IOException notPng) {
            try {
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null) throw notPng;
                java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", png);
                return registerTexture(NativeImage.read(png.toByteArray()), label);
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("unreadable image (" + t + ")");
            }
        }
    }

    /** The first image next to {@code file} named {@code stem} (.png, .jpg or .jpeg), or null. */
    static Identifier textureNear(Path file, String stem) {
        Path dir = file.getParent();
        if (dir == null) return null;
        for (String ext : new String[]{".png", ".jpg", ".jpeg", ".PNG", ".JPG", ".JPEG"}) {
            Identifier id = textureFile(dir.resolve(stem + ext));
            if (id != null) return id;
        }
        return null;
    }

    // ---------------------------------------------------------------- loading: Blockbench

    private static Model loadBbModel(Path file) throws IOException {
        return loadBbModel(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(), file);
    }

    /** A Blockbench model already parsed ({@link GeoModels} builds one from a Bedrock file). */
    static Model loadBbModel(JsonObject root, Path file) throws IOException {
        JsonObject resolution = obj(root, "resolution");
        float resW = resolution == null ? 16 : num(resolution, "width", 16);
        float resH = resolution == null ? 16 : num(resolution, "height", 16);
        JsonObject meta = obj(root, "meta");
        boolean modelBoxUv = meta != null && meta.has("box_uv") && meta.get("box_uv").getAsBoolean();

        // Textures: embedded PNGs (data URIs), else the saved path, else a file of that name next to the model.
        JsonArray texArr = arr(root, "textures");
        int texCount = Math.max(1, texArr == null ? 0 : texArr.size());
        Identifier[] textures = new Identifier[texCount];
        float[][] texSize = new float[texCount][];
        for (int i = 0; texArr != null && i < texArr.size(); i++) {
            JsonObject t = texArr.get(i).getAsJsonObject();
            textures[i] = bbTexture(t, file.getParent());
            texSize[i] = new float[]{num(t, "uv_width", resW), num(t, "uv_height", resH)};
        }
        for (int i = 0; i < texCount; i++) {
            if (textures[i] == null) textures[i] = whiteTexture();
            if (texSize[i] == null) texSize[i] = new float[]{resW, resH};
        }

        Builder builder = new Builder(texCount);
        Map<String, JsonObject> elements = new HashMap<>();
        JsonArray elArr = arr(root, "elements");
        if (elArr != null) {
            for (JsonElement e : elArr) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("uuid")) elements.put(o.get("uuid").getAsString(), o);
            }
        }
        // Blockbench 5 keeps groups in their own list, referenced from the outliner by uuid.
        Map<String, JsonObject> groups = new HashMap<>();
        JsonArray grArr = arr(root, "groups");
        if (grArr != null) {
            for (JsonElement e : grArr) {
                if (e.isJsonObject() && e.getAsJsonObject().has("uuid")) groups.put(e.getAsJsonObject().get("uuid").getAsString(), e.getAsJsonObject());
            }
        }

        Set<String> placed = new HashSet<>();
        JsonArray outliner = arr(root, "outliner");
        if (outliner != null) {
            for (JsonElement child : outliner) {
                bbNode(child, 0, new HashSet<>(), elements, groups, placed, builder, texSize, modelBoxUv);
            }
        }
        if (elArr != null) {
            for (JsonElement e : elArr) {
                JsonObject o = e.getAsJsonObject();
                String uuid = o.has("uuid") ? o.get("uuid").getAsString() : null;
                if (uuid == null || !placed.contains(uuid)) bbElement(o, 0, builder, texSize, modelBoxUv);
            }
        }
        boolean humanRig = hasBone(builder, ANIM_RIGHT_ARM) && hasBone(builder, ANIM_LEFT_ARM)
                && hasBone(builder, ANIM_RIGHT_LEG) && hasBone(builder, ANIM_LEFT_LEG);
        float baseTurn = meta == null ? 0f : (float) Math.toRadians(num(meta, "sky2m_base_turn", 0f));
        return builder.build(textures, 1f / 16f, false, baseTurn, humanRig);
    }

    private static boolean hasBone(Builder builder, int animation) {
        return builder.bones.stream().anyMatch(bone -> bone.anim() == animation);
    }

    private static Identifier bbTexture(JsonObject t, Path dir) {
        String source = str(t, "source");
        if (source != null && source.startsWith("data:")) {
            int comma = source.indexOf(',');
            try {
                byte[] png = Base64.getDecoder().decode(source.substring(comma + 1));
                return textureBytes(png, str(t, "name"));
            } catch (Exception e) {
                LOGGER.warn("[Play as Mob] could not read an embedded texture: {}", e.toString());
            }
        }
        // A "path" is a file on the computer the model was made on: never read (models come from the server).
        String name = str(t, "name");
        if (name != null && !name.isBlank() && dir != null) {
            // Blockbench exports commonly keep JPEGs next to the model and store only their basename here.
            // Also accept an explicit relative path, while never following paths outside the model directory.
            Path relative = Path.of(name);
            Path near = dir.resolve(relative).normalize();
            if (near.startsWith(dir) && Files.isRegularFile(near)) return textureFile(near);
            if (!relative.getFileName().toString().contains(".")) {
                for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
                    near = dir.resolve(name + ext).normalize();
                    if (near.startsWith(dir) && Files.isRegularFile(near)) return textureFile(near);
                }
            }
        }
        return null;
    }

    /** One outliner entry: an element uuid, or a group (bone) with children. */
    private static void bbNode(JsonElement node, int parentBone, Set<Integer> animatedAbove, Map<String, JsonObject> elements,
                               Map<String, JsonObject> groups, Set<String> placed, Builder builder, float[][] texSize,
                               boolean modelBoxUv) throws IOException {
        if (node.isJsonPrimitive()) {
            String uuid = node.getAsString();
            JsonObject el = elements.get(uuid);
            if (el != null && placed.add(uuid)) bbElement(el, parentBone, builder, texSize, modelBoxUv);
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject g = node.getAsJsonObject();
        JsonObject info = g.has("uuid") && groups.containsKey(g.get("uuid").getAsString()) ? groups.get(g.get("uuid").getAsString()) : g;
        if (info.has("visibility") && !info.get("visibility").getAsBoolean()) return;
        int anim = animFor(str(info, "name"));
        if (animatedAbove.contains(anim)) anim = ANIM_NONE;
        int bone = builder.bone(parentBone, vec(info, "origin"), vec(info, "rotation"), anim);
        int hand = handSide(str(info, "name"));
        if (hand != 0) builder.hand(hand == 2, bone, vec(info, "origin"));
        Set<Integer> below = animatedAbove;
        if (anim != ANIM_NONE) {
            below = new HashSet<>(animatedAbove);
            below.add(anim);
        }
        JsonArray children = arr(g, "children");
        if (children == null) return;
        for (JsonElement child : children) {
            bbNode(child, bone, below, elements, groups, placed, builder, texSize, modelBoxUv);
        }
    }

    /** Which body part a bone name is: head, left/right arm or left/right leg. */
    /** 1 for a right hand bone ("right_hand", "hand_r", "mixamorig:RightHand", "J_Bip_R_Hand"), 2 for a left one, else 0. */
    static int handSide(String name) {
        if (name == null) return 0;
        String n = name.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_').replace('.', '_');
        if (!n.contains("hand") || n.contains("handle")) return 0;
        boolean right = n.contains("right") || n.startsWith("r_") || n.endsWith("_r") || n.contains("_r_");
        boolean left = n.contains("left") || n.startsWith("l_") || n.endsWith("_l") || n.contains("_l_");
        return right == left ? 0 : right ? 1 : 2;
    }

    static int animFor(String name) {
        if (name == null) return ANIM_NONE;
        String n = name.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_').replace('.', '_');
        if (n.contains("head") && !n.contains("forehead")) return ANIM_HEAD;
        // "right_arm", "r_arm", "arm_r", "J_Bip_R_UpperArm", "mixamorig:RightUpLeg", "Thigh.L"
        boolean right = n.contains("right") || n.startsWith("r_") || n.endsWith("_r") || n.contains("_r_");
        boolean left = n.contains("left") || n.startsWith("l_") || n.endsWith("_l") || n.contains("_l_");
        if (right == left) return ANIM_NONE;
        boolean arm = n.contains("arm") && !n.contains("armor") && !n.contains("armour") && !n.contains("armature");
        // "right_forearm", "J_Bip_R_LowerArm", "mixamorig:LeftForeArm", "shin_l", "J_Bip_L_LowerLeg", "calf.R"
        if (n.contains("forearm") || n.contains("elbow") || (arm && n.contains("lower"))) return right ? ANIM_RIGHT_ELBOW : ANIM_LEFT_ELBOW;
        if (n.contains("shin") || n.contains("calf") || n.contains("knee") || (n.contains("leg") && n.contains("lower"))) {
            return right ? ANIM_RIGHT_KNEE : ANIM_LEFT_KNEE;
        }
        if (arm) return right ? ANIM_RIGHT_ARM : ANIM_LEFT_ARM;
        if (n.contains("leg") || n.contains("thigh")) return right ? ANIM_RIGHT_LEG : ANIM_LEFT_LEG;
        return ANIM_NONE;
    }

    private static final String[] FACE_NAMES = {"north", "south", "east", "west", "up", "down"};

    private static void bbElement(JsonObject el, int bone, Builder builder, float[][] texSize, boolean modelBoxUv) throws IOException {
        if (el.has("visibility") && !el.get("visibility").getAsBoolean()) return;
        String type = str(el, "type");
        Matrix4f rot = new Matrix4f();
        float[] origin = vec(el, "origin");
        float[] r = vec(el, "rotation");
        rot.translate(origin[0], origin[1], origin[2])
                .rotateZ((float) Math.toRadians(r[2])).rotateY((float) Math.toRadians(r[1])).rotateX((float) Math.toRadians(r[0]));
        if ("mesh".equals(type)) {
            bbMesh(el, bone, rot, builder, texSize);
            return;
        }
        if (type != null && !"cube".equals(type)) return;
        rot.translate(-origin[0], -origin[1], -origin[2]);

        float[] from = vec(el, "from");
        float[] to = vec(el, "to");
        float inflate = num(el, "inflate", 0);
        float x0 = from[0] - inflate, y0 = from[1] - inflate, z0 = from[2] - inflate;
        float x1 = to[0] + inflate, y1 = to[1] + inflate, z1 = to[2] + inflate;
        // Corners of each face as seen from outside: top-left, top-right, bottom-right, bottom-left.
        float[][][] corners = {
                {{x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}, {x1, y0, z0}}, // north
                {{x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}, {x0, y0, z1}}, // south
                {{x1, y1, z1}, {x1, y1, z0}, {x1, y0, z0}, {x1, y0, z1}}, // east
                {{x0, y1, z0}, {x0, y1, z1}, {x0, y0, z1}, {x0, y0, z0}}, // west
                {{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, // up
                {{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}, // down
        };
        boolean boxUv = el.has("box_uv") ? el.get("box_uv").getAsBoolean() : modelBoxUv;
        float[] boxOffset = vec(el, "uv_offset");
        boolean mirror = el.has("mirror_uv") && el.get("mirror_uv").getAsBoolean();
        float w = Math.abs(to[0] - from[0]), h = Math.abs(to[1] - from[1]), d = Math.abs(to[2] - from[2]);

        JsonObject faces = obj(el, "faces");
        if (faces == null) return;
        Vector3f v = new Vector3f();
        for (int f = 0; f < 6; f++) {
            JsonObject face = obj(faces, FACE_NAMES[f]);
            if (face == null) continue;
            JsonElement tex = face.get("texture");
            if (tex == null || tex.isJsonNull() || (tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isBoolean())) continue;
            int texture = tex.getAsInt();
            if (texture < 0 || texture >= texSize.length) continue;
            float[] uv = face.has("uv") ? vec4(face.getAsJsonArray("uv")) : null;
            if (uv == null && boxUv) uv = boxUv(f, boxOffset[0], boxOffset[1], w, h, d, mirror);
            if (uv == null) continue;
            float[][] c = new float[4][];
            for (int i = 0; i < 4; i++) {
                rot.transformPosition(v.set(corners[f][i][0], corners[f][i][1], corners[f][i][2]));
                c[i] = new float[]{v.x, v.y, v.z};
            }
            float tw = texSize[texture][0], th = texSize[texture][1];
            float[][] uvs = {{uv[0] / tw, uv[1] / th}, {uv[2] / tw, uv[1] / th}, {uv[2] / tw, uv[3] / th}, {uv[0] / tw, uv[3] / th}};
            int turns = ((int) num(face, "rotation", 0) / 90) & 3;
            for (int t = 0; t < turns; t++) uvs = new float[][]{uvs[3], uvs[0], uvs[1], uvs[2]};
            builder.face(bone, texture, c, uvs);
        }
    }

    /** Blockbench's box UV layout for a cube of size w x h x d at (u, v). */
    private static float[] boxUv(int face, float u, float v, float w, float h, float d, boolean mirror) {
        float[] r = switch (face) {
            case 0 -> new float[]{u + d, v + d, u + d + w, v + d + h};               // north
            case 1 -> new float[]{u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h}; // south
            case 2 -> new float[]{u, v + d, u + d, v + d + h};                       // east
            case 3 -> new float[]{u + d + w, v + d, u + 2 * d + w, v + d + h};       // west
            case 4 -> new float[]{u + d, v, u + d + w, v + d};                       // up
            default -> new float[]{u + d + w, v + d, u + d + 2 * w, v};              // down
        };
        if (mirror) {
            float t = r[0];
            r[0] = r[2];
            r[2] = t;
        }
        return r;
    }

    private static void bbMesh(JsonObject el, int bone, Matrix4f rot, Builder builder, float[][] texSize) throws IOException {
        JsonObject vertices = obj(el, "vertices");
        JsonObject faces = obj(el, "faces");
        if (vertices == null || faces == null) return;
        Vector3f v = new Vector3f();
        for (Map.Entry<String, JsonElement> fe : faces.entrySet()) {
            JsonObject face = fe.getValue().getAsJsonObject();
            JsonElement tex = face.get("texture");
            if (tex == null || tex.isJsonNull() || (tex.isJsonPrimitive() && tex.getAsJsonPrimitive().isBoolean())) continue;
            int texture = tex.getAsInt();
            if (texture < 0 || texture >= texSize.length) continue;
            JsonArray keys = arr(face, "vertices");
            JsonObject uvMap = obj(face, "uv");
            if (keys == null || uvMap == null || keys.size() < 3 || keys.size() > 4) continue;
            int count = keys.size();
            float[][] c = new float[count][];
            float[][] uvs = new float[count][];
            for (int i = 0; i < count; i++) {
                String k = keys.get(i).getAsString();
                float[] p = vec3(vertices.getAsJsonArray(k));
                rot.transformPosition(v.set(p[0], p[1], p[2]));
                c[i] = new float[]{v.x, v.y, v.z};
                float[] uv = uvMap.has(k) ? vec3(uvMap.getAsJsonArray(k)) : new float[3];
                uvs[i] = new float[]{uv[0] / texSize[texture][0], uv[1] / texSize[texture][1]};
            }
            if (count == 4) sortQuad(c, uvs);
            builder.face(bone, texture, c, uvs);
        }
    }

    /** Puts a quad's corners in order around its edge (Blockbench does not store them that way). */
    private static void sortQuad(float[][] c, float[][] uvs) {
        float cx = 0, cy = 0, cz = 0;
        for (float[] p : c) {
            cx += p[0] / 4;
            cy += p[1] / 4;
            cz += p[2] / 4;
        }
        Vector3f a = new Vector3f(c[0][0] - cx, c[0][1] - cy, c[0][2] - cz);
        Vector3f n = new Vector3f(c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2])
                .cross(c[2][0] - c[0][0], c[2][1] - c[0][1], c[2][2] - c[0][2]);
        if (a.lengthSquared() < 1.0e-12f || n.lengthSquared() < 1.0e-12f) return;
        a.normalize();
        n.normalize();
        Vector3f b = new Vector3f(n).cross(a);
        Integer[] order = {0, 1, 2, 3};
        double[] angle = new double[4];
        for (int i = 0; i < 4; i++) {
            Vector3f d = new Vector3f(c[i][0] - cx, c[i][1] - cy, c[i][2] - cz);
            angle[i] = Math.atan2(d.dot(b), d.dot(a));
        }
        java.util.Arrays.sort(order, Comparator.comparingDouble(i -> angle[i]));
        float[][] c2 = new float[4][];
        float[][] u2 = new float[4][];
        for (int i = 0; i < 4; i++) {
            c2[i] = c[order[i]];
            u2[i] = uvs[order[i]];
        }
        System.arraycopy(c2, 0, c, 0, 4);
        System.arraycopy(u2, 0, uvs, 0, 4);
    }

    // ---------------------------------------------------------------- loading: OBJ

    private static Model loadObj(Path file) throws IOException {
        List<float[]> positions = new ArrayList<>();
        List<float[]> texCoords = new ArrayList<>();
        Map<String, Integer> materialTexture = new HashMap<>();
        List<Identifier> textures = new ArrayList<>();
        Map<String, Path> materialFiles = new HashMap<>();
        Path dir = file.getParent();
        String stem = file.getFileName().toString().replaceFirst("(?i)\\.obj$", "");

        // First pass: geometry lines are kept so faces can be built once every material is known.
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("mtllib ")) readMtl(dir, line.substring(7).trim(), materialFiles);
        }
        int fallback = -1;
        List<Object[]> faceList = new ArrayList<>();
        int current = -1;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "v" -> positions.add(new float[]{f(p, 1), f(p, 2), f(p, 3)});
                case "vt" -> texCoords.add(new float[]{f(p, 1), p.length > 2 ? f(p, 2) : 0f});
                case "usemtl" -> {
                    String mat = p.length > 1 ? line.substring(7).trim() : "";
                    Integer index = materialTexture.get(mat);
                    if (index == null) {
                        Identifier id = textureFile(materialFiles.get(mat));
                        if (id == null) {
                            current = -1;
                            continue;
                        }
                        textures.add(id);
                        index = textures.size() - 1;
                        materialTexture.put(mat, index);
                    }
                    current = index;
                }
                case "f" -> faceList.add(new Object[]{p, current});
                default -> {
                }
            }
        }
        boolean needFallback = false;
        for (Object[] fc : faceList) if ((int) fc[1] < 0) needFallback = true;
        if (needFallback) {
            Identifier id = textureNear(file, stem);
            // No texture anywhere: a white model still shows the shape.
            if (id == null) id = whiteTexture();
            textures.add(id);
            fallback = textures.size() - 1;
        }
        Builder builder = new Builder(textures.size());
        for (Object[] fc : faceList) {
            String[] p = (String[]) fc[0];
            int texture = (int) fc[1] < 0 ? fallback : (int) fc[1];
            if (texture < 0 || p.length < 4) continue;
            int count = p.length - 1;
            float[][] c = new float[count][];
            float[][] uvs = new float[count][];
            for (int i = 0; i < count; i++) {
                String[] idx = p[i + 1].split("/");
                c[i] = positions.get(index(idx[0], positions.size()));
                float[] t = idx.length > 1 && !idx[1].isEmpty() ? texCoords.get(index(idx[1], texCoords.size())) : new float[]{0, 0};
                uvs[i] = new float[]{t[0], 1f - t[1]};
            }
            if (count <= 4) {
                builder.face(0, texture, c, uvs);
            } else {
                for (int i = 1; i + 1 < count; i++) {
                    builder.face(0, texture, new float[][]{c[0], c[i], c[i + 1]}, new float[][]{uvs[0], uvs[i], uvs[i + 1]});
                }
            }
        }
        return builder.build(textures.toArray(new Identifier[0]), 1f, true);
    }

    private static void readMtl(Path dir, String name, Map<String, Path> out) {
        Path mtl = dir.resolve(name).normalize();
        if (!mtl.startsWith(dir) || !Files.isRegularFile(mtl)) return;
        try {
            String material = null;
            for (String raw : Files.readAllLines(mtl, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.startsWith("newmtl ")) material = line.substring(7).trim();
                else if (line.startsWith("map_Kd ") && material != null) {
                    String[] parts = line.substring(7).trim().split("\\s+");
                    String texName = parts[parts.length - 1];
                    Path tex = dir.resolve(texName).normalize();
                    if (!tex.startsWith(dir) || !Files.isRegularFile(tex)) tex = dir.resolve(Path.of(texName).getFileName().toString());
                    out.put(material, tex);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[Play as Mob] could not read {}: {}", mtl, e.toString());
        }
    }

    private static int index(String s, int size) {
        int i = Integer.parseInt(s);
        return i < 0 ? size + i : i - 1;
    }

    private static float f(String[] p, int i) {
        return i < p.length ? Float.parseFloat(p[i]) : 0f;
    }

    // ---------------------------------------------------------------- json helpers

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    static float num(JsonObject o, String key, float fallback) {
        JsonElement e = o == null ? null : o.get(key);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsFloat() : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    static float[] vec(JsonObject o, String key) {
        return vec3(arr(o, key));
    }

    static float[] vec3(JsonArray a) {
        float[] r = new float[3];
        for (int i = 0; a != null && i < Math.min(3, a.size()); i++) r[i] = a.get(i).getAsFloat();
        return r;
    }

    private static float[] vec4(JsonArray a) {
        if (a == null || a.size() < 4) return null;
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
    }
}
