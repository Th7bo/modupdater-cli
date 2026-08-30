package dev.th7bo.modupdater.profile;

import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The moves that would turn the inventory on disk into the resolved profile.
 *
 * <p>Worked out in full before anything is touched, so the caller can see what a
 * profile switch costs — and so applying it is a loop over a fixed list rather
 * than a series of decisions made halfway through mutating the folder.
 */
public record ProfilePlan(String profileName, List<Move> activate, List<Move> deactivate) {

    /** One JAR, and where it is going. */
    public record Move(String modId, Path from, Path to) {

        public String filename() {
            return from.getFileName().toString();
        }
    }

    public ProfilePlan {
        activate = activate == null ? List.of() : List.copyOf(activate);
        deactivate = deactivate == null ? List.of() : List.copyOf(deactivate);
    }

    public static ProfilePlan of(
            ModPaths paths, ModInventory inventory, ProfileResolver.Resolution resolution) {

        List<Move> activate = new ArrayList<>();
        List<Move> deactivate = new ArrayList<>();

        for (InstalledMod mod : inventory.inactive()) {
            if (skip(inventory, mod) || !resolution.wants(mod)) {
                continue;
            }
            activate.add(new Move(mod.modId(), mod.path(), paths.activePath(mod.filename())));
        }

        for (InstalledMod mod : inventory.active()) {
            if (skip(inventory, mod) || resolution.wants(mod)) {
                continue;
            }
            deactivate.add(new Move(mod.modId(), mod.path(), paths.inactivePath(mod.filename())));
        }

        return new ProfilePlan(resolution.profileName(), activate, deactivate);
    }

    /**
     * Mods the plan will not touch: ones with no identity to match on, and ones
     * installed twice, where moving either copy would be a guess.
     */
    private static boolean skip(ModInventory inventory, InstalledMod mod) {
        return !mod.matchable() || inventory.conflicted(mod.modId());
    }

    public boolean isEmpty() {
        return activate.isEmpty() && deactivate.isEmpty();
    }

    /** Every move, activations first — the order they are carried out in. */
    public List<Move> moves() {
        List<Move> all = new ArrayList<>(activate);
        all.addAll(deactivate);
        return List.copyOf(all);
    }
}
