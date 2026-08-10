package dev.th7bo.modupdater.manifest;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestClientTest {

    private static final String MANIFEST = """
            {
              "generatedAt": "2026-08-10T12:00:00.000Z",
              "mods": [
                {
                  "modId": "examplemod",
                  "displayName": "Example Mod",
                  "repoId": "repo-1",
                  "repoName": "example-mod",
                  "versions": [
                    {
                      "modVersion": "1.2.3",
                      "loader": "fabric",
                      "mcVersions": ["1.21.4"],
                      "mcVersionsRaw": ">=1.21.4",
                      "filename": "examplemod-1.2.3.jar",
                      "sha256": "aaaa",
                      "size": 1024,
                      "downloadUrl": "http://example.test/a.jar",
                      "buildId": "build-1",
                      "builtAt": "2026-08-10T11:00:00.000Z",
                      "commitHash": "abc1234",
                      "commitSummary": "Fix the thing"
                    },
                    {
                      "modVersion": "1.2.3",
                      "loader": "fabric",
                      "mcVersions": ["1.21.5"],
                      "filename": "examplemod-1.2.3+1.21.5.jar",
                      "sha256": "bbbb",
                      "size": 2048,
                      "downloadUrl": "http://example.test/b.jar",
                      "buildId": "build-1",
                      "builtAt": "2026-08-10T11:00:00.000Z"
                    }
                  ]
                }
              ]
            }
            """;

    private HttpServer server;
    private final List<String> authHeaders = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();

    private String start(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/manifest", exchange -> {
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            queries.add(String.valueOf(exchange.getRequestURI().getQuery()));

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesAValidManifest() throws IOException {
        String base = start(200, MANIFEST);

        FetchResult result = new ManifestClient().fetch(base, "a-token", null);

        Manifest manifest = assertInstanceOf(FetchResult.Ok.class, result).manifest();
        assertEquals(1, manifest.mods().size());

        Manifest.Mod mod = manifest.mods().get(0);
        assertEquals("examplemod", mod.modId());
        assertEquals("Example Mod", mod.label());
        assertEquals(2, mod.versions().size(), "several MC versions per mod must survive parsing");
        assertEquals(1024, mod.versions().get(0).size());
        assertEquals("Fix the thing", mod.versions().get(0).commitSummary());
    }

    @Test
    void sendsTheBearerToken() throws IOException {
        String base = start(200, MANIFEST);

        new ManifestClient().fetch(base, "a-token", null);

        assertEquals("Bearer a-token", authHeaders.get(0));
    }

    @Test
    void includesTheMcFilterWhenGiven() throws IOException {
        String base = start(200, MANIFEST);

        new ManifestClient().fetch(base, "a-token", "1.21.4");

        assertEquals("mc=1.21.4", queries.get(0));
    }

    @Test
    void omitsTheMcFilterWhenAbsent() throws IOException {
        String base = start(200, MANIFEST);

        new ManifestClient().fetch(base, "a-token", null);

        assertEquals("null", queries.get(0));
    }

    @Test
    void reportsUnauthorizedSeparately() throws IOException {
        String base = start(401, "{\"error\":\"Unauthorized\"}");

        assertInstanceOf(FetchResult.Unauthorized.class, new ManifestClient().fetch(base, "bad", null));
    }

    @Test
    void reportsAnUnconfiguredEndpointSeparately() throws IOException {
        String base = start(503, "{\"error\":\"not configured\"}");

        assertInstanceOf(FetchResult.Unavailable.class, new ManifestClient().fetch(base, "t", null));
    }

    @Test
    void reportsMalformedJson() throws IOException {
        String base = start(200, "{ this is not json");

        assertInstanceOf(FetchResult.Malformed.class, new ManifestClient().fetch(base, "t", null));
    }

    @Test
    void reportsUnexpectedStatusAsUnreachable() throws IOException {
        String base = start(500, "boom");

        FetchResult result = new ManifestClient().fetch(base, "t", null);

        assertEquals("HTTP 500", assertInstanceOf(FetchResult.Unreachable.class, result).detail());
    }

    @Test
    void reportsAClosedPortAsUnreachable() {
        FetchResult result = new ManifestClient().fetch("http://127.0.0.1:1", "t", null);

        assertInstanceOf(FetchResult.Unreachable.class, result);
    }

    @Test
    void survivesAConnectionResetWithoutGivingUpImmediately() throws Exception {
        AtomicInteger accepted = new AtomicInteger();

        // A real connection reset, not an empty HTTP response: setSoLinger(true, 0)
        // makes close() send RST. HttpClient retries idempotent requests itself,
        // so more than one connection is expected — the exact count is a JDK
        // implementation detail and deliberately not asserted.
        try (ServerSocket listener = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                while (!listener.isClosed()) {
                    try (Socket socket = listener.accept()) {
                        accepted.incrementAndGet();
                        socket.setSoLinger(true, 0);
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            server.setDaemon(true);
            server.start();

            FetchResult result = new ManifestClient()
                    .fetch("http://127.0.0.1:" + listener.getLocalPort(), "t", null);

            assertInstanceOf(FetchResult.Unreachable.class, result);
            assertTrue(accepted.get() >= 2, "a reset connection should be retried, not abandoned on first failure");
        }
    }

    @Test
    void rejectsABlankBaseUrl() {
        FetchResult result = new ManifestClient().fetch("", "t", null);

        assertTrue(assertInstanceOf(FetchResult.Unreachable.class, result).detail().contains("base URL"));
    }
}
