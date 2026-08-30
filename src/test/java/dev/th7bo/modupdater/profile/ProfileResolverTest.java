package dev.th7bo.modupdater.profile;

import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.instance.ModInventory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which mods a profile means. Pure rules, so they are checked without a filesystem. */
class ProfileResolverTest {

    private static final List<String> INSTALLED = List.of(
            "fabric-api", "skyhanni", "sodium", "lithium", "firmament",
            "bettermap", "dungeonrooms", "coleweight");

    private static ModInventory allActive() {
        return Profiles.inventory(INSTALLED, List.of());
    }

    private static Set<String> active(String profile, ModInventory inventory) {
        return ProfileResolver.resolve(Profiles.skyblock(), profile, inventory).activeModIds();
    }

    @Test
    void composesAProfileFromItsGroups() {
        assertEquals(
                Set.of("fabric-api", "skyhanni", "sodium", "lithium", "firmament"),
                active("general", allActive()));
    }

    @Test
    void addingAGroupAddsItsMods() {
        // dungeons is general plus the dungeons group — the whole point of composing
        // from sets rather than listing exceptions per profile.
        assertEquals(
                Set.of("fabric-api", "skyhanni", "sodium", "lithium", "firmament",
                        "bettermap", "dungeonrooms"),
                active("dungeons", allActive()));
    }

    @Test
    void aModAddedToAGroupReachesEveryProfileBuiltOnIt() {
        // The property that makes groups worth having: file a newly installed mod
        // once, and it turns up everywhere it belongs.
        ProfileConfig config = Profiles.config("""
                {
                  "groups": { "base": ["fabric-api", "skyhanni", "newly-installed"] },
                  "profiles": {
                    "general":  { "include": ["base"] },
                    "dungeons": { "include": ["base"] }
                  }
                }
                """);
        ModInventory inventory = Profiles.inventory(
                List.of("fabric-api", "skyhanni", "newly-installed"), List.of());

        assertTrue(ProfileResolver.resolve(config, "general", inventory)
                .activeModIds().contains("newly-installed"));
        assertTrue(ProfileResolver.resolve(config, "dungeons", inventory)
                .activeModIds().contains("newly-installed"));
    }

    @Test
    void addAndRemoveHandleTheOneOffs() {
        ModInventory inventory = Profiles.inventory(
                List.of("fabric-api", "skyhanni", "sodium", "lithium", "coleweight",
                        "skyblockcollectiontracker"),
                List.of());

        Set<String> mining = active("mining", inventory);

        assertTrue(mining.contains("skyblockcollectiontracker"), "add: names a mod with no group");
        assertTrue(mining.contains("coleweight"));
    }

    @Test
    void removeWinsOverTheGroupsThatIncludeIt() {
        ProfileConfig config = Profiles.config("""
                {
                  "groups": { "base": ["fabric-api", "skyhanni"] },
                  "profiles": { "trimmed": { "include": ["base"], "remove": ["skyhanni"] } }
                }
                """);
        ModInventory inventory = Profiles.inventory(List.of("fabric-api", "skyhanni"), List.of());

        assertEquals(Set.of("fabric-api"),
                ProfileResolver.resolve(config, "trimmed", inventory).activeModIds());
    }

    @Test
    void everythingTakesTheWholeInventory() {
        ModInventory inventory = Profiles.inventory(
                List.of("skyhanni"), List.of("bettermap", "coleweight"));

        assertEquals(Set.of("skyhanni", "bettermap", "coleweight"), active("everything", inventory));
    }

    @Test
    void aModInNoGroupStaysActiveByDefault() {
        // Installing a mod and forgetting to file it must not make it disappear from
        // the game — that is the one mistake this feature cannot afford.
        ModInventory inventory = Profiles.inventory(
                List.of("fabric-api", "skyhanni", "some-random-mod"), List.of());

        assertTrue(active("general", inventory).contains("some-random-mod"));
    }

