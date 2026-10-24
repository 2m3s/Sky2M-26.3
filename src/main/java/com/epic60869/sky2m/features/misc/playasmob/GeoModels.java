package com.epic60869.sky2m.features.misc.playasmob;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.arr;
import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.num;
import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.obj;
import static com.epic60869.sky2m.features.misc.playasmob.CustomModels.str;

/**
 * Two more formats for Play as Mob's imported models:
 * <ul>
 *   <li>Minecraft Bedrock entity geometry ({@code .geo.json}, the 1.12+ {@code minecraft:geometry} list and the old
 *   {@code geometry.name} objects), turned into a Blockbench model the way Blockbench imports it (x mirrored, x and y
 *   rotations negated) and drawn by the Blockbench code. Its texture is the image of the same name next to it
 *   ({@code dragon.geo.json} uses {@code dragon.png}).</li>
 *   <li>{@code .stl} (ASCII or binary), the 3D-printing format: shape only, drawn light grey, Z-up turned to Y-up.</li>
 * </ul>
 */
final class GeoModels {

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    private GeoModels() {
    }

    // ---------------------------------------------------------------- Bedrock

    static CustomModels.Model loadBedrock(Path file) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject geo = null;
        float texW = 64, texH = 64;
        JsonArray list = arr(root, "minecraft:geometry");
        if (list != null && !list.isEmpty()) {
            geo = list.get(0).getAsJsonObject();
            JsonObject description = obj(geo, "description");
            texW = num(description, "texture_width", 64);
            texH = num(description, "texture_height", 64);
        } else {
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (e.getKey().startsWith("geometry.") && e.getValue().isJsonObject()) {
                    geo = e.getValue().getAsJsonObject();
                    texW = num(geo, "texturewidth", 64);
                    texH = num(geo, "textureheight", 64);
                    break;
                }
            }
        }
        if (geo == null) throw new IOException("no Bedrock geometry in the file");
        JsonArray bones = arr(geo, "bones");
        if (bones == null || bones.isEmpty()) throw new IOException("the geometry has no bones");

        JsonObject bb = new JsonObject();
        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", texW);
        resolution.addProperty("height", texH);
        bb.add("resolution", resolution);
        bb.add("textures", textureList(file, texW, texH));

        JsonArray elements = new JsonArray();
        Map<String, JsonObject> groups = new LinkedHashMap<>();
        Map<String, String> parents = new HashMap<>();
        int uuid = 0;
        for (JsonElement be : bones) {
            JsonObject bone = be.getAsJsonObject();
            String name = str(bone, "name");
            if (name == null) name = "bone" + uuid;
            JsonObject group = new JsonObject();
            group.addProperty("name", name);
            group.addProperty("uuid", "g" + (uuid++));
            group.add("origin", mirrorX(vec(bone, "pivot")));
            group.add("rotation", mirrorRotation(vec(bone, "rotation")));
            JsonArray children = new JsonArray();
            group.add("children", children);
            boolean boneMirror = bone.has("mirror") && bone.get("mirror").getAsBoolean();
            float boneInflate = num(bone, "inflate", 0);
            JsonArray cubes = arr(bone, "cubes");
            for (int c = 0; cubes != null && c < cubes.size(); c++) {
                JsonObject cube = cubes.get(c).getAsJsonObject();
                JsonObject el = cube(cube, boneMirror, boneInflate, "c" + (uuid++));
                elements.add(el);
                children.add(el.get("uuid"));
            }
            groups.put(name, group);
            String parent = str(bone, "parent");
            if (parent != null) parents.put(name, parent);
        }
        JsonArray outliner = new JsonArray();
        for (Map.Entry<String, JsonObject> g : groups.entrySet()) {
            JsonObject parent = groups.get(parents.get(g.getKey()));
            if (parent != null && parent != g.getValue()) parent.getAsJsonArray("children").add(g.getValue());
            else outliner.add(g.getValue());
        }
        bb.add("elements", elements);
        bb.add("outliner", outliner);
        return CustomModels.loadBbModel(bb, file);
    }

    private static JsonArray textureList(Path file, float texW, float texH) {
        String name = file.getFileName().toString().replaceFirst("(?i)\\.geo\\.json$", "");
        JsonObject tex = new JsonObject();
        tex.addProperty("uv_width", texW);
        tex.addProperty("uv_height", texH);
        Path dir = file.getParent();
        for (String stem : new String[]{name, name.contains(".") ? name.substring(0, name.indexOf('.')) : name}) {
            for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
                Path image = dir.resolve(stem + ext);
                if (Files.isRegularFile(image)) {
                    tex.addProperty("path", image.toAbsolutePath().toString());
                    JsonArray out = new JsonArray();
                    out.add(tex);
                    return out;
                }
            }
        }
        JsonArray out = new JsonArray();
        out.add(tex); // no image found: the Blockbench code draws it white
        return out;
    }

    /** One Bedrock cube as a Blockbench element. */
    private static JsonObject cube(JsonObject cube, boolean boneMirror, float boneInflate, String uuid) {
        float[] origin = vec(cube, "origin");
        float[] size = vec(cube, "size");
        JsonObject el = new JsonObject();
        el.addProperty("type", "cube");
        el.addProperty("uuid", uuid);
        float x0 = -(origin[0] + size[0]);
        el.add("from", array(x0, origin[1], origin[2]));
        el.add("to", array(x0 + size[0], origin[1] + size[1], origin[2] + size[2]));
        el.add("origin", cube.has("pivot") ? mirrorX(vec(cube, "pivot")) : array(0, 0, 0));
        el.add("rotation", mirrorRotation(vec(cube, "rotation")));
        el.addProperty("inflate", cube.has("inflate") ? num(cube, "inflate", 0) : boneInflate);
        boolean mirror = cube.has("mirror") ? cube.get("mirror").getAsBoolean() : boneMirror;
        JsonElement uv = cube.get("uv");
        JsonObject faces = new JsonObject();
        if (uv != null && uv.isJsonObject()) {
            el.addProperty("box_uv", false);
            for (String face : FACES) {
                JsonObject f = obj(uv.getAsJsonObject(), face);
                if (f == null) continue;
                float[] at = vec(f, "uv");
                float[] sz = vec(f, "uv_size");
                JsonObject out = new JsonObject();
                out.add("uv", array4(at[0], at[1], at[0] + sz[0], at[1] + sz[1]));
                out.addProperty("texture", 0);
                faces.add(face, out);
            }
        } else {
            el.addProperty("box_uv", true);
            float[] at = uv != null && uv.isJsonArray() ? vec3(uv.getAsJsonArray()) : new float[3];
            el.add("uv_offset", array(at[0], at[1], 0));
            el.addProperty("mirror_uv", mirror);
            for (String face : FACES) {
                JsonObject out = new JsonObject();
                out.addProperty("texture", 0);
                faces.add(face, out);
            }
        }
        el.add("faces", faces);
        return el;
    }

    private static float[] vec(JsonObject o, String key) {
        return vec3(arr(o, key));
    }

    private static float[] vec3(JsonArray a) {
        return CustomModels.vec3(a);
    }

    private static JsonArray mirrorX(float[] v) {
        return array(-v[0], v[1], v[2]);
    }

    private static JsonArray mirrorRotation(float[] r) {
        return array(-r[0], -r[1], r[2]);
    }

    private static JsonArray array(float... values) {
        JsonArray a = new JsonArray();
        for (float v : values) a.add(new JsonPrimitive(v));
        return a;
    }

    private static JsonArray array4(float a, float b, float c, float d) {
        return array(a, b, c, d);
    }

    // ---------------------------------------------------------------- STL

    static CustomModels.Model loadStl(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Identifier grey = CustomModels.colourTexture(0xFFB4B4B4);
        CustomModels.Builder builder = new CustomModels.Builder(1);
        float[][] noUv = {{0.5f, 0.5f}, {0.5f, 0.5f}, {0.5f, 0.5f}};
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        long triangles = bytes.length >= 84 ? bb.getInt(80) & 0xFFFFFFFFL : -1;
        if (triangles >= 0 && 84 + triangles * 50 == bytes.length) {
            for (int t = 0; t < triangles; t++) {
                int at = 84 + t * 50 + 12;
                float[][] c = new float[3][];
                for (int k = 0; k < 3; k++) {
                    c[k] = yUp(bb.getFloat(at + k * 12), bb.getFloat(at + k * 12 + 4), bb.getFloat(at + k * 12 + 8));
                }
                builder.face(0, 0, c, noUv);
            }
        } else {
            float[][] c = new float[3][];
            int n = 0;
            for (String raw : new String(bytes, StandardCharsets.US_ASCII).split("\\R")) {
                String line = raw.trim();
                if (!line.startsWith("vertex")) continue;
                String[] p = line.split("\\s+");
                if (p.length < 4) continue;
                c[n++] = yUp(Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
                if (n == 3) {
                    builder.face(0, 0, c, noUv);
                    c = new float[3][];
                    n = 0;
                }
            }
        }
        return builder.build(new Identifier[]{grey}, 1f, true);
    }

    /** STL files are Z-up; Minecraft is Y-up. */
    private static float[] yUp(float x, float y, float z) {
        return new float[]{x, z, -y};
    }
}
