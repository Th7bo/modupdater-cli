package dev.th7bo.modupdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Editing somebody's hand-written config in place.
 *
 * <p>This file holds their server address. Losing a line of it to a convenience
 * command would be a far worse bug than the typing it saves.
 */
class PropertiesFileTest {

    private static Path write(Path dir, String contents) throws IOException {
        Path file = dir.resolve("modupdater.properties");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void createsTheFileWhenThereIsNoneYet(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("modupdater.properties");

        PropertiesFile.set(file, "profiles.enabled", "true");

        assertEquals("profiles.enabled=true" + System.lineSeparator(), Files.readString(file));
    }

    @Test
    void appendsAKeyTheFileDoesNotHave(@TempDir Path dir) throws IOException {
        Path file = write(dir, "base.url=https://mods.example.com\nmc.version=1.21.4\n");

        PropertiesFile.set(file, "profiles.enabled", "true");

        assertEquals("""
                base.url=https://mods.example.com
                mc.version=1.21.4
                profiles.enabled=true
                """.replace("\n", System.lineSeparator()), Files.readString(file));
    }

    @Test
    void replacesAnExistingKeyWhereItStands(@TempDir Path dir) throws IOException {
        Path file = write(dir, """
                base.url=https://mods.example.com
                profiles.enabled=false
                mc.version=1.21.4
                """);

        PropertiesFile.set(file, "profiles.enabled", "true");

        String after = Files.readString(file);
        assertTrue(after.contains("profiles.enabled=true"));
        assertFalse(after.contains("profiles.enabled=false"));
        assertTrue(after.indexOf("profiles.enabled") < after.indexOf("mc.version"),
                "the key should stay where the user put it");
    }

    @Test
    void keepsCommentsAndBlankLines(@TempDir Path dir) throws IOException {
        // java.util.Properties.store would throw all of this away.
        Path file = write(dir, """
                # ModUpdater settings for my laptop
                base.url=https://mods.example.com

                # the exact version, matched literally
                mc.version=1.21.4
                """);

        PropertiesFile.set(file, "profiles.enabled", "true");

        String after = Files.readString(file);
        assertTrue(after.contains("# ModUpdater settings for my laptop"));
        assertTrue(after.contains("# the exact version, matched literally"));
        assertTrue(after.contains("\n\n") || after.contains("\r\n\r\n"), "the blank line survives");
    }

    @Test
    void doesNotMistakeACommentedOutKeyForTheRealOne(@TempDir Path dir) throws IOException {
        Path file = write(dir, "#profiles.enabled=true\nbase.url=https://mods.example.com\n");

        PropertiesFile.set(file, "profiles.enabled", "true");

        String after = Files.readString(file);
        assertTrue(after.contains("#profiles.enabled=true"), "their note stays a note");
        assertTrue(after.lines().anyMatch(line -> line.equals("profiles.enabled=true")),
                "and a real setting is added");
    }

    @Test
    void handlesTheColonForm(@TempDir Path dir) throws IOException {
        Path file = write(dir, "profiles.enabled : false\n");

        PropertiesFile.set(file, "profiles.enabled", "true");

        assertEquals(1, Files.readString(file).lines().filter(line -> !line.isBlank()).count(),
                "the same key written with a colon must be replaced, not duplicated");
        assertTrue(Files.readString(file).contains("profiles.enabled=true"));
    }

    @Test
    void keepsWindowsLineEndings(@TempDir Path dir) throws IOException {
        Path file = write(dir, "base.url=https://mods.example.com\r\nmc.version=1.21.4\r\n");

        PropertiesFile.set(file, "profiles.enabled", "true");

        String after = Files.readString(file);
        assertTrue(after.endsWith("profiles.enabled=true\r\n"));
        assertFalse(after.replace("\r\n", "").contains("\n"), "no stray bare newlines");
    }

    @Test
    void keepsAByteOrderMark(@TempDir Path dir) throws IOException {
        // Notepad and PowerShell 5.1 both write one, and Config copes with it.
        // Silently dropping it here would be a change we did not intend to make.
        Path file = dir.resolve("modupdater.properties");
        Files.write(file, ("﻿base.url=https://mods.example.com\n").getBytes(StandardCharsets.UTF_8));

        PropertiesFile.set(file, "profiles.enabled", "true");

        assertTrue(Files.readString(file).startsWith("﻿"));
    }

    @Test
    void leavesTheFileReadableByConfig(@TempDir Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));
        Path file = write(dir, "base.url=https://mods.example.com\nmc.version=1.21.4\n");

        PropertiesFile.set(file, "profiles.enabled", "true");

        Config config = Config.resolve(new String[]{"--mods-dir", mods.toString()});
        assertTrue(config.profilesEnabled());
        assertEquals("https://mods.example.com", config.baseUrl(), "nothing else moved");
        assertEquals("1.21.4", config.mcVersion());
    }

    @Test
    void leavesNoTemporaryFileBehind(@TempDir Path dir) throws IOException {
        Path file = write(dir, "base.url=https://mods.example.com\n");

        PropertiesFile.set(file, "profiles.enabled", "true");

        try (var entries = Files.list(dir)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
