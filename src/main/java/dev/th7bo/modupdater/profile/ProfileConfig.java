package dev.th7bo.modupdater.profile;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code .modupdater/profiles.json} — the groups this instance defines and the
 * profiles built out of them.
 *
 * <pre>
 * {
 *   "groups": {
 *     "base":        ["fabric-api", "skyhanni"],
 *     "performance": ["sodium", "lithium"],
 *     "mining":      ["coleweight", "skyblockcollectiontracker"]
 *   },
 *   "profiles": {
 *     "general": { "include": ["base", "performance"] },
 *     "mining":  { "include": ["base", "performance", "mining"] }
 *   }
 * }
 * </pre>
 *
 * <p>Hand-edited, so every read is defensive: a missing file, a broken file and
 * an empty file all produce a config with no profiles rather than an error. A
 * launch is never blocked by a typo in here.
 */
public final class ProfileConfig {

    public static final String FILE = "profiles.json";

    /** The name of the built-in fallback: every installed mod active. */
    public static final String EVERYTHING = "everything";

    private final Map<String, Profile> profiles;
    private final Map<String, List<String>> groups;

    private ProfileConfig(Map<String, Profile> profiles, Map<String, List<String>> groups) {
        // LinkedHashMap, not Map.copyOf: the file's order is the order the picker
        // and `profile list` show, and Map.copyOf does not promise to keep it.
        this.profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        this.groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups));
    }

    public static ProfileConfig empty() {
        return new ProfileConfig(Map.of(), Map.of());
    }

    public static ProfileConfig of(Map<String, Profile> profiles, Map<String, List<String>> groups) {
        return new ProfileConfig(
                normaliseProfiles(profiles == null ? Map.of() : profiles),
                normaliseGroups(groups == null ? Map.of() : groups));
    }

    public static Path fileIn(Path stateDir) {
        return stateDir.resolve(FILE);
    }

    /** @return the profiles on disk, or an empty config when there are none to read */
    public static ProfileConfig read(Path stateDir) {
        Path file = fileIn(stateDir);
        if (!Files.isRegularFile(file)) {
            return empty();
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Document document = new Gson().fromJson(json, Document.class);
            if (document == null) {
                return empty();
            }
            return of(document.profiles, document.groups);
        } catch (IOException | JsonParseException e) {
            Log.warn("could not read " + FILE + ": " + e.getMessage() + " — no profiles will be offered");
            return empty();
        }
    }

    public boolean isEmpty() {
        return profiles.isEmpty();
    }

    /** Profile names in the order the file lists them. */
    public List<String> names() {
        return List.copyOf(profiles.keySet());
    }

    public Map<String, List<String>> groups() {
        return groups;
    }

    public Optional<Profile> profile(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(profiles.get(normalise(name)));
    }

    public boolean has(String name) {
        return profile(name).isPresent();
    }

    /** The mod ids behind a list of group names. Unknown groups contribute nothing. */
    public Set<String> expand(List<String> groupNames) {
        Set<String> ids = new LinkedHashSet<>();
        for (String group : groupNames) {
            ids.addAll(groups.getOrDefault(normalise(group), List.of()));
        }
        return ids;
    }

    /**
     * Mods this config has an opinion about — anything in a group, or named by a
     * profile. What is left over is unmanaged, and stays active unless a profile
     * explicitly asks otherwise.
     */
    public Set<String> managedModIds() {
        Set<String> managed = new LinkedHashSet<>();
        groups.values().forEach(managed::addAll);
        for (Profile profile : profiles.values()) {
            managed.addAll(profile.add());
            managed.addAll(profile.remove());
        }
        return managed;
    }

    /** Lower-cased and trimmed, so a profile naming {@code SkyHanni} still matches. */
    public static String normalise(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Profile> normaliseProfiles(Map<String, Profile> raw) {
        Map<String, Profile> byName = new LinkedHashMap<>();
        raw.forEach((name, profile) -> {
            if (name == null || name.isBlank() || profile == null) {
                return;
            }
            byName.put(normalise(name), profile);
        });
        return byName;
    }

    private static Map<String, List<String>> normaliseGroups(Map<String, List<String>> raw) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        raw.forEach((name, members) -> {
            if (name == null || name.isBlank()) {
                return;
            }
            byName.put(normalise(name), Profile.clean(members));
        });
        return byName;
    }

    /** The file's shape. Gson fills these in; nothing else touches them. */
    private static final class Document {
        Map<String, Profile> profiles;
        Map<String, List<String>> groups;
    }
}
