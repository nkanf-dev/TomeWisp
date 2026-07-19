# SKMB-2026-07-19-021: Model Ownership and Player Tool Bridge

- status: accepted
- decided_by: designer
- approval_source: the designer required that client settings configure only client-owned models, server models remain server-configured and appear on clients only as synchronized read-only choices, model IDs can be fetched with the current or saved API key, and a server-hosted Agent can invoke the requesting player's client read-only tools instead of terminating on an unavailable local execution path
- date: 2026-07-19
- commit: pending
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: model-origin presentation, model catalog listing, tool-name compatibility, and request-scoped client Tool execution for server-hosted Agents

## Context

The final Phase 4 walkthrough exposed three related boundary failures. A
durable server-model preference could reappear on a later connection even
though the advertised server model and payer are connection-scoped. The native
model editor did not provide a generic authenticated model-list operation or a
clear in-field indication that a local credential already existed. Finally, a
model returning the registered canonical Tool ID instead of its schema-safe
alias caused an executor exception. A server-hosted Agent also had no reverse
bridge to the requesting player's client-observable settings, packs, and F3
state.

## Decision

Client settings own only local client model profiles and the client's local
credential store. A server model is configured only by `server-model.json` and
is projected to a connected client as a read-only, server-provided Guide model
choice. New sessions and recovered sessions at a new connection begin with the
configured default client profile. A server-model preference is connection
scoped and must be selected again after reconnect; it is never silently
restored against a different server capability.

The Models page performs a configuration-layer, cancellable `GET` against the
profile base URL's `models` resource. OpenAI-compatible profiles use Bearer
authentication. Anthropic profiles use the provider's API-key/version headers
and also send the same credential as Bearer authentication because mainstream
Anthropic-compatible gateways often expose an OpenAI-style `/models` route.
Both forms remain scoped to the same validated provider origin. The
unsaved password-field value is used only for that request when present;
otherwise the saved `credentialRef` is resolved. The response contributes only
validated model IDs to an ephemeral searchable picker. The model field remains
free-form. Credentials, headers, response bodies, and endpoint details never
enter settings snapshots, notices, history, traces, or logs. A saved-key hint
is based on actual credential-store presence rather than the shape of a
reference.

For a server-hosted Agent request, the client sends only the IDs of currently
enabled, registered read-only client Tools. The server intersects those IDs
with its own trusted common Tool registry and derives descriptions and schemas
locally. It freezes the resulting player-scoped capability set for the
request. A selected client Tool invocation is correlated by authenticated
actor, request ID, and invocation ID, sent to that same client, checked again
against the frozen client capability snapshot, captured on the client's owning
thread, executed and encoded on a worker, and returned to the same server
request. No capability is globalized or reusable by another player.

One logical Tool ID is exposed once. Placement policy is code-owned. The
player-observable `inspect_game_state` Tool is client-first for client options,
mods, packs, shaders, HUD/F3, and player-visible state, while explicitly
server-authoritative query sections remain server-owned when available.
Placement is selected before execution and does not silently change authority
after a failure. This applies in both directions: a client-hosted model sees one
merged definition, and code routes `WORLD_QUERY` to the server while retaining
the other game-state sections locally.

Tool dispatch accepts both the schema-safe model name and the canonical ID only
when either maps to a Tool registered in the frozen request catalog. Events,
history, trace, and presentation always use the canonical ID. An unknown name,
malformed arguments, unavailable placement, bridge rejection, remote Tool
failure, or lost result is normalized into a complete error Tool result and
returned to the model so it can recover or explain the limitation. Explicit
request cancellation, disconnect, and shutdown cancel outstanding client Tool
calls and suppress late results.

A remote Tool result uses the selected model profile's existing five-minute
bridge/request timeout as its deadline in either direction. Expiry sends a
best-effort correlated cancel and returns `client_tool_timeout` or
`server_tool_timeout` as a Tool result; it does not leave the Agent in an
unbounded Tool wait or terminate the enclosing request.
Reverse Tool results and server Agent events are transported as bounded chunks
below Minecraft's encoded String limit and reassembled before strict decoding;
the bridge never truncates evidence or normalized Tool output to fit a packet.
Chunk metadata is stored sparsely so an untrusted advertised chunk count cannot
cause proportional server allocation. Every incomplete request, result, or
event assembly expires after the same five-minute bridge deadline and is also
cleared immediately when its enclosing request terminates. The client thread
captures live state only; Tool execution, normalization, JSON encoding, and
Base64 chunking run on a worker. The loader-owned response sink then marshals
the actual packet send back to the client thread.

