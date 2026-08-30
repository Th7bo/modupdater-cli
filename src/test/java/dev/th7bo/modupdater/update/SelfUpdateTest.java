package dev.th7bo.modupdater.update;

import dev.th7bo.modupdater.install.Downloader;
import dev.th7bo.modupdater.util.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing the program with a newer one.
 *
 * <p>The install being replaced is a fixture directory rather than the real one,
 * so these exercise the actual file swapping without the test run updating
 * itself.
 */
class SelfUpdateTest {

    @AfterEach
    void cleanup() {
        Log.reset();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** A JAR with the manifest a real build has. */
    private static byte[] jar(String version, String mainClass) throws IOException {
        Path temporary = Files.createTempFile("build", ".jar");
        try {
            String manifest = "Manifest-Version: 1.0\r\n"
                    + "Main-Class: " + mainClass + "\r\n"
                    + (version == null ? "" : "Implementation-Version: " + version + "\r\n")
                    + "\r\n";
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zip.write(manifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] ourJar(String version) throws IOException {
        return jar(version, "dev.th7bo.modupdater.Main");
    }

    /** The installer zip a release carries. */
    private static byte[] installerZip(byte[] jar, String wrapperScript) throws IOException {
        Path temporary = Files.createTempFile("installer", ".zip");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary))) {
                zip.putNextEntry(new ZipEntry(Installed.JAR_NAME));
                zip.write(jar);
                zip.closeEntry();

                if (wrapperScript != null) {
                    zip.putNextEntry(new ZipEntry("modupdater.sh"));
                    zip.write(wrapperScript.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("modupdater.bat"));
                    zip.write("@echo off\r\nrem new\r\n".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return Files.readAllBytes(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** An install directory as the installer leaves it. */
    private static Installed install(Path dir, String version, boolean withWrapper) throws IOException {
        Path jar = dir.resolve(Installed.JAR_NAME);
        Files.write(jar, ourJar(version));

        if (withWrapper) {
            Path wrapper = dir.resolve("modupdater.sh");
            Files.writeString(wrapper, "#!/usr/bin/env bash\n# old\n", StandardCharsets.UTF_8);
            try {
                var permissions = Files.getPosixFilePermissions(wrapper);
                permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(wrapper, permissions);
            } catch (UnsupportedOperationException e) {
                // Windows; the executable bit is not a thing there.
            }
        }

        // Files the installer also leaves, which an update must not touch.
        Files.writeString(dir.resolve("settings.properties"), "base.url=https://mods.example.com\n");
        Files.writeString(dir.resolve("token"), "a-secret-token");

        return new Installed(jar, Version.of(version));
    }

    private static ReleaseSource offering(String tag) {
        return () -> new Release(Version.of(tag), tag, "http://example.test/" + tag, 0L);
    }

    private static Downloader serving(byte[] bytes) {
        return (url, token, destination) -> Files.write(destination, bytes);
    }

    private static SelfUpdate.Result update(Installed installed, String tag, byte[] zip) {
        return new SelfUpdate(offering(tag), serving(zip)).run(installed);
    }

    // ── The happy path ──────────────────────────────────────────────────────

    @Test
    void replacesTheJarWithANewerRelease(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", false);
        byte[] newer = ourJar("0.6.0");

        SelfUpdate.Result result = update(installed, "v0.6.0", installerZip(newer, null));

        assertInstanceOf(SelfUpdate.Result.Updated.class, result);
        assertEquals(Version.of("0.6.0"),
                Installed.versionOf(dir.resolve(Installed.JAR_NAME)),
                "the JAR on disk should now be the new build");
    }

    @Test
    void refreshesTheWrapperScriptAlongsideIt(@TempDir Path dir) throws IOException {
        // The wrapper changes between releases too — one of them taught the
        // profile commands to offer the instance menu — so a JAR-only update
        // would leave the two out of step.
        Installed installed = install(dir, "0.5.0", true);
        byte[] zip = installerZip(ourJar("0.6.0"), "#!/usr/bin/env bash\n# new\n");

        SelfUpdate.Result result = update(installed, "v0.6.0", zip);

        assertEquals(List.of("modupdater.sh"), ((SelfUpdate.Result.Updated) result).refreshed());
        assertTrue(Files.readString(dir.resolve("modupdater.sh")).contains("# new"));
    }

    @Test
    void keepsTheWrapperExecutable(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", true);
        byte[] zip = installerZip(ourJar("0.6.0"), "#!/usr/bin/env bash\n# new\n");

        update(installed, "v0.6.0", zip);

        assertTrue(Files.isExecutable(dir.resolve("modupdater.sh")),
                "a wrapper that stops being executable stops being a launcher hook");
    }

    @Test
    void addsNoWrapperTheInstallDidNotHave(@TempDir Path dir) throws IOException {
        // A Linux install has no modupdater.bat, and an update should not invent one.
        Installed installed = install(dir, "0.5.0", true);
        byte[] zip = installerZip(ourJar("0.6.0"), "#!/usr/bin/env bash\n# new\n");

        update(installed, "v0.6.0", zip);

        assertFalse(Files.exists(dir.resolve("modupdater.bat")));
    }

    @Test
    void leavesTheTokenAndSettingsAlone(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", true);

        update(installed, "v0.6.0", installerZip(ourJar("0.6.0"), "#!/usr/bin/env bash\n# new\n"));

        assertEquals("a-secret-token", Files.readString(dir.resolve("token")));
        assertTrue(Files.readString(dir.resolve("settings.properties")).contains("mods.example.com"));
    }

    @Test
    void clearsUpAfterItself(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", true);

        update(installed, "v0.6.0", installerZip(ourJar("0.6.0"), "#!/usr/bin/env bash\n# new\n"));

        try (var entries = Files.list(dir)) {
            List<String> leftovers = entries
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".new") || name.endsWith(".old"))
                    .toList();
            assertEquals(List.of(), leftovers);
        }
    }

    @Test
    void sweepsWhatAPreviousUpdateCouldNotDelete(@TempDir Path dir) throws IOException {
        // Windows cannot delete the outgoing JAR while it is still open, so it is
        // left renamed and cleared on the next run.
        Installed installed = install(dir, "0.6.0", false);
        Files.writeString(dir.resolve(Installed.JAR_NAME + ".old"), "last time's build");

        update(installed, "v0.6.0", installerZip(ourJar("0.6.0"), null));

        assertFalse(Files.exists(dir.resolve(Installed.JAR_NAME + ".old")));
    }

    // ── Standing down ───────────────────────────────────────────────────────

    @Test
    void doesNothingWhenAlreadyCurrent(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.6.0", false);
        byte[] before = Files.readAllBytes(dir.resolve(Installed.JAR_NAME));

        SelfUpdate.Result result = update(installed, "v0.6.0", installerZip(ourJar("0.6.0"), null));

        assertInstanceOf(SelfUpdate.Result.UpToDate.class, result);
        assertArrayEqualsQuietly(before, Files.readAllBytes(dir.resolve(Installed.JAR_NAME)));
    }

    @Test
    void neverDowngrades(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.9.0", false);

        SelfUpdate.Result result = update(installed, "v0.5.0", installerZip(ourJar("0.5.0"), null));

        assertInstanceOf(SelfUpdate.Result.UpToDate.class, result);
        assertEquals(Version.of("0.9.0"), Installed.versionOf(dir.resolve(Installed.JAR_NAME)));
    }

    @Test
    void saysSoWhenRunningFromLooseClasses(@TempDir Path dir) {
        Installed notAJar = new Installed(null, Version.UNKNOWN);

        assertInstanceOf(SelfUpdate.Result.NotInstalled.class,
                new SelfUpdate(offering("v9.9.9"), serving(new byte[0])).run(notAJar));
    }

    @Test
    void reportsAServerItCannotReach(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", false);
        ReleaseSource broken = () -> {
            throw new IOException("connection reset");
        };

        SelfUpdate.Result result = new SelfUpdate(broken, serving(new byte[0])).run(installed);

        assertInstanceOf(SelfUpdate.Result.Failed.class, result);
        assertTrue(((SelfUpdate.Result.Failed) result).detail().contains("connection reset"));
        assertEquals(Version.of("0.5.0"), Installed.versionOf(dir.resolve(Installed.JAR_NAME)),
                "a failure must leave the working install exactly as it was");
    }

    // ── Refusing a bad download ─────────────────────────────────────────────

    @Test
    void refusesAZipWithNoJarInIt(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", false);

        Path empty = Files.createTempFile("empty", ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(empty))) {
            zip.putNextEntry(new ZipEntry("README.md"));
            zip.write("nothing useful".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        SelfUpdate.Result result = update(installed, "v0.6.0", Files.readAllBytes(empty));

        assertInstanceOf(SelfUpdate.Result.Failed.class, result);
        assertEquals(Version.of("0.5.0"), Installed.versionOf(dir.resolve(Installed.JAR_NAME)));
    }

    @Test
    void refusesAJarThatIsNotThisProgram(@TempDir Path dir) throws IOException {
        // Whatever this is, it is not us, and it is not going anywhere near the
        // name the launcher hooks point at.
        Installed installed = install(dir, "0.5.0", false);
        byte[] impostor = jar("0.6.0", "com.example.SomethingElse");

        SelfUpdate.Result result = update(installed, "v0.6.0", installerZip(impostor, null));

        assertInstanceOf(SelfUpdate.Result.Failed.class, result);
        assertEquals(Version.of("0.5.0"), Installed.versionOf(dir.resolve(Installed.JAR_NAME)),
                "the working install must survive a download that is not what it claims");
    }

    @Test
    void refusesADownloadThatIsNotAZipAtAll(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", false);

        SelfUpdate.Result result = update(
                installed, "v0.6.0", "404: Not Found".getBytes(StandardCharsets.UTF_8));

        assertInstanceOf(SelfUpdate.Result.Failed.class, result);
        assertEquals(Version.of("0.5.0"), Installed.versionOf(dir.resolve(Installed.JAR_NAME)));
    }

    @Test
    void survivesADownloadThatFailsPartWay(@TempDir Path dir) throws IOException {
        Installed installed = install(dir, "0.5.0", false);
        Downloader failing = (url, token, destination) -> {
            Files.writeString(destination, "half a zip");
            throw new IOException("connection reset");
        };

        SelfUpdate.Result result = new SelfUpdate(offering("v0.6.0"), failing).run(installed);

        assertInstanceOf(SelfUpdate.Result.Failed.class, result);
        assertEquals(Version.of("0.5.0"), Installed.versionOf(dir.resolve(Installed.JAR_NAME)));
        assertFalse(Files.exists(dir.resolve(Release.ASSET + ".new")), "the part-download is cleared");
    }

    private static void assertArrayEqualsQuietly(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte " + i);
        }
    }
}
