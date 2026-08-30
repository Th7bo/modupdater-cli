package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.Config;
import dev.th7bo.modupdater.profile.ProfileConfig;
import dev.th7bo.modupdater.util.Log;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The profile picker on its own, for the launch where nothing needs updating.
 *
 * <p>Deliberately small. When there are updates the picker rides along in
 * {@link UpdateDialog} instead — one window per launch, never two.
 */
public final class ProfileDialog {

    /** What the user chose. Abort is the only outcome that stops the launch. */
    public sealed interface Choice {

        /** Launch with this profile. */
        record Launch(String profile, boolean remember) implements Choice {
        }

        record Abort() implements Choice {
        }
    }

    /** Same reasoning as the update dialog: a prompt nobody can see must not block a launch. */
    private static final int ANSWER_TIMEOUT_MS =
            Integer.getInteger("modupdater.dialogTimeoutMs", 120_000);

    private ProfileDialog() {
    }

    public static Choice show(DialogViewModel model) {
        return show(model, null);
    }

    /**
     * @param config the instance being launched, so the profile picker can offer
     *               its Manage button; null leaves that button off
     */
    public static Choice show(DialogViewModel model, Config config) {
        AtomicReference<Choice> result = new AtomicReference<>(
                new Choice.Launch(model.selectedProfile(), model.rememberProfile()));

        try {
            SwingUtilities.invokeAndWait(() -> result.set(build(model, config)));
        } catch (Exception e) {
            // A UI problem is never a reason to stop the game from starting; the
            // preselected profile is applied instead.
            return new Choice.Launch(model.selectedProfile(), model.rememberProfile());
        }

        return result.get();
    }

    private static Choice build(DialogViewModel model, Config config) {
        Theme.apply();

        JDialog dialog = new JDialog((java.awt.Frame) null, "Choose a mod profile", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getContentPane().setBackground(Theme.BACKGROUND);

        JLabel title = new JLabel("Which mods do you want this session?");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Everything is up to date.");
        subtitle.setFont(subtitle.getFont().deriveFont(12f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 12, 0));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel picker = ProfilePicker.build(model, config);
        picker.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel body = new JPanel();
        body.setBackground(Theme.BACKGROUND);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 8, 22));
        body.add(title);
        body.add(subtitle);
        body.add(picker);

        JButton launch = new JButton("Launch");
        JButton abort = new JButton("Cancel launch");
        Theme.styleButton(launch, true);
        Theme.styleButton(abort, false);

        AtomicReference<Choice> choice = new AtomicReference<>(
                new Choice.Launch(model.selectedProfile(), model.rememberProfile()));

        launch.addActionListener(event -> {
            choice.set(new Choice.Launch(model.selectedProfile(), model.rememberProfile()));
            dialog.dispose();
        });
        abort.addActionListener(event -> {
            choice.set(new Choice.Abort());
            dialog.dispose();
        });

        JPanel buttons = new JPanel();
        buttons.setBackground(Theme.BACKGROUND);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(14, 22, 18, 22));
        buttons.add(Box.createHorizontalGlue());
        buttons.add(abort);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(launch);

        dialog.setLayout(new BorderLayout());
        dialog.add(body, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(460, 250));
        dialog.pack();
        placeOnActiveScreen(dialog);
        dialog.setAlwaysOnTop(true);
        dialog.getRootPane().setDefaultButton(launch);

        Timer raise = new Timer(150, event -> {
            dialog.toFront();
            dialog.requestFocus();
        });
        raise.setRepeats(false);
        raise.start();

        Timer timeout = new Timer(ANSWER_TIMEOUT_MS, event -> {
            Log.warn("no answer after " + (ANSWER_TIMEOUT_MS / 1000)
                    + "s — launching with the profile already selected");
            dialog.dispose();
        });
        timeout.setRepeats(false);
        timeout.start();

        dialog.setVisible(true);
        timeout.stop();

        return choice.get();
    }

    /** Centres the dialog on the screen the user is actually looking at. */
    private static void placeOnActiveScreen(JDialog dialog) {
        try {
            Rectangle screen = ActiveScreen.bounds();
            dialog.setLocation(
                    screen.x + (screen.width - dialog.getWidth()) / 2,
                    screen.y + (screen.height - dialog.getHeight()) / 2);
        } catch (RuntimeException e) {
            dialog.setLocationRelativeTo(null);
        }
    }

    /** The picker's entries, from an instance's profile config. */
    public static List<ProfileOption> options(ProfileConfig config) {
        return config.names().stream()
                .map(name -> ProfileOption.of(
                        name, config.profile(name).map(profile -> profile.description()).orElse("")))
                .toList();
    }
}
