package dev.th7bo.modupdater.profile;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One named set of mods to launch with, built out of groups.
 *
 * <p>A profile is a composition, not a list of exceptions: {@code mining} is
 * {@code base + performance + mining}. Groups are the unit that gets edited —
 * install a general-purpose mod, add it to {@code base} once, and every profile
 * built on {@code base} picks it up without being touched.
 *
 * <pre>
 * "mining": {
 *   "description": "Mining mods on",
 *   "include": ["base", "performance", "mining"],
 *   "add":     ["skyblockcollectiontracker"],
 *   "remove":  ["bettermap"]
 * }
 * </pre>
 *
 * <p>{@code add} and {@code remove} are the escape hatches for a single mod that
 * does not deserve a group of its own; {@code remove} wins over everything else.
 * {@code includeAll} is the {@code everything} profile — every installed mod,
 * whatever the groups say.
 *
 * <p>Fields are non-final and package-visible rather than a record because Gson
 * populates them straight from {@code profiles.json}. Everything read out of
 * them goes through an accessor that copes with whatever the file contained.
 */
public final class Profile {

    /** What to do with a mod that no group and no profile ever mentions. */
    public enum Ungrouped {
        /**
         * Leave it active. The default: silently switching off a mod the user has
         * never classified is the one mistake a profile system must not make.
         */
        KEEP,
        /** Switch it off. Opt in per profile, for a lean performance set. */
        DISABLE
    }

    String description;
    List<String> include;
    List<String> add;
    List<String> remove;
    boolean includeAll;
    String ungrouped;

    Profile() {
    }

    public String description() {
        return description == null || description.isBlank() ? "" : description.trim();
    }

    public boolean includesEverything() {
        return includeAll;
    }

    public Ungrouped ungrouped() {
        return "disable".equalsIgnoreCase(ungrouped == null ? "" : ungrouped.trim())
                ? Ungrouped.DISABLE
                : Ungrouped.KEEP;
    }

    public List<String> include() {
        return clean(include);
    }

    public List<String> add() {
        return clean(add);
    }

    public List<String> remove() {
        return clean(remove);
    }

    /** The mods this profile asks for: everything its groups hold, plus its own additions. */
    public Set<String> members(ProfileConfig config) {
        Set<String> ids = new LinkedHashSet<>(config.expand(include()));
        ids.addAll(add());
        ids.removeAll(remove());
        return ids;
    }

    /** Every mod id this profile mentions, so unknown ones can be reported. */
    public Set<String> referenced(ProfileConfig config) {
        Set<String> ids = new LinkedHashSet<>(config.expand(include()));
        ids.addAll(add());
        ids.addAll(remove());
        return ids;
    }

    /** Group names it refers to that the config does not define. */
    public Set<String> unknownGroups(ProfileConfig config) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String group : include()) {
            if (!config.groups().containsKey(group)) {
                unknown.add(group);
            }
        }
        return unknown;
    }

    static List<String> clean(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }
}
