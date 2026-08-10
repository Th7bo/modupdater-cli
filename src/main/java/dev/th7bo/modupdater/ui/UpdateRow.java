package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.diff.UpdateCandidate;

import java.util.ArrayList;
import java.util.List;

/** One line in the update dialog. */
public record UpdateRow(
        String name,
        String source,
        String installedVersion,
        String availableVersion,
        String mcVersion,
        String size,
        String change,
        UpdateCandidate candidate) {

    public static List<UpdateRow> from(List<UpdateCandidate> candidates) {
        List<UpdateRow> rows = new ArrayList<>();
        for (UpdateCandidate candidate : candidates) {
            rows.add(new UpdateRow(
                    candidate.label(),
                    candidate.mod().repoName(),
                    candidate.installed().versionLabel(),
                    versionLabel(candidate),
                    String.join(", ", candidate.version().mcVersions()),
                    humanSize(candidate.version().size()),
                    changeLabel(candidate),
                    candidate));
        }
        return List.copyOf(rows);
    }

    private static String versionLabel(UpdateCandidate candidate) {
        String version = candidate.version().modVersion();
        if (version == null || version.isBlank()) {
            return "new build";
        }

        // The platform builds from development commits, so the same release
        // version gets rebuilt repeatedly with different bytes. Showing
        // "7.44.0 -> 7.44.0" reads as a bug, so name the build instead.
        if (version.equals(candidate.installed().modVersion())) {
            String sha = candidate.version().sha256();
            return sha != null && sha.length() >= 8
                    ? version + " (build " + sha.substring(0, 8) + ")"
                    : version + " (new build)";
        }

        return version;
    }

    private static String changeLabel(UpdateCandidate candidate) {
        String summary = candidate.version().commitSummary();
        if (summary == null || summary.isBlank()) {
            return "no commit message";
        }
        return summary.length() > 80 ? summary.substring(0, 77) + "..." : summary;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
