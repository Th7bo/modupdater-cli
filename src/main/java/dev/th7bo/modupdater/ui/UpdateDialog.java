package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.diff.UpdateCandidate;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
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
        JDialog dialog = new JDialog((java.awt.Frame) null, "Mod updates available", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        ModTableModel tableModel = new ModTableModel(model);
        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(34);

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        header.add(new JLabel(model.rows().size() + " mod(s) have a newer build available."), BorderLayout.WEST);

        JButton selectAll = new JButton("Select all");
        JButton selectNone = new JButton("Select none");
        JButton update = new JButton("Update and launch");
        JButton skip = new JButton("Launch without updating");
        JButton abort = new JButton("Cancel launch");

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
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        dialog.setLayout(new BorderLayout());
        dialog.add(header, BorderLayout.NORTH);
        dialog.add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.setPreferredSize(new Dimension(940, 380));
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);

        return choice.get();
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
