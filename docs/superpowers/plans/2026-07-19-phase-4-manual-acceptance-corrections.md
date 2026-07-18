# Phase 4 Manual-Acceptance Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct the four failures found in TomeWisp's first normal Phase 4 walkthrough: Fabric text input, player-entered stored API keys, Tools/Skills/source settings UX, and pre-release history recovery.

**Architecture:** Keep all product behavior in common code and use existing ordered settings/history owners. Model JSON schema 2 stores only qualified credential references resolved by a dedicated SQLite store; logical Tools own typed source configurations and present separately from external Agent Skills; recognized history schemas 1–3 are transactionally rebuilt. Loader changes are limited to Fabric dependency metadata and the accepted optional-mod development version.

**Tech Stack:** Java 25, Minecraft 26.2 native Screen widgets, Gson strict codecs, SQLite JDBC, Gradle/Loom, JUnit 5, Fabric and NeoForge.

---

### Task 1: Reject the broken Architectury version and rebuild old pre-release history

**Files:**
- Modify: `gradle.properties`
- Modify: `fabric/src/main/resources/fabric.mod.json`
- Modify: `common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java`
- Modify: `common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java`
- Modify: `common/src/test/java/dev/tomewisp/integration/RecipeViewerApiCompatibilityTest.java`

- [x] **Step 1: Write failing history and metadata tests**

Replace the old schema-1 rejection test with parameterized schema 1/2/3 rebuild coverage, retain the schema-99 fail-closed test, and assert the packaged Fabric metadata contains:

```json
"breaks": {
  "architectury": "<=21.0.2"
}
```

- [x] **Step 2: Run the focused tests and confirm they fail**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.guide.history.SqliteGuideHistoryStoreTest' --tests 'dev.tomewisp.integration.RecipeViewerApiCompatibilityTest'
```

Expected: old schema still reports `history_schema_unsupported`, and Fabric metadata lacks the conflict.

- [x] **Step 3: Implement transactional recognized-schema rebuild**

Change `ensureSchema` from static validation to an instance method so it can call one shared reset transaction. Only versions 1, 2, and 3 enter the rebuild branch:

```java
if (version > 0 && version < SCHEMA_VERSION) {
    rebuildRecognizedSchema(connection);
    return;
}
if (version != SCHEMA_VERSION) {
    throw new GuideHistoryException(
            "history_schema_unsupported",
            "Unsupported guide history schema version " + version);
}
```

The helper disables foreign keys before the transaction, drops only names returned by `applicationTables`, recreates schema 4, injects `Mutation.RESET`, commits, and maps rollback failure to `history_schema_rebuild_failed`. Missing/inconsistent metadata, foreign tables, corruption, and future versions remain untouched.

- [x] **Step 4: Upgrade the accepted optional dependency and add the loader conflict**

Set `architectury_version=21.0.4` and add the Fabric `breaks` entry without adding a `depends` entry.

- [x] **Step 5: Run focused tests and commit**

Run the command from Step 2. Expected: PASS.

```bash
git add gradle.properties fabric/src/main/resources/fabric.mod.json common/src/main/java/dev/tomewisp/guide/history/SqliteGuideHistoryStore.java common/src/test/java/dev/tomewisp/guide/history/SqliteGuideHistoryStoreTest.java common/src/test/java/dev/tomewisp/integration/RecipeViewerApiCompatibilityTest.java
git commit -m "fix: recover pre-release history and reject broken input dependency"
```

### Task 2: Add the local credential store and model profile schema 2

**Files:**
- Create: `common/src/main/java/dev/tomewisp/model/config/CredentialReference.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/CredentialResolver.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/LocalCredentialStore.java`
- Create: `common/src/test/java/dev/tomewisp/model/config/LocalCredentialStoreTest.java`
- Modify: `common/src/main/java/dev/tomewisp/model/config/ModelProfileDefinition.java`
- Modify: `common/src/main/java/dev/tomewisp/model/config/ModelProfilesConfig.java`
- Modify: `common/src/main/java/dev/tomewisp/model/config/ModelProfilesConfigLoader.java`
- Modify: `common/src/main/java/dev/tomewisp/model/config/ModelProfilesConfigWriter.java`
- Modify: `common/src/main/java/dev/tomewisp/model/config/ModelProfileSettingsStore.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/model/ModelSettingsBackend.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/model/ModelProfileSettingsView.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsRuntime.java`
- Modify: adjacent model/settings tests

- [x] **Step 1: Write failing credential-store tests**

Cover schema creation, insertion/resolution, deletion, shared-reference retention, unavailable/corrupt store failures, and the absence of raw secrets from `toString`. The API is:

```java
try (LocalCredentialStore credentials = new LocalCredentialStore(path, clock)) {
    CredentialReference ref = credentials.insert(SecretValue.of("secret"));
    assertEquals("secret", credentials.resolve(ref).reveal());
}
```

- [x] **Step 2: Implement qualified references and the SQLite store**

`CredentialReference.parse` accepts only `local:<uuid>` and `env:<valid-name>`. `LocalCredentialStore` owns schema 1, stores UTF-8 secret bytes as a BLOB, configures `WAL` and `synchronous=full`, attempts owner-only POSIX permissions, returns stable `credential_store_unavailable` failures at its public boundary, and never formats a secret.

- [x] **Step 3: Change the credential-free profile contract**

Rename `apiKeyEnv` to `credentialRef`, bump `ModelProfilesConfig.SCHEMA_VERSION` to 2, encode only `credentialRef`, and resolve through `CredentialResolver`:

```java
SecretValue secret = credentials.resolve(CredentialReference.parse(definition.credentialRef()));
```

Retain an explicit schema-1 external import path that converts `apiKeyEnv` to `env:<name>` in memory; every normal save emits schema 2. Inline `apiKey` remains rejected.

- [x] **Step 4: Make profile replacement credential-aware**

Add a save candidate carrying an optional transient replacement key:

```java
public record ModelProfileSave(
        ModelProfilesConfig config,
        String profileId,
        SecretValue replacement) {}
