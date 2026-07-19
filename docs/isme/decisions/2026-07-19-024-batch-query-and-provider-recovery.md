# SKMB-2026-07-19-024: Batch Query, Parallel Tool Turns, and Provider Recovery

- Status: accepted
- Date: 2026-07-19
- Scope: Phase 4 final live-provider acceptance
- Patterns: A, B, C, D, E, F

## Decision basis

```yaml
decision_basis:
  decision_id: SKMB-2026-07-19-024
  trigger: >-
    Real Fabric sessions repeatedly expanded one player question into four or
    more independent recipe calls, produced a 17 KiB catalog result, then
    failed on a later provider request with HTTP 400 or a transient TLS/network
    transport failure. A client-visible biome question was also routed to the
    server-world query branch and incorrectly reported a permission failure.
  owner: GameGuideAgent, ModelRequestScheduler, game-content query tool, knowledge adapters
  lifecycle: one Agent request and its captured immutable context
  concurrency_scope: independent ToolUse blocks returned by one model turn
  selected_defaults:
    parallel_execution: start independent calls together and append results in original call order
    bulk_interface: runtime-described typed property trees plus closed query/batch inputs, never a shell or arbitrary program
    property_discovery: schema is derived from captured codec output; domain field names are never a core whitelist
    transport_retry: at most two automatic retries before any response event is published
    http_4xx_retry: never retry; classify a bounded redacted provider error body
    online_knowledge: fixed-origin Minecraft Wiki and MC百科 search adapters with local retrieval preserved
    observable_location: biome, dimension, coordinates, and direction are client diagnostics, not server queries
    prompt_assembly: ordered stable sections with mandatory metadata-first Skill preflight
    skill_disclosure: name and description at startup, SKILL.md on activation, one exact reference on demand
  authority:
    - user delegated all Phase 4 decisions to the recommended implementation
    - user explicitly authorized Tool, Skill, and knowledge-source expansion
    - user explicitly required real DeepSeek long-task acceptance before release
  forbidden:
    - arbitrary URLs, shell syntax, scripts, reflection, paths, callbacks, or mutations
    - unordered Tool results or ToolUse/ToolResult pairing changes
    - retry after any visible model delta, response body progress, or Tool call
    - raw provider body, endpoint, credential, or exception exposure
    - treating online search snippets as complete game-state evidence
```

## Context and evidence

Retained local history showed two small/medium failed requests and one large
failed request after valid Tool chronology. A direct protocol probe against the
selected DeepSeek-compatible endpoint accepted 1, 2, 3, 4, 5, and 8 Tool results
in one assistant continuation, disproving a fixed result-count limit. The same
endpoint also produced a TLS connection failure during an otherwise valid probe,
matching the later in-game `model_transport_error`.

The earlier runtime executed every same-turn call through a serial
`CompletableFuture` chain. This increased wall time and encouraged the model to
reissue narrow calls. The content catalog returned every match, so one broad
namespace query produced a large result that was then followed by more narrow
recipe calls. These are independent defects even where a provider rejects the
request for a separate reason.

## Selected behavior

### One model turn with multiple Tool calls

All calls are validated and assigned their original index before execution.
Independent calls start without waiting for prior calls. Each completion is
captured into its indexed slot. Only when all slots settle does the Agent append
one complete Tool-result group in original ToolUse order and continue the model.
Tool cards keep their invocation IDs; completion events may arrive as work
finishes, while provider history remains ordered.

Repeated-call detection is decided from the immutable pre-turn outcome map.
Equivalent duplicates inside one turn execute once and receive correlated
`no_new_information` results for the duplicates. Cancellation fails the turn,
suppresses late events, and never emits a partial provider continuation.

### Bulk and analytical queries

High-volume domains expose typed batch inputs. The game-content catalog keeps
its simple localized search and adds a closed pipeline over detached virtual
datasets. Operations are an enum-backed JSON AST: search/filter, projection,
sort, group, aggregate, array expansion, and take. Before an unfamiliar query,
`describe` derives RFC 6901 JSON Pointer fields, encoded types, coverage,
examples, and legal operations from the actual captured rows. The query engine
has no Minecraft-domain field whitelist. There is no parser for shell text and
no access to the real filesystem.

