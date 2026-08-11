package dev.th7bo.modupdater.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Writes Modrinth App's pre-launch and post-exit hooks straight into its database.
 *
 * <p>Its own settings UI creates the record and stores nulls for the values, so the
 * fields cannot be filled in by hand. The hooks live in
 * {@code instance_launch_overrides.overrides}, a JSONB column, shaped:
 *
 * <pre>{"hooks":{"pre_launch":"...","wrapper":null,"post_exit":"..."}}</pre>
 *
 * <p>JSONB is SQLite's internal binary encoding, not text, so the value is produced
 * by the engine's own {@code jsonb()} function rather than assembled here — writing
 * those bytes by hand would be guessing at a private format inside somebody else's
 * launcher.
 *
 * <p>Modrinth App must not be running: it keeps this state in memory and writes it
 * back on exit, which would undo anything done here.
 */
public final class ModrinthHooks {

    /** Copied next to the database before anything is written. */
    static final String BACKUP_SUFFIX = ".modupdater-backup";

    private ModrinthHooks() {
    }

    public sealed interface Result {

        record Configured(String instanceName, Path database) implements Result {
        }

        record NoDatabase(Path looked) implements Result {
        }

        record InstanceNotFound(String folder) implements Result {
        }

        record Failed(String detail) implements Result {
        }

        default String describe() {
            return switch (this) {
                case Configured c -> "hooks written for " + c.instanceName();
                case NoDatabase d -> "no Modrinth App database at " + d.looked();
                case InstanceNotFound f -> "Modrinth App has no instance in folder " + f.folder();
                case Failed f -> "could not write the hooks: " + f.detail();
            };
        }
    }

    /**
     * A Modrinth game directory is {@code <data>/profiles/<folder>}, and the
     * database sits beside {@code profiles}.
     */
    public static Path databaseFor(Path gameDir) {
        Path profiles = gameDir.getParent();
        if (profiles == null || profiles.getParent() == null) {
            return null;
        }
        return profiles.getParent().resolve("app.db");
    }

    public static Result configure(Path gameDir, String preLaunch, String postExit) {
        Path database = databaseFor(gameDir);
        if (database == null || !Files.isRegularFile(database)) {
            return new Result.NoDatabase(database == null ? gameDir : database);
        }

        String folder = gameDir.getFileName().toString();

        try {
            backup(database);
        } catch (SQLException | IOException e) {
            return new Result.Failed("could not back up the database: " + e.getMessage());
        }

        // The JDBC URL takes a path, and Windows paths are fine as-is here.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.setAutoCommit(false);

            String instanceId = null;
            String instanceName = folder;
            try (PreparedStatement select =
                         connection.prepareStatement("SELECT id, name FROM instances WHERE path = ?")) {
                select.setString(1, folder);
                try (ResultSet rows = select.executeQuery()) {
                    if (rows.next()) {
                        instanceId = rows.getString(1);
                        instanceName = rows.getString(2);
                    }
                }
            }

            if (instanceId == null) {
                return new Result.InstanceNotFound(folder);
            }

            JsonObject overrides = readOverrides(connection, instanceId);
            JsonObject hooks = overrides.has("hooks") && overrides.get("hooks").isJsonObject()
                    ? overrides.getAsJsonObject("hooks")
                    : new JsonObject();

            hooks.addProperty("pre_launch", preLaunch);
            hooks.addProperty("post_exit", postExit);
            // Left exactly as found: it is the user's, and this tool has no
            // business wrapping how their game starts.
            if (!hooks.has("wrapper")) {
                hooks.add("wrapper", com.google.gson.JsonNull.INSTANCE);
            }
            overrides.add("hooks", hooks);

            try (PreparedStatement upsert = connection.prepareStatement(
                    "INSERT INTO instance_launch_overrides (instance_id, overrides) VALUES (?, jsonb(?)) "
                            + "ON CONFLICT (instance_id) DO UPDATE SET overrides = jsonb(?)")) {
                upsert.setString(1, instanceId);
                upsert.setString(2, overrides.toString());
                upsert.setString(3, overrides.toString());
                upsert.executeUpdate();
            }

            connection.commit();

            if (!writtenCorrectly(connection, instanceId, preLaunch, postExit)) {
                return new Result.Failed("the database did not keep the values that were written");
            }

            return new Result.Configured(instanceName, database);
        } catch (SQLException e) {
            return new Result.Failed(e.getMessage());
        }
    }

    /**
     * A consistent copy, made by SQLite rather than by copying the file.
     *
     * <p>The database runs in WAL mode, so recent changes live in a side file and a
     * plain file copy can be a torn mixture of the two.
     */
    private static void backup(Path database) throws SQLException, IOException {
        Path target = database.resolveSibling(database.getFileName() + BACKUP_SUFFIX);
        Files.deleteIfExists(target);

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        }
    }

    private static JsonObject readOverrides(Connection connection, String instanceId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT json(overrides) FROM instance_launch_overrides WHERE instance_id = ?")) {
            select.setString(1, instanceId);
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return new JsonObject();
                }
                String json = rows.getString(1);
                if (json == null || json.isBlank()) {
                    return new JsonObject();
                }
                return JsonParser.parseString(json).getAsJsonObject();
            }
        } catch (RuntimeException e) {
            // Anything unreadable is replaced rather than merged; the alternative is
            // refusing to help because of a field we do not understand.
            Log.warn("existing Modrinth launch overrides could not be read, replacing them");
            return new JsonObject();
        }
    }

    /** Reads the row back through SQLite's own JSON decoder. */
    private static boolean writtenCorrectly(
            Connection connection, String instanceId, String preLaunch, String postExit) throws SQLException {

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT json_extract(overrides, '$.hooks.pre_launch'), "
                        + "json_extract(overrides, '$.hooks.post_exit') "
                        + "FROM instance_launch_overrides WHERE instance_id = ?")) {
            select.setString(1, instanceId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        && preLaunch.equals(rows.getString(1))
                        && postExit.equals(rows.getString(2));
            }
        }
    }
}
