package dev.th7bo.modupdater.instance;

import dev.th7bo.modupdater.Jars;
import dev.th7bo.modupdater.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceScannerTest {

    private final InstanceScanner scanner = new InstanceScanner();

    private Optional<InstalledMod> find(List<InstalledMod> mods, String filename) {
        return mods.stream().filter(m -> m.filename().equals(filename)).findFirst();
    }

    @Test
    void readsModIdAndVersion(@TempDir Path dir) throws IOException {
        Jars.modJar(dir, "examplemod-1.2.3.jar", "examplemod", "1.2.3");

        List<InstalledMod> mods = scanner.scan(dir);

        assertEquals(1, mods.size());
        assertEquals("examplemod", mods.get(0).modId());
        assertEquals("1.2.3", mods.get(0).modVersion());
        assertTrue(mods.get(0).matchable());
    }

    @Test
    void computesTheSha256(@TempDir Path dir) throws IOException {
        Path jar = Jars.modJar(dir, "examplemod.jar", "examplemod", "1.0.0");

        List<InstalledMod> mods = scanner.scan(dir);

        assertEquals(Hashing.sha256(jar), mods.get(0).sha256());
    }

    @Test
    void handlesAMixedFolder(@TempDir Path dir) throws IOException {
        Jars.modJar(dir, "good.jar", "goodmod", "1.0.0");
        Jars.emptyJar(dir, "nomodjson.jar");
        Jars.corruptJar(dir, "corrupt.jar");
        Files.writeString(dir.resolve("notes.txt"), "not a jar");
        Files.createDirectory(dir.resolve("subdir"));

        List<InstalledMod> mods = scanner.scan(dir);

        assertEquals(3, mods.size(), "only the .jar files, including unreadable ones");
        assertEquals("goodmod", find(mods, "good.jar").orElseThrow().modId());
        assertNull(find(mods, "nomodjson.jar").orElseThrow().modId());
        assertNull(find(mods, "corrupt.jar").orElseThrow().modId());
    }

    @Test
    void stillHashesJarsItCannotIdentify(@TempDir Path dir) throws IOException {
        Jars.corruptJar(dir, "corrupt.jar");

        InstalledMod mod = scanner.scan(dir).get(0);

        assertNotNull(mod.sha256());
        assertFalse(mod.matchable());
    }

    @Test
    void ignoresDisabledMods(@TempDir Path dir) throws IOException {
        Jars.modJar(dir, "active.jar", "active", "1.0.0");
        Jars.modJar(dir, "off.jar", "off", "1.0.0");
        Files.move(dir.resolve("off.jar"), dir.resolve("off.jar.disabled"));

        List<InstalledMod> mods = scanner.scan(dir);

        assertEquals(1, mods.size());
        assertEquals("active", mods.get(0).modId());
    }

    @Test
    void treatsAModWithNoIdAsUnmatchable(@TempDir Path dir) throws IOException {
        Jars.jar(dir, "noid.jar", "fabric.mod.json", "{\"version\":\"1.0.0\"}");

        assertFalse(scanner.scan(dir).get(0).matchable());
    }

    @Test
    void toleratesMalformedModJson(@TempDir Path dir) throws IOException {
        Jars.jar(dir, "broken.jar", "fabric.mod.json", "{ \"id\": ");

        List<InstalledMod> mods = scanner.scan(dir);

        assertEquals(1, mods.size());
        assertNull(mods.get(0).modId());
    }

    @Test
    void returnsEmptyForAnEmptyFolder(@TempDir Path dir) {
        assertTrue(scanner.scan(dir).isEmpty());
    }

    @Test
    void returnsEmptyForAMissingFolder(@TempDir Path dir) {
        assertTrue(scanner.scan(dir.resolve("does-not-exist")).isEmpty());
    }

    @Test
    void returnsEmptyForNull() {
        assertTrue(scanner.scan(null).isEmpty());
    }
}
