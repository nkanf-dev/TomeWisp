# SKMB-2026-07-18-016: Native Settings Coordination

- status: accepted
- decided_by: designer
- approval_source: designer stated “从现在开始，整个phase 4 的决策，我都相信你的最佳判断”; the selected design keeps domain configuration independent behind one native settings service
- date: 2026-07-18
- commit: pending
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - E_security_boundary
  - F_fail_semantics
- scope: common native settings ownership, domain configuration writes/reloads, drafts, diagnostics, and loader lifecycle

## Context

Model profiles, capability/source preferences, display/debug projection,
durable history, and trusted metadata already have distinct owners and strict
formats. Phase 4 needs one native screen and coherent operation status without
turning the screen into an orchestrator or treating one tool such as recipes as
a top-level mod configuration domain.

## Decision

Common code owns one `ClientSettingsService` that publishes immutable settings
snapshots and accepts typed actions. The screen owns only editable draft values,
selection/focus, and local confirmation state. It never opens files, constructs
model clients, touches SQLite, refreshes metadata directly, or mutates recipe,
display, model, or Guide runtime state.

Persistent configuration remains separated by domain:

- `models.json` owns named model profiles and their environment-variable names;
- `capabilities.json` owns player-disabled local tool and Skill identities;
- capability-owned files such as `recipes.json` own only that tool/source
  family's typed preferences;
- `display.json` owns debug and later accepted presentation/accessibility
  preferences;
- `model-metadata.json` remains an automatically managed credential-free cache;
- `history.sqlite3` remains owned by the ordered history repository.

Each strict JSON domain has a matching codec/store boundary. A confirmed save
validates the whole candidate, writes a temporary sibling, requires atomic
replacement, and only then publishes the already-prepared runtime value. A
failure leaves the prior file and runtime unchanged. Domains are not combined
into one transaction: a model-profile save cannot accidentally rewrite
capability, recipe-tool, or display preferences.

The settings service permits one mutating settings operation at a time. It
does not queue hidden future writes. A conflicting save, reload, metadata
refresh, or history action fails visibly as `settings_busy`; the player may
retry after the current operation ends. The live model probe follows its more
specific SKMB-2026-07-18-015 busy/cancellation rules.

All file, provider, and SQLite work runs off Minecraft-owned threads. Snapshot
publication and screen callbacks return through the client dispatcher. A
screen may detach and reopen without becoming the operation owner. Closing the
screen discards unconfirmed drafts and confirmation tokens, cancels its
ephemeral connection probe, but does not roll back or cancel an already
confirmed atomic configuration write, metadata refresh, or history deletion.
Late completion updates the service snapshot and is visible when settings are
reopened.

Reload reads and validates the full domain file before replacement. When a
draft is dirty, reload requires an explicit discard confirmation. Invalid or
unreadable external edits retain the last valid runtime and publish a redacted
diagnostic; they are never partially applied. Missing files resolve to the
documented defaults and are materialized only by an explicit save.

Normal diagnostics present friendly domain status cards. Debug Mode appends
only the redacted technical projection authorized by SKMB-2026-07-18-010.
Snapshots may state that a named credential environment variable is present,
but neither the service nor UI exposes its value. History deletion/reset goes
through GuideService coordination and the ordered repository under
SKMB-2026-07-18-014; settings never executes direct SQL.

The top-level player section is “知识与能力” (`Knowledge & Capabilities`), not
“配方” (`Recipes`). It inventories code-registered knowledge sources, tools,
and Skills through friendly cards. A registered entry may expose a typed child
settings page owned by that capability. Recipe visibility, vanilla/viewer
sources, and JEI/REI/EMI-style viewer preferences therefore live under the
recipe tool's child page rather than in top-level mod settings. Future online
knowledge sources and tools follow the same registration boundary; appearing
in the settings catalog never makes them model-callable or grants network,
evidence, or permission authority.

Before the first release, settings JSON schemas follow the same clean current-
schema policy as other development state: production does not accumulate
migrations for unshipped formats. Unsupported explicit schema versions fail
closed and the prior runtime remains active.

## States and Transitions

- `settings_idle -> settings_mutating`: one confirmed typed save, reload,
  metadata refresh, or history action begins on its owning asynchronous path.
- `settings_mutating -> settings_idle`: the action commits and publishes one
  immutable snapshot containing the new state and transient success status.
- `settings_mutating -> settings_idle`: the action fails and publishes one
  redacted diagnostic while retaining all prior durable/runtime values not
  transactionally committed by that action.
- `draft_clean -> draft_dirty`: the player edits a field locally; no runtime or
  file changes.
- `draft_dirty -> draft_clean`: save succeeds or the player explicitly discards
  changes; closing the screen also discards the screen-local draft.

## Invariants

1. `ClientSettingsService` is the common owner of settings operations and
   immutable settings snapshots; Screen and loaders remain adapters.
2. Model, capability, capability-owned, display, metadata, and history storage
   remain independently versioned and cannot rewrite one another as a side
   effect.
3. No file, provider, or SQLite wait runs on a Minecraft-owned thread.
4. A failed validation/read/write/reload retains the last valid runtime state.
5. Closing settings cannot cancel or roll back an already confirmed durable
   mutation, while unconfirmed drafts and confirmations are not durable.
6. UI diagnostics never expand the data classes authorized by normal/debug
   projection decisions.
7. History actions use actor-scoped GuideService/repository coordination and
   never bypass active-request or pending-write checks.

## Failure Semantics

- A second mutating action starts while one is active: `settings_busy`; start
  no queued or partial operation.
- Dirty-draft reload lacks confirmation: `settings_discard_confirmation_required`;
  retain the draft and runtime.
- Strict decode/validation fails: the domain-specific `invalid_*_config` code;
  retain the prior file/runtime projection.
- Temporary write or atomic move fails: `settings_write_failed`; retain the
  prior target/runtime and remove the temporary sibling best-effort.
- External reload reads an unsupported schema: domain-specific invalid-schema
  failure; never migrate or partially apply the file.
- Screen closes during a confirmed mutation: detach the listener and let the
  service-owned action reach its normal terminal state.

## Applies To

- `ClientSettingsService`, snapshot/event/action records, and client dispatcher
- strict model, capability, capability-owned, and display codecs/stores
- model registry, recipe runtime, and display runtime replacement hooks
- metadata refresh and history administration invocation
- native settings sections “常规 / 模型 / 知识与能力 / 历史 / 诊断”, capability
  child pages, dirty drafts, confirmations, and diagnostics
- Fabric and NeoForge path/lifecycle wiring
- deterministic atomicity, busy, detach, reload, redaction, and parity tests

## Supersedes

None. This coordinates but does not weaken SKMB-2026-07-18-010,
SKMB-2026-07-18-014, or SKMB-2026-07-18-015.

## Superseded By

None.
