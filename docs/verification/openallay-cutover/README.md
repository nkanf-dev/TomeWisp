# OpenAllay 0.1.0-SNAPSHOT release verification

Verification date: 2026-07-20

This report covers the coordinated OpenAllay identity cutover and the Phase 4
release candidate. Historical real-client reports and screenshots remain under
the neighboring Phase 4 verification directories; they were not rewritten for
the product rename.

## Production gate

The following commands completed successfully from the repository root:

```text
./gradlew clean :common:test :fabric:build :neoforge:build
./scripts/verify-phase4-package.sh
./scripts/verify-sqlite-packaging.sh
./scripts/verify-distribution.sh
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
git diff --check
```

The clean common suite executed 625 tests with 0 failures, 0 errors, and 4
explicitly opt-in live tests skipped. Both loader builds completed. The package
verifier found the required Phase 4 runtime surfaces, and the SQLite verifier
loaded SQLite 3.50.3 from both production JARs for Linux x86_64/aarch64, macOS
x86_64/aarch64, and Windows x86_64/aarch64.

## Release artifacts

```text
openallay-fabric-26.2-0.1.0-SNAPSHOT.jar
sha256 571fefaf47f936b08758a5efd75cd2a94b15599deffb65742fbee40fa03ca3af

openallay-neoforge-26.2-0.1.0-SNAPSHOT.jar
sha256 1ee420fa166d401ef123000ddb4ed575d4109aff5924c20bb862fdffe425f9f0
```

## Identity and credential audit

Current build files, production source/resources, scripts, workflows, player
READMEs, `AGENTS.md`, `docs/development.md`, and the SKMB were scanned for the
former identity. The remaining runtime references are only the explicit loader
incompatibility declaration that prevents installing the destructive pre-release
cutover beside the former mod, plus the contract test for that declaration.
There is no old config-path fallback, packet alias, API alias, or durable-data
migration.

Tracked and candidate untracked files were scanned without printing matched
values. No OpenAI-style key, Modrinth PAT, private provider hostname, or live
credential database was present. README local links and images resolved.

## Publication contract

`v0.1.0-SNAPSHOT` is a strict SemVer prerelease tag matching
`gradle.properties`. The tag workflow rebuilds and verifies both JARs, uploads
them as separate Fabric and NeoForge Modrinth alpha versions, creates the
Modrinth project on its first run, generates release notes and checksums, adds
build provenance, and publishes a GitHub prerelease. Modrinth upload is
idempotent per loader and version.

## Publication evidence

- Quality run: <https://github.com/nkanf-dev/OpenAllay/actions/runs/29697399882>
- Release run (attempt 2):
  <https://github.com/nkanf-dev/OpenAllay/actions/runs/29697551720>
- GitHub prerelease:
  <https://github.com/nkanf-dev/OpenAllay/releases/tag/v0.1.0-SNAPSHOT>
- Modrinth project: `vuB8HitN` / `openallay`
- Fabric alpha version: `vHKJrZzd`, Minecraft 26.2
- NeoForge alpha version: `CjNfr7HI`, Minecraft 26.2

The GitHub prerelease is public and contains both JARs, `SHA256SUMS`, and build
provenance. The Modrinth project contains the README banner, generated project
icon, and both loader versions. It was submitted to Modrinth moderation at
2026-07-19T18:00:38Z and was in `processing` state when this report was
captured; the public listing depends on Modrinth completing that review.
