# SKMB-2026-07-20-026: Resource VFS and Context Projection

- status: accepted
- decided_by: designer
- approval_source: >-
    The designer explicitly required that all game resources be unified as a
    virtual filesystem ("Resources manager as file system"), delegated the
    detailed design with "直接按你的理解来，我相信你的理解", and required
    context management to follow mainstream Agent practice without another
    approval checkpoint. The designer then made Tool results as processable
    files and independent per-mod raw resource mounts explicit first-class
    requirements.
- date: 2026-07-20
- commit: pending
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: OpenAllay read-only resource namespace, Agent Tool surface, result projection, client/server routing, context assembly, and native presentation

## Context

The withdrawn `0.1.1-SNAPSHOT` emergency fix proved that limiting serialized
Tool JSON and adding a generic result reader does not solve the underlying
problem. It left domain Tools as the primary information architecture, lost
semantic grouping in a generic flattened projection, encouraged repeated
reads, and still made dynamic UI depend on model-authored component syntax.

The current `0.1.0-SNAPSHOT` code has valuable lower-level pieces: immutable
Minecraft snapshots, evidence-bearing normalized outputs, typed query stages,
ordered parallel Tool execution, durable chronological history, and native
presenters. The architectural defect is that those pieces meet at a
domain-specific Tool boundary instead of a uniform resource boundary.

The designer selected a resource-as-filesystem model. Examples include
`/item/minecraft/diamond_sword/damage` and
`/recipe/farmersdelight/cooking/apple_cider`. Future domains such as world
inspection can add mounts without changing the Agent's basic read/search
workflow.

## Decision

### One read-only virtual resource namespace

OpenAllay exposes game and knowledge information through an internal read-only
VFS. It is not the operating-system filesystem and is never mounted into the
host process. An absolute virtual path selects a registered mount, a canonical
resource identity, and optionally a field or related resource.

The canonical identity grammar is:

```text
/<mount>/<namespace>/<percent-encoded-resource-path>[/<field-or-relation>...]
```

The namespace and resource path remain separate so normal Minecraft IDs are
readable. A resource path containing `/` is percent-encoded as one identity
segment, preventing ambiguity between an ID and a child field. Paths are
canonicalized once, reject `.`/`..`, control characters, empty identity
segments, invalid percent encoding, and paths outside registered mounts.

A resource node may provide:

- exact typed value and immutable evidence;
- deterministic text projection for model reads;
- child entries and typed links to other virtual paths;
- a stable content kind and presentation hint;
- source generation, authority, completeness, capture time, provenance, game
  version, and loader;
- optional query fields derived from the actual captured codec/property tree.

The exact structured value remains OpenAllay's factual truth. Text is a
projection, not a second truth representation.

The namespace is **closed under Tool execution**. Every validated Tool result
is atomically published as an immutable resource at
`/result/<opaque-result-id>` before either its model or UI projection is made.
The same `read`, `glob`, `grep`, and `query` operations therefore compose over
game resources and prior Tool results. A large result is not a special spill
file and does not require a parallel `read_tool_result` protocol; it is another
evidence-bearing VFS node with typed children, links, lineage, and semantic
continuation state.

The `/mod` mount also provides a generic public-resource escape hatch for
content that has no typed adapter yet:

```text
/mod/<modid>/raw/assets/<namespace>/<encoded-resource-path>
/mod/<modid>/raw/data/<namespace>/<encoded-resource-path>
/mod/<modid>/raw/metadata/<known-public-manifest>
```

These paths represent logical resources obtained through loader and Minecraft
resource APIs. They never expose physical JAR locations, the host filesystem,
class/native-library bytes, signatures, credentials, or private runtime
objects. Text resources are readable and searchable; binary resources expose
only safe metadata, digest and presentation references. Pack precedence,
active/shadowed status and provenance remain explicit evidence. Typed mounts
may link to their originating raw resources without copying them.

### Mounts and generations

