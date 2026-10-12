# Test corpus

Every file here has an expected result recorded in `manifest.json`, and CI runs the validator
against all of them. A wrong "valid" is the worst outcome, so the corpus matters more than any
feature.

## Running it

```bash
java -jar target/eidas-validator.jar corpus corpus/manifest.json --mode no-trusted-lists
```

Modes: `full` honours each case's declared setup (trusted lists, online revocation);
`offline` loads trusted lists from the local cache and never touches the network;
`no-trusted-lists` uses only the anchor files named in the manifest, so it is deterministic
and runs on every `mvn test` and in CI. A case whose outcome legitimately differs by mode
carries `offlineExpected` and `noTrustedListsExpected` overrides; a case that has no
expectation for the current mode is reported as skipped, never as passed. Exit code 1 on any
mismatch.

Real-world documents go in `corpus/private/` (git-ignored) unless their licence allows
redistribution. Generated cases live in `corpus/generated/` and are produced by the test
suite's signer. Regenerate them from the project root with:

```bash
mvn -q test-compile exec:java -Dexec.mainClass=org.eidasvalidation.engine.dss.GenerateCorpus -Dexec.classpathScope=test
```

(In PowerShell, quote the `-D` arguments: `"-Dexec.mainClass=..."`.)

The throwaway certificate (`generated/test-signer.pem`) is valid for one year from generation.

## Public real-world files

`corpus/public/` holds real signed documents whose licence allows redistribution; the manifest
records the source URL and the legal basis for each. Current contents: authentic editions of the
Austrian Federal Law Gazette from RIS (public domain under § 7 UrhG), signed with the Federal
Chancellery's Amtssignatur.

Sources tried and rejected: EUR-Lex Official Journal PDFs are authenticated with a *detached*
XAdES signature checked by CheckLex, not with a signature inside the PDF, so they are a case for
the XAdES phase, not for PAdES. Legilux (Luxembourg) blocks scripted downloads.

## Manifest fields

| Field | Meaning |
| --- | --- |
| `file` | path relative to `corpus/` |
| `source` | where the file came from and who allowed its use |
| `validationTime` | ISO instant the expected result refers to |
| `trustAnchors` | certificate files (relative to `corpus/`) accepted as trust anchors, or `"EU trusted lists"`; empty means none |
| `online` | whether revocation lookups were allowed for the expected result |
| `offlineExpected` | the outcome without network access, when it differs (fields override `expected`) |
| `noTrustedListsExpected` | the outcome with anchor files only, when the case normally relies on the trusted lists |
| `expected.indication` | `TOTAL_PASSED`, `TOTAL_FAILED`, `INDETERMINATE` |
| `expected.subIndication` | ETSI sub-indication, or null |
| `expected.level` | e.g. `PAdES-BASELINE-B` |
| `expected.qualified` | `true`, `false`, or `unknown` when no trusted list applies |
| `crossChecks` | results from DSS demo, SiVa, Adobe Reader; disagreements are findings |
| `notes` | why the expected answer is the right one |
