package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.Jars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Reading a mod's own artwork out of its JAR.
 *
 * <p>Runs headless, like the rest of the suite: decoding a PNG and scaling it
 * needs no display, which is what makes this part of the UI testable at all.
 */
class ModIconTest {

    /** A mod JAR whose {@code fabric.mod.json} points at a real PNG inside it. */
    private static Path modWithIcon(Path dir, String filename, String modId, String iconField)
            throws IOException {

        Path path = dir.resolve(filename);
        String json = """
                { "schemaVersion": 1, "id": "%s", "version": "1.0.0", "icon": %s }
                """.formatted(modId, iconField);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("assets/" + modId + "/icon.png"));
            zip.write(png(64, 0xFF3366CC));
            zip.closeEntry();
        }

        return path;
    }

    private static byte[] png(int size, int argb) throws IOException {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                image.setRGB(x, y, argb);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void readsTheIconAModDeclares(@TempDir Path dir) throws IOException {
        Path jar = modWithIcon(dir, "skyhanni.jar", "skyhanni", "\"assets/skyhanni/icon.png\"");

        Icon icon = ModIcon.of(jar);

        assertNotNull(icon);
        assertEquals(ModIcon.SIZE, icon.getIconWidth());
        assertEquals(ModIcon.SIZE, icon.getIconHeight());
    }

    @Test
    void readsTheSizedForm(@TempDir Path dir) throws IOException {
        // Some mods declare {"32": "...", "128": "..."} instead of a bare path.
        Path jar = modWithIcon(dir, "sized.jar", "sized",
                "{ \"16\": \"assets/sized/missing.png\", \"64\": \"assets/sized/icon.png\" }");

        assertEquals(ModIcon.SIZE, ModIcon.of(jar).getIconWidth());
    }

    @Test
    void fallsBackToABlankOfTheSameSizeWhenThereIsNoIcon(@TempDir Path dir) throws IOException {
        // A missing picture should leave a gap the same width as the others, not
        // shunt one row's text out of line with the rest.
        Path jar = Jars.modJar(dir, "plain.jar", "plain", "1.0.0");

        assertSame(ModIcon.blank(), ModIcon.of(jar));
        assertEquals(ModIcon.SIZE, ModIcon.of(jar).getIconWidth());
    }

    @Test
    void survivesAnIconPathPointingAtNothing(@TempDir Path dir) throws IOException {
        Path path = dir.resolve("broken.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("""
                    { "schemaVersion": 1, "id": "broken", "version": "1.0.0",
                      "icon": "assets/broken/not-there.png" }
                    """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertSame(ModIcon.blank(), ModIcon.of(path));
    }

    @Test
    void survivesAJarThatIsNotAZip(@TempDir Path dir) throws IOException {
        assertSame(ModIcon.blank(), ModIcon.of(Jars.corruptJar(dir, "rubbish.jar")));
    }

    @Test
    void survivesAMissingFileAndANullPath(@TempDir Path dir) {
        assertSame(ModIcon.blank(), ModIcon.of(dir.resolve("gone.jar")));
        assertSame(ModIcon.blank(), ModIcon.of(null));
    }

    @Test
    void readsAFreshIconAfterTheJarChanges(@TempDir Path dir) throws IOException {
        // The cache is keyed by modification time, so an update does not keep
        // showing the old build's artwork.
        Path jar = Jars.modJar(dir, "changing.jar", "changing", "1.0.0");
        assertSame(ModIcon.blank(), ModIcon.of(jar));

        Files.delete(jar);
        Path replaced = modWithIcon(dir, "changing.jar", "changing", "\"assets/changing/icon.png\"");
        Files.setLastModifiedTime(replaced,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));

        assertEquals(ModIcon.SIZE, ModIcon.of(replaced).getIconWidth());
    }
}
