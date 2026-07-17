# Phase 3A Grounded Tool Contracts Design

## 1. Problem

Phase 2 captures useful facts, but `find_recipes` returns one flat record shape
and `player_context` exposes inventory without a machine-readable completeness
contract. A real model correctly counted nine ingredient slots, then had to
guess that a crafting table was required and questioned whether the inventory
was complete. More complex pack recipes amplify this ambiguity.

Phase 3A makes deterministic Java tools responsible for retrieval, identity,
counts, alternatives, and craftability. The model remains responsible for
intent, tool selection, route preference, and explanation.

## 2. Evidence contract

Every knowledge-bearing result carries one or more immutable evidence records:

```text
EvidenceMetadata
- authority: CLIENT_VISIBLE | SERVER_AUTHORITATIVE | RESOURCE_ASSET |
             INTEGRATION_API | DETERMINISTIC_TEST
- completeness: COMPLETE | PARTIAL | UNKNOWN
- capturedAt: Instant
- sourceId: namespaced stable source identifier
- provenance: namespaced origin identifier
- gameVersion: string
- loader: fabric | neoforge | common-test
- details: sorted namespaced string map for source-specific facts
```

No server address, API credential, filesystem path, or private implementation
object enters evidence. `details` is data, not an extension execution hook.

Snapshot metadata belongs to each source snapshot rather than one global
context because client recipes, server recipes, player state, and asset
knowledge can have different authority and completeness.

The normalizer rejects successful factual outputs that omit required evidence.
Failures identify the attempted source without claiming factual completeness.

## 3. Recipe domain

The stable common recipe model is split into summaries and complete details:

```text
RecipeSummary
- recipeId
- recipeType
- primaryOutputs
- workstation (optional)
- evidence

RecipeDetails
- recipeId
- recipeType
- layout (optional width, height, shaped)
- workstation (optional resource ID)
- ingredients
- catalysts
- fluids
- outputs
- byproducts
- processing (optional duration, energy, temperature)
- conditions
- evidence
- extensions: sorted namespaced JSON object map
```

Each ingredient requirement has a positive count, a consumed flag, and one or
more alternatives. An alternative records whether it is an item or tag, its
stable ID, and the completely resolved item IDs known to the source. Output
stacks preserve count and optional probability. Unknown data is represented as
absent/unknown, never as zero or an invented default.

Vanilla capture fills the fields the public recipe/display APIs prove. Mod
adapters may populate processing and namespaced extensions. Extensions cannot
override stable common fields.

Client recipe-display references are stable only for the active connection and
are marked `CLIENT_VISIBLE`; server RecipeManager IDs are
`SERVER_AUTHORITATIVE`. A client display entry must not use a fabricated global
recipe ID. Its evidence and opaque reference make its scope explicit.

## 4. Player inventory domain

`InventorySnapshot` separates the counted inventory from presentation state:

```text
- ownerId
- slots: every captured slot, including empty slots
- totalSlots
- selectedHotbarSlot
- mainHandSlot (when known)
- offHand
- complete
- evidence
```

The selected main-hand stack is not counted twice when it is already represented
by an inventory slot. Armor, curios, nested containers, nearby storage, and
external networks are excluded unless a future adapter explicitly declares and
labels them.

`player_context` may remain for broad questions, but recipe planning uses the
narrow `inspect_inventory` tool so the model receives only necessary player
data.

## 5. Tool surface

Phase 3A exposes these read-only tools:

### `tomewisp:search_recipes`

Accepts any non-empty combination of recipe ID, output item, input item, and
recipe type. Returns every matching summary in deterministic order with no
project-defined truncation.

### `tomewisp:get_recipe`

Accepts one source-scoped recipe reference and returns complete known details.
An expired client reference returns `stale_reference`.

### `tomewisp:find_item_usages`

Returns every recipe where the item is a consumed ingredient, catalyst, fluid
container input, output, or byproduct, preserving the role.

### `tomewisp:inspect_inventory`

Returns the complete captured inventory scope and evidence. It does not expose
ender chests, other players, server storage, or arbitrary block entities.

### `tomewisp:calculate_craftability`

Accepts a recipe reference and desired positive craft count. It allocates
available stacks to alternatives deterministically, reports allocated stacks,
missing requirements, maximum immediately craftable count, and whether the
answer is conclusive for the declared inventory and recipe scope.

Alternative allocation uses a deterministic capacity-matching algorithm rather
than greedy model arithmetic. It handles overlapping alternatives without
double-spending one stack. It does not recursively craft missing intermediates;
recursive production planning is a later capability.

### Compatibility

`tomewisp:find_recipes` remains as a deprecated compatibility alias during
Phase 3. Bundled Skills and traces migrate to the new tools. The alias is
removed only in a future versioned compatibility decision.

## 6. Context capture and freshness

Capture happens on the owning game thread after a queued request is selected to
start, matching the existing SKMB decision. A request uses one immutable source
snapshot so all of its tool calls reason over a consistent view. Results expose
the capture timestamp; the model may explicitly request a fresh query in a new
request but cannot mutate a running snapshot.

Client and server capture share domain types but not authority claims. Server
tools derive actor identity from the packet sender. Client tools use only the
current local player.

## 7. Validation and failure semantics

- Invalid resource IDs and empty searches return `invalid_arguments`.
- Unknown resources and no matches are successful empty factual results.
- Missing required source snapshots return `capability_unavailable`.
- A stale opaque recipe reference returns `stale_reference`.
- An incomplete recipe or inventory can produce a non-conclusive craftability
  result but never a conclusive `true` based on missing scope.
- Malformed extensions are rejected during adapter ingestion.
- No result count, ingredient count, slot count, text length, or extension byte
  limit is introduced. Metrics observe actual sizes for later evidence-based
  policy.

## 8. Tests

Tests cover exact matching, input/output/usage roles, deterministic ordering,
shaped and shapeless recipes, counts, tags, overlapping alternatives,
catalysts, byproducts, partial sources, stale references, no matches, empty
inventory, selected-slot deduplication, client/server evidence, codec round
trips, remote export policy, and complete text/result preservation.

The real-provider acceptance prompt must use at least `search_recipes`,
`get_recipe`, `inspect_inventory`, and `calculate_craftability` before answering.

