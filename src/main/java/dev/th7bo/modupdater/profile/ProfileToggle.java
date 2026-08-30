package dev.th7bo.modupdater.profile;

import com.google.gson.GsonBuilder;
import dev.th7bo.modupdater.Config;
import dev.th7bo.modupdater.PropertiesFile;
import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Switching the feature on and off for one instance.
 *
 * <p>Here rather than in the command that first needed it, because the manager
 * window offers the same switch. Turning profiles off has to put every stored
 * mod back before it flips the property, and that is not a rule worth having two
 * copies of.
 */
public final class ProfileToggle {

    private ProfileToggle() {
    }

    public sealed interface Result {

        /** @param starterConfig the config written for a first-time user, or null */
        record Enabled(Path properties, Path profilesFile, Path starterConfig) implements Result {
        }

        record AlreadyEnabled(Path profilesFile) implements Result {
        }

        /** @param restored how many stored mods were put back into {@code mods/} */
        record Disabled(Path properties, Path profilesFile, int restored) implements Result {
        }

        record AlreadyDisabled() implements Result {
        }

        record Failed(String detail) implements Result {
        }
    }

    /**
     * Switches profiles on, and writes a starter {@code profiles.json} when there
     * is none. The starter puts every installed mod in one group, so enabling the
     * feature cannot change which mods load — only make it possible to.
     */
    public static Result enable(Config config) {
        Path properties = config.propertiesFile();
        if (properties == null) {
            return new Result.Failed("could not work out where modupdater.properties belongs from "
                    + config.modsDir());
        }

        ModPaths paths = ModPaths.of(config.modsDir());
        Path profilesFile = ProfileConfig.fileIn(paths.stateDir());

        if (config.profilesEnabled() && Files.isRegularFile(profilesFile)) {
            return new Result.AlreadyEnabled(profilesFile);
        }

        if (!config.profilesEnabled()) {
            try {
                PropertiesFile.set(properties, "profiles.enabled", "true");
            } catch (IOException e) {
                return new Result.Failed("could not write " + properties + ": " + message(e));
            }
        }

        // Never overwritten. Somebody's groups are not ours to replace, and this
        // is the command they would run again after forgetting they had.
        if (Files.isRegularFile(profilesFile)) {
            return new Result.Enabled(properties, profilesFile, null);
        }

        try {
            writeStarterConfig(profilesFile, ModInventory.scan(paths, new InstanceScanner()));
        } catch (IOException e) {
            return new Result.Failed("could not write " + profilesFile + ": " + message(e));
        }

        return new Result.Enabled(properties, profilesFile, profilesFile);
    }

    /**
     * Switches profiles off, after bringing every stored mod back into
     * {@code mods/}.
     *
     * <p>Order matters. Flipping the property first would strand whatever the
     * current profile had switched off: the game does not read the storage folder,
     * and an instance with profiles off is one this program does not rearrange, so
     * nothing would ever move them back.
     */
    public static Result disable(Config config) {
        Path properties = config.propertiesFile();
        if (properties == null) {
            return new Result.Failed("could not work out where modupdater.properties belongs from "
                    + config.modsDir());
        }

        if (!config.profilesEnabled()) {
            return new Result.AlreadyDisabled();
        }

        ModPaths paths = ModPaths.of(config.modsDir());
        ModInventory inventory = ModInventory.scan(paths, new InstanceScanner());
        int restored = 0;

        if (!inventory.inactive().isEmpty()) {
            ProfilePlan plan = ProfilePlan.of(paths, inventory, ProfileResolver.everything(inventory));
            ProfileManager.Result result = new ProfileManager(paths).apply(plan);

            if (result instanceof ProfileManager.Result.Failed failed) {
                // Leave the feature on: it is the only thing that can tidy this up.
                return new Result.Failed("could not bring every mod back into mods/: "
                        + failed.detail() + " — profiles are still enabled");
            }
            restored = plan.activate().size();
        }

        try {
            PropertiesFile.set(properties, "profiles.enabled", "false");
        } catch (IOException e) {
            return new Result.Failed("could not write " + properties + ": " + message(e));
        }

        return new Result.Disabled(properties, ProfileConfig.fileIn(paths.stateDir()), restored);
    }

    /**
     * One group holding everything installed, and two profiles that both amount to
     * "all of it" — a starting point that changes nothing until it is split up.
     */
    static void writeStarterConfig(Path file, ModInventory inventory) throws IOException {
        Map<String, Object> profiles = new LinkedHashMap<>();
        profiles.put("general", Map.of(
                "description", "Everything you have now",
                "include", List.of("base")));
        profiles.put("everything", Map.of(
                "description", "Every installed mod",
                "includeAll", true));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("groups", Map.of("base", List.copyOf(inventory.modIds())));
        document.put("profiles", profiles);

        Files.createDirectories(file.getParent());
        Files.writeString(file,
                new GsonBuilder().setPrettyPrinting().create().toJson(document) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
