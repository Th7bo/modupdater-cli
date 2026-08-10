package dev.th7bo.modupdater.manifest;

/**
 * Every way fetching the manifest can end. Modelled as data rather than
 * exceptions so the caller is forced to decide what each one means for the
 * launch — and so none of them can escape as a stack trace and produce a
 * non-zero exit code (Phase 6 Task 7).
 */
public sealed interface FetchResult {

    record Ok(Manifest manifest) implements FetchResult {
    }

    /** Token rejected. */
    record Unauthorized() implements FetchResult {
    }

    /** Server reachable but the manifest endpoint is not configured (HTTP 503). */
    record Unavailable() implements FetchResult {
    }

    /** Network failure, timeout, or an unexpected status code. */
    record Unreachable(String detail) implements FetchResult {
    }

    /** Reached the server, but the body was not a manifest. */
    record Malformed(String detail) implements FetchResult {
    }

    default String describe() {
        return switch (this) {
            case Ok ok -> "manifest with " + ok.manifest().mods().size() + " mod(s)";
            case Unauthorized ignored -> "server rejected the token (401)";
            case Unavailable ignored -> "server has no manifest token configured (503)";
            case Unreachable u -> "could not reach the server: " + u.detail();
            case Malformed m -> "server returned an unreadable manifest: " + m.detail();
        };
    }
}
