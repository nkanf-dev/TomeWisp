# SKMB-2026-07-17-003: Multi-Session Endpoint Rate Scheduling

- status: accepted
- decided_by: designer
- approval_source: user explicitly clarified “本地也可以一个玩家多个请求…单个会话当然是只能同时有单请求，我说的是那种多会话的情况” and stated that dispatch should depend on model rate limits and 429
- date: 2026-07-17
- commit: pending
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - F_fail_semantics
- scope: local and server model request scheduling

## Context

A player may maintain multiple independent conversations. Serializing all work
by player unnecessarily prevents independent sessions from using provider
capacity. Conversely, parallel requests inside one conversation can race its
history. Provider rate limits are endpoint behavior and are reported with HTTP
429 rather than a universal concurrency value TomeWisp can infer.

## Decision

The isolation key is `(actor identity, sessionId)`. One request may be active in
each session, while different sessions owned by the same player may execute in
parallel in both client-local and server-hosted modes. A same-session concurrent
request returns `agent_busy`.

TomeWisp applies no default global concurrency cap. A scheduler is scoped to one
effective endpoint/credential configuration. HTTP 429 is not a terminal Agent
failure: the request is requeued, the endpoint gate closes for `Retry-After`, or
for cancellable exponential backoff when the header is absent, and sessions are
selected fairly when dispatch resumes. A queue has no TomeWisp-defined count
limit. Cancellation removes queued/running work for that session; disconnect
removes all sessions owned by the actor.

## Applies To

- `AgentSessionKey` and `AgentSessionStore`
- default/new/switch/list/close session commands
- `ModelRequestScheduler` endpoint key and fair queue
- HTTP 429 decoding and Retry-After parsing
- local and server-hosted model execution
- traces, queue events, cancellation, and concurrency tests

## Rationale

Conversation history, not player identity, is the true serialization boundary.
Provider 429 responses are the authoritative rate signal and avoid inventing a
fixed concurrency policy that may underuse or overload different endpoints.

## Alternatives

- One active request per player: rejected by the designer.
- Fixed global concurrency default: rejected because it is not the provider's
  actual rate limit.
- Treat 429 as failure: rejected because shared/local requests should wait and
  resume rather than forcing players to resubmit.

## Supersedes

- SKMB-2026-07-17-001 invariant I2 and fail semantic F1.
- SKMB-2026-07-17-002 scheduling details about default concurrency and
  per-player queues.

## Superseded By

None.
