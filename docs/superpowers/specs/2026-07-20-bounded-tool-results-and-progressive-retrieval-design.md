# Bounded Tool Results and Progressive Retrieval Design

## Status

Accepted emergency correction for `0.1.1-SNAPSHOT`. This is a post-Phase-4
stabilization change, not Phase 5.

## Incident and evidence

A real Fabric session sent a later DeepSeek continuation with 731,288 input
tokens. The request had accumulated complete normalized Tool JSON, including
wide recipe results, in the provider transcript. Clicking the selected Tool
card in Debug Mode then attempted to format and render the same unbounded JSON
on the client thread and made the game unresponsive.

The same walkthrough exposed two adjacent defects:

- context budgeting ran before the first model turn but not before each
  Tool-result continuation;
- metadata refresh skipped custom OpenAI-compatible endpoints, wrote no
  `model-metadata.json`, exact-matched only OpenRouter-style IDs, and still
  reported success;
- MC百科 search returned result-list excerpts but could not retrieve the
  selected article body.

These observations satisfy the repository requirement for operational evidence
before introducing an output projection limit.

## Goals

1. No individual Tool result can fill a model context or freeze a UI panel.
2. Limiting the immediate projection must not destroy the exact result.
3. The Agent can progressively describe, search, and page through a retained
   result by opaque handle.
4. Every model dispatch, including a Tool continuation, is budgeted locally
   before HTTP starts.
5. Metadata refresh can reuse a uniquely identified underlying model across
   providers and truthfully reports cache hits, misses, and unresolved IDs.
6. A fixed-origin MC百科 search hit can be opened, sanitized, indexed, and
   progressively read without exposing arbitrary web fetch.

## Non-goals

- No shell, filesystem path, URL, callback, script, or arbitrary program is
  exposed to the model.
- No general web browser or unrestricted Web Fetch Tool is added.
- No factual success is silently discarded or represented as complete after a
  projection was shortened.
- No provider-specific orchestration loop is introduced.

## Reused prior art

This design adapts two validated entries from the local agentic hoard:

- `agent-output-truncation-spill-file`: keep a bounded preview, retain the full
  output, and return an exact continuation instruction;
- `reversible-context-compression-with-on-demand-retrieval`: insert a compact
  representation by default while preserving the original behind a
  machine-readable handle.

OpenAllay keeps those cores but replaces arbitrary spill-file paths with an
opaque, scoped resource store. This is required because model-authored paths
and cross-session access violate OpenAllay's authority boundary.

## Architecture

### 1. Result resources

`ToolResultResourceStore` owns exact normalized Tool results. Each stored result
has an opaque `resultRef`, owner `(actorId, sessionId, requestId, invocationId)`,
content type, UTF-8 byte count, structural summary, SHA-256 digest, capture time,
and lifecycle state. A reference never contains a path or URL.

The store is connection/session scoped. It releases request resources when the
associated transcript entry is removed, the session is deleted, the player
disconnects, or the runtime shuts down. Durable history stores the bounded
projection and resource descriptor, not the exact spill. After process loss an
old handle is explicitly `resource_expired`; it is never rebound to another
result.

The initial implementation may keep exact UTF-8 JSON in a private runtime
store, but the interface is streaming and does not promise an in-memory
representation. This permits a later compressed/file-backed implementation
without changing Tool or model contracts.

### 2. Output governor

`ToolResultGovernor` runs immediately after normalization and before an
`AgentToolResult` is published, traced, persisted, sent to the provider, or
projected into the UI.

The exact normalized JSON always stays internal. The model receives a
deterministically rendered KV/CLI-style text projection of at most 8 KiB.
Above that projection boundary, the governor stores the exact JSON and appends
a retrieval reference:

```text
status = success
recipes.count = 46
recipes[0].recipeId = minecraft:repeater
evidence[0].sourceId = openallay:recipe_catalog
evidence[0].authority = CLIENT_VISIBLE
more = true
result_ref = result_…
next = read_tool_result describe/read/search
```

The projection preserves status, every required evidence field, stable game
references, exact total counts, schema/field summaries, and as many complete
KV records as fit. It never cuts a UTF-8 sequence and never labels the
projection complete. The 8 KiB boundary limits one continuation payload; it is not a
limit on stored facts, recipe count, document length, or history length.

Domain projectors may provide better recipe, inventory, game-state, knowledge,
and analytical summaries. An unknown Tool still receives the generic
structural projection. A projector may make the result smaller but cannot omit
the retrieval handle when exact data was spilled.

### 3. One progressive read Tool

OpenAllay exposes one Tool, `openallay:read_tool_result`, instead of one Tool per
domain. Its closed input is:

```java
record Input(
        String resultRef,
        Action action,
        String cursor,
        String query,
        String jsonPointer) {}

enum Action { DESCRIBE, READ, SEARCH }
```

- `DESCRIBE` returns types, JSON Pointer paths, counts, byte size, and examples.
- `READ` returns the next complete records or text segment from an opaque
  cursor, optionally projected at one JSON Pointer.
- `SEARCH` performs literal/token search within the retained result and returns
  matching paths plus bounded snippets. It does not accept regex, shell syntax,
  code, or paths.

