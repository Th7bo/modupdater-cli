package dev.th7bo.modupdater.install;

import dev.th7bo.modupdater.diff.UpdateCandidate;
import dev.th7bo.modupdater.util.Hashing;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads, verifies and installs JARs, keeping the replaced ones until a
 * launch confirms the new build works.
 *
 * <p>Ordering matters: the download is verified <em>before</em> anything in
 * {@code mods/} is touched, so a bad or truncated artifact can never displace a
 * working one.
 */
public final class Installer {

    public static final String STATE_DIR = ".modupdater";
    private static final String STAGING_DIR = "staging";
    private static final String BACKUP_DIR = "backup";

    private final Downloader downloader;

    public Installer(Downloader downloader) {
        this.downloader = downloader;
    }

    /** @param failures filename → reason, for anything that did not install */
    public record Outcome(List<String> installed, Map<String, String> failures) {

        public Outcome {
            installed = installed == null ? List.of() : List.copyOf(installed);
            failures = failures == null ? Map.of() : Map.copyOf(failures);
        }

        public boolean anyInstalled() {
            return !installed.isEmpty();
        }
    }

    public static Path stateDir(Path modsDir) {
        return modsDir.resolve(STATE_DIR);
    }

    public Outcome install(List<UpdateCandidate> chosen, Path modsDir, String token) {
        List<String> installed = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        List<PendingState.Entry> entries = new ArrayList<>();

        if (chosen == null || chosen.isEmpty()) {
            return new Outcome(installed, failures);
        }

        Path state = stateDir(modsDir);
        Path staging = state.resolve(STAGING_DIR);
        Path backup = state.resolve(BACKUP_DIR);

        try {
            // Exactly one backup generation is kept. Older ones are worthless once
            // a launch has confirmed the newer build, and unbounded backups would
            // quietly fill the instance folder.
            deleteRecursively(backup);
            deleteRecursively(staging);
            Files.createDirectories(staging);
            Files.createDirectories(backup);
        } catch (IOException e) {
            Log.error("could not prepare " + STATE_DIR + ": " + e.getMessage());
            return new Outcome(installed, Map.of("*", String.valueOf(e.getMessage())));
        }

        for (UpdateCandidate candidate : chosen) {
            String filename = candidate.version().filename();
            Path staged = staging.resolve(filename);

            try {
                downloader.download(candidate.version().downloadUrl(), token, staged);

                String actual = Hashing.sha256(staged);
                if (!actual.equalsIgnoreCase(candidate.version().sha256())) {
                    Files.deleteIfExists(staged);
                    failures.put(filename, "checksum mismatch — discarded");
                    Log.error(filename + " failed checksum verification, not installed");
                    continue;
                }

                Path replaced = candidate.installed().path();
                Path backedUp = backup.resolve(replaced.getFileName().toString());
                Files.move(replaced, backedUp, StandardCopyOption.REPLACE_EXISTING);

                Path target = modsDir.resolve(filename);
                try {
                    Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    // The old JAR is already out of the way; put it back rather than
                    // leaving the instance with neither version present.
                    Files.move(backedUp, replaced, StandardCopyOption.REPLACE_EXISTING);
                    throw e;
                }

                entries.add(new PendingState.Entry(
                        candidate.modId(),
                        target.toAbsolutePath().toString(),
                        backedUp.toAbsolutePath().toString(),
                        replaced.toAbsolutePath().toString()));
                installed.add(filename);
                Log.info("installed " + filename);

            } catch (IOException e) {
                failures.put(filename, String.valueOf(e.getMessage()));
                Log.error("could not install " + filename + ": " + e.getMessage());
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }

        if (!entries.isEmpty()) {
            new PendingState(entries, false, System.currentTimeMillis()).write(state);
        }

        try {
            deleteRecursively(staging);
        } catch (IOException ignored) {
            // staging is disposable
        }

        return new Outcome(installed, failures);
    }

    /**
     * Undoes the previous run when no launch confirmed it — i.e. the game did not
     * get far enough to mark success, which usually means a new JAR crashed on init.
     *
     * @return the mod ids that were rolled back
     */
    public static List<String> restoreIfUnconfirmed(Path modsDir) {
        Path state = stateDir(modsDir);
        PendingState pending = PendingState.read(state);

        if (!pending.awaitingConfirmation()) {
            return List.of();
        }

        List<String> restored = new ArrayList<>();
        for (PendingState.Entry entry : pending.entries()) {
            try {
                Path backedUp = Path.of(entry.backupFile());
                Path original = Path.of(entry.replacedFile());
                Path installed = Path.of(entry.newFile());

                if (!Files.isRegularFile(backedUp)) {
                    Log.warn("no backup on disk for " + entry.modId() + ", leaving as-is");
                    continue;
                }

                Files.deleteIfExists(installed);
                Files.move(backedUp, original, StandardCopyOption.REPLACE_EXISTING);
                restored.add(entry.modId());
            } catch (IOException e) {
                Log.error("could not roll back " + entry.modId() + ": " + e.getMessage());
            }
        }

        PendingState.clear(state);
        return List.copyOf(restored);
    }

    /** Records that a launch got far enough to be considered good. */
    public static void confirmLaunch(Path modsDir) {
        Path state = stateDir(modsDir);
        PendingState pending = PendingState.read(state);
        if (pending.entries().isEmpty()) {
            return;
        }
        pending.confirmed().write(state);
    }

    /** What {@link #resolveAfterSession} decided about the session that just ended. */
    public sealed interface SessionOutcome {

        record NothingPending() implements SessionOutcome {
        }

        record Confirmed(int modCount) implements SessionOutcome {
        }

        record RolledBack(List<String> modIds) implements SessionOutcome {
        }
    }

    /**
     * Post-exit decision: did the session that followed an update actually work?
     *
     * <p>The hook fires either way, so a session shorter than {@code minSession}
     * is treated as a failed launch and rolled back. A crash on init returns in
     * seconds; a real session does not.
     */
    public static SessionOutcome resolveAfterSession(Path modsDir, Duration minSession, long nowMillis) {
        PendingState pending = PendingState.read(stateDir(modsDir));

        if (!pending.awaitingConfirmation()) {
            return new SessionOutcome.NothingPending();
        }

        long lasted = pending.sessionMillis(nowMillis);
        if (lasted >= minSession.toMillis()) {
            confirmLaunch(modsDir);
            return new SessionOutcome.Confirmed(pending.entries().size());
        }

        Log.warn("the game exited " + (lasted / 1000) + "s after updating — treating it as a failed launch");
        return new SessionOutcome.RolledBack(restoreIfUnconfirmed(modsDir));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
