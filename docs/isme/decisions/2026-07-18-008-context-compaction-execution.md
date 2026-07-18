# SKMB-2026-07-18-008: Context Compaction Execution

- status: reviewable_default
- decided_by: statistical_default
- approval_source: designer explicitly delegated implementation details with "按照你的最佳路径去走就可以了" after accepting SKMB-2026-07-18-005
- date: 2026-07-18
- commits: 1cd1c69, 1d7a665, 56f0a26, 3d80eef, 96b71ba, 00cc905, 3f4e435
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: Phase 4 provider-neutral context estimation, deterministic reduction, summary checkpoints, cancellation, and recovery

## Context

SKMB-2026-07-18-005 fixes the protected context classes, selected-topology
rule, token-budget trigger, derived-memory authority boundary, and terminal
failure behavior. It does not select a provider-neutral token estimator, the
default context-window configuration, the exact summary-checkpoint lifecycle,
or how a process-recovered checkpoint is proven to describe the current
durable source messages. The designer subsequently confirmed that different
provider/model pairs require different configured windows and questioned
whether provider endpoints can supply trustworthy metadata.

The designer authorized autonomous use of the best implementation path. These
execution details are therefore reviewable statistical defaults rather than
new designer-attributed product policy.

## Decision

Each model configuration has a positive `contextWindowTokens`. It is explicit
and may be supplied by `TOMEWISP_CONTEXT_WINDOW_TOKENS`; no provider-independent
window is guessed. A future trusted provider metadata adapter may resolve a
missing value, records its provenance and capture time, and never overrides an
explicit value. The usable input budget reserves the
configured maximum output twice: once for the immediate model turn and once for
one expected tool-result continuation. A configuration whose context window
cannot hold those reserves is invalid.

The common estimator is deterministic and provider-neutral. It counts a
conservative UTF-8 byte upper bound for system text, message structure and
content, tool declarations, and JSON. Provider-reported usage remains
diagnostic evidence and does not retroactively change a request already
assembled. Provider-specific tokenizers may later improve estimates behind the
same interface but cannot weaken the common protected-content rules.

Reduction operates on structural message units. The current request boundary
and every tool-use/result pair at or after it are immutable. Older tool results
are replaced first by a strict reduced projection containing status, failure,
stable references, and evidence metadata. Reasoning is never copied into a
summary prompt or checkpoint.

If deterministic reduction still does not fit, the same scheduled `ModelClient`
and request scheduling key generate a versioned structured summary. Summary
input is itself budgeted and may cover only a structural prefix; unsummarized
recent messages remain verbatim. The checkpoint records the source range,
SHA-256 hash, configured model identifier, prompt/schema versions, creation
time, summary or structured failure, and the estimated projection size.
The recorded model identifier is diagnostic provenance, not session ownership
and not a reuse gate.

A completed checkpoint is reusable only when its source hash exactly matches
the durable source messages in the same player/world/server partition and its
prompt and schema versions are supported. It may be reused after changing
provider or model because it is validated derived text rather than a provider
cache. The projection is always re-estimated against the newly selected
model's budget; if it does not fit, compaction runs again. A
failed or stale checkpoint is retained for diagnosis but never inserted into a
model request. Summary text is prefixed as derived memory and explicitly says
that it is not factual evidence.

Durable schema v2 stores checkpoints in a session-owned table and migrates v1
transactionally without replacing message or timeline rows. Normal recovery
reconstructs only visible user and completed-assistant messages. Client-local
mode may reuse a validated checkpoint; server-model mode sends that same
privacy-safe visible history over strict bridge protocol v4 and recomputes a
summary when needed. It never restores live capabilities, authoritative game
snapshots, reasoning, or full normalized tool payloads.

A guide session owns the provider-neutral transcript and checkpoints, not a
model binding. Changing the selected model affects future requests only. Each
adapter serializes the same common `ModelMessage` history for its protocol;
provider-side prompt/KV caches may be lost on a switch without losing semantic
conversation context.

Compaction is cancellable through the request's existing cancellation signal.
Cancellation produces `agent_cancelled`, stores no successful checkpoint, and
cannot dispatch the primary model request. If summary generation fails, the
deterministically reduced projection proceeds only when it fits; otherwise the
request terminates as `context_compaction_failed` and the previous session
history remains unchanged.

## Applies To

- `ModelConfig` context-window validation and diagnostics
- token estimation, structural units, old tool-result reduction, and projection
- structured summary prompt, decoding, checkpoint hashes, and reuse
- AgentSessionStore checkpoint ownership and durable checkpoint projection
- GuideService compaction state/status and client/server model dispatch
- Anthropic/OpenAI adapters only through their existing `ModelClient` contract

## Invariants

1. Context compaction never mutates or replaces original session history.
2. Current-request tool-use/result pairs are byte-for-byte preserved.
3. Reasoning cannot enter summary input, summary output records, or durable checkpoints.
4. A summary checkpoint never supplies factual evidence.
5. A checkpoint is reusable only after same-partition source-hash and supported
   prompt/schema validation; generating model and endpoint are provenance only.
6. The summary call uses the same selected endpoint, credentials, scheduling key, and cancellation signal as the primary request.
7. No fixed message-count, history-length, or database-size cap is introduced.

## Failure Semantics

- Invalid context-window/output reserve configuration: `invalid_model_config` before runtime creation.
- Malformed summary output: retain a failed checkpoint diagnostic and use deterministic reduction only if it fits.
- Summary transport/provider failure: preserve the structured provider failure as checkpoint diagnostics without raw bodies or secrets, then apply the same fallback.
- Projection still over budget: fail `context_compaction_failed`; do not dispatch the primary request or modify history.
- Cancellation during compaction: fail `agent_cancelled`; suppress late summary completion and primary dispatch.
- Durable checkpoint hash mismatch: treat the checkpoint as stale and rebuild; never guess equivalence.
- Durable checkpoint prompt/schema mismatch: retain it for diagnosis and
  rebuild; never insert it into model context.

## Review Debt

Review the UTF-8 estimator conservatism and double-output reserve against
retained provider usage during final Phase 4 acceptance. A
provider-specific optimization requires deterministic cross-adapter tests and
must not alter protected-content semantics.

## Implementation Evidence

The implementation commits listed above cover budget/structure, old tool-result
reduction, structured summaries, Agent integration, explicit selected-model
windows, schema-v2 checkpoint persistence, protocol-v4 server recovery, and
provider/model-neutral session reuse. Deterministic tests cover malformed and
failed summaries, cancellation before and during compaction, source/version
staleness, schema migration, partition isolation, privacy exclusions,
same-session races, cross-model history reuse, Unicode request chunking, and
both loader bridge paths.

The 2026-07-18 clean gate completed 217 common tests with zero failures/errors
and one opt-in skip, then built Fabric and NeoForge successfully. Production
artifact SHA-256 values are
`a7b0a7c5227d19ccb426dc40e298af742bbd0ae97e610417be81572d491b472e`
and `fc50311e7b0ffc62da07d102618a3a7cb8af7c00e7d3c64bd41441d34760557d`
respectively. Syntax and credential scans passed. No graphical client or live
provider request was run for Phase 4D; those remain part of final consolidated
Phase 4 acceptance.

## Supersedes

None.

## Superseded By

None.
