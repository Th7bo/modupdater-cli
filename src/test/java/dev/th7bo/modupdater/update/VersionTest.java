package dev.th7bo.modupdater.update;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deciding whether the release on GitHub is worth downloading. */
class VersionTest {

    @Test
    void readsATagAndAManifestValueTheSameWay() {
        // The tag is "v0.5.0" and the manifest says "0.5.0". They compare equal,
        // while each keeps the text it was given for showing the user.
        assertEquals(0, Version.of("0.5.0").compareTo(Version.of("v0.5.0")));
        assertTrue(Version.of("v0.5.0").known());
        assertEquals(List.of(0, 5, 0), Version.of("v0.5.0").numbers());
    }

    @Test
    void comparesComponentByComponent() {
        assertTrue(Version.of("0.5.0").supersededBy(Version.of("0.6.0")));
        assertTrue(Version.of("0.5.0").supersededBy(Version.of("1.0.0")));
        assertTrue(Version.of("0.5.0").supersededBy(Version.of("0.5.1")));
        assertFalse(Version.of("0.6.0").supersededBy(Version.of("0.5.9")));
    }

    @Test
    void treatsAMissingComponentAsZero() {
        assertFalse(Version.of("1.0").supersededBy(Version.of("1.0.0")));
        assertTrue(Version.of("1.0").supersededBy(Version.of("1.0.1")));
    }

    @Test
    void doesNotOfferTheVersionAlreadyInstalled() {
        assertFalse(Version.of("0.5.0").supersededBy(Version.of("0.5.0")));
        assertFalse(Version.of("v0.5.0").supersededBy(Version.of("0.5.0")));
    }

    @Test
    void comparesTwoDigitComponentsAsNumbers() {
        // "0.10.0" is newer than "0.9.0", which string comparison gets backwards.
        assertTrue(Version.of("0.9.0").supersededBy(Version.of("0.10.0")));
        assertFalse(Version.of("0.10.0").supersededBy(Version.of("0.9.0")));
    }

    @Test
    void countsAPreReleaseAsBehindTheReleaseItLeadsTo() {
        assertTrue(Version.of("1.2.0-rc1").supersededBy(Version.of("1.2.0")));
        assertFalse(Version.of("1.2.0").supersededBy(Version.of("1.2.0-rc1")));
    }

    @Test
    void refusesToActOnAVersionItCannotRead() {
        // Replacing a working install because a version string was unparseable is
        // not a trade worth making — the installer is always there.
        assertFalse(Version.UNKNOWN.supersededBy(Version.of("9.9.9")));
        assertFalse(Version.of("0.1.0").supersededBy(Version.of("nightly")));
        assertFalse(Version.of("0.1.0").supersededBy(null));
        assertFalse(Version.of("nightly").known());
    }

    @Test
    void survivesRubbish() {
        assertFalse(Version.of(null).known());
        assertFalse(Version.of("").known());
        assertFalse(Version.of("   ").known());
        assertFalse(Version.of("v").known());
        assertFalse(Version.of("...").known());
    }

    @Test
    void keepsWhatItWasGivenForShowingTheUser() {
        assertEquals("v0.5.0", Version.of("v0.5.0").toString());
        assertEquals("unknown", Version.UNKNOWN.toString());
    }
}
