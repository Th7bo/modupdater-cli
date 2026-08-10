package dev.th7bo.modupdater.manifest;

import java.util.List;

/**
 * The platform's client manifest (ModUpdater REQUIREMENTS §12.3).
 *
 * <p>Grouped by repo + mod id server-side, so the same mod id from an upstream
 * and from a personal fork arrives as two entries rather than one merged one.
 */
public record Manifest(String generatedAt, List<Mod> mods) {

    public Manifest {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }

    public record Mod(
            String modId,
            String displayName,
            String repoId,
            String repoName,
            List<Version> versions) {

        public Mod {
            versions = versions == null ? List.of() : List.copyOf(versions);
        }

        public String label() {
            return displayName != null && !displayName.isBlank() ? displayName : modId;
        }
    }

    public record Version(
            String modVersion,
            String loader,
            List<String> mcVersions,
            String mcVersionsRaw,
            String filename,
            String sha256,
            long size,
            String downloadUrl,
            String buildId,
            String builtAt,
            String commitHash,
            String commitSummary) {

        public Version {
            mcVersions = mcVersions == null ? List.of() : List.copyOf(mcVersions);
        }

        /**
         * Exact-string match only. §12.1 normalizes version ranges server-side
         * precisely so there is one implementation of that logic, not two.
         */
        public boolean supports(String mcVersion) {
            return mcVersion != null && mcVersions.contains(mcVersion);
        }
    }
}
