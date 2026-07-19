# SKMB-2026-07-18-005: Phase 4 Product State

- status: accepted
- decided_by: designer
- approval_source: designer approved the consolidated Phase 4 design with “是的”, approved `ALL_KNOWN` with “当然是默认全部可查询了”, approved controlled dynamic components and partitioned persistence with “可以”, explicitly required a compatible recipe viewer, Patchouli, and a recipe-rich sample mod in the final client smoke, and chose the mainstream interleaved assistant/tool timeline over a flattened answer followed by all tool calls
- date: 2026-07-18
- commit: ad8ad52
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
  - G_irreversible_action
- scope: Phase 4 recipe sources, durable history, context compaction, semantic rich UI, developer diagnostics, and modded client smoke

## Context

Phase 3 completed the grounded tools, shared GuideService, deterministic E2E
path, and native screen. Real client use exposed a product limitation: client
recipe discovery can be constrained by vanilla recipe-book visibility even
when normal modded clients use JEI, REI, EMI, or an equivalent viewer to inspect
the pack's actual recipes.

The designer deferred Structure-to-Ponder, recursive technology production
planning, old-version ports, and formal distribution. Phase 4 instead completes
recipe discovery, durable sessions, long-context management, and Minecraft-
native rich interaction as one product phase.

## Decision

### Recipe visibility and sources

The default recipe visibility is `ALL_KNOWN`. Vanilla recipe-book unlock state
is evidence and an optional filter, not a default access boundary. OpenAllay
merges immutable snapshots from every compatible authorized source, including
client, server, and optional viewer adapters, without allowing one source to
silently overwrite a conflicting record from another source.

JEI, REI, EMI, and future viewers are independent optional adapters behind a
common provider and navigation contract. Missing or failed adapters publish
diagnostics and leave the remaining Agent usable. Only verified public APIs and
actual Minecraft 26.2-compatible artifacts may be claimed.

### Durable history

One local database stores versioned history partitioned by local player identity
and the current single-player world or server connection. Players may browse
all partitions, but automatic model context uses only the current partition.
Connection capabilities, live recipe generations, inventory, and knowledge
source state never resume across connections.

`GuideService` remains the active state owner. The database stores durable
domain projections, not live service or Minecraft objects. Requests that lose
their process become `interrupted` on recovery and require explicit manual
retry with a new request ID. They are never automatically resent.

Normal mode retains messages, semantic content, player-visible card data,
source references, evidence summaries, and terminal state. Developer mode,
disabled by default, additionally retains complete normalized tool inputs and
outputs, service transitions, compaction details, and redacted traces for
future requests. Neither mode stores credentials, authorization headers, model
reasoning, or unauthorized data.

SQLite is the preferred database subject to retained Java 25, Fabric,
NeoForge, and supported-OS packaging proof. A failed proof requires a new
accepted database decision; it does not authorize unversioned JSON fallback.

### Context compaction

Complete original history remains durable. Each model request receives a
provider-neutral `ContextProjection` produced by structural validation, old
tool-result reduction, token budgeting, structured summary checkpoints, and
recent verbatim messages.

System instructions, the current request, active tool-call/result pairs,
required evidence, pinned preferences, and unresolved tasks are protected.
Summaries are derived conversation memory and never factual game evidence.

Summary generation uses the active request's explicitly selected model topology.
There is no silent change of model, payer, or authority. If summarization fails,
deterministic reduction may continue only when the request still fits. Otherwise
the request fails as `context_compaction_failed` without deleting history or
required evidence.

### Semantic rich UI

Markdown is the base text layer, not the complete rendering contract. OpenAllay
adds validated Minecraft semantic references, first-class domain cards, and a
versioned allowlist of controlled dynamic components. The model may select a
registered component and bind it to validated references; it cannot provide
code, scripts, callbacks, arbitrary widget trees, commands, URLs, unrestricted
NBT, texture paths, permissions, or world mutations.

Unknown or malformed semantic content fails closed and remains readable as
text. A resource ID's existence permits presentation but does not create
evidence. UI paging and model-context reduction are independent.

The visible request is a stable chronological timeline, not a flattened final
answer plus a trailing tool list. Assistant text before a tool, the tool
invocation and completion, later assistant continuation, further tools, and the
final segment retain their actual order across streaming, persistence, restart,
and remote transport. Tool start and completion correlate by invocation ID;
repeated calls to the same tool never correlate by name alone. Final text can
reconcile only its final model-turn segment and cannot replace earlier visible
segments.

### Smoke evidence

