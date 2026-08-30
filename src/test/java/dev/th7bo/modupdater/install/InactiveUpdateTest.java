package dev.th7bo.modupdater.install;

import dev.th7bo.modupdater.diff.Differ;
import dev.th7bo.modupdater.diff.UpdateCandidate;
import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.manifest.Manifest;
import dev.th7bo.modupdater.profile.ProfileManager;
import dev.th7bo.modupdater.profile.ProfilePlan;
import dev.th7bo.modupdater.profile.ProfileResolver;
import dev.th7bo.modupdater.util.Hashing;
import dev.th7bo.modupdater.util.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mod a profile leaves out is still installed, and still gets updates.
 *
 * <p>The build that lands for it goes into storage, not into {@code mods/}:
 * updating a mod must not switch it on.
 */
class InactiveUpdateTest {

    @AfterEach
    void cleanup() {
        Log.reset();
    }

    /**
     * The 1.7.0 build, as real JAR bytes.
     *
     * <p>A text file would do for the installer, but the profile step reads
     * {@code fabric.mod.json} to identify what it is moving — so the artifact has
     * to be a mod, not a stand-in.
     */
    private static byte[] newBuild() throws IOException {
        Path dir = Files.createTempDirectory("build");
        try {
            return Files.readAllBytes(
                    dev.th7bo.modupdater.Jars.modJar(dir, "bettermap-1.7.0.jar", "bettermap", "1.7.0"));
        } finally {
            deleteRecursively(dir);
        }
    }

    private static Downloader serving(byte[] content) {
        return (url, token, destination) -> Files.write(destination, content);
    }

    private static String sha256Of(byte[] content) throws IOException {
        Path tmp = Files.createTempFile("sha", ".bin");
        try {
            Files.write(tmp, content);
            return Hashing.sha256(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Manifest manifestOffering(String filename, String sha) {
        Manifest.Version version = new Manifest.Version("1.7.0", "fabric", List.of("1.21.4"), "1.21.4",
                "exact", filename, sha, 21, "http://example.test/bettermap.jar",
                "build-9", "2026-08-10T10:00:00Z", "abc1234", "Updated dungeon map rendering");
        return new Manifest("now",
                List.of(new Manifest.Mod("bettermap", "BetterMap", "repo-1", "better-map", List.of(version))));
    }

    /** A stored BetterMap, out of the profile, one version behind. */
    private static ModPaths storedBetterMap(Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        ModPaths paths = ModPaths.of(mods);
        Files.createDirectories(paths.inactiveDir());
        dev.th7bo.modupdater.Jars.modJar(
                paths.inactiveDir(), "bettermap-1.6.2.jar", "bettermap", "1.6.2");
        return paths;
    }

    private static InstalledMod storedMod(ModPaths paths) throws IOException {
        Path jar = paths.inactivePath("bettermap-1.6.2.jar");
        return new InstalledMod(jar, "bettermap-1.6.2.jar", "bettermap", "1.6.2", Hashing.sha256(jar));
    }

    @Test
    void offersAnUpdateForAModThatIsNotActive(@TempDir Path dir) throws IOException {
        ModPaths paths = storedBetterMap(dir);
        byte[] build = newBuild();
        ModInventory inventory = ModInventory.of(List.of(), List.of(storedMod(paths)));

        List<UpdateCandidate> candidates = Differ.plan(
                inventory.all(), manifestOffering("bettermap-1.7.0.jar", sha256Of(build)), "1.21.4");

        assertEquals(1, candidates.size());
        assertEquals("bettermap", candidates.get(0).modId());
    }

    @Test
    void installsItIntoStorageRatherThanTurningItOn(@TempDir Path dir) throws IOException {
        ModPaths paths = storedBetterMap(dir);
        byte[] build = newBuild();

        Installer.Outcome outcome = new Installer(serving(build))
                .install(List.of(candidate(paths, build)), paths.modsDir(), "t");

        assertEquals(List.of("bettermap-1.7.0.jar"), outcome.installed());
        assertTrue(Files.isRegularFile(paths.inactivePath("bettermap-1.7.0.jar")));
        assertFalse(Files.exists(paths.modsDir().resolve("bettermap-1.7.0.jar")),
                "updating a stored mod must not activate it");
    }

    @Test
    void leavesNoStaleCopyOfTheOldBuildBehind(@TempDir Path dir) throws IOException {
        ModPaths paths = storedBetterMap(dir);
        byte[] build = newBuild();

        new Installer(serving(build)).install(List.of(candidate(paths, build)), paths.modsDir(), "t");

        assertFalse(Files.exists(paths.inactivePath("bettermap-1.6.2.jar")),
                "the superseded build belongs in backup/, not next to its replacement");
        assertTrue(ModInventory.scan(paths, new InstanceScanner()).conflicts().isEmpty(),
                "there must never be two versions of one mod in the inventory");
    }

    @Test
    void theUpdatedBuildIsWhatComesBackWhenTheProfileWantsIt(@TempDir Path dir) throws IOException {
        ModPaths paths = storedBetterMap(dir);
        byte[] build = newBuild();

        new Installer(serving(build)).install(List.of(candidate(paths, build)), paths.modsDir(), "t");

        // The profile names the mod, not the file, so the rename that came with the
        // update changes nothing about finding it again.
        ModInventory inventory = ModInventory.scan(paths, new InstanceScanner());
        ProfileResolver.Resolution resolution = ProfileResolver.everything(inventory);
        new ProfileManager(paths).apply(ProfilePlan.of(paths, inventory, resolution));

        assertTrue(Files.isRegularFile(paths.modsDir().resolve("bettermap-1.7.0.jar")),
                "activating the mod brings up the build the updater just installed");
        assertArrayEquals(build, Files.readAllBytes(paths.modsDir().resolve("bettermap-1.7.0.jar")));
        assertFalse(Files.exists(paths.inactivePath("bettermap-1.7.0.jar")));
    }

    /** The stored 1.6.2 build, and the 1.7.0 the server is offering to replace it with. */
    private static UpdateCandidate candidate(ModPaths paths, byte[] build) throws IOException {
        Manifest manifest = manifestOffering("bettermap-1.7.0.jar", sha256Of(build));
        return new UpdateCandidate(
                storedMod(paths), manifest.mods().get(0), manifest.mods().get(0).versions().get(0));
    }
}
