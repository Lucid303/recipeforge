package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.PackSelectionConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Registers an always-on, top-priority data pack that lives in
 * config/recipeforge/pack. Recipe edits are written there as ordinary datapack files.
 */
public final class PackSetup {
    private PackSetup() {}

    public static Path packDir() {
        return FMLPaths.CONFIGDIR.get().resolve("recipeforge").resolve("pack");
    }

    public static void addPacks(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        final Path dir = packDir();
        try {
            Files.createDirectories(dir);
            Path meta = dir.resolve("pack.mcmeta");
            if (!Files.exists(meta)) {
                Files.writeString(meta,
                        "{\"pack\":{\"pack_format\":48,\"description\":\"Recipe Forge overlay\"}}");
            }
        } catch (IOException e) {
            RecipeForge.LOGGER.error("Recipe Forge could not create its pack folder", e);
            return;
        }

        PackLocationInfo info = new PackLocationInfo(
                "recipeforge_overlay",
                Component.literal("Recipe Forge Overlay"),
                PackSource.BUILT_IN,
                Optional.empty());

        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new PathPackResources(location, dir);
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return new PathPackResources(location, dir);
            }
        };

        Pack pack = Pack.readMetaAndCreate(
                info,
                supplier,
                PackType.SERVER_DATA,
                new PackSelectionConfig(true, Pack.Position.TOP, false));

        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        } else {
            RecipeForge.LOGGER.error("Recipe Forge overlay pack could not be created (bad pack.mcmeta?)");
        }
    }
}
