package dev.th7bo.modupdater.instance;

import java.nio.file.Path;

/**
 * The layout inside an instance's {@code mods/} folder.
 *
 * <p>The one place that knows what a directory means. Whether a JAR on disk is
 * loaded by the game, held back for another profile, or a backup waiting to be
 * discarded is answered here and nowhere else — duplicating that knowledge is
 * how a rollback ends up putting a mod in the wrong place.
 *
 * <pre>
 * mods/
 * ├── SkyHanni.jar            active — Fabric loads these
 * └── .modupdater/
 *     ├── token
 *     ├── log.txt
 *     ├── inactive/           installed, but not part of the current profile
 *     ├── backup/             replaced JARs, kept until a launch confirms
 *     └── staging/            downloads being verified
 * </pre>
 */
public record ModPaths(Path modsDir) {

    public static final String STATE = ".modupdater";
    public static final String INACTIVE = "inactive";
    public static final String BACKUP = "backup";
    public static final String STAGING = "staging";

    public ModPaths {
        modsDir = modsDir.toAbsolutePath().normalize();
    }

    public static ModPaths of(Path modsDir) {
        return new ModPaths(modsDir);
    }

    public Path stateDir() {
        return modsDir.resolve(STATE);
    }

    /** Installed mods that the current profile leaves out. Never created unless used. */
    public Path inactiveDir() {
        return stateDir().resolve(INACTIVE);
    }

    public Path backupDir() {
        return stateDir().resolve(BACKUP);
    }

    public Path stagingDir() {
        return stateDir().resolve(STAGING);
    }

    /** Where a JAR of this name lives when the profile has it switched on. */
    public Path activePath(String filename) {
        return modsDir.resolve(filename);
    }

    /** Where a JAR of this name lives when the profile has it switched off. */
    public Path inactivePath(String filename) {
        return inactiveDir().resolve(filename);
    }

    public boolean isInactive(Path jar) {
        return jar != null && inactiveDir().equals(normalisedParent(jar));
    }

    public boolean isActive(Path jar) {
        return jar != null && modsDir.equals(normalisedParent(jar));
    }

    private static Path normalisedParent(Path jar) {
        Path parent = jar.toAbsolutePath().normalize().getParent();
        return parent == null ? null : parent;
    }
}
