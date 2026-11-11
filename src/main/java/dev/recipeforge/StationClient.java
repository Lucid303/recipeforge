package dev.recipeforge;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The client's copy of the custom anvil / grindstone combinations (sent by the server). */
public final class StationClient {
    private StationClient() {}

    public static volatile List<JsonObject> anvil = new ArrayList<>();
    public static volatile List<JsonObject> grindstone = new ArrayList<>();
    public static volatile int version = 0;

    public static List<JsonObject> of(String station) {
        return station.equals("anvil") ? anvil : grindstone;
    }

    public static void apply(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            anvil = read(o, "anvil");
            grindstone = read(o, "grindstone");
            version++;
        } catch (Exception ignored) {
            // keep the old lists
        }
    }

    private static List<JsonObject> read(JsonObject o, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (o.has(key)) {
            for (JsonElement e : o.getAsJsonArray(key)) {
                out.add(e.getAsJsonObject());
            }
        }
        return out;
    }
}
