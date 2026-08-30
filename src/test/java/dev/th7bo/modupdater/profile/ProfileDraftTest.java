package dev.th7bo.modupdater.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editing rules behind the manager window.
 *
 * <p>Tested here rather than through the window, because these are the
 * operations that can quietly break somebody's setup — a deleted group leaving
 * dangling references, a rename losing the profiles that used it — and none of
 * that should only be reachable by clicking.
 */
class ProfileDraftTest {

    private static final String CONFIG = """
            {
              "groups": {
                "base":        ["fabric-api", "skyhanni"],
                "performance": ["sodium", "lithium"],
                "dungeons":    ["bettermap"]
              },
              "profiles": {
                "general":  { "description": "Normal", "include": ["base", "performance"] },
                "dungeons": { "description": "Dungeons", "include": ["base", "dungeons"] },
                "everything": { "includeAll": true }
              }
            }
            """;

    private static Path stateDir(Path dir, String json) throws IOException {
        Path state = Files.createDirectories(dir.resolve(".modupdater"));
        Files.writeString(ProfileConfig.fileIn(state), json, StandardCharsets.UTF_8);
        return state;
    }

    private static ProfileDraft draft(Path dir) throws IOException {
        return ProfileDraft.load(stateDir(dir, CONFIG));
    }

    @Test
    void readsGroupsAndProfilesInTheFilesOrder(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        assertEquals(List.of("base", "performance", "dungeons"), draft.groupNames());
        assertEquals(List.of("general", "dungeons", "everything"), draft.profileNames());
        assertEquals(List.of("fabric-api", "skyhanni"), draft.membersOf("base"));
    }

    @Test
    void startsCleanAndNoticesTheFirstChange(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);
        assertFalse(draft.dirty());

        draft.setMember("base", "sodium", true);

