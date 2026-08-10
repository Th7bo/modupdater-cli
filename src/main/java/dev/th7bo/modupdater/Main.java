package dev.th7bo.modupdater;

import dev.th7bo.modupdater.diff.Differ;
import dev.th7bo.modupdater.diff.UpdateCandidate;
import dev.th7bo.modupdater.install.Downloader;
import dev.th7bo.modupdater.install.Installer;
import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.manifest.FetchResult;
import dev.th7bo.modupdater.manifest.ManifestClient;
import dev.th7bo.modupdater.setup.Instance;
import dev.th7bo.modupdater.setup.InstanceDiscovery;
import dev.th7bo.modupdater.ui.DialogViewModel;
import dev.th7bo.modupdater.ui.UpdateDialog;
import dev.th7bo.modupdater.util.Log;

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
            case "list-instances" -> listInstances();
            default -> {
                Log.warn("unknown command '" + command + "', expected 'check' or 'apply'");
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

    private static int check(Config config) {
        List<String> rolledBack = Installer.restoreIfUnconfirmed(config.modsDir());
        if (!rolledBack.isEmpty()) {
            Log.warn("rolled back after a failed launch: " + String.join(", ", rolledBack));
        }

        if (!config.usable()) {
            Log.warn("no base URL configured — set base.url in modupdater.properties. Launching unchanged.");
            return OK;
        }

        String token = config.readToken();
        if (token == null) {
            Log.warn("no token configured — put one in " + config.tokenFile() + ". Launching unchanged.");
            return OK;
        }

        FetchResult fetched = new ManifestClient().fetch(config.baseUrl(), token, config.mcVersion());
        if (!(fetched instanceof FetchResult.Ok ok)) {
            Log.warn(fetched.describe() + " — launching unchanged");
            return OK;
        }

        List<InstalledMod> installed = new InstanceScanner().scan(config.modsDir());
        Log.info("scanned " + installed.size() + " mod(s) in " + config.modsDir());

        if (config.mcVersion() == null || config.mcVersion().isBlank()) {
            Log.warn("no Minecraft version configured — cannot check compatibility, launching unchanged");
            return OK;
        }

        List<UpdateCandidate> candidates = Differ.plan(installed, ok.manifest(), config.mcVersion());
        if (candidates.isEmpty()) {
            Log.info("everything is up to date");
            return OK;
        }

        DialogViewModel model = new DialogViewModel(candidates);

        if (DialogViewModel.headless()) {
            Log.info("no display available; " + candidates.size() + " update(s) not applied:");
            model.rows().forEach(row -> Log.info("  " + row.name() + " " + row.installedVersion()
                    + " -> " + row.availableVersion()));
            return OK;
        }

        // The launcher blocks on this hook while the dialog is up, and a tiling
        // window manager may have put that dialog on another workspace. Say so,
        // or a waiting prompt is indistinguishable from a frozen launcher.
        Log.info("waiting for your answer in the \"Mod updates available\" window"
                + " (check your other workspaces if you can't see it)");

        UpdateDialog.Choice choice = UpdateDialog.show(model);

        return switch (choice) {
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

    private static void install(Config config, List<UpdateCandidate> chosen) {
        Installer.Outcome outcome =
                new Installer(Downloader.http()).install(chosen, config.modsDir(), config.readToken());

        if (outcome.anyInstalled()) {
            Log.info("installed " + outcome.installed().size() + " update(s)");
        }
        outcome.failures().forEach((filename, reason) -> Log.error(filename + ": " + reason));
    }

    private static int apply(Config config) {
        Installer.SessionOutcome outcome = Installer.resolveAfterSession(
                config.modsDir(), MIN_HEALTHY_SESSION, System.currentTimeMillis());

        switch (outcome) {
            case Installer.SessionOutcome.NothingPending ignored -> Log.info("nothing pending");
            case Installer.SessionOutcome.Confirmed confirmed ->
                    Log.info("confirmed " + confirmed.modCount() + " update(s)");
            case Installer.SessionOutcome.RolledBack rolledBack -> Log.warn(
                    "rolled back " + rolledBack.modIds().size() + " update(s): "
                            + String.join(", ", rolledBack.modIds()));
        }

        relaunch(config);
        return OK;
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
