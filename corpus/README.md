# Test corpus

Every file here has an expected result recorded in `manifest.json`, and CI runs the validator
against all of them. A wrong "valid" is the worst outcome, so the corpus matters more than any
feature.

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
| `offlineExpected` | the outcome without network access, when it differs |
| `expected.indication` | `TOTAL_PASSED`, `TOTAL_FAILED`, `INDETERMINATE` |
| `expected.subIndication` | ETSI sub-indication, or null |
| `expected.level` | e.g. `PAdES-BASELINE-B` |
| `expected.qualified` | `true`, `false`, or `unknown` when no trusted list applies |
| `crossChecks` | results from DSS demo, SiVa, Adobe Reader; disagreements are findings |
| `notes` | why the expected answer is the right one |
