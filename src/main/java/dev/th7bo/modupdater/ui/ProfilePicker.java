package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.Config;
import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.profile.ProfileConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;

/**
 * The profile dropdown, shared by both windows that offer one.
 *
 * <p>Writes straight through to the view model, so whichever dialog is on screen
 * reads the selection the same way.
 */
final class ProfilePicker {

    private ProfilePicker() {
    }

    /**
     * @param config the instance being launched, so the Manage button can open the
     *               editor on it; null leaves the button off
     */
    static JPanel build(DialogViewModel model, Config config) {
        JLabel caption = new JLabel("Profile");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));
        caption.setForeground(Theme.TEXT_MUTED);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);

        JComboBox<ProfileOption> combo = new JComboBox<>(
                new DefaultComboBoxModel<>(model.profiles().toArray(new ProfileOption[0])));
        combo.setMaximumSize(new Dimension(340, 30));
        combo.setPreferredSize(new Dimension(340, 30));
        Theme.styleComboBox(combo);

        select(combo, model.selectedProfile());

        combo.addActionListener(event -> {
            if (combo.getSelectedItem() instanceof ProfileOption option) {
                model.selectProfile(option.name());
            }
        });

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(combo);

        if (config != null) {
            JButton manage = new JButton("Manage…");
            Theme.styleButton(manage, false);
            manage.setFont(manage.getFont().deriveFont(11f));
            manage.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            manage.setToolTipText("Sort your mods into groups, and build profiles from them");
            manage.addActionListener(event -> {
                ManagerWindow.openFrom(SwingUtilities.getWindowAncestor(manage), config);
                reload(model, combo, config);
            });

            row.add(Box.createHorizontalStrut(6));
            row.add(manage);
        }

        JCheckBox remember = new JCheckBox("Remember this choice", model.rememberProfile());
        remember.setAlignmentX(Component.LEFT_ALIGNMENT);
        remember.setOpaque(false);
        remember.setForeground(Theme.TEXT_MUTED);
        remember.setFont(remember.getFont().deriveFont(11f));
        remember.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        remember.addActionListener(event -> model.setRememberProfile(remember.isSelected()));

        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(caption);
        panel.add(Box.createVerticalStrut(4));
        panel.add(row);
        panel.add(remember);

        return panel;
    }

    /**
     * Re-reads the profiles after the editor has been in them.
     *
     * <p>From disk rather than from anything the editor handed back: it saves
     * there, and this way a rename, a deletion or a whole new profile shows up
     * without the two having to agree on a channel.
     */
    private static void reload(DialogViewModel model, JComboBox<ProfileOption> combo, Config config) {
        String wanted = model.selectedProfile();
        ProfileConfig profiles = ProfileConfig.read(ModPaths.of(config.modsDir()).stateDir());
        List<ProfileOption> options = ProfileDialog.options(profiles);

        // The profile that was selected may have just been renamed or deleted.
        // offerProfiles falls back to the first one, which is the safe answer.
        model.offerProfiles(options, wanted, model.rememberProfile());

        combo.setModel(new DefaultComboBoxModel<>(options.toArray(new ProfileOption[0])));
        select(combo, model.selectedProfile());
        combo.setEnabled(!options.isEmpty());
    }

    private static void select(JComboBox<ProfileOption> combo, String name) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).name().equals(name)) {
                combo.setSelectedIndex(i);
                return;
            }
        }
    }
}
