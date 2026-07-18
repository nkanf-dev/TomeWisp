# SKMB-2026-07-18-018: Semantic Messages and Windowed History

- status: accepted
- decided_by: designer delegation
- approval_source: the designer approved the consolidated Phase 4 semantic-rich-message and long-history design, then delegated all remaining Phase 4 implementation decisions to the agent's best judgment
- date: 2026-07-18
- commit: 9655dd2
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: safe Markdown, semantic references, controlled components, streaming parsing, incremental history persistence, context reads, GUI paging, virtualization, and auto-scroll

## Context

Phase 4 already preserves the real Agent chronology, durable partitions,
compaction checkpoints, typed tool cards, and normal/debug privacy boundaries.
Assistant text is still rendered as wrapped plain text, however, and the
history store still replaces and reloads a complete partition. That cannot
satisfy semantic rich messages or genuinely long histories.

The final Phase 4 package must add richer presentation without giving model
text authority, and must separate the model-context read window from the GUI
viewport so neither path materializes an unbounded transcript.

## Decision

### Semantic content

TomeWisp uses `org.commonmark:commonmark:0.28.0` plus its GFM table extension
as a narrow parser dependency. The library AST never becomes the product or
persistence contract. Common code immediately translates supported nodes into
a versioned sealed TomeWisp semantic tree. HTML, image/embed nodes, link
destinations, and unsupported extension nodes become readable literal/fallback
text with no action.

Inline Minecraft references use the closed syntax
`[[tw:<kind>|<target>|<optional label>]]`. Registered kinds are item, block,
fluid, entity, biome, dimension, tag, recipe, key, source, and evidence. A raw
resource target may be resolved on the owning Minecraft thread for an icon or
localized name, but it is explicitly presentation-only and never creates
evidence. A stable source/recipe/evidence handle is actionable only when it is
present in the same request's validated tool/source reference index.

Controlled components use a fenced `tomewisp-component` block containing one
strict envelope with `schemaVersion`, registered `type`, type-specific
references, and allowlisted presentation properties. It is not an arbitrary UI
tree. Version 1 registers item rows, recipe grids, ingredient checks,
craftability summaries, progress steps, source summaries, status badges, and
bounded choice groups. Unknown keys, types, versions, references, actions, or
malformed values fail closed to the block's readable text equivalent.

No semantic node can name a class, script, callback, command, URL, texture,
path, NBT selector, Tool, permission, network request, or world mutation.
Interactive nodes emit only registered typed intents already implemented by
TomeWisp services. Every component stores a non-empty text/narration fallback.

Streaming parsing is block-incremental. Completed immutable blocks are cached
by content hash; only the mutable tail is reparsed. An incomplete Markdown or
TomeWisp token remains literal text until it closes and validates. Terminal
assistant segments persist the strict semantic tree plus fallback text;
streaming cache state is never durable.

### Durable history and context

The current unshipped history schema is replaced cleanly by schema version 4.
There is no migration branch. The normalized schema assigns stable session and
request sequence values and upserts only changed session/request/timeline/
message/checkpoint records in one ordered transaction. It no longer deletes
and reinserts an entire partition for each event.

Initial connection load reads partition/session metadata and interrupted-state
recovery only. It does not materialize all request rows. GUI reads use an
exclusive sequence/ordinal cursor and a positive caller-requested window size
derived from the current viewport. There is no product-wide maximum page,
history, or row count. The store returns the requested neighborhood, total
metadata, and earlier/later cursors; the service retains only the visible
neighborhood plus active request entries and evicts off-window render data.

Model-context reads are a separate ordered repository operation. The selected
model topology supplies its actual context budget. The store streams the newest
eligible messages/checkpoints until that budget is allocated, verifies
checkpoint source hashes from durable rows, and returns a provider-neutral
context seed. It never depends on the GUI page and never treats a summary as
evidence. A server model advertises its context budget in the strict bridge
protocol; absence or mismatch fails explicitly rather than guessing a default.

At most one GUI page load per `(actor, session)` is active. A new viewport may
supersede an obsolete not-yet-published read; disconnect, deletion, session
replacement, and shutdown generation-check late completions. Page failure
retains the current visible window and exposes a retryable diagnostic. Context
load failure fails the request before provider dispatch; it does not fall back
to incomplete history.

### Native rendering and scroll ownership

