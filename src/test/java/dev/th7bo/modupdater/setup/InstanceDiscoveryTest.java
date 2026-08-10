package dev.th7bo.modupdater.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDiscoveryTest {

    private static final String PACK = """
            {
              "components": [
                { "uid": "org.lwjgl3", "version": "3.3.3" },
                { "cachedName": "Minecraft", "cachedVersion": "1.21.6",
                  "important": true, "uid": "net.minecraft", "version": "1.21.6" },
                { "uid": "net.fabricmc.fabric-loader", "version": "0.17.2" }
              ],
              "formatVersion": 1
            }
            """;

    private static Path prismInstance(Path home, String dirName, String displayName,
                                      String gameDirName, String pack) throws IOException {
        Path dir = home.resolve(".local/share/PrismLauncher/instances").resolve(dirName);
        Files.createDirectories(dir.resolve(gameDirName).resolve("mods"));
        Files.writeString(dir.resolve("instance.cfg"),
                "OverrideCommands=false\nPreLaunchCommand=\nname=" + displayName + "\n");
        if (pack != null) {
            Files.writeString(dir.resolve("mmc-pack.json"), pack);
        }
        return dir;
    }

    private static Path modrinthProfile(Path home, String name) throws IOException {
        Path dir = home.resolve(".local/share/ModrinthApp/profiles").resolve(name);
        Files.createDirectories(dir.resolve("mods"));
        return dir;
    }

    @Test
    void findsAPrismInstanceWithItsDisplayName(@TempDir Path home) throws IOException {
        prismInstance(home, "some-folder", "My Fabric Instance", "minecraft", PACK);

        List<Instance> found = new InstanceDiscovery(home, null).discover();

        assertEquals(1, found.size());
        assertEquals("Prism", found.get(0).launcher());
        assertEquals("My Fabric Instance", found.get(0).name(),
                "the launcher's display name, not the folder name");
        assertEquals("1.21.6", found.get(0).mcVersion());
    }

    @Test
    void handlesBothMinecraftAndDotMinecraft(@TempDir Path home) throws IOException {
        prismInstance(home, "plain", "Plain", "minecraft", PACK);
        prismInstance(home, "dotted", "Dotted", ".minecraft", PACK);

        List<Instance> found = new InstanceDiscovery(home, null).discover();

        assertEquals(2, found.size());
        assertTrue(found.stream().allMatch(i -> Files.isDirectory(i.modsDir())),
                "both layouts occur on the same machine and both must resolve");
    }

    @Test
    void handlesNamesWithSpacesAndParentheses(@TempDir Path home) throws IOException {
        prismInstance(home, "Contained (A Space Adventure)", "Contained (A Space Adventure)", "minecraft", PACK);

        List<Instance> found = new InstanceDiscovery(home, null).discover();

        assertEquals("Contained (A Space Adventure)", found.get(0).name());
    }

    @Test
    void fallsBackToTheFolderNameWhenCfgHasNoName(@TempDir Path home) throws IOException {
        Path dir = home.resolve(".local/share/PrismLauncher/instances").resolve("folder-name");
        Files.createDirectories(dir.resolve("minecraft").resolve("mods"));
        Files.writeString(dir.resolve("instance.cfg"), "OverrideCommands=false\n");

        assertEquals("folder-name", new InstanceDiscovery(home, null).discover().get(0).name());
    }

    @Test
    void reportsAnUnknownVersionWhenThePackIsMissing(@TempDir Path home) throws IOException {
        prismInstance(home, "nopack", "No Pack", "minecraft", null);

        Instance instance = new InstanceDiscovery(home, null).discover().get(0);

        assertNull(instance.mcVersion());
        assertEquals("unknown", instance.versionLabel());
    }

    @Test
    void toleratesAMalformedPackFile(@TempDir Path home) throws IOException {
        prismInstance(home, "broken", "Broken", "minecraft", "{ not json");

        assertNull(new InstanceDiscovery(home, null).discover().get(0).mcVersion());
    }

    @Test
    void skipsDirectoriesWithNoInstanceCfg(@TempDir Path home) throws IOException {
        Files.createDirectories(home.resolve(".local/share/PrismLauncher/instances/not-an-instance"));

        assertTrue(new InstanceDiscovery(home, null).discover().isEmpty());
    }

    @Test
    void skipsInstancesWithNoGameDirectory(@TempDir Path home) throws IOException {
        Path dir = home.resolve(".local/share/PrismLauncher/instances").resolve("empty");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("instance.cfg"), "name=Empty\n");

        assertTrue(new InstanceDiscovery(home, null).discover().isEmpty());
    }

    @Test
    void findsModrinthProfiles(@TempDir Path home) throws IOException {
        modrinthProfile(home, "Fabric 1.21.4");

        List<Instance> found = new InstanceDiscovery(home, null).discover();

        assertEquals(1, found.size());
        assertEquals("Modrinth", found.get(0).launcher());
        assertEquals("Fabric 1.21.4", found.get(0).name());
        assertNull(found.get(0).mcVersion(), "Modrinth keeps the version in SQLite; the installer asks");
        assertFalse(found.get(0).supportsAutoConfig());
    }

    @Test
    void marksPrismInstancesAsAutoConfigurable(@TempDir Path home) throws IOException {
        prismInstance(home, "p", "P", "minecraft", PACK);

        assertTrue(new InstanceDiscovery(home, null).discover().get(0).supportsAutoConfig());
    }

    @Test
    void findsInstancesFromSeveralLaunchersAtOnce(@TempDir Path home) throws IOException {
        prismInstance(home, "p", "Prism One", "minecraft", PACK);
        modrinthProfile(home, "Modrinth One");

        List<Instance> found = new InstanceDiscovery(home, null).discover();

        assertEquals(List.of("Modrinth", "Prism"), found.stream().map(Instance::launcher).toList());
    }

    @Test
    void returnsNothingWhenNoLauncherIsInstalled(@TempDir Path home) {
        assertTrue(new InstanceDiscovery(home, null).discover().isEmpty());
    }

    @Test
    void includesWindowsRootsWhenAppDataIsSet(@TempDir Path home, @TempDir Path appData) {
        var discovery = new InstanceDiscovery(home, appData);

        assertTrue(discovery.prismRoots().contains(appData.resolve("PrismLauncher/instances")));
        assertTrue(discovery.modrinthRoots().contains(appData.resolve("ModrinthApp/profiles")));
    }

    @Test
    void producesTsvTheInstallerCanParse(@TempDir Path home) throws IOException {
        prismInstance(home, "p", "My Instance", "minecraft", PACK);

        String[] fields = new InstanceDiscovery(home, null).discover().get(0).toTsv().split("\t");

        assertEquals(5, fields.length);
        assertEquals("Prism", fields[0]);
        assertEquals("My Instance", fields[1]);
        assertEquals("1.21.6", fields[2]);
        assertTrue(fields[4].endsWith("instance.cfg"));
    }
}
