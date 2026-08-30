package dev.th7bo.modupdater.profile;

import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only thing that moves a mod between active and inactive storage.
 *
 * <p>Mods are moved, never copied and never deleted: there is one JAR per
 * installed mod and it is either in {@code mods/} or in {@code inactive/}. A
 * profile switch that fails partway puts back everything it had already moved,
 * so an interrupted run leaves the instance as it was rather than half in each
 * profile.
 */
public final class ProfileManager {

    private final ModPaths paths;

    public ProfileManager(ModPaths paths) {
        this.paths = paths;
    }

    /** What happened, and where each JAR ended up. */
    public sealed interface Result {

        /** @param moved old path → new path, for anything else holding those paths */
        record Applied(String profileName, Map<Path, Path> moved, int activated, int deactivated)
                implements Result {
        }

        record NothingToDo(String profileName) implements Result {
        }

        /** @param restored whether the moves already made were undone cleanly */
        record Failed(String profileName, String detail, boolean restored) implements Result {
        }
    }

    public Result apply(ProfilePlan plan) {
        if (plan.isEmpty()) {
            Log.info("profile '" + plan.profileName() + "' is already what is on disk");
            return new Result.NothingToDo(plan.profileName());
        }

        List<ProfilePlan.Move> done = new ArrayList<>();
        Map<Path, Path> moved = new LinkedHashMap<>();

        try {
            // Only ever created when something actually needs storing. An instance
            // that never leaves a mod out never grows the directory.
            if (!plan.deactivate().isEmpty()) {
                Files.createDirectories(paths.inactiveDir());
            }

            for (ProfilePlan.Move move : plan.moves()) {
                boolean activating = paths.isActive(move.to());
                Log.info((activating ? "activating " : "deactivating ") + move.modId()
                        + " (" + move.filename() + ")");
                relocate(move.from(), move.to());
                done.add(move);
                moved.put(move.from(), move.to());
            }
        } catch (IOException e) {
            String detail = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            Log.error("could not apply profile '" + plan.profileName() + "': " + detail);
            boolean restored = undo(done);
            return new Result.Failed(plan.profileName(), detail, restored);
        }

        Log.info("profile '" + plan.profileName() + "' applied: "
                + plan.activate().size() + " activated, " + plan.deactivate().size() + " deactivated");
        return new Result.Applied(
                plan.profileName(), Map.copyOf(moved), plan.activate().size(), plan.deactivate().size());
    }

    /**
     * Moves one JAR, atomically where the platform allows it.
     *
     * <p>Refuses to write over anything already at the destination. Every path
     * here is derived from a filename we found on disk, so a collision means
     * something else is there — and overwriting it would destroy a mod rather
     * than move one.
     */
    private static void relocate(Path from, Path to) throws IOException {
        if (Files.exists(to)) {
            throw new IOException("something is already at " + to + " — not overwriting it");
        }

        Path parent = to.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Both directories are inside mods/, so this is one filesystem and an
            // atomic move is the normal case. Windows can still refuse it.
            Files.move(from, to);
        }
    }

    /** Puts back the moves already made, so a failure leaves the previous profile intact. */
    private boolean undo(List<ProfilePlan.Move> done) {
        boolean clean = true;
        for (int i = done.size() - 1; i >= 0; i--) {
            ProfilePlan.Move move = done.get(i);
            try {
                Files.move(move.to(), move.from(), StandardCopyOption.REPLACE_EXISTING);
                Log.warn("put " + move.filename() + " back where it was");
            } catch (IOException e) {
                clean = false;
                Log.error("could not put " + move.filename() + " back: " + e.getMessage());
            }
        }
        if (clean && !done.isEmpty()) {
            Log.warn("the profile was not applied; the previous set of mods is unchanged");
        }
        return clean;
    }
}
