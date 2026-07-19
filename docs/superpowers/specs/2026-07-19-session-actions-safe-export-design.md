# Session Actions And Safe Export Design

## Goal

Finish the Guide screen's player-owned conversation actions: double-confirmed
session deletion, complete current-session export, and explicit copying of
visible user/assistant messages.

## Architecture

`GuideService` remains the session/history owner. A narrow export collector
captures the selected session and, when history is windowed, reads every
durable page without changing the viewport window. It returns an immutable,
credential-free export snapshot. A client-only writer formats that snapshot as
plain text and atomically writes it beneath the fixed Minecraft game-directory
child `openallay/exports`.

`OpenAllayScreen` owns only presentation intents. Delete opens two native
`ConfirmScreen` instances bound to the originally selected session ID. Export
runs asynchronously and reports a relative filename or friendly failure.
User/assistant headers expose a copy action that delegates to Minecraft's
clipboard handler. None of these actions are Agent Tools.

## Data And Privacy

Exports retain request chronology, user text, assistant segments, Tool status,
and terminal request state. They omit Tool normalized JSON, evidence/source
internals, model/profile details, checkpoints, debug traces, and raw failure
messages. Common credential-shaped substrings in visible text are replaced by
`[REDACTED]` before persistence.

## Failures And Concurrency

Deletion semantics remain GuideService-owned: confirmed close may cancel its
active request and fences late events. Export is a point-in-time read; later
messages are not added to that file. History/read/write failures publish no
final file. Clipboard failure changes no transcript state. UI completion is
marshalled back to the Minecraft client thread.

## Verification

Deterministic tests cover multi-page chronological collection, invocation-time
overlay, cursor non-progress, secret redaction, filename/path confinement,
atomic output, copyable-row selection, confirmation text/target binding, and
the existing session-close cancellation behavior.
