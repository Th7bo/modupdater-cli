package dev.th7bo.modupdater.update;

/**
 * A published release, and where to get it.
 *
 * @param version     the tag, parsed for comparison
 * @param tag         the tag as GitHub spells it, for showing the user
 * @param downloadUrl the installer zip
 * @param size        its size in bytes, or 0 when the API did not say
 */
public record Release(Version version, String tag, String downloadUrl, long size) {

    /** The asset every release carries; the same one the bootstrap scripts fetch. */
    public static final String ASSET = "modupdater-installer.zip";
}
