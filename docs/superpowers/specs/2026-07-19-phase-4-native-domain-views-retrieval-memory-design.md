# Phase 4 Native Domain Views, Retrieval, and Memory Foundation Design

## Outcome

Finish the gap between OpenAllay's validated semantic messages and genuinely
Minecraft-native data views. The result embeds viewer/mod recipe layouts when
available, provides a clear neutral fallback, renders real tables, stabilizes
streaming and selection behavior, improves local guide retrieval, and records a
safe foundation for later player memory.

## Native view architecture

`RichComponent` remains the model-facing closed vocabulary. It carries only a
validated stable handle plus narration/fallback. A `DomainViewBinding` resolves
that handle against immutable normalized Tool evidence and creates a detached
presentation model. A client `NativeDomainViewRegistry` selects a renderer.

```text
model component + same-request reference index
        -> validated DomainViewBinding
        -> detached presentation model
        -> JEI native / REI native / OpenAllay generic provider
        -> measured visible-row view + typed hit regions + narration
```

The initial high-value presentation models are:

- recipe/processing view: station, typed input/catalyst/output/byproduct slots,
  alternatives, amounts, probability, duration, energy, and temperature;
- item/inventory view: item grid with counts and deterministic ordering;
- game-state/settings view: titled sections with typed values and status badges;
- document/source view: title, section trail, excerpt, provenance and actions;
- table/comparison/progress views: structural layout rather than delimiter text.

Enchantment is the next native domain family. Its closed presentation model
will carry the target item, available enchantments, level/cost/compatibility and
evidence. A provider may use a public native recipe/viewer integration, while
the generic renderer presents a neutral option list. No live enchanting menu or
write action is introduced.

### Recipe providers

The JEI adapter re-resolves an exact `RecipeReference` on the client thread and
embeds its public `IRecipeLayoutDrawable`, forwarding draw, overlay, tick,
tooltip and bounded pointer behavior. The REI adapter does the equivalent using
the registered category/display widgets when a stable exact display can be
resolved. Neither adapter is recipe truth; both are optional presentations.

The generic recipe canvas is not a simulated mod screen. It uses a consistent
OpenAllay panel with labelled slot groups, arrows, station and processing facts.
It always remains available from the detached recipe snapshot.

## Semantic layout

`SemanticLayout` becomes a closed element plan rather than flattening every
block into text lines. Text lines, rules, tables and components each own measured
geometry. Table measurement performs column intrinsic sizing, proportional
shrink, cell wrapping and row-height calculation. If the usable width cannot
preserve readable cells, the same table becomes header-key/value cards.

Mutable assistant segments retain a stable presentation identity and reserved
height. The virtualizer applies height changes below the current anchor and
never moves a player-owned viewport. Auto-scroll uses an explicit bottom-owned
flag, not a render-time guess.

## Settings and selection

The top model control opens an anchored, clamped, scrollable list of all model
choices. Each row distinguishes selected, running, next-request and unavailable
states. Clicking a row directly selects it; no intermediate profile is chosen.

Opening/reopening the guide subscribes first, then asynchronously refreshes
capabilities. Active requests retain their captured model. Tool/source content
and selected detail use stable, separate identities.

## Retrieval

Knowledge documents are projected into heading-aware chunks without losing the
original source/document identity. Retrieval is local-first:

1. exact document/resource/reference matches;
2. alias, namespace and linked item/recipe matches;
3. Unicode lexical/BM25-style score over title, headings and body;
4. optional future embedding candidate generation/reranking;
5. stable deterministic tie-breaking and evidenced excerpts.

The first implementation must improve the existing deterministic index and
keep a narrow scorer/retriever seam. It must not introduce an external service,
sidecar, model call, or claim semantic-vector behavior when no embeddings exist.

## Player memory plan

The later memory system will have explicit `MemoryRecord` state with ID, player
scope, optional world/server scope, kind, content, provenance, created/updated
time, confirmation state and supersession links. Writes are explicit and
reviewable. Retrieval uses recency/relevance/scope but never injects memory as
Tool evidence. Users can view, correct, pin, forget and clear memories.

Phase 4 implements only documentation and narrow non-writing contracts needed
to avoid coupling retrieval/history to future memory. Persistent writes,
automatic extraction, UI management and synchronization require a later
implementation checkpoint.

## Verification

- common deterministic tests for component authority, recipe projection,
  provider fallback, lifecycle, tables, focus, selector and streaming anchors;
- JEI/REI API compatibility tests and both-loader builds;
- graphical Fabric evidence with JEI + Farmer's Delight embedded cooking layout;
- graphical fallback evidence with viewers unavailable;
- wide/narrow table screenshots and multi-profile selector screenshot;
- no secret/provider body/native live object in history, logs or reports.
