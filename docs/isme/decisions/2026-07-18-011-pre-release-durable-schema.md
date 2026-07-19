# SKMB-2026-07-18-011: Pre-release Durable Schema Policy

- status: accepted
- decided_by: designer
- approval_source: designer stated that OpenAllay has never shipped, all current durable data is test data, and pre-release schema changes should be intentionally breaking instead of accumulating migrations
- date: 2026-07-18
- commit: pending
- patterns:
  - B_state_persistence
  - F_fail_semantics
  - G_irreversible_action
- scope: durable history schema changes before OpenAllay's first formal release

## Context

Phase 4 development introduced successive internal SQLite layouts while no
OpenAllay version had been formally released. Treating those development-only
layouts as installed user formats created migration code and compatibility
columns for data that the designer confirmed is disposable test state.

## Decision

Until the first formal OpenAllay release, the repository maintains one current
durable history schema only. A schema change may replace the development
layout directly. Production code does not migrate, import, or retain
compatibility columns for earlier pre-release layouts.

Opening an earlier or otherwise unsupported schema fails closed as
`history_schema_unsupported` without mutating it. Developers delete the ignored
development database and let OpenAllay create the current schema. Automatic
deletion is not performed because a mismatched file may have been supplied
accidentally and failure should remain observable.

The first formal release must explicitly replace this pre-release policy with
a release schema and a compatibility policy. Only schemas that were actually
shipped may justify migrations.

## Applies To

- SQLite schema metadata and table layout
- durable codecs and record constructors
- model-selection and compaction-checkpoint persistence
- schema mismatch tests and developer documentation

## Invariants

1. There is one writable pre-release durable schema in production code.
2. No legacy pre-release column or constructor exists solely for migration.
3. Unsupported schemas are not mutated automatically.
4. A migration may be added only for a schema that shipped in a formal release
   or after a later accepted designer decision.

## Failure Semantics

- Earlier or future schema version: `history_schema_unsupported`; preserve the
  file and keep in-memory operation subject to the existing persistence
  unavailable behavior.
- Fresh or deleted development database: create the complete current schema
  transactionally.

## Supersedes

Supersedes the pre-release v1-to-v2 migration portion of
SKMB-2026-07-18-008 and the v2-to-v3 migration portion of
SKMB-2026-07-18-009. It does not change partitioning, async ownership,
recovery, context authority, or per-session model-selection semantics.

## Superseded By

SKMB-2026-07-19-019 supersedes only the manual-deletion requirement for
recognized older unshipped OpenAllay schemas. Future, corrupt, or unrecognized
databases still fail closed without mutation.
