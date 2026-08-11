package dev.th7bo.modupdater.install;

import dev.th7bo.modupdater.diff.UpdateCandidate;
import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.manifest.Manifest;
import dev.th7bo.modupdater.util.Hashing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallerTest {

    private static final String NEW_CONTENT = "the new jar bytes";
    private static final String OLD_CONTENT = "the old jar bytes";

    /** Writes fixed content instead of hitting the network. */
    private static Downloader serving(String content) {
        return (url, token, destination) ->
                Files.writeString(destination, content, StandardCharsets.UTF_8);
    }

    private static Downloader failing() {
        return (url, token, destination) -> {
            throw new IOException("connection reset");
        };
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

    private static UpdateCandidate candidate(Path installedJar, String newFilename, String sha) {
        InstalledMod installed = new InstalledMod(
                installedJar, installedJar.getFileName().toString(), "examplemod", "1.0.0", "old-sha");

        Manifest.Version version = new Manifest.Version("2.0.0", "fabric", List.of("1.21.4"), "1.21.4", "exact",
                newFilename, sha, 17, "http://example.test/mod.jar",
                "build-1", "2026-08-10T10:00:00Z", "abc1234", "Newer build");

        Manifest.Mod mod = new Manifest.Mod("examplemod", "Example Mod", "repo-1", "example-mod", List.of(version));

        return new UpdateCandidate(installed, mod, version);
    }

    private static Path oldJar(Path modsDir) throws IOException {
        Path jar = modsDir.resolve("examplemod-1.0.0.jar");
        Files.writeString(jar, OLD_CONTENT, StandardCharsets.UTF_8);
        return jar;
    }

    @Test
    void installsAVerifiedDownload(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        UpdateCandidate candidate = candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT));

        Installer.Outcome outcome = new Installer(serving(NEW_CONTENT)).install(List.of(candidate), modsDir, "t");

        assertEquals(List.of("examplemod-2.0.0.jar"), outcome.installed());
        assertTrue(outcome.failures().isEmpty());
        assertFalse(Files.exists(old), "the replaced JAR must not remain in mods/");
        assertEquals(NEW_CONTENT, Files.readString(modsDir.resolve("examplemod-2.0.0.jar")));
    }

    @Test
    void neverLetsAMismatchedDownloadReachModsDir(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        UpdateCandidate candidate = candidate(old, "examplemod-2.0.0.jar", "not-the-real-hash");

        Installer.Outcome outcome = new Installer(serving(NEW_CONTENT)).install(List.of(candidate), modsDir, "t");

        assertTrue(outcome.installed().isEmpty());
        assertTrue(outcome.failures().get("examplemod-2.0.0.jar").contains("checksum"));
        assertFalse(Files.exists(modsDir.resolve("examplemod-2.0.0.jar")));
        assertEquals(OLD_CONTENT, Files.readString(old), "the working JAR must be untouched");
    }

    @Test
    void cleansUpTheStagedFileAfterAFailedVerification(@TempDir Path modsDir) throws IOException {
        UpdateCandidate candidate = candidate(oldJar(modsDir), "examplemod-2.0.0.jar", "wrong");

        new Installer(serving(NEW_CONTENT)).install(List.of(candidate), modsDir, "t");

        Path staging = Installer.stateDir(modsDir).resolve("staging");
        assertFalse(Files.exists(staging) && Files.list(staging).findAny().isPresent());
    }

    @Test
    void leavesTheInstanceIntactWhenTheDownloadFails(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        UpdateCandidate candidate = candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT));

        Installer.Outcome outcome = new Installer(failing()).install(List.of(candidate), modsDir, "t");

        assertTrue(outcome.installed().isEmpty());
        assertEquals(OLD_CONTENT, Files.readString(old));
    }

    @Test
    void restoresTheOriginalWhenTheNextLaunchIsNotConfirmed(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        UpdateCandidate candidate = candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT));
        new Installer(serving(NEW_CONTENT)).install(List.of(candidate), modsDir, "t");

        List<String> restored = Installer.restoreIfUnconfirmed(modsDir);

        assertEquals(List.of("examplemod"), restored);
        assertEquals(OLD_CONTENT, Files.readString(old), "byte-for-byte back to the prior state");
        assertFalse(Files.exists(modsDir.resolve("examplemod-2.0.0.jar")));
    }

    @Test
    void doesNotRestoreOnceTheLaunchIsConfirmed(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        UpdateCandidate candidate = candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT));
        new Installer(serving(NEW_CONTENT)).install(List.of(candidate), modsDir, "t");

        Installer.confirmLaunch(modsDir);
        List<String> restored = Installer.restoreIfUnconfirmed(modsDir);

        assertTrue(restored.isEmpty());
        assertEquals(NEW_CONTENT, Files.readString(modsDir.resolve("examplemod-2.0.0.jar")));
    }

    @Test
    void restoringTwiceIsHarmless(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        UpdateCandidate candidate = candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT));
        new Installer(serving(NEW_CONTENT)).install(List.of(candidate), modsDir, "t");

        Installer.restoreIfUnconfirmed(modsDir);
        List<String> second = Installer.restoreIfUnconfirmed(modsDir);

        assertTrue(second.isEmpty());
        assertEquals(OLD_CONTENT, Files.readString(old));
    }

    @Test
    void keepsOnlyOneBackupGeneration(@TempDir Path modsDir) throws IOException {
        Path first = oldJar(modsDir);
        new Installer(serving(NEW_CONTENT))
                .install(List.of(candidate(first, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT))), modsDir, "t");
        Installer.confirmLaunch(modsDir);

        Path second = modsDir.resolve("examplemod-2.0.0.jar");
        new Installer(serving("even newer bytes"))
                .install(List.of(candidate(second, "examplemod-3.0.0.jar", sha256Of("even newer bytes"))), modsDir, "t");

        Path backup = Installer.stateDir(modsDir).resolve("backup");
        try (var entries = Files.list(backup)) {
            assertEquals(1, entries.count(), "backups must not accumulate one folder per update forever");
        }
    }

    @Test
    void confirmsASessionThatLastedLongEnough(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        new Installer(serving(NEW_CONTENT))
                .install(List.of(candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT))), modsDir, "t");

        long later = System.currentTimeMillis() + Duration.ofMinutes(30).toMillis();
        var outcome = Installer.resolveAfterSession(modsDir, Duration.ofMinutes(2), later);

        assertInstanceOf(Installer.SessionOutcome.Confirmed.class, outcome);
        assertEquals(NEW_CONTENT, Files.readString(modsDir.resolve("examplemod-2.0.0.jar")));
    }

    @Test
    void rollsBackASessionThatDiedImmediately(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        new Installer(serving(NEW_CONTENT))
                .install(List.of(candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT))), modsDir, "t");

        var outcome = Installer.resolveAfterSession(modsDir, Duration.ofMinutes(2), System.currentTimeMillis());

        var rolledBack = assertInstanceOf(Installer.SessionOutcome.RolledBack.class, outcome);
        assertEquals(List.of("examplemod"), rolledBack.modIds());
        assertEquals(OLD_CONTENT, Files.readString(old), "a crash on init must not leave the bad JAR in place");
    }

    @Test
    void confirmsImmediatelyWhenTheModReportsASuccessfulLoad(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        new Installer(serving(NEW_CONTENT))
                .install(List.of(candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT))), modsDir, "t");

        // The in-game mod leaves this as soon as the client is up, so a deliberate
        // quick quit is no longer mistaken for a crash on init.
        Files.writeString(Installer.stateDir(modsDir).resolve(Installer.LAUNCH_MARKER), "ok");

        var outcome = Installer.resolveAfterSession(modsDir, Duration.ofMinutes(2), System.currentTimeMillis());

        assertInstanceOf(Installer.SessionOutcome.Confirmed.class, outcome);
        assertEquals(NEW_CONTENT, Files.readString(modsDir.resolve("examplemod-2.0.0.jar")));
    }

    @Test
    void ignoresAMarkerLeftBeforeThisInstall(@TempDir Path modsDir) throws IOException {
        Path stateDir = Installer.stateDir(modsDir);
        Files.createDirectories(stateDir);
        Path marker = stateDir.resolve(Installer.LAUNCH_MARKER);
        Files.writeString(marker, "ok");
        Files.setLastModifiedTime(marker, java.nio.file.attribute.FileTime.fromMillis(1_000));

        Path old = oldJar(modsDir);
        new Installer(serving(NEW_CONTENT))
                .install(List.of(candidate(old, "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT))), modsDir, "t");

        var outcome = Installer.resolveAfterSession(modsDir, Duration.ofMinutes(2), System.currentTimeMillis());

        assertInstanceOf(Installer.SessionOutcome.RolledBack.class, outcome);
        assertEquals(OLD_CONTENT, Files.readString(old), "a stale marker must not confirm an unlaunched update");
    }

    @Test
    void installsItemsPreparedElsewhere(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        InstallItem item = new InstallItem(
                "examplemod", "examplemod-2.0.0.jar", sha256Of(NEW_CONTENT), "http://example.test/m.jar", old);

        Installer.Outcome outcome =
                new Installer(serving(NEW_CONTENT)).installItems(List.of(item), modsDir, "t");

        assertEquals(List.of("examplemod-2.0.0.jar"), outcome.installed());
        assertEquals(NEW_CONTENT, Files.readString(modsDir.resolve("examplemod-2.0.0.jar")));
        assertFalse(Files.exists(old));
    }

    @Test
    void verifiesItemsPreparedElsewhereToo(@TempDir Path modsDir) throws IOException {
        Path old = oldJar(modsDir);
        InstallItem item = new InstallItem(
                "examplemod", "examplemod-2.0.0.jar", "not-the-real-hash", "http://example.test/m.jar", old);

        Installer.Outcome outcome =
                new Installer(serving(NEW_CONTENT)).installItems(List.of(item), modsDir, "t");

        assertTrue(outcome.installed().isEmpty());
        assertEquals(OLD_CONTENT, Files.readString(old));
    }

    @Test
    void reportsNothingPendingWhenNoUpdateHappened(@TempDir Path modsDir) {
        var outcome = Installer.resolveAfterSession(modsDir, Duration.ofMinutes(2), System.currentTimeMillis());

        assertInstanceOf(Installer.SessionOutcome.NothingPending.class, outcome);
    }

    @Test
    void doesNothingForAnEmptySelection(@TempDir Path modsDir) {
        Installer.Outcome outcome = new Installer(serving(NEW_CONTENT)).install(List.of(), modsDir, "t");

        assertTrue(outcome.installed().isEmpty());
        assertFalse(Files.exists(Installer.stateDir(modsDir).resolve(PendingState.FILE)));
    }
}
