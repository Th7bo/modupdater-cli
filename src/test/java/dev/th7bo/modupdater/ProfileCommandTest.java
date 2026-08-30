package dev.th7bo.modupdater;

import dev.th7bo.modupdater.instance.ModPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deciding whether to act on the folder we are standing in, or to ask. */
class ProfileCommandTest {

    @Test
    void aModsFolderWithModsInItIsAnInstance(@TempDir Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Jars.modJar(mods, "skyhanni.jar", "skyhanni", "1.0.0");

        assertTrue(ProfileCommand.looksLikeAnInstance(mods));
    }

    /** Everything a profile switched off still counts — those are installed too. */
    @Test
    void storedModsCountAsWell(@TempDir Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Path inactive = Files.createDirectories(ModPaths.of(mods).inactiveDir());
        Jars.modJar(inactive, "coleweight.jar", "coleweight", "2.0.0");

        assertTrue(ProfileCommand.looksLikeAnInstance(mods));
    }

    /**
     * The bug this exists for: running the updater outside an instance leaves a
     * mods/.modupdater behind for its log, and taking that as an instance is how
     * "modupdater profile enable" in a home directory enabled profiles on the
     * home directory rather than offering the menu.
     */
    @Test
    void aLogFolderLeftBehindIsNotAnInstance(@TempDir Path dir) throws IOException {
        Path mods = dir.resolve("mods");
        Files.createDirectories(ModPaths.of(mods).stateDir());
        Files.writeString(ModPaths.of(mods).stateDir().resolve("log.txt"), "INFO nothing pending\n");

        assertFalse(ProfileCommand.looksLikeAnInstance(mods));
    }

    @Test
    void nothingThereIsNotAnInstance(@TempDir Path dir) {
        assertFalse(ProfileCommand.looksLikeAnInstance(dir.resolve("mods")));
    }
}
