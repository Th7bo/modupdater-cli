package dev.th7bo.modupdater.instance;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.th7bo.modupdater.util.Hashing;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Inventories the JARs already installed in an instance. */
public final class InstanceScanner {

    private static final String MOD_JSON = "fabric.mod.json";

    private final Gson gson = new Gson();

    public List<InstalledMod> scan(Path modsDir) {
        if (modsDir == null || !Files.isDirectory(modsDir)) {
            return List.of();
        }

        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(jars::add);
        } catch (IOException e) {
            Log.warn("could not list " + modsDir + ": " + e.getMessage());
            return List.of();
        }

        List<InstalledMod> mods = new ArrayList<>();
        for (Path jar : jars) {
            String filename = jar.getFileName().toString();

            String sha256;
            try {
                sha256 = Hashing.sha256(jar);
            } catch (IOException e) {
                // No hash means we can't reason about whether it's current, so it
                // is not safe to offer an update for it. Skip entirely.
                Log.warn("could not hash " + filename + ": " + e.getMessage());
                continue;
            }

            ModDescriptor descriptor = readDescriptor(jar);
            mods.add(new InstalledMod(jar, filename, descriptor.id(), descriptor.version(), sha256));
        }

        return List.copyOf(mods);
    }

    private ModDescriptor readDescriptor(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(MOD_JSON);
            if (entry == null) {
                return ModDescriptor.unknown();
            }

            try (InputStream in = zip.getInputStream(entry);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject json = gson.fromJson(reader, JsonObject.class);
                if (json == null) {
                    return ModDescriptor.unknown();
                }
                return new ModDescriptor(string(json, "id"), string(json, "version"));
            }
        } catch (IOException | JsonParseException e) {
            // A corrupt or non-ZIP file in mods/ is the user's business, not a
            // reason to abort the launch.
            Log.warn("could not read " + MOD_JSON + " from " + jar.getFileName() + ": " + e.getMessage());
            return ModDescriptor.unknown();
        }
    }

    private static String string(JsonObject json, String field) {
        var element = json.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    private record ModDescriptor(String id, String version) {
        static ModDescriptor unknown() {
            return new ModDescriptor(null, null);
        }
    }
}
