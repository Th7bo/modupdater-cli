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
 * What the last update run changed, and whether the launch after it succeeded.
 *
 * <p>The platform builds from upstream development commits, so a JAR that fails
 * on init is an ordinary outcome rather than an edge case. Without this record
 * a bad build leaves the user doing manual file surgery.
 */
public record PendingState(List<Entry> entries, boolean launchConfirmed, long installedAtMillis) {

    public static final String FILE = "pending.json";

    public record Entry(String modId, String newFile, String backupFile, String replacedFile) {
    }

    public PendingState {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static PendingState empty() {
        return new PendingState(List.of(), true, 0L);
    }

    /**
     * How long the session that followed the update lasted. The post-exit hook
     * runs whether the game played fine or died on init, so elapsed time is the
     * only signal available that distinguishes them.
     */
    public long sessionMillis(long nowMillis) {
        return installedAtMillis <= 0 ? Long.MAX_VALUE : nowMillis - installedAtMillis;
    }

    public boolean awaitingConfirmation() {
        return !launchConfirmed && !entries.isEmpty();
    }

    public static PendingState read(Path stateDir) {
        Path file = stateDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return empty();
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            PendingState state = new Gson().fromJson(json, PendingState.class);
            return state == null ? empty() : state;
        } catch (IOException | JsonParseException e) {
            Log.warn("could not read " + FILE + ": " + e.getMessage());
            return empty();
        }
    }

    public void write(Path stateDir) {
        try {
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve(FILE), new Gson().toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.warn("could not write " + FILE + ": " + e.getMessage());
        }
    }

    public static void clear(Path stateDir) {
        try {
            Files.deleteIfExists(stateDir.resolve(FILE));
        } catch (IOException e) {
            Log.warn("could not clear " + FILE + ": " + e.getMessage());
        }
    }

    public PendingState confirmed() {
        return new PendingState(new ArrayList<>(entries), true, installedAtMillis);
    }
}
