package dev.th7bo.modupdater;

import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.profile.Profile;
import dev.th7bo.modupdater.profile.ProfileConfig;
import dev.th7bo.modupdater.profile.ProfileConfigFile;
import dev.th7bo.modupdater.profile.ProfileSession;
import dev.th7bo.modupdater.profile.ProfileState;
import dev.th7bo.modupdater.profile.ProfileToggle;
import dev.th7bo.modupdater.ui.ManagerWindow;
import dev.th7bo.modupdater.util.Log;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code modupdater profile [list|current|use|group|edit|enable|disable]}.
 *
 * <p>On the same executable as {@code check} and {@code apply} deliberately: one
 * thing owns the mods folder, so there is one place a mod can be moved from.
 *
 * <p>Prints to stdout rather than through {@link Log} — this is someone at a
 * terminal asking a question, not a launch hook leaving a record.
 */
final class ProfileCommand {

    private static final String USAGE = """
            Usage: modupdater profile [command]

              list                          groups, profiles, and what is applied now
              current                       the profile on disk
              use <name>                    switch to a profile
              edit                          open the manager window
              group add <group> <mod>...    put mods in a group, creating it if needed
              group remove <group> <mod>... take mods out of a group
              enable                        turn profiles on for this instance
              disable                       turn them off, putting every stored mod back""";

    private ProfileCommand() {
    }

    static int run(Config config, String[] args) {
        String subcommand = subcommand(args);

        // Turning the feature on and off has to work from whichever side it is
        // currently on, so these come before the enabled check.
        if ("enable".equals(subcommand)) {
            return enable(config);
        }
        if ("disable".equals(subcommand)) {
            return disable(config);
        }
        // The window has the on/off switch in it, so it opens either way.
        if ("edit".equals(subcommand)) {
            ManagerWindow.open(config);
            return 0;
        }

        if (!config.profilesEnabled()) {
            System.out.println("Profiles are switched off for this instance.");
            System.out.println();
            System.out.println("    modupdater profile enable");
            System.out.println();
            System.out.println("turns them on and writes a starter config, or");
            System.out.println();
            System.out.println("    modupdater profile edit");
            System.out.println();
            System.out.println("opens a window to sort your mods into groups.");
            return 0;
        }

        ProfileSession session = ProfileSession.load(config);

        return switch (subcommand) {
            case "list" -> list(session);
            case "current" -> current(session);
            case "use" -> use(config, session, args);
            case "group" -> group(session, args);
            default -> {
                System.out.println(USAGE);
                yield 0;
            }
        };
    }

    /** Both toggles report the same outcomes; only the wording is this class's business. */
    private static int enable(Config config) {
        switch (ProfileToggle.enable(config)) {
            case ProfileToggle.Result.Failed failed -> {
                System.out.println(capitalise(failed.detail()));
                System.out.println("Add this line to modupdater.properties by hand:  profiles.enabled=true");
            }
            case ProfileToggle.Result.AlreadyEnabled already -> {
                System.out.println("Profiles are already enabled for this instance.");
                System.out.println("Your profiles are in " + already.profilesFile() + ".");
            }
            case ProfileToggle.Result.Enabled enabled -> {
                System.out.println("Profiles enabled for this instance (" + enabled.properties() + ").");
                if (enabled.starterConfig() != null) {
                    System.out.println();
                    System.out.println("Wrote a starter config to " + enabled.starterConfig() + ".");
                    System.out.println("Every installed mod is in one group called \"base\", so nothing"
                            + " moves until you split it up.");
                    System.out.println("Edit it by hand, with 'modupdater profile group add', or in"
                            + " 'modupdater profile edit'.");
                } else {
                    System.out.println("Your profiles are in " + enabled.profilesFile() + ".");
                }
            }
            default -> System.out.println("Nothing to do.");
        }
        return 0;
    }

    private static int disable(Config config) {
        switch (ProfileToggle.disable(config)) {
            case ProfileToggle.Result.Failed failed -> System.out.println(capitalise(failed.detail()));
            case ProfileToggle.Result.AlreadyDisabled ignored ->
                    System.out.println("Profiles are already switched off for this instance.");
            case ProfileToggle.Result.Disabled disabled -> {
                if (disabled.restored() > 0) {
                    System.out.println("Brought " + disabled.restored()
                            + " stored mod(s) back into mods/.");
                }
                System.out.println("Profiles disabled. This instance now behaves as it did before.");
                System.out.println("Your profiles are still in " + disabled.profilesFile()
                        + " if you want them back.");
            }
            default -> System.out.println("Nothing to do.");
        }
        return 0;
    }

    private static String capitalise(String message) {
        return message.isEmpty() ? message : Character.toUpperCase(message.charAt(0)) + message.substring(1);
    }

    private static int list(ProfileSession session) {
        ProfileConfig profiles = session.config();

        if (profiles.isEmpty()) {
            System.out.println("No profiles are defined yet. Create "
                    + ProfileConfig.fileIn(session.paths().stateDir()) + " to add some.");
            return 0;
        }

        String current = session.state().hasSelection() ? session.state().selected() : null;
        String next = session.preselected();

        if (!profiles.groups().isEmpty()) {
            System.out.println("Groups:");
            profiles.groups().forEach((name, members) ->
                    System.out.printf("  %-14s %s%n", name, String.join(", ", members)));
            System.out.println();
        }

        System.out.println("Profiles:");
        for (String name : profiles.names()) {
            Profile profile = profiles.profile(name).orElseThrow();
            String marker = name.equals(current) ? "*" : " ";
            System.out.printf("%s %-14s %s%n", marker, name, profile.description());

            if (profile.includesEverything()) {
                System.out.printf("   %-14s every installed mod%n", "");
            } else {
                describe("includes", profile.include());
                describe("plus", profile.add());
                describe("minus", profile.remove());
            }
        }

        System.out.println();
        if (current != null) {
            System.out.println("* is applied now.");
        }
        System.out.println("Next launch starts from: " + next);
        return 0;
    }

