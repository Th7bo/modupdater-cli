package dev.th7bo.modupdater.install;

import dev.th7bo.modupdater.manifest.Manifest;
import dev.th7bo.modupdater.util.Hashing;
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

class ModInstallerTest {

    private static final String CONTENT = "the mod jar bytes";

    private static Downloader serving(String content) {
        return (url, token, destination) -> Files.writeString(destination, content, StandardCharsets.UTF_8);
    }

    private static String sha256Of(String content) throws IOException {
        Path tmp = Files.createTempFile("sha", ".bin");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            return Hashing.sha256(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Manifest manifestOffering(String modId, String filename, String sha) {
        Manifest.Version version = new Manifest.Version("1.0.0", "fabric", List.of("26.1"), "~26.1", "prefix",
                filename, sha, 100, "http://example.test/mod.jar", "b1", "t", null, null);
        return new Manifest("now", List.of(
                new Manifest.Mod(modId, "ModUpdater", "repo-1", "ModUpdater-mod", List.of(version))));
    }

    @Test
    void installsAModTheInstanceDoesNotHave(@TempDir Path modsDir) throws IOException {
        Manifest manifest = manifestOffering("modupdater", "modupdater-mod-1.0.0.jar", sha256Of(CONTENT));

        var result = new ModInstaller(serving(CONTENT)).install(manifest, "modupdater", modsDir, "t");

        assertInstanceOf(ModInstaller.Result.Installed.class, result);
        assertEquals(CONTENT, Files.readString(modsDir.resolve("modupdater-mod-1.0.0.jar")));
    }

    @Test
    void doesNotInstallASecondCopy(@TempDir Path modsDir) throws IOException {
        String sha = sha256Of(CONTENT);
        Files.writeString(modsDir.resolve("modupdater-mod-1.0.0.jar"), CONTENT);

        var result = new ModInstaller(serving(CONTENT)).install(
                manifestOffering("modupdater", "modupdater-mod-1.0.0.jar", sha), "modupdater", modsDir, "t");

        assertInstanceOf(ModInstaller.Result.AlreadyPresent.class, result);
    }

    @Test
    void recognisesTheSameBuildUnderAnotherName(@TempDir Path modsDir) throws IOException {
        // Matched by content: a JAR renamed on the way in is still the same
        // build, and a second copy would leave the loader with two.
        String sha = sha256Of(CONTENT);
        Files.writeString(modsDir.resolve("renamed-by-hand.jar"), CONTENT);

        var result = new ModInstaller(serving(CONTENT)).install(
                manifestOffering("modupdater", "modupdater-mod-1.0.0.jar", sha), "modupdater", modsDir, "t");

        assertInstanceOf(ModInstaller.Result.AlreadyPresent.class, result);
        assertFalse(Files.exists(modsDir.resolve("modupdater-mod-1.0.0.jar")));
    }

    @Test
    void reportsWhenTheServerOffersNothing(@TempDir Path modsDir) throws IOException {
        Manifest manifest = manifestOffering("somethingelse", "other.jar", sha256Of(CONTENT));

        var result = new ModInstaller(serving(CONTENT)).install(manifest, "modupdater", modsDir, "t");

        assertInstanceOf(ModInstaller.Result.NotOffered.class, result);
    }

    @Test
    void neverInstallsAMismatchedDownload(@TempDir Path modsDir) {
        Manifest manifest = manifestOffering("modupdater", "modupdater-mod-1.0.0.jar", "not-the-real-hash");

        var result = new ModInstaller(serving(CONTENT)).install(manifest, "modupdater", modsDir, "t");

        assertInstanceOf(ModInstaller.Result.Failed.class, result);
        assertFalse(Files.exists(modsDir.resolve("modupdater-mod-1.0.0.jar")));
    }

    @Test
    void reportsADownloadFailureWithoutThrowing(@TempDir Path modsDir) throws IOException {
        Downloader failing = (url, token, destination) -> {
            throw new IOException("connection reset");
        };

        var result = new ModInstaller(failing).install(
                manifestOffering("modupdater", "m.jar", sha256Of(CONTENT)), "modupdater", modsDir, "t");

        assertEquals("connection reset", assertInstanceOf(ModInstaller.Result.Failed.class, result).detail());
    }

    @Test
    void skipsVersionsWithNoChecksum(@TempDir Path modsDir) {
        Manifest.Version noHash = new Manifest.Version("1.0.0", "fabric", List.of("26.1"), "~26.1", "prefix",
                "m.jar", "", 1, "http://example.test/m.jar", "b", "t", null, null);
        Manifest manifest = new Manifest("now", List.of(
                new Manifest.Mod("modupdater", "ModUpdater", "r", "repo", List.of(noHash))));

        assertInstanceOf(ModInstaller.Result.NotOffered.class,
                new ModInstaller(serving(CONTENT)).install(manifest, "modupdater", modsDir, "t"));
    }

    @Test
    void handlesAMissingModsDirectory(@TempDir Path parent) throws IOException {
        Path modsDir = parent.resolve("mods");

        var result = new ModInstaller(serving(CONTENT)).install(
                manifestOffering("modupdater", "m.jar", sha256Of(CONTENT)), "modupdater", modsDir, "t");

        assertInstanceOf(ModInstaller.Result.Installed.class, result);
        assertTrue(Files.exists(modsDir.resolve("m.jar")));
    }
}
