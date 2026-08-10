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
 */
public final class Log {

    private static Path logFile;
    private static String secret;

    private Log() {
    }

    public static synchronized void init(Path stateDir, String tokenToRedact) {
        secret = tokenToRedact;
        try {
            Files.createDirectories(stateDir);
            logFile = stateDir.resolve("log.txt");
        } catch (IOException e) {
            logFile = null;
            System.out.println("[modupdater] could not open log file: " + redact(e.getMessage()));
        }
    }

    /** Visible for testing: clears state between cases. */
    public static synchronized void reset() {
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

        if (logFile == null) {
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
        }
    }
}
