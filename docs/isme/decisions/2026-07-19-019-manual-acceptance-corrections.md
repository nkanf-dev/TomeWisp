# SKMB-2026-07-19-019: Manual-Acceptance Corrections

- status: accepted
- decided_by: designer
- approval_source: the designer required an API-key password field persisted to a database, separate Tool and Skill master-detail pages, Tool-owned typed source schemas, bundled read-only Agent Skills with external Markdown overrides, confirmed built-in sources are disable-only while user sources support full CRUD, and then stated “我已经确认了……就按你说的来吧”
- date: 2026-07-19
- commit: pending
- patterns:
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
  - G_irreversible_action
- scope: Fabric input compatibility, client credential persistence, Tool-owned sources, external Agent Skills, and pre-release history schema recovery

## Context

The first normal full-mod Phase 4 walkthrough contradicted the automated
completion claim. Architectury Fabric 21.0.2 broke character input, the model
editor exposed environment-variable configuration to players, the combined
capability page did not communicate selection versus enablement and mixed
Skills with Tools, and a schema-1 test database disabled history under the
schema-4 runtime.

The product remains unshipped. The designer reaffirmed that pre-release test
data should be replaced cleanly rather than migrated, that sources are child
configuration of their consuming Tool, and that Agent Skills are external
Markdown packages rather than hard-coded option cards.

## Decision

1. Fabric declares Architectury through 21.0.2 incompatible when present and
   the accepted full-mod profile uses Architectury 21.0.4. TomeWisp does not
   add a hard Architectury dependency.
2. The normal client model editor accepts a masked API key. Secrets are stored
   in the dedicated local `credentials.sqlite3` store; `models.json` schema 2
   retains only a qualified `credentialRef` such as `local:<uuid>`.
   `env:<name>` references remain an externally authored development/headless
   mechanism and are not player-facing.
3. Settings sections are General, Models, Tools, Skills, History, and
   Diagnostics. Clicking a Tool selects it; an explicit control changes
   enablement. Skills have document/provenance/edit presentation rather than
   Tool toggles.
4. Every source belongs to exactly one logical Tool and uses a common versioned
   envelope plus a trusted kind-specific strict schema. Built-in sources can be
   disabled/restored but not deleted. User-created sources support add, edit,
   delete, test, and refresh where the registered kind implements them.
5. Bundled Skills use uppercase `SKILL.md` and are read-only. Local Agent Skills
   live under `config/tomewisp/skills/`; editing a bundled Skill creates a local
   override. Scripts and Skill-granted permissions remain unsupported.
6. During the unshipped period, recognized TomeWisp history schemas 1, 2, and 3
   are transactionally rebuilt to the single current schema 4. Future, corrupt,
   missing/inconsistent-metadata, foreign, or otherwise unrecognized databases
   still fail closed without mutation. No migration branch is added.

## States and Transitions

- `credential_absent -> credential_staged -> profile_referenced`: insert a new
  immutable secret row, atomically replace the credential-free profile, then
  publish the prepared runtime.
- `credential_staged -> orphaned`: profile replacement fails or the process
  stops before replacement; the row is unreferenced and later collected.
- `tool_selected -> source_candidate_editing -> tool_config_saving ->
  tool_selected`: validate and atomically replace one owning Tool configuration;
  failure retains the prior file/runtime.
- `bundled_skill_selected -> local_override_editing -> skill_reloading`: create
  and validate an external override; invalid content retains the prior valid or
  bundled document.
- `history_schema_older_recognized -> rebuilding -> history_schema_current`:
  rebuild TomeWisp application tables in one transaction.
- `history_schema_future_or_unrecognized -> persistence_unavailable`: mutate
  nothing and expose an actionable diagnostic.

## Invariants

1. API keys never appear in model JSON, history, settings snapshots,
   diagnostics, logs, packets, prompts, screenshots, traces, or retained
   reports. A stored key is never pre-filled into or exposed through copy/cut
   from the masked widget.
2. Profile publication never references a missing secret; a failed replacement
   retains the prior runtime and credential.
3. One source has one owning Tool, and a source definition cannot register a
   Tool, grant network access, or widen evidence/permission authority.
4. Built-in source identity and kind cannot be edited or deleted. User sources
   can be mutated only through their registered strict schema.
5. Skills cannot execute scripts, grant tools, read arbitrary paths, or write
   files through the Agent. Local player editing does not create Agent write
   authority.
6. Active requests retain captured Tool/source/Skill/model state while later
   settings changes affect future requests only.
7. Automatic history rebuild applies only to explicitly recognized unshipped
   TomeWisp schemas 1 through 3 and never to a future, corrupt, missing- or
   inconsistent-metadata, foreign, or otherwise unrecognized file.
8. Fabric and NeoForge share the credential, Tool/source, Skill, history, and UI
   semantics in common code.

## Failure Semantics

- Known incompatible optional dependency: reject at loader resolution with an
  actionable upgrade, rather than starting a client with broken text input.
- Credential store/read/write failure: stable redacted credential failure; send
  no provider request and leave other profiles/settings usable.
- Invalid source/Tool candidate or atomic write failure: retain the previous
  configuration and runtime; do not partially publish.
- Invalid Skill override: retain the previous valid/bundled Skill and isolate
  the diagnostic to that override.
- Recognized older history rebuild failure: roll back and report
  `history_schema_rebuild_failed`; do not claim persistence.
- Future, corrupt, missing-metadata, or unrecognized history database:
  `history_schema_unsupported`/`history_corrupt`; mutate nothing.

## Applies To

- Fabric metadata and retained full-mod profile
- client model profile schema, credential resolver/store, settings editor,
  metadata refresh, connection probe, diagnostics, and shutdown
- Tool family/source registries, per-Tool settings stores, active-request capture
- bundled/local Skill discovery, parser, repository, filesystem store, and UI
- SQLite history open/rebuild/reset policy and repository startup
- common settings screen navigation/layout and both loader entrypoints
- deterministic and normal-client acceptance evidence

## Supersedes

- Supersedes SKMB-2026-07-18-011 only for recognized older unshipped TomeWisp
  schemas: they now rebuild automatically instead of failing until manual
  deletion. Future/unrecognized/corrupt files still fail closed.
- Supersedes SKMB-2026-07-18-015's environment-only client credential policy.
  Its atomic runtime publication, active-request capture, probe isolation, and
  redaction rules remain in force.
- Supersedes SKMB-2026-07-18-017's combined top-level Knowledge & Capabilities
  page and generic Skill toggles. Recipe/guide sources remain Tool-owned and
  all authority/capture invariants remain in force.

## Superseded By

None.