```

Insert a new immutable local row before writing JSON, replace only the selected profile reference in the prepared candidate, publish runtime only after the JSON replacement, then garbage-collect unreferenced local IDs. A failed JSON replacement leaves the previous file/runtime/reference active.

- [x] **Step 5: Wire lifecycle and redacted projections**

Create `credentials.sqlite3` beside `models.json`, pass a composite local/environment resolver through initial load, metadata refresh, save, reload, resolve, and probe, expose only `credentialPresent`, and close the store after ordered settings work drains.

- [x] **Step 6: Run model/settings tests and commit**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.model.config.*' --tests 'dev.tomewisp.settings.model.*' --tests 'dev.tomewisp.settings.ClientSettingsRuntimeTest' --tests 'dev.tomewisp.settings.ClientSettingsServiceTest'
```

Expected: PASS with no secret in JSON, snapshots, diagnostics, or failures.

```bash
git add common/src/main/java/dev/tomewisp/model common/src/main/java/dev/tomewisp/settings common/src/test/java/dev/tomewisp/model common/src/test/java/dev/tomewisp/settings
git commit -m "feat: store client model credentials locally"
```

### Task 3: Replace the environment-name editor with a masked API-key workflow

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/client/gui/settings/ModelProfileDraft.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispSettingsScreen.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/settings/ModelProfileDraftTest.java`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/TomeWispSettingsScreenProjectionTest.java`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/SettingsLocalizationTest.java`

- [x] **Step 1: Write failing draft/projection/localization tests**

Assert the draft retains only `credentialRef` plus a boolean replacement state, the projection never contains an environment name or raw key, and both locales contain labels for “API key”, “not set”, “saved”, and “replace on save”.

- [x] **Step 2: Add a password EditBox**

Replace `apiKeyEnv` with `apiKey`. Never prefill a saved key. Apply a formatter that draws bullets without changing the widget value:

```java
apiKey.addFormatter((text, offset) ->
        FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
```

Set a bounded key length, clear it after save, and show saved/pending state outside the widget. The settings snapshot and `ModelProfileDraft.toString()` must never contain the transient key.

- [x] **Step 3: Pass transient secret only to the save boundary**

Validate ordinary model fields into a definition retaining the current credential reference, convert a nonblank widget value directly to `SecretValue`, call the credential-aware service save overload, and immediately clear the local field/widget after handoff.

- [x] **Step 4: Run GUI/settings tests and commit**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*' --tests 'dev.tomewisp.settings.*'
```

Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/client/gui common/src/main/resources/assets/tomewisp/lang common/src/test/java/dev/tomewisp/client/gui
git commit -m "feat: add masked player API key editor"
```

### Task 4: Define logical Tools and Tool-owned typed sources

**Files:**
- Create: `common/src/main/java/dev/tomewisp/tool/config/ToolFamilyId.java`
- Create: `common/src/main/java/dev/tomewisp/tool/config/ToolSourceDefinition.java`
- Create: `common/src/main/java/dev/tomewisp/tool/config/ToolFamilyConfig.java`
- Create: `common/src/main/java/dev/tomewisp/tool/config/ToolFamilyConfigCodec.java`
- Create: `common/src/main/java/dev/tomewisp/tool/config/ToolSourceKind.java`
- Create: `common/src/main/java/dev/tomewisp/tool/config/ToolSourceKindRegistry.java`
- Create: `common/src/main/java/dev/tomewisp/tool/config/ToolFamilySettingsStore.java`
- Create: `common/src/main/java/dev/tomewisp/tool/config/LocalMarkdownKnowledgeProvider.java`
- Create: `common/src/main/java/dev/tomewisp/settings/tool/ToolSettingsView.java`
- Create: `common/src/main/java/dev/tomewisp/settings/tool/ToolSettingsBackend.java`
- Create: corresponding tests under `common/src/test/java/dev/tomewisp/tool/config/` and `common/src/test/java/dev/tomewisp/settings/tool/`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsRuntime.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsService.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/ClientSettingsSnapshot.java`
- Modify: `common/src/main/java/dev/tomewisp/settings/capability/RecipeSettingsBackend.java`
- Modify: `common/src/main/java/dev/tomewisp/TomeWispBootstrap.java`

- [x] **Step 1: Write failing strict-codec and lifecycle tests**

Test the common envelope from the accepted design, unknown-field rejection, one owning Tool, source-kind registration constrained by owner, built-in delete rejection, user CRUD, atomic rollback, and managed-root confinement for `local_markdown`.

- [x] **Step 2: Implement immutable Tool/source records and strict registry**

The common source shape is:

```java
public record ToolSourceDefinition(
        String sourceId,
        String sourceKind,
        String displayName,
        boolean enabled,
        JsonObject config,
        Lifecycle lifecycle) {
    public enum Lifecycle { BUILT_IN, USER }
}
```

Defensively deep-copy JSON. `ToolSourceKindRegistry` binds each kind to one owning logical Tool, a strict validator, localized editor fields, lifecycle capability, and optional runtime factory.

- [x] **Step 3: Build Tool-family configuration stores**

Use `config/tomewisp/tools/<family>.json`, one `AtomicSettingsFile` replacement per family, schema 1, complete-candidate validation, and future-request publication. Recipes owns vanilla/JEI/REI source definitions and Guides owns Patchouli/FTB/local Markdown definitions. Inventory and Craftability remain separate families.

- [x] **Step 4: Add managed local Markdown loading**

Allow only a source-owned directory below `config/tomewisp/knowledge/`. Parse direct `.md` files into immutable `KnowledgeDocument` values with `RESOURCE_ASSET` evidence, reject symlink/root escape, and reload the KnowledgeRegistry from the full enabled provider set.

- [x] **Step 5: Integrate settings/runtime without duplicating callable IDs**

`ToolSettingsView` exposes logical family title, description, explicit enabled state, member callable IDs, and source rows. Enabling/disabling updates the existing deny policy for every member ID; source changes update only the owning family file. Move the recipe file path to `tools/recipes.json` for new unshipped state.

- [x] **Step 6: Run Tool/source tests and commit**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.tool.config.*' --tests 'dev.tomewisp.settings.tool.*' --tests 'dev.tomewisp.settings.capability.*' --tests 'dev.tomewisp.knowledge.*'
```

Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/tool common/src/main/java/dev/tomewisp/settings common/src/main/java/dev/tomewisp/knowledge common/src/main/java/dev/tomewisp/TomeWispBootstrap.java common/src/test/java/dev/tomewisp/tool common/src/test/java/dev/tomewisp/settings common/src/test/java/dev/tomewisp/knowledge
git commit -m "feat: make sources children of logical tools"
```

### Task 5: Move bundled and local Skills onto the Agent Skills subset

**Files:**
- Create: `common/src/main/java/dev/tomewisp/skill/FilesystemSkillLoader.java`
- Create: `common/src/main/java/dev/tomewisp/skill/SkillSettingsStore.java`
- Create: `common/src/main/java/dev/tomewisp/settings/skill/SkillSettingsView.java`
- Create: `common/src/main/java/dev/tomewisp/settings/skill/SkillSettingsBackend.java`
- Modify: `common/src/main/java/dev/tomewisp/skill/SkillSource.java`
- Modify: `common/src/main/java/dev/tomewisp/skill/SkillMetadata.java`
- Modify: `common/src/main/java/dev/tomewisp/skill/SkillParser.java`
- Modify: `common/src/main/java/dev/tomewisp/skill/BundledSkillLoader.java`
- Modify: `common/src/main/java/dev/tomewisp/skill/SkillRepository.java`
- Rename: every bundled `skill.md` to `SKILL.md`
- Modify: bundled Skill frontmatter to the accepted subset
- Modify: `ClientSettingsRuntime`, `ClientSettingsService`, and `ClientSettingsSnapshot`
- Modify/create adjacent Skill tests

- [x] **Step 1: Write failing Agent Skills subset tests**

Cover uppercase entry enforcement, required `name`/`description`, name-directory match, length constraints, optional `license`/`compatibility`/string `metadata`, `allowed-tools` as dependency only, scripts rejection, symlink/path confinement, local-over-bundled precedence, invalid-local isolation, and atomic create/edit/delete override.

- [x] **Step 2: Implement filesystem discovery and subset parsing**

`FilesystemSkillLoader` scans only direct skill directories, requires `SKILL.md`, reads supported `references/` and `assets/`, rejects unsupported executable files and escaped symlinks, and returns source-scoped diagnostics instead of aborting unrelated Skills.

- [x] **Step 3: Implement precedence and last-valid behavior**

Reload bundled sources first and valid local sources second. A valid local name replaces its bundled package. Invalid local content retains the previous valid/bundled document while recording `skill_validation_failed` against that local path.

- [x] **Step 4: Add atomic player-driven override storage**

The backend creates a complete local copy before editing a bundled Skill and atomically replaces `SKILL.md`. It never exposes an Agent write Tool. Deleting an override reveals the bundled Skill again; bundled packages cannot be deleted.

- [x] **Step 5: Run Skill tests and commit**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.skill.*' --tests 'dev.tomewisp.settings.skill.*' --tests 'dev.tomewisp.capability.*'
```

Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/skill common/src/main/java/dev/tomewisp/settings common/src/main/resources/assets/tomewisp/tomewisp_skills common/src/test/java/dev/tomewisp/skill common/src/test/java/dev/tomewisp/settings
git commit -m "feat: support external Agent Skills overrides"
```

### Task 6: Build separate Tools and Skills master-detail pages

**Files:**
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/ToolSettingsProjection.java`
- Create: `common/src/main/java/dev/tomewisp/client/gui/settings/SkillSettingsProjection.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/settings/SettingsSection.java`
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispSettingsScreen.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`
- Modify/create adjacent projection, layout, localization, and screen tests

- [x] **Step 1: Write failing page-projection tests**

Assert top-level sections are General, Models, Tools, Skills, History, Diagnostics; Tools group callable IDs and sources; selecting never toggles; the explicit toggle changes only the selected logical Tool; Skills expose provenance/content and no generic enabled switch; ordinary mode omits internal IDs, confidence, and raw diagnostics.

- [x] **Step 2: Replace combined-page state with two master-detail states**

Track `selectedToolId`, `selectedSourceId`, and `selectedSkillName` separately. Wide layout uses the existing left list/right editor geometry; narrow layout uses list-to-detail routing with Back. Remove the capability kind filter and mixed card wall.

- [x] **Step 3: Implement Tools page actions**

The left list selects a logical Tool. The right pane renders friendly description, readiness, an explicit enable button, and source cards. Recipes keeps visibility/preferred-viewer controls. Guides supports add/edit/delete `local_markdown`; built-ins show enable/disable/restore but no delete.

- [x] **Step 4: Implement Skills page view/editor**

Use `MultiLineEditBox` for a local override's `SKILL.md`. Bundled Skills default to read-only content with “Create local override”. Local overrides show Save and Delete Override. Do not display Tool-like toggles.

- [ ] **Step 5: Verify keyboard and secret behavior**

Ensure Tab traversal, Escape/back routing, Ctrl+Enter where applicable, IME/preedit delegation, scissor bounds, and focus restoration work without screen-owned orchestration state. Keep raw Skill and API-key values out of narration/diagnostic projections where required.

- [x] **Step 6: Run GUI tests and commit**

Run:

```bash
./gradlew :common:test --tests 'dev.tomewisp.client.gui.*' --tests 'dev.tomewisp.settings.*'
```

Expected: PASS.

```bash
git add common/src/main/java/dev/tomewisp/client/gui common/src/main/resources/assets/tomewisp/lang common/src/test/java/dev/tomewisp/client/gui
git commit -m "feat: separate Tools and Skills settings pages"
```

### Task 7: Update source-of-truth docs and run the complete product gate

**Files:**
- Modify: `README.md`
- Modify: `docs/development.md`
- Modify: `docs/isme/SKMB.md`
- Modify: `docs/isme/decisions/2026-07-19-019-manual-acceptance-corrections.md`
- Modify: `docs/verification/phase-4-final-acceptance/README.md`
- Create: retained redacted reports/screenshots only from actual normal-client acceptance

- [x] **Step 1: Update documentation to match actual behavior**

Replace environment-variable player instructions, the combined Knowledge & Capabilities page, lowercase bundled Skill format, schema rejection behavior, and Architectury 21.0.2 evidence. Keep environment references documented only for external/headless configuration and explicitly state local credential storage is not an OS vault.

- [x] **Step 2: Run the clean deterministic gate**

Run:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
./scripts/verify-phase4-package.sh
git diff --check
```

Expected: all tests and both loader builds pass; scripts parse; package verification reports success; no credential appears in tracked or ignored acceptance output.

- [ ] **Step 3: Launch the normal full-mod Fabric client**

Use the accepted Fabric run directory with Architectury 21.0.4, JEI, REI, and Farmer's Delight. Manually verify ASCII, Chinese IME, backspace, paste, Tab, Ctrl+Enter; masked API-key save/reload/test; Tools selection/source edit; Skills view/override; old-history rebuild; guide send and restart persistence. Retain only redacted evidence.

- [ ] **Step 4: Run focused NeoForge parity smoke where practical**

Open the same common settings/history behavior in a normal NeoForge client if the local profile is available; otherwise rely on common deterministic coverage plus the clean NeoForge build and state that graphical NeoForge was not rerun.

- [ ] **Step 5: Audit and commit**

Run:

```bash
git status --short --branch
git diff --stat
git diff --check
rg -n 'sk-[A-Za-z0-9]|Authorization:|secret-value' README.md docs common fabric neoforge
```

Expected: only intentional files, no raw credential, and the unrelated `docs/verification/.DS_Store` remains untracked.

```bash
git add README.md docs/development.md docs/isme docs/verification/phase-4-final-acceptance
git commit -m "docs: close phase 4 manual acceptance corrections"
```
