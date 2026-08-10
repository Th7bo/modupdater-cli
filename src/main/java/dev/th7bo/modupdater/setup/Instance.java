package dev.th7bo.modupdater.setup;

import java.nio.file.Path;

/**
 * A Minecraft instance found on disk.
 *
 * @param launcher   "Prism" / "MultiMC" / "Modrinth"
 * @param name       what the launcher shows the user
 * @param mcVersion  null when it could not be determined — Modrinth App keeps
 *                   this in a SQLite database, so the installer has to ask
 * @param gameDir    the directory containing {@code mods/}
 * @param instanceCfg Prism's {@code instance.cfg}, or null for other launchers
 */
public record Instance(
        String launcher,
        String name,
        String mcVersion,
        Path gameDir,
        Path instanceCfg) {

    public Path modsDir() {
        return gameDir.resolve("mods");
    }

    /** Prism reads hook commands from instance.cfg, so those can be configured for the user. */
    public boolean supportsAutoConfig() {
        return instanceCfg != null;
    }

    public String versionLabel() {
        return mcVersion == null || mcVersion.isBlank() ? "unknown" : mcVersion;
    }

    /** Tab-separated, for the installer scripts to render a menu from. */
    public String toTsv() {
        return String.join("\t",
                launcher,
                name,
                versionLabel(),
                gameDir.toString(),
                instanceCfg == null ? "" : instanceCfg.toString());
    }
}
