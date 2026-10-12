# Changelog

Notable changes to this project are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); version numbers follow semantic
versioning. Each version has a GitHub release with `eidas-validator-<version>.zip` (the jar
and its libraries) and a container image `ghcr.io/juraj-jumic/eidas-validator:<version>`.

## [0.2.0] - 2026-10-12

### Fixed

- PDFs with embedded revocation data (a DSS dictionary, as in PAdES-LT and LTA documents) made
  the validator fail with "No implementation found for ICRLUtils": the build lacked DSS's CRL
  parser module. `dss-crl-parser-x509crl` is now included; the German gazette sample in the
  corpus guards against a regression.

### Added

- Two real qualified samples in the public corpus: the German Federal Law Gazette 2026 I Nr. 1
  (qualified electronic seal, PAdES-BASELINE-LT) and the Polish Journal of Laws 2025 poz. 1
  (qualified electronic signature).

### Changed

- The README leads with the release zip and `java -jar`; building from source and the
  container image are documented at the end, the container marked optional.

## [0.1.0] - 2026-10-12

First release.

### Added

- Validation of PAdES signatures in PDF files with EU DSS 6.5: integrity, certificate chain,
  revocation and the eIDAS qualification of each signature (qualified signature or seal,
  advanced signature with a qualified certificate, advanced signature), following
  ETSI EN 319 102-1 for validation and ETSI TS 119 615 for qualification.
- EU trusted lists: the list of trusted lists (LOTL) and the national lists it points to,
  verified against the LOTL signing certificates announced in Official Journal notice
  C/2026/1944, cached on disk and usable offline. Every report names the LOTL sequence
  number and time it relied on.
- Plain-language explanations of every outcome in English, German and Croatian, selected
  with `--lang` on the command line and `Accept-Language` in the web interface.
- Command line: text or JSON report (report version 4), exit codes 0 to 4, self-contained
  HTML report (`--html`), engine report (`--engine-report`), validation at a chosen time
  (`--at`), console output in the console's own code page.
- Local web interface (`serve`): loopback only by default, Host and Origin checks, 50 MB
  upload cap, strict Content-Security-Policy, no JavaScript; `--bind` and `--allow-host`
  for deployments behind an authenticating reverse proxy.
- Guarded network access: one fetcher for every outbound connection, with address vetting
  (no loopback, private, link-local, multicast or reserved targets), DNS pinning, redirect
  vetting, timeouts and size caps.
- Sandboxed parsing: documents are parsed in a supervised worker JVM with a heap cap, a time
  limit and automatic replacement after a hang or crash; on by default in the web
  interface, `--sandbox` on the command line.
- Test corpus with a manifest-driven runner (`corpus`), run in CI in a deterministic mode on
  every build and against the live trusted lists on pushes to `main`.
- Container image: non-root user, read-only root filesystem, health check;
  `docker compose up -d` serves the web interface.
- Reader's guide to electronic signatures with infographics
  (`docs/how-electronic-signatures-work.md`).

### Known limitations

- The public test corpus has no qualified-signature sample yet; the Austrian federal gazette
  samples are valid but not qualified.
- The Croatian wording awaits native proofreading.
- This is an informational tool, not a qualified validation service in the sense of eIDAS.

[0.2.0]: https://github.com/juraj-jumic/eidas-validator/releases/tag/v0.2.0
[0.1.0]: https://github.com/juraj-jumic/eidas-validator/releases/tag/v0.1.0
