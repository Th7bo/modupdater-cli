package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.Config;
import dev.th7bo.modupdater.instance.InstalledMod;
import dev.th7bo.modupdater.instance.InstanceScanner;
import dev.th7bo.modupdater.instance.ModInventory;
import dev.th7bo.modupdater.instance.ModPaths;
import dev.th7bo.modupdater.profile.ProfileConfig;
import dev.th7bo.modupdater.profile.ProfileDraft;
import dev.th7bo.modupdater.profile.ProfileManager;
import dev.th7bo.modupdater.profile.ProfilePlan;
import dev.th7bo.modupdater.profile.ProfileResolver;
import dev.th7bo.modupdater.profile.ProfileToggle;
import dev.th7bo.modupdater.setup.Instance;
import dev.th7bo.modupdater.setup.InstanceDiscovery;
import dev.th7bo.modupdater.util.Log;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The manager: groups on the left, whatever is selected on the right.
 *
 * <p>A window because classifying thirty mods into groups the first time is
 * genuinely tedious in a text editor — you are cross-referencing a list of mod
 * ids against what each mod actually does. Ticking boxes next to icons is the
 * job this shape is good at.
 *
 * <p>Everything that changes the config goes through {@link ProfileDraft}, and
 * everything that moves a file goes through {@link ProfileManager}. This class
 * holds no rules of its own — it is the part that cannot be unit tested, so it
 * is kept to layout and wiring.
 */
public final class ManagerWindow {

    private final JDialog dialog;

    private Config config;
    private ModPaths paths;
    private ProfileDraft draft;
    private ModInventory inventory;

    private final JComboBox<InstanceChoice> instances = new JComboBox<>();
    private final JCheckBox profilesEnabled = new JCheckBox("Mod profiles");
    private final DefaultListModel<String> groupItems = new DefaultListModel<>();
    private final DefaultListModel<String> profileItems = new DefaultListModel<>();
    private final JList<String> groupList = new JList<>(groupItems);
    private final JList<String> profileList = new JList<>(profileItems);
    private final JPanel detail = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JButton save = new JButton("Save");
    private final JButton revert = new JButton("Revert");

    /** One line in the mod list, tall enough for a 20px icon and its text. */
    private static final int ROW_HEIGHT = 28;

    /** Suppresses the listeners that would otherwise fire while a pane is rebuilt. */
    private boolean loading;

    private ManagerWindow(Config config) {
        this.config = config;
        this.dialog = new JDialog((java.awt.Frame) null, "ModUpdater — mod profiles", true);
        reload();
    }

    /** Opens the window and returns once it is closed. */
    public static void open(Config config) {
        if (DialogViewModel.headless()) {
            Log.warn("no display available — 'modupdater profile edit' needs one."
                    + " Use 'modupdater profile group add' instead.");
            return;
        }

        try {
            SwingUtilities.invokeAndWait(() -> new ManagerWindow(config).show());
        } catch (Exception e) {
            Log.error("could not open the profile manager: " + e);
        }
    }

    /** One entry in the instance picker. */
    private record InstanceChoice(String label, Path modsDir) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void show() {
        Theme.apply();
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.getContentPane().setBackground(Theme.BACKGROUND);
        dialog.setLayout(new BorderLayout());

        dialog.add(buildHeader(), BorderLayout.NORTH);
        dialog.add(buildBody(), BorderLayout.CENTER);
        dialog.add(buildFooter(), BorderLayout.SOUTH);

        refreshLists();
        selectFirst();

        dialog.setPreferredSize(new Dimension(940, 600));
        dialog.pack();
        placeOnActiveScreen();
        dialog.setVisible(true);
    }

    // ── Header: which instance, and whether profiles are on for it ──────────

