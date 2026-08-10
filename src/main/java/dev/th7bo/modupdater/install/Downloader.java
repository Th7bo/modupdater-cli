package dev.th7bo.modupdater.install;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

/** Fetches an artifact to a local path. Separated so the installer is testable without a network. */
@FunctionalInterface
public interface Downloader {

    void download(String url, String token, Path destination) throws IOException;

    static Downloader http() {
        HttpClient client = HttpClient.newBuilder()
                // See ManifestClient: the h2c upgrade breaks against some servers.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        return (url, token, destination) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + (token == null ? "" : token))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            try {
                HttpResponse<Path> response =
                        client.send(request, HttpResponse.BodyHandlers.ofFile(destination));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("download interrupted", e);
            }
        };
    }
}
