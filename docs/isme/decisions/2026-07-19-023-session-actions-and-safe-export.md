# SKMB-2026-07-19-023: Session Actions And Safe Export

- status: accepted
- decided_by: designer
- approval_source: the designer delegated all remaining Phase 4 decisions to the agent's best judgment and requested direct completion; the accepted Phase 4 design already requires explicit per-session deletion, while the final handoff requires double confirmation, current-session export, and visible chat copying
- date: 2026-07-19
- commit: pending
- patterns:
  - B_state_persistence
  - C_concurrent_operations
  - E_security_boundary
  - F_fail_semantics
  - G_irreversible_action
- scope: native session deletion confirmation, conversation export, and visible message copying

## Decision

The Guide screen treats session deletion as a destructive player action. The
first native confirmation identifies the captured session. A second native
confirmation states that durable conversation data is removed and any active
request in that session is stopped. Only the same captured session ID reaches
`GuideService.closeSession`; dismissing either screen performs no action.

Current-session export captures one immutable point-in-time view. When durable
history is windowed, it reads every earlier page through `GuideHistoryAccess`
without mutating the visible history window, merges the invocation-time live
requests by their service-owned sequence, and preserves request and timeline
order. A failed page read fails the export; it does not silently emit a partial
conversation.

The filesystem writer accepts the Minecraft game directory only and derives
the fixed `tomewisp/exports` child itself. It rejects symlink escape, writes
UTF-8 plain text through a temporary file, and atomically publishes a safe,
deterministic name. The document contains user and assistant text, request
state, and closed Tool chronology only. It excludes normalized Tool payloads,
sources/evidence internals, checkpoints, model/provider configuration,
credentials, and raw failures. A defense-in-depth text redactor masks common
credential shapes that a player may have pasted into chat.

Copy is a local, explicit action attached only to visible user and assistant
rows. It writes exactly that visible message text to the system clipboard and
shows a friendly success or failure notice. It is never available to the Agent
as a Tool.

## Invariants

1. No session is deleted without two affirmative native confirmations bound to
   the same captured session ID.
2. Export cannot choose an arbitrary path or leave a partially published file.
3. Export preserves the immutable request/timeline order captured at invocation.
4. Export never contains credential stores, model settings, normalized Tool
   JSON, checkpoints, raw diagnostics, or known credential-shaped text.
5. Copy is player-triggered and limited to already-visible chat text.

## Failure Semantics

- Either confirmation is dismissed: return to the Guide screen and do nothing.
- The captured session no longer exists: retain state and show a friendly
  no-change notice.
- A durable page cannot be read or its cursor does not progress: return
  `history_export_failed` and create no final export file.
- The managed export directory is unavailable, escapes through a symlink, or
  an atomic write fails: return `history_export_failed`, remove the temporary
  file where possible, and retain conversation state.
- Clipboard access throws: preserve the message and show a localized copy
  failure notice.

## Applies To

- `GuideService` session export capture and history paging
- native Guide session controls and row actions
- managed local export formatter/writer
- deterministic paging, redaction, confinement, confirmation, and copy tests

## Supersedes

None. This specializes the explicit session deletion and export requirements
in SKMB-2026-07-18-005, SKMB-2026-07-18-014, and SKMB-2026-07-18-018.

## Superseded By

None.
