# SKMB-2026-07-18-015: Settings Model Administration

- status: accepted
- decided_by: designer
- approval_source: designer authorized a real request through the supplied provider for testing, requested mainstream connection-test behavior, and then stated “从现在开始，整个phase 4 的决策，我都相信你的最佳判断”
- date: 2026-07-18
- commit: f1ba74b
- implementation_commits: ed7fef3, 32ac7c7, 4afd5d1, 1930516, 22b3656, 6498516
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
  - G_irreversible_action
- scope: native model-profile administration, atomic configuration replacement, and redacted live connection testing

## Context

Phase 4F introduced strict named model profiles, per-session model selection,
and asynchronous trusted metadata discovery. It intentionally deferred native
profile CRUD and connection testing. The settings workflow now needs to change
persisted profile definitions without partially changing the live registry and
must prove inference availability rather than treating model listing or
metadata discovery as a successful connection.

The designer permits an explicitly requested, possibly billable real request
for connection testing. Credentials remain environment-only despite that
authorization.

## Decision

The settings editor operates on an immutable candidate profile configuration.
Saving first validates the complete candidate, resolves each profile against a
snapshot of environment presence and trusted metadata, and prepares the
replacement registry state without mutating the current file or runtime. A
profile may be saved while its named environment variable is absent; it is
then retained as explicitly unavailable for repair rather than silently
discarded or replaced.

After preparation succeeds, TomeWisp writes the complete strict `models.json`
document to a temporary sibling and atomically replaces the target. Only after
that move succeeds does it publish the already-prepared registry state in one
replacement. A validation, preparation, temporary-write, or atomic-move
failure leaves both the prior file and prior runtime active. The prepared
runtime publication performs no further parsing, network work, or fallible
file I/O. The first successful save of a legacy `model.json` projection writes
the current `models.json`; the legacy file is no longer authoritative and is
not rewritten.

An active Guide request retains the immutable model runtime it captured at
submission. Successful settings replacement affects later requests only.
Deleting a selected profile never rewrites or deletes conversation history;
the remembered selection becomes visibly unavailable until the player chooses
another profile.

“Test connection” is a separate, explicit settings action. It validates the
current saved or unsaved candidate and requires its named API-key environment
variable to be present. It then uses the exact selected protocol, endpoint,
model ID, and transport path to send one isolated text-generation request with
a fixed harmless instruction to answer `OK`. The request contains no Guide
session, player text, conversation history, tool declaration, game snapshot,
Skill, evidence, or trace. A protocol-valid non-empty assistant response proves
the connection; the response text itself is discarded.

The probe uses the profile's connection and request timeouts, supports explicit
cancellation, and constrains generated output to the smaller of the profile
limit and 64 tokens. It does not retry, follow a provider redirect, enter the
Guide request scheduler, honor a 429 by sending a later request, or fall back to
another profile/provider. At most one probe is active in one settings
controller. A second start fails as busy rather than cancelling or charging for
an unexpected replacement request. Closing the settings screen, disconnect,
or client shutdown cancels the ephemeral probe; this does not alter the rule
that closing the Guide conversation screen leaves an Agent request running.

The UI states before starting that a small real request may incur provider
cost. A successful result exposes only profile identity, protocol, redacted
endpoint authority, completion time, and elapsed latency. Failures expose a
stable redacted category such as missing credential, authentication, missing
model, rate limit, timeout, transport, protocol, cancellation, or busy. API
keys, authorization headers, request/response bodies, assistant output, URL
userinfo/query/fragment, and provider error bodies never enter settings state,
logs, history, traces, screenshots, fixtures, or retained verification.

Model listing and trusted metadata refresh remain separate configuration-layer
operations. Their success never proves that inference works and never replaces
the explicit live probe.

## States and Transitions

- `settings_idle -> settings_saving`: the player confirms a fully formed
  candidate; asynchronous preparation and atomic persistence begin.
- `settings_saving -> settings_idle`: atomic replacement succeeds and the
  prepared runtime snapshot is published.
- `settings_saving -> settings_idle`: validation or persistence fails; publish
  a redacted failure while retaining the previous file and runtime.
- `settings_idle -> connection_testing`: the player explicitly starts one live
  probe after the cost notice is visible.
- `connection_testing -> settings_idle`: one valid non-empty assistant response
  produces a transient success/latency result.
- `connection_testing -> settings_idle`: cancellation or a classified provider,
  protocol, transport, or timeout failure produces a transient redacted result
  and no retry.

## Invariants

1. A failed settings save cannot partially replace either persistent profiles
   or the live registry.
2. Runtime publication after the atomic move performs no fallible parsing,
   network request, or file operation.
3. Active Agent requests retain their captured runtime across profile edits,
   deletion, and reload.
4. Connection testing is explicit, isolated, at most one request, cancellable,
   non-retrying, and absent from Guide history and developer traces.
5. Listing/metadata success is never reported as inference success.
6. Credentials remain environment-only and all probe outputs/errors are
   discarded or reduced to stable redacted diagnostics.
7. Neither settings save nor connection testing silently changes the selected
   model, payer, endpoint, tool authority, or conversation contents.

## Failure Semantics

- Candidate syntax/semantic validation fails: `invalid_model_config`; do not
  write or replace the runtime.
- Temporary write or atomic replacement fails: `settings_write_failed`; retain
  the prior target and runtime and remove the temporary file best-effort.
- Another save or live probe conflicts with the current settings operation:
  `settings_busy` or `connection_test_busy`; start no additional operation.
- Named credential is absent: `model_not_configured`; send no request.
- Provider rejects authentication: `connection_auth_failed`; expose no raw
  body or credential-bearing detail.
- Provider rejects the model ID: `connection_model_unavailable`; preserve the
  candidate for correction.
- Provider returns 429: `connection_rate_limited`; send no retry.
- Timeout/cancel/transport/protocol/empty-output failure:
  `connection_timeout`, `connection_cancelled`, `connection_transport_failed`,
  or `connection_protocol_failed`; persist no result and keep configuration
  unchanged.

## Applies To

- common settings service and immutable settings snapshots/actions
- strict model-profile writer and candidate validation
- `ClientModelRuntimeRegistry` prepared atomic replacement
- OpenAI-compatible and Anthropic connection probes through `ModelClient`
- Fabric and NeoForge client wiring and shutdown cancellation
- native settings/profile editor and redacted diagnostics
- deterministic atomicity, race, cancellation, redaction, and provider-mapping
  tests plus opt-in live-provider smoke

## Supersedes

None. This specializes SKMB-2026-07-18-009 profile administration,
SKMB-2026-07-18-012 metadata separation, and SKMB-2026-07-18-013 outbound HTTP
authority boundaries.

## Superseded By

SKMB-2026-07-19-019 supersedes the environment-only client credential policy
with a masked player input and dedicated local credential store. Probe
isolation, redaction, atomic runtime publication, and active-request capture
remain authoritative.
