package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Runs on the server: checks permissions, reads/writes the overlay files, reloads. */
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
                case "REAPPLY" -> reapply(player, server, payload.json());
                case "SAVE", "DISABLE", "RESTORE" ->
                        edit(player, server, payload.action(), payload.recipeId(), payload.json());
                default -> fail(player, "Unknown action: " + payload.action());
            }
        });
    }

    // ---------------------------------------------------------------- reading

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
            o.addProperty("id", e.id);
            array.add(o);
        }
        send(player, "HISTORY", "", array.toString());
    }

    // ---------------------------------------------------------------- writing

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
            RecipeForge.LOGGER.error("Recipe Forge file error", e);
            fail(player, "File error: " + e.getMessage());
            return;
        }

        History.add(player.getGameProfile().getName(), action, id.toString(), written);
        RecipeForge.LOGGER.info("{} did {} on {}", player.getGameProfile().getName(), action, id);
        ok(player, verb + id + ". Reloading...");
        // Same as typing /reload. The server re-reads all recipes and re-sends them to players.
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
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

    // ---------------------------------------------------------------- replies

    private static void send(ServerPlayer player, String kind, String id, String data) {
        PacketDistributor.sendToPlayer(player, new ServerMessagePayload(kind, id, data));
    }

    private static void ok(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Recipe Forge] " + message));
        send(player, "OK", "", message);
    }

    private static void fail(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Recipe Forge] " + message));
        send(player, "FAIL", "", message);
    }
}
