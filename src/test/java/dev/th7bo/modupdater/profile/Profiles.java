package dev.th7bo.modupdater.profile;

import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.instance.ModInventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fixtures for the profile tests: a config file, and inventories to resolve against. */
final class Profiles {

    private Profiles() {
    }

    /**
     * The shape a SkyBlock pack would actually use — a few groups, profiles
     * composed from them, and one profile that takes everything.
     */
    static final String SKYBLOCK = """
            {
              "groups": {
                "base":        ["fabric-api", "skyhanni"],
                "performance": ["sodium", "lithium"],
                "qol":         ["firmament"],
                "dungeons":    ["bettermap", "dungeonrooms"],
                "mining":      ["coleweight"]
              },
              "profiles": {
                "general": {
                  "description": "Normal SkyBlock gameplay",
                  "include": ["base", "performance", "qol"]
                },
                "dungeons": {
                  "description": "Dungeon mods on",
                  "include": ["base", "performance", "qol", "dungeons"]
                },
                "mining": {
                  "description": "Mining mods on",
                  "include": ["base", "performance", "mining"],
                  "add": ["skyblockcollectiontracker"]
                },
                "lite": {
                  "description": "Maximum FPS",
                  "include": ["base", "performance"],
                  "ungrouped": "disable"
                },
                "everything": {
                  "description": "Every installed mod",
                  "includeAll": true
                }
              }
            }
            """;

    static ProfileConfig config(String json) {
        return new com.google.gson.Gson().fromJson(json, Document.class).toConfig();
    }

    static ProfileConfig skyblock() {
        return config(SKYBLOCK);
    }

    static Path write(Path stateDir, String json) throws IOException {
        Files.createDirectories(stateDir);
        Path file = ProfileConfig.fileIn(stateDir);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    /** An inventory of identified mods, without touching a filesystem. */
    static ModInventory inventory(List<String> active, List<String> inactive) {
        return ModInventory.of(mods(active, "mods"), mods(inactive, "inactive"));
    }

    private static List<InstalledMod> mods(List<String> modIds, String folder) {
        List<InstalledMod> mods = new ArrayList<>();
        for (String modId : modIds) {
            String filename = modId + "-1.0.0.jar";
            mods.add(new InstalledMod(
                    Path.of(folder, filename), filename, modId, "1.0.0", "sha-" + modId));
        }
        return mods;
    }

    /** Mirrors {@link ProfileConfig}'s private document shape, for building one in a test. */
    private static final class Document {
        java.util.Map<String, Profile> profiles;
        java.util.Map<String, List<String>> groups;

        ProfileConfig toConfig() {
            return ProfileConfig.of(profiles, groups);
        }
    }
}
