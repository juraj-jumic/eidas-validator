# eIDAS signature validator

[![CI](https://github.com/juraj-jumic/eidas-validator/actions/workflows/ci.yml/badge.svg)](https://github.com/juraj-jumic/eidas-validator/actions/workflows/ci.yml)

A free, self-hostable tool that tells ordinary people, in plain language, whether a signed PDF
carries a valid, and qualified, electronic signature under EU rules (eIDAS).

**Status: milestone M1, trusted lists.** It validates PAdES signatures with [EU DSS](https://github.com/esig/dss),
loads the EU list of trusted lists (LOTL) and the national lists it points to, determines the
eIDAS qualification of each signature, and explains the outcome. Every report names the LOTL
sequence number, issue date and synchronisation time it relied on.

This tool is informational. It is not a qualified validation service in the sense of eIDAS,
and its output is not legal advice.

## Build and run

Requires JDK 17+ and Maven.

```bash
mvn package
```

```bash
java -jar target/eidas-validator.jar path/to/signed.pdf
```

Options:

| Option | Meaning |
| --- | --- |
| `--at <ISO instant>` | Validate as of this time instead of now (reproducible results) |
| `--no-trusted-lists` | Skip the EU trusted lists; integrity and chain checks against `--trust` anchors only |
| `--offline` | No network at all: trusted lists from the cache, no revocation lookups |
| `--online` | Allow CRL, OCSP and AIA fetches for the signer's chain (off by default) |
| `--cache-dir <dir>` | Trusted-list cache (default `~/.eidas-validator/tl-cache`) |
| `--tl-max-age <hours>` | Re-download lists older than this (default 24) |
| `--lotl-signers <file>` | Override the bundled LOTL signing certificates |
| `--trust <cert>` | Accept an extra PEM/DER certificate as a trust anchor (repeatable) |
| `--json` | Machine-readable report instead of text |
| `--engine-report <file>` | Save the DSS simple report XML for the technical view |

Exit codes: `0` valid, `1` invalid, `2` indeterminate, `3` no signature, `4` could not validate.

## Trust data

The first run downloads the LOTL from `https://ec.europa.eu/tools/lotl/eu-lotl.xml` and the
national trusted lists (a few megabytes, roughly a minute). They are cached on disk, so later
runs are fast and `--offline` works from the cache.

The LOTL's own signature is verified against the certificates the European Commission announced
in the Official Journal; a copy ships in the jar as `lotl-signers.pem` (see
`src/main/resources/lotl-signers.pem` for its provenance). A list whose signature does not
verify is rejected; an expired list is used but flagged in the report. Only trusted-list and
revocation data ever cross the network; the document stays on your machine.

The online trusted-list test is opt-in: `EIDAS_TL_ONLINE=true mvn test`.

**Guarded fetching.** A validator follows URLs found inside the document's certificates (CRL
distribution points, OCSP responders, AIA issuer links), which an attacker controls. All network
access, including trusted-list downloads, goes through one guarded fetcher
(`org.eidasvalidation.net.GuardedFetcher`) that allows only `http`/`https`, refuses loopback,
private, link-local (cloud metadata), multicast and reserved addresses, pins each connection to
the vetted address so a DNS rebinding cannot bypass the check, vets redirect targets the same
way (max 3), and enforces connect/read/total timeouts and a byte limit (4 MB for revocation data,
32 MB for trusted lists). Nothing is fetched at all without `--online` or the trusted-list refresh.

**TLS to the list servers.** National lists are served from hosts whose roots (HARICA, FNMT-RCM,
Certum, Microsec, GlobalSign R46, ...) are missing from older JDK `cacerts` files; an original
JDK 17 GA build fails on several of them with "PKIX path building failed". Use a current JDK
update (17.0.x latest or 21). The tool also enables AIA fetching of missing intermediates and, on
Windows, uses the operating system's root store unless `javax.net.ssl.trustStoreType` is already
set. A list that cannot be fetched is named in the report's trust notes, not silently skipped.

## Layout

```
src/main/java/org/eidasvalidation/
  engine/        engine-neutral request, result and ValidationEngine interface
  engine/dss/    EU DSS implementation
  explain/       maps ETSI indications and qualifications to plain language
  cli/           command line
src/test/java/   generates its own signed PDFs with a throwaway certificate
corpus/          test corpus manifest (see corpus/README.md)
docs/            project brief and design notes
```

## Licence

MIT. EU DSS is LGPL-2.1 and is used unmodified as a library; see [NOTICE](NOTICE).