The screen uses a pure virtualizer over stable row identities and cached
measured heights. It measures/renders only the visible neighborhood, creates
heavy item/component state only for visible rows, and releases it when rows
leave the neighborhood. An in-place tool or streaming-tail update invalidates
only its row and later offsets.

Auto-scroll follows new content only while the viewport is already at the
bottom. Scrolling up transfers ownership to the player until they explicitly
return to the bottom. Loading an earlier page preserves the same anchor row and
pixel offset. Keyboard focus and narration follow stable semantic node IDs;
color is never the only status signal. Presentation animation is optional,
stored in `display.json`, and never owns state.

## States and Transitions

- `semantic_tail_literal -> semantic_tail_validated`: a token/block closes,
  parses, passes the reference/component validator, and atomically replaces
  only the mutable tail.
- `semantic_tail_literal -> semantic_tail_literal`: input remains incomplete or
  malformed; keep readable text and expose no action.
- `history_metadata_loading -> history_window_idle`: partition/session metadata
  and interrupted recovery load without request-body hydration.
- `history_window_idle -> history_page_loading`: the GUI requests one viewport
  neighborhood for the current actor/session generation.
- `history_page_loading -> history_window_idle`: the matching page merges and
  the anchor is preserved, or the read fails and the prior window remains.
- `preparing -> context_loading -> model_wait`: the selected topology's budget
  produces a structurally valid context seed before any provider request.
- `context_loading -> failed`: durable context cannot be read/validated; send no
  provider request and preserve all history.

## Invariants

1. Model text can select only registered versioned semantic types; it cannot
   create authority, permissions, callbacks, network access, or mutations.
2. Raw resource existence is presentation-only. Actionable stable handles must
   originate in the same authorized request context.
3. HTML, URLs, images/embeds, unknown components, and malformed references have
   readable non-interactive fallbacks.
4. Original assistant fallback text and durable history remain recoverable even
   when semantic parsing or rendering fails.
5. GUI paging and model context selection are independent and neither requires
   loading the complete partition into memory.
6. Context is budgeted by the actually selected model/topology; model/provider
   switches reuse provider-neutral history but recalculate the read window.
7. One ordered repository owns all history writes, page reads, context reads,
   deletion, and reset; no Minecraft-owned thread waits on SQLite.
8. Late page/context completions cannot publish into a replaced actor, scope,
   session, request, or deletion generation.
9. Auto-scroll never moves a player who is reading earlier history.
10. Every visual semantic state has text, narration, and non-color status.

## Failure Semantics

- CommonMark conversion or semantic token is malformed:
  `semantic_content_invalid`; retain readable literal/fallback text and expose
  no semantic action.
- Component kind/version/property/reference is unknown or unauthorized:
  `semantic_component_unsupported`; render its text fallback only.
- Raw Minecraft resource does not resolve: `semantic_reference_unresolved`;
  show the supplied readable label/target with no icon or action.
- A page is already loading for the same generation: coalesce the identical
  viewport or supersede it; never enqueue an unbounded read backlog.
- Page read fails: `history_page_failed`; retain the current window and allow an
  explicit retry.
- Context read/hash/schema validation fails: `history_context_failed`; fail the
  request before provider dispatch and preserve history.
- Unsupported schema version: `history_schema_unsupported`; mutate nothing and
  require explicit development reset under SKMB-2026-07-18-011/014.
- Renderer cannot create a native view: retain fallback text and narration;
  never crash the screen or fabricate a successful component.

## Applies To

- semantic AST, strict codecs, CommonMark adapter, streaming block cache
- semantic reference index and controlled component registry
- Agent prompt syntax and assistant timeline persistence
- history schema/store/repository incremental commit, metadata, page, and
  context operations
- GuideService page/context state and strict bridge context-budget protocol
- Guide UI virtualization, native semantic renderer, focus, narration,
  animation, and typed actions
- deterministic parser/authority/persistence/race/performance tests and final
  Fabric/NeoForge real-client acceptance

## Supersedes

This replaces only SKMB-2026-07-18-006's temporary ordered full-partition
save/load execution default with ordered incremental commits, metadata reads,
context reads, and viewport pages. It executes the semantic-rich-message and
long-history requirements in SKMB-2026-07-18-005 while preserving the authority
and failure invariants in SKMB-2026-07-18-006, 008, 010, 011, 014, and 016.

## Superseded By

None.