The final explicit real-client smoke installs every actually compatible target
viewer practical for the selected loader, at least one compatible viewer when
available, Patchouli or a retained resource-based fallback with honest claim
boundaries, and Farmer's Delight or a documented compatible recipe-rich sample
mod. Downloaded artifacts remain ignored and the retained report records
version, source URL, loader, game version, and SHA-256.

A deterministic loopback model is the default smoke provider. The smoke must
prove all-known recipe visibility, viewer provenance and navigation, custom
sample-mod recipes, Patchouli search, durable restart, explicit interruption,
and secret redaction. Missing upstream artifacts and compilation alone do not
count as real integration proof.

## Applies To

- canonical recipe provider, merge, reference, evidence, and viewer contracts
- JEI, REI, EMI, and future optional recipe adapters
- `GuideHistoryStore`, current schema, recovery, deletion, and partitions
- GuideService persistence projection and interruption recovery
- context reducers, token budgeting, summaries, and provider adapters
- semantic message schema, component validation, rendering, and actions
- chronological timeline entries, tool invocation identities, and protocol
  ordering
- settings, developer mode, trace retention, and redaction
- Fabric and NeoForge modded client smoke profiles and retained reports

## Named States

- `interrupted`: durable request history exists but execution lost its process;
  terminal until the player explicitly retries with a new request ID.
- `compacting`: request preparation is reducing or summarizing history before
  model dispatch; cancellable and unable to mutate original durable messages.
- `persistence_unavailable`: active in-memory service remains usable, but a
  durable write or load failed and no save claim may be made.
- `integration_degraded`: one optional recipe source or viewer failed while
  other authorized sources remain available.

## Transitions

1. `preparing -> compacting` when the selected model's context budget cannot
   accept the unreduced projection.
2. `compacting -> model_wait` when a valid projection is committed for the
   request.
3. `compacting -> failed` with `context_compaction_failed` when no valid
   projection fits after the approved fallback.
4. `active -> interrupted` on next load after process loss without a terminal
   event; no provider call is made during recovery.
5. `interrupted -> preparing` only through explicit retry with a new request ID.
6. `durable write -> persistence_unavailable` on transaction failure; current
   in-memory state continues with an explicit unsaved diagnostic.
7. `recipe source available -> integration_degraded` when one optional adapter
   fails; unaffected sources keep their current snapshots.
8. A recipe-source generation change makes old unresolved references stale;
   exact lookup returns `stale_reference`.
9. Enabling developer mode changes future capture only and does not rewrite
   normal historical records.

## Invariants

1. Recipe-book unlock state is never the default knowledge boundary.
2. Source conflicts remain visible; authority ordering does not erase variants.
3. Automatic model context never crosses the durable world/server/player
   partition.
4. Durable history never restores connection-scoped capabilities or live game
   snapshots.
5. Process loss never causes automatic provider retry or duplicate cost.
6. Original messages remain durable when context is reduced or summarized.
7. Summary content never satisfies a factual evidence requirement.
8. Tool-call/result structure remains valid in every context projection.
9. Normal history and developer traces retain only their approved data classes.
10. Controlled components can only bind registered types and validated data.
11. Optional integration failure never disables unrelated Agent capabilities.
12. Runtime artifacts and secrets never enter Git or retained smoke reports.
13. Player-visible transcript order matches the actual Agent event order and is
    identical before and after durable recovery.
14. Final reconciliation never overwrites an earlier assistant segment or moves
    a tool entry.

## Failure Semantics

- Unknown viewer or incompatible adapter: `capability_unavailable` plus an
  integration diagnostic; remaining recipe sources continue.
- Conflicting recipe records: preserve separate variants and provenance; do
  not select one silently.
- Database transaction failure: mark data unsaved and persistence unavailable;
  do not claim success or corrupt the in-memory request.
- Corrupt database segment: isolate and diagnose it; do not silently delete it
  or prevent the base Agent from starting.
- Interrupted request: retain visible history and require manual retry.
- Compaction failure: use deterministic reduction only if it fits; otherwise
  fail as `context_compaction_failed`.
- Invalid semantic reference or component: render safe fallback text with no
  action or fabricated evidence.
- Missing, duplicate, or inconsistent timeline/tool invocation identity: fail
  the affected request closed rather than guessing order or updating another
  tool card.
- Missing smoke dependency: record the unavailable upstream fact and do not
  claim that integration as tested.

## Supersedes

- Supersedes the Phase 3 decision that disconnect permanently clears all
  conversation history. Disconnect still clears active connection state and
  capabilities, while Phase 4 may later reload only durable, partitioned,
  non-live history for the new connection.
- Supersedes earlier roadmap prose that assigned Structure-to-Ponder to Phase 4.

## Superseded By

None.
