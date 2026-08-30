package dev.th7bo.modupdater.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logs to stdout and, when a directory has been set, to {@code .modupdater/log.txt}.
 *
 * <p>Every message passes through {@link #redact} so a token can never reach the
 * log file even if it ends up inside an exception message or a URL.
 *
 * <p>The directory is created by the first line written, not by {@link #init}.
 * {@code modupdater} is on PATH now, so a command like {@code profile list} runs
 * from wherever the user is standing and its instance is only settled afterwards
 * — creating the folder up front left a stray {@code mods/.modupdater} in
 * whatever directory they happened to be in.
 */
public final class Log {

    private static Path stateDir;
    private static Path logFile;
    private static String secret;

    private Log() {
    }

    public static synchronized void init(Path stateDir, String tokenToRedact) {
        Log.stateDir = stateDir;
        Log.logFile = null;
        Log.secret = tokenToRedact;
    }

    /** Visible for testing: clears state between cases. */
    public static synchronized void reset() {
        stateDir = null;
        logFile = null;
        secret = null;
    }

    public static void info(String message) {
        write("INFO", message);
    }

    public static void warn(String message) {
        write("WARN", message);
    }

    public static void error(String message) {
        write("ERROR", message);
    }

    static String redact(String message) {
        if (message == null) {
            return "";
        }
        if (secret == null || secret.isBlank()) {
            return message;
        }
        return message.replace(secret, "***");
    }

    private static synchronized void write(String level, String message) {
        String safe = redact(message);
        String line = "[modupdater] " + level + " " + safe;
        System.out.println(line);

        if (!openLogFile()) {
            return;
        }

        String stamped = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                + " " + level + " " + safe + System.lineSeparator();
        try {
            Files.writeString(logFile, stamped, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A failing log must never take the process down.
            logFile = null;
            stateDir = null;
        }
    }

    /** @return false when there is nowhere to write, which is not an error */
    private static boolean openLogFile() {
        if (logFile != null) {
            return true;
        }
        if (stateDir == null) {
            return false;
        }

        try {
            Files.createDirectories(stateDir);
            logFile = stateDir.resolve("log.txt");
            return true;
        } catch (IOException e) {
            stateDir = null;
            System.out.println("[modupdater] could not open log file: " + redact(e.getMessage()));
            return false;
        }
    }
}
