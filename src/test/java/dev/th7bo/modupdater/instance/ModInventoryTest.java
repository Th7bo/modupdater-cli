package dev.th7bo.modupdater.instance;

import dev.th7bo.modupdater.Jars;
import dev.th7bo.modupdater.util.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What counts as installed. */
class ModInventoryTest {

    @AfterEach
    void cleanup() {
        Log.reset();
    }

    private static ModPaths mods(Path dir) throws IOException {
        return ModPaths.of(Files.createDirectories(dir.resolve("mods")));
    }

    private static ModInventory scan(ModPaths paths) {
        return ModInventory.scan(paths, new InstanceScanner());
    }

    @Test
    void countsBothActiveAndStoredMods(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        Jars.modJar(paths.modsDir(), "skyhanni.jar", "skyhanni", "1.2.4");
        Files.createDirectories(paths.inactiveDir());
        Jars.modJar(paths.inactiveDir(), "bettermap.jar", "bettermap", "1.6.2");

        ModInventory inventory = scan(paths);

        assertEquals(Set.of("skyhanni", "bettermap"), inventory.modIds());
        assertEquals(1, inventory.active().size());
        assertEquals(1, inventory.inactive().size());
    }

    @Test
    void aStoredModIsStillInstalled(@TempDir Path dir) throws IOException {
        // The whole reason the updater reads storage: a mod a profile leaves out is
        // not uninstalled, and must go on receiving updates.
        ModPaths paths = mods(dir);
        Files.createDirectories(paths.inactiveDir());
        Jars.modJar(paths.inactiveDir(), "bettermap.jar", "bettermap", "1.6.2");

        assertTrue(scan(paths).modIds().contains("bettermap"));
    }

    @Test
    void neverCountsABackupAsInstalled(@TempDir Path dir) throws IOException {
        // A backup is the previous version of something already counted. Offering an
        // update for it would mean offering the same mod twice.
        ModPaths paths = mods(dir);
        Jars.modJar(paths.modsDir(), "skyhanni-1.2.5.jar", "skyhanni", "1.2.5");
        Files.createDirectories(paths.backupDir());
        Jars.modJar(paths.backupDir(), "skyhanni-1.2.4.jar", "skyhanni", "1.2.4");

        ModInventory inventory = scan(paths);

        assertEquals(1, inventory.size());
        assertEquals(Set.of(), inventory.conflicts());
    }

    @Test
    void readsNothingExtraWhenStorageHasNeverExisted(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        Jars.modJar(paths.modsDir(), "skyhanni.jar", "skyhanni", "1.2.4");

        ModInventory inventory = scan(paths);

        assertEquals(List.of(), inventory.inactive());
        assertEquals(1, inventory.size());
    }

    @Test
    void reportsAModThatExistsInBothPlaces(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        Jars.modJar(paths.modsDir(), "bettermap-1.7.0.jar", "bettermap", "1.7.0");
        Files.createDirectories(paths.inactiveDir());
        Jars.modJar(paths.inactiveDir(), "bettermap-1.6.2.jar", "bettermap", "1.6.2");

        ModInventory inventory = scan(paths);

        assertEquals(Set.of("bettermap"), inventory.conflicts());
        assertTrue(inventory.conflicted("bettermap"));
        assertEquals(2, inventory.pathsFor("bettermap").size());
    }

    @Test
    void listsJarsWithNoDescriptorWithoutClaimingToIdentifyThem(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        Jars.emptyJar(paths.modsDir(), "mystery.jar");

        ModInventory inventory = scan(paths);

        assertEquals(1, inventory.size());
        assertEquals(1, inventory.unidentified().size());
        assertEquals(Set.of(), inventory.modIds());
    }

    @Test
    void knowsWhichDirectoryMeansWhat(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);

        assertTrue(paths.isActive(paths.activePath("skyhanni.jar")));
        assertFalse(paths.isInactive(paths.activePath("skyhanni.jar")));

        assertTrue(paths.isInactive(paths.inactivePath("skyhanni.jar")));
        assertFalse(paths.isActive(paths.inactivePath("skyhanni.jar")));

        // A backup is neither, which is what keeps it out of the inventory.
        Path backedUp = paths.backupDir().resolve("skyhanni.jar");
        assertFalse(paths.isActive(backedUp));
        assertFalse(paths.isInactive(backedUp));
    }
}
