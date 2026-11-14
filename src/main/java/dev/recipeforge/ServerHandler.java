package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Runs on the server: checks permissions, reads/writes files, reloads. */
public final class ServerHandler {
    private ServerHandler() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DISABLED_JSON =
            "{\"neoforge:conditions\":[{\"type\":\"neoforge:false\"}]}";

    public static void handle(RecipeEditPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }

            // Server-side permission checks (the client check alone can be bypassed).
            if (!player.isCreative()) {
                fail(player, "Creative mode only.");
                return;
            }
            if (server.isDedicatedServer() && !player.hasPermissions(2)) {
                fail(player, "You need operator permission on this server.");
                return;
            }

            switch (payload.action()) {
                case "GET_RECIPE" -> sendRecipe(player, server, payload.recipeId());
                case "GET_HISTORY" -> sendHistory(player);
                case "GET_HISTORY_ENTRY" -> sendHistoryEntry(player, payload.recipeId());
                case "REAPPLY" -> reapply(player, server, payload.json());
                case "SAVE", "DISABLE", "RESTORE" ->
                        edit(player, server, payload.action(), payload.recipeId(), payload.json());
                case "STATION_SAVE" -> stationSave(player, server, payload.recipeId(), payload.json());
                case "STATION_REMOVE" -> stationRemove(player, server, payload.recipeId(), payload.json());
                default -> {
                    String a = payload.action();
                    if (LootServer.handle(a, player, server, payload.recipeId(), payload.json())
                            || BiomeServer.handle(a, player, server, payload.recipeId(), payload.json())
                            || StructureServer.handle(a, player, server, payload.recipeId(), payload.json())) {
                        return;
                    }
                    if (a.equals("EXPORT_PACK")) {
                        ExportServer.export(player, server);
                        return;
                    }
                    if (Extensions.dispatchServer(a, player, server, payload.recipeId(), payload.json())) {
                        return;
                    }
                    fail(player, "Unknown action: " + a);
                }
            }
        });
    }

    // ---------------------------------------------------------------- recipes: reading

    private static void sendRecipe(ServerPlayer player, MinecraftServer server, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            send(player, "RECIPE_ERROR", idText, "Not a valid recipe id.");
            return;
        }
        Optional<RecipeHolder<?>> holder = server.getRecipeManager().byKey(id);
        if (holder.isEmpty()) {
            send(player, "RECIPE_ERROR", idText, "That recipe does not exist on the server.");
            return;
        }
        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        DataResult<JsonElement> result = Recipe.CODEC.encodeStart(ops, holder.get().value());
        if (result.result().isPresent()) {
            send(player, "RECIPE", idText, GSON.toJson(result.result().get()));
        } else {
            String why = result.error().map(e -> e.message()).orElse("unknown reason");
            send(player, "RECIPE_ERROR", idText,
                    "This recipe cannot be edited (it can still be removed). Reason: " + why);
        }
    }

    private static void sendHistory(ServerPlayer player) {
        JsonArray array = new JsonArray();
        for (History.Entry e : History.recent(100)) {
            JsonObject o = new JsonObject();
            o.addProperty("seq", e.seq);
            o.addProperty("time", e.time);
            o.addProperty("player", e.player);
            o.addProperty("action", e.action);
            o.addProperty("module", e.module == null ? History.moduleOf(e.action) : e.module);
            o.addProperty("id", e.id);
            array.add(o);
        }
        send(player, "HISTORY", "", array.toString());
    }

    private static void sendHistoryEntry(ServerPlayer player, String seqText) {
        try {
            History.Entry e = History.bySeq(Long.parseLong(seqText.trim()));
            if (e == null) {
                send(player, "HISTORY_ENTRY", seqText, "{}");
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("module", e.module == null ? History.moduleOf(e.action) : e.module);
            o.addProperty("action", e.action);
            o.addProperty("id", e.id);
            o.addProperty("before", e.before == null ? "" : e.before);
            o.addProperty("after", e.after == null ? (e.json == null ? "" : e.json) : e.after);
            send(player, "HISTORY_ENTRY", seqText, o.toString());
        } catch (NumberFormatException ex) {
            send(player, "HISTORY_ENTRY", seqText, "{}");
        }
    }

    // ---------------------------------------------------------------- recipes: writing

    private static void reapply(ServerPlayer player, MinecraftServer server, String seqText) {
        long seq;
        try {
            seq = Long.parseLong(seqText.trim());
        } catch (NumberFormatException e) {
            fail(player, "Bad history number.");
            return;
        }
        History.Entry entry = History.bySeq(seq);
        if (entry == null) {
            fail(player, "That history entry no longer exists.");
            return;
        }
        if (!(entry.action.equals("SAVE") || entry.action.equals("DISABLE") || entry.action.equals("RESTORE"))) {
            fail(player, "This kind of change cannot be re-applied from history.");
            return;
        }
        edit(player, server, entry.action, entry.id, entry.json == null ? "" : entry.json);
    }

    private static void edit(ServerPlayer player, MinecraftServer server, String action, String idText, String jsonText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null) {
            fail(player, "Not a valid recipe id: " + idText);
            return;
        }
        Path file = fileFor(id);
        if (file == null) {
            fail(player, "Recipe id rejected for safety: " + id);
            return;
        }

        String before = "";
        try {
            Optional<RecipeHolder<?>> current = server.getRecipeManager().byKey(id);
            if (current.isPresent()) {
                RegistryOps<JsonElement> beforeOps = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                DataResult<JsonElement> encoded = Recipe.CODEC.encodeStart(beforeOps, current.get().value());
                if (encoded.result().isPresent()) {
                    before = GSON.toJson(encoded.result().get());
                }
            }
        } catch (Exception ignored) {
            // history just will not have a "before" picture
        }

        String written = "";
        String verb;
        try {
            switch (action) {
                case "SAVE" -> {
                    JsonElement json;
                    try {
                        json = JsonParser.parseString(jsonText);
                    } catch (JsonParseException e) {
                        fail(player, "That is not valid JSON: " + e.getMessage());
                        return;
                    }
                    if (!json.isJsonObject()) {
                        fail(player, "The recipe must be a JSON object { ... }.");
                        return;
                    }
                    RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                    DataResult<Recipe<?>> parsed = Recipe.CODEC.parse(ops, json);
                    if (parsed.error().isPresent()) {
                        fail(player, "Recipe rejected: " + parsed.error().get().message());
                        return;
                    }
                    written = GSON.toJson(json);
                    write(file, written);
                    verb = "Saved ";
                }
                case "DISABLE" -> {
                    write(file, DISABLED_JSON);
                    verb = "Removed ";
                }
                case "RESTORE" -> {
                    if (!Files.deleteIfExists(file)) {
                        fail(player, "There is no edit of " + id + " to undo.");
                        return;
                    }
                    verb = "Put back the original of ";
                }
                default -> {
                    fail(player, "Unknown action: " + action);
                    return;
                }
            }
        } catch (IOException e) {
            WorldForge.LOGGER.error("World Forge file error", e);
            fail(player, "File error: " + e.getMessage());
            return;
        }

        History.add(player.getGameProfile().getName(), action, id.toString(), before, written);
        WorldForge.LOGGER.info("{} did {} on {}", player.getGameProfile().getName(), action, id);
        ok(player, verb + id + ". Reloading...");
        reload(server);
    }

    private static Path fileFor(ResourceLocation id) {
        Path base = PackSetup.packDir().toAbsolutePath().normalize();
        Path file = base.resolve("data").resolve(id.getNamespace()).resolve("recipe")
                .resolve(id.getPath() + ".json").normalize();
        return file.startsWith(base) ? file : null;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    // ---------------------------------------------------------------- anvil / grindstone

    private static boolean isStation(String station) {
        return station.equals("anvil") || station.equals("grindstone");
    }

    private static boolean itemExists(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String withStation(String station, StationData.Pair p) {
        JsonObject o = GSON.toJsonTree(p).getAsJsonObject();
        o.addProperty("station", station);
        return GSON.toJson(o);
    }

    private static void broadcastStations() {
        PacketDistributor.sendToAllPlayers(new ServerMessagePayload("STATION_SYNC", "", StationData.toSyncJson()));
    }

    private static void stationSave(ServerPlayer player, MinecraftServer server, String station, String json) {
        if (!isStation(station)) {
            fail(player, "Unknown station: " + station);
            return;
        }
        StationData.Pair p;
        try {
            p = GSON.fromJson(json, StationData.Pair.class);
        } catch (JsonParseException e) {
            fail(player, "That entry could not be read: " + e.getMessage());
            return;
        }
        if (p == null) {
            fail(player, "Empty entry.");
            return;
        }
        p.mode = "BLOCK".equals(p.mode) ? "BLOCK" : "ADD";
        if (!itemExists(p.left) || !itemExists(p.right)) {
            fail(player, "Both input items must be real items.");
            return;
        }
        if (p.mode.equals("ADD") && !itemExists(p.result)) {
            fail(player, "The result must be a real item.");
            return;
        }
        p.count = clamp(p.count, 1, 64);
        p.cost = clamp(p.cost, 1, 1000);
        p.xp = clamp(p.xp, 0, 10000);
        if (p.id == null || p.id.isBlank()) {
            p.id = "c" + (System.currentTimeMillis() % 100000000L);
        }
        p.id = p.id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        String before = "";
        for (StationData.Pair old : StationData.snapshot(station)) {
            if (p.id.equals(old.id)) {
                before = withStation(station, old);
            }
        }
        StationData.put(station, p);
        History.add(player.getGameProfile().getName(), "STATION_SAVE", station + ":" + p.id, before, withStation(station, p));
        broadcastStations();
        ok(player, "Saved the " + station + " combination. Reloading...");
        reload(server);   // makes JEI look again
    }

    private static void stationRemove(ServerPlayer player, MinecraftServer server, String station, String entryId) {
        if (!isStation(station)) {
            fail(player, "Unknown station: " + station);
            return;
        }
        String before = "";
        for (StationData.Pair old : StationData.snapshot(station)) {
            if (entryId.equals(old.id)) {
                before = withStation(station, old);
            }
        }
        if (StationData.remove(station, entryId)) {
            History.add(player.getGameProfile().getName(), "STATION_REMOVE", station + ":" + entryId, before, "");
            broadcastStations();
            ok(player, "Removed that " + station + " combination. Reloading...");
            reload(server);
        } else {
            fail(player, "That combination no longer exists.");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Same as typing /reload. */
    public static void reload(MinecraftServer server) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
    }

    public static void send(ServerPlayer player, String kind, String id, String data) {
        PacketDistributor.sendToPlayer(player, new ServerMessagePayload(kind, id, data));
    }

    public static void ok(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[World Forge] " + message));
        send(player, "OK", "", message);
    }

    public static void fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[World Forge] " + message));
        send(player, "FAIL", "", message);
    }
}
