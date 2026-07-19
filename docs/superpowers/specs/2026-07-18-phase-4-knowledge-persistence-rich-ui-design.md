# TomeWisp Phase 4 Knowledge, Persistence, and Rich UI Design

Status: approved by the designer through the Phase 4 roadmap discussion on
2026-07-18. The designer approved one consolidated Phase 4, default access to
all known recipes, partitioned durable history, manual retry after interruption,
normal/developer persistence levels, mainstream context compaction, controlled
dynamic UI components, chronological interleaved Agent rendering, and a
real-client modded smoke matrix.

## 1. Goal

Phase 4 turns the completed grounded Agent and native screen into a durable,
modpack-aware Minecraft knowledge product. It has three product outcomes:

1. Recipe discovery no longer treats the vanilla recipe book as an authority
   boundary. TomeWisp consumes every compatible, authorized recipe source it
   can actually observe, including viewer integrations.
2. Sessions, messages, summaries, and player-visible evidence survive restart
   without leaking state between worlds, servers, or players.
3. The screen renders Minecraft-native semantic content, structured cards, and
   controlled dynamic components rather than stopping at plain Markdown.

Phase 4 is one phase. The work packages below are dependency and verification
boundaries, not separate product phases.

Structure-to-Ponder, recursive technology production planning, old-version
ports, and formal distribution remain deferred. They are not Phase 4 completion
dependencies and must not be mixed into its implementation.

## 2. Approaches considered

### 2.1 Recommended: shared semantic core with optional adapters

Extend the current `RecipeCatalog`, `GuideService`, immutable UI projection,
and provider-neutral model layer. Common code owns canonical recipe identity,
durable history contracts, context reduction, semantic message nodes, and rich
component validation. Loader and optional-integration adapters only translate
actual platform APIs into those contracts.

This preserves one product path while allowing JEI, REI, EMI, database, and
provider capabilities to fail independently.

### 2.2 Rejected: make a recipe viewer the canonical database

Using JEI, REI, or EMI as the only recipe truth would make client-only behavior
depend on one optional mod, create different semantics across installations,
and erase server authority and provenance. Viewers are sources and navigation
targets, not owners of TomeWisp recipe identity.

### 2.3 Rejected: persist live GuideService objects

Serializing the current in-memory service graph would retain connection-scoped
capabilities and couple schema layout to implementation classes. Phase 4 instead
persists versioned domain records and reconstructs a new connection-scoped
service projection on load.

### 2.4 Rejected: arbitrary model-generated UI

Allowing the model to emit Java classes, scripts, callbacks, unrestricted JSON
widget trees, texture paths, commands, or NBT access would create a new
execution and permission surface. The model may select only registered,
versioned component types and bind them to validated references.

## 3. Unified recipe knowledge layer

### 3.1 Provider contract

Phase 4 introduces a narrow common recipe-provider contract around the existing
catalog. Providers publish immutable snapshots containing source identity,
freshness, completeness, diagnostics, and canonical recipe records.

Initial provider targets are:

- the synchronized vanilla client recipe/display state;
- authenticated server `RecipeManager` snapshots;
- JEI when a compatible Minecraft 26.2 API and artifact exist;
- REI when a compatible Minecraft 26.2 API and artifact exist;
- EMI when a compatible Minecraft 26.2 API and artifact exist.

NEI or another viewer may be added through the same contract only after an
actual compatible artifact and supported public API are verified. No adapter
may use private reflection or copy an older Minecraft API by assumption.

Each optional adapter fails independently. Its absence or failure publishes an
explicit capability diagnostic while the remaining catalog stays usable.

### 3.2 Visibility policy

The default policy is `ALL_KNOWN`. A recipe visible through an authorized
client viewer, synchronized client state, or server provider remains queryable
even when the vanilla recipe book reports it locked.

Recipe-book state is retained as `unlocked`, `locked`, or `unknown` evidence and
may be selected through an explicit `UNLOCKED_ONLY` preference. It is not a
default authority boundary.

Configuration may enable or disable individual recipe sources and select a
preferred viewer. Disabling a source affects future snapshots and requests; it
does not rewrite historical evidence.

### 3.3 Identity and merge rules

Every source emits a stable source-scoped reference. The catalog may group
records into one logical recipe only when their normalized semantic fingerprints
match. Grouping retains every source and evidence entry.

Records with the same recipe ID but different normalized contents remain
separate. A server-authoritative record may be presented first, but it does not
silently overwrite a client-visible or viewer-specific variant.

