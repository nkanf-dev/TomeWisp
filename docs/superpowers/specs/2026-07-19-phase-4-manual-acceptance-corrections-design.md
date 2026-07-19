# Phase 4 Manual-Acceptance Corrections Design

## Status

- Product phase: Phase 4, not a new product phase
- Design status: approved by the designer on 2026-07-19
- Decision basis: SKMB-2026-07-19-019
- Target: Minecraft 26.2, Java 25, Fabric and NeoForge

## Context

The consolidated automated Phase 4 gate passed, but the first normal full-mod
Fabric walkthrough exposed four product failures that the automated harness did
not prove:

1. typing into a Screen repeatedly failed inside Architectury 21.0.2's
   uninitialized `ScreenInputDelegate.DelegateScreen`;
2. the model editor asked an ordinary player for an API-key environment-variable
   name instead of accepting a masked API key;
3. the combined Knowledge & Capabilities page mixed sources, Agent tools, and
   Skills into toggle cards and left the detail pane without an understandable
   selection/configuration model;
4. an ignored schema-1 development history database caused schema-4 OpenAllay to
   disable persistence indefinitely.

The designer confirmed that OpenAllay has not shipped, current durable state is
test data, API keys must be entered like passwords and stored locally, sources
belong to the Tool that consumes them, built-in sources are disable-only while
user sources support full CRUD, and bundled Skills are read-only Agent Skills
with external file overrides.

## Goals

- Make normal Fabric text input work in the accepted full-mod profile and fail
  early with an actionable dependency conflict for the verified bad version.
- Give ordinary players a masked API-key workflow without requiring shell or
  environment configuration.
- Replace the combined capability card wall with separate Tools and Skills
  sections and a real master-detail editor.
- Make source definitions typed, versioned children of one logical Tool.
- Store Skills as an Agent Skills-compatible filesystem subset with bundled
  read-only packages and editable local overrides.
- Recreate recognized older unshipped history schemas automatically and
  transactionally, without adding migration code.
- Preserve immutable active-request capture, credential redaction, common-core
  ownership, and Fabric/NeoForge parity.

## Non-goals

- Agent-authored Skill writes are not granted in this change. Player-driven
  local editing prepares the storage/service boundary; a later accepted write-
  tool decision must authorize Agent creation or editing.
- OpenAllay will not execute Agent Skill `scripts/` directories.
- This design does not add arbitrary Web Fetch, Wiki HTTP, MC Wiki, or MC
  encyclopedia network authority. Future source kinds must receive their own
  endpoint, permission, cache, and evidence decisions.
- No migration path is retained for schema 1, 2, 3, or 4 history test data.
- This does not claim encryption against another local account/process that can
  read the Minecraft configuration directory. The credential file receives
  restrictive permissions and is never exposed through product state, but an
  OS-native vault remains future hardening.

## 1. Fabric Input Compatibility

Architectury Fabric 21.0.2 creates a temporary `DelegateScreen` for character
events without initializing Fabric Screen API event state. Architectury 21.0.3
and 21.0.4 remove that implementation; 21.0.4 is the accepted current 26.2
artifact.

The full-mod development profile moves to Architectury Fabric 21.0.4. Fabric
metadata declares Architectury versions through 21.0.2 incompatible when that
optional mod is present, so affected packs fail during dependency resolution
with an actionable upgrade instead of freezing every character event. OpenAllay
does not gain a hard Architectury runtime dependency. NeoForge remains
unchanged because the failing Fabric input delegate does not exist there.

The deterministic boundary test checks the Fabric metadata conflict. The
retained normal-client acceptance must type ASCII, Chinese IME text, backspace,
paste, Tab focus movement, and Ctrl+Enter into OpenAllay after JEI, REI, and
Farmer's Delight finish loading.

## 2. Stored Credential Architecture

### 2.1 Persistent shape

Client model-profile schema 2 replaces player-facing `apiKeyEnv` with an opaque
`credentialRef`. `models.json` remains credential-free. References are
qualified:

- `local:<uuid>` resolves through `credentials.sqlite3`;
- `env:<name>` is retained only for externally authored development/headless
  configuration and is never requested by the normal player editor.

The dedicated credential database has one current unshipped schema:

```sql
create table schema_metadata(
    singleton integer primary key check(singleton = 1),
    schema_version integer not null
);

create table credentials(
    credential_id text primary key,
    secret_value blob not null,
    created_at text not null,
    updated_at text not null
);
```

The database is global to the local client configuration, not partitioned by
player/world, and is never stored inside guide history. On POSIX filesystems
OpenAllay applies owner-read/write permissions best-effort; permission failure is
diagnosed and prevents claiming hardened storage, but never logs the key.

### 2.2 Atomic profile replacement

Updating a key creates a new immutable local credential ID instead of
overwriting the currently referenced row:

1. validate and prepare the complete model candidate without publishing it;
2. insert the new secret row transactionally;
3. atomically replace `models.json` with the new `credentialRef`;
4. publish the already-prepared runtime for future requests;
5. garbage-collect unreferenced local credentials after successful publication.

