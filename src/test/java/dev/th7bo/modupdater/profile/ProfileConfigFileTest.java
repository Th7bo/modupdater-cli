package dev.th7bo.modupdater.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The group subcommands' half: editing the config from the command line. */
class ProfileConfigFileTest {

    private static final String CONFIG = """
            {
              "groups": {
                "base":     ["fabric-api", "skyhanni"],
                "dungeons": ["bettermap"]
              },
              "profiles": {
                "general": { "description": "Normal", "include": ["base"] }
              }
            }
            """;

    private static Path stateDir(Path dir, String json) throws IOException {
        Path state = Files.createDirectories(dir.resolve(".modupdater"));
        Files.writeString(ProfileConfig.fileIn(state), json, StandardCharsets.UTF_8);
        return state;
    }

    @Test
    void addsAModToAGroup(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);

        var result = ProfileConfigFile.addToGroup(state, "base", List.of("modupdater"));

        assertInstanceOf(ProfileConfigFile.Result.Changed.class, result);
        assertEquals(List.of("fabric-api", "skyhanni", "modupdater"),
                ProfileConfig.read(state).groups().get("base"));
    }

    @Test
    void createsTheGroupWhenItIsNew(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);

        ProfileConfigFile.addToGroup(state, "mining", List.of("coleweight"));

        assertEquals(List.of("coleweight"), ProfileConfig.read(state).groups().get("mining"));
    }

    @Test
    void addsSeveralAtOnce(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);

        var result = ProfileConfigFile.addToGroup(
                state, "dungeons", List.of("dungeonrooms", "skytils"));

        assertEquals(List.of("dungeonrooms", "skytils"),
                ((ProfileConfigFile.Result.Changed) result).affected());
        assertEquals(List.of("bettermap", "dungeonrooms", "skytils"),
                ProfileConfig.read(state).groups().get("dungeons"));
    }

    @Test
    void saysNothingChangedWhenItIsAlreadyThere(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);

        var result = ProfileConfigFile.addToGroup(state, "base", List.of("skyhanni"));

        assertInstanceOf(ProfileConfigFile.Result.Unchanged.class, result);
        assertEquals(List.of("fabric-api", "skyhanni"),
                ProfileConfig.read(state).groups().get("base"), "no duplicate");
    }

    @Test
    void removesAMod(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);

        ProfileConfigFile.removeFromGroup(state, "base", List.of("skyhanni"));

        assertEquals(List.of("fabric-api"), ProfileConfig.read(state).groups().get("base"));
    }

    @Test
    void refusesToRemoveFromAGroupThatDoesNotExist(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);

        var result = ProfileConfigFile.removeFromGroup(state, "mining", List.of("coleweight"));

        assertInstanceOf(ProfileConfigFile.Result.Failed.class, result);
    }

    @Test
    void keepsTheProfilesAndEverythingElseInTheFile(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, """
                {
                  "schemaVersion": 2,
                  "groups": { "base": ["skyhanni"] },
                  "profiles": { "general": { "description": "Normal", "include": ["base"] } }
                }
                """);

        ProfileConfigFile.addToGroup(state, "base", List.of("sodium"));

        String after = Files.readString(ProfileConfig.fileIn(state));
        assertTrue(after.contains("schemaVersion"), "a key this version does not model survives");

        ProfileConfig reread = ProfileConfig.read(state);
        assertEquals("Normal", reread.profile("general").orElseThrow().description());
        assertEquals(List.of("base"), reread.profile("general").orElseThrow().include());
    }

    @Test
    void normalisesTheNamesItIsGiven(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);

        ProfileConfigFile.addToGroup(state, "  BASE  ", List.of("  SkyBlockCollectionTracker "));

        assertEquals(List.of("fabric-api", "skyhanni", "skyblockcollectiontracker"),
                ProfileConfig.read(state).groups().get("base"));
    }

    @Test
    void saysSoWhenThereIsNoConfigYet(@TempDir Path dir) {
        var result = ProfileConfigFile.addToGroup(
                dir.resolve(".modupdater"), "base", List.of("skyhanni"));

        assertInstanceOf(ProfileConfigFile.Result.Failed.class, result);
        assertTrue(((ProfileConfigFile.Result.Failed) result).detail().contains("profile enable"));
    }

    @Test
    void doesNotWriteOverAFileItCannotParse(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, "{ not json at all");

        var result = ProfileConfigFile.addToGroup(state, "base", List.of("skyhanni"));

        assertInstanceOf(ProfileConfigFile.Result.Failed.class, result);
        assertEquals("{ not json at all", Files.readString(ProfileConfig.fileIn(state)));
    }
}