When a provider refresh changes its generation, unresolved old references
return `stale_reference`. The Agent must explicitly search again.

### 3.4 Viewer navigation

A common `RecipeViewerBridge` exposes capabilities to open:

- recipes for an item;
- usages for an item;
- an exact recipe when the viewer supports it.

The bridge discovers installed viewers, uses a configured preference when more
than one is available, and never changes Agent or session state. Unsupported
navigation is a disabled action with a diagnostic, not a fabricated success.

## 4. Durable history

Implementation status on 2026-07-18: the current normal-mode projection,
hashed player/connection partitions, ordered asynchronous SQLite repository,
interruption recovery, GuideService lifecycle integration, Fabric/NeoForge
packaging and lifecycle hooks, and player-visible persistence health are
implemented. Privacy-safe compaction checkpoints and per-session/request model
selection are also complete. SKMB-2026-07-18-011 removes all migration paths
for unshipped development schemas. Developer-mode payloads, explicit partition/all-history
management, paging, and retained graphical restart acceptance remain open
Phase 4 work.

### 4.1 Ownership and partitioning

`GuideService` remains the single owner of active connection-scoped request,
session, topology, cancellation, source, and transcript state. A new
`GuideHistoryStore` persists durable projections; it does not become a second
orchestration loop.

One local database is partitioned by a non-secret scope derived from the local
player identity and the current single-player world or server connection.
Players may browse and manage all partitions, but automatic model context uses
only the current partition. Capabilities, live recipe generations, inventories,
and source availability never resume from an old connection.

SQLite is the preferred implementation because it provides transactions,
indexes, strict schema metadata, and recoverable local tooling. Adoption is conditional on
a retained Java 25, Fabric, NeoForge, Windows, macOS, and Linux packaging proof.
If that proof fails, selecting a different embedded database requires a new
accepted decision; the implementation must not silently fall back to ad-hoc
JSON persistence.

### 4.2 Durable record model

The versioned schema stores:

- partition and session metadata;
- user messages and completed assistant text;
- semantic message nodes and player-visible card projections;
- source and evidence summaries;
- structured terminal, cancelled, interrupted, and error states;
- per-session selection and each request's captured credential-free selection;
- compaction checkpoints and their source-message ranges;
- schema metadata.

Normal mode does not retain full inventory snapshots, raw provider bodies,
authorization data, model reasoning, or full normalized tool results. It stores
only the structured projection required to reconstruct the player UI.

Debug mode additionally retains complete normalized tool arguments and
results, GuideService transitions, queue and retry events, compaction details,
and redacted traces. Debug data is separately identifiable, exportable, and
deletable. Debug mode is disabled by default and affects only future
requests.

No mode stores API keys, authorization headers, cookies, raw secrets, or model
reasoning.

### 4.3 Recovery and failure

An active request that loses its process or client becomes `interrupted` when
history is next loaded. TomeWisp preserves the user message, any committed
visible output, and a diagnostic, but never automatically sends the request
again. Retry is an explicit player action with a new request ID.

Database writes are transactional. A failed write reports persistence as
unavailable and must not claim the message was saved. The current in-memory
GuideService may remain usable with a visible diagnostic. Corrupt data is
isolated for diagnosis rather than blocking mod bootstrap or being silently
discarded.

Phase 4 adds no automatic age, session-count, message-count, or database-size
deletion policy. Players can delete one session, one partition, developer
traces, or all durable history through explicit actions.

## 5. Context reduction and compaction

### 5.1 Provider-neutral reducer pipeline

TomeWisp follows the reducer/compaction pattern used by current LangChain,
Semantic Kernel, and Anthropic agent guidance while keeping semantics portable
across Anthropic Messages and OpenAI-compatible Chat Completions.

The common pipeline is:

```text
complete durable history
  -> structural integrity reducer
  -> old tool-result reducer
  -> token budget calculation
  -> structured summary checkpoint when required
  -> recent verbatim messages
  -> request-scoped ContextProjection
```

The database retains the complete history. Compaction changes only the context
projection sent to a model.

References informing this design:

- LangChain short-term memory: token trimming, persistence, and summarization;
- Microsoft Semantic Kernel chat-history reducers: composable reduction and
  preservation of system/function-call structure;
- Anthropic context editing: clear old tool results while retaining client
  history;
- Anthropic compaction: token-triggered summary blocks for long-running work.

### 5.2 Required preservation

