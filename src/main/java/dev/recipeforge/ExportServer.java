package dev.recipeforge;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Pack Export: zips everything World Forge changed into one file.
 * The zip contains config/worldforge/... so it can be dropped into an instance (or a modpack's
 * "overrides" folder) and everything works the same on the other side.
 */
public final class ExportServer {
    private ExportServer() {}

    public static void export(ServerPlayer player, MinecraftServer server) {
        Path config = FMLPaths.CONFIGDIR.get().resolve("worldforge");
        if (!Files.exists(config)) {
            ServerHandler.fail(player, "There is nothing to export yet: you have not changed anything.");
            return;
        }
        try {
            Path outDir = FMLPaths.GAMEDIR.get().resolve("worldforge-exports");
            Files.createDirectories(outDir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path zip = outDir.resolve("worldforge-export-" + stamp + ".zip");

            List<Path> files = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(config)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
            int count = 0;
            try (OutputStream raw = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(raw)) {
                for (Path f : files) {
                    String name = f.getFileName().toString();
                    if (name.equals("history.json")) {
                        continue;   // the history is personal; it is not needed in a shared pack
                    }
                    String rel = "config/worldforge/" + config.relativize(f).toString().replace('\\', '/');
                    out.putNextEntry(new ZipEntry(rel));
                    Files.copy(f, out);
                    out.closeEntry();
                    count++;
                }
                String readme = "World Forge export\n\n"
                        + "Copy the 'config' folder from this zip into your Minecraft instance folder\n"
                        + "(or into your modpack's 'overrides' folder) and install the World Forge mod.\n"
                        + "Everything you changed will be applied automatically.\n";
                out.putNextEntry(new ZipEntry("README.txt"));
                out.write(readme.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
            ServerHandler.send(player, "EXPORT_DONE", "", zip.toAbsolutePath().toString());
            ServerHandler.ok(player, "Exported " + count + " files to " + zip.toAbsolutePath());
        } catch (IOException e) {
            WorldForge.LOGGER.error("World Forge export failed", e);
            ServerHandler.fail(player, "Export failed: " + e.getMessage());
        }
    }
}