Each registered `ResourceMount` owns one path prefix and publishes immutable
generation snapshots. Initial mounts cover registry content, recipes, guides,
mods, player-visible game state, packs/settings/diagnostics, inventory and
fixed online knowledge. A dynamic `/result` mount contains completed Tool
results for the live session/connection. The future `/world` mount is reserved
but spatial world scanning remains unimplemented until a separate authority
decision.

Minecraft-owned data is captured only on its owning thread, detached into an
immutable snapshot, and atomically published. A request captures a
`ResourceView` consisting of the exact mount generations and actor/topology
authority available at submission. Reads during that request never drift to a
new reload generation.

Stable paths remain names; a read receipt also carries its generation. A path
whose selected generation no longer exists fails `stale_resource` rather than
silently rebinding. Cross-resource links are typed `(relation, targetPath,
label)` values and are validated inside the same captured view.

### Small familiar Agent Tool family

The model sees a small stable Tool family backed by the same VFS:

- `resource_list`: list direct children and metadata for one virtual path;
- `resource_read`: read one or more exact paths, fields, or ranges;
- `resource_glob`: discover paths by a bounded virtual path pattern;
- `resource_grep`: search model-facing text and indexed scalar fields under
  selected roots;
- `resource_query`: execute a closed typed pipeline for filter, project,
  expand, sort, group, aggregate, take, and link traversal.

Inputs support batches so independent paths do not require one model round trip
per item. All calls return results correlated to input index. These names and
semantics intentionally match the read/list/glob/grep workflow used by common
coding Agents while preserving OpenAllay's Minecraft authority boundary.

There is no operating-system shell, command parser, Java reflection, arbitrary
path, URL fetch, callback, write, or general program execution. A future
human-facing or test-only CLI may compile a familiar command string into the
same typed query AST, but model calls remain schema-validated data.

Domain-specific deterministic actions that are not information retrieval,
notably craftability allocation, remain narrow functions over VFS references.
They must consume exact structured values and publish their result back into
the same result/projection pipeline. Compatibility Tool IDs are removed from
the advertised model catalog after trace and bridge fixtures migrate; durable
history retains enough display metadata to render old entries without
re-executing them.

Completed results form an immutable lineage DAG: a result records its source
paths and prior result paths, and only already-published results may be input to
a new operation. Internally equal result values may be content-addressed and
deduplicated, while opaque public result IDs preserve invocation identity.
Result paths remain readable across turns in the same live session and
connection, then expire on session deletion, disconnect, or shutdown. Durable
history retains only safe display receipts; it does not resurrect executable
result resources after process loss.

### Result truth, model projection, and UI projection are separate

Each Tool completion produces `AgentToolResult` with four explicit views:

1. `truth`: exact normalized JSON plus evidence, used for validation, traces,
   deterministic calculations, client/server bridge integrity, and native UI;
2. `modelView`: concise line-oriented text plus a structural receipt, used only
   for provider continuation;
3. `uiView`: closed typed presentation data or a stable VFS reference, derived
   from truth without parsing model text;
4. `diagnostics`: redacted size, timing, generation, and projection metadata,
   never ordinary player/model content.

Provider codecs serialize `modelView` as Tool-result text. They never call
`gson.toJson(truth)` for model consumption. Internal validation and native
presenters continue to use truth directly.

### Semantic receipts and progressive reads

The initial result projection is chosen from the current request's remaining
input budget, not a fixed global byte count. A read that cannot fit returns the
largest complete semantic units that fit plus a receipt containing:

- canonical path(s), result kind, generation, authority and completeness;
- total record/section/line counts when known;
- returned ranges or record identities;
- available fields/sections and stable links;
- an opaque next cursor bound to actor, session, request, captured view and
  query plan;
- an explicit continuation example using the same VFS Tool.

Cursors advance by semantic units such as directory entries, records, table
rows, document sections, or text lines. They never point into serialized JSON
bytes. The next page is re-projected from exact truth and can choose a narrower
field selection. A forged, expired, cross-owner, cross-view, or stale cursor
fails without revealing another resource's existence.