Every response is at most 8 KiB and returns `nextCursor` when more is
available. Cursors are store-issued, tamper-evident, bound to the resource and
owner, and expire with it. The Tool cannot read a resource from another actor,
session, request owner, or server connection.

### 4. Provider context gate

`GameGuideAgent` invokes a single dispatch preparation path before every
`ModelClient.complete`, including after same-turn parallel Tool results.
Preparation estimates system prompt, Tool schemas, all messages, protocol
structure, maximum output reserve, and one continuation reserve.

Older history may use the existing deterministic reduction and summary
checkpoints. Current-request Tool results are already bounded by the governor,
so their exact spill is not protected provider content. If the bounded current
request still cannot fit, the Agent terminates locally as
`context_compaction_failed`; it does not send an inevitably rejected request.

Same-turn repeated calls to a batch-capable Tool are coalesced before execution
when the transformation preserves invocation correlation. Recipe batch output
must not duplicate the same rows in both a flattened list and per-query lists.

### 5. UI projection

Normal and Debug Mode consume only bounded `GuideToolDetailView` data. Debug
Mode shows invocation identity, evidence, projection statistics, schema, the
first bounded page, and explicit previous/next controls. It never stores a deep
copy of unbounded normalized JSON and never formats the entire result during a
render tick.

Selecting a Tool card is therefore O(visible page), not O(full result). Page
loading occurs asynchronously and client-thread mutation is limited to the
completed immutable page.

### 6. Cross-provider metadata and cache

Trusted public metadata is fetched independently of the configured inference
host. A configured provider model ID is resolved in this order:

1. exact provider ID;
2. exact trusted canonical ID;
3. unique providerless alias, such as `deepseek-v4-flash` matching
   `deepseek/deepseek-v4-flash`;
4. unresolved when the alias has zero or multiple trusted matches.

The cache stores canonical identity, aliases, context window, output limit when
published, source, capture time, and match method. It remains credential-free.
Custom provider `/models` responses may add IDs and limits when present, but a
list containing only IDs is not metadata success.

Manual refresh returns per-profile `UPDATED`, `CACHE_HIT`, `UNCHANGED`,
`NOT_FOUND`, `AMBIGUOUS`, or `SOURCE_UNAVAILABLE`. The UI may no longer display
“refresh completed” when every profile was skipped. A manual context override
remains authoritative, while settings display the discovered value and source
beside it and offer an explicit Auto mode; refresh never silently overwrites a
manual override.

### 7. MC百科 article retrieval

MC百科 remains a fixed-origin integration. Search results are normalized to an
opaque document identity plus an allowlisted `https://www.mcmod.cn/` reference.
`OnlineKnowledgeDocumentService` may fetch only the exact reference issued by a
preceding search result in the same scoped cache. Redirects must remain on the
allowlisted host.

The adapter removes scripts, styles, navigation, comments, forms, and active
content; extracts title, headings, paragraphs, lists, tables, and inert internal
references; normalizes whitespace; and records partial public-documentation
evidence. The sanitized document enters the same result-resource path, so long
articles are searched and paged instead of copied wholesale into context.

`openallay:get_knowledge_document` accepts the returned source/document ID and
uses the online document service when the source is online. The model never
supplies an arbitrary URL.

## Failure semantics

- Spill/storage failure: return `tool_result_projection_failed`; do not send the
  full result as fallback.
- Invalid, expired, or cross-owner handle: `resource_expired`,
  `resource_not_found`, or `resource_forbidden` without existence leakage.
- Malformed cursor or JSON Pointer: `invalid_arguments` without reading data.
- Progressive page still exceeds its projection budget: emit fewer complete
  records; if one atomic record alone is too large, return its structural
  description and a text-segment cursor.
- Current continuation cannot fit after projection/compaction:
  `context_compaction_failed` before HTTP dispatch.
- Metadata has no unique match: preserve the prior cache and report
  `metadata_not_found` or `metadata_ambiguous`.
- MC百科 page timeout/parse/layout change: source-scoped diagnostic; local
  guides, Minecraft Wiki, and other Tools remain usable.

## Acceptance

- A synthetic multi-megabyte Tool result produces a <=8 KiB human-readable provider payload,
  exact byte/count metadata, a working handle, and complete reconstruction by
  pages.
- Cross-session/cross-player reads and forged cursors fail closed.
- Selecting a megabyte-scale Tool card never deep-copies or formats the full
  JSON on the client thread.
- A fake provider proves that every continuation is budgeted before HTTP.
- `deepseek-v4-flash` uniquely resolves to trusted canonical metadata, writes a
  cache entry, and reports the true refresh outcome on a custom endpoint.
- MC百科 search -> selected article -> sanitized body -> progressive search and
  read is covered by fixed HTTP fixtures.
- Focused deterministic regressions prove bounded provider/UI projections,
  progressive reconstruction, per-continuation budgeting, truthful metadata
  caching, and fixed-origin article reading. For this emergency release these
  regressions plus both-loader builds are the release gate; a long graphical or
  live-provider matrix is explicitly not required.
