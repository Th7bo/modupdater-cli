package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The Manage button in the pre-launch prompt, and what it does to the prompt's clock. */
class ProfilePickerTest {

    private static void waitFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static DialogViewModel model() {
        DialogViewModel model = new DialogViewModel(List.of());
        model.offerProfiles(
                List.of(ProfileOption.of("general", "Normal play"),
                        ProfileOption.of("mining", "Mining mods on")),
                "general",
                true);
        return model;
    }

    private static JButton manageButton(JComponent panel) {
        for (java.awt.Component child : panel.getComponents()) {
            if (child instanceof JButton button && button.getText().startsWith("Manage")) {
                return button;
            }
            if (child instanceof JComponent nested) {
                JButton found = manageButton(nested);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The editor is modal and the prompt's timer keeps firing under it, so a long
     * session in the editor used to dispose the prompt and launch the game while
     * the user was still sorting their mods.
     */
    @Test
    void theClockIsHeldWhileTheEditorIsOpen(@TempDir Path dir) {
        AtomicInteger gaveUp = new AtomicInteger();
        // Comfortably longer than doClick's own 68ms press, which would
        // otherwise expire the clock before the button's action ever runs.
        AnswerTimeout timeout = new AnswerTimeout(500, gaveUp::incrementAndGet);
        Config config = Config.resolve(new String[]{"--mods-dir", dir.resolve("mods").toString()});

        JButton manage = manageButton(
                ProfilePicker.build(model(), config, timeout, owner -> waitFor(1_200)));
        assertNotNull(manage);

        timeout.start();
        manage.doClick();

        assertEquals(0, gaveUp.get());
        timeout.stop();
    }

    /** Closing the editor puts the user back in front of the prompt, so it counts again. */
    @Test
    void theClockRunsAgainAfterTheEditorCloses(@TempDir Path dir) {
        AtomicInteger gaveUp = new AtomicInteger();
        AnswerTimeout timeout = new AnswerTimeout(300, gaveUp::incrementAndGet);
        Config config = Config.resolve(new String[]{"--mods-dir", dir.resolve("mods").toString()});

        JButton manage = manageButton(
                ProfilePicker.build(model(), config, timeout, owner -> waitFor(50)));

        timeout.start();
        manage.doClick();
        waitFor(1_500);

        assertEquals(1, gaveUp.get());
    }
}
