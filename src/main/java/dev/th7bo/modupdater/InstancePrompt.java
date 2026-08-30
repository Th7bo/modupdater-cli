package dev.th7bo.modupdater;

import dev.th7bo.modupdater.setup.Instance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/**
 * The instance menu behind {@code modupdater profile ...}.
 *
 * <p>The launcher hooks always say which instance they mean, and so does anyone
 * standing in their instance folder. Since the installer puts {@code modupdater}
 * on PATH, everyone else runs it from wherever they happen to be — and asking
 * them to find a Prism instance folder is the exact thing the installer's own
 * menu exists to avoid, so the same menu is offered here.
 *
 * <p>Reader and stream are arguments so the menu can be tested without a
 * terminal.
 */
final class InstancePrompt {

    private InstancePrompt() {
    }

    sealed interface Result {

        record Chosen(Instance instance) implements Result {
        }

        /** Nothing to act on. The reason has already been printed. */
        record None() implements Result {
        }
    }

    static Result choose(List<Instance> instances, BufferedReader in, PrintStream out) {
        if (instances.isEmpty()) {
            out.println("No Minecraft instances found.");
            out.println();
            out.println("This looks for Prism Launcher, MultiMC and Modrinth App in their usual");
            out.println("places. If yours is somewhere else, name its mods folder:");
            out.println();
            out.println("    modupdater profile enable --mods-dir /path/to/instance/mods");
            return new Result.None();
        }

        // One instance is not a question worth asking, and this is the common
        // case: most people set up the one they play.
        if (instances.size() == 1) {
            Instance only = instances.get(0);
            out.println("Instance: " + describe(only));
            out.println();
            return new Result.Chosen(only);
        }

        out.println("Which instance?");
        out.println();
        for (int i = 0; i < instances.size(); i++) {
            Instance instance = instances.get(i);
            out.printf("  %2d) %s%n", i + 1, describe(instance));
            // Two instances can share a display name, and then the folder is the
            // only thing telling them apart.
            if (duplicated(instances, instance)) {
                out.printf("      %s%n", instance.gameDir());
            }
        }
        out.println();
        out.print("Number: ");
        out.flush();

        String answer = readLine(in);

        // No answer and no keyboard: something piped this in, and no prompt here
        // can ever be answered. Say how to skip the question instead of hanging.
        if (answer == null) {
            out.println();
            out.println("Could not read an answer. Name the instance instead:");
            out.println();
            out.println("    modupdater profile enable --mods-dir "
                    + instances.get(0).modsDir());
            return new Result.None();
        }

        answer = answer.trim();
        if (answer.isEmpty()) {
            out.println("Nothing selected.");
            return new Result.None();
        }

        int number;
        try {
            number = Integer.parseInt(answer);
        } catch (NumberFormatException e) {
            out.println("'" + answer + "' is not a number.");
            return new Result.None();
        }

        if (number < 1 || number > instances.size()) {
            out.println(number + " is not in the list.");
            return new Result.None();
        }

        out.println();
        return new Result.Chosen(instances.get(number - 1));
    }

    private static String describe(Instance instance) {
        return "[" + instance.launcher() + "] " + instance.name()
                + "  (MC " + instance.versionLabel() + ")";
    }

    private static boolean duplicated(List<Instance> instances, Instance instance) {
        return instances.stream().filter(other -> other.name().equals(instance.name())).count() > 1;
    }

    private static String readLine(BufferedReader in) {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