Reduction always preserves:

- system safety and authority instructions;
- the current user request;
- the current request's complete tool-call/result pairs;
- evidence and references still required by the active task;
- pinned player preferences and unresolved tasks;
- as many recent verbatim messages as fit the remaining budget.

Old tool results are reduced before natural-language summarization. Large
inventory, recipe-search, and document results become concise conclusions plus
stable references, authority, completeness, capture time, and failure codes.

### 5.3 Summary checkpoints

When deterministic reduction is insufficient, the selected request topology
generates a structured summary containing goals, preferences, completed topics,
current tasks, important decisions, unresolved questions, and evidence
references. TomeWisp does not silently switch to a different model or payer for
summarization. A separately configured summary model may be added later only
through explicit player configuration.

Each checkpoint records its source-message range and hash, model identifier,
prompt/schema version, creation time, result, and failure state. Older
checkpoints may be summarized into a higher-level checkpoint while original
messages remain intact.

The model identifier is generation provenance, not ownership. Sessions retain
a provider-neutral transcript and are never bound to one provider or model.
Changing the selected model affects future requests only: TomeWisp serializes
the same common history through the newly selected adapter, validates any
checkpoint by source hash and summary version, and re-estimates it against the
new model's context budget. Provider-side prompt/KV cache reuse may be lost,
but semantic conversation context is preserved. A valid derived checkpoint may
therefore cross both provider and model boundaries.

A summary is derived conversation memory, never factual game evidence. Current
inventory, recipes, quest state, position, capabilities, and knowledge must be
validated through current evidence or a fresh tool call.

### 5.4 Trigger and failure behavior

Compaction is triggered by the configured model's token budget rather than a
fixed message count. The assembler reserves space for system instructions,
Skills, tool declarations, output, and expected tool continuation before
allocating history.

If summary generation fails, TomeWisp keeps all durable history and attempts a
deterministic reduced projection. If that projection fits, the request may
continue with a visible diagnostic. Otherwise it fails as
`context_compaction_failed`; it never silently removes the current request or
required evidence.

Provider-native compaction may later be used as an adapter optimization, but it
cannot be required for product behavior or run in addition to an overlapping
common compaction pass.

## 6. Semantic rich messages

### 6.1 Content model

Player-visible assistant content is parsed into a versioned immutable semantic
tree with four layers:

1. safe Markdown for paragraphs, headings, lists, emphasis, quotes, tables,
   inline code, and code blocks;
2. Minecraft semantic references for items, blocks, fluids, entities, biomes,
   dimensions, tags, recipes, key mappings, knowledge sources, and evidence;
3. first-class structured cards for recipe, usage, inventory, craftability,
   source, error, and step content;
4. registered controlled dynamic components.

HTML, scripts, external embeds, and arbitrary URL actions are unsupported.
Malformed or unsupported content degrades to readable text.

### 6.2 Grounded references

The model prompt teaches the semantic syntax but grants no authority. The
preferred form binds components to stable references returned by tools in the
current authorized context.

A raw resource ID emitted by the model is resolved against the current client
registry before rendering. Existence permits an icon or localized name; it does
not create evidence or authorize a factual claim. Unknown resources remain
readable invalid-reference text.

### 6.3 Controlled dynamic components

The model may select only versioned component types registered by TomeWisp,
such as item rows, recipe grids, ingredient checks, craftability summaries,
progress steps, source summaries, status badges, and bounded choice groups.
Properties may reference only validated domain handles and allowlisted display
parameters.

Components cannot name Java classes, execute scripts or commands, register
callbacks or tools, access arbitrary NBT or paths, fetch URLs, provide arbitrary
textures, or mutate the world. Unknown schemas and components fail closed and
fall back to text.

### 6.4 Native rendering and interaction

The Minecraft renderer supports localized names, item icons, counts, durability,
enchantment glint, rarity, native tooltips, recipe slots, workstation and
processing facts, missing-material highlighting, keyboard focus, narration,
and optional presentation-only animation.

Recipe and item components may offer recipe, usage, inventory-check, and viewer
navigation actions. Actions send typed intents through existing services; they
do not execute arbitrary model-authored behavior.

Normal tool/source details are player-facing cards, not diagnostics. They show
localized names, Minecraft item icons, quantities, inputs/outputs, workstation
or processing facts, available/required/missing materials, steps, and friendly
errors. Raw tool/request IDs, confidence, authority/completeness enums, capture
timestamps, provenance, stable internal handles, normalized JSON, and technical
failure codes are hidden. The player-facing “调试模式” / “Debug Mode” setting
may append those redacted diagnostics in a visibly separate section; it never
reveals reasoning, credentials, authorization data, raw provider bodies, or
another player's state.

