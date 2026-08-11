package dev.th7bo.modupdater.install;

import dev.th7bo.modupdater.diff.UpdateCandidate;

import java.nio.file.Path;
import java.util.List;

/**
 * One JAR to fetch and put in place.
 *
 * The unit both install paths share: the pre-launch dialog builds these from the
 * manifest, and `apply` builds them from the request the in-game mod wrote. One
 * shape means one implementation of verify-then-swap, rather than two that can
 * drift on the part where a mistake costs somebody their mods folder.
 */
public record InstallItem(
        String modId,
        String filename,
        String sha256,
        String downloadUrl,
        /** The installed JAR being replaced. */
        Path replaces) {

    public static List<InstallItem> of(List<UpdateCandidate> candidates) {
        return candidates.stream()
                .map(candidate -> new InstallItem(
                        candidate.modId(),
                        candidate.version().filename(),
                        candidate.version().sha256(),
                        candidate.version().downloadUrl(),
                        candidate.installed().path()))
                .toList();
    }
}
