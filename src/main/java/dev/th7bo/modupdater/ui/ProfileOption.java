package dev.th7bo.modupdater.ui;

/** One entry in the profile picker. */
public record ProfileOption(String name, String description) {

    /** Title case for display: the config's {@code dungeons} shows as "Dungeons". */
    public String label() {
        if (name == null || name.isBlank()) {
            return "";
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Override
    public String toString() {
        return description == null || description.isBlank() ? label() : label() + " — " + description;
    }

    public static ProfileOption of(String name, String description) {
        return new ProfileOption(name, description);
    }
}
