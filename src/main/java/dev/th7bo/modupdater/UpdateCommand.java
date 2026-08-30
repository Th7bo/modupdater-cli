package dev.th7bo.modupdater;

import dev.th7bo.modupdater.install.Downloader;
import dev.th7bo.modupdater.update.Installed;
import dev.th7bo.modupdater.update.ReleaseSource;
import dev.th7bo.modupdater.update.SelfUpdate;

/**
 * {@code modupdater update} — updates this program, not your mods.
 *
 * <p>Deliberately a command you type rather than a check folded into the
 * pre-launch hook. That hook runs with the launcher blocked on it and the game
 * waiting; spending a network round trip there to see whether the updater itself
 * is current would make every launch slower to serve a question nobody asked.
 *
 * <p>Prints to stdout rather than through the log, like the other commands
 * somebody runs from a terminal.
 */
final class UpdateCommand {

    private UpdateCommand() {
    }

    static int run() {
        SelfUpdate.Result result =
                new SelfUpdate(ReleaseSource.github(), Downloader.http()).run();

        switch (result) {
            case SelfUpdate.Result.UpToDate current ->
                    System.out.println("Already on the latest release (" + current.version() + ").");

            case SelfUpdate.Result.Updated updated -> {
                System.out.println("Updated " + updated.from() + " to " + updated.to() + ".");
                if (!updated.refreshed().isEmpty()) {
                    System.out.println("Refreshed " + String.join(", ", updated.refreshed()) + ".");
                }
                System.out.println();
                System.out.println("Your instances, hooks and settings are untouched — this replaced");
                System.out.println("the updater itself, at " + updated.jar() + ".");
            }

            case SelfUpdate.Result.NotInstalled ignored -> {
                System.out.println("This is not an installed copy — there is nothing here to replace.");
                System.out.println("Run the installer instead:");
                System.out.println("  https://github.com/" + ReleaseSource.REPOSITORY + "#install");
            }

            case SelfUpdate.Result.ReadOnly readOnly -> {
                System.out.println("Cannot write to " + readOnly.jar() + ".");
                System.out.println("Re-run the installer, or update that copy as whoever owns it.");
            }

            case SelfUpdate.Result.Failed failed -> {
                System.out.println("Could not update: " + failed.detail());
                System.out.println("Nothing was changed. The installer always works:");
                System.out.println("  https://github.com/" + ReleaseSource.REPOSITORY + "#install");
            }
        }

        // Always 0. This is not a launch hook, but the exit-code contract is the
        // program's, and a failed self-update is not a reason to look broken.
        return 0;
    }

    /** {@code modupdater version} — what this build is, for a bug report. */
    static int version() {
        Installed installed = Installed.locate();
        System.out.println("modupdater " + installed.version());
        if (installed.fromJar()) {
            System.out.println(installed.jar());
        }
        return 0;
    }
}
