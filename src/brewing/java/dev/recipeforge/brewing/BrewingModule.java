package dev.recipeforge.brewing;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.recipeforge.History;
import dev.recipeforge.Modules;
import dev.recipeforge.WorldForge;
import dev.recipeforge.ServerHandler;
import dev.recipeforge.ServerMessagePayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server side of the optional brewing stand support. */
public final class BrewingModule {
    private BrewingModule() {}

    private static final Gson GSON = new Gson();

    /** Called by Recipe Forge when this module is part of the jar. */
    public static void init() {
        Modules.addServer(BrewingModule::handle);
        NeoForge.EVENT_BUS.addListener(BrewingModule::onServerStarted);
        NeoForge.EVENT_BUS.addListener(BrewingModule::onLogin);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        try {
            PotionBrewing brewing = event.getServer().overworld().potionBrewing();
            BrewingAccess.apply(brewing, new BrewingData.Store(), BrewingData.get());
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge could not apply brewing changes", e);
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerHandler.send(player, "BREW_SYNC", "", BrewingData.toJson());
        }
    }

    private static boolean handle(String action, ServerPlayer player, MinecraftServer server, String id, String data) {
        switch (action) {
            case "BREW_ADD", "BREW_REMOVE", "BREW_RESTORE", "BREW_REPLACE" -> {
                run(action, player, server, data);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean validPotion(String id) {
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.POTION.containsKey(rl);
    }

    private static boolean validNewMix(BrewingData.MixRef m) {
        if (m == null || !validPotion(m.from) || !validPotion(m.to) || m.ingredient == null) {
            return false;
        }
        ResourceLocation item = ResourceLocation.tryParse(m.ingredient);
        return item != null && BuiltInRegistries.ITEM.containsKey(item);
    }

    private static void addMix(PotionBrewing brewing, BrewingData.MixRef m) {
        if (BrewingData.removedRemove(m)) {
            BrewingAccess.add(brewing, m);   // putting back a vanilla mix
            return;
        }
        if (BrewingAccess.add(brewing, m)) {
            BrewingData.addedAdd(m);
        }
    }

    private static void removeMix(PotionBrewing brewing, BrewingData.MixRef m) {
        if (BrewingData.addedRemove(m)) {
            BrewingAccess.remove(brewing, m);
            return;
        }
        if (BrewingAccess.remove(brewing, m)) {
            BrewingData.removedAdd(m);
        }
    }

    private static void run(String action, ServerPlayer player, MinecraftServer server, String data) {
        PotionBrewing brewing = server.overworld().potionBrewing();
        String label;
        try {
            switch (action) {
                case "BREW_ADD" -> {
                    BrewingData.MixRef m = GSON.fromJson(data, BrewingData.MixRef.class);
                    if (!validNewMix(m)) {
                        ServerHandler.fail(player, "That brew is not valid.");
                        return;
                    }
                    addMix(brewing, m);
                    label = m.key();
                }
                case "BREW_REMOVE" -> {
                    BrewingData.MixRef m = GSON.fromJson(data, BrewingData.MixRef.class);
                    if (m == null || m.from == null || m.to == null || m.ingredient == null) {
                        ServerHandler.fail(player, "That brew is not valid.");
                        return;
                    }
                    removeMix(brewing, m);
                    label = m.key();
                }
                case "BREW_RESTORE" -> {
                    BrewingData.MixRef m = GSON.fromJson(data, BrewingData.MixRef.class);
                    if (m == null || m.from == null || m.to == null || m.ingredient == null) {
                        ServerHandler.fail(player, "That brew is not valid.");
                        return;
                    }
                    addMix(brewing, m);
                    label = m.key();
                }
                default -> {
                    JsonObject o = JsonParser.parseString(data).getAsJsonObject();
                    BrewingData.MixRef oldMix = GSON.fromJson(o.get("old"), BrewingData.MixRef.class);
                    BrewingData.MixRef newMix = GSON.fromJson(o.get("new"), BrewingData.MixRef.class);
                    if (oldMix == null || !validNewMix(newMix)) {
                        ServerHandler.fail(player, "That brew is not valid.");
                        return;
                    }
                    removeMix(brewing, oldMix);
                    addMix(brewing, newMix);
                    label = newMix.key();
                }
            }
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge brewing error", e);
            ServerHandler.fail(player, "Brewing error: " + e.getMessage());
            return;
        }
        BrewingData.save();
        History.add(player.getGameProfile().getName(), action, "brewing:" + label, data);
        // Tell every player, then reload so JEI refreshes its brewing list.
        PacketDistributor.sendToAllPlayers(new ServerMessagePayload("BREW_SYNC", "", BrewingData.toJson()));
        ServerHandler.ok(player, "Brewing updated. Reloading...");
        ServerHandler.reload(server);
    }
}
