# Open-source signature validator: project brief

Oct 11, 2026 · @Juraj Jumic

## How to use this brief

Copy this doc, or export it as Markdown, into a new chat that is not incognito, ideally inside a Project so it persists. Facts are as of 11 October 2026; items marked **To verify** were not checked at the source.

Suggested first message in the new session:

> I'm starting an open-source, MIT-licensed validator for qualified electronic signatures (eIDAS), described in the brief below. Treat the brief as the current plan. Help me run milestone M0 first: compare existing validators, choose the engine, and set up the repository. Check every item marked To verify before relying on it.

## Goal and decisions so far

The goal is a free, self-hostable tool that tells ordinary people, in plain language, whether a signed PDF carries a valid and qualified electronic signature under EU rules. The longer aim is a free, qualified-signature-first way to request and collect signatures, but the validator comes first.

| Decision | Status | Reason |
| --- | --- | --- |
| Start with a read-only validator, then signing | Decided | No QTSP needed, lower risk, reused by every later flow |
| License: MIT (most permissive) | Decided | Maximum reuse; see the dependency caution below |
| Never issue certificates | Decided | Needs a qualified trust service provider, certified devices and audits; the tool sits on top of providers |
| Do not claim to be a "qualified validation service" | Decided | That is a regulated trust service; the tool is informational |
| Build on an existing engine, do not write validation logic from scratch | Decided | Subtle errors are the main risk |
| Engine: EU DSS (Java) | Decided 11 Oct 2026 | Java stack preferred; DSS 6.5 is the reference eIDAS engine. pyHanko (MIT) was the alternative and is kept in mind only as a cross-check engine |
| Contribute upstream vs. separate project | Open | Decide after evaluating the EUDI reference signing components |
| Repository: github.com/juraj-jumic/eidas-validator, public | Decided 11 Oct 2026 | CI on JDK 17 and 21 |
| LOTL signing certificates: bundled copy taken from the DSS demo keystore | Provisional | Fingerprints recorded in `src/main/resources/lotl-signers.pem`; **To verify** against OJ C/2026/1944 (the LOTL now announces that notice) before the first release |

License caution (not legal advice): DSS is LGPL 2.1, so an MIT project can depend on it as an unmodified, replaceable library, but must pass on the LGPL notices, and any changes to DSS itself must be shared under the LGPL. A pyHanko-based engine may keep the whole stack permissive. **To verify**: pyHanko's license and every transitive dependency's license before the first release.

## What exists and where the gap is

Validation engines are mature; plain-language, privacy-first, free validation for the public is the open space. No open-source project issues qualified certificates, so every QES setup needs a qualified trust service provider.

