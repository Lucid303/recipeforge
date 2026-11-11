package dev.recipeforge;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Registers an always-on, top-priority data pack that lives in
 * config/recipeforge/pack. Recipe edits are written there as ordinary datapack files.
 */
public final class PackSetup {
    private PackSetup() {}

    public static Path packDir() {
        return FMLPaths.CONFIGDIR.get().resolve("worldforge").resolve("pack");
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
            WorldForge.LOGGER.error("Recipe Forge could not create its pack folder", e);
            return;
        }

        PackLocationInfo info = new PackLocationInfo(
                "worldforge_overlay",
                Component.literal("World Forge Overlay"),
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

        final Pack pack;
        try {
            pack = createPack(info, supplier);
        } catch (Exception e) {
            WorldForge.LOGGER.error("Recipe Forge overlay pack could not be created", e);
            return;
        }

        if (pack != null) {
            event.addRepositorySource(consumer -> consumer.accept(pack));
        } else {
            WorldForge.LOGGER.error("Recipe Forge overlay pack could not be created (no matching Pack method found)");
        }
    }

    /**
     * Calls Pack.readMetaAndCreate by reflection, because the exact class used for its
     * last argument differs between Minecraft versions.
     */
    private static Pack createPack(PackLocationInfo info, Pack.ResourcesSupplier supplier) throws Exception {
        for (Method m : Pack.class.getDeclaredMethods()) {
            if (!m.getName().equals("readMetaAndCreate") || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 4 && p[0] == PackLocationInfo.class && p[2] == PackType.class) {
                WorldForge.LOGGER.info("Recipe Forge: using Pack method with last argument type {}", p[3].getName());
                Object config = buildConfig(p[3]);
                if (config != null) {
                    m.setAccessible(true);
                    return (Pack) m.invoke(null, info, supplier, PackType.SERVER_DATA, config);
                }
            }
        }
        return null;
    }

    private static Object buildConfig(Class<?> type) throws Exception {
        if (type == Pack.Position.class) {
            return Pack.Position.TOP;
        }
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] a = c.getParameterTypes();
            if (a.length == 3 && a[0] == boolean.class && a[1] == Pack.Position.class && a[2] == boolean.class) {
                c.setAccessible(true);
                return c.newInstance(true, Pack.Position.TOP, false);
            }
        }
        return null;
    }
}
