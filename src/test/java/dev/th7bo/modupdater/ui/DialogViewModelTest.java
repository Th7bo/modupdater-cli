package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.diff.UpdateCandidate;
import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.manifest.Manifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogViewModelTest {

    private static UpdateCandidate candidate(String modId, String installedVersion, String summary, long size) {
        InstalledMod installed = new InstalledMod(
                Path.of("mods", modId + ".jar"), modId + ".jar", modId, installedVersion, "old-sha");

        Manifest.Version version = new Manifest.Version("2.0.0", "fabric", List.of("1.21.4"), "1.21.4", "exact",
                modId + "-2.0.0.jar", "new-sha", size, "http://example.test/mod.jar",
                "build-1", "2026-08-10T10:00:00Z", "abc1234", summary);

        Manifest.Mod mod = new Manifest.Mod(modId, "Example Mod", "repo-1", "example-mod", List.of(version));

        return new UpdateCandidate(installed, mod, version);
    }

    @Test
    void preselectsEverything() {
        var model = new DialogViewModel(List.of(candidate("a", "1.0.0", "x", 10), candidate("b", "1.0.0", "y", 10)));

        assertEquals(2, model.selectedCount());
        assertTrue(model.canUpdate());
    }

    @Test
    void deselectingEverythingDisablesUpdate() {
        var model = new DialogViewModel(List.of(candidate("a", "1.0.0", "x", 10)));

        model.setSelected(0, false);

        assertFalse(model.canUpdate());
        assertTrue(model.chosen().isEmpty());
    }

    @Test
    void selectAllAndNone() {
        var model = new DialogViewModel(List.of(candidate("a", "1.0.0", "x", 10), candidate("b", "1.0.0", "y", 10)));

        model.selectAll(false);
        assertEquals(0, model.selectedCount());

        model.selectAll(true);
        assertEquals(2, model.selectedCount());
    }

    @Test
    void chosenReflectsSelection() {
        var model = new DialogViewModel(List.of(candidate("a", "1.0.0", "x", 10), candidate("b", "1.0.0", "y", 10)));

        model.setSelected(0, false);

        assertEquals(List.of("b"), model.chosen().stream().map(UpdateCandidate::modId).toList());
    }

    @Test
    void ignoresOutOfRangeIndexes() {
        var model = new DialogViewModel(List.of(candidate("a", "1.0.0", "x", 10)));

        model.setSelected(5, true);
        model.setSelected(-1, true);

        assertEquals(1, model.selectedCount());
    }

    @Test
    void handlesNoCandidates() {
        var model = new DialogViewModel(List.of());

        assertTrue(model.isEmpty());
        assertFalse(model.canUpdate());
    }

    @Test
    void handlesNullCandidates() {
        assertTrue(new DialogViewModel(null).isEmpty());
    }

    @Test
    void buildsReadableRows() {
        var model = new DialogViewModel(List.of(candidate("examplemod", "1.0.0", "Fix the thing", 2_097_152)));
        UpdateRow row = model.rows().get(0);

        assertEquals("Example Mod", row.name());
        assertEquals("example-mod", row.source());
        assertEquals("1.0.0", row.installedVersion());
        assertEquals("2.0.0", row.availableVersion());
        assertEquals("1.21.4", row.mcVersion());
        assertEquals("2.0 MB", row.size());
        assertEquals("Fix the thing", row.change());
    }

    @Test
    void labelsAMissingInstalledVersion() {
        InstalledMod noVersion = new InstalledMod(Path.of("mods/x.jar"), "x.jar", "x", null, "sha");
        Manifest.Version version = new Manifest.Version(null, "fabric", List.of("1.21.4"), null, "exact",
                "x-2.jar", "new", 1, "http://example.test/x.jar", "b", "t", null, null);
        Manifest.Mod mod = new Manifest.Mod("x", null, "repo-1", "example-mod", List.of(version));

        var model = new DialogViewModel(List.of(new UpdateCandidate(noVersion, mod, version)));
        UpdateRow row = model.rows().get(0);

        assertEquals("unknown", row.installedVersion());
        assertEquals("new build", row.availableVersion());
        assertEquals("no commit message", row.change());
        assertEquals("x", row.name(), "falls back to the mod id when there is no display name");
    }

    @Test
    void identifiesTheBuildWhenTheVersionStringIsUnchanged() {
        // SkyHanni 7.44.0 rebuilt from a newer commit: same version, new bytes.
        // "7.44.0 -> 7.44.0" would look like a bug to the user.
        InstalledMod installed = new InstalledMod(
                Path.of("mods/skyhanni.jar"), "skyhanni.jar", "skyhanni", "7.44.0", "old-sha");
        Manifest.Version version = new Manifest.Version("7.44.0", "fabric", List.of("26.1"), "~26.1", "prefix",
                "SkyHanni-7.44.0.jar", "c14afbf6aa7cc0cc", 100, "http://example.test/s.jar",
                "b", "t", "abc1234", "Fix something");
        Manifest.Mod mod = new Manifest.Mod("skyhanni", "SkyHanni", "repo-1", "SkyHanni", List.of(version));

        var model = new DialogViewModel(List.of(new UpdateCandidate(installed, mod, version)));

        assertEquals("7.44.0", model.rows().get(0).installedVersion());
        assertEquals("7.44.0 (build c14afbf6)", model.rows().get(0).availableVersion());
    }

    @Test
    void truncatesAVeryLongCommitSummary() {
        var model = new DialogViewModel(List.of(candidate("a", "1.0.0", "x".repeat(200), 10)));

        assertEquals(80, model.rows().get(0).change().length());
        assertTrue(model.rows().get(0).change().endsWith("..."));
    }

    @Test
    void formatsSizes() {
        assertEquals("512 B", UpdateRow.humanSize(512));
        assertEquals("1.0 KB", UpdateRow.humanSize(1024));
        assertEquals("1.0 MB", UpdateRow.humanSize(1024 * 1024));
    }
}
