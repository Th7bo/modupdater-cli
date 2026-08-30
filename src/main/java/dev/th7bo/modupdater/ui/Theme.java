package dev.th7bo.modupdater.ui;

import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;

/** Look and feel for the update dialog. */
public final class Theme {

    public static final Color BACKGROUND = new Color(0x1E1F22);
    public static final Color SURFACE = new Color(0x2B2D30);
    public static final Color ROW_ALT = new Color(0x26282C);
    public static final Color BORDER = new Color(0x3A3D41);
    public static final Color TEXT = new Color(0xE6E6E6);
    public static final Color TEXT_MUTED = new Color(0x9DA0A8);
    public static final Color ACCENT = new Color(0x4C8DF6);
    public static final Color SELECTION = new Color(0x2F4C7A);

    /** Preferred UI faces, most preferred first; the first installed one wins. */
    private static final List<String> UI_FONTS =
            List.of("Inter", "Noto Sans", "DejaVu Sans", "Liberation Sans", "SansSerif");

    private static boolean applied;

    private Theme() {
    }

    public static synchronized void apply() {
        if (applied) {
            return;
        }
        applied = true;

        // Deliberately not switching look and feel. Nimbus ignores per-component
        // colours unless every painter is overridden, which left a light grey
        // header and silver buttons on a dark window. The default one honours
        // setBackground/setForeground, so every surface here is painted
        // explicitly and the result looks the same on all three platforms.
        Font base = new Font(pickFont(), Font.PLAIN, 13);
        applyFont(base);

        UIManager.put("Table.background", SURFACE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Table.selectionBackground", SELECTION);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("ScrollPane.background", SURFACE);
        UIManager.put("Viewport.background", SURFACE);
        UIManager.put("ScrollBar.thumb", new Color(0x4A4D52));
        UIManager.put("ScrollBar.track", SURFACE);

        // A combo box paints its closed state through its own UI delegate, which
        // ignores setBackground — without these it stays a pale platform-blue bar
        // hanging off a dark window.
        UIManager.put("ComboBox.background", SURFACE);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", SELECTION);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ComboBox.buttonBackground", SURFACE);
        UIManager.put("ComboBox.buttonShadow", BORDER);
        UIManager.put("ComboBox.buttonDarkShadow", BORDER);
        UIManager.put("ComboBox.buttonHighlight", BORDER);
        UIManager.put("ComboBox.disabledBackground", SURFACE);
        UIManager.put("ComboBox.disabledForeground", TEXT_MUTED);
        UIManager.put("List.background", SURFACE);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("TextField.background", SURFACE);
        UIManager.put("TextField.foreground", TEXT);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("OptionPane.background", BACKGROUND);
        UIManager.put("OptionPane.messageForeground", TEXT);
    }

    /** Flat, readable buttons — the default look is heavily bevelled. */
    public static void styleButton(javax.swing.JButton button, boolean primary) {
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(true);
        button.setOpaque(true);
        button.setBackground(primary ? ACCENT : new Color(0x3A3D41));
        button.setForeground(primary ? Color.WHITE : TEXT);
        button.setBorder(javax.swing.BorderFactory.createEmptyBorder(9, 16, 9, 16));
        button.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        if (primary) {
            button.setFont(button.getFont().deriveFont(Font.BOLD));
        }
    }

    /**
     * A combo box that honours the dark palette.
     *
     * <p>Setting background and foreground is not enough on its own: the default
     * renderer paints the popup's rows with the look and feel's own colours, which
     * is what leaves a pale blue list hanging off a dark window.
     */
    public static void styleComboBox(javax.swing.JComboBox<?> combo) {
        // The platform delegate paints the closed state itself and ignores
        // setBackground, which leaves a pale blue bar on a dark window. The basic
        // one honours both the colours and the renderer below.
        combo.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override
            protected javax.swing.JButton createArrowButton() {
                javax.swing.JButton button = super.createArrowButton();
                button.setBackground(SURFACE);
                button.setBorder(javax.swing.BorderFactory.createEmptyBorder());
                button.setFocusable(false);
                return button;
            }
        });

        combo.setBackground(SURFACE);
        combo.setForeground(TEXT);
        combo.setBorder(javax.swing.BorderFactory.createLineBorder(BORDER));

        javax.swing.ListCellRenderer<Object> renderer = (list, value, index, selected, focused) -> {
            javax.swing.JLabel label = new javax.swing.JLabel(value == null ? "" : value.toString());
            label.setOpaque(true);
            label.setBackground(selected ? SELECTION : SURFACE);
            label.setForeground(TEXT);
            label.setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 8, 5, 8));
            return label;
        };

        @SuppressWarnings("unchecked")
        javax.swing.JComboBox<Object> untyped = (javax.swing.JComboBox<Object>) combo;
        untyped.setRenderer(renderer);
    }

    private static String pickFont() {
        Set<String> installed;
        try {
            installed = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames());
        } catch (RuntimeException e) {
            return Font.SANS_SERIF;
        }

        for (String candidate : UI_FONTS) {
            if (installed.contains(candidate)) {
                return candidate;
            }
        }
        return Font.SANS_SERIF;
    }

    private static void applyFont(Font font) {
        FontUIResource resource = new FontUIResource(font);
        Enumeration<Object> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource) {
                UIManager.put(key, resource);
            }
        }
    }
}
