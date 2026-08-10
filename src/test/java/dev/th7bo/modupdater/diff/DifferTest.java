package dev.th7bo.modupdater.diff;

import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.manifest.Manifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifferTest {

    private static final String MC = "1.21.4";

    private static InstalledMod installed(String modId, String sha) {
        return new InstalledMod(Path.of("mods", modId + ".jar"), modId + ".jar", modId, "1.0.0", sha);
    }

    private static Manifest.Version version(String sha, String... mcVersions) {
        return new Manifest.Version("2.0.0", "fabric", List.of(mcVersions), "1.21.4", "exact",
                "mod-2.0.0.jar", sha, 1024, "http://example.test/mod.jar",
                "build-1", "2026-08-10T10:00:00Z", "abc1234", "Newer build");
    }

    private static Manifest manifestOf(String modId, String repoName, Manifest.Version... versions) {
        return new Manifest("now", List.of(
                new Manifest.Mod(modId, "Example Mod", "repo-" + repoName, repoName, List.of(versions))));
    }

    @Test
    void offersAnUpdateWhenTheHashDiffers() {
        Manifest manifest = manifestOf("examplemod", "example-mod", version("new-sha", MC));

        List<UpdateCandidate> plan = Differ.plan(List.of(installed("examplemod", "old-sha")), manifest, MC);

        assertEquals(1, plan.size());
        assertEquals("examplemod", plan.get(0).modId());
        assertEquals("new-sha", plan.get(0).version().sha256());
    }

    @Test
    void offersNothingWhenTheHashMatches() {
        Manifest manifest = manifestOf("examplemod", "example-mod", version("same-sha", MC));

        List<UpdateCandidate> plan = Differ.plan(List.of(installed("examplemod", "same-sha")), manifest, MC);

        assertTrue(plan.isEmpty(), "the installed bytes are already current — offering it would be a no-op");
    }

    @Test
    void ignoresHashCase() {
        Manifest manifest = manifestOf("examplemod", "example-mod", version("ABCDEF", MC));

        assertTrue(Differ.plan(List.of(installed("examplemod", "abcdef")), manifest, MC).isEmpty());
    }

    @Test
    void doesNotOfferAJarForADifferentMinecraftVersion() {
        Manifest manifest = manifestOf("examplemod", "example-mod", version("new-sha", "1.20.1"));

        assertTrue(Differ.plan(List.of(installed("examplemod", "old-sha")), manifest, MC).isEmpty());
    }

    @Test
    void picksTheJarMatchingThisInstancesMinecraftVersion() {
        Manifest manifest = manifestOf("examplemod", "example-mod",
                version("sha-1205", "1.20.5"),
                version("sha-1214", MC),
                version("sha-1215", "1.21.5"));

        List<UpdateCandidate> plan = Differ.plan(List.of(installed("examplemod", "old")), manifest, MC);

        assertEquals(1, plan.size());
        assertEquals("sha-1214", plan.get(0).version().sha256());
    }

    private static Manifest.Version prefixVersion(String sha, String base) {
        return new Manifest.Version("2.0.0", "fabric", List.of(base), "~" + base, "prefix",
                "mod-2.0.0.jar", sha, 1024, "http://example.test/mod.jar",
                "build-1", "2026-08-10T10:00:00Z", "abc1234", "Newer build");
    }

    @Test
    void offersAPrefixBuildToAPatchRelease() {
        // The real case: SkyHanni declares ~26.1 and the instance runs 26.1.2.
        Manifest manifest = manifestOf("skyhanni", "SkyHanni", prefixVersion("new-sha", "26.1"));

        List<UpdateCandidate> plan = Differ.plan(List.of(installed("skyhanni", "old-sha")), manifest, "26.1.2");

        assertEquals(1, plan.size());
    }

    @Test
    void offersAPrefixBuildToTheBaseVersionItself() {
        Manifest manifest = manifestOf("skyhanni", "SkyHanni", prefixVersion("new-sha", "26.1"));

        assertEquals(1, Differ.plan(List.of(installed("skyhanni", "old-sha")), manifest, "26.1").size());
    }

    @Test
    void doesNotLetAPrefixLeakIntoTheNextLine() {
        Manifest manifest = manifestOf("skyhanni", "SkyHanni", prefixVersion("new-sha", "26.1"));

        assertTrue(Differ.plan(List.of(installed("skyhanni", "old-sha")), manifest, "26.2").isEmpty());
        assertTrue(Differ.plan(List.of(installed("skyhanni", "old-sha")), manifest, "26.11").isEmpty(),
                "26.11 is a different line from 26.1, not a patch of it");
    }

    @Test
    void treatsAMissingMatchModeAsExact() {
        // A server predating the range support sends no mcVersionMatch at all.
        Manifest.Version legacy = new Manifest.Version("2.0.0", "fabric", List.of("26.1"), "~26.1", null,
                "mod.jar", "new-sha", 1, "http://example.test/mod.jar", "b", "t", null, null);
        Manifest manifest = manifestOf("skyhanni", "SkyHanni", legacy);

        assertTrue(Differ.plan(List.of(installed("skyhanni", "old-sha")), manifest, "26.1.2").isEmpty(),
                "missing information must narrow behaviour, never widen it");
    }

    @Test
    void doesNotOfferAnUnresolvedCompatibilityRange() {
        Manifest manifest = manifestOf("examplemod", "example-mod", version("new-sha"));

        assertTrue(Differ.plan(List.of(installed("examplemod", "old")), manifest, MC).isEmpty(),
                "an empty mcVersions list means compatibility is unknown, not universal");
    }

    @Test
    void doesNotInstallModsThatArentPresent() {
        Manifest manifest = manifestOf("notinstalled", "other-mod", version("new-sha", MC));

        assertTrue(Differ.plan(List.of(installed("examplemod", "old")), manifest, MC).isEmpty(),
                "this is an updater, not an installer");
    }

    @Test
    void leavesModsTheManifestDoesNotKnowAbout() {
        Manifest manifest = manifestOf("examplemod", "example-mod", version("new-sha", MC));

        List<UpdateCandidate> plan = Differ.plan(
                List.of(installed("examplemod", "old"), installed("somethingelse", "whatever")), manifest, MC);

        assertEquals(1, plan.size());
        assertEquals("examplemod", plan.get(0).modId());
    }

    @Test
    void keepsTheSameModIdFromTwoReposSeparate() {
        Manifest manifest = new Manifest("now", List.of(
                new Manifest.Mod("examplemod", "Example Mod", "repo-1", "example-mod",
                        List.of(version("upstream-sha", MC))),
                new Manifest.Mod("examplemod", "Example Mod", "repo-2", "example-mod-fork",
                        List.of(version("fork-sha", MC)))));

        List<UpdateCandidate> plan = Differ.plan(List.of(installed("examplemod", "old")), manifest, MC);

        assertEquals(2, plan.size(), "an upstream and a fork are distinct choices, not one merged entry");
        assertEquals(List.of("example-mod", "example-mod-fork"),
                plan.stream().map(c -> c.mod().repoName()).toList());
    }

    @Test
    void skipsJarsWithNoModId() {
        InstalledMod unmatchable = new InstalledMod(Path.of("mods/x.jar"), "x.jar", null, null, "sha");
        Manifest manifest = manifestOf("examplemod", "example-mod", version("new-sha", MC));

        assertTrue(Differ.plan(List.of(unmatchable), manifest, MC).isEmpty());
    }

    @Test
    void skipsManifestEntriesWithNoHash() {
        Manifest.Version noHash = new Manifest.Version("2.0.0", "fabric", List.of(MC), null, "exact",
                "mod.jar", null, 1, "http://example.test/mod.jar", "b", "t", null, null);
        Manifest manifest = manifestOf("examplemod", "example-mod", noHash);

        assertTrue(Differ.plan(List.of(installed("examplemod", "old")), manifest, MC).isEmpty());
    }

    @Test
    void returnsEmptyForMissingInputs() {
        Manifest manifest = manifestOf("examplemod", "example-mod", version("new-sha", MC));

        assertTrue(Differ.plan(null, manifest, MC).isEmpty());
        assertTrue(Differ.plan(List.of(installed("examplemod", "old")), null, MC).isEmpty());
        assertTrue(Differ.plan(List.of(installed("examplemod", "old")), manifest, null).isEmpty());
        assertTrue(Differ.plan(List.of(installed("examplemod", "old")), manifest, "").isEmpty());
    }
}
