package dev.th7bo.modupdater;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Settings, resolved as: command-line flags &gt; {@code modupdater.properties} in
 * the instance directory &gt; environment variables.
 *
 * <p>The token is deliberately not a flag. Command lines are visible to every
 * process on the machine, so it is read from a file or the environment only.
 */
public record Config(
        String baseUrl,
        Path modsDir,
        String mcVersion,
        Path tokenFile,
        String relaunchCommand) {

    private static final String PROPERTIES_FILE = "modupdater.properties";

    public static Config resolve(String[] args) {
        Map<String, String> flags = parseFlags(args);

        Path modsDir = Path.of(value(flags, "mods-dir", "MODUPDATER_MODS_DIR", "mods"))
                .toAbsolutePath()
                .normalize();

        Properties file = loadProperties(modsDir.getParent());

        String baseUrl = resolve(flags, file, "base-url", "base.url", "MODUPDATER_BASE_URL", null);
        String mcVersion = resolve(flags, file, "mc", "mc.version", "MODUPDATER_MC_VERSION", null);
        String relaunch = resolve(flags, file, "relaunch-command", "relaunch.command",
                "MODUPDATER_RELAUNCH_COMMAND", null);

        String tokenPath = resolve(flags, file, "token-file", "token.file", "MODUPDATER_TOKEN_FILE", null);
        Path tokenFile = tokenPath == null
                ? modsDir.resolve(".modupdater").resolve("token")
                : Path.of(tokenPath);

        return new Config(baseUrl, modsDir, mcVersion, tokenFile, relaunch);
    }

    /** @return the token, or null when none is configured */
    public String readToken() {
        String fromEnv = System.getenv("MODUPDATER_TOKEN");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }

        if (tokenFile == null || !Files.isRegularFile(tokenFile)) {
            return null;
        }

        try {
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            return token.isBlank() ? null : token;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean usable() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    private static Map<String, String> parseFlags(String[] args) {
        Map<String, String> flags = new HashMap<>();
        if (args == null) {
            return flags;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }

            String name = arg.substring(2);
            int equals = name.indexOf('=');
            if (equals >= 0) {
                flags.put(name.substring(0, equals), name.substring(equals + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                flags.put(name, args[++i]);
            } else {
                flags.put(name, "true");
            }
        }

        return flags;
    }

    private static Properties loadProperties(Path instanceDir) {
        Properties properties = new Properties();
        if (instanceDir == null) {
            return properties;
        }

        Path file = instanceDir.resolve(PROPERTIES_FILE);
        if (!Files.isRegularFile(file)) {
            return properties;
        }

        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Java's UTF-8 decoder keeps a byte-order mark as a character, so a
            // BOM makes the first key "﻿base.url" and the setting silently
            // goes missing. Both Notepad and Windows PowerShell 5.1 write one.
            reader.mark(1);
            if (reader.read() != '﻿') {
                reader.reset();
            }
            properties.load(reader);
        } catch (IOException e) {
            // A broken config file is not a reason to block the launch.
            return new Properties();
        }

        return properties;
    }

    private static String resolve(
            Map<String, String> flags,
            Properties file,
            String flag,
            String property,
            String env,
            String fallback) {

        String fromFlag = flags.get(flag);
        if (fromFlag != null && !fromFlag.isBlank()) {
            return fromFlag;
        }

        String fromFile = file.getProperty(property);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }

        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }

        return fallback;
    }

    private static String value(Map<String, String> flags, String flag, String env, String fallback) {
        String fromFlag = flags.get(flag);
        if (fromFlag != null && !fromFlag.isBlank()) {
            return fromFlag;
        }
        String fromEnv = System.getenv(env);
        return fromEnv != null && !fromEnv.isBlank() ? fromEnv : fallback;
    }
}
