package dev.th7bo.modupdater.profile;

import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.instance.ModInventory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a profile plus an inventory into "these mod ids should be active".
 *
 * <p>Pure — no filesystem, no I/O. Which mods a profile means is the part that
 * has to be right, so it is kept separately testable from the part that moves
 * files.
 */
public final class ProfileResolver {

    private ProfileResolver() {
    }

    /**
     * @param profileName  the profile that was resolved, for logging and state
     * @param activeModIds the mods that should end up in {@code mods/}
     * @param warnings     things worth telling the user, none of them fatal
     */
    public record Resolution(String profileName, Set<String> activeModIds, List<String> warnings) {

        public Resolution {
            activeModIds = activeModIds == null ? Set.of() : Set.copyOf(activeModIds);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /**
         * A JAR with no readable {@code fabric.mod.json} has no identity to match
         * against, so it is always left active. Moving something we cannot name is
         * how a mod goes missing with nothing in the log to explain it.
         */
        public boolean wants(InstalledMod mod) {
            return !mod.matchable() || activeModIds.contains(ProfileConfig.normalise(mod.modId()));
        }
    }

    /** Everything installed stays where the game can see it. The safe fallback. */
    public static Resolution everything(ModInventory inventory) {
        return everything(inventory, List.of());
    }

    public static Resolution everything(ModInventory inventory, List<String> warnings) {
        return new Resolution(ProfileConfig.EVERYTHING, normalisedIds(inventory), warnings);
    }

    public static Resolution resolve(ProfileConfig config, String profileName, ModInventory inventory) {
        List<String> warnings = new ArrayList<>();

        Profile profile = config.profile(profileName).orElse(null);
        if (profile == null) {
            // §10: a profile that no longer exists must not stop the game from
            // starting. Launch with everything, and say why.
            warnings.add("no profile named '" + profileName + "' in " + ProfileConfig.FILE
                    + " — launching with every installed mod active");
            return everything(inventory, warnings);
        }

        Set<String> installed = normalisedIds(inventory);

        if (profile.includesEverything()) {
            return new Resolution(ProfileConfig.normalise(profileName), installed,
                    conflictWarnings(inventory, warnings));
        }

        profile.unknownGroups(config).forEach(group ->
                warnings.add("profile '" + profileName + "' includes group '" + group
                        + "', which is not defined — ignoring it"));

        for (String referenced : profile.referenced(config)) {
            if (!installed.contains(referenced)) {
                warnings.add("profile '" + profileName + "' names '" + referenced
                        + "', which is not installed — ignoring it");
            }
        }

        Set<String> wanted = profile.members(config);
        Set<String> managed = config.managedModIds();
        Set<String> removed = new LinkedHashSet<>(profile.remove());

        Set<String> active = new LinkedHashSet<>();
        for (String modId : installed) {
            if (wanted.contains(modId)) {
                active.add(modId);
                continue;
            }

            // Nothing in the config has an opinion about this mod. Leaving it on is
            // the only safe reading: the alternative is that installing a mod and
            // forgetting to file it makes it vanish from the game.
            boolean unmanaged = !managed.contains(modId);
            if (unmanaged && profile.ungrouped() == Profile.Ungrouped.KEEP && !removed.contains(modId)) {
                active.add(modId);
            }
        }

        Set<String> unmanagedAndActive = new LinkedHashSet<>(active);
        unmanagedAndActive.removeAll(managed);
        if (!unmanagedAndActive.isEmpty()) {
            warnings.add(unmanagedAndActive.size() + " installed mod(s) are in no group ("
                    + String.join(", ", unmanagedAndActive)
                    + ") — they stay active in every profile until you add them to one");
        }

        return new Resolution(ProfileConfig.normalise(profileName),
                active, conflictWarnings(inventory, warnings));
    }

    /**
     * A mod with more than one JAR is reported, not resolved around. Which copy is
     * the real one is a question only the user can answer, so the resolution says
     * nothing special about it and {@link ProfileManager} leaves every copy where
     * it found it.
     */
    private static List<String> conflictWarnings(ModInventory inventory, List<String> warnings) {
        for (String conflicted : inventory.conflicts()) {
            warnings.add("'" + conflicted + "' is installed more than once ("
                    + describe(inventory.pathsFor(conflicted))
                    + ") — leaving both where they are; delete the copy you do not want");
        }
        return warnings;
    }

    private static Set<String> normalisedIds(ModInventory inventory) {
        Set<String> ids = new LinkedHashSet<>();
        inventory.modIds().forEach(id -> ids.add(ProfileConfig.normalise(id)));
        return ids;
    }

    private static String describe(List<Path> paths) {
        List<String> names = new ArrayList<>();
        paths.forEach(path -> names.add(String.valueOf(path.getFileName())));
        return String.join(", ", names);
    }
}
