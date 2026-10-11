package org.eidasvalidation.engine.dss;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader;
import eu.europa.esig.dss.model.tsl.LOTLInfo;
import eu.europa.esig.dss.model.tsl.TLInfo;
import eu.europa.esig.dss.model.tsl.TLValidationJobSummary;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.x509.CommonCertificateSource;
import eu.europa.esig.dss.tsl.function.OfficialJournalSchemeInformationURI;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import eu.europa.esig.dss.tsl.sync.ExpirationAndSignatureCheckStrategy;
import org.eidasvalidation.engine.TrustSummary;
import org.eidasvalidation.net.FetchPolicy;
import org.eidasvalidation.net.GuardedFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Loads the EU list of trusted lists (LOTL) and the national trusted lists it points to,
 * through DSS's {@link TLValidationJob}, with an on-disk cache so later runs work offline.
 *
 * <p>The LOTL's own signature is verified against the certificates the European Commission
 * published in the Official Journal; those ship with the tool as {@code lotl-signers.pem} and
 * can be overridden. Lists whose signature does not verify are rejected; expired lists are
 * accepted but reported.
 */
public final class EuTrustedLists {

    private static final Logger LOG = LoggerFactory.getLogger(EuTrustedLists.class);

    /** Official LOTL location, as published by the Commission. */
    public static final String LOTL_URL = "https://ec.europa.eu/tools/lotl/eu-lotl.xml";
    /** Official Journal notice announcing the LOTL signing certificates. */
    public static final String OJ_URL =
            "https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=uriserv:OJ.C_.2019.276.01.0001.01.ENG";
    private static final String BUNDLED_SIGNERS = "/lotl-signers.pem";

    private final Path cacheDirectory;
    private final Duration maxAge;
    private final boolean offline;
    private final List<CertificateToken> lotlSigners;

    private EuTrustedLists(Path cacheDirectory, Duration maxAge, boolean offline, List<CertificateToken> lotlSigners) {
        this.cacheDirectory = cacheDirectory;
        this.maxAge = maxAge;
        this.offline = offline;
        this.lotlSigners = lotlSigners;
    }

    /**
     * @param cacheDirectory where downloaded lists are kept between runs
     * @param maxAge         how old a cached list may be before it is fetched again (ignored when offline)
     * @param offline        never touch the network; use the cache only
     * @param signersFile    PEM/DER file with the LOTL signing certificates, or null for the bundled set
     */
    public static EuTrustedLists configure(Path cacheDirectory, Duration maxAge, boolean offline, Path signersFile)
            throws IOException {
        TlsDefaults.apply();
        List<CertificateToken> signers = signersFile == null ? bundledSigners() : loadSigners(signersFile);
        if (signers.isEmpty()) {
            throw new IOException("No LOTL signing certificates available; cannot verify the list of trusted lists");
        }
        return new EuTrustedLists(Objects.requireNonNull(cacheDirectory), Objects.requireNonNull(maxAge), offline,
                signers);
    }

    /** Result of a load: the certificate source for the engine and the summary for the report. */
    public record Loaded(TrustedListsCertificateSource source, TrustSummary summary) {
    }

    public Loaded load() throws IOException {
        Files.createDirectories(cacheDirectory);

        CommonCertificateSource ojSource = new CommonCertificateSource();
        lotlSigners.forEach(ojSource::addCertificate);

        LOTLSource lotl = new LOTLSource();
        lotl.setUrl(LOTL_URL);
        lotl.setCertificateSource(ojSource);
        lotl.setSigningCertificatesAnnouncementPredicate(new OfficialJournalSchemeInformationURI(OJ_URL));
        lotl.setPivotSupport(true);

        // Cache-only loader: reads what is on disk, never expires it.
        FileCacheDataLoader offlineLoader = new FileCacheDataLoader();
        offlineLoader.setFileCacheDirectory(cacheDirectory.toFile());
        offlineLoader.setCacheExpirationTime(-1);
        offlineLoader.setDataLoader(new IgnoreDataLoader());

        TrustedListsCertificateSource source = new TrustedListsCertificateSource();

        TLValidationJob job = new TLValidationJob();
        job.setListOfTrustedListSources(lotl);
        job.setTrustedListCertificateSource(source);
        job.setOfflineDataLoader(offlineLoader);

        ExpirationAndSignatureCheckStrategy strategy = new ExpirationAndSignatureCheckStrategy();
        strategy.setAcceptInvalidListOfTrustedLists(false);
        strategy.setAcceptInvalidTrustedList(false);
        strategy.setAcceptExpiredListOfTrustedLists(true);
        strategy.setAcceptExpiredTrustedList(true);
        job.setSynchronizationStrategy(strategy);

        job.offlineRefresh();

        if (!offline) {
            // List URLs come from the signature-checked LOTL, so the exposure is smaller than for
            // URLs taken from documents, but the same guard (no internal addresses, timeouts,
            // size limit) applies.
            FileCacheDataLoader onlineLoader = new FileCacheDataLoader();
            onlineLoader.setFileCacheDirectory(cacheDirectory.toFile());
            onlineLoader.setCacheExpirationTime(maxAge.toMillis());
            onlineLoader.setDataLoader(new GuardedFetcher(FetchPolicy.trustedLists()));
            job.setOnlineDataLoader(onlineLoader);
            job.onlineRefresh();
        }

        TrustSummary summary = summarise(job.getSummary(), source, offline);
        LOG.info("Trusted lists: {}", summary.describe());
        return new Loaded(source, summary);
    }

