package dev.th7bo.modupdater.instance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every mod the instance has, wherever its JAR happens to sit.
 *
 * <p>A mod left out by the current profile is still installed — it is just not
 * in the folder Fabric reads. Both the updater and the profile system work from
 * this list, which is what makes an inactive mod keep receiving updates instead
 * of quietly falling a year behind.
 *
 * <p>Backups are deliberately not scanned. A JAR in {@code backup/} is the
 * previous version of something already counted here, and counting it again
 * would offer the user an update for a file the game never loads.
 *
 * @param conflicts mod ids with more than one JAR in the inventory — a state we
 *                  never create, but one an interrupted move or a hand-copied
 *                  file can leave behind
 */
public record ModInventory(List<InstalledMod> active, List<InstalledMod> inactive, Set<String> conflicts) {

    public ModInventory {
        active = active == null ? List.of() : List.copyOf(active);
        inactive = inactive == null ? List.of() : List.copyOf(inactive);
        conflicts = conflicts == null ? Set.of() : Set.copyOf(conflicts);
    }

    /**
     * Reads both halves of the inventory.
     *
     * <p>The inactive folder is only read when it already exists. Nothing here
     * creates it, so an instance that has never enabled profiles is scanned
     * exactly as it always was.
     */
    public static ModInventory scan(ModPaths paths, InstanceScanner scanner) {
        List<InstalledMod> active = scanner.scan(paths.modsDir());
        List<InstalledMod> inactive = scanner.scan(paths.inactiveDir());
        return of(active, inactive);
    }

    public static ModInventory of(List<InstalledMod> active, List<InstalledMod> inactive) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InstalledMod mod : concat(active, inactive)) {
            if (mod.matchable()) {
                counts.merge(mod.modId(), 1, Integer::sum);
            }
        }

        Set<String> conflicts = new LinkedHashSet<>();
        counts.forEach((modId, count) -> {
            if (count > 1) {
                conflicts.add(modId);
            }
        });

        return new ModInventory(active, inactive, conflicts);
    }

    /** Everything installed, active first. This is what the updater diffs against. */
    public List<InstalledMod> all() {
        return concat(active, inactive);
    }

    /** Mod ids that can be named in a profile — anything without one cannot be addressed. */
    public Set<String> modIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (InstalledMod mod : all()) {
            if (mod.matchable()) {
                ids.add(mod.modId());
            }
        }
        return ids;
    }

    /** JARs with no readable {@code fabric.mod.json}, so nothing can identify them. */
    public List<InstalledMod> unidentified() {
        return all().stream().filter(mod -> !mod.matchable()).toList();
    }

    public boolean conflicted(String modId) {
        return conflicts.contains(modId);
    }

    /** Where each of a mod's copies sits, for reporting a conflict usefully. */
    public List<Path> pathsFor(String modId) {
        return all().stream()
                .filter(mod -> modId != null && modId.equals(mod.modId()))
                .map(InstalledMod::path)
                .toList();
    }

    public int size() {
        return active.size() + inactive.size();
    }

    private static List<InstalledMod> concat(List<InstalledMod> first, List<InstalledMod> second) {
        List<InstalledMod> all = new ArrayList<>(first == null ? List.of() : first);
        all.addAll(second == null ? List.of() : second);
        return List.copyOf(all);
    }
}
