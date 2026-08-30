package dev.th7bo.modupdater.profile;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Edits {@code profiles.json} in place, for the group subcommands.
 *
 * <p>Works on the parsed tree rather than the mapped {@link ProfileConfig} so
 * that everything the file says survives a write — profiles, descriptions, keys
 * a later version might add — instead of only the parts this version models.
 * Gson's object is insertion-ordered, so groups and profiles come back in the
 * order they were written.
 *
 * <p>Whitespace is not preserved: the file is re-serialised pretty-printed. JSON
 * carries no comments to lose, so formatting is the only casualty, and it is
 * worth the certainty that the result parses.
 */
public final class ProfileConfigFile {

    private ProfileConfigFile() {
    }

    /** @param members the group's contents after the change, for reporting */
    public sealed interface Result {

        record Changed(String group, List<String> members, List<String> affected) implements Result {
        }

        /** Nothing to do — every id was already in (or already absent from) the group. */
        record Unchanged(String group, List<String> members) implements Result {
        }

        record Failed(String detail) implements Result {
        }
    }

    /** Adds mod ids to a group, creating the group when it does not exist yet. */
    public static Result addToGroup(Path stateDir, String group, List<String> modIds) {
        return edit(stateDir, group, modIds, true);
    }

    public static Result removeFromGroup(Path stateDir, String group, List<String> modIds) {
        return edit(stateDir, group, modIds, false);
    }

    private static Result edit(Path stateDir, String groupName, List<String> modIds, boolean adding) {
        Path file = ProfileConfig.fileIn(stateDir);
        String group = ProfileConfig.normalise(groupName);
        List<String> ids = Profile.clean(modIds);

        if (group == null || group.isBlank()) {
            return new Result.Failed("no group named");
        }
        if (ids.isEmpty()) {
            return new Result.Failed("no mod ids given");
        }

        JsonObject document;
        try {
            document = read(file);
        } catch (IOException | JsonParseException e) {
            return new Result.Failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        JsonObject groups = document.getAsJsonObject("groups");
        if (groups == null) {
            if (!adding) {
                return new Result.Failed("this config defines no groups");
            }
            groups = new JsonObject();
            document.add("groups", groups);
        }

        if (!adding && !groups.has(group)) {
            return new Result.Failed("no group named '" + group + "'");
        }

        List<String> members = membersOf(groups, group);
        List<String> affected = new ArrayList<>();

        for (String id : ids) {
            if (adding && !members.contains(id)) {
                members.add(id);
                affected.add(id);
            } else if (!adding && members.remove(id)) {
                affected.add(id);
            }
        }

        if (affected.isEmpty()) {
            return new Result.Unchanged(group, List.copyOf(members));
        }

        JsonArray updated = new JsonArray();
        members.forEach(updated::add);
        groups.add(group, updated);

        try {
            write(file, document);
        } catch (IOException e) {
            return new Result.Failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }

        return new Result.Changed(group, List.copyOf(members), List.copyOf(affected));
    }

    /**
     * A group's mod ids, normalised. A member that is not a string — which only a
     * hand-edit can produce — is dropped rather than written back unchanged, since
     * {@link ProfileConfig} would ignore it anyway.
     */
    private static List<String> membersOf(JsonObject groups, String group) {
        List<String> members = new ArrayList<>();
        JsonElement existing = groups.get(group);

        if (existing != null && existing.isJsonArray()) {
            for (JsonElement element : existing.getAsJsonArray()) {
                if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                    String id = ProfileConfig.normalise(element.getAsString());
                    if (id != null && !id.isBlank() && !members.contains(id)) {
                        members.add(id);
                    }
                }
            }
        }

        return members;
    }

    private static JsonObject read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("there is no " + ProfileConfig.FILE
                    + " yet — run 'modupdater profile enable' first");
        }

        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            throw new IOException(ProfileConfig.FILE + " is not a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    /** Through a temporary file, so an interrupted write cannot truncate the config. */
    private static void write(Path file, JsonObject document) throws IOException {
        Files.createDirectories(file.getParent());

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary,
                new GsonBuilder().setPrettyPrinting().create().toJson(document) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        try {
            Files.move(temporary, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