`resource_read` may render concise prose, numbered text, key/value records, or
tabular rows according to content kind. It does not use generic dotted-key
flattening. `resource_query` works on typed values before rendering, so the
model does not need to parse and re-clean a large text dump.

### Context management

Context pressure is handled at four independent layers:

1. **Tool catalog disclosure:** keep the stable VFS family small; load
   domain-specific Skill procedures progressively instead of advertising many
   domain Tools.
2. **Immediate result projection:** budget every Tool group before it becomes
   provider input and return semantic receipts for unread remainder.
3. **Historical Tool-result editing:** preserve call/result pairing and recent
   active results, replace old result bodies with stable resource receipts and
   validated factual references, and never treat a natural-language summary as
   evidence.
4. **Conversation compaction:** when non-Tool history still exceeds the
   selected model budget, create a versioned source-hashed derived summary of
   goals, preferences, decisions and unresolved work while retaining factual
   VFS receipts separately.

The final provider-neutral request is estimated before every model dispatch,
including Tool continuations. It uses the selected model's resolved context
window and output reserve. Provider-native prompt caching, Anthropic context
editing, or OpenAI response compaction may be used by compatible adapters as
optimizations, but correctness and cross-provider session reuse depend only on
the common local projection.

### Agent progress and stopping

The Agent loop records the set of factual resource identities and fields made
available by each Tool group. Repeating an equivalent call is evaluated by
information delta, not only argument equality.

- New facts or new requested ranges permit continuation.
- A complete answer-sufficient result transitions to synthesis guidance.
- A no-progress call returns a compact receipt pointing to already-available
  paths and asks the model to answer.
- A second no-progress attempt does not discard prior evidence; the runtime
  requests final synthesis from existing context and terminates with a precise
  failure only if the provider still cannot produce a final answer.

Skill instructions teach domain-specific navigation and examples. They do not
grant mounts, permissions, paths, network access, or writes. Simple reads such
as listing installed mods do not require a Skill.

### Native UI

Tool chronology retains invocation identity, but first-class UI no longer
depends on model-authored component JSON. The selected VFS path and exact truth
choose a registered native presenter. A recipe path can resolve JEI/REI or the
generic recipe canvas; an item path can render item details; a document path
can render structured headings/tables. Model text remains narration and
fallback, not the source of component authority.

Debug detail pages read bounded diagnostic/model projections asynchronously.
They never stringify or lay out the full truth object on the client thread.

## States and transitions

1. `resource_snapshot_building -> resource_snapshot_ready`: a mount captures,
   validates and atomically publishes a detached generation; failure retains
   the prior valid generation and marks only that mount degraded.
2. `preparing -> resource_view_ready`: a request captures exact mount
   generations and player/topology authority before model dispatch.
3. `tool_wait -> resource_querying -> resource_result_ready`: a validated VFS
   operation executes against the captured view, validates exact truth, and
   publishes an immutable `/result/<id>` node before producing projections.
4. `resource_result_ready -> resource_cursor_ready`: unread semantic units are
   retained behind an owner/view-bound cursor when the model budget cannot
   include them.
5. `model_wait -> context_projecting -> model_wait`: every provider dispatch
   rebuilds and estimates its exact provider-neutral input.
6. `context_projecting -> compacting`: historical receipts and non-factual
   conversation memory are reduced only when needed.
7. `context_projecting|compacting -> failed`: no provider I/O starts when a
   structurally valid request cannot fit.
8. any request terminal/disconnect/session deletion -> cursor released: late
   reads fail and cannot rebind to another request or generation.

## Invariants

1. The VFS is read-only and cannot reach the host filesystem, arbitrary URLs,
   shell execution, reflection, commands, callbacks or mutations.
2. Live Minecraft objects never enter a mount snapshot, cursor, model result,
   bridge payload, history record or worker thread.
