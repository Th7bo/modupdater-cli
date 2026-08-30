package dev.th7bo.modupdater.update;

import java.util.ArrayList;
import java.util.List;

/**
 * A release version, for deciding whether the one on GitHub is newer than this
 * one.
 *
 * <p>Deliberately forgiving. The tag is {@code v0.5.0} and the manifest says
 * {@code 0.5.0}; either parses. Anything that is not a run of dotted numbers —
 * a {@code -SNAPSHOT} suffix, a stray word — stops the numeric comparison and is
 * then only compared for equality, so an unreadable version reads as "not newer"
 * rather than as an upgrade.
 */
public record Version(List<Integer> numbers, String raw) implements Comparable<Version> {

    public static final Version UNKNOWN = new Version(List.of(), "unknown");

    public Version {
        numbers = numbers == null ? List.of() : List.copyOf(numbers);
    }

    public static Version of(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }

        String trimmed = text.trim();
        String body = trimmed.startsWith("v") || trimmed.startsWith("V")
                ? trimmed.substring(1)
                : trimmed;

        List<Integer> numbers = new ArrayList<>();
        for (String part : body.split("\\.")) {
            // Stops at the first part that is not a plain number, so 1.2.0-rc1
            // compares as 1.2.0 and everything after it is a tie-break on text.
            int digits = 0;
            while (digits < part.length() && Character.isDigit(part.charAt(digits))) {
                digits++;
            }
            if (digits == 0) {
                break;
            }
            try {
                numbers.add(Integer.parseInt(part.substring(0, digits)));
            } catch (NumberFormatException e) {
                break;
            }
            if (digits < part.length()) {
                break;
            }
        }

        return new Version(numbers, trimmed);
    }

    public boolean known() {
        return !numbers.isEmpty();
    }

    /**
     * Whether {@code other} is a version worth downloading.
     *
     * <p>False whenever either side is unreadable. Replacing a working install on
     * the strength of a version string nobody could parse is not a trade worth
     * making — the user can always re-run the installer.
     */
    public boolean supersededBy(Version other) {
        if (other == null || !known() || !other.known()) {
            return false;
        }
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Version other) {
        int length = Math.max(numbers.size(), other.numbers.size());
        for (int i = 0; i < length; i++) {
            int mine = i < numbers.size() ? numbers.get(i) : 0;
            int theirs = i < other.numbers.size() ? other.numbers.get(i) : 0;
            if (mine != theirs) {
                return Integer.compare(mine, theirs);
            }
        }

        // Same numbers. A build with a suffix — 1.2.0-rc1 — is behind the plain
        // release, which is the only ordering that does not offer a downgrade.
        return Integer.compare(suffixRank(), other.suffixRank());
    }

    private int suffixRank() {
        String body = raw.startsWith("v") || raw.startsWith("V") ? raw.substring(1) : raw;
        String numeric = String.join(".", numbers.stream().map(String::valueOf).toList());
        return body.equals(numeric) ? 1 : 0;
    }

    @Override
    public String toString() {
        return raw;
    }
}
