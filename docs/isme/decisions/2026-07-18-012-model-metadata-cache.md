# SKMB-2026-07-18-012: Model Metadata Cache and Refresh

- status: accepted
- decided_by: designer
- approval_source: designer requested startup prefetch with local reuse, manual refresh, and fetch-on-cache-miss because model-ID capability metadata is generally stable
- date: 2026-07-18
- commit: pending
- patterns:
  - A_async_wait
  - B_state_persistence
  - D_external_dependency
  - F_fail_semantics
- scope: trusted model metadata cache, startup refresh, and manual refresh

## Context

SKMB-2026-07-18-009 made explicit model limits authoritative and required
trusted metadata discovery to remain nonessential to runtime bootstrap. It did
not decide whether successful discovery should be reused across launches or
whether startup may refresh it opportunistically.

## Decision

TomeWisp stores successful trusted model metadata in a strict, credential-free
local cache keyed by metadata source and upstream model ID. Cache records keep
the provider model ID, provider-published canonical ID when present, context
window, explicitly published output limit when present, source, and capture
time. API keys, authorization headers, endpoint response bodies, pricing, and
provider runtime state are unrepresentable.

Client startup reads the cache asynchronously and may apply valid entries to
profiles whose explicit required values are absent. Startup never waits for
network metadata. For configured trusted-provider profiles with no valid cache
entry, it schedules a background refresh; online success is written atomically
and may make an unresolved profile usable on a later registry reload. Offline,
malformed, cancelled, or failed refresh leaves explicit configuration and any
prior valid cache unchanged.

Settings/diagnostics provide an explicit manual refresh. Cache entries do not
expire merely because wall-clock time passes: model capability metadata is
treated as stable until manual refresh, cache deletion, or an upstream model-ID
change. A refresh may replace only the matching source/model entry after a
fully validated success.

Explicit `contextWindowTokens` and `maxOutputTokens` always outrank cache and
network metadata. A cache hit never silently changes provider, payer, endpoint,
tool authority, or the selected profile.

## Invariants

1. Cache loading and refresh never block a Minecraft-owned thread.
2. Network failure never prevents startup or invalidates a prior valid cache.
3. Only validated successful metadata is written, using atomic replacement.
4. Credentials and raw provider bodies cannot enter cache or diagnostics.
5. Explicit limits always override discovered limits.
6. Cache keys include trusted source and exact upstream model ID; canonical ID
   is identity evidence, not permission for silent profile rerouting.

## Failure Semantics

- Missing cache and offline refresh: retain unresolved/explicit profile state
  and publish a redacted `metadata_unavailable` diagnostic.
- Malformed cache entry/file: reject that cache projection as
  `metadata_cache_invalid`; do not guess or partially apply it.
- Malformed/duplicate upstream match or invalid numeric limit: fail refresh as
  `metadata_invalid`; preserve the prior cache.
- Cancelled refresh: `metadata_cancelled`; do not write a cache entry.

## Supersedes

Supersedes only the “settings/diagnostics invocation only” portion of
SKMB-2026-07-18-009. Its non-blocking bootstrap, explicit-value precedence,
redaction, and no-silent-fallback rules remain authoritative.

## Superseded By

None.
