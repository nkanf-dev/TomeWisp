# SKMB-2026-07-19-022: Native Domain Views, Retrieval, and Player Memory Boundary

- status: accepted
- decided_by: designer
- approval_source: the designer approved the recommended embedded-native-view, hybrid retrieval, and selective player-memory design on 2026-07-19, then clarified that OpenAllay must embed JEI/REI or mod-provided views when available and must not imitate a mod GUI in its fallback
- date: 2026-07-19
- commit: pending
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: embedded native domain views, generic fallback canvases, semantic tables, stable streaming layout, explicit model selection projection, local knowledge retrieval, and the future player-memory authority boundary

## Context

Phase 4 already has a closed semantic AST, validated component references,
native item rendering, complete normalized recipe results, durable history, and
optional JEI/REI integrations. The current `recipe_grid` renderer nevertheless
draws only a textual placeholder. Markdown tables are flattened into delimiter
text, Tool focus can select the first null identity, model selection cycles
implicitly, and a streaming tail can change measured height as it becomes a
validated block.

The product also needs an offline-first retrieval base for future guide content
and an explicit boundary for a later player-memory system. Neither retrieval
summaries nor player memories may acquire game-fact authority.

## Decision

### Closed native domain-view layer

A model may select only a registered component type and bind references returned
by Tools in the same authorized request. It cannot provide slots, coordinates,
textures, widget class names, callbacks, or arbitrary view trees.

Every referenced domain component is resolved through a common native-view
registry. Resolution produces a detached, versioned presentation model from the
complete normalized Tool result. A provider may then render that model using a
verified public integration API on the Minecraft client thread.

Recipe rendering prefers, in order:

1. the exact JEI layout drawable registered by the recipe owner;
2. the exact REI category widgets registered by the recipe owner;
3. a OpenAllay-owned generic canvas that clearly presents inputs, catalysts,
   outputs, byproducts, station, and processing metadata.

The generic canvas uses OpenAllay's own neutral Minecraft-native visual grammar.
It does not copy or imitate Farmer's Delight or another mod's screen, textures,
coordinates, or live menu. An optional provider failure affects only that view
and immediately falls through to the next provider or readable fallback.

The registry is not recipe-specific. Registered domain-view families may cover
recipe/processing, item and inventory groups, enchantment choices, game-state
and settings summaries, knowledge documents, comparisons, progress, and tables.
Adding a family requires a closed common presentation model and a client-thread
provider; it never expands model authority.

Live viewer objects exist only for visible rows. They are created, ticked,
rendered, hit-tested, and released on the owning client thread. They are not
stored in the semantic AST, SQLite, bridge payloads, model context, or worker
threads. Durable components retain their validated reference, fallback, and a
closed detached presentation snapshot sufficient for the generic renderer.

### Tables, focus, and streaming geometry

Tables remain structural through layout. The layout owns measured columns,
wrapped cells, row heights, alignment, header state, bounds, and reference hit
regions. Wide layouts draw a native grid; narrow layouts deterministically
project each data row into a header-key/value card. Delimiter text is only a
last-resort narration fallback.

Keyboard focus, opened detail selection, and semantic action selection use
separate stable IDs. A missing focus ID is never focusable or selected. Tool
selection is correlated by invocation ID, not object identity or Tool name.

Streaming presentation keeps a stable row identity and a monotonic reserved
height for the mutable tail until that assistant segment becomes terminal.
Semantic validation may replace content inside that reservation but cannot move
the viewport upward. Auto-scroll follows only when the player already owns the
bottom anchor. Returning from settings refreshes capabilities before presenting
the replacement screen. Model selection is an explicit list choice and affects
future requests only.

### Local-first retrieval

Knowledge ingestion produces stable source/document/section identities and
heading-aware chunks while preserving the original document and evidence.
Retrieval combines exact resource/document matches, aliases and metadata,
Unicode lexical matching, and BM25-style document ranking. The implementation
remains useful offline and deterministic without an embedding endpoint.