    private static TrustSummary summarise(TLValidationJobSummary jobSummary, TrustedListsCertificateSource source,
                                          boolean offline) {
        List<LOTLInfo> lotls = jobSummary == null ? List.of() : jobSummary.getLOTLInfos();
        if (lotls.isEmpty()) {
            return new TrustSummary("EU trusted lists: not available" + (offline ? " (offline, empty cache)" : ""),
                    null, null, null, null, null, 0, 0, 0,
                    List.of("The list of trusted lists could not be loaded" + (offline ? " from the cache." : ".")));
        }
        LOTLInfo lotl = lotls.get(0);
        List<String> problems = new ArrayList<>();

        boolean lotlValid = lotl.getValidationCacheInfo() != null && lotl.getValidationCacheInfo().isValid();
        if (lotl.getDownloadCacheInfo() != null && lotl.getDownloadCacheInfo().isError()) {
            problems.add("LOTL download: " + lotl.getDownloadCacheInfo().getExceptionMessage());
        }
        if (lotl.getParsingCacheInfo() != null && lotl.getParsingCacheInfo().isError()) {
            problems.add("LOTL parsing: " + lotl.getParsingCacheInfo().getExceptionMessage());
        }
        if (!lotlValid) {
            problems.add("LOTL signature did not verify against the Official Journal certificates");
        }

        int valid = 0;
        List<TLInfo> tls = lotl.getTLInfos();
        for (TLInfo tl : tls) {
            String territory = tl.getParsingCacheInfo() != null && tl.getParsingCacheInfo().getTerritory() != null
                    ? tl.getParsingCacheInfo().getTerritory() : tl.getUrl();
            boolean ok = true;
            if (tl.getDownloadCacheInfo() == null || tl.getDownloadCacheInfo().isError()) {
                problems.add(territory + ": download failed");
                ok = false;
            } else if (tl.getParsingCacheInfo() == null || tl.getParsingCacheInfo().isError()) {
                problems.add(territory + ": could not be parsed");
                ok = false;
            } else if (tl.getValidationCacheInfo() == null || !tl.getValidationCacheInfo().isValid()) {
                problems.add(territory + ": signature did not verify; list rejected");
                ok = false;
            } else {
                Date next = tl.getParsingCacheInfo().getNextUpdateDate();
                if (next != null && next.before(new Date())) {
                    problems.add(territory + ": list expired on " + next.toInstant().toString().substring(0, 10)
                            + "; still used");
                }
            }
            if (ok) {
                valid++;
            }
        }

        Date sync = lotl.getDownloadCacheInfo() == null ? null
                : lotl.getDownloadCacheInfo().getLastSuccessSynchronizationTime();
        var parsing = lotl.getParsingCacheInfo();
        return new TrustSummary(
                "EU trusted lists (LOTL)" + (offline ? ", from cache" : ""),
                sync == null ? null : sync.toInstant(),
                parsing == null ? null : parsing.getSequenceNumber() == null ? null : parsing.getSequenceNumber().longValue(),
                parsing == null || parsing.getIssueDate() == null ? null : parsing.getIssueDate().toInstant(),
                parsing == null || parsing.getNextUpdateDate() == null ? null : parsing.getNextUpdateDate().toInstant(),
                lotlValid,
                tls.size(),
                valid,
                source.getNumberOfCertificates(),
                List.copyOf(problems));
    }

    private static List<CertificateToken> bundledSigners() throws IOException {
        try (InputStream in = EuTrustedLists.class.getResourceAsStream(BUNDLED_SIGNERS)) {
            if (in == null) {
                return List.of();
            }
            return parsePem(new String(in.readAllBytes(), StandardCharsets.US_ASCII));
        }
    }

    private static List<CertificateToken> loadSigners(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        if (text.contains("-----BEGIN CERTIFICATE-----")) {
            return parsePem(text);
        }
        return List.of(DSSUtils.loadCertificate(file.toFile()));
    }

    /** Splits a PEM bundle into certificates; DSS's loader only reads the first entry of a bundle. */
    private static List<CertificateToken> parsePem(String pem) {
        List<CertificateToken> certs = new ArrayList<>();
        String begin = "-----BEGIN CERTIFICATE-----";
        String end = "-----END CERTIFICATE-----";
        int from = 0;
        while (true) {
            int b = pem.indexOf(begin, from);
            if (b < 0) {
                break;
            }
            int e = pem.indexOf(end, b);
            if (e < 0) {
                break;
            }
            String block = pem.substring(b, e + end.length());
            certs.add(DSSUtils.loadCertificate(block.getBytes(StandardCharsets.US_ASCII)));
            from = e + end.length();
        }
        return certs;
    }

    /** Instant helper kept package-private for tests. */
    static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
