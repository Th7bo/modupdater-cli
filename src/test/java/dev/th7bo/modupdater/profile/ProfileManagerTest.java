package dev.th7bo.modupdater.profile;

import dev.th7bo.modupdater.Jars;
import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Moving JARs between active and inactive storage. */
class ProfileManagerTest {

    @AfterEach
    void cleanup() {
        Log.reset();
    }

    private static ModPaths setUp(Path dir, List<String> active, List<String> inactive) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        ModPaths paths = ModPaths.of(mods);

        for (String modId : active) {
            Jars.modJar(mods, modId + "-1.0.0.jar", modId, "1.0.0");
        }
        if (!inactive.isEmpty()) {
            Files.createDirectories(paths.inactiveDir());
            for (String modId : inactive) {
                Jars.modJar(paths.inactiveDir(), modId + "-1.0.0.jar", modId, "1.0.0");
            }
        }
        return paths;
    }

    private static ModInventory scan(ModPaths paths) {
        return ModInventory.scan(paths, new InstanceScanner());
    }

    private static ProfileManager.Result apply(ModPaths paths, String profile) {
        ModInventory inventory = scan(paths);
        ProfileResolver.Resolution resolution =
                ProfileResolver.resolve(Profiles.skyblock(), profile, inventory);
        return new ProfileManager(paths).apply(ProfilePlan.of(paths, inventory, resolution));
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

    @Test
    void movesTheModsAProfileLeavesOutIntoStorage(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir,
                List.of("fabric-api", "skyhanni", "sodium", "lithium", "firmament", "coleweight"),
                List.of());

        apply(paths, "general");

        assertEquals(
                List.of("fabric-api-1.0.0.jar", "firmament-1.0.0.jar", "lithium-1.0.0.jar",
                        "skyhanni-1.0.0.jar", "sodium-1.0.0.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of("coleweight-1.0.0.jar"), namesIn(paths.inactiveDir()));
    }

    @Test
    void bringsThemBackWhenTheProfileWantsThemAgain(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir,
                List.of("fabric-api", "skyhanni", "sodium", "lithium", "firmament"),
                List.of("bettermap", "dungeonrooms", "coleweight"));

        apply(paths, "everything");

        assertEquals(
                List.of("bettermap-1.0.0.jar", "coleweight-1.0.0.jar", "dungeonrooms-1.0.0.jar",
                        "fabric-api-1.0.0.jar", "firmament-1.0.0.jar", "lithium-1.0.0.jar",
                        "skyhanni-1.0.0.jar", "sodium-1.0.0.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of(), namesIn(paths.inactiveDir()),
                "nothing should be left behind in storage");
    }

    @Test
    void switchingProfilesTradesOneSetForTheOther(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir,
                List.of("fabric-api", "skyhanni", "sodium", "lithium", "coleweight"),
                List.of("bettermap", "dungeonrooms", "firmament"));

        apply(paths, "dungeons");

        assertEquals(
                List.of("bettermap-1.0.0.jar", "dungeonrooms-1.0.0.jar", "fabric-api-1.0.0.jar",
                        "firmament-1.0.0.jar", "lithium-1.0.0.jar", "skyhanni-1.0.0.jar",
                        "sodium-1.0.0.jar"),
                namesIn(paths.modsDir()));
        assertEquals(List.of("coleweight-1.0.0.jar"), namesIn(paths.inactiveDir()));
    }

    @Test
    void neverDeletesAModItSwitchesOff(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir, List.of("fabric-api", "skyhanni", "coleweight"), List.of());

        apply(paths, "general");

        assertTrue(Files.isRegularFile(paths.inactivePath("coleweight-1.0.0.jar")),
                "a deactivated mod is moved, never discarded");
    }

    @Test
    void createsNoStorageDirectoryWhenNothingNeedsStoring(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir, List.of("fabric-api", "skyhanni"), List.of());

        ProfileManager.Result result = apply(paths, "general");

        assertInstanceOf(ProfileManager.Result.NothingToDo.class, result);
        assertFalse(Files.exists(paths.inactiveDir()),
                "an instance that never leaves a mod out should never grow the folder");
    }

    @Test
    void refusesToWriteOverSomethingAlreadyAtTheDestination(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir, List.of("fabric-api", "skyhanni", "coleweight"), List.of());

        // Something unrelated is sitting where coleweight would be stored.
        Files.createDirectories(paths.inactiveDir());
        Files.writeString(paths.inactivePath("coleweight-1.0.0.jar"), "not a mod at all",
                StandardCharsets.UTF_8);

        ProfileManager.Result result = apply(paths, "general");

        assertInstanceOf(ProfileManager.Result.Failed.class, result);
        assertEquals("not a mod at all",
                Files.readString(paths.inactivePath("coleweight-1.0.0.jar")),
                "the file that was already there must survive untouched");
        assertTrue(Files.isRegularFile(paths.modsDir().resolve("coleweight-1.0.0.jar")),
                "and the mod stays where it was");
    }

    @Test
    void putsBackWhatItAlreadyMovedWhenAMoveFails(@TempDir Path dir) throws IOException {
        // Two mods to deactivate; the second one's destination is occupied, so the
        // whole switch has to come undone rather than leave the instance half in
        // each profile.
        ModPaths paths = setUp(dir,
                List.of("fabric-api", "skyhanni", "coleweight", "bettermap", "dungeonrooms"),
                List.of());

        // Deactivations run in filename order, so blocking coleweight means bettermap
        // has already been moved by the time the failure happens.
        Files.createDirectories(paths.inactiveDir());
        Files.writeString(paths.inactivePath("coleweight-1.0.0.jar"), "in the way",
                StandardCharsets.UTF_8);

        ProfileManager.Result result = apply(paths, "general");

        assertInstanceOf(ProfileManager.Result.Failed.class, result);
        assertTrue(((ProfileManager.Result.Failed) result).restored());

        List<String> stillActive = namesIn(paths.modsDir());
        for (String modId : List.of("fabric-api", "skyhanni", "coleweight", "bettermap", "dungeonrooms")) {
            assertTrue(stillActive.contains(modId + "-1.0.0.jar"),
                    modId + " should have been put back");
        }
    }

    @Test
    void leavesAModInstalledTwiceExactlyWhereItIs(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir, List.of("fabric-api", "skyhanni", "bettermap"), List.of());
        Files.createDirectories(paths.inactiveDir());
        Jars.modJar(paths.inactiveDir(), "bettermap-0.9.0.jar", "bettermap", "0.9.0");

        apply(paths, "dungeons");

        assertTrue(Files.isRegularFile(paths.modsDir().resolve("bettermap-1.0.0.jar")));
        assertTrue(Files.isRegularFile(paths.inactivePath("bettermap-0.9.0.jar")),
                "neither copy may be moved while it is ambiguous which one is real");
    }

    @Test
    void leavesAJarWithNoModIdAlone(@TempDir Path dir) throws IOException {
        ModPaths paths = setUp(dir, List.of("fabric-api", "skyhanni"), List.of());
        Jars.emptyJar(paths.modsDir(), "mystery.jar");

        apply(paths, "lite");

        assertTrue(Files.isRegularFile(paths.modsDir().resolve("mystery.jar")),
                "nothing can identify it, so nothing may move it");
    }
}
