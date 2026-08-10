package dev.th7bo.modupdater.ui;

import dev.th7bo.modupdater.diff.UpdateCandidate;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Selection state behind the update dialog. Kept free of Swing so the rules that
 * matter — what's selectable, when Update is enabled — are unit testable.
 */
public final class DialogViewModel {

    private final List<UpdateRow> rows;
    private final Set<Integer> selected = new LinkedHashSet<>();

    public DialogViewModel(List<UpdateCandidate> candidates) {
        this.rows = UpdateRow.from(candidates == null ? List.of() : candidates);
        // Everything is pre-selected: the user asked for updates by launching, so
        // opting out is the exception rather than the rule.
        for (int i = 0; i < rows.size(); i++) {
            selected.add(i);
        }
    }

    public List<UpdateRow> rows() {
        return rows;
    }

    public boolean isSelected(int index) {
        return selected.contains(index);
    }

    public void setSelected(int index, boolean value) {
        if (index < 0 || index >= rows.size()) {
            return;
        }
        if (value) {
            selected.add(index);
        } else {
            selected.remove(index);
        }
    }

    public void selectAll(boolean value) {
        selected.clear();
        if (value) {
            for (int i = 0; i < rows.size(); i++) {
                selected.add(i);
            }
        }
    }

    public boolean canUpdate() {
        return !selected.isEmpty();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int selectedCount() {
        return selected.size();
    }

    public List<UpdateCandidate> chosen() {
        List<UpdateCandidate> chosen = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (selected.contains(i)) {
                chosen.add(rows.get(i).candidate());
            }
        }
        return List.copyOf(chosen);
    }

    /**
     * A pre-launch hook can run without a display — over SSH, on a headless
     * server, or under a launcher that detaches stdio. Blocking on a dialog
     * nobody can see would hang the launch forever.
     */
    public static boolean headless() {
        return GraphicsEnvironment.isHeadless();
    }
}
