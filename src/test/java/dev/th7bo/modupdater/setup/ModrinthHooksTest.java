package dev.th7bo.modupdater.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Built against the schema Modrinth App actually uses, read out of a real
 * installation: the hooks live in a JSONB column on instance_launch_overrides,
 * keyed by an instances row whose `path` is the profile folder name.
 */
class ModrinthHooksTest {

    /** @return the game directory of the created instance */
    private static Path modrinthWith(Path root, String folder, String displayName, String existingOverrides)
            throws IOException, SQLException {

        Path profiles = Files.createDirectories(root.resolve("profiles"));
        Path gameDir = Files.createDirectories(profiles.resolve(folder));
        Path db = root.resolve("app.db");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE instances (id TEXT NOT NULL PRIMARY KEY, path TEXT NOT NULL, "
                    + "name TEXT NOT NULL, icon_path TEXT NULL, UNIQUE (path))");
            s.execute("CREATE TABLE instance_launch_overrides (instance_id TEXT NOT NULL PRIMARY KEY, "
                    + "overrides JSONB NOT NULL, "
                    + "FOREIGN KEY (instance_id) REFERENCES instances(id) ON DELETE CASCADE)");
            s.execute("INSERT INTO instances (id, path, name) VALUES "
                    + "('local:abc', '" + folder + "', '" + displayName + "')");

            if (existingOverrides != null) {
                s.execute("INSERT INTO instance_launch_overrides (instance_id, overrides) "
                        + "VALUES ('local:abc', jsonb('" + existingOverrides + "'))");
            }
        }

        return gameDir;
    }

    private static String hook(Path root, String which) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db"));
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT json_extract(overrides, '$.hooks." + which + "') FROM instance_launch_overrides")) {
            return r.next() ? r.getString(1) : null;
        }
    }

    @Test
    void writesBothHooksWhenModrinthLeftThemNull(@TempDir Path root) throws Exception {
        // Exactly what the UI leaves behind: the record exists, the values do not.
        Path gameDir = modrinthWith(root, "Skyblock 1.0.0", "Skyblock",
                "{\"hooks\":{\"pre_launch\":null,\"wrapper\":null,\"post_exit\":null}}");

        var result = ModrinthHooks.configure(gameDir, "C:\\mu\\check.bat", "C:\\mu\\apply.bat");

        assertInstanceOf(ModrinthHooks.Result.Configured.class, result);
        assertEquals("C:\\mu\\check.bat", hook(root, "pre_launch"));
        assertEquals("C:\\mu\\apply.bat", hook(root, "post_exit"));
    }

    @Test
    void writesHooksWhenThereIsNoOverridesRowAtAll(@TempDir Path root) throws Exception {
        Path gameDir = modrinthWith(root, "Fabric 26.1.2", "Fabric", null);

        var result = ModrinthHooks.configure(gameDir, "/home/x/check.sh", "/home/x/apply.sh");

        assertInstanceOf(ModrinthHooks.Result.Configured.class, result);
        assertEquals("/home/x/check.sh", hook(root, "pre_launch"));
    }

    @Test
    void leavesTheWrapperAlone(@TempDir Path root) throws Exception {
        // Wrapping how somebody's game starts is theirs to decide, not ours.
        Path gameDir = modrinthWith(root, "Skyblock", "Skyblock",
                "{\"hooks\":{\"pre_launch\":null,\"wrapper\":\"gamemoderun\",\"post_exit\":null}}");

        ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        assertEquals("gamemoderun", hook(root, "wrapper"));
    }

    @Test
    void keepsUnrelatedOverrideSettings(@TempDir Path root) throws Exception {
        Path gameDir = modrinthWith(root, "Skyblock", "Skyblock",
                "{\"memory\":{\"maximum\":4096},\"hooks\":{\"pre_launch\":null}}");

        ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db"));
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery(
                     "SELECT json_extract(overrides, '$.memory.maximum') FROM instance_launch_overrides")) {
            assertTrue(r.next());
            assertEquals(4096, r.getInt(1));
        }
    }

    @Test
    void storesJsonbNotText(@TempDir Path root) throws Exception {
        // The column is JSONB. Storing text there would be a different encoding
        // from every row Modrinth wrote itself.
        Path gameDir = modrinthWith(root, "Skyblock", "Skyblock", null);

        ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db"));
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT typeof(overrides) FROM instance_launch_overrides")) {
            assertTrue(r.next());
            assertEquals("blob", r.getString(1));
        }
    }

    @Test
    void backsUpTheDatabaseBeforeWriting(@TempDir Path root) throws Exception {
        Path gameDir = modrinthWith(root, "Skyblock", "Skyblock", null);

        ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        Path backup = root.resolve("app.db" + ModrinthHooks.BACKUP_SUFFIX);
        assertTrue(Files.isRegularFile(backup), "a copy should exist before anything is written");

        // And it must be a usable database, not a torn file copy.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + backup);
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM instances")) {
            assertTrue(r.next());
            assertEquals(1, r.getInt(1));
        }
    }

    @Test
    void reportsAnInstanceThatIsNotInTheDatabase(@TempDir Path root) throws Exception {
        modrinthWith(root, "Skyblock", "Skyblock", null);
        Path stranger = Files.createDirectories(root.resolve("profiles").resolve("Not Registered"));

        var result = ModrinthHooks.configure(stranger, "/x/check.sh", "/x/apply.sh");

        assertInstanceOf(ModrinthHooks.Result.InstanceNotFound.class, result);
    }

    @Test
    void reportsAMissingDatabaseRatherThanCreatingOne(@TempDir Path root) throws IOException {
        Path gameDir = Files.createDirectories(root.resolve("profiles").resolve("Skyblock"));

        var result = ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        assertInstanceOf(ModrinthHooks.Result.NoDatabase.class, result);
        assertTrue(Files.notExists(root.resolve("app.db")), "must not conjure a database");
    }

    @Test
    void findsTheDatabaseBesideTheProfilesFolder(@TempDir Path root) {
        Path gameDir = root.resolve("profiles").resolve("Skyblock");

        assertEquals(root.resolve("app.db"), ModrinthHooks.databaseFor(gameDir));
    }

    @Test
    void survivesOverridesItCannotUnderstand(@TempDir Path root) throws Exception {
        Path gameDir = modrinthWith(root, "Skyblock", "Skyblock", "[1,2,3]");

        var result = ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        assertInstanceOf(ModrinthHooks.Result.Configured.class, result);
        assertEquals("/x/check.sh", hook(root, "pre_launch"));
    }

    @Test
    void isIdempotent(@TempDir Path root) throws Exception {
        Path gameDir = modrinthWith(root, "Skyblock", "Skyblock", null);

        ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");
        var second = ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        assertInstanceOf(ModrinthHooks.Result.Configured.class, second);
        assertEquals("/x/check.sh", hook(root, "pre_launch"));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("app.db"));
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM instance_launch_overrides")) {
            r.next();
            assertEquals(1, r.getInt(1), "must update the row, not add another");
        }
    }

    @Test
    void handlesAProfileFolderWithSpacesAndPunctuation(@TempDir Path root) throws Exception {
        Path gameDir = modrinthWith(root, "SkyBlock Enhanced - Modern Edition 4.0.8", "SkyBlock", null);

        var result = ModrinthHooks.configure(gameDir, "/x/check.sh", "/x/apply.sh");

        assertInstanceOf(ModrinthHooks.Result.Configured.class, result);
        assertNull(hook(root, "wrapper"));
    }
}
