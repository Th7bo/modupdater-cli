package dev.th7bo.modupdater.ui;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Giving up on a prompt nobody is answering — and not while they are answering it. */
class AnswerTimeoutTest {

    private static void waitFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void givesUpWhenNobodyAnswers() throws InterruptedException {
        CountDownLatch fired = new CountDownLatch(1);

        new AnswerTimeout(50, fired::countDown).start();

        assertTrue(fired.await(5, TimeUnit.SECONDS));
    }

    /**
     * The editor opens over the prompt and is modal, and a Swing timer keeps
     * firing under a modal window — which disposed the prompt mid-edit and
     * launched the game while the user was still sorting their mods.
     */
    @Test
    void doesNotGiveUpWhileTheEditorIsOpen() {
        AtomicInteger fired = new AtomicInteger();
        AnswerTimeout timeout = new AnswerTimeout(50, fired::incrementAndGet);

        timeout.start();
        timeout.heldFor(() -> waitFor(400));

        assertEquals(0, fired.get());
        timeout.stop();
    }

    @Test
    void startsCountingAgainOnceTheEditorCloses() {
        AtomicInteger fired = new AtomicInteger();
        AnswerTimeout timeout = new AnswerTimeout(200, fired::incrementAndGet);

        timeout.start();
        timeout.heldFor(() -> waitFor(50));
        waitFor(1_000);

        assertEquals(1, fired.get());
    }

    /** A clock that was never running must not be started by holding it. */
    @Test
    void leavesAStoppedClockStopped() {
        AtomicInteger fired = new AtomicInteger();
        AnswerTimeout timeout = new AnswerTimeout(50, fired::incrementAndGet);

        timeout.heldFor(() -> waitFor(10));
        waitFor(300);

        assertEquals(0, fired.get());
    }
}
