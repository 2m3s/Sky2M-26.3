package com.epic60869.sky2m.features.profileviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Null-safe reads from Hypixel's profile JSON, where any field can be missing. */
final class Json {
    private Json() {}

    static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }

    /** Follows a path like "player_data.experience". */
    static JsonObject path(JsonObject o, String path) {
        for (String key : path.split("\\.")) o = obj(o, key);
        return o;
    }

    static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    static double num(JsonObject o, String key) {
        try {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }
}
