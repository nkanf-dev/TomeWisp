# SKMB-2026-07-18-009: Session Model Selection

- status: accepted
- decided_by: designer
- approval_source: designer approved the recommendation “每个会话记住当前选择，但可以随时切换；正在执行的请求不变” with “按照你推荐的来”
- date: 2026-07-18
- commit: pending
- patterns:
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: named model profiles, per-session selection, runtime switching, and model metadata discovery

## Context

Phase 4D made durable conversation history and derived checkpoints
provider/model-neutral, but the runtime still exposes one client model plus one
server-model mode. The designer explicitly requires Codex/OpenCode-style model
switching inside an existing conversation without losing context, including
switching providers that expose the same underlying model.

## Decision

TomeWisp manages multiple named client model profiles. A profile contains its
protocol, endpoint, model ID, API-key environment-variable name, context/output
limits, timeouts, and redacted metadata provenance. New sessions start with the
configured default client profile. Each session remembers its current
selection, which may be a client profile or the connected server model.

The remembered selection is a convenience pointer, not a model binding.
Changing it affects only future requests in that session. An active request
captures its selection and immutable runtime at submission and continues on
that model until terminal completion, cancellation, or failure. Other sessions
are unchanged and may use different models concurrently.

Conversation messages and valid derived checkpoints remain provider/model-
neutral. Every request rebuilds the common history through its selected adapter
and re-estimates it against that profile's context budget. Checkpoint
`modelIdentifier` remains generation provenance only. Provider-side KV/prompt
caches may be lost after switching without losing semantic context.

An explicit `contextWindowTokens` is authoritative. Trusted provider-native
metadata may fill a missing value in the settings workflow and records source,
capture time, and upstream model ID. External catalogs may suggest values only
when explicitly enabled and never silently override explicit configuration.
Under SKMB-2026-07-18-012, startup may load cached metadata and refresh cache
misses asynchronously, but runtime bootstrap never depends on metadata network
availability.

If a remembered profile is missing, disabled, malformed, or cannot resolve a
required context window, submission fails explicitly as `model_not_configured`
or `invalid_model_config`. TomeWisp never silently selects another local
profile or switches to the server model. Removing or reloading a profile does
not cancel an active request that already captured its runtime.

Credentials remain environment-only in the new multi-profile format. Settings
may show whether an environment variable is present but cannot reveal, copy, or
persist its value. Metadata and diagnostics contain no authorization headers,
API keys, or raw error bodies.

## Applies To

- versioned multi-profile model configuration and legacy single-profile import
- per-session durable model selection in the single current pre-release schema
- GuideService selection, submission, retry, disconnect, and recovery
- client model runtime registry and active-request capture
- server-model selection compatibility
- provider metadata adapters, provenance, and redacted diagnostics
- settings/UI model selectors and deterministic concurrency/failure tests

## Invariants

1. A guide session is never owned by or permanently bound to one model.
2. A selection change affects future requests in exactly one session.
3. An active request retains the model runtime captured when it started.
4. Model switching never clears, rewrites, or repartitions conversation history.
5. Profile or metadata failure never silently changes payer, endpoint, model, or authority.
6. Explicit model limits override discovered or catalog limits.
7. Credentials never enter persisted profiles, packets, prompts, history, metadata, or diagnostics.

## Failure Semantics

- Missing/disabled selected client profile: `model_not_configured`; keep the
  session and its selection visible for repair.
- Invalid profile or unresolved required context window: `invalid_model_config`;
  do not dispatch a model request.
- Metadata endpoint unavailable/malformed: retain explicit configuration and
  expose a redacted diagnostic; runtime remains usable when required fields are
  already explicit.
- Selection changes while a request is active: store the new preference for the
  next request; do not reroute or restart the active request.
- Profile reload/removal during an active request: the captured runtime finishes;
  later requests fail if their remembered selection is no longer available.

## Supersedes

Supersedes global `GuideModelMode` as the long-term selection owner. The enum
remains a compatibility projection while per-session `GuideModelSelection`
becomes authoritative.

## Superseded By

None.