A crash before step 3 leaves only an unreferenced row, which is ignored and
later collected. A crash after step 3 leaves a complete reference and secret.
A JSON-write failure leaves the prior profile/runtime/credential active. A
best-effort cleanup failure may retain an unreachable secret but cannot change
the selected credential.

Profile deletion atomically removes the profile reference first, then removes a
credential only when no remaining profile refers to it. Shared references are
never deleted while in use.

### 2.3 UI and memory boundary

The Models page displays a masked password field with the states “not set”,
“saved”, and “replace on save”. It accepts paste and clear, does not expose the
secret through copy/cut, narration, tooltip, responder state, `toString`, or
settings snapshots, and never pre-fills a saved key back into the widget.
Saving consumes the transient input into `SecretValue` and clears the widget.

Connection testing resolves the selected candidate's credential reference and
retains the existing isolated, one-request, redacted behavior. Metadata refresh
and model listing use the same resolver but remain configuration-layer work.
Dedicated-server/headless model configuration may continue to use environment
references because it has no local player settings screen.

## 3. Tools Own Sources

### 3.1 Navigation and logical Tool families

The top-level settings navigation becomes:

- General
- Models
- Tools
- Skills
- History
- Diagnostics

There is no top-level Knowledge Sources page. The Tools page is master-detail:
the left pane selects one logical Tool family, and the right pane shows its
description, explicit enable switch, settings, and source sub-editor. Clicking
a Tool selects it; it never implicitly toggles it.

A logical Tool family may own several Agent tool IDs. For example, Recipes owns
recipe search, exact recipe lookup, item usage, and its recipe sources. Guides
owns knowledge search and exact document loading. Inventory and Craftability
remain separately selectable logical Tools even though their workflow consumes
recipe results. This grouping changes settings presentation, not the stable
callable tool IDs or invocation correlation.

### 3.2 Source schema

Every source is stored under exactly one owning Tool in
`config/openallay/tools/<tool-family-id>.json`. Files are independently versioned
and strict. The common envelope is:

```json
{
  "schemaVersion": 1,
  "toolId": "openallay:guides",
  "enabled": true,
  "sources": [
    {
      "sourceId": "user:minecraft-notes",
      "sourceKind": "local_markdown",
      "displayName": "Minecraft Notes",
      "enabled": true,
      "config": {
        "directory": "minecraft-notes",
        "locale": "zh_cn"
      }
    }
  ]
}
```

The common envelope does not accept arbitrary kind-specific keys. A trusted
`ToolSourceKind` registry supplies, per owning Tool and `sourceKind`:

- a strict config codec/schema version;
- localized field descriptions and editor controls;
- built-in versus user-created lifecycle capability;
- capture/refresh implementation and evidence metadata contract;
- optional credential-reference capability;
- stable validation and failure codes.

Runtime readiness, item/document counts, last refresh, coverage, authority,
completeness, diagnostics, and cache health are projections and are never
written back as user configuration.

### 3.3 CRUD rules

- Built-in/discovered sources (Vanilla, server recipes, JEI, REI, Patchouli,
  FTB Quests) can be selected, inspected, enabled/disabled, refreshed where
  meaningful, and restored to defaults. They cannot be deleted or have their
  registered kind/identity edited.
- User sources can be created only from registered user-creatable kinds and can
  be edited, deleted, tested, refreshed, and restored before save.
- Phase 4 initially supplies a managed `local_markdown` Guides source rooted
  below OpenAllay's own configuration directory. It cannot point to arbitrary
  paths. Online source kinds remain unavailable until separately accepted.
- Recipe viewers remain children of Recipes. Patchouli/FTB/local documents
  remain children of Guides. Web Fetch is a future Tool, not a source.

Source mutation validates the complete owning Tool candidate, writes a
temporary sibling, atomically replaces only that Tool file, and publishes an
immutable future-request snapshot. An active request keeps its captured Tool
and source state.

## 4. Agent Skills Subset

OpenAllay follows the public Agent Skills directory format while deliberately
supporting a safe subset:

```text
skills/<skill-name>/
├── SKILL.md
├── references/     # optional, read-only Markdown/text
└── assets/         # optional, non-executable resources
```

`SKILL.md` uses YAML frontmatter plus Markdown body. `name` and `description`
follow Agent Skills limits and the directory name must match `name`. Optional
`license`, `compatibility`, string metadata, and an `allowed-tools` dependency
declaration may be parsed. OpenAllay-specific required-mod information is stored
under namespaced string metadata rather than extra non-standard top-level YAML
fields. In OpenAllay, `allowed-tools` is a dependency check, never a permission
grant. Tool permission continues to come only from code and the captured Tool
policy.

The subset rejects scripts, URLs as file references, root escape, symlinks out
of the Skill root, arbitrary paths, duplicate names, malformed YAML, excessive
reference chaining, unavailable required Tool dependencies, and unsupported
files. References are relative and loaded progressively only when requested.