An optional future embedding/reranking provider may add scores behind a narrow
retriever interface. It cannot replace exact-ID matches, provenance, evidence,
or the deterministic lexical path. Retrieval output is a set of references and
excerpts; it is not itself factual evidence beyond the referenced source.

### Future player memory

Conversation history, context summaries, player memory, and live game facts are
four separate classes. A future player-memory store may contain explicit player
preferences, pinned goals, and player-confirmed facts. Model-derived candidates
are not durable until the player confirms them. Every memory carries scope,
provenance, timestamps, status, and deletion/editability metadata.

Player memory never satisfies evidence requirements for current game state and
never silently overrides a Tool result. Contradictions are retained as explicit
versions until player resolution. Automatic write-all memory is forbidden.
This decision establishes the schema and authority boundary; no player-memory
write UI or automatic extraction is enabled by this Phase 4 increment.

## States and Transitions

- `native_view_unresolved -> native_view_resolving`: a visible, validated
  component enters the viewport and asks the registry for a compatible provider.
- `native_view_resolving -> native_view_ready`: one provider resolves the exact
  reference and publishes a visible-thread-owned view.
- `native_view_resolving -> native_view_fallback`: providers are absent, stale,
  unsupported, or fail; create the generic detached canvas or readable fallback.
- `native_view_ready/native_view_fallback -> native_view_released`: the row
  leaves the visible lifecycle, the screen closes, or the connection generation
  changes; release live provider state.
- `knowledge_index_building -> knowledge_index_ready`: a detached snapshot is
  indexed and atomically replaces the prior generation.
- `knowledge_index_building -> knowledge_index_degraded`: indexing fails; retain
  the prior valid generation and expose a source-scoped diagnostic.

## Invariants

1. Model output cannot name native widget classes, textures, slots, coordinates,
   callbacks, commands, URLs, or arbitrary view trees.
2. JEI/REI/mod-provided views are embedded only through verified public APIs and
   only for exact references; generic fallback never imitates a mod GUI.
3. Live viewer and Minecraft objects remain on the owning client thread and only
   for visible rows.
4. Every native view has deterministic generic or textual fallback and failure
   of one provider never breaks the conversation.
5. Table structure, focus identity, viewport ownership, and streaming row
   identity survive incremental rendering.
6. Retrieval retains stable provenance/evidence and works without embeddings.
7. Context summaries and player memories are derived context, never game-fact
   evidence; player-memory writes require explicit confirmation.

## Failure Semantics

- Exact native view cannot be resolved: `native_view_unavailable`; use the next
  provider, generic canvas, or readable fallback without failing the request.
- A provider throws, returns a stale display, or violates thread ownership:
  `native_view_failed`; release it and isolate the diagnostic to that provider.
- Table geometry is malformed or cannot fit: render the narrow key/value
  projection, then narration fallback if structural validation itself failed.
- Streaming replacement reports a smaller height: retain the terminal segment's
  reserved height until normal viewport reflow can preserve the anchor.
- Knowledge indexing fails: retain the last valid index or use the deterministic
  in-memory lexical path; never return fabricated empty knowledge.
- A model proposes a player memory without confirmation: keep it ephemeral and
  do not write durable state.

## Applies To

- semantic component registry, prompt guidance, bundled Skills, and codecs
- native domain-view registry, recipe presentation, JEI/REI adapters, generic
  canvas, tool-detail and assistant-inline rendering
- semantic table layout, focus routing, auto-scroll, and streaming row geometry
- model selector and settings-return refresh
- knowledge ingestion/index/retrieval contracts
- future player-memory design and acceptance gates
- deterministic common tests and retained Fabric/NeoForge graphical evidence

## Supersedes

This refines the native-rendering and controlled-component execution details of
SKMB-2026-07-18-005/018 and the viewport stability requirements of
SKMB-2026-07-19-020. It preserves their authority, evidence, history, and
failure boundaries.

## Superseded By

None.

