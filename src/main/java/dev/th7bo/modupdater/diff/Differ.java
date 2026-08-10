package dev.th7bo.modupdater.diff;

import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.manifest.Manifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides what is updatable. Pure functions, no I/O — this is the part that has
 * to be right, so it is kept trivially testable.
 *
 * <p>An installed mod is updatable when the manifest offers an entry with the
 * same mod id, whose {@code mcVersions} contains the instance's Minecraft
 * version, and whose SHA-256 differs from what is installed.
 */
public final class Differ {

    private Differ() {
    }

    public static List<UpdateCandidate> plan(
            List<InstalledMod> installed,
            Manifest manifest,
            String mcVersion) {

        if (installed == null || manifest == null || mcVersion == null || mcVersion.isBlank()) {
            return List.of();
        }

        List<UpdateCandidate> candidates = new ArrayList<>();

        for (InstalledMod mod : installed) {
            if (!mod.matchable()) {
                continue;
            }

            for (Manifest.Mod offered : manifest.mods()) {
                if (!mod.modId().equals(offered.modId())) {
                    continue;
                }

                // Two repos can publish the same mod id (an upstream and a fork of
                // it). Each is a separate choice for the user; merging them would
                // silently pick one.
                newestFor(offered, mcVersion, mod.sha256())
                        .ifPresent(version -> candidates.add(new UpdateCandidate(mod, offered, version)));
            }
        }

        return List.copyOf(candidates);
    }

    /**
     * The first version supporting this MC version whose hash differs from what's
     * installed. Manifest order is the server's newest-successful-build order, so
     * first match is the current build.
     */
    private static java.util.Optional<Manifest.Version> newestFor(
            Manifest.Mod offered,
            String mcVersion,
            String installedSha) {

        return offered.versions().stream()
                .filter(version -> version.supports(mcVersion))
                .filter(version -> version.sha256() != null && !version.sha256().isBlank())
                // Same bytes as what's on disk is not an update; offering it would
                // be a no-op download the user has to think about.
                .filter(version -> !version.sha256().equalsIgnoreCase(installedSha))
                .findFirst();
    }
}
