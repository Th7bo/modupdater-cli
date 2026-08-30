package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.diff.UpdateCandidate;
import dev.th7bo.modupdater.util.Log;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** The pre-launch update prompt. */
public final class UpdateDialog {

    /** What the user chose. Abort is the only outcome that stops the launch. */
    public sealed interface Choice {

        record Update(List<UpdateCandidate> chosen) implements Choice {
        }

        record Skip() implements Choice {
        }

        record Abort() implements Choice {
        }
    }

    /**
     * How long to wait for an answer before launching unchanged.
     *
     * <p>A tiling window manager can put this dialog on a workspace the user
     * isn't looking at, and the launcher blocks on the pre-launch hook until it
     * is answered — which is indistinguishable from a frozen launcher. Giving up
     * is always safe: it just means launching with the mods already installed.
     */
    private static final int ANSWER_TIMEOUT_MS =
            Integer.getInteger("modupdater.dialogTimeoutMs", 120_000);

    /** The mod name column, which also carries the mod's icon. */
    private static final int COLUMN_MOD = 1;

    /** The commit summary column, rendered muted as secondary information. */
    private static final int COLUMN_CHANGE = 7;

    private UpdateDialog() {
    }

    public static Choice show(DialogViewModel model) {
        AtomicReference<Choice> result = new AtomicReference<>(new Choice.Skip());

        try {
            SwingUtilities.invokeAndWait(() -> result.set(build(model)));
        } catch (Exception e) {
            // Never let a UI problem block the launch.
            return new Choice.Skip();
        }

        return result.get();
    }

    private static Choice build(DialogViewModel model) {
        Theme.apply();

        JDialog dialog = new JDialog((java.awt.Frame) null, "Mod updates available", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getContentPane().setBackground(Theme.BACKGROUND);

        ModTableModel tableModel = new ModTableModel(model);
        JTable table = buildTable(tableModel, model);

        int count = model.rows().size();
        JLabel title = new JLabel(count + (count == 1 ? " mod can be updated" : " mods can be updated"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
        title.setForeground(Theme.TEXT);

        JLabel subtitle = new JLabel("Choose which to install before the game starts.");
        subtitle.setFont(subtitle.getFont().deriveFont(12f));
        subtitle.setForeground(Theme.TEXT_MUTED);
        subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        heading.add(title);
        heading.add(subtitle);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(18, 20, 14, 20));
        header.add(heading, BorderLayout.WEST);

        // Same window, one extra control — rather than a second dialog the user
        // has to dismiss before the one they came for.
        if (model.profilesOffered()) {
            header.add(ProfilePicker.build(model), BorderLayout.EAST);
        }

        JButton selectAll = new JButton("Select all");
        JButton selectNone = new JButton("Select none");
        JButton update = new JButton("Update and launch");
        JButton skip = new JButton("Launch without updating");
        JButton abort = new JButton("Cancel launch");

        for (JButton button : List.of(selectAll, selectNone, skip, abort)) {
            Theme.styleButton(button, false);
        }
        Theme.styleButton(update, true);

        AtomicReference<Choice> choice = new AtomicReference<>(new Choice.Skip());

        selectAll.addActionListener(e -> {
            model.selectAll(true);
            tableModel.fireTableDataChanged();
            update.setEnabled(model.canUpdate());
        });
        selectNone.addActionListener(e -> {
            model.selectAll(false);
            tableModel.fireTableDataChanged();
            update.setEnabled(model.canUpdate());
        });
        update.addActionListener(e -> {
            choice.set(new Choice.Update(model.chosen()));
            dialog.dispose();
        });
        skip.addActionListener(e -> {
            choice.set(new Choice.Skip());
            dialog.dispose();
        });
        abort.addActionListener(e -> {
            choice.set(new Choice.Abort());
            dialog.dispose();
        });

        tableModel.addTableModelListener(e -> update.setEnabled(model.canUpdate()));

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(selectAll);
        left.add(Box.createHorizontalStrut(6));
        left.add(selectNone);

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(abort);
        right.add(Box.createHorizontalStrut(6));
        right.add(skip);
        right.add(Box.createHorizontalStrut(6));
        right.add(update);

        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBackground(Theme.BACKGROUND);
        buttons.setBorder(BorderFactory.createEmptyBorder(14, 20, 18, 20));
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.SURFACE);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Theme.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 20));
        body.add(scroll, BorderLayout.CENTER);

        dialog.setLayout(new BorderLayout());
        dialog.add(header, BorderLayout.NORTH);
        dialog.add(body, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(1000, 420));
        dialog.pack();
        placeOnActiveScreen(dialog);

        dialog.setAlwaysOnTop(true);

        // toFront and requestFocus do nothing before the window exists on
        // screen, and setVisible on a modal dialog does not return until it is
        // dismissed — so raise it just after it appears.
        Timer raise = new Timer(150, e -> {
            dialog.toFront();
            dialog.requestFocus();
        });
        raise.setRepeats(false);
        raise.start();

