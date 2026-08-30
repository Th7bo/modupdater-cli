package dev.th7bo.modupdater.profile;

import dev.th7bo.modupdater.Config;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.util.Log;

import java.nio.file.Files;
import java.util.List;

/**
 * The profile feature, as one launch sees it.
 *
 * <p>Everything the rest of the program needs to ask about profiles goes through
 * here: whether they are on at all, which one to offer, and what to do once the
 * user has chosen. When the feature is off this object exists but answers no to
 * everything, so the update path needs no branching of its own.
 */
public final class ProfileSession {

    private final ModPaths paths;
    private final boolean enabled;
    private final ProfileConfig config;
    private final ProfileState state;
    private final String configuredDefault;
    private final boolean prompt;
    private final boolean remember;

    private ProfileSession(
            ModPaths paths,
            boolean enabled,
            ProfileConfig config,
            ProfileState state,
            String configuredDefault,
            boolean prompt,
            boolean remember) {

        this.paths = paths;
        this.enabled = enabled;
        this.config = config;
        this.state = state;
        this.configuredDefault = configuredDefault;
        this.prompt = prompt;
        this.remember = remember;
    }

    /**
     * Reads the feature's state for this instance.
     *
     * <p>Nothing is created here. An instance with profiles switched off — which
     * is every instance that has not opted in — reads no files it did not read
     * before and writes none at all.
     */
    public static ProfileSession load(Config config) {
        ModPaths paths = ModPaths.of(config.modsDir());

        if (!config.profilesEnabled()) {
            return new ProfileSession(
                    paths, false, ProfileConfig.empty(), ProfileState.none(), null, false, false);
        }

        ProfileConfig profiles = ProfileConfig.read(paths.stateDir());
        if (profiles.isEmpty()) {
            Log.warn("profiles are enabled but " + ProfileConfig.fileIn(paths.stateDir())
                    + " defines none — launching with every installed mod active");
        }

        return new ProfileSession(
                paths,
                true,
                profiles,
                ProfileState.read(paths.stateDir()),
                config.profileDefault(),
                config.profilePrompt(),
                config.profileRemember());
    }

    public boolean enabled() {
        return enabled;
    }

    /** True when there is a choice worth putting in front of the user. */
    public boolean shouldPrompt() {
        return enabled && prompt && !config.isEmpty();
    }

    public boolean rememberSelections() {
        return remember;
    }

    public ProfileConfig config() {
        return config;
    }

    public ModPaths paths() {
        return paths;
    }

    /** The profiles to offer, in the order the file defines them. */
    public List<String> names() {
        return config.names();
    }

    /**
     * The profile to start from: what was chosen last time if we are remembering
     * and it still exists, then {@code profile.default}, then whatever the file
     * lists first, and finally the built-in "everything".
     *
     * <p>Each step falls through rather than failing. A profile deleted from the
     * config between launches is a reason to pick something else, never a reason
     * to stop the game from starting.
     */
    public String preselected() {
        if (state.remembered() && state.hasSelection() && config.has(state.selected())) {
            return ProfileConfig.normalise(state.selected());
        }

        if (configuredDefault != null && !configuredDefault.isBlank()) {
            if (config.has(configuredDefault)) {
                return ProfileConfig.normalise(configuredDefault);
            }
            Log.warn("profile.default is '" + configuredDefault
                    + "', which is not defined in " + ProfileConfig.FILE);
        }

        return config.names().isEmpty() ? ProfileConfig.EVERYTHING : config.names().get(0);
    }

    /** What the last applied profile was, for {@code modupdater profile current}. */
    public ProfileState state() {
        return state;
    }

    /**
     * Materialises a profile into {@code mods/}.
     *
     * @param inventory a fresh inventory — after any updates, so the filenames are
     *                  the ones now on disk
     */
    public Outcome apply(ModInventory inventory, String profileName, boolean rememberIt) {
        ProfileResolver.Resolution resolution = config.isEmpty()
                ? ProfileResolver.everything(inventory)
                : ProfileResolver.resolve(config, profileName, inventory);

        resolution.warnings().forEach(Log::warn);

        Log.info("selected profile: " + resolution.profileName());
        Log.info("logical installed mods: " + inventory.size()
                + " (" + inventory.active().size() + " active, " + inventory.inactive().size() + " stored)");
        Log.info("target active mods: " + resolution.activeModIds().size());

        ProfilePlan plan = ProfilePlan.of(paths, inventory, resolution);
        ProfileManager.Result result = new ProfileManager(paths).apply(plan);

        // Always recorded, so `profile current` and the log can say what the
        // instance is running. Whether it is *offered* again next launch is the
        // separate question the flag answers.
        new ProfileState(
                resolution.profileName(),
                rememberIt,
                System.currentTimeMillis(),
                List.copyOf(resolution.activeModIds()))
                .write(paths.stateDir());

        return new Outcome(resolution, plan, result);
    }

    public record Outcome(
            ProfileResolver.Resolution resolution, ProfilePlan plan, ProfileManager.Result result) {

        public String profileName() {
            return resolution.profileName();
        }
    }

    /**
     * Mods sitting in storage while the feature is switched off — the state left
     * by turning profiles off after using them. Nothing is moved back
     * automatically: an instance with profiles disabled is one this program does
     * not rearrange.
     */
    public boolean hasStrandedMods() {
        return !enabled && Files.isDirectory(paths.inactiveDir()) && anyJarIn();
    }

    private boolean anyJarIn() {
        try (var entries = Files.list(paths.inactiveDir())) {
            return entries.anyMatch(path -> path.getFileName().toString().endsWith(".jar"));
        } catch (java.io.IOException e) {
            return false;
        }
    }
}
