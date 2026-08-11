package dev.th7bo.modupdater.ui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.th7bo.modupdater.util.Log;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Decides which monitor the update dialog should open on.
 *
 * <p>Harder than it sounds on Wayland. {@link MouseInfo#getPointerInfo()} returns
 * null there: the compositor will not tell a client where the pointer is unless
 * it is over one of that client's own surfaces, and a dialog that has not been
 * shown yet has none. AWT then falls back to the "default" screen device, which
 * is whichever the display server lists first — and on a tiling window manager
 * that also decides the workspace, so the prompt opens somewhere the user is not
 * and the launch looks frozen.
 *
 * <p>So the compositor is asked directly when it is one that answers. Monitors
 * are matched to AWT screens by resolution rather than position, because the two
 * do not share a coordinate space: a rotated monitor Hyprland reports as
 * 3840x2160 appears to AWT as 2160x3840.
 */
final class ActiveScreen {

    private ActiveScreen() {
    }

    /** @return the screen to centre on, never null */
    static Rectangle bounds() {
        Rectangle configured = fromProperty();
        if (configured != null) {
            return configured;
        }

        Rectangle fromCompositor = fromHyprland();
        if (fromCompositor != null) {
            return fromCompositor;
        }

        Rectangle fromPointer = fromPointer();
        if (fromPointer != null) {
            return fromPointer;
        }

        return GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration()
                .getBounds();
    }

    /** An explicit escape hatch: `-Dmodupdater.screen=1` picks the second screen. */
    private static Rectangle fromProperty() {
        Integer index = Integer.getInteger("modupdater.screen");
        if (index == null) {
            return null;
        }

        GraphicsDevice[] screens = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (index < 0 || index >= screens.length) {
            Log.warn("modupdater.screen=" + index + " but there are " + screens.length + " screens");
            return null;
        }
        return screens[index].getDefaultConfiguration().getBounds();
    }

    private static Rectangle fromPointer() {
        try {
            PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null || pointer.getDevice() == null) {
                return null;
            }
            return pointer.getDevice().getDefaultConfiguration().getBounds();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Rectangle fromHyprland() {
        if (System.getenv("HYPRLAND_INSTANCE_SIGNATURE") == null) {
            return null;
        }

        int[] size = focusedMonitorSize();
        if (size == null) {
            return null;
        }

        for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle bounds = screen.getDefaultConfiguration().getBounds();

            // Either orientation: a rotated monitor is reported one way by the
            // compositor and the other by AWT.
            boolean matches = (bounds.width == size[0] && bounds.height == size[1])
                    || (bounds.width == size[1] && bounds.height == size[0]);

            if (matches) {
                return bounds;
            }
        }

        return null;
    }

    /** @return {width, height} of the focused monitor, or null */
    private static int[] focusedMonitorSize() {
        try {
            Process process = new ProcessBuilder("hyprctl", "monitors", "-j")
                    .redirectErrorStream(false)
                    .start();

            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return null;
            }

            JsonArray monitors = new Gson().fromJson(output, JsonArray.class);
            if (monitors == null) {
                return null;
            }

            for (JsonElement element : monitors) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject monitor = element.getAsJsonObject();
                JsonElement focused = monitor.get("focused");

                if (focused != null && focused.isJsonPrimitive() && focused.getAsBoolean()) {
                    return new int[]{monitor.get("width").getAsInt(), monitor.get("height").getAsInt()};
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // No hyprctl, unreadable output, anything at all — fall through to the
            // other strategies rather than failing to show a dialog.
        }

        return null;
    }
}