3. Every factual node and successful result retains evidence, authority,
   completeness, capture time, source, provenance, game version and loader.
4. Exact normalized truth is never replaced by its text, UI or diagnostic
   projection.
5. The model never consumes raw normalized JSON merely because it exists
   internally.
6. Native UI never parses model text to recover authority or exact game data.
7. One request reads one immutable `ResourceView`; paths and cursors never
   drift across generation, actor, session, request or connection.
8. Context assembly runs before every provider call and preserves provider
   protocol ToolUse/ToolResult structure.
9. Derived summaries are not evidence. Removing an old model-facing result
   body does not delete its exact truth or durable player-visible chronology.
10. Unknown mod-defined fields are discovered from public codecs or trusted
    Extension extractors and need no core field whitelist.
11. Every completed validated Tool invocation, including a structured failure,
    publishes one immutable, owner-scoped `/result` resource before any
    model/UI projection; subsequent Tools may compose over that resource
    through the same VFS operations.
12. Generic mod raw mounts expose only logical public resources and safe
    metadata; physical archives, host paths, executable code and unrestricted
    binary payloads are unrepresentable.

## Failure semantics

- Invalid path, glob, pattern, query stage, field, or range:
  `invalid_resource_query` with the closest valid mount/field vocabulary; no
  fuzzy execution.
- Ambiguous natural identity: return deterministic matching paths and require
  exact selection; never choose arbitrarily.
- Missing or degraded mount: `resource_unavailable` or partial evidence for
  that mount while unrelated mounts remain usable.
- Stale path generation or cursor: `stale_resource`; a new discovery/read is
  required explicitly.
- Unauthorized or cross-owner read: `resource_forbidden` without existence
  leakage.
- Result cannot fit the current model projection: return a semantic receipt and
  cursor; never send raw truth, generic dotted flattening, or silent truncation.
- One atomic semantic unit cannot fit: return its metadata/structure and a
  narrower field/line read instruction; do not split structured JSON bytes.
- Final provider request cannot fit after projection and permitted compaction:
  `context_compaction_failed` before HTTP dispatch.
- Mount reload fails: retain the prior valid generation and mark the new
  generation failed; never publish half a tree.
- Optional provider or online source fails: isolate that mount/source and keep
  local/current-game mounts usable.
- Result path expired or outside its live session/connection: return
  `stale_resource`; never reconstruct it from display history or re-execute the
  originating Tool implicitly.
- Raw resource is binary or disallowed: return safe metadata or
  `resource_content_unavailable`; never encode arbitrary bytes into model
  context.
- Model attempts an OS path, shell command, URL, write or unregistered mount:
  reject as `operation_not_permitted`; never reinterpret it as a virtual read.

## Applies to

- common Tool/result/runtime/context/model protocol types
- registry, recipe, guide, knowledge, mod, game-state and future Extension data
  providers
- client/server Tool placement and bridge chunking
- Skills, system prompt, trace replay and deterministic live acceptance
- GuideService history, Tool chronology, native presenters and Debug UI
- Fabric and NeoForge capture/reload/lifecycle adapters

## Supersedes

- Supersedes SKMB-2026-07-19-020 invariants 65-66 only for the Agent-facing
  information architecture: player-observable state, recipes and guides become
  mounts under one VFS instead of separately advertised retrieval Tools. Their
  capture, authority and deep-content boundaries remain applicable.
- Supersedes SKMB-2026-07-19-024 typed virtual-dataset Tool surface and
  `result_too_large` terminal behavior. Its ordered parallel execution,
  provider recovery and fixed-origin knowledge boundaries remain applicable.
- Refines SKMB-2026-07-18-008: exact current-request truth remains protected
  internally, while the provider receives a budgeted semantic projection and
  historical receipts rather than complete normalized JSON.

The historical, rolled-back `SKMB-2026-07-20-025` and its bounded dotted-KV
design are withdrawn and are not authoritative. The identifier is intentionally
not reused.

## Superseded by

None.
