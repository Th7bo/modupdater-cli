package dev.th7bo.modupdater.diff;

import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.manifest.Manifest;

/** One installed mod, and the newer build the platform is offering for it. */
public record UpdateCandidate(InstalledMod installed, Manifest.Mod mod, Manifest.Version version) {

    public String label() {
        return mod.label();
    }

    public String modId() {
        return mod.modId();
    }
}
