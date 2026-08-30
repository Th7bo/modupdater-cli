package dev.th7bo.modupdater.update;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Which build this is, and where it lives.
 *
 * <p>Answered from the running JAR rather than from anything written down: the
 * file about to be replaced is the one currently executing, and asking it
 * directly is the only way to be sure those are the same file.
 *
 * @param jar     the JAR being executed, or null when running from loose classes
 * @param version what its manifest says, or {@link Version#UNKNOWN}
 */
public record Installed(Path jar, Version version) {

    /** The name the installer gives the JAR, and the one the wrapper scripts look for. */
    public static final String JAR_NAME = "modupdater-cli.jar";

    public static Installed locate() {
        Path jar = runningJar();
        return new Installed(jar, jar == null ? Version.UNKNOWN : versionOf(jar));
    }

    /** False when running from a build directory, where there is nothing to replace. */
    public boolean fromJar() {
        return jar != null;
    }

    /** Where the wrapper scripts sit — the installer puts them beside the JAR. */
    public Path directory() {
        return jar == null ? null : jar.getParent();
    }

    /**
     * The JAR this JVM is running out of.
     *
     * @return null when the code source is a directory, unreadable, or absent —
     *     all of which mean this is not an installed copy
     */
    private static Path runningJar() {
        try {
            var source = Installed.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }

            Path path = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(path) ? path.toAbsolutePath().normalize() : null;
        } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
            return null;
        }
    }

    /** Reads {@code Implementation-Version} straight out of the JAR's own manifest. */
    static Version versionOf(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return Version.UNKNOWN;
            }
            String value = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            return value == null || value.isBlank() ? Version.UNKNOWN : Version.of(value);
        } catch (IOException | SecurityException e) {
            return Version.UNKNOWN;
        }
    }

    /** Whether a downloaded file is plausibly this program, before it replaces this program. */
    static boolean looksLikeOurJar(Path candidate) {
        try (JarFile file = new JarFile(candidate.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                return false;
            }
            String main = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
            return main != null && main.startsWith("dev.th7bo.modupdater");
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