    private JPanel buildHeader() {
        for (InstanceChoice choice : discoverInstances()) {
            instances.addItem(choice);
        }
        Theme.styleComboBox(instances);
        instances.setPreferredSize(new Dimension(420, 30));
        instances.setMaximumSize(new Dimension(420, 30));
        instances.addActionListener(event -> {
            if (loading || !(instances.getSelectedItem() instanceof InstanceChoice choice)) {
                return;
            }
            if (!confirmDiscard()) {
                loading = true;
                selectCurrentInstance();
                loading = false;
                return;
            }
            switchTo(choice.modsDir());
        });

        profilesEnabled.setOpaque(false);
        profilesEnabled.setForeground(Theme.TEXT);
        profilesEnabled.setFont(profilesEnabled.getFont().deriveFont(Font.BOLD, 13f));
        profilesEnabled.setSelected(config.profilesEnabled());
        profilesEnabled.addActionListener(event -> toggleProfiles(profilesEnabled.isSelected()));

        JLabel caption = new JLabel("Instance");
        caption.setForeground(Theme.TEXT_MUTED);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(caption);
        left.add(Box.createHorizontalStrut(10));
        left.add(instances);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.BACKGROUND);
        header.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
        header.add(left, BorderLayout.WEST);
        header.add(profilesEnabled, BorderLayout.EAST);
        return header;
    }

    private List<InstanceChoice> discoverInstances() {
        List<InstanceChoice> choices = new ArrayList<>();
        choices.add(new InstanceChoice(shortLabel(config.modsDir()), config.modsDir()));

        try {
            for (Instance instance : InstanceDiscovery.forThisMachine().discover()) {
                Path mods = instance.gameDir().resolve("mods");
                if (!mods.equals(config.modsDir())) {
                    choices.add(new InstanceChoice(
                            instance.name() + "  (" + instance.launcher() + ")", mods));
                }
            }
        } catch (RuntimeException e) {
            // Discovery is a convenience. The instance we were pointed at is
            // already in the list, so failing to find the others is survivable.
            Log.warn("could not list your other instances: " + e.getMessage());
        }

        return choices;
    }

    private static String shortLabel(Path modsDir) {
        Path instanceDir = modsDir.getParent();
        return instanceDir == null ? String.valueOf(modsDir) : String.valueOf(instanceDir.getFileName());
    }

    // ── Body: the two lists, and the detail pane ────────────────────────────

    private JPanel buildBody() {
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        for (JList<String> list : List.of(groupList, profileList)) {
            list.setBackground(Theme.SURFACE);
            list.setForeground(Theme.TEXT);
            list.setSelectionBackground(Theme.SELECTION);
            list.setSelectionForeground(Theme.TEXT);
            list.setFixedCellHeight(26);
            list.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        }

        groupList.addListSelectionListener(event -> {
            if (!loading && !event.getValueIsAdjusting() && groupList.getSelectedValue() != null) {
                profileList.clearSelection();
                showGroup(groupList.getSelectedValue());
            }
        });
        profileList.addListSelectionListener(event -> {
            if (!loading && !event.getValueIsAdjusting() && profileList.getSelectedValue() != null) {
                groupList.clearSelection();
                showProfile(profileList.getSelectedValue());
            }
        });

        JPanel sidebar = new JPanel();
        sidebar.setBackground(Theme.BACKGROUND);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setPreferredSize(new Dimension(250, 0));
        sidebar.add(section("Groups", groupList, this::newGroup, this::deleteGroup, this::renameGroup));
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(section("Profiles", profileList, this::newProfile, this::deleteProfile,
                this::renameProfile));

        detail.setBackground(Theme.BACKGROUND);
        detail.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        JPanel body = new JPanel(new BorderLayout(16, 0));
        body.setBackground(Theme.BACKGROUND);
        body.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        body.add(sidebar, BorderLayout.WEST);
        body.add(detail, BorderLayout.CENTER);
        return body;
    }

    private JPanel section(
            String title, JList<String> list, Runnable add, Runnable delete, Runnable rename) {

        JLabel caption = new JLabel(title);
        caption.setForeground(Theme.TEXT_MUTED);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 12f));

        JButton addButton = smallButton("+", add);
        JButton removeButton = smallButton("−", delete);
        JButton renameButton = smallButton("Rename", rename);

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(addButton);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(removeButton);
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(renameButton);
        buttons.add(Box.createHorizontalGlue());

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.SURFACE);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(Theme.BACKGROUND);
        panel.add(caption, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private static JButton smallButton(String text, Runnable action) {
        JButton button = new JButton(text);
        Theme.styleButton(button, false);
        button.setFont(button.getFont().deriveFont(11f));
        button.setBorder(BorderFactory.createEmptyBorder(5, 9, 5, 9));
        button.addActionListener(event -> action.run());
        return button;
    }

    // ── Detail: a group's mods ──────────────────────────────────────────────

    private void showGroup(String group) {
        JPanel panel = detailPanel("Group \"" + group + "\"",
                "Tick the mods that belong to it. A mod can be in more than one group.");

        JPanel mods = new JPanel();
        mods.setBackground(Theme.SURFACE);
        mods.setLayout(new BoxLayout(mods, BoxLayout.Y_AXIS));

        for (InstalledMod mod : inventory.all()) {
            if (!mod.matchable()) {
                continue;
            }

            JCheckBox box = new JCheckBox(mod.modId(), draft.isMember(group, mod.modId()));
            box.setOpaque(false);
            box.setForeground(Theme.TEXT);
            box.setIcon(null);
            box.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            box.addActionListener(event -> {
                draft.setMember(group, mod.modId(), box.isSelected());
                refreshLists();
                updateStatus();
            });

            JLabel icon = new JLabel(ModIcon.of(mod.path()));
            icon.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));

            JLabel where = new JLabel(inventory.inactive().contains(mod) ? "stored" : "active");
            where.setForeground(Theme.TEXT_MUTED);
            where.setFont(where.getFont().deriveFont(11f));

            JPanel row = new JPanel();
            row.setOpaque(false);
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Without a ceiling, BoxLayout shares the whole pane between however
            // many rows there are and a short list ends up double-spaced.
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, ROW_HEIGHT));
            row.add(icon);
            row.add(box);
            row.add(Box.createHorizontalGlue());
            row.add(where);
            row.add(Box.createHorizontalStrut(10));
            mods.add(row);
        }

        mods.add(Box.createVerticalGlue());
        panel.add(scrolled(mods), BorderLayout.CENTER);
        setDetail(panel);
    }

    // ── Detail: a profile's groups, and what it comes to ────────────────────

    private void showProfile(String name) {
        ProfileDraft.Entry entry = draft.profile(name);
        if (entry == null) {
            return;
        }

        JPanel panel = detailPanel("Profile \"" + name + "\"",
                "Built from the groups you tick. Everything else is switched off at launch.");

        JTextField description = new JTextField(entry.description());
        description.setBackground(Theme.SURFACE);
        description.setForeground(Theme.TEXT);
        description.setCaretColor(Theme.TEXT);
        description.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        description.addActionListener(event -> draft.setDescription(name, description.getText()));
        description.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                draft.setDescription(name, description.getText());
            }
        });

        JCheckBox everything = themedCheckBox("Every installed mod, ignoring groups",
                entry.includesEverything());
        JCheckBox strict = themedCheckBox("Also switch off mods that are in no group",
                entry.disablesUngrouped());

        JPanel groupBoxes = new JPanel(new GridLayout(0, 2, 6, 2));
        groupBoxes.setBackground(Theme.SURFACE);
        groupBoxes.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        for (String group : draft.groupNames()) {
            JCheckBox box = themedCheckBox(
                    group + "  (" + draft.membersOf(group).size() + ")", entry.includes(group));
            box.setEnabled(!entry.includesEverything());
            box.addActionListener(event -> {
                draft.setIncluded(name, group, box.isSelected());
                showProfile(name);
                updateStatus();
            });
            groupBoxes.add(box);
        }

        everything.addActionListener(event -> {
            draft.setIncludesEverything(name, everything.isSelected());
            showProfile(name);
            updateStatus();
        });
        strict.addActionListener(event -> {
            draft.setDisablesUngrouped(name, strict.isSelected());
            showProfile(name);
            updateStatus();
        });

        JButton apply = new JButton("Apply to mods folder now");
        Theme.styleButton(apply, false);
        apply.addActionListener(event -> applyNow(name));
        apply.setMaximumSize(apply.getPreferredSize());

        JPanel form = new JPanel();
        form.setBackground(Theme.BACKGROUND);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(field("Description", description));
        form.add(Box.createVerticalStrut(12));
        form.add(field("Includes", groupBoxes));
        form.add(Box.createVerticalStrut(8));
        for (JCheckBox box : List.of(everything, strict)) {
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
        }
        form.add(everything);
        form.add(strict);
        form.add(Box.createVerticalStrut(12));
        form.add(field("Comes to", preview(name, entry)));
        form.add(Box.createVerticalStrut(12));
        apply.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(apply);
        form.add(Box.createVerticalGlue());

        panel.add(scrolled(form), BorderLayout.CENTER);
        setDetail(panel);
    }

    /**
     * What this profile actually comes to, resolved the same way a launch would.
     *
     * <p>The point of the window: seeing the answer while you are still deciding,
     * rather than at the next launch.
     */
    private JPanel preview(String name, ProfileDraft.Entry entry) {
        ProfileResolver.Resolution resolution =
                ProfileResolver.resolve(draft.toConfig(), name, inventory);

        Set<String> active = new LinkedHashSet<>(resolution.activeModIds());
        Set<String> off = new LinkedHashSet<>(inventory.modIds());
        off.removeIf(active::contains);

        JLabel count = new JLabel(active.size() + " on, " + off.size() + " off");
        count.setForeground(Theme.TEXT);
        count.setFont(count.getFont().deriveFont(Font.BOLD, 12f));
        count.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel switchedOff = new JLabel(off.isEmpty()
                ? "Nothing is switched off."
                : "<html>Off: " + String.join(", ", off) + "</html>");
        switchedOff.setForeground(Theme.TEXT_MUTED);
        switchedOff.setFont(switchedOff.getFont().deriveFont(11f));
        switchedOff.setAlignmentX(Component.LEFT_ALIGNMENT);
        switchedOff.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        JPanel panel = new JPanel();
        panel.setBackground(Theme.SURFACE);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(count);
        panel.add(switchedOff);

        if (!entry.add().isEmpty() || !entry.remove().isEmpty()) {
            JLabel extras = new JLabel("<html>Also, from the config file: "
                    + (entry.add().isEmpty() ? "" : "plus " + String.join(", ", entry.add()) + " ")
                    + (entry.remove().isEmpty() ? "" : "minus " + String.join(", ", entry.remove()))
                    + "</html>");
            extras.setForeground(Theme.TEXT_MUTED);
            extras.setFont(extras.getFont().deriveFont(11f));
            extras.setAlignmentX(Component.LEFT_ALIGNMENT);
            extras.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            panel.add(extras);
        }

        return panel;
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private void newGroup() {
        String name = ask("Name for the new group");
        if (name != null && !draft.addGroup(name)) {
            warn("There is already a group called \"" + name + "\".");
        } else if (name != null) {
            refreshLists();
            groupList.setSelectedValue(ProfileConfig.normalise(name), true);
        }
    }

    private void deleteGroup() {
        String group = groupList.getSelectedValue();
        if (group == null) {
            return;
        }

        List<String> users = draft.profileNames().stream()
                .filter(profile -> draft.profile(profile).includes(group))
                .toList();

        String question = users.isEmpty()
                ? "Delete the group \"" + group + "\"?"
                : "Delete the group \"" + group + "\"?\nIt will also be removed from: "
                        + String.join(", ", users);

        if (confirm(question)) {
            draft.removeGroup(group);
            refreshLists();
            clearDetail();
        }
    }

    private void renameGroup() {
        String group = groupList.getSelectedValue();
        if (group == null) {
            return;
        }
        String name = ask("New name for \"" + group + "\"");
        if (name == null) {
            return;
        }
        if (!draft.renameGroup(group, name)) {
            warn("Could not rename it — \"" + name + "\" is already taken.");
            return;
        }
        refreshLists();
        groupList.setSelectedValue(ProfileConfig.normalise(name), true);
    }

    private void newProfile() {
        String name = ask("Name for the new profile");
        if (name != null && !draft.addProfile(name)) {
            warn("There is already a profile called \"" + name + "\".");
        } else if (name != null) {
            refreshLists();
            profileList.setSelectedValue(ProfileConfig.normalise(name), true);
        }
    }

    private void deleteProfile() {
        String profile = profileList.getSelectedValue();
        if (profile != null && confirm("Delete the profile \"" + profile + "\"?")) {
            draft.removeProfile(profile);
            refreshLists();
            clearDetail();
        }
    }

    private void renameProfile() {
        String profile = profileList.getSelectedValue();
        if (profile == null) {
            return;
        }
        String name = ask("New name for \"" + profile + "\"");
        if (name == null) {
            return;
        }
        if (!draft.renameProfile(profile, name)) {
            warn("Could not rename it — \"" + name + "\" is already taken.");
            return;
        }
        refreshLists();
        profileList.setSelectedValue(ProfileConfig.normalise(name), true);
    }

    /**
     * Applies a profile straight away, saving first: moving files to match a
     * profile that is not on disk yet would leave the two disagreeing.
     */
    private void applyNow(String name) {
        if (!config.profilesEnabled()) {
            warn("Profiles are switched off for this instance. Tick the box at the top first.");
            return;
        }
        if (draft.dirty() && !saveDraft()) {
            return;
        }

        ModInventory current = ModInventory.scan(paths, new InstanceScanner());
        ProfileResolver.Resolution resolution =
                ProfileResolver.resolve(draft.toConfig(), name, current);
        ProfileManager.Result result =
                new ProfileManager(paths).apply(ProfilePlan.of(paths, current, resolution));

        switch (result) {
            case ProfileManager.Result.Failed failed ->
                    warn("Could not apply it: " + failed.detail());
            case ProfileManager.Result.NothingToDo ignored ->
                    status.setText("\"" + name + "\" is already what is in the mods folder.");
            case ProfileManager.Result.Applied applied -> status.setText(
                    "Applied \"" + name + "\" — " + applied.activated() + " on, "
                            + applied.deactivated() + " off.");
        }

        reloadInventory();
    }

    private void toggleProfiles(boolean wanted) {
        ProfileToggle.Result result =
                wanted ? ProfileToggle.enable(config) : ProfileToggle.disable(config);

        if (result instanceof ProfileToggle.Result.Failed failed) {
            warn(failed.detail());
            profilesEnabled.setSelected(config.profilesEnabled());
            return;
        }

        if (result instanceof ProfileToggle.Result.Disabled disabled && disabled.restored() > 0) {
            status.setText("Brought " + disabled.restored() + " stored mod(s) back into mods/.");
        }

        // Re-resolved rather than assumed: the toggle wrote the properties file,
        // and this is the object that reads it.
        switchTo(config.modsDir());
    }

    private boolean saveDraft() {
        try {
            draft.save(paths.stateDir());
            status.setText("Saved to " + ProfileConfig.fileIn(paths.stateDir()) + ".");
            updateButtons();
            return true;
        } catch (IOException e) {
            warn("Could not save: " + e.getMessage());
            return false;
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private JPanel buildFooter() {
        Theme.styleButton(save, true);
        Theme.styleButton(revert, false);

        save.addActionListener(event -> saveDraft());
        revert.addActionListener(event -> {
            if (confirmDiscard()) {
                reload();
                refreshLists();
                clearDetail();
                status.setText("Reverted to what is on disk.");
            }
        });

        status.setForeground(Theme.TEXT_MUTED);
        status.setFont(status.getFont().deriveFont(11f));

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(revert);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(save);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Theme.BACKGROUND);
        footer.setBorder(BorderFactory.createEmptyBorder(14, 18, 16, 18));
        footer.add(status, BorderLayout.WEST);
        footer.add(buttons, BorderLayout.EAST);
        return footer;
    }

    private void reload() {
        this.paths = ModPaths.of(config.modsDir());
        this.draft = ProfileDraft.load(paths.stateDir());
        reloadInventory();
    }

    private void reloadInventory() {
        this.inventory = ModInventory.scan(paths, new InstanceScanner());
        updateStatus();
        updateButtons();
    }

    private void switchTo(Path modsDir) {
        this.config = Config.resolve(new String[]{"--mods-dir", modsDir.toString()});
        reload();

        loading = true;
        profilesEnabled.setSelected(config.profilesEnabled());
        selectCurrentInstance();
        loading = false;

        refreshLists();
        clearDetail();
    }

    private void selectCurrentInstance() {
        for (int i = 0; i < instances.getItemCount(); i++) {
            if (instances.getItemAt(i).modsDir().equals(config.modsDir())) {
                instances.setSelectedIndex(i);
                return;
            }
        }
    }

    private void refreshLists() {
        loading = true;
        String group = groupList.getSelectedValue();
        String profile = profileList.getSelectedValue();

        groupItems.clear();
        draft.groupNames().forEach(groupItems::addElement);
        profileItems.clear();
        draft.profileNames().forEach(profileItems::addElement);

        if (group != null) {
            groupList.setSelectedValue(group, true);
        }
        if (profile != null) {
            profileList.setSelectedValue(profile, true);
        }
        loading = false;

        updateButtons();
        updateStatus();
    }

    private void selectFirst() {
        if (!groupItems.isEmpty()) {
            groupList.setSelectedIndex(0);
        } else if (!profileItems.isEmpty()) {
            profileList.setSelectedIndex(0);
        }
    }

    private void updateButtons() {
        save.setEnabled(draft.dirty());
        revert.setEnabled(draft.dirty());
    }

    private void updateStatus() {
        if (inventory == null) {
            return;
        }

        Set<String> loose = draft.ungrouped(inventory.modIds());
        String message = inventory.size() + " mod(s) installed"
                + (inventory.inactive().isEmpty() ? "" : ", " + inventory.inactive().size() + " stored");

        if (!loose.isEmpty()) {
            message += "  ·  " + loose.size() + " in no group (they stay on in every profile)";
        }
        if (draft.dirty()) {
            message += "  ·  unsaved changes";
        }

        status.setText(message);
    }

    private void setDetail(JPanel panel) {
        detail.removeAll();
        detail.add(panel, BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
    }

    private void clearDetail() {
        JLabel hint = new JLabel("Pick a group or a profile on the left.");
        hint.setForeground(Theme.TEXT_MUTED);
        hint.setHorizontalAlignment(JLabel.CENTER);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.BACKGROUND);
        panel.add(hint, BorderLayout.CENTER);
        setDetail(panel);
    }

    private JPanel detailPanel(String title, String subtitle) {
        JLabel heading = new JLabel(title);
        heading.setForeground(Theme.TEXT);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));

        JLabel caption = new JLabel(subtitle);
        caption.setForeground(Theme.TEXT_MUTED);
        caption.setFont(caption.getFont().deriveFont(11f));
        caption.setBorder(BorderFactory.createEmptyBorder(3, 0, 10, 0));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(heading);
        header.add(caption);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Theme.BACKGROUND);
        panel.add(header, BorderLayout.NORTH);
        return panel;
    }

    private static JPanel field(String label, Component content) {
        JLabel caption = new JLabel(label);
        caption.setForeground(Theme.TEXT_MUTED);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD, 11f));
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);

        javax.swing.JComponent inner = (javax.swing.JComponent) content;
        inner.setAlignmentX(Component.LEFT_ALIGNMENT);
        // BoxLayout hands out every spare pixel to whatever will take it, which
        // turns a one-line text field into a tall empty box. Nothing here grows.
        inner.setMaximumSize(new Dimension(Integer.MAX_VALUE, inner.getPreferredSize().height));

        JPanel panel = new JPanel();
        panel.setBackground(Theme.BACKGROUND);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(caption);
        panel.add(Box.createVerticalStrut(4));
        panel.add(inner);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private static JCheckBox themedCheckBox(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setForeground(Theme.TEXT);
        box.setFont(box.getFont().deriveFont(12f));
        return box;
    }

    private static JScrollPane scrolled(Component content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.SURFACE);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private String ask(String question) {
        String answer = JOptionPane.showInputDialog(dialog, question, "ModUpdater",
                JOptionPane.PLAIN_MESSAGE);
        return answer == null || answer.isBlank() ? null : answer.trim();
    }

    private boolean confirm(String question) {
        return JOptionPane.showConfirmDialog(dialog, question, "ModUpdater",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    /** @return true when there is nothing to lose, or the user is willing to lose it */
    private boolean confirmDiscard() {
        return !draft.dirty() || confirm("You have unsaved changes. Discard them?");
    }

    private void warn(String message) {
        JOptionPane.showMessageDialog(dialog, message, "ModUpdater", JOptionPane.WARNING_MESSAGE);
    }

    private void placeOnActiveScreen() {
        try {
            Rectangle screen = ActiveScreen.bounds();
            dialog.setLocation(
                    screen.x + (screen.width - dialog.getWidth()) / 2,
                    screen.y + (screen.height - dialog.getHeight()) / 2);
        } catch (RuntimeException e) {
            dialog.setLocationRelativeTo(null);
        }
    }
}
