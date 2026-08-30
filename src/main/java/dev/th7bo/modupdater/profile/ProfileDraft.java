package dev.th7bo.modupdater.profile;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An editable copy of {@code profiles.json}, held while somebody rearranges it.
 *
 * <p>No Swing here on purpose. The manager window is a view onto this, so what
 * happens when a group is renamed or deleted — the part that can quietly corrupt
 * somebody's setup — is ordinary testable code rather than something reachable
 * only by clicking.
 *
 * <p>Anything the file said that this class does not model is kept and written
 * back untouched, so editing groups in the window cannot silently drop a key a
 * later version added.
 */
public final class ProfileDraft {

    /** One profile, in the shape the editor works in. */
    public static final class Entry {

        private String description = "";
        private final List<String> include = new ArrayList<>();
        private final List<String> add = new ArrayList<>();
        private final List<String> remove = new ArrayList<>();
        private boolean includeAll;
        private boolean disableUngrouped;

        public String description() {
            return description;
        }

        public List<String> include() {
            return List.copyOf(include);
        }

        public List<String> add() {
            return List.copyOf(add);
        }

        public List<String> remove() {
            return List.copyOf(remove);
        }

        public boolean includesEverything() {
            return includeAll;
        }

        public boolean disablesUngrouped() {
            return disableUngrouped;
        }

        public boolean includes(String group) {
            return include.contains(ProfileConfig.normalise(group));
        }
    }

    private final LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry> profiles = new LinkedHashMap<>();

    /** The file as it was read, so keys this class does not model survive a save. */
    private JsonObject original = new JsonObject();

    private boolean dirty;

    private ProfileDraft() {
    }

    /** @return the config on disk as a draft, or an empty draft when there is none */
    public static ProfileDraft load(Path stateDir) {
        ProfileDraft draft = new ProfileDraft();
        Path file = ProfileConfig.fileIn(stateDir);

        if (!Files.isRegularFile(file)) {
            return draft;
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                return draft;
            }
            draft.original = parsed.getAsJsonObject();
            draft.readGroups();
            draft.readProfiles();
        } catch (IOException | RuntimeException e) {
            // A file too broken to parse is left entirely alone: the draft stays
            // empty and saving is what would overwrite it, which the window only
            // does when the user asks.
            return new ProfileDraft();
        }

