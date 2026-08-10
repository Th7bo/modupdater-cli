package dev.th7bo.modupdater.manifest;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ManifestClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http;
    private final Gson gson = new Gson();

    public ManifestClient() {
        // HTTP/1.1 explicitly: the default HTTP/2 client attempts an h2c upgrade
        // over cleartext, which some servers (including the Next.js dev server)
        // answer by closing the connection with no response at all.
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    public ManifestClient(HttpClient http) {
        this.http = http;
    }

    /**
     * @param mcVersion optional; when present the server filters to artifacts
     *                  declaring that exact Minecraft version
     */
    public FetchResult fetch(String baseUrl, String token, String mcVersion) {
        URI uri;
        try {
            uri = buildUri(baseUrl, mcVersion);
        } catch (IllegalArgumentException e) {
            return new FetchResult.Unreachable("bad base URL: " + e.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + (token == null ? "" : token))
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        // No retry loop here on purpose: HttpClient already retries connection
        // failures for idempotent requests, and layering a second attempt on top
        // doubles how long a pre-launch hook blocks the game before giving up.
        return send(request);
    }

    private FetchResult send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return switch (response.statusCode()) {
                case 200 -> parse(response.body());
                case 401, 403 -> new FetchResult.Unauthorized();
                case 503 -> new FetchResult.Unavailable();
                default -> new FetchResult.Unreachable("HTTP " + response.statusCode());
            };
        } catch (IOException e) {
            return new FetchResult.Unreachable(String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new FetchResult.Unreachable("interrupted");
        }
    }

    private FetchResult parse(String body) {
        try {
            Manifest manifest = gson.fromJson(body, Manifest.class);
            if (manifest == null) {
                return new FetchResult.Malformed("empty body");
            }
            return new FetchResult.Ok(manifest);
        } catch (JsonParseException e) {
            return new FetchResult.Malformed(String.valueOf(e.getMessage()));
        }
    }

    private static URI buildUri(String baseUrl, String mcVersion) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("no base URL configured");
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String url = trimmed + "/api/manifest";
        if (mcVersion != null && !mcVersion.isBlank()) {
            url += "?mc=" + URLEncoder.encode(mcVersion, StandardCharsets.UTF_8);
        }
        return URI.create(url);
    }
}
