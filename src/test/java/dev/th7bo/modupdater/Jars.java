package dev.th7bo.modupdater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds mod JARs on the fly so no binary fixtures need committing. */
public final class Jars {

    private Jars() {
    }

    public static Path modJar(Path dir, String filename, String modId, String version) throws IOException {
        String json = """
                {
                  "schemaVersion": 1,
                  "id": "%s",
                  "version": "%s",
                  "name": "%s",
                  "depends": { "minecraft": "1.21.4" }
                }
                """.formatted(modId, version, modId);
        return jar(dir, filename, "fabric.mod.json", json);
    }

    public static Path jar(Path dir, String filename, String entryName, String content) throws IOException {
        Path path = dir.resolve(filename);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    public static Path emptyJar(Path dir, String filename) throws IOException {
        Path path = dir.resolve(filename);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return path;
    }

    public static Path corruptJar(Path dir, String filename) throws IOException {
        Path path = dir.resolve(filename);
        Files.writeString(path, "this is definitely not a zip archive");
        return path;
    }
}
