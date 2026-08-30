package dev.th7bo.modupdater;

import dev.th7bo.modupdater.instance.ModPaths;

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
 *
 * <p>The profile settings are per instance because that is where this file
 * lives: a laptop can pick a mod set at every launch while the desktop, whose
 * properties file says nothing about profiles, behaves exactly as it always has.
 *
 * @param profilesEnabled  off unless the instance asks for it — see {@code profiles.enabled}
 * @param profileDefault   the profile to start from when nothing is remembered
 * @param profilePrompt    whether to ask at launch, or just apply the default
 * @param profileRemember  whether the last choice becomes the next default
 */
public record Config(
        String baseUrl,
        Path modsDir,
        String mcVersion,
        Path tokenFile,
        String relaunchCommand,
        boolean profilesEnabled,
        String profileDefault,
        boolean profilePrompt,
        boolean profileRemember) {

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
                ? ModPaths.of(modsDir).stateDir().resolve("token")
                : Path.of(tokenPath);

        // Absent means off. Existing instances have no profile properties at all,
        // and must go on behaving as though the feature does not exist.
        boolean profilesEnabled = flag(flags, file, "profiles-enabled", "profiles.enabled",
                "MODUPDATER_PROFILES_ENABLED", false);
        String profileDefault = resolve(flags, file, "profile", "profile.default",
                "MODUPDATER_PROFILE", null);
        boolean profilePrompt = flag(flags, file, "profile-prompt", "profile.prompt",
                "MODUPDATER_PROFILE_PROMPT", true);
        boolean profileRemember = flag(flags, file, "profile-remember", "profile.remember",
                "MODUPDATER_PROFILE_REMEMBER", true);

        return new Config(baseUrl, modsDir, mcVersion, tokenFile, relaunch,
                profilesEnabled, profileDefault, profilePrompt, profileRemember);
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

    /**
     * A yes/no setting from the same three sources.
     *
     * <p>Anything that is not recognisably a yes or a no falls back to the
     * default rather than guessing — a typo in {@code profiles.enabled} should
     * not silently rearrange somebody's mods folder.
     */
    private static boolean flag(
            Map<String, String> flags,
            Properties file,
            String flag,
            String property,
            String env,
            boolean fallback) {

        String value = resolve(flags, file, flag, property, env, null);
        if (value == null) {
            return fallback;
        }

        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> fallback;
        };
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