Animation is never the state source. Closing and reopening the screen rebuilds
the same content from immutable semantic records.

### 6.5 Chronological Agent timeline

The visible transcript follows the actual Agent loop rather than flattening one
request into a final assistant paragraph followed by a separate list of every
tool. A normal request may render as:

```text
user question
assistant segment
tool invocation and result
assistant continuation
second tool invocation and result
final assistant segment
```

Phase 4 replaces the player-visible `assistantText + tools[]` projection with a
stable ordered timeline. Each entry has a durable entry ID, request ID,
monotonic ordinal, type, timestamps, and type-specific immutable data.

Text deltas append only to the active assistant segment. Starting a tool closes
that segment, appends a tool entry at the current chronological position, and
updates that same entry in place when the result arrives. A later text delta
creates or continues a later assistant segment after the tool. Repeated calls
to the same tool are correlated by tool invocation ID rather than by tool name.

`FinalText` reconciles only the final assistant segment for its model turn. It
must never replace or move earlier assistant segments or tool entries. Model
turn boundaries and tool-call IDs must remain explicit across local events,
server protocol decoding, durable persistence, and UI projection. If the
current protocol cannot preserve those identities, it receives a strict
versioned upgrade; loader transports do not infer the order themselves.

Tool cards appear collapsed inline by default and update from waiting to
success/failure without moving. Multiple tool calls emitted in one model turn
retain model order. Rate-limit, interruption, compaction, cancellation, and
failure status entries appear at their actual point in the timeline.

Reasoning remains unrepresentable. The timeline exposes only player-visible
assistant text, typed tool activity, authorized evidence, and product status.

Sources remain attached to their producing tool or an explicit semantic
reference. TomeWisp does not attach every request source to every assistant
segment and imply unsupported claim-level attribution.

### 6.6 Streaming and long-history performance

Streaming parsing reparses only the mutable message tail. Incomplete Markdown
or semantic tokens remain text until closed and validated. Completed semantic
trees, wrapping, and layout are cached per message.

Durable history is paged. The screen loads only the visible neighborhood,
creates heavy component render state only when visible, and loads developer
details on demand. Model context selection and GUI paging are separate systems.

Paging and virtualization operate on timeline ordinals. Restoring a session
recreates the same interleaving, and an in-progress tool entry may update
without rebuilding or scrolling the entire transcript. Auto-scroll follows new
entries only while the player remains at the bottom; reading earlier history is
not interrupted by background tool or text events.

Every semantic component has a text/narration equivalent. Color is never the
only state signal, keyboard access remains complete, and animation can be
disabled.

## 7. Configuration and diagnostics

The native settings flow manages multiple named model profiles. Each profile
covers protocol, base URL, model ID, API-key environment-variable name,
context/output limits, timeouts, and a redacted connection test. Players select
the profile used by future requests without rebinding or clearing the current
session; an active request retains the profile selected when it started.
Settings also cover recipe visibility, enabled sources, preferred viewer,
database health, history management, compaction state, debug mode, animation,
accessibility, and reload. Debug mode is disabled by default.

The GUI may report whether a named environment variable is present but never
read out or persist its value.

Debug diagnostics expose request/session IDs, topology, provider and recipe
source capability, snapshot generation and counts, tool and evidence data,
queue and 429 state, current database schema, compaction checkpoints,
token estimates, and redacted trace export. They never expose reasoning,
credentials, authorization headers, or another player's data.

## 8. Loader and dependency boundaries

Common code owns recipe-domain contracts, catalog merging, history interfaces,
schema codecs, context reducers, semantic message types, validation, view
projection, and deterministic tests.

Fabric and NeoForge own viewer discovery hooks, optional API registration,
client lifecycle callbacks, development run wiring, and platform-specific
navigation. Behavior and diagnostics remain in parity even when the actually
available viewer artifacts differ by loader.

Optional integration dependencies are development/runtime inputs only where
possible. They must not become mandatory TomeWisp runtime dependencies, and
downloaded mod JARs remain outside Git.

## 9. Modded real-client smoke matrix

Phase 4 adds an explicit retained smoke workflow after deterministic tests and
both loader builds pass. It launches graphical clients only when the user has
requested the smoke run.