| Project | What it is | Relevance |
| --- | --- | --- |
| [EU DSS](https://ec.europa.eu/digital-building-blocks/sites/x/V4FwJ) | Java library, LGPL 2.1, creates and validates PAdES, XAdES, CAdES, ASiC; follows eIDAS closely; has demo apps | Leading engine candidate |
| [SiVa (Estonia)](https://github.com/open-eid/SiVa/commit/bdecf3b228067b66974f7fba29aab12fc6dfcc0f) | Validation service using a DSS fork, with separate policies for qualified and advanced signatures | Reference for policy design |
| pyHanko | Open-source Python library for PDF signing and validation (named in a third-party FAQ) | Alternative engine; **To verify** license and qualified-status handling |
| [DocuSeal](https://www.docuseal.com/qualified-electronic-signature) | Open-source signing workflow; its site lists QES as a paid per-signature feature | Shows the gap on the free side |
| Documenso | Open-source workflow; an old community thread listed QES as not supported | **To verify** current state |
| LibreSign, OpenSign | Open-source workflows (LibreSign runs inside Nextcloud) | Possible upstream homes later |

A comparison of open-source e-signature APIs ([esign.ai](https://www.esign.ai/blog/open-source-e-signature-api)) confirms that qualified signatures always require integrating a qualified trust service provider.

Gap hypothesis to test in M0: existing validators are accurate but technical, and the polished tools are paid or cloud-only. If M0 shows existing validators already explain results well, rethink the scope.

## EUDI wallet status for signing (October 2026)

The law is fixed, but the signing interfaces are still moving, so the wallet flow stays out of the validator and sits behind an adapter later.

**Stable**

- Each Member State must offer a wallet by around the end of December 2026 (one source says 24 December, another 26 November). Wallets must let users sign with qualified signatures, free for natural persons at least for non-professional use ([Garrigues](https://www.garrigues.com/en_GB/garrigues-digital/european-digital-identity-wallet-now-has-timetable-tasks-be-completed-december)).
- PAdES is the mandatory signature format. The signature creation application may be provided by the wallet provider, a trust service provider or a relying party, and may be integrated or external ([ARF topic AB](https://eudi.dev/latest/discussion-topics/ab-digital-signature-using-wallet/)).
- The ARF is at v3.0.0 (21 July 2026) at [eudi.dev](https://eudi.dev/latest/); the repository holds documentation only, licensed CC BY 4.0.

**Still moving**

- Signing is a discussion topic (version 1.1, 16 September 2026), written against ARF 2.8.0 and a draft amendment to the implementing rules. **To verify** whether that amendment is now final.
- The wallet-to-relying-party signature request interface is OID4VP-based "with known limitations" and rests on ETSI TS 119 432; a newer EN 319 432 is in progress. Signature authorization and the on-screen message rendering are open questions.
- The proposal removes mandatory CSC API support for wallets and the wallet multi-signer requirement, so multi-signer flows would be the application's job.

**Reference implementation**: open-source wallet and verifier components exist under the [eu-digital-identity-wallet](https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework) GitHub organization. A secondary source says they include rQES libraries, CSC API components and a relying-party-centric signing application ([wavect](https://wavect.io/de/blog/ai-agent-eidas-signature-integration/)). **To verify** by running them; the project describes itself as a showcase, not a product.

Useful contribution: the signing topic has a public discussion ([#661](https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework/discussions/661)) with open questions on message rendering and one-time certificates, where practitioner input is wanted.

## Validator MVP scope

The MVP validates PAdES signatures in PDF files, gives one clear verdict, and explains it in plain language.

| In the MVP | Later |
| --- | --- |
| PAdES in PDF | XAdES, CAdES, ASiC containers |
| Integrity, certificate chain, revocation, timestamps | Long-term archiving checks beyond B-LTA |
| Qualified status from EU trusted lists (qualified certificate, qualified device) | Seals and other trust services in depth |
| Command line and local web interface | Library and REST API for integrators |
| English first; message catalog ready for translation | Additional languages |
| JSON report plus readable HTML report | Signed or archived validation reports |

**Wording policy.** The verdict should read like "Valid as of \<date/time>. Signed with a qualified certificate listed on the EU trusted lists", never "legally valid". Each result states what was checked, against which trusted list version and when, and what was not checked. Indeterminate results explain what is missing and what the reader can do about it.

**Verdict model.** Follow the standard validation outcomes (passed, failed, indeterminate with sub-reasons) and map each to a short explanation, a severity and a suggested next step. Keep the engine's technical detail one click away, not hidden.

**Accessibility.** The web interface targets WCAG 2.2 AA and screen-reader use from the start; documents that cannot be checked should say so clearly instead of failing silently.

## Architecture

The validator runs entirely on the machine that holds the document; only trusted-list and revocation data crosses the network.

&#91;embedded content: architecture · 4 stages, 3 support parts\]

Read left to right: the parser finds signatures, the engine validates them using cached trust data, and the explanation layer turns standard outcomes into plain-language reports. The guarded fetcher is the only part that talks to the internet.

**Design choices**

- The engine sits behind an interface, so DSS and pyHanko can be swapped or run side by side, and later signing flows reuse the same validation step.
- Command line first, then a local web interface and a container image for self-hosting; a library or REST API comes later.
- Fetching URLs found inside untrusted certificates is a security risk, so the fetcher blocks internal addresses and enforces timeouts and size limits.
- Trusted lists are cached with their issue date, so the tool can work offline and every report says which list version it used.
- The report format is versioned JSON, with the readable HTML report generated from it; messages live in a translatable catalog.

## Test corpus and cross-checking

The corpus matters more than any feature: a validator that wrongly says "valid" is worse than none. Start with 30 to 50 files, each with an expected result recorded in a manifest, and run them in continuous integration.

| Case | How to get it |
| --- | --- |
| Valid qualified signature from several countries and providers | Real documents you are allowed to use, or sample files from providers and national projects |
| Valid advanced (non-qualified) signature | Self-generated with a test certificate authority |
| Altered after signing | Edit a valid file and re-test |
| Expired, revoked, or not-yet-valid certificate | Test certificate authority with controlled dates and revocation |
| Missing timestamp, or timestamp from an untrusted service | Self-generated |
| Unknown trust anchor; incomplete chain | Self-generated |
| Multiple signatures, incremental updates, shadow-attack style edits | Self-generated and published security test cases |
| Malformed or hostile PDFs | Fuzzed inputs; also used for security testing |

**Cross-checks.** For every file, compare the result with the EU DSS demo validator, SiVa and Adobe Reader, and record any disagreement as a finding, not just a bug. Where two engines (DSS and pyHanko) disagree, the case goes into the manifest with the reasoned correct answer.

**Manifest fields (proposal).** File, source and permission, expected verdict, expected signature level, qualified or not, validation date to use, notes. **To verify** that each real-world document may legally be stored in a public repository; otherwise keep those in a private test set and publish only generated cases.

## Milestones and roadmap

Start with M0, the cheapest test of the idea; each gate decides whether to continue. Phases are in order, not to scale, and no dates are assumed.

&#91;embedded content: roadmap · 4 milestones, 4 gates, later work\]

Later work sits behind the same engine interface. Local signing needs real cards and tokens for testing. The provider integration needs a trust service provider willing to give a sandbox, which is a business step, not a coding one. The wallet adapter waits until the signing specifications settle.

## Risks, open questions and monetisation

The biggest risk is a wrong "valid" verdict; the next is maintenance, because standards, trusted lists and wallet specifications keep changing.

**Risks and cautions**

- Correctness: test corpus, two-engine cross-checks, and clear limits on what the tool claims.
- Security: validators fetch revocation and certificate URLs taken from untrusted files, so block requests to internal networks, set timeouts and size limits, and parse PDFs in a sandbox.
- Legal: a "qualified validation service" is a regulated trust service; this tool is informational. Paid use needs proper terms and probably insurance. Not legal advice; get a review before offering anything paid.
- Privacy: documents must not leave the user's machine by default.
- Sustainability: name a maintainer and a second reviewer before the first release.

**Open questions**

- Engine choice (DSS or pyHanko), and implementation language.
- Project name, repository home, and governance.
- First countries and providers to cover in the corpus.
- Whether to contribute upstream after evaluating the EUDI reference signing components.
- Which team size and time budget apply (not yet known).

**Monetisation options** (free for individuals stays the default)

- Support and integration work for organizations adopting qualified signatures; the most realistic early income.
- Hosted service with local or hash-only processing; paid bulk validation, audit logs, archiving reports.
- Dual licensing needs the copyright held by one party, which conflicts with a plain MIT release; decide before accepting outside contributions.
- Partnerships with qualified trust service providers; disclose them openly.
- Grants: NLnet's NGI programs and the Sovereign Tech Agency. **To verify** their current calls.

## Sources

Pages opened in this session unless marked otherwise. Search-only items were read as snippets and need checking at the source.

| Source | Used for | Checked |
| --- | --- | --- |
| [EU DSS overview](https://ec.europa.eu/digital-building-blocks/sites/x/V4FwJ) | Engine capabilities, LGPL 2.1 | Search snippet |
| [SiVa validation policy commit](https://github.com/open-eid/SiVa/commit/bdecf3b228067b66974f7fba29aab12fc6dfcc0f) | Qualified vs advanced policies | Search snippet |
| [ARF at eudi.dev (v3.0.0)](https://eudi.dev/latest/) | Framework status and release date | Opened |
| [ARF topic AB, signing with the wallet](https://eudi.dev/latest/discussion-topics/ab-digital-signature-using-wallet/) | Legal requirements, open signing questions | Opened |
| [ARF GitHub repository](https://github.com/eu-digital-identity-wallet/eudi-doc-architecture-and-reference-framework) | What the repository contains, licence | Opened |
| [EUDI technical specifications page](https://ec.europa.eu/digital-building-blocks/sites/spaces/EUDIGITALIDENTITYWALLET/pages/869793973/Technical+Specifications) | Reference implementation overview | Opened |
| [Garrigues on the wallet timetable](https://www.garrigues.com/en_GB/garrigues-digital/european-digital-identity-wallet-now-has-timetable-tasks-be-completed-december) | Deadline, free QES, 2026 regulations | Search snippet |
| [ETSI TS 119 432 entry](https://github.com/eu-digital-identity-wallet/eudi-doc-standards-and-technical-specifications/issues/68) | Remote signing protocol spec | Search snippet |
| [Reference implementation roadmap #58](https://github.com/eu-digital-identity-wallet/eudi-wallet-reference-implementation-roadmap/discussions/58) | rQES flow status in early 2025 | Search snippet |
| [wavect blog on QES integration](https://wavect.io/de/blog/ai-agent-eidas-signature-integration/) | Reference implementation contents (secondary source) | Search snippet |
| [German wallet architecture concept, QES chapter](https://bmi.usercontent.opencode.de/eudi-wallet/eidas-2.0-architekturkonzept/functions/07-qes) | Roles and message flows | Search snippet |
| [DocuSeal QES page](https://www.docuseal.com/qualified-electronic-signature) | Paid QES in an open-source workflow tool | Search snippet |
| [Open-source e-signature API comparison](https://www.esign.ai/blog/open-source-e-signature-api) | QTSP requirement, project landscape | Search snippet |
| [Tom's Hardware on open-source Office clones](https://www.tomshardware.com/software/microsoft-office/creator-of-free-adobe-clones-unveils-open-source-microsoft-office-excel-powerpoint-and-word-replications-autodesk-tools-and-other-subscription-based-services-may-come-next) | Origin of the idea | Opened |

Figures about effort and timing in this brief are rough estimates, not sourced.
