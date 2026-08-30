package dev.th7bo.modupdater.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogTest {

    @AfterEach
    void cleanup() {
        Log.reset();
    }

    /**
     * {@code modupdater profile list} settles which instance it means after the
     * log has been pointed somewhere, so creating the folder in init left a
     * stray mods/.modupdater in whatever directory the user was standing in.
     */
    @Test
    void createsNothingUntilSomethingIsLogged(@TempDir Path dir) {
        Path stateDir = dir.resolve("mods/.modupdater");

        Log.init(stateDir, null);

        assertFalse(Files.exists(stateDir));
        assertFalse(Files.exists(dir.resolve("mods")));
    }

    @Test
    void writesThroughToTheFileOnceThereIsSomethingToSay(@TempDir Path dir) throws IOException {
        Path stateDir = dir.resolve("mods/.modupdater");

        Log.init(stateDir, "s3cret");
        Log.info("token is s3cret");

        assertTrue(Files.readString(stateDir.resolve("log.txt")).contains("token is ***"));
    }
}