For each loader under test:

1. Discover the actual Minecraft 26.2-compatible JEI, REI, and EMI artifacts
   from authoritative project metadata.
2. Install every targeted viewer that is compatible with that loader when a
   combined installation is supported; otherwise run separate viewer profiles.
   At minimum, install and prove one compatible viewer when the ecosystem
   provides one.
3. Install a compatible Patchouli artifact and a small retained test book. If
   no 26.2 Patchouli artifact exists, record that fact and use the retained
   resource-based book to test TomeWisp parsing without claiming binary
   Patchouli compatibility.
4. Install Farmer's Delight when a compatible 26.2 artifact exists. Otherwise
   select a maintained, recipe-rich sample mod with custom recipe types and
   record why it was substituted.
5. Keep downloaded artifacts in ignored smoke/run directories. Record project,
   version, loader, source URL, and SHA-256 in the retained smoke report.

The smoke workflow uses a deterministic loopback model by default so it is
repeatable and non-billable. It proves:

- a recipe not unlocked in the vanilla recipe book remains searchable;
- the viewer provider contributes recipes and provenance;
- custom sample-mod recipes can be searched and opened;
- recipe and usage cards show real item icons and viewer navigation;
- Patchouli content can be searched and opened;
- a combined guide request completes through `GuideService`;
- restart restores durable history without automatically retrying requests;
- the report contains no credentials.

The run retains a redacted JSON report and relevant screenshots/log hashes.
Compilation, a missing upstream artifact, or synthetic fixtures alone never
count as a successful viewer, Patchouli, or sample-mod smoke claim.

## 10. Implementation sequence

1. Record Phase 4 ISME decisions and persistence/message protocol versions.
2. Introduce the recipe-provider contract and deterministic merge tests.
3. Verify 26.2 viewer APIs and implement compatible JEI/REI/EMI adapters.
4. Add the common viewer bridge and first-class recipe/item actions.
5. Implement `GuideHistoryStore`, the current database schema, and recovery.
6. Restore durable sessions as inactive projections and add explicit retry.
7. Implement reducer, token-budget, tool-result reduction, and checkpoints.
8. Add semantic message parsing, validation, and streaming reconciliation.
9. Replace the flattened request projection with the ordered timeline and
   version its local, remote, and durable identities.
10. Add native item/recipe renderers and controlled component registry.
11. Add settings, debug diagnostics, paging, and accessibility.
12. Extend deterministic, race, schema-rejection, redaction, and loader tests.
13. Run the full build, then execute and retain the approved modded client smoke.

Each implementation work package receives a focused plan and verification
checkpoint. Stateful behavior is rechecked against ISME whenever implementation
discovers a new recovery, concurrency, persistence, or failure decision.

## 11. Acceptance gates

Phase 4 is complete only when all applicable items are proven:

1. `ALL_KNOWN` recipe visibility works independently of recipe-book unlock.
2. Every claimed compatible JEI/REI/EMI adapter passes deterministic and real
   client smoke coverage; absent integrations fail independently.
3. Source merging preserves conflicts, authority, completeness, and provenance.
4. Viewer navigation opens the installed viewer or returns an explicit
   unavailable capability.
5. Sessions and rich messages survive restart within the correct partition.
6. Interrupted requests never automatically repeat a provider call.
7. Normal and developer persistence retain exactly their approved data classes;
   secrets and reasoning never enter either store.
8. Database schema rejection, corruption, failed writes, concurrent reads, deletion,
   and disconnect behavior have deterministic coverage.
9. Context reduction preserves system and tool-message structure, summaries
   never become factual evidence, and compaction failure is explicit.
10. Markdown, semantic references, native icons, controlled components,
    streaming, paging, keyboard navigation, narration, and disabled animation
    all have deterministic coverage.
11. Assistant segments, tool calls, tool results, later continuations, and
    terminal status restore and render in their original chronological order;
    repeated calls to one tool never merge or move incorrectly.
12. Fabric and NeoForge builds pass and their adapter behavior remains in
    parity.
13. The retained smoke report proves the installed viewer, Patchouli path, and
    recipe-rich sample mod without overstating unavailable upstream support.

## 12. Traceability

- Phase 3 product decision:
  `docs/isme/decisions/2026-07-17-004-phase-3-product-state.md`
- Phase 4 decision:
  `docs/isme/decisions/2026-07-18-005-phase-4-product-state.md`
- Repository operating contract: `AGENTS.md`
