package dev.th7bo.modupdater.install;

import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.manifest.Manifest;
import dev.th7bo.modupdater.util.Hashing;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Installs a mod the instance does not have yet, straight from the manifest.
 *
 * Separate from {@link Installer}: nothing is being replaced, so there is no
 * backup to keep and no rollback to arrange. Used by the setup script to offer
 * the in-game notifier, which otherwise means telling people to download a JAR
 * and find their mods folder — the exact friction this project exists to remove.
 */
public final class ModInstaller {

    /** What happened, so the caller can say something useful. */
    public sealed interface Result {

        record Installed(String filename) implements Result {
        }

        record AlreadyPresent(String filename) implements Result {
        }

        record NotOffered(String modId) implements Result {
        }

        record Failed(String detail) implements Result {
        }
    }

    private final Downloader downloader;

    public ModInstaller(Downloader downloader) {
        this.downloader = downloader;
    }

    /**
     * @param manifest already filtered to the instance's Minecraft version, so
     *     the first version offered for the mod is the right one
     */
    public Result install(Manifest manifest, String modId, Path modsDir, String token) {
        Manifest.Version version = manifest.mods().stream()
                .filter(mod -> modId.equals(mod.modId()))
                .flatMap(mod -> mod.versions().stream())
                .filter(candidate -> candidate.sha256() != null && !candidate.sha256().isBlank())
                .findFirst()
                .orElse(null);

        if (version == null) {
            return new Result.NotOffered(modId);
        }

        if (alreadyPresent(modsDir, version.sha256())) {
            return new Result.AlreadyPresent(version.filename());
        }

        Path staging = ModPaths.of(modsDir).stagingDir();
        Path staged = staging.resolve(version.filename());

        try {
            Files.createDirectories(staging);
            downloader.download(version.downloadUrl(), token, staged);

            String actual = Hashing.sha256(staged);
            if (!actual.equalsIgnoreCase(version.sha256())) {
                Files.deleteIfExists(staged);
                return new Result.Failed("checksum mismatch — discarded");
            }

            Files.move(staged, modsDir.resolve(version.filename()), StandardCopyOption.REPLACE_EXISTING);
            return new Result.Installed(version.filename());

        } catch (IOException e) {
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // best effort
            }
            return new Result.Failed(reason);
        }
    }

    /**
     * Matched by content, not filename: a JAR renamed on the way in is still the
     * same build, and installing a second copy would leave the loader with two.
     */
    private static boolean alreadyPresent(Path modsDir, String sha256) {
        if (!Files.isDirectory(modsDir)) {
            return false;
        }

        try (var entries = Files.list(modsDir)) {
            List<Path> jars = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .toList();

            for (Path jar : jars) {
                try {
                    if (Hashing.sha256(jar).equalsIgnoreCase(sha256)) {
                        return true;
                    }
                } catch (IOException e) {
                    Log.warn("could not hash " + jar.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            return false;
        }

        return false;
    }
}