Bundled Skills move from lowercase `skill.md` to uppercase `SKILL.md` and are
read-only. Local Skills live under `config/openallay/skills/`. A local package
with the same valid name overrides its bundled package. “Edit bundled Skill”
first creates an atomic local copy; subsequent saves replace only the local
copy. An invalid local override leaves the last valid/bundled Skill active and
shows a source-scoped diagnostic rather than disabling unrelated Skills.

The Skills page is separate from Tools. Its left pane lists installed Skills
and provenance (bundled/local override); its right pane shows metadata,
instructions, references, validation, and an explicit create-override/edit
action. It has no Tool-style option list or generic enabled/disabled toggle.
Install/uninstall and marketplace state belong to the later Skill marketplace.

Player-driven local editing is included. Agent-driven creation/editing remains
unavailable because Phase 4 Agent tools are read-only; the filesystem/service
shape is intentionally ready for a later narrowly authorized write Tool.

## 5. Pre-release History Rebuild

The current history schema is 5. On startup, `SqliteGuideHistoryStore` applies
an explicit pre-release schema policy:

- fresh database: create schema 5 transactionally;
- recognized OpenAllay schema 1, 2, 3, or 4: transactionally drop only OpenAllay
  application tables and create schema 5;
- schema 5: open normally;
- future schema greater than 5, missing/inconsistent metadata, unrecognized
  tables, corrupt database, or wrong file: fail closed without deletion.

The existing database-reset transaction and rollback behavior are reused. A
reset failure restores the old recognized schema and reports
`history_schema_rebuild_failed`; in-memory Guide operation remains available
with a persistence-unavailable notice. Successful rebuild publishes one
friendly startup notice and does not pretend old test messages survived.

This automatic rebuild is explicitly temporary before the first formal
release. The release process must remove the pre-release rebuild policy and
accept a shipped-schema compatibility decision.

## 6. Threading and Lifecycle

- Credential, Tool settings, Skill files, and history database work run on
  ordered background owners, never the render/client/server tick thread.
- UI widgets create immutable candidates; completion is marshalled back to the
  client thread and generation-checked against the current screen selection.
- Saving one domain does not partially publish another domain.
- Shutdown cancels connection probes, drains settings work, closes credential
  and history stores, then closes model resources on both loaders.
- Fabric and NeoForge use identical common services and paths; loader code only
  supplies lifecycle, client dispatcher, and platform integration discovery.

## 7. Failure Semantics

- Bad Architectury Fabric version: loader conflict with an upgrade message;
  do not enter a client where text input is known broken.
- Credential database unavailable/corrupt: `credential_store_unavailable`;
  profiles remain visible but stored-key profiles are unavailable and no
  provider request is sent.
- Secret insertion succeeds but profile replacement fails: old reference and
  runtime remain active; new unreferenced row is collected later.
- Invalid Tool/source candidate: `invalid_tool_config` or
  `invalid_source_config`; retain prior file/runtime and identify the field.
- Unsupported source kind or unavailable optional integration: retain the
  definition and show `source_kind_unavailable`/integration diagnostics; do not
  disable unrelated Tools.
- Invalid Skill override: `skill_validation_failed`; retain the previous valid
  document and keep unrelated Skills usable.
- Recognized older history rebuild fails: `history_schema_rebuild_failed`;
  rollback, preserve the prior file, and make the unsaved state visible.
- Future/unrecognized history schema: `history_schema_unsupported`; mutate
  nothing.

## 8. Verification and Acceptance

Deterministic coverage must include:

- Fabric metadata conflict and common/loader architecture parity;
- credential schema, permission attempt, immutable rotation, shared reference,
  crash boundaries, cleanup, redaction, masked-widget behavior, connection
  probe, and no secret in JSON/snapshot/log/diagnostics;
- Tool family grouping, source ownership, strict per-kind schema, built-in
  disable-only behavior, user source CRUD, atomic rollback, active-request
  capture, and Guides local Markdown path confinement;
- official Agent Skills naming/frontmatter limits, external discovery,
  bundled/local precedence, atomic override, invalid-override isolation,
  reference confinement, and scripts rejection;
- recognized history 1/2/3/4 rebuild, schema 5 preservation, future/corrupt
  rejection, reset rollback, and sibling-file preservation;
- responsive Tools/Skills master-detail layouts, keyboard focus/narration, and
  Simplified Chinese/English localization.

Run the clean product gate:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
./scripts/verify-phase4-package.sh
git diff --check
```

Then launch a normal, non-E2E full-mod Fabric client with Architectury 21.0.4,
JEI, REI, and Farmer's Delight. Retain evidence for text entry, masked API-key
save/reload/test, Tools source editing, Skill view/override, automatic old-
history rebuild, guide submission, restart persistence, and secret redaction.
NeoForge receives the same common behavior through deterministic tests, a clean
build, and a focused normal-client settings/history smoke where practical.
