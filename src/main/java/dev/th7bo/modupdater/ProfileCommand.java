package dev.th7bo.modupdater;

import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.profile.Profile;
import dev.th7bo.modupdater.profile.ProfileConfig;
import dev.th7bo.modupdater.profile.ProfileSession;
import dev.th7bo.modupdater.profile.ProfileState;
import dev.th7bo.modupdater.util.Log;

import java.util.List;

/**
 * {@code modupdater profile [list|current|use <name>]}.
 *
 * <p>On the same executable as {@code check} and {@code apply} deliberately: one
 * thing owns the mods folder, so there is one place a mod can be moved from.
 *
 * <p>Prints to stdout rather than through {@link Log} — this is someone at a
 * terminal asking a question, not a launch hook leaving a record.
 */
final class ProfileCommand {

    private ProfileCommand() {
    }

    static int run(Config config, String[] args) {
        String subcommand = subcommand(args);

        if (!config.profilesEnabled()) {
            System.out.println("Profiles are switched off for this instance.");
            System.out.println();
            System.out.println("Add this to modupdater.properties, next to mods/:");
            System.out.println("    profiles.enabled=true");
            System.out.println();
            System.out.println("Then describe your groups and profiles in "
                    + ProfileConfig.fileIn(ModPaths.of(config.modsDir()).stateDir()));
            return 0;
        }

        ProfileSession session = ProfileSession.load(config);

        return switch (subcommand) {
            case "list" -> list(session);
            case "current" -> current(session);
            case "use" -> use(config, session, args);
            default -> {
                System.out.println("Usage: modupdater profile [list | current | use <name>]");
                yield 0;
            }
        };
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
            System.out.println("Usage: modupdater profile use <name>");
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

    /** The word after {@code profile}, ignoring flags. Defaults to listing. */
    private static String subcommand(String[] args) {
        String value = positional(args, 1);
        return value == null ? "list" : value;
    }

    private static String argument(String[] args) {
        return positional(args, 2);
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
