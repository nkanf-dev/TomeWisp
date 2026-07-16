# SKMB-2026-07-17-002: Shared Server Model Queue

- status: accepted
- decided_by: designer
- approval_source: user explicitly required “如果是走服务端模型的话…后续有请求可以排队…不要那种明确的直接给他拒绝掉”，with TRAE as the interaction reference
- date: 2026-07-17
- commit: pending
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - F_fail_semantics
- scope: server-hosted multi-player model scheduler

## Context

Multiple players can share one server-owned model credential and endpoint. A
single active-request rejection policy would turn normal contention into user
errors and differs from the queueing interaction the designer expects.

## Decision

Server-hosted requests enter a fair per-player queue whenever all configured
execution slots are occupied. Each player's requests remain FIFO, while the
scheduler rotates players so one player cannot starve others. Server model
concurrency is positive administrator configuration and defaults to one. The
queue has no TomeWisp-defined count limit in Phase 2.

Queue position and start events are returned to the client. A player may cancel
queued and running work. Disconnect removes that player's queued requests and
cancels their running request. Completion, failure, and cancellation always
release the execution slot and schedule the next fair item. Context and session
history are captured when execution starts, not when it is enqueued.

The client-local Agent remains one-active-request with explicit `agent_busy`;
this decision changes only server-hosted shared inference.

## Applies To

- `ServerAgentQueue` state and scheduling
- server-hosted Agent request payload handling
- queue position/start/cancel events
- disconnect and shutdown cleanup
- fairness, ordering, concurrency, and failure tests

## Rationale

Queueing is the expected product behavior for a shared paid endpoint. Fair
player rotation preserves per-player intent while preventing one player from
monopolizing a single-concurrency model.

## Alternatives

- Reject while busy: explicitly rejected by the designer.
- Global FIFO only: simpler, but one player can fill the head of the queue.
- Unlimited parallel execution: can exceed shared provider concurrency and makes
  operator cost/control unpredictable.

## Supersedes

The server-hosted portion of SKMB-2026-07-17-001 invariant I2 and fail semantic
F1. Client-local behavior remains unchanged.

## Superseded By

None.
