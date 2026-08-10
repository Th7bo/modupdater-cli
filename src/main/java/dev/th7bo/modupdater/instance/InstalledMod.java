package dev.th7bo.modupdater.instance;

import java.nio.file.Path;

/**
 * A JAR found in the instance's {@code mods/} folder.
 *
 * @param modId null when {@code fabric.mod.json} was missing or unreadable — the
 *              JAR is still listed, it just can't be matched against the manifest
 */
public record InstalledMod(Path path, String filename, String modId, String modVersion, String sha256) {

    public boolean matchable() {
        return modId != null && !modId.isBlank();
    }

    public String versionLabel() {
        return modVersion == null || modVersion.isBlank() ? "unknown" : modVersion;
    }
}
