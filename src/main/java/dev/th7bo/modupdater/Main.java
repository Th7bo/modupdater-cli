package dev.th7bo.modupdater;

import dev.th7bo.modupdater.diff.Differ;
import dev.th7bo.modupdater.diff.UpdateCandidate;
import dev.th7bo.modupdater.install.Downloader;
import dev.th7bo.modupdater.install.Installer;
import dev.th7bo.modupdater.install.ModInstaller;
import dev.th7bo.modupdater.install.PendingState;
import dev.th7bo.modupdater.install.UpdateRequest;
import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.manifest.FetchResult;
import dev.th7bo.modupdater.manifest.ManifestClient;
import dev.th7bo.modupdater.profile.ProfileManager;
import dev.th7bo.modupdater.profile.ProfileSession;
import dev.th7bo.modupdater.setup.Instance;
import dev.th7bo.modupdater.setup.InstanceDiscovery;
import dev.th7bo.modupdater.setup.ModrinthHooks;
import dev.th7bo.modupdater.ui.DialogViewModel;
import dev.th7bo.modupdater.ui.ProfileDialog;
import dev.th7bo.modupdater.ui.UpdateDialog;
import dev.th7bo.modupdater.util.Log;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Entry point.
 *
 * <p><strong>Exit code contract:</strong> 0 on every non-fatal path — nothing to
 * update, server unreachable, token rejected, endpoint unconfigured, malformed
 * response, no display, user skipped. A non-zero exit from a launcher's
 * pre-launch hook blocks the launch, which the user experiences as a broken
 * launcher rather than a broken updater. The only non-zero exit is the user
 * explicitly choosing to cancel the launch.
 */
public final class Main {

    private static final int OK = 0;
    private static final int ABORT_LAUNCH = 1;

    /** Below this, the session that followed an update is treated as a failed launch. */
    private static final Duration MIN_HEALTHY_SESSION = Duration.ofMinutes(2);

    public static void main(String[] args) {
        // Must happen before any AWT class loads, or the toolkit caches the
        // defaults and text renders without antialiasing — which is what makes
        // Swing look pixelated next to every other window on the desktop.
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        // No reliable way to detect display scaling on Wayland via XWayland when
        // Xft.dpi is unset, so leave an override for anyone on a HiDPI screen.
        String uiScale = System.getenv("MODUPDATER_UI_SCALE");
        if (uiScale != null && !uiScale.isBlank()) {
            System.setProperty("sun.java2d.uiScale", uiScale.trim());
        }

        int code = OK;
        try {
            code = run(args);
        } catch (Throwable t) {
            // Anything unexpected still must not block the game from starting.
            Log.error("unexpected failure: " + t);
            code = OK;
        }
        System.exit(code);
    }

    static int run(String[] args) {
        String command = args != null && args.length > 0 && !args[0].startsWith("--") ? args[0] : "check";
        Config config = Config.resolve(args);

        Log.init(Installer.stateDir(config.modsDir()), config.readToken());

        return switch (command) {
            case "check" -> check(config);
            case "apply" -> apply(config);
            case "profile" -> ProfileCommand.run(config, args);
            case "list-instances" -> listInstances();
            case "install-mod" -> installMod(config, args);
            case "configure-modrinth" -> configureModrinth(config, args);
            default -> {
                Log.warn("unknown command '" + command
                        + "', expected 'check', 'apply', 'profile' or 'install-mod'");
                yield OK;
            }
        };
    }

    /**
     * Prints discovered instances as TSV for the installer scripts. Straight to
     * stdout rather than through {@link Log}, since it is machine-read.
     */
    private static int listInstances() {
        for (Instance instance : InstanceDiscovery.forThisMachine().discover()) {
            System.out.println(instance.toTsv());
        }
        return OK;
    }

    /**
     * Installs a mod from the manifest that the instance does not have.
     *
     * Used by the setup script to offer the in-game notifier. Exits 0 whatever
     * happens: this runs during setup, and failing to add an optional extra is
     * not a reason to fail the whole installation.
     */
    private static int installMod(Config config, String[] args) {
        String modId = flagValue(args, "--mod-id", "modupdater");

        if (!config.usable()) {
            Log.warn("no base URL configured; cannot install " + modId);
            return OK;
        }

        String token = config.readToken();
        if (token == null) {
            Log.warn("no token configured; cannot install " + modId);
            return OK;
        }

        FetchResult fetched = new ManifestClient().fetch(config.baseUrl(), token, config.mcVersion());
        if (!(fetched instanceof FetchResult.Ok ok)) {
            Log.warn(fetched.describe());
            return OK;
        }

        ModInstaller.Result result =
                new ModInstaller(Downloader.http()).install(ok.manifest(), modId, config.modsDir(), token);

        switch (result) {
            case ModInstaller.Result.Installed installed -> Log.info("installed " + installed.filename());
            case ModInstaller.Result.AlreadyPresent present ->
                    Log.info(present.filename() + " is already installed");
            case ModInstaller.Result.NotOffered notOffered -> Log.warn(
                    "the server offers no build of " + notOffered.modId()
                            + " for Minecraft " + config.mcVersion());
            case ModInstaller.Result.Failed failed -> Log.error("could not install " + modId + ": " + failed.detail());
        }

        return OK;
    }

