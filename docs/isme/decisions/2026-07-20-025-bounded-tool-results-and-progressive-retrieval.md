# SKMB-2026-07-20-025: Bounded Tool Results and Progressive Retrieval

- Status: accepted
- Date: 2026-07-20
- Scope: `0.1.1-SNAPSHOT` emergency post-Phase-4 stabilization
- Patterns: A, B, C, D, E, F, G

## Decision basis

```yaml
decision_basis:
  decision_id: SKMB-2026-07-20-025
  trigger: >-
    A real Fabric request reached 731,288 provider input tokens after complete
    normalized Tool outputs accumulated in one request. Selecting the Tool
    detail card then made the game unresponsive because Debug Mode copied and
    rendered the same unbounded JSON. Manual metadata refresh skipped the
    custom endpoint, created no cache, and nevertheless reported success.
  owner: GameGuideAgent, AgentToolExecutor boundary, Guide Tool detail projection, model metadata bootstrap, online knowledge adapters
  lifecycle: one connection-scoped session and its retained Tool-result resources
  concurrency_scope: all Tool completions and every subsequent model dispatch
  selected_defaults:
    immediate_model_projection: at most 8 KiB UTF-8 KV/CLI-style text per Tool result
    progressive_page: at most 8 KiB UTF-8 with complete KV records
    full_result: retained behind an opaque owner-bound handle
    read_surface: one closed DESCRIBE/READ/SEARCH Tool
    dispatch_gate: recompute context budget before every model request
    ui_detail: bounded structured projection; never full normalized JSON
    metadata_identity: exact canonical ID or unique providerless alias
    online_document: only a fixed-origin reference issued by prior search
  authority:
    - user declared this an emergency fix and a new goal
    - user explicitly required truncation plus a progressive read strategy
    - user explicitly requested reuse of the local hoard approach
    - user required metadata/cache repair, MC百科 article retrieval, testing, and 0.1.1 snapshot publication
    - user classified this as a major bug and required a fast release after basic focused tests rather than a complex E2E matrix
  forbidden:
    - passing an over-budget full Tool result to a model or UI
    - deleting exact facts merely to satisfy the immediate projection
    - model-authored paths, URLs, regex programs, shell syntax, or callbacks
    - cross-actor, cross-session, cross-connection, or stale-handle reads
    - reporting metadata refresh success when no profile was resolved or reused
    - reading an arbitrary MC百科 URL not issued by the fixed search adapter
```

## Selected behavior

Complete normalized Tool results cross a mandatory governor before any model,
trace, history, event, or UI projection. Results above the selected byte
boundary are retained in a scoped resource store and replaced by a truthful
bounded projection carrying total size/counts, evidence, stable references,
schema hints, an opaque handle, and explicit incomplete state.

One common read Tool progressively describes, searches, and reads retained
results. Each response is itself bounded and cursor-based. Exact data stays
recoverable during its owner lifecycle, while model and UI costs are bounded.
The store is not a general filesystem and never accepts a path or URL.

Every Agent continuation re-enters context preparation before HTTP dispatch.
Historical compaction remains reversible and source-preserving. Current Tool
results need no irreversible compaction because the exact value lives behind
the resource handle. A request that still cannot fit terminates locally.

Debug Tool details project a bounded immutable structured view. Selecting a card never
deep-copies the full normalized JSON and never performs proportional-to-result
formatting on the Minecraft client thread.

Trusted model metadata is a canonical model property, not an inference-host
property. A custom provider ID may reuse trusted metadata only through an exact
canonical match or a unique providerless alias. Ambiguity fails closed. Refresh
publishes a per-profile outcome and cannot turn “skipped everything” into
success. Explicit manual values remain visible overrides rather than being
silently overwritten.

MC百科 article retrieval is allowed only for a same-session opaque document
identity returned by the fixed-origin search adapter. Sanitized page content is
evidence-bearing and uses the same progressive resource protocol.

## States and transitions

`raw_result -> projected_result` when the result fits.

`raw_result -> retained_result -> projected_result` when it spills.

`retained_result -> page_ready` on an authorized describe/read/search request.

`retained_result -> expired` when its transcript owner is removed, the session
is deleted, the connection ends, or runtime shuts down.

`model_wait -> preparing_context -> model_wait` before every provider turn.
`preparing_context -> compacting` when older context can be reduced.
`preparing_context -> failed` when the protected bounded projection cannot fit.

`metadata_refreshing -> updated|cache_hit|unchanged|not_found|ambiguous|source_unavailable`
is decided separately for every profile. Aggregate success requires at least
one non-failure outcome and exposes all per-profile outcomes.

## Invariants

1. A provider or UI projection never contains more than the selected bounded
   result/page bytes.
2. Bounded projection never implies factual completeness.
3. Tool success evidence and stable references survive projection.
4. A retrieval handle grants no authority beyond its original owner.
5. No provider request starts without a budget check on its final serialized
   common request structure.
6. Durable history remains bounded and never embeds the private exact spill.
7. Metadata cache contains no credential, endpoint secret, or raw body.
8. Fixed-origin document retrieval cannot be widened by model text.

## Failure semantics

- Exact result cannot be safely retained: `tool_result_projection_failed`; the
  unbounded result is not used as fallback.
- Handle is invalid/expired/not owned: stable resource failure without leaking
  another owner's existence.
- Continuation remains too large: `context_compaction_failed` before HTTP.
- Metadata identity is ambiguous or absent: preserve the prior cache and return
  a truthful unresolved outcome.
- Online body fetch or sanitization fails: fail only that source and retain
  other knowledge capabilities.

## Supersedes

This decision narrows SKMB-2026-07-18-008 invariant 2: current-request Tool
call/result *structure, invocation identity, evidence, and exact retrievability*
remain protected, while an unbounded full Tool-result body is replaced by a
bounded reversible projection before it becomes model context.

It extends SKMB-2026-07-18-012 by allowing trusted canonical metadata reuse
across inference providers through an exact or unique alias match and by making
refresh outcomes truthful per profile. Explicit manual override precedence is
unchanged.

It extends SKMB-2026-07-19-024: `result_too_large` is no longer a terminal loss
of access; the exact result is retained and progressively queryable.
