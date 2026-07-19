# OpenAllay Cutover and Release Automation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Perform the coordinated pre-release TomeWisp-to-OpenAllay identity cutover, replace the root documentation with player-facing English and Chinese READMEs, and add quality plus tag-release GitHub Actions.

**Architecture:** Treat the rename as one destructive pre-release namespace cutover with no aliases or data migration. Preserve behavior while moving Java packages, Minecraft resources, runtime paths, developer interfaces, and both loader adapters in lockstep. Keep CI read-only and reproducible; let tag releases build verified production jars, derive notes from Git history and GitHub contributor metadata, and publish the same Fabric/NeoForge artifacts to Modrinth. SemVer prerelease tags such as `v0.1.0-SNAPSHOT` produce GitHub prereleases and Modrinth alpha versions.

**Tech Stack:** Java 25, Gradle 9 wrapper, Fabric Loom, NeoForge ModDevGradle, GitHub Actions, GitHub CLI/API, Bash, Python 3 standard library.

---

### Task 1: Freeze the pre-cutover baseline and add rename contract tests

**Files:**
- Create: `common/src/test/java/dev/openallay/OpenAllayIdentityTest.java`
- Modify: `docs/superpowers/specs/2026-07-19-openallay-rename-design.md`

- [ ] **Step 1: Record the passing pre-cutover baseline**

