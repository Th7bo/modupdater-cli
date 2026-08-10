package dev.th7bo.modupdater;

import dev.th7bo.modupdater.util.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exit-code contract (Phase 6 Task 7). A non-zero exit from a pre-launch
 * hook blocks the launch, so every one of these must be 0.
 */
class MainTest {

    private HttpServer server;

    @AfterEach
    void cleanup() {
        if (server != null) {
            server.stop(0);
        }
        Log.reset();
    }

    private String serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/manifest", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Path modsWithToken(Path dir) throws IOException {
        Path mods = dir.resolve("mods");
        Files.createDirectories(mods.resolve(".modupdater"));
        Files.writeString(mods.resolve(".modupdater").resolve("token"), "a-token");
        return mods;
    }

    private static String[] args(String command, Path mods, String baseUrl, String mc) {
        return new String[]{
                command,
                "--mods-dir", mods.toString(),
                "--base-url", baseUrl,
                "--mc", mc
        };
    }

    @Test
    void exitsZeroWhenNothingIsConfigured(@TempDir Path dir) {
        assertEquals(0, Main.run(new String[]{"check", "--mods-dir", dir.resolve("mods").toString()}));
    }

    @Test
    void exitsZeroWhenNoTokenIsPresent(@TempDir Path dir) throws IOException {
        Path mods = Files.createDirectories(dir.resolve("mods"));

        assertEquals(0, Main.run(args("check", mods, "http://127.0.0.1:1", "1.21.4")));
    }

    @Test
    void exitsZeroWhenTheServerIsUnreachable(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);

        assertEquals(0, Main.run(args("check", mods, "http://127.0.0.1:1", "1.21.4")));
    }

    @Test
    void exitsZeroWhenTheTokenIsRejected(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);
        String base = serve(401, "{\"error\":\"Unauthorized\"}");

        assertEquals(0, Main.run(args("check", mods, base, "1.21.4")));
    }

    @Test
    void exitsZeroWhenTheEndpointIsUnconfigured(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);
        String base = serve(503, "{\"error\":\"not configured\"}");

        assertEquals(0, Main.run(args("check", mods, base, "1.21.4")));
    }

    @Test
    void exitsZeroOnAMalformedManifest(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);
        String base = serve(200, "{ not json at all");

        assertEquals(0, Main.run(args("check", mods, base, "1.21.4")));
    }

    @Test
    void exitsZeroWhenThereIsNothingToUpdate(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);
        Jars.modJar(mods, "examplemod.jar", "examplemod", "1.0.0");
        String base = serve(200, "{\"generatedAt\":\"now\",\"mods\":[]}");

        assertEquals(0, Main.run(args("check", mods, base, "1.21.4")));
    }

    @Test
    void exitsZeroWhenNoMinecraftVersionIsConfigured(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);
        String base = serve(200, "{\"generatedAt\":\"now\",\"mods\":[]}");

        assertEquals(0, Main.run(new String[]{
                "check", "--mods-dir", mods.toString(), "--base-url", base}));
    }

    @Test
    void exitsZeroForAnUnknownCommand(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);

        assertEquals(0, Main.run(new String[]{"frobnicate", "--mods-dir", mods.toString()}));
    }

    @Test
    void applyExitsZeroWithNothingPending(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);

        assertEquals(0, Main.run(new String[]{"apply", "--mods-dir", mods.toString()}));
    }

    @Test
    void neverWritesTheTokenToTheLog(@TempDir Path dir) throws IOException {
        Path mods = modsWithToken(dir);
        String base = serve(401, "{\"error\":\"Unauthorized\"}");

        Main.run(args("check", mods, base, "1.21.4"));

        Path log = mods.resolve(".modupdater").resolve("log.txt");
        assertTrue(Files.isRegularFile(log), "a log file should have been written");
        assertTrue(Files.readString(log).contains("401"), "the log should record what happened");
        assertEquals(-1, Files.readString(log).indexOf("a-token"), "the token must never appear in the log");
    }
}