        Timer timeout = new Timer(ANSWER_TIMEOUT_MS, e -> {
            Log.warn("no answer after " + (ANSWER_TIMEOUT_MS / 1000)
                    + "s — launching without updating");
            dialog.dispose();
        });
        timeout.setRepeats(false);
        timeout.start();

        dialog.setVisible(true);
        timeout.stop();

        return choice.get();
    }

    private static JTable buildTable(ModTableModel tableModel, DialogViewModel model) {
        JTable table = new JTable(tableModel);
        table.setRowHeight(30);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(Theme.SURFACE);
        table.setForeground(Theme.TEXT);
        table.setSelectionBackground(Theme.SELECTION);
        table.setSelectionForeground(Theme.TEXT);
        table.setFillsViewportHeight(true);

        JTableHeader tableHeader = table.getTableHeader();
        tableHeader.setReorderingAllowed(false);
        tableHeader.setFont(tableHeader.getFont().deriveFont(Font.BOLD, 12f));
        tableHeader.setBackground(Theme.BACKGROUND);
        tableHeader.setForeground(Theme.TEXT_MUTED);
        tableHeader.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        // The header's own renderer ships platform colours, which is what made it
        // a light grey bar across a dark window.
        tableHeader.setDefaultRenderer((t, value, selected, focused, row, column) -> {
            JLabel label = new JLabel(value == null ? "" : value.toString());
            label.setOpaque(true);
            label.setBackground(Theme.BACKGROUND);
            label.setForeground(Theme.TEXT_MUTED);
            label.setFont(t.getTableHeader().getFont());
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                    BorderFactory.createEmptyBorder(6, 10, 6, 10)));
            return label;
        });

        // Zebra striping, so a long list of mods stays readable across columns.
        DefaultTableCellRenderer striped = new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable t, Object value, boolean selected, boolean focused, int row, int column) {
                java.awt.Component cell =
                        super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                if (!selected) {
                    cell.setBackground(row % 2 == 0 ? Theme.SURFACE : Theme.ROW_ALT);
                }
                cell.setForeground(column == COLUMN_CHANGE ? Theme.TEXT_MUTED : Theme.TEXT);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                return cell;
            }
        };

        for (int column = 1; column < tableModel.getColumnCount(); column++) {
            table.getColumnModel().getColumn(column).setCellRenderer(striped);
        }

        // The mod's own icon goes in the name cell rather than a column of its
        // own: the picture and the name are one thing to read, and it keeps the
        // column layout as it was.
        table.getColumnModel().getColumn(COLUMN_MOD).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(
                    JTable t, Object value, boolean selected, boolean focused, int row, int column) {
                java.awt.Component cell =
                        super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                if (!selected) {
                    cell.setBackground(row % 2 == 0 ? Theme.SURFACE : Theme.ROW_ALT);
                }
                cell.setForeground(Theme.TEXT);
                setIcon(model.rows().get(row).icon());
                setIconTextGap(8);
                setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                return cell;
            }
        });

        // The checkbox column needs the same striping, or it sits on the look
        // and feel's own background and reads as a separate panel.
        JCheckBox checkBox = new JCheckBox();
        checkBox.setHorizontalAlignment(JCheckBox.CENTER);
        checkBox.setOpaque(true);
        table.getColumnModel().getColumn(0).setCellRenderer((t, value, selected, focused, row, column) -> {
            checkBox.setSelected(Boolean.TRUE.equals(value));
            checkBox.setBackground(selected
                    ? Theme.SELECTION
                    : (row % 2 == 0 ? Theme.SURFACE : Theme.ROW_ALT));
            checkBox.setForeground(Theme.TEXT);
            return checkBox;
        });

        int[] widths = {36, 224, 150, 130, 190, 90, 90, 320};
        for (int column = 0; column < widths.length; column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
        }
        table.getColumnModel().getColumn(0).setMaxWidth(36);

        return table;
    }

    /** Centres the dialog on the screen the user is actually looking at. */
    private static void placeOnActiveScreen(JDialog dialog) {
        try {
            Rectangle screen = ActiveScreen.bounds();
            dialog.setLocation(
                    screen.x + (screen.width - dialog.getWidth()) / 2,
                    screen.y + (screen.height - dialog.getHeight()) / 2);
        } catch (RuntimeException e) {
            dialog.setLocationRelativeTo(null);
        }
    }

    private static final class ModTableModel extends AbstractTableModel {

        private static final String[] COLUMNS =
                {"", "Mod", "Source", "Installed", "Available", "MC", "Size", "Latest change"};

        private final DialogViewModel model;

        ModTableModel(DialogViewModel model) {
            this.model = model;
        }

        @Override
        public int getRowCount() {
            return model.rows().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            UpdateRow entry = model.rows().get(row);
            return switch (column) {
                case 0 -> model.isSelected(row);
                case 1 -> entry.name();
                case 2 -> entry.source();
                case 3 -> entry.installedVersion();
                case 4 -> entry.availableVersion();
                case 5 -> entry.mcVersion();
                case 6 -> entry.size();
                default -> entry.change();
            };
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && value instanceof Boolean selected) {
                model.setSelected(row, selected);
                fireTableCellUpdated(row, column);
            }
        }
    }
}
