# SKMB-2026-07-18-014: History Administration

- status: accepted
- decided_by: designer
- approval_source: designer replied “确认，这一块我相信你的判断” to the proposed deletion scope, concurrency, and reset policy
- date: 2026-07-18
- commit: adaffaf
- implementation_commits: 28ebb02, ba2ed91, 1b0dd4b, 154a74b, a3ae197
- patterns:
  - B_state_persistence
  - C_concurrent_operations
  - F_fail_semantics
  - G_irreversible_action
- scope: player history deletion and pre-release database reset

## Decision

Normal settings may delete the current history partition or every partition
owned by the current player identity. They never delete another local player
identity's data. The current-player operation is actor-scoped even when the
database also contains partitions for other actors.

Deletion is an explicit asynchronous transaction on the ordered history
repository. It is rejected while the affected actor has any active request or
while a prior history write is pending. OpenAllay does not cancel requests to
make deletion succeed and does not allow a late completion/save to recreate
history that the player just deleted. On success, matching in-memory sessions
are reset to a clean current connection state before new writes are accepted.

Whole-database reset is a separate Debug Mode action with a second explicit
confirmation. It is unavailable in normal settings and is rejected while any
request or history write is active. Unsupported pre-release schema is never
deleted automatically: the developer/player must explicitly invoke the reset
or remove the ignored development database.

The native settings adapter resolves the current `GuideService` at action time
and holds no actor ID, raw scope identifier, database path, or SQL operation.
Confirmation tokens are service-issued but screen-held, action/generation
bound, one-use, and invalidated by navigation, screen detach, or snapshot
replacement. Both loader entrypoints late-bind the same connection-scoped
`GuideServiceManager` only after construction, avoiding a second history owner.

All deletion operations are scoped to the local history database. They do not
delete model-provider data, server-side data, configuration, metadata cache,
developer traces, or another player's state.

## Invariants

1. Normal history management cannot delete another actor's partitions.
2. No deletion races an active request or pending ordered history write.
3. A successful deletion cannot be resurrected by a previously scheduled save.
4. Whole-database reset requires Debug Mode and a distinct second confirmation.
5. Unsupported schemas fail closed and are never automatically reset.
6. Partial SQL deletion is unobservable: matching rows are removed in one
   transaction or the prior database remains intact.

## Failure Semantics

- Affected actor has an active request: `history_delete_busy`; delete nothing.
- The repository has an unflushed write: `history_delete_busy`; wait for the
  player to retry explicitly after it becomes idle.
- Confirmation is absent or stale: `history_delete_confirmation_required`;
  delete nothing.
- SQL/open/schema failure: `history_delete_failed`; roll back and preserve the
  prior in-memory and durable history projection.
- Unsupported pre-release schema during ordinary load: retain
  `history_schema_unsupported`; do not route it into reset automatically.

## Applies To

- `GuideHistoryStore` and its ordered asynchronous repository
- SQLite partition/actor/database deletion transactions
- `GuideService` active-request and in-memory reset coordination
- normal settings and Debug Mode history actions
- deterministic actor-isolation, busy, rollback, and resurrection-race tests

## Supersedes

None. This specializes the player-initiated history-management requirement in
SKMB-2026-07-18-005 and preserves SKMB-2026-07-18-011's pre-release schema
policy.

## Superseded By

None.
