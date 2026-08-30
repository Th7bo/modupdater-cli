package dev.th7bo.modupdater.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/**
 * The profile dropdown, shared by both windows that offer one.
 *
 * <p>Writes straight through to the view model, so whichever dialog is on screen
 * reads the selection the same way.
 */
final class ProfilePicker {

    private ProfilePicker() {
    }

    static JPanel build(DialogViewModel model) {
        JLabel caption = new JLabel("Profile");
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));
        caption.setForeground(Theme.TEXT_MUTED);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);

        JComboBox<ProfileOption> combo = new JComboBox<>(
                new DefaultComboBoxModel<>(model.profiles().toArray(new ProfileOption[0])));
        combo.setAlignmentX(Component.LEFT_ALIGNMENT);
        combo.setMaximumSize(new Dimension(340, 30));
        combo.setPreferredSize(new Dimension(340, 30));
        combo.setBackground(Theme.SURFACE);
        combo.setForeground(Theme.TEXT);

        model.profiles().stream()
                .filter(option -> option.name().equals(model.selectedProfile()))
                .findFirst()
                .ifPresent(combo::setSelectedItem);

        combo.addActionListener(event -> {
            if (combo.getSelectedItem() instanceof ProfileOption option) {
                model.selectProfile(option.name());
            }
        });

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
        panel.add(javax.swing.Box.createVerticalStrut(4));
        panel.add(combo);
        panel.add(remember);

        return panel;
    }
}
