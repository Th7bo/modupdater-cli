package dev.th7bo.modupdater.ui;

import javax.swing.Timer;

/**
 * The clock that gives up on a prompt nobody is answering.
 *
 * <p>Its own class because it has to be stoppable from inside the prompt. The
 * profile editor opens over the pre-launch prompt, and a Swing timer keeps
 * firing while a modal window is up — so somebody sorting thirty mods into
 * groups had the prompt underneath them disposed mid-edit and the game launched
 * without them. Time spent in the editor is not time spent ignoring the prompt.
 *
 * <p>Resuming restarts the full delay rather than continuing the old one. The
 * question is whether the user is still there, and having just closed the editor
 * they plainly are.
 */
final class AnswerTimeout {

    private final Timer timer;

    AnswerTimeout(int delayMs, Runnable onExpiry) {
        this.timer = new Timer(delayMs, event -> onExpiry.run());
        this.timer.setRepeats(false);
    }

    void start() {
        timer.start();
    }

    void stop() {
        timer.stop();
    }

    /** Runs {@code work} with the clock stopped, and restarts it afterwards. */
    void heldFor(Runnable work) {
        boolean wasRunning = timer.isRunning();
        timer.stop();
        try {
            work.run();
        } finally {
            if (wasRunning) {
                timer.restart();
            }
        }
    }
}
