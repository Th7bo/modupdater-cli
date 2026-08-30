package dev.th7bo.modupdater.update;

import dev.th7bo.modupdater.install.Downloader;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Replaces this program with the newest release.
 *
 * <p>The awkward part is that the file being replaced is the file being
 * executed. Every swap here is a rename rather than a write over the top: on
 * Windows the JVM holds the JAR open and {@code cmd.exe} reads the wrapper batch
 * file as it runs, and overwriting either is refused or corrupts a run in
 * progress. Renaming leaves those open handles pointing at the renamed file,
 * which keeps working, while the new file takes the name for next time.
 */
public final class SelfUpdate {

    /** Wrapper scripts to refresh, when the install already has them. */
    private static final List<String> WRAPPERS = List.of("modupdater.sh", "modupdater.bat");

    private static final String SUPERSEDED = ".old";
    private static final String INCOMING = ".new";

    private final ReleaseSource releases;
    private final Downloader downloader;

    public SelfUpdate(ReleaseSource releases, Downloader downloader) {
        this.releases = releases;
        this.downloader = downloader;
    }

    public sealed interface Result {

        /** @param refreshed the wrapper scripts brought up to date alongside the JAR */
        record Updated(Version from, Version to, Path jar, List<String> refreshed) implements Result {
        }

        record UpToDate(Version version) implements Result {
        }

        /** Running from loose classes, or from somewhere with nothing to replace. */
        record NotInstalled() implements Result {
        }

        /** The install is somewhere this user cannot write — a system-wide copy, say. */
        record ReadOnly(Path jar) implements Result {
        }

        record Failed(String detail) implements Result {
        }
    }

    public Result run() {
        return run(Installed.locate());
    }

    /** @param installed visible for testing, so a fixture directory can stand in */
    public Result run(Installed installed) {
        if (!installed.fromJar()) {
            return new Result.NotInstalled();
        }

        Path jar = installed.jar();
        Path directory = installed.directory();

        // Left behind by the previous update: the old file could not be deleted
        // while it was still open. Nothing holds it now.
        sweep(directory);

        Release release;
        try {
            release = releases.latest();
        } catch (IOException e) {
            return new Result.Failed(message(e));
        }

        if (!installed.version().supersededBy(release.version())) {
            return new Result.UpToDate(installed.version());
        }

        if (!Files.isWritable(directory) || !Files.isWritable(jar)) {
            return new Result.ReadOnly(jar);
        }

        Path zip = directory.resolve(Release.ASSET + INCOMING);
        try {
            Log.info("downloading " + release.tag());
            downloader.download(release.downloadUrl(), null, zip);
            return install(installed, release, zip);
        } catch (IOException e) {
            return new Result.Failed(message(e));
        } finally {
            deleteQuietly(zip);
        }
    }

    private Result install(Installed installed, Release release, Path zip) throws IOException {
        Path directory = installed.directory();
        Path stagedJar = directory.resolve(Installed.JAR_NAME + INCOMING);
        List<Path> staged = new ArrayList<>();
        List<String> refreshed = new ArrayList<>();

        try (ZipFile archive = new ZipFile(zip.toFile())) {
            if (!extract(archive, Installed.JAR_NAME, stagedJar)) {
                return new Result.Failed(
                        "the download had no " + Installed.JAR_NAME + " in it");
            }
            staged.add(stagedJar);

            // Verified before anything is moved, so a truncated or wrong download
            // can never displace a working install.
            if (!Installed.looksLikeOurJar(stagedJar)) {
                return new Result.Failed("the downloaded JAR is not a ModUpdater build — not installing it");
            }

            // Only the wrappers this install already has. Adding the other
            // platform's script would leave a file the installer never put there.
            for (String wrapper : WRAPPERS) {
                Path existing = directory.resolve(wrapper);
                if (!Files.isRegularFile(existing)) {
                    continue;
                }
                Path stagedWrapper = directory.resolve(wrapper + INCOMING);
                if (extract(archive, wrapper, stagedWrapper)) {
                    staged.add(stagedWrapper);
                    refreshed.add(wrapper);
                }
            }
        }

        replace(installed.jar(), stagedJar);
        staged.remove(stagedJar);

        for (String wrapper : List.copyOf(refreshed)) {
            Path stagedWrapper = directory.resolve(wrapper + INCOMING);
            try {
                makeExecutableLike(directory.resolve(wrapper), stagedWrapper);
                replace(directory.resolve(wrapper), stagedWrapper);
                staged.remove(stagedWrapper);
            } catch (IOException e) {
                // The JAR is already in place and is what actually matters; a
                // wrapper left at its old version still runs.
                Log.warn("could not refresh " + wrapper + ": " + message(e));
                refreshed.remove(wrapper);
            }
        }

        staged.forEach(SelfUpdate::deleteQuietly);

        return new Result.Updated(
                installed.version(), release.version(), installed.jar(), List.copyOf(refreshed));
    }

    /** @return false when the archive has no such entry */
    private static boolean extract(ZipFile archive, String name, Path destination) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null) {
            return false;
        }

        try (InputStream in = archive.getInputStream(entry)) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * Puts {@code replacement} where {@code target} is, by renaming rather than
     * writing over it — see the class comment.
     */
    private static void replace(Path target, Path replacement) throws IOException {
        Path aside = target.resolveSibling(target.getFileName() + SUPERSEDED);
        deleteQuietly(aside);

        boolean movedAside = false;
        if (Files.exists(target)) {
            Files.move(target, aside, StandardCopyOption.REPLACE_EXISTING);
            movedAside = true;
        }

        try {
            move(replacement, target);
        } catch (IOException e) {
            if (movedAside) {
                // Put the working copy back rather than leaving nothing at the
                // name the wrapper scripts and the launcher hooks point at.
                Files.move(aside, target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }

        // Where the rename already released it — everywhere but Windows, and
        // Windows once the process exits — it goes now rather than lingering
        // until the next update sweeps it.
        deleteQuietly(aside);
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Carries the old script's permissions over, so an executable wrapper stays executable. */
    private static void makeExecutableLike(Path existing, Path staged) {
        try {
            if (Files.isExecutable(existing)) {
                var permissions = Files.getPosixFilePermissions(staged);
                permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
                permissions.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
                permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(staged, permissions);
            }
        } catch (IOException | UnsupportedOperationException e) {
            // Windows has no POSIX permissions and needs none of this.
        }
    }

    /** Clears what a previous update could not delete while the files were open. */
    private static void sweep(Path directory) {
        try (var entries = Files.list(directory)) {
            entries.filter(path -> path.getFileName().toString().endsWith(SUPERSEDED))
                    .forEach(SelfUpdate::deleteQuietly);
        } catch (IOException e) {
            // Nothing here is load-bearing; the next run tries again.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Still open, most likely. Swept on a later run.
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.toString() : e.getMessage();
    }
}
