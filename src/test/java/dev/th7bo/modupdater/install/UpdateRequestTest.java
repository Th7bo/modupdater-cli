package dev.th7bo.modupdater.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateRequestTest {

    private static final String VALID = """
            {
              "requestedAt": 1786400000000,
              "restartWanted": false,
              "entries": [
                {
                  "modId": "skyhanni",
                  "buildId": "b1",
                  "filename": "SkyHanni-7.44.0.jar",
                  "sha256": "abcdef",
                  "downloadUrl": "https://mods.example.com/api/artifacts/b1/SkyHanni-7.44.0.jar",
                  "replaces": "/instance/mods/SkyHanni-old.jar"
                }
              ]
            }
            """;

    private static Path write(Path stateDir, String json) throws IOException {
        Files.createDirectories(stateDir);
        Path file = stateDir.resolve(UpdateRequest.FILE);
        Files.writeString(file, json);
        return file;
    }

    @Test
    void readsWhatTheModWrote(@TempDir Path stateDir) throws IOException {
        write(stateDir, VALID);

        UpdateRequest request = UpdateRequest.read(stateDir);

        assertNotNull(request);
        assertFalse(request.restartWanted());
        assertEquals(1, request.entries().size());
        assertEquals("skyhanni", request.entries().get(0).modId());
    }

    @Test
    void convertsToInstallItems(@TempDir Path stateDir) throws IOException {
        write(stateDir, VALID);

        InstallItem item = UpdateRequest.read(stateDir).items().get(0);

        assertEquals("SkyHanni-7.44.0.jar", item.filename());
        assertEquals("abcdef", item.sha256());
        assertEquals(Path.of("/instance/mods/SkyHanni-old.jar"), item.replaces());
    }

    @Test
    void absentWhenThereIsNoRequest(@TempDir Path stateDir) {
        assertNull(UpdateRequest.read(stateDir));
    }

    @Test
    void ignoresAnEmptyRequest(@TempDir Path stateDir) throws IOException {
        write(stateDir, "{\"requestedAt\":1,\"restartWanted\":false,\"entries\":[]}");

        assertNull(UpdateRequest.read(stateDir));
    }

    @Test
    void ignoresMalformedJson(@TempDir Path stateDir) throws IOException {
        write(stateDir, "{ not json");

        assertNull(UpdateRequest.read(stateDir));
    }

    @Test
    void ignoresAnEntryMissingItsChecksum(@TempDir Path stateDir) throws IOException {
        // Acting on a half-understood instruction to replace files is worse than
        // doing nothing, so the whole request is discarded.
        write(stateDir, VALID.replace("\"sha256\": \"abcdef\",", "\"sha256\": \"\","));

        assertNull(UpdateRequest.read(stateDir));
    }

    @Test
    void ignoresAnEntryMissingItsTarget(@TempDir Path stateDir) throws IOException {
        write(stateDir, VALID.replace("\"replaces\": \"/instance/mods/SkyHanni-old.jar\"", "\"replaces\": \"\""));

        assertNull(UpdateRequest.read(stateDir));
    }

    @Test
    void clearsTheRequest(@TempDir Path stateDir) throws IOException {
        Path file = write(stateDir, VALID);

        UpdateRequest.clear(stateDir);

        assertFalse(Files.exists(file));
    }

    @Test
    void clearingWhenAbsentIsHarmless(@TempDir Path stateDir) {
        UpdateRequest.clear(stateDir);
    }
}
