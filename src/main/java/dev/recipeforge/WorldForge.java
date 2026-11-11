package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Common (client + server) entry point of World Forge. */
@Mod(WorldForge.MODID)
public class WorldForge {
    public static final String MODID = "worldforge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldForge(IEventBus modBus) {
        migrateOldConfig();
        modBus.addListener(PackSetup::addPacks);
        modBus.addListener(Network::register);
        NeoForge.EVENT_BUS.addListener(StationEvents::onAnvil);
        NeoForge.EVENT_BUS.addListener(StationEvents::onGrindstone);
        NeoForge.EVENT_BUS.addListener(WorldForge::onLogin);
        ModRecipes.SERIALIZERS.register(modBus);
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BiomeServer.sendTo(player);
            ServerHandler.send(player, "STATION_SYNC", "", StationData.toSyncJson());
        }
    }

    /** Copies the old config/recipeforge folder to config/worldforge the first time. */
    private static void migrateOldConfig() {
        try {
            Path oldDir = FMLPaths.CONFIGDIR.get().resolve("recipeforge");
            Path newDir = FMLPaths.CONFIGDIR.get().resolve("worldforge");
            if (!Files.exists(oldDir) || Files.exists(newDir)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(oldDir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path target = newDir.resolve(oldDir.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    }
                }
            }
            LOGGER.info("World Forge copied your old Recipe Forge settings to config/worldforge");
        } catch (IOException e) {
            LOGGER.error("World Forge could not copy the old settings", e);
        }
    }
}
