# SKMB-2026-07-18-006: Durable History Execution

- status: reviewable_default
- decided_by: statistical_default
- approval_source: designer explicitly delegated implementation details with "按照你的最佳路径去走就可以了" after accepting SKMB-2026-07-18-005
- date: 2026-07-18
- commit: cb77181
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - E_security_boundary
  - F_fail_semantics
  - G_irreversible_action
- scope: Phase 4 durable history loading, transaction ordering, partition identity, recovery, and disconnect behavior
- implemented_by: a0eaeff, 19ab90f, c6ca6bc
- deterministic_verification: Phase 4B clean gate recorded in docs/superpowers/plans/2026-07-18-phase-4b-durable-history.md

## Context

SKMB-2026-07-18-005 determines the durable data classes, player/world/server
partitioning, manual recovery, fail semantics, and SQLite proof requirement. It
does not determine how disk I/O is kept off the client thread, how a partition
identifier avoids retaining a raw server address or local save path, or what
happens if the player submits while history is still loading.

The designer authorized autonomous use of the best implementation path. These
execution details are therefore recorded as reviewable statistical defaults,
not attributed as separately selected designer policies.

## Decision

One SQLite database is accessed through a dedicated single-writer executor.
The client and render threads never open a connection, wait for a transaction,
or decode durable history. Writes retain event order and use one transaction to
replace the current durable projection for the affected partition. No fixed
queue or history-size limit is introduced.

A partition ID is the SHA-256 digest of the player UUID, connection kind, and a
normalized connection discriminator. Single-player uses the normalized world
directory and multiplayer uses the normalized server address. Only the digest
and connection kind are durable; raw paths and server addresses are not stored.

While initial history is loading, the service publishes `LOADING` persistence
state and rejects a new request as `history_loading`. This avoids an invisible
request queue, ambiguous cancellation, and a model call built from incomplete
history. A load failure changes persistence to `UNAVAILABLE` while keeping the
in-memory Agent usable.

Every accepted visible GuideService transition schedules a durable snapshot in
the same order. A successful completion advances the persistence generation;
a failed transaction marks that generation unsaved and changes persistence to
`UNAVAILABLE`. Older write completions cannot overwrite newer health state.

On recovery, a request without a durable terminal state becomes `INTERRUPTED`.
Its visible timeline remains unchanged and retry creates a new request ID. A
graceful disconnect cancels active work and persists the resulting terminal
state, then clears only connection-scoped memory. The empty disconnected state
is never written over the durable partition.

## Applies To

- `GuideHistoryScope` and Minecraft connection scope resolution
- SQLite schema version 1 and strict durable codecs
- asynchronous history repository and transaction ordering
- GuideService loading, persistence health, save, recovery, retry, and disconnect
- Fabric and NeoForge client bootstrap and packaged-driver verification

## Rationale

The defaults preserve the accepted authority and recovery model while keeping
all filesystem and database work away from Minecraft-owned threads. Hashed
scope discriminators provide stable isolation without making server addresses
or local paths part of the browsable database model.

## Alternatives

- Synchronous first-use loading was rejected because it can block the render or
  client game thread on disk I/O and corruption handling.
- Queueing questions behind hydration was rejected because it creates a second
  pending-request state with cancellation and cost semantics not approved by
  the designer.
- Ad-hoc JSON files were rejected by SKMB-2026-07-18-005.
- Coalescing or dropping pending snapshots was deferred until measurements and
  an explicit decision define the durability trade-off.

## Review Debt

Review these defaults no later than the final Phase 4 real-client acceptance.
If the designer selects different loading or persistence behavior, record a
superseding decision and a versioned migration where durable identity changes.
The final review must include retained graphical restart evidence and must not
treat packaged Windows/Linux native entries as execution on those hosts.

## Supersedes

None.

## Superseded By

None.
