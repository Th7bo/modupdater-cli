package dev.th7bo.modupdater.ui;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The picture a mod ships inside its own JAR.
 *
 * <p>{@code fabric.mod.json} names it under {@code icon} — either a path, or an
 * object keyed by pixel size. Both forms appear in the wild, so both are read.
 *
 * <p>A mod with no icon, an unreadable one, or a path pointing at nothing gets
 * {@link #blank()} rather than nothing at all: a missing picture should leave a
 * gap the same width as the others, not shunt one row's text out of line.
 */
final class ModIcon {

    /** Sized to sit inside the 30px table row with a little air around it. */
    static final int SIZE = 20;

    private static final String MOD_JSON = "fabric.mod.json";

    /** Keyed by JAR path and last-modified time, so an update reads a fresh one. */
    private static final Map<String, Icon> CACHE = new HashMap<>();

    private static Icon blank;

    private ModIcon() {
    }

    /** @return the mod's icon, or a blank one of the same size */
    static synchronized Icon of(Path jar) {
        if (jar == null) {
            return blank();
        }

        String key = jar.toAbsolutePath() + "@" + lastModified(jar);
        Icon cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Icon icon = read(jar);
        CACHE.put(key, icon);
        return icon;
    }

    /** A transparent square, so rows without an icon still line up. */
    static synchronized Icon blank() {
        if (blank == null) {
            blank = new ImageIcon(new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB));
        }
        return blank;
    }

    private static Icon read(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            String path = iconPath(zip);
            if (path == null) {
                return blank();
            }

            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                return blank();
            }

            try (InputStream in = zip.getInputStream(entry)) {
                BufferedImage image = ImageIO.read(in);
                return image == null ? blank() : scaled(image);
            }
        } catch (IOException | RuntimeException e) {
            // A mod's own artwork is never worth failing a launch over, and a
            // corrupt PNG can throw from inside the reader in several ways —
            // JsonParseException among them, which is a RuntimeException.
            return blank();
        }
    }

    private static String iconPath(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(MOD_JSON);
        if (entry == null) {
            return null;
        }

        try (InputStream in = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {

            JsonObject json = new Gson().fromJson(reader, JsonObject.class);
            if (json == null) {
                return null;
            }

            JsonElement icon = json.get("icon");
            if (icon == null) {
                return null;
            }

            if (icon.isJsonPrimitive() && icon.getAsJsonPrimitive().isString()) {
                return blankToNull(icon.getAsString());
            }

            // The sized form: {"32": "...", "128": "..."}. Take the largest, since
            // it is about to be scaled down and a bigger source looks better.
            if (icon.isJsonObject()) {
                return largest(icon.getAsJsonObject());
            }

            return null;
        }
    }

    private static String largest(JsonObject sizes) {
        String best = null;
        int bestSize = -1;

        for (String key : sizes.keySet()) {
            JsonElement value = sizes.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                continue;
            }

            int size;
            try {
                size = Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                size = 0;
            }

            if (size > bestSize) {
                bestSize = size;
                best = blankToNull(value.getAsString());
            }
        }

        return best;
    }

    private static Icon scaled(BufferedImage image) {
        Image resized = image.getScaledInstance(SIZE, SIZE, Image.SCALE_SMOOTH);
        return new ImageIcon(resized);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long lastModified(Path jar) {
        try {
            return java.nio.file.Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
