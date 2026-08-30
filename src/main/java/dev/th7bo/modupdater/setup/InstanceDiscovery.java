package dev.th7bo.modupdater.setup;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Finds Prism / MultiMC / Modrinth App instances so the installer can offer a
 * menu instead of asking non-technical users to find paths themselves.
 *
 * <p>Home and AppData are injectable so the search paths can be tested without
 * touching the real machine.
 */
public final class InstanceDiscovery {

    private final Path home;
    private final Path appData;
    private final Gson gson = new Gson();

    public InstanceDiscovery(Path home, Path appData) {
        this.home = home;
        this.appData = appData;
    }

    public static InstanceDiscovery forThisMachine() {
        String appData = System.getenv("APPDATA");
        return new InstanceDiscovery(home(), appData == null ? null : Path.of(appData));
    }

    /**
     * $HOME first, and only then the JVM's own idea of it.
     *
     * <p>On Linux {@code user.home} comes from the passwd entry and ignores
     * $HOME entirely, so a relocated home — a sandboxed shell, a Flatpak, an
     * account whose files live elsewhere — searched the wrong one and offered
     * instances belonging to the real home instead.
     */
    static Path home() {
        String fromEnv = System.getenv("HOME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }

        String fromEnvWindows = System.getenv("USERPROFILE");
        if (fromEnvWindows != null && !fromEnvWindows.isBlank()) {
            return Path.of(fromEnvWindows);
        }

        return Path.of(System.getProperty("user.home"));
    }

    public List<Instance> discover() {
        List<Instance> found = new ArrayList<>();

        for (Path root : prismRoots()) {
            found.addAll(scanPrism(root));
        }
        for (Path root : modrinthRoots()) {
            found.addAll(scanModrinth(root));
        }

        found.sort(Comparator.comparing(Instance::launcher).thenComparing(Instance::name));
        return List.copyOf(found);
    }

    List<Path> prismRoots() {
        List<Path> roots = new ArrayList<>();

        roots.add(home.resolve(".local/share/PrismLauncher/instances"));
        roots.add(home.resolve(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances"));
        roots.add(home.resolve("Library/Application Support/PrismLauncher/instances"));
        roots.add(home.resolve(".local/share/multimc/instances"));
        roots.add(home.resolve(".local/share/MultiMC/instances"));

        if (appData != null) {
            roots.add(appData.resolve("PrismLauncher/instances"));
            roots.add(appData.resolve("MultiMC/instances"));
        }

        return roots;
    }

    List<Path> modrinthRoots() {
        List<Path> roots = new ArrayList<>();

        roots.add(home.resolve(".local/share/ModrinthApp/profiles"));
        roots.add(home.resolve(".var/app/com.modrinth.theseus/data/ModrinthApp/profiles"));
        roots.add(home.resolve("Library/Application Support/ModrinthApp/profiles"));
        roots.add(home.resolve("Library/Application Support/theseus/profiles"));

        if (appData != null) {
            roots.add(appData.resolve("ModrinthApp/profiles"));
            roots.add(appData.resolve("com.modrinth.theseus/profiles"));
        }

        return roots;
    }

    private List<Instance> scanPrism(Path root) {
        List<Instance> instances = new ArrayList<>();

        for (Path dir : childDirectories(root)) {
            Path cfg = dir.resolve("instance.cfg");
            if (!Files.isRegularFile(cfg)) {
                continue;
            }

            Path gameDir = gameDirOf(dir);
            if (gameDir == null) {
                continue;
            }

            String launcher = root.toString().toLowerCase().contains("multimc") ? "MultiMC" : "Prism";
            instances.add(new Instance(
                    launcher,
                    prismName(cfg, dir),
                    minecraftVersion(dir.resolve("mmc-pack.json")),
                    gameDir,
                    cfg));
        }

        return instances;
    }

    private List<Instance> scanModrinth(Path root) {
        List<Instance> instances = new ArrayList<>();

        for (Path dir : childDirectories(root)) {
            // A Modrinth profile directory is the game directory itself.
            if (!Files.isDirectory(dir.resolve("mods")) && !Files.isRegularFile(dir.resolve("profile.json"))) {
                continue;
            }
            // Modrinth App stores the game version in app.db (SQLite), which we
            // deliberately do not open — the installer asks instead.
            instances.add(new Instance("Modrinth", dir.getFileName().toString(), null, dir, null));
        }

        return instances;
    }

    /** Prism used {@code .minecraft}; newer instances use {@code minecraft}. Both occur side by side. */
    static Path gameDirOf(Path instanceDir) {
        Path dotted = instanceDir.resolve(".minecraft");
        if (Files.isDirectory(dotted)) {
            return dotted;
        }
        Path plain = instanceDir.resolve("minecraft");
        if (Files.isDirectory(plain)) {
            return plain;
        }
        return null;
    }

    private static String prismName(Path cfg, Path dir) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            return dir.getFileName().toString();
        }

        String name = properties.getProperty("name");
        return name == null || name.isBlank() ? dir.getFileName().toString() : name;
    }

    /** Reads the {@code net.minecraft} component version out of Prism's mmc-pack.json. */
    String minecraftVersion(Path packFile) {
        if (!Files.isRegularFile(packFile)) {
            return null;
        }

        try {
            String json = Files.readString(packFile, StandardCharsets.UTF_8);
            JsonObject root = gson.fromJson(json, JsonObject.class);
            if (root == null) {
                return null;
            }

            JsonElement components = root.get("components");
            if (components == null || !components.isJsonArray()) {
                return null;
            }

            JsonArray array = components.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject component = element.getAsJsonObject();
                JsonElement uid = component.get("uid");
                if (uid == null || !uid.isJsonPrimitive() || !"net.minecraft".equals(uid.getAsString())) {
                    continue;
                }
                JsonElement version = component.get("version");
                if (version != null && version.isJsonPrimitive()) {
                    return version.getAsString();
                }
            }
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return null;
        }

        return null;
    }

    private static List<Path> childDirectories(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (var stream = Files.list(root)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
