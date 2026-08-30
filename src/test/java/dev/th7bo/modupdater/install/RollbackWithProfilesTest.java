package dev.th7bo.modupdater.install;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rollback has to put a JAR back where the profile expects it.
 *
 * <p>An update installed at pre-launch can be switched off by the profile applied
 * moments later. If the rollback record still points at {@code mods/}, a failed
 * launch restores the old build into the active folder — turning on a mod the
 * user's profile deliberately excluded, by way of a crash.
 */
class RollbackWithProfilesTest {

    private static final String NEW_BYTES = "bettermap 1.7.0 bytes";
    private static final String OLD_BYTES = "bettermap 1.6.2 bytes";

    @AfterEach
    void cleanup() {
        Log.reset();
    }

    private static ModPaths mods(Path dir) throws IOException {
        return ModPaths.of(Files.createDirectories(dir.resolve("mods")));
    }

    /** The state a finished update leaves: new JAR active, old one in backup, unconfirmed. */
    private static PendingState pendingActiveUpdate(ModPaths paths) throws IOException {
        Files.createDirectories(paths.backupDir());
        Files.writeString(paths.activePath("bettermap-1.7.0.jar"), NEW_BYTES, StandardCharsets.UTF_8);
        Files.writeString(paths.backupDir().resolve("bettermap-1.6.2.jar"), OLD_BYTES,
                StandardCharsets.UTF_8);

        PendingState pending = new PendingState(List.of(new PendingState.Entry(
                "bettermap",
                paths.activePath("bettermap-1.7.0.jar").toString(),
                paths.backupDir().resolve("bettermap-1.6.2.jar").toString(),
                paths.activePath("bettermap-1.6.2.jar").toString())),
                false, System.currentTimeMillis());
        pending.write(paths.stateDir());
        return pending;
    }

    @Test
    void restoresAnActiveModIntoTheActiveFolder(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        pendingActiveUpdate(paths);

        List<String> restored = Installer.restoreIfUnconfirmed(paths.modsDir());

        assertEquals(List.of("bettermap"), restored);
        assertEquals(OLD_BYTES, Files.readString(paths.activePath("bettermap-1.6.2.jar")));
        assertFalse(Files.exists(paths.activePath("bettermap-1.7.0.jar")));
    }

    @Test
    void restoresIntoStorageWhenTheProfileMovedTheUpdateThere(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        PendingState pending = pendingActiveUpdate(paths);

        // The profile then deactivated the mod that was just updated.
        Files.createDirectories(paths.inactiveDir());
        Files.move(paths.activePath("bettermap-1.7.0.jar"), paths.inactivePath("bettermap-1.7.0.jar"));
        pending.relocate(Map.of(
                        paths.activePath("bettermap-1.7.0.jar"),
                        paths.inactivePath("bettermap-1.7.0.jar")))
                .write(paths.stateDir());

        List<String> restored = Installer.restoreIfUnconfirmed(paths.modsDir());

        assertEquals(List.of("bettermap"), restored);
        assertEquals(OLD_BYTES, Files.readString(paths.inactivePath("bettermap-1.6.2.jar")),
                "the old build belongs in storage, where the mod now lives");
        assertFalse(Files.exists(paths.activePath("bettermap-1.6.2.jar")),
                "a failed update must not activate a mod the profile excluded");
        assertFalse(Files.exists(paths.inactivePath("bettermap-1.7.0.jar")));
    }

    @Test
    void leavesUntouchedEntriesAlone(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        PendingState pending = pendingActiveUpdate(paths);

        PendingState after = pending.relocate(Map.of(
                paths.activePath("something-else.jar"), paths.inactivePath("something-else.jar")));

        assertEquals(pending.entries().get(0).newFile(), after.entries().get(0).newFile());
        assertEquals(pending.entries().get(0).replacedFile(), after.entries().get(0).replacedFile());
    }

    @Test
    void aProfileSwitchDoesNotDisturbTheBackupGeneration(@TempDir Path dir) throws IOException {
        ModPaths paths = mods(dir);
        pendingActiveUpdate(paths);

        Files.createDirectories(paths.inactiveDir());
        Files.move(paths.activePath("bettermap-1.7.0.jar"), paths.inactivePath("bettermap-1.7.0.jar"));

        assertTrue(Files.isRegularFile(paths.backupDir().resolve("bettermap-1.6.2.jar")),
                "moving a mod between profiles must not touch what rollback depends on");
    }
}