    @Test
    void aModInNoGroupIsReported() {
        ModInventory inventory = Profiles.inventory(
                List.of("fabric-api", "skyhanni", "some-random-mod"), List.of());

        List<String> warnings =
                ProfileResolver.resolve(Profiles.skyblock(), "general", inventory).warnings();

        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("some-random-mod")
                && warning.contains("no group")));
    }

    @Test
    void aProfileCanAskForUngroupedModsToBeSwitchedOff() {
        // What makes "lite" actually lean on a low-powered machine.
        ModInventory inventory = Profiles.inventory(
                List.of("fabric-api", "skyhanni", "sodium", "lithium", "some-random-mod"), List.of());

        assertEquals(Set.of("fabric-api", "skyhanni", "sodium", "lithium"),
                active("lite", inventory));
    }

    @Test
    void anUnknownProfileFallsBackToEverything() {
        ProfileResolver.Resolution resolution =
                ProfileResolver.resolve(Profiles.skyblock(), "raiding", allActive());

        assertEquals(Set.copyOf(INSTALLED), resolution.activeModIds());
        assertTrue(resolution.warnings().stream().anyMatch(warning -> warning.contains("raiding")),
                "the user should be told which profile went missing");
    }

    @Test
    void anUnknownModIdWarnsInsteadOfFailing() {
        ProfileConfig config = Profiles.config("""
                {
                  "groups": { "base": ["fabric-api", "a-mod-nobody-installed"] },
                  "profiles": { "general": { "include": ["base"] } }
                }
                """);

        ProfileResolver.Resolution resolution = ProfileResolver.resolve(
                config, "general", Profiles.inventory(List.of("fabric-api"), List.of()));

        assertEquals(Set.of("fabric-api"), resolution.activeModIds());
        assertTrue(resolution.warnings().stream()
                .anyMatch(warning -> warning.contains("a-mod-nobody-installed")));
    }

    @Test
    void anUnknownGroupWarnsInsteadOfFailing() {
        ProfileConfig config = Profiles.config("""
                {
                  "groups": { "base": ["fabric-api"] },
                  "profiles": { "general": { "include": ["base", "typo-group"] } }
                }
                """);

        ProfileResolver.Resolution resolution = ProfileResolver.resolve(
                config, "general", Profiles.inventory(List.of("fabric-api"), List.of()));

        assertEquals(Set.of("fabric-api"), resolution.activeModIds());
        assertTrue(resolution.warnings().stream().anyMatch(warning -> warning.contains("typo-group")));
    }

    @Test
    void aModInstalledTwiceIsReportedRatherThanChosenBetween() {
        InstalledMod inMods = new InstalledMod(
                Path.of("mods", "bettermap-1.7.0.jar"), "bettermap-1.7.0.jar",
                "bettermap", "1.7.0", "sha-new");
        InstalledMod inStorage = new InstalledMod(
                Path.of("inactive", "bettermap-1.6.2.jar"), "bettermap-1.6.2.jar",
                "bettermap", "1.6.2", "sha-old");

        ModInventory inventory = ModInventory.of(List.of(inMods), List.of(inStorage));

        ProfileResolver.Resolution resolution =
                ProfileResolver.resolve(Profiles.skyblock(), "dungeons", inventory);

        assertTrue(resolution.warnings().stream().anyMatch(warning ->
                warning.contains("bettermap") && warning.contains("more than once")));
    }

    @Test
    void aJarWithNoModIdIsAlwaysWanted() {
        // Nothing can name it, so nothing may move it.
        InstalledMod unidentified = new InstalledMod(
                Path.of("mods", "mystery.jar"), "mystery.jar", null, null, "sha-mystery");

        ProfileResolver.Resolution resolution = ProfileResolver.resolve(
                Profiles.skyblock(), "lite", ModInventory.of(List.of(unidentified), List.of()));

        assertTrue(resolution.wants(unidentified));
    }

    @Test
    void profileAndModNamesAreMatchedCaseInsensitively() {
        ProfileConfig config = Profiles.config("""
                {
                  "groups": { "Base": ["Fabric-API"] },
                  "profiles": { "General": { "include": ["Base"] } }
                }
                """);

        ProfileResolver.Resolution resolution = ProfileResolver.resolve(
                config, "GENERAL", Profiles.inventory(List.of("fabric-api"), List.of()));

        assertEquals(Set.of("fabric-api"), resolution.activeModIds());
        assertFalse(resolution.warnings().stream().anyMatch(warning -> warning.contains("not installed")));
    }
}