        assertTrue(draft.dirty());
    }

    @Test
    void emptyWhenThereIsNoFileYet(@TempDir Path dir) {
        ProfileDraft draft = ProfileDraft.load(dir.resolve(".modupdater"));

        assertEquals(List.of(), draft.groupNames());
        assertEquals(List.of(), draft.profileNames());
        assertFalse(draft.dirty());
    }

    @Test
    void leavesABrokenFileAloneRatherThanReadingHalfOfIt(@TempDir Path dir) throws IOException {
        ProfileDraft draft = ProfileDraft.load(stateDir(dir, "{ not json at all"));

        assertEquals(List.of(), draft.groupNames());
        assertFalse(draft.dirty(), "an untouched draft must not be able to overwrite it");
    }

    // ── Groups ──────────────────────────────────────────────────────────────

    @Test
    void addsAndRemovesGroupMembers(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        draft.setMember("base", "modupdater", true);
        draft.setMember("base", "skyhanni", false);

        assertEquals(List.of("fabric-api", "modupdater"), draft.membersOf("base"));
    }

    @Test
    void refusesADuplicateGroupName(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        assertTrue(draft.addGroup("mining"));
        assertFalse(draft.addGroup("base"));
        assertFalse(draft.addGroup("BASE"), "names differing only in case are the same group");
    }

    @Test
    void deletingAGroupTakesItOutOfEveryProfile(@TempDir Path dir) throws IOException {
        // Otherwise each profile keeps a reference to a group that is gone, which
        // becomes an "includes an undefined group" warning at the next launch —
        // a puzzle to anyone who only deleted something in a window.
        ProfileDraft draft = draft(dir);

        assertTrue(draft.removeGroup("base"));

        assertFalse(draft.groupNames().contains("base"));
        assertEquals(List.of("performance"), draft.profile("general").include());
        assertEquals(List.of("dungeons"), draft.profile("dungeons").include());
    }

    @Test
    void renamingAGroupCarriesTheProfilesWithIt(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        assertTrue(draft.renameGroup("base", "core"));

        assertEquals(List.of("core", "performance", "dungeons"), draft.groupNames());
        assertEquals(List.of("fabric-api", "skyhanni"), draft.membersOf("core"));
        assertEquals(List.of("core", "performance"), draft.profile("general").include());
    }

    @Test
    void renamingKeepsTheGroupWhereItWasInTheFile(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        draft.renameGroup("performance", "fps");

        assertEquals(List.of("base", "fps", "dungeons"), draft.groupNames(),
                "a rename should not shuffle the file");
    }

    @Test
    void refusesToRenameOverAnExistingGroup(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        assertFalse(draft.renameGroup("base", "dungeons"));
        assertEquals(List.of("fabric-api", "skyhanni"), draft.membersOf("base"));
    }

    // ── Profiles ────────────────────────────────────────────────────────────

    @Test
    void editsAProfilesGroupsAndDescription(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        draft.setIncluded("general", "dungeons", true);
        draft.setDescription("general", "  Everyday play  ");

        assertEquals(List.of("base", "performance", "dungeons"), draft.profile("general").include());
        assertEquals("Everyday play", draft.profile("general").description());
    }

    @Test
    void renamingAProfileKeepsItsPlaceAndContents(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        assertTrue(draft.renameProfile("general", "everyday"));

        assertEquals(List.of("everyday", "dungeons", "everything"), draft.profileNames());
        assertEquals(List.of("base", "performance"), draft.profile("everyday").include());
    }

    @Test
    void reportsWhichModsNoGroupHolds(@TempDir Path dir) throws IOException {
        ProfileDraft draft = draft(dir);

        Set<String> loose = draft.ungrouped(
                Set.of("fabric-api", "skyhanni", "sodium", "lithium", "bettermap", "some-random-mod"));

        assertEquals(Set.of("some-random-mod"), loose);
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    @Test
    void savesSomethingTheRestOfTheProgramReadsBack(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);
        ProfileDraft draft = ProfileDraft.load(state);

        draft.addGroup("mining");
        draft.setMember("mining", "coleweight", true);
        draft.addProfile("mining");
        draft.setIncluded("mining", "base", true);
        draft.setIncluded("mining", "mining", true);
        draft.setDescription("mining", "Mining mods on");
        draft.save(state);

        ProfileConfig reread = ProfileConfig.read(state);
        assertTrue(reread.has("mining"));
        assertEquals("Mining mods on", reread.profile("mining").orElseThrow().description());
        assertEquals(List.of("coleweight"), reread.groups().get("mining"));
        assertFalse(draft.dirty(), "saving settles the draft");
    }

    @Test
    void keepsWhatItDoesNotUnderstand(@TempDir Path dir) throws IOException {
        // A key a later version added must survive being edited by this one.
        Path state = stateDir(dir, """
                {
                  "schemaVersion": 2,
                  "groups": { "base": ["skyhanni"] },
                  "profiles": { "general": { "include": ["base"] } }
                }
                """);

        ProfileDraft draft = ProfileDraft.load(state);
        draft.addGroup("mining");
        draft.save(state);

        assertTrue(Files.readString(ProfileConfig.fileIn(state)).contains("\"schemaVersion\""));
    }

    @Test
    void writesIncludeAllRatherThanAnEmptyIncludeList(@TempDir Path dir) throws IOException {
        Path state = stateDir(dir, CONFIG);
        ProfileDraft draft = ProfileDraft.load(state);

        draft.setDescription("everything", "All of it");
        draft.save(state);

        ProfileConfig reread = ProfileConfig.read(state);
        assertTrue(reread.profile("everything").orElseThrow().includesEverything(),
                "the everything profile must survive a round trip");
    }

    @Test
    void resolvesAgainstTheDraftBeforeItIsSaved(@TempDir Path dir) throws IOException {
        // What the window previews: the answer while you are still deciding.
        ProfileDraft draft = draft(dir);
        draft.setIncluded("general", "dungeons", true);

        var inventory = Profiles.inventory(
                List.of("fabric-api", "skyhanni", "sodium", "lithium", "bettermap"), List.of());

        assertEquals(Set.of("fabric-api", "skyhanni", "sodium", "lithium", "bettermap"),
                ProfileResolver.resolve(draft.toConfig(), "general", inventory).activeModIds());
    }
}