    private static void describe(String label, List<String> values) {
        if (!values.isEmpty()) {
            System.out.printf("   %-14s %s: %s%n", "", label, String.join(", ", values));
        }
    }

    private static int current(ProfileSession session) {
        ProfileState state = session.state();

        if (!state.hasSelection()) {
            System.out.println("No profile has been applied yet. Next launch starts from: "
                    + session.preselected());
            return 0;
        }

        System.out.println(state.selected()
                + (state.remembered() ? " (remembered)" : " (not remembered)"));
        System.out.println(state.activeModIds().size() + " mod(s) active");
        return 0;
    }

    /**
     * Applies a profile there and then. Safe outside a launch — nothing holds the
     * JARs open — and it goes through the same resolve-plan-move path the
     * pre-launch hook uses, so there is no second implementation to disagree.
     */
    private static int use(Config config, ProfileSession session, String[] args) {
        String name = argument(args);
        if (name == null) {
            System.out.println(USAGE);
            return 0;
        }

        if (!session.config().has(name)) {
            System.out.println("No profile named '" + name + "'. Try: modupdater profile list");
            return 0;
        }

        ModInventory inventory = ModInventory.scan(session.paths(), new InstanceScanner());
        ProfileSession.Outcome outcome = session.apply(inventory, name, config.profileRemember());

        System.out.println("Profile: " + outcome.profileName());
        System.out.println("Active mods: " + outcome.resolution().activeModIds().size()
                + " of " + inventory.size() + " installed");
        return 0;
    }

    /**
     * {@code profile group add|remove <group> <mod>...} — the maintenance that
     * would otherwise mean opening the config to change one line.
     */
    private static int group(ProfileSession session, String[] args) {
        String action = positional(args, 2);
        String name = positional(args, 3);
        List<String> modIds = positionalsFrom(args, 4);

        boolean adding = "add".equals(action);
        boolean removing = "remove".equals(action) || "rm".equals(action);

        if ((!adding && !removing) || name == null || modIds.isEmpty()) {
            System.out.println(USAGE);
            return 0;
        }

        Path stateDir = session.paths().stateDir();
        ProfileConfigFile.Result result = adding
                ? ProfileConfigFile.addToGroup(stateDir, name, modIds)
                : ProfileConfigFile.removeFromGroup(stateDir, name, modIds);

        switch (result) {
            case ProfileConfigFile.Result.Failed failed ->
                    System.out.println("Could not edit " + ProfileConfig.FILE + ": " + failed.detail());
            case ProfileConfigFile.Result.Unchanged unchanged -> System.out.println(
                    "'" + unchanged.group() + "' already " + (adding ? "has" : "lacks")
                            + " all of those. It holds: " + describeMembers(unchanged.members()));
            case ProfileConfigFile.Result.Changed changed -> {
                System.out.println((adding ? "Added to " : "Removed from ") + "'" + changed.group()
                        + "': " + String.join(", ", changed.affected()));
                System.out.println("'" + changed.group() + "' now holds: "
                        + describeMembers(changed.members()));
                warnAboutStrangers(session, changed.affected(), adding);
            }
        }

        return 0;
    }

    private static String describeMembers(List<String> members) {
        return members.isEmpty() ? "nothing" : String.join(", ", members);
    }

    /**
     * A group may name a mod that is not installed — the user could be about to
     * install it — so this warns rather than refusing. Nothing here has moved a
     * file, and the resolver already skips ids it cannot find.
     */
    private static void warnAboutStrangers(ProfileSession session, List<String> modIds, boolean adding) {
        if (!adding) {
            return;
        }

        var installed = ModInventory.scan(session.paths(), new InstanceScanner()).modIds().stream()
                .map(ProfileConfig::normalise)
                .toList();

        List<String> unknown = modIds.stream().filter(id -> !installed.contains(id)).toList();
        if (!unknown.isEmpty()) {
            System.out.println();
            System.out.println("Note: not installed right now — " + String.join(", ", unknown));
            System.out.println("That is fine, they just do nothing until they are.");
        }
    }

    /** The word after {@code profile}, ignoring flags. Defaults to listing. */
    private static String subcommand(String[] args) {
        String value = positional(args, 1);
        return value == null ? "list" : value;
    }

    private static String argument(String[] args) {
        return positional(args, 2);
    }

    /** Every positional from {@code index} onwards — the mod ids of a group edit. */
    private static List<String> positionalsFrom(String[] args, int index) {
        List<String> values = new ArrayList<>();
        for (int i = index; ; i++) {
            String value = positional(args, i);
            if (value == null) {
                return values;
            }
            values.add(value);
        }
    }

    private static String positional(String[] args, int index) {
        if (args == null) {
            return null;
        }

        int seen = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                // Skip the flag's value too, unless it was written as --name=value.
                if (!args[i].contains("=") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    i++;
                }
                continue;
            }
            if (seen++ == index) {
                return args[i];
            }
        }
        return null;
    }
}