Run:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
./scripts/verify-phase4-package.sh
./scripts/verify-sqlite-packaging.sh
```

Expected: all commands pass before identity files are moved.

- [ ] **Step 2: Add identity assertions**

Create `OpenAllayIdentityTest` that reads the staged loader metadata and
resource roots, asserts Mod ID `openallay`, Java package `dev.openallay`, and
rejects current runtime resource paths under `assets/tomewisp` or
`data/tomewisp`.

- [ ] **Step 3: Run the identity test before the cutover**

Run:

```bash
./gradlew :common:test --tests dev.openallay.OpenAllayIdentityTest
```

Expected: compilation or assertions fail until Tasks 2 and 3 finish.

### Task 2: Rename build identity, Java packages, and loader entrypoints

**Files:**
- Modify: `settings.gradle`
- Modify: `gradle.properties`
- Modify: `build.gradle`
- Modify: `build-logic/src/main/groovy/multiloader-common.gradle`
- Move: `common/src/main/java/dev/tomewisp/` to `common/src/main/java/dev/openallay/`
- Move: `common/src/test/java/dev/tomewisp/` to `common/src/test/java/dev/openallay/`
- Move: `fabric/src/main/java/dev/tomewisp/` to `fabric/src/main/java/dev/openallay/`
- Move: `neoforge/src/main/java/dev/tomewisp/` to `neoforge/src/main/java/dev/openallay/`
- Modify: `fabric/src/main/resources/fabric.mod.json`
- Modify: `neoforge/src/main/resources/META-INF/neoforge.mods.toml`

- [ ] **Step 1: Move complete package trees with Git-aware moves**

Use `git mv` for each root tree, then replace `dev.tomewisp` with
`dev.openallay`, `TomeWisp` with `OpenAllay`, and `tomewisp` with `openallay`
inside current Java source and tests. Rename every Java filename whose public
type name changes.

- [ ] **Step 2: Cut over Gradle and loader metadata**

Set root project name `OpenAllay`, group `dev.openallay`, Mod ID `openallay`,
display name `OpenAllay`, and artifact base names `openallay-*`. Point loader
entrypoints and adapter metadata at the renamed classes.

- [ ] **Step 3: Compile both loaders**

Run:

```bash
./gradlew :common:compileJava :common:compileTestJava :fabric:compileJava :neoforge:compileJava
```

Expected: all renamed packages and entrypoints compile.

### Task 3: Rename Minecraft namespaces, resources, storage, and developer interfaces

**Files:**
- Move: `common/src/main/resources/assets/tomewisp/` to `common/src/main/resources/assets/openallay/`
- Move: `common/src/main/resources/data/tomewisp/` to `common/src/main/resources/data/openallay/`
- Modify: `common/src/main/resources/assets/openallay/lang/en_us.json`
- Modify: `common/src/main/resources/assets/openallay/lang/zh_cn.json`
- Modify: `common/src/main/resources/assets/openallay/openallay_skills/**/SKILL.md`
- Modify: `common/src/main/resources/data/openallay/agent_traces/*.json`
- Modify: `scripts/*.sh`
- Modify: `scripts/*.py`
- Modify: `fabric/src/main/resources/fabric.mod.json`
- Modify: `neoforge/src/main/resources/META-INF/neoforge.mods.toml`

- [ ] **Step 1: Move resource roots and bundled Skills**

Rename the asset namespace, data namespace, and `tomewisp_skills` directory to
`openallay_skills`. Replace tool IDs, component fences, translation keys,
trace IDs, payload IDs, capability IDs, and evidence IDs with `openallay:*`.

- [ ] **Step 2: Cut over local state without migration**

Replace every current `config/tomewisp`, `tomewisp/exports`, SQLite application
identifier, system property, and environment-variable prefix with the clean
`openallay` / `OPENALLAY_` identity. Do not add old-path fallback, aliases, or
automatic deletion.

- [ ] **Step 3: Cut over test and developer harnesses**

Rename E2E properties, loopback fixture identifiers, report labels, package
verification variables, and generated jar expectations. Keep the wire schema
version unchanged where structure is unchanged.

- [ ] **Step 4: Run focused resource/protocol/script verification**

Run:

```bash
./gradlew :common:test --tests 'dev.openallay.*ArchitectureTest' --tests 'dev.openallay.bridge.*' --tests 'dev.openallay.skill.*' --tests 'dev.openallay.trace.*'
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
```

Expected: renamed resources and identifiers round-trip and all scripts parse.

### Task 4: Replace repository-facing documentation with the OpenAllay identity

**Files:**
- Modify: `AGENTS.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/isme/decisions/2026-07-19-022-native-domain-views-retrieval-memory.md`
- Modify: `docs/isme/decisions/2026-07-19-023-session-actions-and-safe-export.md`
- Modify: `docs/superpowers/specs/2026-07-19-openallay-rename-design.md`
- Create: `docs/verification/openallay-cutover/README.md`

- [ ] **Step 1: Update active operating and development documentation**

Use `OpenAllay`, `openallay`, `dev.openallay`, and `OPENALLAY_` throughout
current instructions, commands, paths, and accepted decisions.

- [ ] **Step 2: Preserve historical evidence honestly**

Do not rewrite retained reports or screenshots. Add one explicit historical
identity notice to legacy plan/spec/verification index contexts that still use
the old name, and exclude those immutable evidence artifacts from current-name
runtime scans.

- [ ] **Step 3: Mark the rename design implemented**

Change its status from pending to implemented after the full gate, cite the
cutover commits and verification directory, and record that no migration or
compatibility alias was introduced.

### Task 5: Write player-facing English and Chinese READMEs

**Files:**
- Replace: `README.md`
- Create: `README.zh-CN.md`

- [ ] **Step 1: Write the English player README**

Lead with player value, a retained real-client screenshot, pre-release support
badges, installation, first model configuration, example questions, features,
client/server modes, provider protocols, optional integrations, privacy,
limitations, troubleshooting, and developer links. Do not present a download
store or compatibility claim that does not exist.

- [ ] **Step 2: Write the Simplified Chinese mirror**

Mirror the same factual structure and link both files at the top. Keep
`OpenAllay`, `OpenAllay Skills`, and provider/loader names untranslated.

- [ ] **Step 3: Validate README links and product claims**

Check every local link exists and every stated integration/limitation matches
`docs/development.md` and retained runtime evidence.

### Task 6: Add quality CI and tag-driven GitHub Release automation

**Files:**
- Replace: `.github/workflows/build.yml`
- Create: `.github/workflows/release.yml`
- Create: `scripts/generate-release-notes.py`
- Create: `.github/release.yml`

- [ ] **Step 1: Build the quality workflow**

Trigger on pull requests and pushes to `main`/`mc/**`; use read-only contents
permission, pinned major actions, Java 25, Gradle caching, wrapper validation,
script/JSON/rename scans, the clean full gate, package verification, SQLite
packaging verification, and upload only production jars plus checksums.

- [ ] **Step 2: Build the release workflow**

Trigger only on tags matching `v*`; grant `contents: write` only to the release
job, validate the tag matches the Gradle version, rebuild with the clean full
gate, verify both jars, generate SHA-256 checksums, publish the corresponding
Fabric and NeoForge versions to Modrinth, and create an idempotent GitHub
Release with jars and checksums. Tags with a SemVer prerelease suffix remain
explicit prereleases on both destinations.

- [ ] **Step 3: Generate human-readable notes**

The notes script determines the previous reachable tag, lists categorized
commits with links, and queries GitHub's compare endpoint for unique
contributors. Combine this with GitHub's generated release notes so pull
requests, first-time contributors, and full commit history are retained. Do
not require a manually maintained changelog.

- [ ] **Step 4: Validate workflow syntax and dry-run note generation**

Parse both YAML files, run `bash -n`, exercise the notes script against local
history without publishing, and verify workflow permissions and artifact globs.

### Task 7: Complete the cutover verification and commit

**Files:**
- Modify: `docs/verification/openallay-cutover/README.md`

- [ ] **Step 1: Scan current runtime surfaces for old identity**

Run case-sensitive scans over build files, source, active resources, scripts,
workflows, README, AGENTS, and development docs. Any remaining old identity
must be in the approved rename spec or explicitly marked historical evidence.

- [ ] **Step 2: Run the final production gate**

Run:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
./scripts/verify-phase4-package.sh
./scripts/verify-sqlite-packaging.sh
git diff --check
```

Expected: all tests and both loader builds pass; production jars use the
`openallay-*` names and package checks pass.

- [ ] **Step 3: Retain redacted cutover evidence**

Record the commands, test counts, jar hashes, identity scan boundaries, CI
workflow validation, and the explicit absence of migration/aliases. Do not
retain credentials, environment dumps, or runtime configuration.

- [ ] **Step 4: Commit coherent outcomes on `main`**

Commit the coordinated identity cutover and player README together, followed
by a separate CI/release automation commit if that produces a clearer audit
boundary. Never publish a tag or GitHub Release from this implementation run.
