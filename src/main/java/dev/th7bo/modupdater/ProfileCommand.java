package dev.th7bo.modupdater;

import com.google.gson.GsonBuilder;
import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.profile.Profile;
import dev.th7bo.modupdater.profile.ProfileConfig;
import dev.th7bo.modupdater.profile.ProfileManager;
import dev.th7bo.modupdater.profile.ProfilePlan;
import dev.th7bo.modupdater.profile.ProfileResolver;
import dev.th7bo.modupdater.profile.ProfileSession;
import dev.th7bo.modupdater.profile.ProfileState;
import dev.th7bo.modupdater.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code modupdater profile [list|current|use <name>|enable|disable]}.
 *
 * <p>On the same executable as {@code check} and {@code apply} deliberately: one
 * thing owns the mods folder, so there is one place a mod can be moved from.
 *
 * <p>Prints to stdout rather than through {@link Log} — this is someone at a
 * terminal asking a question, not a launch hook leaving a record.
 */
final class ProfileCommand {

    private static final String USAGE =
            "Usage: modupdater profile [list | current | use <name> | enable | disable]";

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

        if (!config.profilesEnabled()) {
            System.out.println("Profiles are switched off for this instance.");
            System.out.println();
            System.out.println("    modupdater profile enable");
            System.out.println();
            System.out.println("turns them on and writes a starter config you can edit.");
            return 0;
        }

        ProfileSession session = ProfileSession.load(config);

        return switch (subcommand) {
            case "list" -> list(session);
            case "current" -> current(session);
            case "use" -> use(config, session, args);
            default -> {
                System.out.println(USAGE);
                yield 0;
            }
        };
    }

    /**
     * Switches the feature on for this instance: sets the property, and writes a
     * starter {@code profiles.json} listing what is installed so there is
     * something real to edit rather than a blank file.
     */
    private static int enable(Config config) {
        Path properties = config.propertiesFile();
        if (properties == null) {
            System.out.println("Could not work out where modupdater.properties belongs from "
                    + config.modsDir());
            return 0;
        }

        ModPaths paths = ModPaths.of(config.modsDir());
        Path profilesFile = ProfileConfig.fileIn(paths.stateDir());
        boolean alreadyOn = config.profilesEnabled();

        if (!alreadyOn) {
            try {
                PropertiesFile.set(properties, "profiles.enabled", "true");
            } catch (IOException e) {
                System.out.println("Could not write " + properties + ": " + e.getMessage());
                System.out.println("Add this line to it by hand:  profiles.enabled=true");
                return 0;
            }
            System.out.println("Profiles enabled for this instance (" + properties + ").");
        } else {
            System.out.println("Profiles are already enabled for this instance.");
        }

        // Never overwritten. Somebody's groups are not ours to replace, and this
        // command is the one they would run again after forgetting they had.
        if (Files.isRegularFile(profilesFile)) {
            System.out.println("Your profiles are in " + profilesFile + ".");
            return 0;
        }

        ModInventory inventory = ModInventory.scan(paths, new InstanceScanner());
        try {
            writeStarterConfig(profilesFile, inventory);
        } catch (IOException e) {
            System.out.println("Could not write " + profilesFile + ": " + e.getMessage());
            return 0;
        }

        System.out.println();
        System.out.println("Wrote a starter config to " + profilesFile + ".");
        System.out.println("Every installed mod is in one group called \"base\", so nothing moves"
                + " until you split it up.");
        System.out.println("Edit it, then: modupdater profile list");
        return 0;
    }

    /**
     * Switches the feature off — after bringing every stored mod back into
     * {@code mods/}.
     *
     * <p>Order matters. Disabling first would strand whatever the current profile
     * had switched off: the game would not load it and nothing would move it back,
     * because an instance with profiles off is one this program does not rearrange.
     */
    private static int disable(Config config) {
        Path properties = config.propertiesFile();
        if (properties == null) {
            System.out.println("Could not work out where modupdater.properties belongs from "
                    + config.modsDir());
            return 0;
        }

        if (!config.profilesEnabled()) {
            System.out.println("Profiles are already switched off for this instance.");
            return 0;
        }

        ModPaths paths = ModPaths.of(config.modsDir());
        ModInventory inventory = ModInventory.scan(paths, new InstanceScanner());

        if (!inventory.inactive().isEmpty()) {
            ProfilePlan plan = ProfilePlan.of(paths, inventory, ProfileResolver.everything(inventory));
            ProfileManager.Result result = new ProfileManager(paths).apply(plan);

            if (result instanceof ProfileManager.Result.Failed failed) {
                // Leave the feature on: it is the only thing that can tidy this up.
                System.out.println("Could not bring every mod back into mods/: " + failed.detail());
                System.out.println("Profiles are still enabled. Fix that, then try again.");
                return 0;
            }
            System.out.println("Brought " + plan.activate().size() + " stored mod(s) back into mods/.");
        }

        try {
            PropertiesFile.set(properties, "profiles.enabled", "false");
        } catch (IOException e) {
            System.out.println("Could not write " + properties + ": " + e.getMessage());
            return 0;
        }

        System.out.println("Profiles disabled. This instance now behaves as it did before.");
        System.out.println("Your profiles are still in " + ProfileConfig.fileIn(paths.stateDir())
                + " if you want them back.");
        return 0;
    }

    /**
     * One group holding everything installed, and two profiles that both amount to
     * "all of it". Deliberately a no-op set: enabling the feature must not change
     * which mods load, only make it possible to.
     */
    private static void writeStarterConfig(Path file, ModInventory inventory) throws IOException {
        Map<String, Object> profiles = new LinkedHashMap<>();
        profiles.put("general", Map.of(
                "description", "Everything you have now — split this up as you go",
                "include", List.of("base")));
        profiles.put("everything", Map.of(
                "description", "Every installed mod",
                "includeAll", true));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("groups", Map.of("base", List.copyOf(inventory.modIds())));
        document.put("profiles", profiles);

        Files.createDirectories(file.getParent());
        Files.writeString(file,
                new GsonBuilder().setPrettyPrinting().create().toJson(document) + System.lineSeparator(),
                StandardCharsets.UTF_8);
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
