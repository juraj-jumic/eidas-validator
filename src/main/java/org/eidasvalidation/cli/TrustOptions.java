package org.eidasvalidation.cli;

import org.eidasvalidation.service.ValidatorOptions;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Trust and network options shared by the validate and serve commands. */
public final class TrustOptions {

    @Option(names = "--trusted-lists", negatable = true, defaultValue = "true", fallbackValue = "true",
            description = "Use the EU trusted lists (LOTL) as trust anchors (default: on; --no-trusted-lists to skip).")
    boolean trustedLists;

    @Option(names = "--offline", description = "Never use the network: trusted lists from cache only, no revocation lookups.")
    boolean offline;

    @Option(names = "--cache-dir", paramLabel = "<dir>",
            description = "Where downloaded trusted lists are kept (default: ~/.eidas-validator/tl-cache).")
    Path cacheDir = ValidatorOptions.defaultCacheDir();

    @Option(names = "--tl-max-age", paramLabel = "<hours>", defaultValue = "24",
            description = "Re-download trusted lists older than this (default: 24).")
    long tlMaxAgeHours;

    @Option(names = "--lotl-signers", paramLabel = "<file>",
            description = "PEM bundle with the LOTL signing certificates from the Official Journal "
                    + "(default: the bundled copy).")
    Path lotlSigners;

    @Option(names = "--trust", paramLabel = "<cert>",
            description = "Additional certificate file (PEM or DER) to accept as a trust anchor. Repeatable.")
    List<Path> trustAnchors = new ArrayList<>();

    ValidatorOptions toOptions() {
        return new ValidatorOptions(trustedLists, offline, cacheDir, Duration.ofHours(tlMaxAgeHours), lotlSigners,
                List.copyOf(trustAnchors));
    }
}
