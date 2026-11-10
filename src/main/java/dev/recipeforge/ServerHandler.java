package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Runs on the server: checks permissions, writes the overlay file, reloads. */
public final class ServerHandler {
    private ServerHandler() {}

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
                reply(player, "Creative mode only.");
                return;
            }
            if (server.isDedicatedServer() && !player.hasPermissions(2)) {
                reply(player, "You need operator permission on this server.");
                return;
            }

            ResourceLocation id = ResourceLocation.tryParse(payload.recipeId());
            if (id == null) {
                reply(player, "Not a valid recipe id: " + payload.recipeId());
                return;
            }

            Path file = fileFor(id);
            if (file == null) {
                reply(player, "Recipe id rejected for safety: " + id);
                return;
            }

            try {
                switch (payload.action()) {
                    case "SAVE" -> {
                        JsonElement json;
                        try {
                            json = JsonParser.parseString(payload.json());
                        } catch (JsonParseException e) {
                            reply(player, "That is not valid JSON: " + e.getMessage());
                            return;
                        }
                        if (!json.isJsonObject()) {
                            reply(player, "The recipe must be a JSON object { ... }.");
                            return;
                        }
                        RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
                        DataResult<Recipe<?>> parsed = Recipe.CODEC.parse(ops, json);
                        if (parsed.error().isPresent()) {
                            reply(player, "Recipe rejected: " + parsed.error().get().message());
                            return;
                        }
                        write(file, new GsonBuilder().setPrettyPrinting().create().toJson(json));
                        reply(player, "Saved " + id + ". Reloading...");
                    }
                    case "DISABLE" -> {
                        write(file, DISABLED_JSON);
                        reply(player, "Disabled " + id + ". Reloading...");
                    }
                    case "RESTORE" -> {
                        if (Files.deleteIfExists(file)) {
                            reply(player, "Restored original " + id + ". Reloading...");
                        } else {
                            reply(player, "No edit found for " + id + " (nothing to restore).");
                            return;
                        }
                    }
                    default -> {
                        reply(player, "Unknown action: " + payload.action());
                        return;
                    }
                }
            } catch (IOException e) {
                RecipeForge.LOGGER.error("Recipe Forge file error", e);
                reply(player, "File error: " + e.getMessage());
                return;
            }

            RecipeForge.LOGGER.info("{} did {} on {}", player.getGameProfile().getName(), payload.action(), id);
            // Same as typing /reload. The server re-reads all recipes and re-sends them to players.
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        });
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

    private static void reply(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal("[Recipe Forge] " + message));
    }
}
