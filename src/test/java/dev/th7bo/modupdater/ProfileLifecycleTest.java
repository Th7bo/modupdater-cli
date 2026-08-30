package dev.th7bo.modupdater;

import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.profile.ProfileState;
import dev.th7bo.modupdater.util.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The feature end to end, through {@code Main} — including the case that matters
 * most, which is the instance that never asked for any of this.
 */
class ProfileLifecycleTest {

    private static final String PROFILES = """
            {
              "groups": {
                "base":     ["fabric-api", "skyhanni"],
                "dungeons": ["bettermap"],
                "mining":   ["coleweight"]
              },
              "profiles": {
                "general":  { "description": "Normal play", "include": ["base"] },
                "dungeons": { "description": "Dungeons",    "include": ["base", "dungeons"] },
                "mining":   { "description": "Mining",      "include": ["base", "mining"] },
                "everything": { "description": "All of it", "includeAll": true }
              }
            }
            """;

    @AfterEach
    void cleanup() {
        Log.reset();
    }

    /** An instance with four mods, all active, and no server configured. */
    private static ModPaths instance(Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        for (String modId : List.of("fabric-api", "skyhanni", "bettermap", "coleweight")) {
            Jars.modJar(mods, modId + ".jar", modId, "1.0.0");
        }
        return ModPaths.of(mods);
    }

    private static void properties(Path dir, String contents) throws IOException {
        Files.writeString(dir.resolve("modupdater.properties"), contents, StandardCharsets.UTF_8);
    }