The generic player-facing phrase “using a read-only Tool” is removed. Known
Tools show their concrete localized action; unknown Tools retain their normal
card title/status without an invented generic action sentence.

## States and Transitions

- `settings_idle -> model_catalog_loading`: validate the draft, select the
  transient or stored credential, and start one cancellable non-inference
  request.
- `model_catalog_loading -> settings_idle`: publish only validated model IDs or
  one stable redacted failure; stale completion after draft change is ignored.
- `server_model_wait -> client_tool_wait`: freeze the authenticated player's
  client Tool placement and send one correlated call.
- `client_tool_wait -> server_model_wait`: return a normalized success or error
  Tool result and continue the same Agent chronology.
- `client_tool_wait -> cancelled`: request cancel, disconnect, or shutdown
  cancels the correlation and suppresses every late result.
- `connection lost -> session recovered`: retain provider-neutral history but
  replace a server-model pointer with the configured local default.

## Invariants

1. Client settings cannot create, edit, delete, test, or persist a server model.
2. A server model appears on the client only while the current connection
   advertises it and is explicitly labeled server-provided/read-only.
3. Client Tool schemas used by a server Agent come from trusted server code;
   the client can only reduce the registered read-only ID set.
4. Every reverse Tool call is bound to one authenticated actor and one active
   request, and cannot survive cancellation, disconnect, or capability-snapshot
   replacement.
5. Tool execution failures are model-visible structured Tool results unless
   the enclosing request itself was explicitly cancelled or disconnected.
6. Canonical Tool aliases never widen authority: only an already-registered
   Tool in the frozen catalog can be selected.
7. Listing models is configuration-layer network I/O, not an Agent Tool and not
   evidence that inference works.
8. A raw credential is never persisted outside the dedicated credential store
   or represented in UI/service/protocol/trace state.
9. If client settings/capability initialization fails, the reverse bridge
   advertises no client Tools; it never fails open to the full registry.
10. A cancellation that wins before dispatch sends neither a Tool call nor a
    meaningless cancel; after dispatch, call and cancel preserve transport
    order and late results remain suppressed.
11. Incomplete bridge assemblies are sparse, deadline-bounded, and scoped to an
    active request; inactive or terminated requests cannot accumulate chunks.

## Failure Semantics

- Missing credential: `model_catalog_credential_missing`; send no request.
- Authentication/rate/transport/timeout/malformed catalog: return the matching
  `model_catalog_*` failure, retain the typed model ID and prior candidates.
- Stale catalog completion: ignore without changing the newer draft.
- Client Tool absent or disabled in the frozen request set:
  `client_tool_unavailable` Tool result; continue the Agent.
- Client Tool bridge rejects, times out, disconnects, or returns malformed
  chunks: a redacted `client_tool_*` Tool result, unless the whole request was
  explicitly cancelled.
- Server Tool bridge rejects, times out, or returns malformed chunks:
  `server_tool_bridge_unavailable`, `server_tool_timeout`, or
  `server_tool_result_invalid` Tool result; continue the Agent.
- Incomplete request/result/event chunks: expire after the five-minute bridge
  deadline and release their assembly without publishing a partial value.
- Unknown model Tool name: `tool_unavailable` Tool result; never throw an
  executor exception that terminates the request.

## Applies To

- Guide model selection/recovery and native model-origin labels
- client settings service, credential presence, generic model catalog client,
  model picker, and localization
- common Tool-name binding and error normalization
- common bridge payloads/correlation and Fabric/NeoForge client/server adapters
- server Agent per-request Tool placement, cancellation, disconnect, and tests
- opt-in redacted live-provider and graphical acceptance

## Supersedes

- Refines SKMB-2026-07-18-009: client-profile selections remain durable, while
  a server-model selection is connection-scoped and does not auto-resume.
- Refines SKMB-2026-07-17-001/004 by making read-only Tool location independent
  in both directions and making remote Tool failure recoverable by the model.
- Refines SKMB-2026-07-18-015/019 with authenticated model listing and actual
  saved-credential presence while preserving probe isolation and redaction.

## Superseded By

None.
