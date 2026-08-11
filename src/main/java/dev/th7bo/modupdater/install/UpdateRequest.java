package dev.th7bo.modupdater.install;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the in-game mod asked for, to be carried out after the game exits.
 *
 * The mod cannot install anything itself — Fabric Loader holds every mod JAR
 * open for the whole session — so it writes this and the post-exit hook acts on
 * it once the files are free.
 */
public record UpdateRequest(long requestedAt, boolean restartWanted, List<Entry> entries) {

    public static final String FILE = "request.json";

    public record Entry(
            String modId,
            String buildId,
            String filename,
            String sha256,
            String downloadUrl,
            String replaces) {

        boolean usable() {
            return filename != null && !filename.isBlank()
                    && sha256 != null && !sha256.isBlank()
                    && downloadUrl != null && !downloadUrl.isBlank()
                    && replaces != null && !replaces.isBlank();
        }
    }

    public UpdateRequest {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * @return the pending request, or null when there is none or it cannot be
     *     trusted. A malformed request is discarded rather than half-applied:
     *     acting on a partially understood instruction to replace files is worse
     *     than doing nothing.
     */
    public static UpdateRequest read(Path stateDir) {
        Path file = stateDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            UpdateRequest request = new Gson().fromJson(json, UpdateRequest.class);

            if (request == null || request.entries().isEmpty()) {
                return null;
            }
            if (request.entries().stream().anyMatch(entry -> entry == null || !entry.usable())) {
                Log.warn("ignoring an update request with incomplete entries");
                return null;
            }
            return request;
        } catch (IOException | JsonParseException e) {
            Log.warn("could not read " + FILE + ": " + e.getMessage());
            return null;
        }
    }

    public static void clear(Path stateDir) {
        try {
            Files.deleteIfExists(stateDir.resolve(FILE));
        } catch (IOException e) {
            Log.warn("could not clear " + FILE + ": " + e.getMessage());
        }
    }

    /** The JARs this request asks for, in the form the installer takes. */
    public List<InstallItem> items() {
        List<InstallItem> items = new ArrayList<>();
        for (Entry entry : entries) {
            items.add(new InstallItem(
                    entry.modId(),
                    entry.filename(),
                    entry.sha256(),
                    entry.downloadUrl(),
                    Path.of(entry.replaces())));
        }
        return items;
    }
}
