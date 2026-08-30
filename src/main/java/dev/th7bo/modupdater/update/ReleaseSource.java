package dev.th7bo.modupdater.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Where to find out what the latest release is.
 *
 * <p>An interface so the update can be tested without reaching GitHub, in the
 * same spirit as {@link dev.th7bo.modupdater.install.Downloader}.
 */
@FunctionalInterface
public interface ReleaseSource {

    /** @return the newest published release */
    Release latest() throws IOException;

    /** The repository this program is released from. */
    String REPOSITORY = "Th7bo/modupdater-cli";

    /**
     * GitHub's releases API.
     *
     * <p>Unauthenticated, which is rate limited per address — generous enough for
     * a command somebody types, and the alternative is asking users for a token
     * to check for updates.
     */
    static ReleaseSource github() {
        return github("https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
    }

    static ReleaseSource github(String endpoint) {
        HttpClient client = HttpClient.newBuilder()
                // See ManifestClient: the h2c upgrade breaks against some servers.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        return () -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "modupdater-cli")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while asking GitHub for the latest release");
            }

            if (response.statusCode() == 403 || response.statusCode() == 429) {
                throw new IOException("GitHub is rate limiting this address — try again later");
            }
            if (response.statusCode() != 200) {
                throw new IOException("GitHub answered HTTP " + response.statusCode());
            }

            return parse(response.body());
        };
    }

    /** Visible for testing: the shape this reads out of the API's answer. */
    static Release parse(String json) throws IOException {
        try {
            JsonObject object = new Gson().fromJson(json, JsonObject.class);
            if (object == null) {
                throw new IOException("GitHub returned nothing readable");
            }

            String tag = string(object, "tag_name");
            if (tag == null) {
                throw new IOException("that release has no tag");
            }

            String url = "https://github.com/" + REPOSITORY
                    + "/releases/download/" + tag + "/" + Release.ASSET;
            long size = 0L;

            // Prefer the asset the release actually carries, so a release built
            // without the installer zip is reported as such rather than 404ing
            // halfway through a download.
            var assets = object.getAsJsonArray("assets");
            boolean found = false;
            if (assets != null) {
                for (var element : assets) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject asset = element.getAsJsonObject();
                    if (Release.ASSET.equals(string(asset, "name"))) {
                        String direct = string(asset, "browser_download_url");
                        if (direct != null) {
                            url = direct;
                        }
                        size = asset.has("size") ? asset.get("size").getAsLong() : 0L;
                        found = true;
                        break;
                    }
                }
            }

            if (!found) {
                throw new IOException("release " + tag + " has no " + Release.ASSET);
            }

            return new Release(Version.of(tag), tag, url, size);
        } catch (JsonParseException | UnsupportedOperationException | IllegalStateException e) {
            throw new IOException("could not read GitHub's answer: " + e.getMessage());
        }
    }

    private static String string(JsonObject object, String field) {
        var element = object.get(field);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }
}