        return draft;
    }

    public boolean dirty() {
        return dirty;
    }

    // ── Groups ──────────────────────────────────────────────────────────────

    public List<String> groupNames() {
        return List.copyOf(groups.keySet());
    }

    public List<String> membersOf(String group) {
        return List.copyOf(groups.getOrDefault(ProfileConfig.normalise(group), List.of()));
    }

    public boolean hasGroup(String group) {
        return groups.containsKey(ProfileConfig.normalise(group));
    }

    /** @return false when the name is unusable or already taken */
    public boolean addGroup(String name) {
        String group = ProfileConfig.normalise(name);
        if (group == null || group.isBlank() || groups.containsKey(group)) {
            return false;
        }
        groups.put(group, new ArrayList<>());
        dirty = true;
        return true;
    }

    /**
     * Removes a group, and every profile's reference to it.
     *
     * <p>Leaving the references behind would turn each one into an "includes a
     * group that is not defined" warning at the next launch, which is a puzzle to
     * anybody who only deleted something in a window.
     */
    public boolean removeGroup(String name) {
        String group = ProfileConfig.normalise(name);
        if (group == null || !groups.containsKey(group)) {
            return false;
        }

        groups.remove(group);
        profiles.values().forEach(entry -> entry.include.remove(group));
        dirty = true;
        return true;
    }

    /** Renames a group, carrying every profile that includes it along with it. */
    public boolean renameGroup(String from, String to) {
        String was = ProfileConfig.normalise(from);
        String now = ProfileConfig.normalise(to);

        if (was == null || now == null || now.isBlank()
                || !groups.containsKey(was) || groups.containsKey(now)) {
            return false;
        }

        // Rebuilt rather than put-and-remove, so the group keeps its place in the
        // file instead of jumping to the end.
        LinkedHashMap<String, List<String>> rebuilt = new LinkedHashMap<>();
        groups.forEach((name, members) -> rebuilt.put(name.equals(was) ? now : name, members));
        groups.clear();
        groups.putAll(rebuilt);

        for (Entry entry : profiles.values()) {
            int at = entry.include.indexOf(was);
            if (at >= 0) {
                entry.include.set(at, now);
            }
        }

        dirty = true;
        return true;
    }

    public boolean isMember(String group, String modId) {
        return membersOf(group).contains(ProfileConfig.normalise(modId));
    }

    /** Puts a mod in a group, or takes it out. Unknown groups are ignored. */
    public void setMember(String group, String modId, boolean member) {
        List<String> members = groups.get(ProfileConfig.normalise(group));
        String id = ProfileConfig.normalise(modId);

        if (members == null || id == null || id.isBlank()) {
            return;
        }

        if (member && !members.contains(id)) {
            members.add(id);
            dirty = true;
        } else if (!member && members.remove(id)) {
            dirty = true;
        }
    }

    // ── Profiles ────────────────────────────────────────────────────────────

    public List<String> profileNames() {
        return List.copyOf(profiles.keySet());
    }

    public Entry profile(String name) {
        return profiles.get(ProfileConfig.normalise(name));
    }

    public boolean addProfile(String name) {
        String profile = ProfileConfig.normalise(name);
        if (profile == null || profile.isBlank() || profiles.containsKey(profile)) {
            return false;
        }
        profiles.put(profile, new Entry());
        dirty = true;
        return true;
    }

    public boolean removeProfile(String name) {
        if (profiles.remove(ProfileConfig.normalise(name)) == null) {
            return false;
        }
        dirty = true;
        return true;
    }

    public boolean renameProfile(String from, String to) {
        String was = ProfileConfig.normalise(from);
        String now = ProfileConfig.normalise(to);

        if (was == null || now == null || now.isBlank()
                || !profiles.containsKey(was) || profiles.containsKey(now)) {
            return false;
        }

        LinkedHashMap<String, Entry> rebuilt = new LinkedHashMap<>();
        profiles.forEach((name, entry) -> rebuilt.put(name.equals(was) ? now : name, entry));
        profiles.clear();
        profiles.putAll(rebuilt);

        dirty = true;
        return true;
    }

    public void setIncluded(String profileName, String group, boolean included) {
        Entry entry = profile(profileName);
        String name = ProfileConfig.normalise(group);

        if (entry == null || name == null) {
            return;
        }

        if (included && !entry.include.contains(name)) {
            entry.include.add(name);
            dirty = true;
        } else if (!included && entry.include.remove(name)) {
            dirty = true;
        }
    }

    public void setDescription(String profileName, String description) {
        Entry entry = profile(profileName);
        if (entry == null) {
            return;
        }
        String value = description == null ? "" : description.trim();
        if (!value.equals(entry.description)) {
            entry.description = value;
            dirty = true;
        }
    }

    public void setIncludesEverything(String profileName, boolean everything) {
        Entry entry = profile(profileName);
        if (entry != null && entry.includeAll != everything) {
            entry.includeAll = everything;
            dirty = true;
        }
    }

    public void setDisablesUngrouped(String profileName, boolean disable) {
        Entry entry = profile(profileName);
        if (entry != null && entry.disableUngrouped != disable) {
            entry.disableUngrouped = disable;
            dirty = true;
        }
    }

    /** Mods no group holds and no profile names — the ones that stay on regardless. */
    public Set<String> ungrouped(Set<String> installedModIds) {
        Set<String> managed = new LinkedHashSet<>();
        groups.values().forEach(managed::addAll);
        profiles.values().forEach(entry -> {
            managed.addAll(entry.add);
            managed.addAll(entry.remove);
        });

        Set<String> loose = new LinkedHashSet<>();
        for (String modId : installedModIds) {
            String id = ProfileConfig.normalise(modId);
            if (!managed.contains(id)) {
                loose.add(id);
            }
        }
        return loose;
    }

    /** The draft as the rest of the program sees it, for previewing a profile. */
    public ProfileConfig toConfig() {
        return ProfileConfig.fromJson(toJson());
    }

    // ── Saving ──────────────────────────────────────────────────────────────

    /** Writes the draft, keeping anything in the file this class does not model. */
    public void save(Path stateDir) throws IOException {
        Path file = ProfileConfig.fileIn(stateDir);
        Files.createDirectories(file.getParent());

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary,
                new GsonBuilder().setPrettyPrinting().create().toJson(toJson()) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }

        dirty = false;
    }

    JsonObject toJson() {
        JsonObject document = original.deepCopy();

        JsonObject groupsJson = new JsonObject();
        groups.forEach((name, members) -> {
            JsonArray array = new JsonArray();
            members.forEach(array::add);
            groupsJson.add(name, array);
        });
        document.add("groups", groupsJson);

        JsonObject profilesJson = new JsonObject();
        profiles.forEach((name, entry) -> profilesJson.add(name, toJson(entry)));
        document.add("profiles", profilesJson);

        return document;
    }

    private static JsonObject toJson(Entry entry) {
        JsonObject json = new JsonObject();

        if (!entry.description.isBlank()) {
            json.addProperty("description", entry.description);
        }
        if (entry.includeAll) {
            json.addProperty("includeAll", true);
        } else {
            json.add("include", array(entry.include));
        }
        if (!entry.add.isEmpty()) {
            json.add("add", array(entry.add));
        }
        if (!entry.remove.isEmpty()) {
            json.add("remove", array(entry.remove));
        }
        if (entry.disableUngrouped) {
            json.addProperty("ungrouped", "disable");
        }

        return json;
    }

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    private void readGroups() {
        JsonObject json = original.getAsJsonObject("groups");
        if (json == null) {
            return;
        }

        for (String name : json.keySet()) {
            String group = ProfileConfig.normalise(name);
            if (group == null || group.isBlank()) {
                continue;
            }
            groups.put(group, new ArrayList<>(strings(json.get(name))));
        }
    }

    private void readProfiles() {
        JsonObject json = original.getAsJsonObject("profiles");
        if (json == null) {
            return;
        }

        for (String name : json.keySet()) {
            String profileName = ProfileConfig.normalise(name);
            JsonElement element = json.get(name);

            if (profileName == null || profileName.isBlank()
                    || element == null || !element.isJsonObject()) {
                continue;
            }

            JsonObject object = element.getAsJsonObject();
            Entry entry = new Entry();
            entry.description = string(object, "description");
            entry.include.addAll(strings(object.get("include")));
            entry.add.addAll(strings(object.get("add")));
            entry.remove.addAll(strings(object.get("remove")));
            entry.includeAll = bool(object, "includeAll");
            entry.disableUngrouped = "disable".equalsIgnoreCase(string(object, "ungrouped"));

            profiles.put(profileName, entry);
        }
    }

    private static List<String> strings(JsonElement element) {
        List<String> values = new ArrayList<>();
        if (element == null || !element.isJsonArray()) {
            return values;
        }

        for (JsonElement item : element.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                String value = ProfileConfig.normalise(item.getAsString());
                if (value != null && !value.isBlank() && !values.contains(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                ? element.getAsString()
                : "";
    }

    private static boolean bool(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean()
                && element.getAsBoolean();
    }
}
