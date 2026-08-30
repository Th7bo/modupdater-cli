package dev.th7bo.modupdater.profile;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Which profile this instance is in, and which one to offer next time.
 *
 * <p>Per instance by construction — it lives in the instance's own state
 * directory — so a laptop can remember "lite" while the desktop has profiles
 * switched off entirely.
 *
 * @param selected     the profile last applied to {@code mods/}
 * @param remembered   whether that choice should be offered again next launch —
 *                     the checkbox in the dialog, seeded from {@code profile.remember}
 * @param appliedAt    when it was last materialised into {@code mods/}
 * @param activeModIds what was active at that point, for the log and for anyone
 *                     diagnosing a launch after the fact
 */
public record ProfileState(String selected, boolean remembered, long appliedAt, List<String> activeModIds) {

    public static final String FILE = "profile.json";

    public ProfileState {
        activeModIds = activeModIds == null ? List.of() : List.copyOf(activeModIds);
    }

    public static ProfileState none() {
        return new ProfileState(null, false, 0L, List.of());
    }

    public boolean hasSelection() {
        return selected != null && !selected.isBlank();
    }

    public static ProfileState read(Path stateDir) {
        Path file = stateDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return none();
        }

        try {
            ProfileState state = new Gson().fromJson(
                    Files.readString(file, StandardCharsets.UTF_8), ProfileState.class);
            return state == null ? none() : state;
        } catch (IOException | JsonParseException e) {
            Log.warn("could not read " + FILE + ": " + e.getMessage());
            return none();
        }
    }

    public void write(Path stateDir) {
        try {
            Files.createDirectories(stateDir);
            Files.writeString(stateDir.resolve(FILE), new Gson().toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Forgetting the selection is a nuisance, not a reason to stop a launch.
            Log.warn("could not write " + FILE + ": " + e.getMessage());
        }
    }
}