The runtime never silently truncates a factual success. When a projection is
too large for safe model use, it returns `result_too_large` with the exact row
count and asks for a projection/filter/aggregate/take operation. The observed
17 KiB broad result is sufficient operational evidence for this explicit
failure boundary; the caller selects the desired result size rather than the
runtime inventing an answer-changing cap.

All persistent item data components are encoded through their registered public
codecs into a detached typed property tree. A mod-added component or nested
field therefore appears in the next schema without a core change. Arrays can be
expanded before filtering, sorting, or aggregation. Non-component public
registry facts use the same property tree. A mod that exposes facts only through
a bespoke public API may contribute a trusted Extension extractor; private
reflection is forbidden. Recipe search accepts a list of independent criteria
and returns results correlated by input index. The deprecated single
`find_recipes` projection remains compatibility-only.

### Prompt assembly and Skill activation

The provider-neutral system prompt is assembled from independently testable,
ordered sections. Skill preflight appears before the catalog and before domain
execution guidance. For a complex, multi-step, or unfamiliar workflow, a Skill
whose description clearly matches that workflow must be loaded before its
domain Tools or answer. The single most-specific match is selected, with at
most one up-front load. A simple obvious one-Tool lookup proceeds directly;
shared Tool membership alone does not make a Skill relevant. Casual
conversation also proceeds without a Skill.

Only escaped name and description metadata enter the catalog. Loading the
Skill returns its main instructions and its declared reference names. A second
call with an exact reference returns only that file. Skill text remains
procedural, never expands the registered Tool set, and never grants authority.
Prompt adherence is covered by deterministic and live trace acceptance, while
correctness and security never depend on the model obeying prose alone.

### Provider failures and retry

Model HTTP adapters consume only a bounded error prefix and extract allowlisted
JSON fields (`type`, `code`, and a classified message category). HTTP 400 is
reported as `model_request_rejected`, `model_context_rejected`, or
`model_protocol_rejected`; it is never automatically retried.

A connection/DNS/TLS/reset failure may be retried only if the attempt emitted no
response-start, text, reasoning, Tool-use, or usage event. The endpoint
scheduler performs at most two retries with cancellable 1 s then 2 s backoff.
Once any response progress exists, retry would risk duplicated visible content
and is forbidden. Timeouts retain their existing explicit-retry behavior.

### Fixed online knowledge

Knowledge search retains the local lexical index and may additionally query
only the registered Minecraft Wiki MediaWiki API and MC百科 search endpoint.
Adapters share the JDK HTTP transport, use HTTPS, do not accept model-authored
URLs, and fail independently. Results are partial public-documentation evidence
with stable source IDs and inert source references; they never gain Tool or
game-state authority.

### Player-observable location

Biome, coordinates, dimension, direction, yaw, and pitch come from the captured
client diagnostics position category. They never require server command
permission. `WORLD_QUERY` remains limited to time, weather, difficulty, world
border, and spawn. Known location aliases supplied to that branch return a
model-visible corrective result pointing to diagnostics instead of a permission
failure.

## Failure semantics

- One Tool failure becomes its correlated result; unrelated same-turn calls
  still settle and remain usable.
- A cancelled parallel turn emits no partial continuation and suppresses late
  completion events.
- A malformed pipeline fails `invalid_tool_arguments` without executing any
  stage.
- A guessed or stale property path fails with `invalid_arguments` and directs
  the model to schema discovery; it is never silently mapped to another field.
- Sorting/grouping a multi-valued path fails until its parent array is expanded.
- An oversized query fails `result_too_large` with exact cardinality and
  available fields.
- An online source timeout or parse failure is source-scoped; local and other
  fixed sources remain available.
- Provider 4xx bodies are never copied into player text, logs, traces, or
  durable history.
- A transport retry exhaustion ends with a friendly retryable transport
  failure and retains actual chronology.

## Acceptance evidence required

Deterministic tests must cover ordered parallel completion, cancellation,
duplicate calls, batch recipe correlation, schema discovery, nested-array
expansion, generic component codec capture, pipeline validation/aggregation,
fixed-origin knowledge degradation, redacted HTTP error
classification, retry-before-progress, and client biome lookup. Final release
also requires retained redacted live DeepSeek runs for ordinary, bulk, complex,
and multi-turn questions on a real Fabric client plus both-loader production
builds.