    /**
     * Fills in Modrinth App's launch hooks, which its own settings screen refuses
     * to save.
     *
     * <p>Exits 0 whatever happens, like the rest of setup: failing to automate a
     * step the user can still do by hand is not worth failing the install over.
     */
    private static int configureModrinth(Config config, String[] args) {
        String pre = flagValue(args, "--pre", null);
        String post = flagValue(args, "--post", null);

        if (pre == null || post == null) {
            Log.error("configure-modrinth needs --pre and --post");
            return OK;
        }

        Path gameDir = config.modsDir().getParent();
        if (gameDir == null) {
            Log.error("could not work out the instance folder from " + config.modsDir());
            return OK;
        }

        ModrinthHooks.Result result = ModrinthHooks.configure(gameDir, pre, post);

        switch (result) {
            case ModrinthHooks.Result.Configured ok -> {
                Log.info("configured Modrinth hooks for " + ok.instanceName());
                Log.info("a copy of the database was saved next to it before writing");
            }
            default -> {
                Log.warn(result.describe());
                Log.warn("set these by hand in Options > Hooks:");
                Log.warn("  Pre-launch: " + ModrinthHooks.hookCommand(pre));
                Log.warn("  Post-exit:  " + ModrinthHooks.hookCommand(post));
            }
        }

        return OK;
    }