    private static List<String> namesIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".jar"))
                    .sorted()
                    .toList();
        }
    }

    // ── The instance that never opted in ────────────────────────────────────

    @Test
    void movesNothingWhenThePropertiesFileSaysNothingAboutProfiles(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        properties(dir, "mc.version=1.21.4\n");

        assertEquals(0, Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()}));

        assertEquals(
                List.of("bettermap.jar", "coleweight.jar", "fabric-api.jar", "skyhanni.jar"),
                namesIn(paths.modsDir()));
    }

    @Test
    void createsNoProfileDirectoriesWhenTheFeatureIsOff(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);

        Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()});

        assertFalse(Files.exists(paths.inactiveDir()), "storage must not appear uninvited");
        assertFalse(Files.exists(paths.stateDir().resolve(ProfileState.FILE)),
                "no profile state should be written for an instance that has none");
    }

    @Test
    void needsNoProfilesFileWhenTheFeatureIsOff(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        properties(dir, "mc.version=1.21.4\n");

        // The point: an existing install updated to this version, with no profiles.json
        // anywhere, behaves exactly as it did before.
        assertEquals(0, Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()}));
        assertEquals(0, Main.run(new String[]{"apply", "--mods-dir", paths.modsDir().toString()}));
        assertEquals(4, namesIn(paths.modsDir()).size());
    }

    // ── The instance that did ───────────────────────────────────────────────

    @Test
    void appliesTheDefaultProfileWithNothingToPrompt(@TempDir Path dir) throws IOException {
        // Headless, which is how the test run sees it — the same path a launcher
        // with no display takes.
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, """
                profiles.enabled=true
                profile.default=dungeons
                profile.prompt=false
                """);

        assertEquals(0, Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()}));

        assertEquals(List.of("bettermap.jar", "fabric-api.jar", "skyhanni.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of("coleweight.jar"), namesIn(paths.inactiveDir()));
    }

    @Test
    void doesNotBlockTheLaunchWhenTheConfiguredProfileIsGone(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, """
                profiles.enabled=true
                profile.default=raiding
                profile.prompt=false
                """);

        assertEquals(0, Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()}));

        // Falls through to the first profile the file defines rather than failing.
        assertTrue(namesIn(paths.modsDir()).contains("skyhanni.jar"));
    }

    @Test
    void doesNotBlockTheLaunchWhenThereIsNoProfilesFileAtAll(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        properties(dir, """
                profiles.enabled=true
                profile.prompt=false
                """);

        assertEquals(0, Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()}));

        assertEquals(4, namesIn(paths.modsDir()).size(),
                "with nothing to filter by, every mod stays active");
    }

    @Test
    void doesNotBlockTheLaunchOnABrokenProfilesFile(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), "{ this is not json");
        properties(dir, """
                profiles.enabled=true
                profile.prompt=false
                """);

        assertEquals(0, Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()}));
        assertEquals(4, namesIn(paths.modsDir()).size());
    }

    @Test
    void remembersTheProfileItApplied(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, """
                profiles.enabled=true
                profile.default=mining
                profile.prompt=false
                profile.remember=true
                """);

        Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()});

        assertEquals("mining", ProfileState.read(paths.stateDir()).selected());
    }

    // ── The CLI ─────────────────────────────────────────────────────────────

    @Test
    void switchesProfileFromTheCommandLine(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, "profiles.enabled=true\n");

        assertEquals(0, Main.run(new String[]{
                "profile", "use", "mining", "--mods-dir", paths.modsDir().toString()}));

        assertEquals(List.of("coleweight.jar", "fabric-api.jar", "skyhanni.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of("bettermap.jar"), namesIn(paths.inactiveDir()));
    }

    @Test
    void switchingBackBringsEverythingHome(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, "profiles.enabled=true\n");
        String mods = paths.modsDir().toString();

        Main.run(new String[]{"profile", "use", "mining", "--mods-dir", mods});
        Main.run(new String[]{"profile", "use", "everything", "--mods-dir", mods});

        assertEquals(
                List.of("bettermap.jar", "coleweight.jar", "fabric-api.jar", "skyhanni.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of(), namesIn(paths.inactiveDir()));
    }

    // ── Turning it on and off ───────────────────────────────────────────────

    @Test
    void enableSwitchesTheFeatureOnAndLeavesAStarterConfig(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        properties(dir, "mc.version=1.21.4\n");

        assertEquals(0, Main.run(new String[]{
                "profile", "enable", "--mods-dir", paths.modsDir().toString()}));

        Config config = Config.resolve(new String[]{"--mods-dir", paths.modsDir().toString()});
        assertTrue(config.profilesEnabled());
        assertEquals("1.21.4", config.mcVersion(), "the rest of the file is untouched");
        assertTrue(Files.isRegularFile(paths.stateDir().resolve("profiles.json")));
    }

    @Test
    void enablingMovesNothingByItself(@TempDir Path dir) throws IOException {
        // The starter config puts every installed mod in one group, so switching
        // the feature on cannot change which mods the game loads.
        ModPaths paths = instance(dir);

        Main.run(new String[]{"profile", "enable", "--mods-dir", paths.modsDir().toString()});
        Main.run(new String[]{"check", "--mods-dir", paths.modsDir().toString()});

        assertEquals(
                List.of("bettermap.jar", "coleweight.jar", "fabric-api.jar", "skyhanni.jar"),
                namesIn(paths.modsDir()));
    }

    @Test
    void enableNeverOverwritesProfilesYouAlreadyHave(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);

        Main.run(new String[]{"profile", "enable", "--mods-dir", paths.modsDir().toString()});

        assertEquals(PROFILES, Files.readString(paths.stateDir().resolve("profiles.json")));
    }

    @Test
    void disableBringsStoredModsBackBeforeSwitchingOff(@TempDir Path dir) throws IOException {
        // Switching off first would strand them: the game would not load them, and
        // nothing moves mods in an instance with profiles disabled.
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, "profiles.enabled=true\n");
        String mods = paths.modsDir().toString();

        Main.run(new String[]{"profile", "use", "mining", "--mods-dir", mods});
        assertEquals(List.of("bettermap.jar"), namesIn(paths.inactiveDir()));

        assertEquals(0, Main.run(new String[]{"profile", "disable", "--mods-dir", mods}));

        assertEquals(
                List.of("bettermap.jar", "coleweight.jar", "fabric-api.jar", "skyhanni.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of(), namesIn(paths.inactiveDir()));
        assertFalse(Config.resolve(new String[]{"--mods-dir", mods}).profilesEnabled());
    }

    @Test
    void disableKeepsYourProfilesForNextTime(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, "profiles.enabled=true\n");

        Main.run(new String[]{"profile", "disable", "--mods-dir", paths.modsDir().toString()});

        assertEquals(PROFILES, Files.readString(paths.stateDir().resolve("profiles.json")));
    }

    @Test
    void turningItOffAndOnAgainIsSafe(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, "profiles.enabled=true\n");
        String mods = paths.modsDir().toString();

        Main.run(new String[]{"profile", "use", "mining", "--mods-dir", mods});
        Main.run(new String[]{"profile", "disable", "--mods-dir", mods});
        Main.run(new String[]{"profile", "enable", "--mods-dir", mods});
        Main.run(new String[]{"profile", "use", "dungeons", "--mods-dir", mods});

        assertEquals(List.of("bettermap.jar", "fabric-api.jar", "skyhanni.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of("coleweight.jar"), namesIn(paths.inactiveDir()));
    }

    @Test
    void refusesToActOnProfilesWhenTheInstanceHasNotEnabledThem(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);

        assertEquals(0, Main.run(new String[]{
                "profile", "use", "mining", "--mods-dir", paths.modsDir().toString()}));

        assertEquals(4, namesIn(paths.modsDir()).size(), "nothing should have moved");
        assertFalse(Files.exists(paths.inactiveDir()));
    }

    @Test
    void listingProfilesTouchesNothing(@TempDir Path dir) throws IOException {
        ModPaths paths = instance(dir);
        Files.createDirectories(paths.stateDir());
        Files.writeString(paths.stateDir().resolve("profiles.json"), PROFILES);
        properties(dir, "profiles.enabled=true\n");

        assertEquals(0, Main.run(new String[]{
                "profile", "list", "--mods-dir", paths.modsDir().toString()}));

        assertEquals(4, namesIn(paths.modsDir()).size());
        assertFalse(Files.exists(paths.inactiveDir()));
    }
}
