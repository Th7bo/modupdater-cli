package dev.th7bo.modupdater;

import dev.th7bo.modupdater.setup.Instance;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The menu shown when nothing has said which instance to act on. */
class InstancePromptTest {

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private static Instance instance(String launcher, String name, String dir) {
        return new Instance(launcher, name, "1.21.4", Path.of(dir), null);
    }

    private InstancePrompt.Result choose(List<Instance> instances, String typed) {
        PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);
        return InstancePrompt.choose(instances, new BufferedReader(new StringReader(typed)), out);
    }

    private String printed() {
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void picksTheNumberedInstance() {
        var chosen = choose(List.of(
                instance("Prism", "SkyBlock", "/games/skyblock"),
                instance("Prism", "Vanilla", "/games/vanilla")), "2\n");

        assertEquals(Path.of("/games/vanilla", "mods"),
                assertInstanceOf(InstancePrompt.Result.Chosen.class, chosen).instance().modsDir());
    }

    /** One instance is not a question worth asking — but it is worth saying which. */
    @Test
    void takesTheOnlyInstanceWithoutAsking() {
        var chosen = choose(List.of(instance("Prism", "SkyBlock", "/games/skyblock")), "");

        assertEquals(Path.of("/games/skyblock", "mods"),
                assertInstanceOf(InstancePrompt.Result.Chosen.class, chosen).instance().modsDir());
        assertTrue(printed().contains("SkyBlock"), printed());
    }

    /** The folder is the only thing telling two instances of the same name apart. */
    @Test
    void showsTheFolderWhenNamesCollide() {
        choose(List.of(
                instance("Prism", "SkyBlock", "/games/one"),
                instance("Modrinth", "SkyBlock", "/games/two")), "1\n");

        assertTrue(printed().contains("/games/one"), printed());
        assertTrue(printed().contains("/games/two"), printed());
    }

    @Test
    void refusesAnAnswerOutsideTheList() {
        assertInstanceOf(InstancePrompt.Result.None.class, choose(List.of(
                instance("Prism", "SkyBlock", "/games/skyblock"),
                instance("Prism", "Vanilla", "/games/vanilla")), "7\n"));
        assertTrue(printed().contains("not in the list"), printed());
    }

    @Test
    void refusesAnAnswerThatIsNotANumber() {
        assertInstanceOf(InstancePrompt.Result.None.class, choose(List.of(
                instance("Prism", "SkyBlock", "/games/skyblock"),
                instance("Prism", "Vanilla", "/games/vanilla")), "skyblock\n"));
        assertTrue(printed().contains("not a number"), printed());
    }

    /**
     * Nothing on stdin means something piped this in, and no prompt can ever be
     * answered — so it must say how to skip the question rather than hang or
     * guess an instance.
     */
    @Test
    void explainsItselfWithNothingToReadFrom() {
        assertInstanceOf(InstancePrompt.Result.None.class, choose(List.of(
                instance("Prism", "SkyBlock", "/games/skyblock"),
                instance("Prism", "Vanilla", "/games/vanilla")), ""));
        assertTrue(printed().contains("--mods-dir"), printed());
    }

    @Test
    void saysHowToNameAnInstanceWhenNoneWereFound() {
        assertInstanceOf(InstancePrompt.Result.None.class, choose(List.of(), ""));
        assertTrue(printed().contains("--mods-dir"), printed());
    }
}
