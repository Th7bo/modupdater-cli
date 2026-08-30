package dev.th7bo.modupdater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets one key in {@code modupdater.properties} without disturbing the rest.
 *
 * <p>Deliberately line-based rather than {@link java.util.Properties#store}: that
 * drops every comment, reorders the keys and rewrites the escaping, so a file
 * somebody wrote by hand comes back unrecognisable after a one-word change.
 *
 * <p>A BOM and CRLF line endings are both preserved. Windows users get one or
 * both from Notepad and PowerShell 5.1, and rewriting the file should not be
 * what changes them.
 */
final class PropertiesFile {

    private static final char BOM = '﻿';

    private PropertiesFile() {
    }

    /**
     * Writes {@code key=value}, replacing the existing line in place if there is
     * one and appending it otherwise. Creates the file, and any missing parent
     * directory, when it does not exist yet.
     *
     * @return true when the file now says what was asked for
     */
    static boolean set(Path file, String key, String value) throws IOException {
        String existing = Files.isRegularFile(file)
                ? Files.readString(file, StandardCharsets.UTF_8)
                : "";

        boolean bom = existing.startsWith(String.valueOf(BOM));
        if (bom) {
            existing = existing.substring(1);
        }

        String newline = existing.contains("\r\n") ? "\r\n" : System.lineSeparator();
        String line = key + "=" + value;

        List<String> lines = new ArrayList<>(List.of(existing.split("\r\n|\n|\r", -1)));

        // A trailing newline leaves an empty last element. Drop it, and put the
        // newline back when joining, so appending does not leave a blank line
        // stranded in the middle of the file.
        boolean trailingNewline = !lines.isEmpty() && lines.get(lines.size() - 1).isEmpty();
        if (trailingNewline) {
            lines.remove(lines.size() - 1);
        }

        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (declares(lines.get(i), key)) {
                lines.set(i, line);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            lines.add(line);
        }

        String body = (bom ? String.valueOf(BOM) : "") + String.join(newline, lines) + newline;
        writeAtomically(file, body);
        return true;
    }

    /** Whether this line sets that key — ignoring comments, spacing and {@code :} form. */
    private static boolean declares(String line, String key) {
        String trimmed = line.stripLeading();
        if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
            return false;
        }

        int separator = indexOfSeparator(trimmed);
        String name = separator < 0 ? trimmed : trimmed.substring(0, separator);
        return name.strip().equals(key);
    }

    private static int indexOfSeparator(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Through a temporary file in the same directory, so an interrupted write
     * cannot leave the instance with a half-written config — which the loader
     * would read as "no server configured".
     */
    private static void writeAtomically(Path file, String contents) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);

        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
