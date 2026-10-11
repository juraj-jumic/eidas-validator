package org.eidasvalidation.service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Trust and network configuration shared by the CLI and the local web interface.
 *
 * @param trustedLists use the EU trusted lists as trust anchors
 * @param offline      never touch the network: lists from the cache only, no revocation lookups
 * @param cacheDir     where trusted lists are cached
 * @param tlMaxAge     re-download lists older than this
 * @param lotlSigners  PEM bundle with the LOTL signing certificates, or null for the bundled copy
 * @param trustAnchors additional certificate files accepted as trust anchors
 */
public record ValidatorOptions(boolean trustedLists,
                               boolean offline,
                               Path cacheDir,
                               Duration tlMaxAge,
                               Path lotlSigners,
                               List<Path> trustAnchors) {

    public static Path defaultCacheDir() {
        return Path.of(System.getProperty("user.home"), ".eidas-validator", "tl-cache");
    }

    public static ValidatorOptions defaults() {
        return new ValidatorOptions(true, false, defaultCacheDir(), Duration.ofHours(24), null, List.of());
    }

    /** No trusted lists, no network: integrity and chain checks against the given anchors only. */
    public static ValidatorOptions anchorsOnly(List<Path> anchors) {
        return new ValidatorOptions(false, true, defaultCacheDir(), Duration.ofHours(24), null, List.copyOf(anchors));
    }
}