    private static String flagValue(String[] args, String flag, String fallback) {
        if (args == null) return fallback;
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) return args[i + 1];
        }
        return fallback;
    }

    private static int check(Config config) {
        // Settle the previous session the same way the post-exit hook would.
        //
        // This used to call restoreIfUnconfirmed directly, which reverts anything
        // still marked unconfirmed without ever reading the launch marker. Post-exit
        // is the only place that sets the confirmed flag, and it does not run when a
        // mod wedges the JVM in a shutdown hook and the process has to be killed — so
        // every update was reverted at the next launch no matter how long the session
        // had lasted, and the same three mods reinstalled themselves for a week.
        settleLastSession(config);

        // A request left over from last session. Normally the post-exit hook has
        // already installed it, but that hook only runs if the game process
        // actually exits — and a mod hanging in a shutdown hook leaves it alive
        // until the user kills it, which skips post-exit entirely. Doing it here
        // as well means "update on exit" survives that, and pre-launch is the
        // better moment anyway: nothing holds the JARs open yet.
        applyRequest(config);

        ProfileSession profiles = ProfileSession.load(config);
        ModPaths paths = profiles.paths();

        // Both halves of the inventory, always. The inactive folder is only read
        // when it already exists, so an instance that has never touched profiles
        // is scanned exactly as it always was — and one that has keeps its stored
        // mods in the update check, because a mod left out of a profile is still
        // installed.
        ModInventory inventory = ModInventory.scan(paths, new InstanceScanner());
        Log.info("scanned " + inventory.size() + " mod(s) in " + config.modsDir()
                + (inventory.inactive().isEmpty()
                        ? ""
                        : " (" + inventory.inactive().size() + " in profile storage)"));

        if (profiles.hasStrandedMods()) {
            Log.warn("profiles are switched off but mods are still stored in " + paths.inactiveDir()
                    + " — the game will not load them. Run 'modupdater profile enable' to pick a"
                    + " profile again, or 'modupdater profile disable' to put them all back.");
        }

        List<UpdateCandidate> candidates = updateCandidates(config, inventory);

        return profiles.enabled()
                ? checkWithProfiles(config, profiles, inventory, candidates)
                : offerUpdates(config, candidates);
    }

    /**
     * What the server has that this instance does not, or an empty list with the
     * reason logged. Every way this can come up short — no server, no token, no
     * Minecraft version, an unreachable or unreadable manifest — means launching
     * with what is already installed.
     */
    private static List<UpdateCandidate> updateCandidates(Config config, ModInventory inventory) {
        if (!config.usable()) {
            Log.warn("no base URL configured — set base.url in modupdater.properties. Launching unchanged.");
            return List.of();
        }

        String token = config.readToken();
        if (token == null) {
            Log.warn("no token configured — put one in " + config.tokenFile() + ". Launching unchanged.");
            return List.of();
        }

        FetchResult fetched = new ManifestClient().fetch(config.baseUrl(), token, config.mcVersion());
        if (!(fetched instanceof FetchResult.Ok ok)) {
            Log.warn(fetched.describe() + " — launching unchanged");
            return List.of();
        }

        if (config.mcVersion() == null || config.mcVersion().isBlank()) {
            Log.warn("no Minecraft version configured — cannot check compatibility, launching unchanged");
            return List.of();
        }

        List<UpdateCandidate> candidates =
                Differ.plan(inventory.all(), ok.manifest(), config.mcVersion());
        if (candidates.isEmpty()) {
            Log.info("everything is up to date");
        }
        return candidates;
    }

    /** The update prompt on its own — the path every instance without profiles takes. */
    private static int offerUpdates(Config config, List<UpdateCandidate> candidates) {
        if (candidates.isEmpty()) {
            return OK;
        }

        DialogViewModel model = new DialogViewModel(candidates);

        if (DialogViewModel.headless()) {
            reportUnapplied(model);
            return OK;
        }

        announceDialog();

        return switch (UpdateDialog.show(model)) {
            case UpdateDialog.Choice.Abort ignored -> {
                Log.info("launch cancelled by the user");
                yield ABORT_LAUNCH;
            }
            case UpdateDialog.Choice.Skip ignored -> {
                Log.info("user skipped the updates");
                yield OK;
            }
            case UpdateDialog.Choice.Update update -> {
                install(config, update.chosen());
                yield OK;
            }
        };
    }

    /**
     * Updates and the profile in one pass.
     *
     * <p>Order matters. Updates are installed first, because an update renames the
     * JAR and the profile has to move whatever is on disk afterwards; the
     * inventory is re-read in between so the moves refer to the files that now
     * exist. The profile then follows, and any pending rollback is repointed at
     * wherever its JAR ended up.
     */
    private static int checkWithProfiles(
            Config config,
            ProfileSession profiles,
            ModInventory inventory,
            List<UpdateCandidate> candidates) {

        DialogViewModel model = new DialogViewModel(candidates);
        boolean prompting = profiles.shouldPrompt() && !DialogViewModel.headless();

        if (prompting) {
            model.offerProfiles(
                    ProfileDialog.options(profiles.config()),
                    profiles.preselected(),
                    profiles.state().remembered() || config.profileRemember());
        }

        String selected = profiles.preselected();
        boolean remember = profiles.rememberSelections();

        if (!candidates.isEmpty() && DialogViewModel.headless()) {
            reportUnapplied(model);
        } else if (!candidates.isEmpty()) {
            announceDialog();

            UpdateDialog.Choice choice = UpdateDialog.show(model, config);
            if (prompting) {
                selected = model.selectedProfile();
                remember = model.rememberProfile();
            }

            switch (choice) {
                case UpdateDialog.Choice.Abort ignored -> {
                    Log.info("launch cancelled by the user");
                    return ABORT_LAUNCH;
                }
                case UpdateDialog.Choice.Skip ignored -> Log.info("user skipped the updates");
                case UpdateDialog.Choice.Update update -> install(config, update.chosen());
            }
        } else if (prompting) {
            Log.info("waiting for your answer in the \"Choose a mod profile\" window"
                    + " (check your other workspaces if you can't see it)");

            ProfileDialog.Choice choice = ProfileDialog.show(model, config);
            if (choice instanceof ProfileDialog.Choice.Abort) {
                Log.info("launch cancelled by the user");
                return ABORT_LAUNCH;
            }
            if (choice instanceof ProfileDialog.Choice.Launch launch) {
                selected = launch.profile();
                remember = launch.remember();
            }
        }

        applyProfile(config, profiles, selected, remember);
        return OK;
    }

    /**
     * Puts the chosen profile on disk, re-reading the inventory first so it acts
     * on the filenames any update just left behind.
     */
    private static void applyProfile(
            Config config, ProfileSession profiles, String selected, boolean remember) {

        ModInventory current = ModInventory.scan(profiles.paths(), new InstanceScanner());
        ProfileSession.Outcome outcome = profiles.apply(current, selected, remember);

        if (outcome.result() instanceof ProfileManager.Result.Applied applied
                && !applied.moved().isEmpty()) {
            // An update installed moments ago may have just been switched off. The
            // rollback record has to follow it, or a failed launch would restore
            // the old JAR into mods/ and turn a mod the profile excluded back on.
            Path stateDir = Installer.stateDir(config.modsDir());
            PendingState pending = PendingState.read(stateDir);
            if (!pending.entries().isEmpty()) {
                pending.relocate(applied.moved()).write(stateDir);
            }
        }
    }

    private static void reportUnapplied(DialogViewModel model) {
        Log.info("no display available; " + model.rows().size() + " update(s) not applied:");
        model.rows().forEach(row -> Log.info("  " + row.name() + " " + row.installedVersion()
                + " -> " + row.availableVersion()));
    }

    /**
     * The launcher blocks on this hook while the dialog is up, and a tiling window
     * manager may have put that dialog on another workspace. Say so, or a waiting
     * prompt is indistinguishable from a frozen launcher.
     */
    private static void announceDialog() {
        Log.info("waiting for your answer in the \"Mod updates available\" window"
                + " (check your other workspaces if you can't see it)");
    }

    private static void install(Config config, List<UpdateCandidate> chosen) {
        Installer.Outcome outcome =
                new Installer(Downloader.http()).install(chosen, config.modsDir(), config.readToken());

        if (outcome.anyInstalled()) {
            Log.info("installed " + outcome.installed().size() + " update(s)");
        }
        outcome.failures().forEach((filename, reason) -> Log.error(filename + ": " + reason));
    }

    private static int apply(Config config) {
        // Settle the session that just ended before acting on anything new: that
        // decision is about the update already on disk, and installing first would
        // overwrite the state it is judged by.
        settleLastSession(config);

        applyRequest(config);

        relaunch(config);
        return OK;
    }

    /**
     * Decides whether the update installed before the last session survived it.
     *
     * <p>Run from both hooks, because neither is guaranteed to fire: post-exit is
     * skipped when the game has to be killed, and pre-launch is skipped when the
     * launcher starts the game some other way. Whichever runs first settles it, and
     * the second finds nothing pending.
     *
     * <p>The launch marker is the real answer. Elapsed time is only the fallback for
     * instances without the in-game mod, and it is a weaker signal here than
     * post-exit: it measures time since the install rather than the length of the
     * session, so a crash followed by a long break reads as a success.
     */
    private static void settleLastSession(Config config) {
        Installer.SessionOutcome outcome = Installer.resolveAfterSession(
                config.modsDir(), MIN_HEALTHY_SESSION, System.currentTimeMillis());

        switch (outcome) {
            case Installer.SessionOutcome.NothingPending ignored -> Log.info("nothing pending");
            case Installer.SessionOutcome.Confirmed confirmed ->
                    Log.info("confirmed " + confirmed.modCount() + " update(s)");
            case Installer.SessionOutcome.RolledBack rolledBack -> Log.warn(
                    "rolled back after a failed launch: " + String.join(", ", rolledBack.modIds()));
        }
    }

    /**
     * Carries out whatever the in-game mod asked for.
     *
     * @return true when something was installed
     */
    private static boolean applyRequest(Config config) {
        Path stateDir = Installer.stateDir(config.modsDir());
        UpdateRequest request = UpdateRequest.read(stateDir);

        if (request == null) {
            return false;
        }

        // Cleared first. A request that fails halfway must not be retried forever
        // on every subsequent exit; the mod will simply offer the update again.
        UpdateRequest.clear(stateDir);

        String token = config.readToken();
        if (token == null) {
            Log.warn("an update was requested in-game but no token is configured");
            return false;
        }

        Log.info("installing " + request.entries().size() + " update(s) requested in-game");

        Installer.Outcome outcome =
                new Installer(Downloader.http()).installItems(request.items(), config.modsDir(), token);

        outcome.failures().forEach((filename, reason) -> Log.error(filename + ": " + reason));

        if (outcome.anyInstalled()) {
            Log.info("installed " + outcome.installed().size() + " update(s)");
        }

        return outcome.anyInstalled();
    }

    private static void relaunch(Config config) {
        String command = config.relaunchCommand();
        if (command == null || command.isBlank()) {
            return;
        }

        try {
            Log.info("running relaunch command");
            new ProcessBuilder(command.split("\\s+")).inheritIO().start();
        } catch (Exception e) {
            Log.error("relaunch command failed: " + e.getMessage());
        }
    }

    private Main() {
    }
}
