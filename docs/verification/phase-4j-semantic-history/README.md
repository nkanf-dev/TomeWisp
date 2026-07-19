# Phase 4J semantic history verification

Date: 2026-07-19 (Asia/Shanghai)

This report retains deterministic evidence for semantic messages, windowed
history, presentation settings, loader parity, and package contents. It is not
graphical acceptance; the consolidated Fabric/NeoForge real-client run is
tracked separately by Phase 4 Task 9.

## Deterministic scale fixture

`Phase4SemanticHistoryScaleTest` generated 10,000 completed requests with
30,000 timeline rows. Assistant rows reused four immutable semantic documents
covering headings, paragraphs, emphasis, lists, quotes, tables, and code;
tool rows preserved normal chronology. These counts are test evidence, not
product caps.

Observed on this Mac during the clean gate:

| observation | result |
| --- | ---: |
| metadata request bodies materialized | 0 |
| newest-page request objects | 9 |
| budgeted context message objects | 5 |
| visible virtual rows with overscan | 23 |
| initial SQLite fixture write | 2784 ms |
| metadata-only read | 13 ms |
| viewport page read | 9 ms |
| context read | 4 ms |
| one semantic timeline-row update | 3 ms |
| 30,000-row virtual index + visible lookup | 7 ms |

The same test verifies that the context estimate stays within the supplied
model budget, an unrelated request and timeline row retain their SQLite row
identities after the targeted update, the total timeline count remains 30,000,
only the visible neighborhood is returned, and the anchor restores to the same
pixel offset.

## Clean product gate

The following completed successfully:

```text
./gradlew clean :common:test :fabric:build :neoforge:build
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
scripts/verify-phase4-package.sh
git diff --check
```

The clean build ran 426 tests across 135 JUnit result files and completed in
1 minute 18 seconds. Fabric and NeoForge both compiled the same common semantic,
history, settings, and protocol code. Architecture tests additionally assert:

- common production code imports neither Fabric nor NeoForge APIs;
- both client entrypoints construct one display/settings/history runtime and
  close GuideService, ordered history, then settings in the same order;
- both server bridges carry protocol-5 context-window, output-reserve, and
  canonical-model fields;
- both loader builds declare the same pinned SQLite and CommonMark modules.

Warnings were limited to existing Javadoc parameter warnings, NeoForge's
compile-time optional Fabric annotation visibility warnings, Gradle deprecation
notices, SQLite/JOML Java native/unsafe notices, and Fabric Loom's warning that
SQLite's four-part version is not SemVer. None failed compilation, tests, or
packaging.

## Package audit

Both production JARs target Minecraft 26.2 and Java 25. Fabric declares
environment `*`; NeoForge declares side `BOTH`. Each contains the common
history/semantic classes plus its loader-appropriate nested copies of:

- `sqlite-jdbc-3.50.3.0.jar`
- `commonmark-0.28.0.jar`
- `commonmark-ext-gfm-tables-0.28.0.jar`

Artifacts from the clean gate:

| artifact | SHA-256 |
| --- | --- |
| `tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar` | `1caf3d72298375bbe5e5c0caddea49c0ade488b0cd1d6f285eba8f486ea8fb5e` |
| `tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar` | `45f879b635ee2d23ad86530c069f9d5a83c20ef1d4f9771685be5bb936199995` |

The package verifier also parsed the shipped Fabric and localization JSON,
confirmed history schema 4 with no migration implementation, and found no
tracked generic API-key/Bearer literal pattern. No credentials, provider
bodies, transcripts, actor/scope values, paths, or component payloads are
retained in this report.
