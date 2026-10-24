package com.epic60869.sky2m.features.misc.playasmob;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.ANIM_NONE;
import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.arr;
import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.num;
import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.obj;
import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.str;

/**
 * glTF 2.0 models for Play as Mob: {@code .gltf} (with its {@code .bin} and images beside it, or embedded as data URIs)
 * and the binary {@code .glb}, which VRM avatars ({@code .vrm}) also use. This is what Blender, Sketchfab and most
 * other tools export, so it is the format to convert anything else to.
 * <p>
 * Triangles only, each primitive textured by its material's base colour image (PNG or JPEG) or coloured by its base
 * colour. Meshes are drawn in their rest pose; nodes or skeleton joints named head, left/right arm (or upper arm) and
 * left/right leg (or thigh) become bones that follow your head and swing as you walk, carrying every triangle whose
 * node, or whose vertices' strongest joint, sits under them.
 */
final class GltfModels {

    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private GltfModels() {
    }

    static CustomModels.Model load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        JsonObject root;
        byte[] glbBin = null;
        ByteBuffer head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length >= 12 && head.getInt(0) == GLB_MAGIC) {
            String json = null;
            int at = 12;
            while (at + 8 <= bytes.length) {
                int length = head.getInt(at);
                int type = head.getInt(at + 4);
                if (length < 0 || at + 8 + length > bytes.length) throw new IOException("the .glb file is cut short");
                if (type == CHUNK_JSON) json = new String(bytes, at + 8, length, StandardCharsets.UTF_8);
                else if (type == CHUNK_BIN && glbBin == null) glbBin = java.util.Arrays.copyOfRange(bytes, at + 8, at + 8 + length);
                at += 8 + length;
            }
            if (json == null) throw new IOException("the .glb file has no JSON part");
            root = JsonParser.parseString(json).getAsJsonObject();
        } else {
            root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        return new Reader(file, root, glbBin).read();
    }

    private static final class Reader {
        final Path file;
        final Path dir;
        final JsonObject root;
        final byte[] glbBin;
        final List<byte[]> buffers = new ArrayList<>();
        final JsonArray views;
        final JsonArray accessors;
        final JsonArray nodes;
        final Map<Integer, Identifier> images = new HashMap<>();

        Reader(Path file, JsonObject root, byte[] glbBin) {
            this.file = file;
            this.dir = file.getParent();
            this.root = root;
            this.glbBin = glbBin;
            this.views = arr(root, "bufferViews");
            this.accessors = arr(root, "accessors");
            JsonArray n = arr(root, "nodes");
            this.nodes = n == null ? new JsonArray() : n;
        }

        CustomModels.Model read() throws IOException {
            refuseCompression();
            JsonArray bufArr = arr(root, "buffers");
            for (int i = 0; bufArr != null && i < bufArr.size(); i++) buffers.add(buffer(bufArr.get(i).getAsJsonObject(), i));

            // One texture per material, plus a white one last for primitives without a material.
            JsonArray materials = arr(root, "materials");
            int materialCount = materials == null ? 0 : materials.size();
            Identifier[] textures = new Identifier[materialCount + 1];
            for (int i = 0; i < materialCount; i++) textures[i] = materialTexture(materials.get(i).getAsJsonObject());
            textures[materialCount] = CustomModels.whiteTexture();

            // Node tree: parents, world matrices, and which nodes are animated bones.
            int count = nodes.size();
            int[] parent = new int[count];
            java.util.Arrays.fill(parent, -1);
            for (int i = 0; i < count; i++) {
                JsonArray children = arr(nodes.get(i).getAsJsonObject(), "children");
                for (int c = 0; children != null && c < children.size(); c++) {
                    int child = children.get(c).getAsInt();
                    if (child >= 0 && child < count) parent[child] = i;
                }
            }
            List<Integer> order = new ArrayList<>();
            Matrix4f[] world = new Matrix4f[count];
            for (int i = 0; i < count; i++) if (parent[i] < 0) walk(i, new Matrix4f(), parent, world, order, 0);

            CustomModels.Builder builder = new CustomModels.Builder(textures.length);
            int[] boneOf = new int[count];   // the animated bone a node's triangles go to (0 = root)
            int[] animOf = new int[count];
            for (int node : order) {
                int up = parent[node];
                int inherited = up < 0 ? 0 : boneOf[up];
                int anim = CustomModels.animFor(str(nodes.get(node).getAsJsonObject(), "name"));
                // An arm or leg inside the same arm or leg is its lower half (Mixamo's RightArm > RightForeArm,
                // RightUpLeg > RightLeg): it bends at the elbow or knee.
                int lower = switch (anim) {
                    case CustomModels.ANIM_RIGHT_ARM -> CustomModels.ANIM_RIGHT_ELBOW;
                    case CustomModels.ANIM_LEFT_ARM -> CustomModels.ANIM_LEFT_ELBOW;
                    case CustomModels.ANIM_RIGHT_LEG -> CustomModels.ANIM_RIGHT_KNEE;
                    case CustomModels.ANIM_LEFT_LEG -> CustomModels.ANIM_LEFT_KNEE;
                    default -> ANIM_NONE;
                };
                if (lower != ANIM_NONE && hasAncestorAnim(node, anim, parent, animOf)) anim = lower;
                if (anim != ANIM_NONE && hasAncestorAnim(node, anim, parent, animOf)) anim = ANIM_NONE;
                animOf[node] = anim;
                if (anim == ANIM_NONE) {
                    boneOf[node] = inherited;
                } else {
                    Vector3f pivot = world[node].getTranslation(new Vector3f());
                    boneOf[node] = builder.bone(inherited, new float[]{pivot.x, pivot.y, pivot.z}, new float[3], anim);
                }
            }

            // Hand bones, for held items: the first per side from the root down.
            for (int node : order) {
                int side = CustomModels.handSide(str(nodes.get(node).getAsJsonObject(), "name"));
                if (side == 0) continue;
                Vector3f at = world[node].getTranslation(new Vector3f());
                builder.hand(side == 2, boneOf[node], new float[]{at.x, at.y, at.z});
            }

            JsonArray meshes = arr(root, "meshes");
            JsonArray skins = arr(root, "skins");
            if (meshes == null) throw new IOException("the file has no meshes");
            for (int node : order) {
                JsonObject n = nodes.get(node).getAsJsonObject();
                if (!n.has("mesh")) continue;
                int meshIndex = n.get("mesh").getAsInt();
                if (meshIndex < 0 || meshIndex >= meshes.size()) continue;
                int[] skinJoints = null;
                if (n.has("skin") && skins != null && n.get("skin").getAsInt() < skins.size()) {
                    JsonArray joints = arr(skins.get(n.get("skin").getAsInt()).getAsJsonObject(), "joints");
                    if (joints != null) {
                        skinJoints = new int[joints.size()];
                        for (int j = 0; j < joints.size(); j++) skinJoints[j] = joints.get(j).getAsInt();
                    }
                }
                // A skinned mesh's vertices are already where its skeleton puts them; others move with their node.
                Matrix4f m = skinJoints != null ? new Matrix4f() : world[node];
                JsonArray primitives = arr(meshes.get(meshIndex).getAsJsonObject(), "primitives");
                for (int p = 0; primitives != null && p < primitives.size(); p++) {
                    primitive(primitives.get(p).getAsJsonObject(), m, boneOf[node], skinJoints, boneOf, builder, materialCount);
                }
            }
            return builder.build(textures, 1f, true, baseTurn(), true);
        }

        /** glTF faces +Z, a player -Z. VRM 0.x avatars are the exception: they face -Z already. */
        private float baseTurn() {
            JsonObject ext = obj(root, "extensions");
            if (ext != null && ext.has("VRM") && !ext.has("VRMC_vrm")) return 0f;
            return (float) Math.PI;
        }

        private void refuseCompression() throws IOException {
            JsonArray required = arr(root, "extensionsRequired");
            JsonArray used = arr(root, "extensionsUsed");
            for (JsonArray list : new JsonArray[]{required, used}) {
                for (int i = 0; list != null && i < list.size(); i++) {
                    String e = list.get(i).getAsString();
                    if (e.equals("KHR_draco_mesh_compression") || e.startsWith("EXT_meshopt") || e.startsWith("KHR_meshopt")) {
                        throw new IOException("the model is compressed (" + e + "): re-export it without compression");
                    }
                }
            }
        }

        private void walk(int node, Matrix4f parentMatrix, int[] parent, Matrix4f[] world, List<Integer> order, int depth) {
            if (depth > 256 || world[node] != null) return;
            world[node] = new Matrix4f(parentMatrix).mul(local(nodes.get(node).getAsJsonObject()));
            order.add(node);
            JsonArray children = arr(nodes.get(node).getAsJsonObject(), "children");
            for (int c = 0; children != null && c < children.size(); c++) {
                int child = children.get(c).getAsInt();
                if (child >= 0 && child < nodes.size() && parent[child] == node) walk(child, world[node], parent, world, order, depth + 1);
            }
        }

        private static boolean hasAncestorAnim(int node, int anim, int[] parent, int[] animOf) {
            for (int up = parent[node]; up >= 0; up = parent[up]) if (animOf[up] == anim) return true;
            return false;
        }

        private static Matrix4f local(JsonObject n) {
            JsonArray matrix = arr(n, "matrix");
            if (matrix != null && matrix.size() == 16) {
                float[] f = new float[16];
                for (int i = 0; i < 16; i++) f[i] = matrix.get(i).getAsFloat();
                return new Matrix4f().set(f);
            }
            float[] t = floats(arr(n, "translation"), 3, 0f);
            float[] r = floats(arr(n, "rotation"), 4, 0f);
            float[] s = floats(arr(n, "scale"), 3, 1f);
            if (!n.has("rotation")) r[3] = 1f;
            return new Matrix4f().translationRotateScale(t[0], t[1], t[2], r[0], r[1], r[2], r[3], s[0], s[1], s[2]);
        }

        private static float[] floats(JsonArray a, int size, float fallback) {
            float[] out = new float[size];
            for (int i = 0; i < size; i++) out[i] = a != null && i < a.size() ? a.get(i).getAsFloat() : fallback;
            return out;
        }

        private void primitive(JsonObject prim, Matrix4f m, int nodeBone, int[] skinJoints, int[] boneOf,
                               CustomModels.Builder builder, int materialCount) throws IOException {
            int mode = prim.has("mode") ? prim.get("mode").getAsInt() : 4;
            if (mode != 4) return; // points, lines and strips are not drawn
            JsonObject attributes = obj(prim, "attributes");
            if (attributes == null || !attributes.has("POSITION")) return;
            float[] pos = floatAccessor(attributes.get("POSITION").getAsInt());
            int vertexCount = pos.length / 3;
            float[] uv = attributes.has("TEXCOORD_0") ? floatAccessor(attributes.get("TEXCOORD_0").getAsInt()) : null;
            // Skinned vertices: up to 4 of the model's bones each, with weights adding up to 1. Joints that aren't
            // animated bones themselves (spine, hands...) count as the animated bone they sit in.
            int[][] vertexBones = null;
            float[][] vertexWeights = null;
            if (skinJoints != null && attributes.has("JOINTS_0") && attributes.has("WEIGHTS_0")) {
                float[] joints = floatAccessorRaw(attributes.get("JOINTS_0").getAsInt());
                float[] weights = floatAccessor(attributes.get("WEIGHTS_0").getAsInt());
                vertexBones = new int[vertexCount][];
                vertexWeights = new float[vertexCount][];
                for (int v = 0; v < vertexCount; v++) {
                    int[] bones = new int[4];
                    float[] ws = new float[4];
                    int used = 0;
                    float total = 0f;
                    for (int k = 0; k < 4 && v * 4 + k < joints.length && v * 4 + k < weights.length; k++) {
                        float w = weights[v * 4 + k];
                        int joint = (int) joints[v * 4 + k];
                        if (w <= 0f || joint < 0 || joint >= skinJoints.length) continue;
                        int node = skinJoints[joint];
                        int bone = node >= 0 && node < boneOf.length ? boneOf[node] : 0;
                        int at = 0;
                        while (at < used && bones[at] != bone) at++;
                        if (at == used) bones[used++] = bone;
                        ws[at] += w;
                        total += w;
                    }
                    if (used == 0 || total <= 0f) {
                        bones[0] = nodeBone;
                        ws[0] = 1f;
                    } else {
                        for (int k = 0; k < used; k++) ws[k] /= total;
                    }
                    vertexBones[v] = bones;
                    vertexWeights[v] = ws;
                }
            }
            int[] indices;
            if (prim.has("indices")) {
                float[] raw = floatAccessorRaw(prim.get("indices").getAsInt());
                indices = new int[raw.length];
                for (int i = 0; i < raw.length; i++) indices[i] = (int) raw[i];
            } else {
                indices = new int[vertexCount];
                for (int i = 0; i < vertexCount; i++) indices[i] = i;
            }
            int material = prim.has("material") ? prim.get("material").getAsInt() : -1;
            int texture = material >= 0 && material < materialCount ? material : materialCount;

            Vector3f v = new Vector3f();
            for (int i = 0; i + 2 < indices.length; i += 3) {
                float[][] corners = new float[3][];
                float[][] uvs = new float[3][];
                for (int k = 0; k < 3; k++) {
                    int idx = indices[i + k];
                    if (idx < 0 || idx >= vertexCount) return;
                    m.transformPosition(v.set(pos[idx * 3], pos[idx * 3 + 1], pos[idx * 3 + 2]));
                    corners[k] = new float[]{v.x, v.y, v.z};
                    uvs[k] = uv != null && idx * 2 + 1 < uv.length ? new float[]{uv[idx * 2], uv[idx * 2 + 1]} : new float[2];
                }
                if (vertexBones == null) {
                    builder.face(nodeBone, texture, corners, uvs);
                    continue;
                }
                int[][] cornerBones = {vertexBones[indices[i]], vertexBones[indices[i + 1]], vertexBones[indices[i + 2]]};
                float[][] cornerWeights = {vertexWeights[indices[i]], vertexWeights[indices[i + 1]], vertexWeights[indices[i + 2]]};
                // A face wholly in one bone is drawn rigidly, which is cheaper; the rest blend.
                int bone = cornerBones[0][0];
                boolean rigid = true;
                float best = -1f;
                for (int k = 0; k < 3; k++) {
                    if (cornerWeights[k][0] < 0.999f || cornerBones[k][0] != cornerBones[0][0]) rigid = false;
                    for (int j = 0; j < 4; j++) {
                        if (cornerWeights[k][j] > best) {
                            best = cornerWeights[k][j];
                            bone = cornerBones[k][j];
                        }
                    }
                }
                if (rigid) builder.face(bone, texture, corners, uvs);
                else builder.face(bone, texture, corners, uvs, cornerBones, cornerWeights);
            }
        }

        // ------------------------------------------------------------ materials and images

        private Identifier materialTexture(JsonObject material) {
            JsonObject pbr = obj(material, "pbrMetallicRoughness");
            JsonObject colourTex = obj(pbr, "baseColorTexture");
            if (colourTex == null) {
                // KHR_materials_pbrSpecularGlossiness models keep their colour image here.
                colourTex = obj(obj(obj(material, "extensions"), "KHR_materials_pbrSpecularGlossiness"), "diffuseTexture");
            }
            if (colourTex != null && colourTex.has("index")) {
                Identifier id = texture(colourTex.get("index").getAsInt());
                if (id != null) return id;
            }
            float[] f = floats(arr(pbr, "baseColorFactor"), 4, 1f);
            int argb = (Math.round(f[3] * 255) << 24) | (Math.round(f[0] * 255) << 16) | (Math.round(f[1] * 255) << 8) | Math.round(f[2] * 255);
            return CustomModels.colourTexture(argb);
        }

        private Identifier texture(int index) {
            JsonArray textures = arr(root, "textures");
            if (textures == null || index < 0 || index >= textures.size()) return null;
            JsonObject t = textures.get(index).getAsJsonObject();
            if (!t.has("source")) return null;
            int source = t.get("source").getAsInt();
            if (images.containsKey(source)) return images.get(source);
            Identifier id = null;
            try {
                byte[] data = image(source);
                if (data != null) id = CustomModels.textureBytes(data, file.getFileName() + " image " + source);
            } catch (Exception e) {
                CustomModels.LOGGER.warn("[Play as Mob] could not read image {} of {}: {}", source, file, e.toString());
            }
            images.put(source, id);
            return id;
        }

        private byte[] image(int index) throws IOException {
            JsonArray imgs = arr(root, "images");
            if (imgs == null || index < 0 || index >= imgs.size()) return null;
            JsonObject img = imgs.get(index).getAsJsonObject();
            if (img.has("bufferView")) return view(img.get("bufferView").getAsInt());
            String uri = str(img, "uri");
            return uri == null ? null : uri(uri);
        }

        // ------------------------------------------------------------ buffers and accessors

        private byte[] buffer(JsonObject b, int index) throws IOException {
            String uri = str(b, "uri");
            if (uri == null) {
                if (index == 0 && glbBin != null) return glbBin;
                throw new IOException("buffer " + index + " has no data");
            }
            return uri(uri);
        }

        /** A data URI, or a file next to the model (never outside its folder). */
        private byte[] uri(String uri) throws IOException {
            if (uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                if (comma < 0 || !uri.substring(0, comma).contains(";base64")) throw new IOException("unsupported data URI");
                return Base64.getDecoder().decode(uri.substring(comma + 1));
            }
            String decoded = java.net.URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8);
            Path target = dir.resolve(decoded).normalize();
            if (!target.startsWith(dir) || !Files.isRegularFile(target)) throw new IOException("missing file " + decoded + " next to the model");
            if (Files.size(target) > CustomModels.MAX_FILE_BYTES) throw new IOException(decoded + " is over 128 MB");
            return Files.readAllBytes(target);
        }

        private byte[] view(int index) throws IOException {
            JsonObject v = views.get(index).getAsJsonObject();
            byte[] buffer = buffers.get(v.get("buffer").getAsInt());
            int offset = (int) num(v, "byteOffset", 0);
            int length = v.get("byteLength").getAsInt();
            if (offset < 0 || length < 0 || offset + length > buffer.length) throw new IOException("a buffer view is out of range");
            return java.util.Arrays.copyOfRange(buffer, offset, offset + length);
        }

        /** An accessor's values as floats, with normalized integers scaled to 0-1 (positions, uvs, weights). */
        private float[] floatAccessor(int index) throws IOException {
            return accessor(index, true);
        }

        /** An accessor's values as floats without scaling (indices, joint numbers). */
        private float[] floatAccessorRaw(int index) throws IOException {
            return accessor(index, false);
        }

        private float[] accessor(int index, boolean normalize) throws IOException {
            if (accessors == null || index < 0 || index >= accessors.size()) throw new IOException("missing accessor " + index);
            JsonObject a = accessors.get(index).getAsJsonObject();
            int count = a.get("count").getAsInt();
            int components = switch (str(a, "type") == null ? "SCALAR" : str(a, "type").toUpperCase(Locale.ROOT)) {
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4", "MAT2" -> 4;
                case "MAT3" -> 9;
                case "MAT4" -> 16;
                default -> 1;
            };
            int type = a.get("componentType").getAsInt();
            int size = switch (type) {
                case 5120, 5121 -> 1;
                case 5122, 5123 -> 2;
                case 5125, 5126 -> 4;
                default -> throw new IOException("unknown component type " + type);
            };
            if ((long) count * components > 20_000_000L) throw new IOException("the model is too big");
            float[] out = new float[count * components];
            if (!a.has("bufferView")) return out; // all zeros (sparse-only accessors are not read)
            JsonObject v = views.get(a.get("bufferView").getAsInt()).getAsJsonObject();
            byte[] buffer = buffers.get(v.get("buffer").getAsInt());
            int base = (int) num(v, "byteOffset", 0) + (int) num(a, "byteOffset", 0);
            int stride = v.has("byteStride") ? v.get("byteStride").getAsInt() : size * components;
            boolean normalized = normalize && a.has("normalized") && a.get("normalized").getAsBoolean();
            ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                for (int c = 0; c < components; c++) {
                    int at = base + i * stride + c * size;
                    if (at < 0 || at + size > buffer.length) throw new IOException("an accessor is out of range");
                    float value = switch (type) {
                        case 5120 -> normalized ? Math.max(bb.get(at) / 127f, -1f) : bb.get(at);
                        case 5121 -> normalized ? (bb.get(at) & 0xFF) / 255f : bb.get(at) & 0xFF;
                        case 5122 -> normalized ? Math.max(bb.getShort(at) / 32767f, -1f) : bb.getShort(at);
                        case 5123 -> normalized ? (bb.getShort(at) & 0xFFFF) / 65535f : bb.getShort(at) & 0xFFFF;
                        case 5125 -> (float) (bb.getInt(at) & 0xFFFFFFFFL);
                        default -> bb.getFloat(at);
                    };
                    out[i * components + c] = value;
                }
            }
            return out;
        }
    }
}
