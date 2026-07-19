# Phase 4 Model Boundary Corrections Design

Date: 2026-07-19
Status: approved
Scope: final Phase 4 correction for model ownership, model discovery, Tool name
recovery, and server-Agent access to player client state

## Outcome

Players configure only their own client profiles. Server models are clearly
marked read-only connection capabilities and never masquerade as client
configuration. A model ID can be typed or selected from an authenticated
provider catalog. Both client- and server-hosted Agents can use the read-only
facts available on the other side of the game connection, while every call
remains player-scoped, evidence-bearing, cancellable, and non-mutating.

## Selected architecture

Three Tool-location approaches were considered:

1. A request-scoped symmetric Tool bridge is selected. It preserves live
   client ownership, captures only when the model asks, keeps results typed,
   and reuses the existing correlated/chunked bridge semantics.
2. Serializing the whole client snapshot into every server Agent request was
   rejected because it would eagerly copy unrelated settings and grow stale
   before later Tool turns.
3. Executing only server-local tools was rejected because the server does not
   own client options, active packs, shader configuration, or client F3 state.

For provider model IDs, a generic configuration-layer `/models` adapter is
selected over provider-specific UI code or an inference probe. It uses the
shared HTTP transport and stable redacted failures. The catalog is a candidate
list, never a validation restriction or proof of inference.

## Model ownership and selection

`ClientSettingsService` and `OpenAllaySettingsScreen` continue to manage only
`models.json` and the local credential store. The page is titled “Client
models”. The Guide selector separately labels client profiles and the
server-synchronized canonical model. No client action edits the latter.

Client-profile selections remain durable. A server selection is discarded on
connection recovery because its endpoint, payer, prompt, permissions, and
model identity are connection-scoped. The local default becomes the next
selection, without moving any already-active request.

## Provider model catalog

The model editor keeps a free-form Model ID field plus Fetch and Choose
controls. Fetch constructs `baseUri.resolve("models")`, uses an unsaved key when
typed or resolves the saved reference otherwise, and performs one cancellable
GET. The decoded response is an ordered, duplicate-free list of nonblank model
IDs. A searchable virtualized picker writes one selection back into the edit
field; the player may edit it afterward.

Changing profile, protocol, base URL, or typed key invalidates the current
candidate generation. A late result for an older generation is ignored. The
password box stays empty after save, but its hint and status explicitly say
whether an actual saved credential exists and whether typed input will replace
it. Model listing never stores or displays secrets or raw provider content.

## Symmetric player Tool bridge

The server Agent request carries a frozen list of enabled client read-only Tool
IDs. The server intersects it with trusted descriptors. A placement-aware
executor exposes each logical Tool exactly once and routes an invocation to
server-local or player-client execution. Reverse packets have request and
invocation correlation, strict JSON codecs, chunk hashes, and cancellation.

The client accepts a reverse call only while the matching request is active and
the Tool belongs to its frozen capability snapshot. It captures required
Minecraft context on the client thread, immediately detaches immutable values,
then executes/normalizes off the live-object boundary. Disconnect clears both
capabilities and correlations. Loader adapters transport common payloads with
Fabric/NeoForge parity and do not decide placement.

Tool errors are complete normalized Tool results. They are appended to the
model conversation and visible chronology, allowing the model to correct once
or explain a limitation. Executor exceptions are reserved for enclosing
cancellation or invariant violations and never arise merely from an unknown
model-returned Tool name.

## Prompt and presentation

The shared prompt refers to Tool purposes, not canonical identifiers that
conflict with provider-safe schema names. It explicitly says to use only names
from the current Tool schema, make one corrected call, and stop after unchanged
evidence. Dispatch accepts a registered canonical alias defensively and emits
canonical IDs into events/history.

Known cards display specific actions such as querying game state or recipes.
The generic sentence “using a read-only Tool” is removed. Unknown calls retain
their card and stable failure without an invented action description.

## Verification

Deterministic tests cover model origin/recovery, catalog authentication and
redaction, actual credential presence, alias binding, unknown Tool recovery,
reverse bridge success/failure/cancel/disconnect/late chunks, actor isolation,
trusted-schema intersection, and Fabric/NeoForge packet parity. The full common
suite and both loader builds are required.

Opt-in live acceptance loads the ignored local profile and credential store
without exporting or printing the key. With `mimo-v2.5-pro`, it verifies a
greeting uses no Tool, an installed-mod question calls the real game-state Tool
and completes, and a canonical Tool alias cannot terminate the Agent. A real
server-model client-state run verifies video/F3/pack queries route to the client.
Retained reports contain only redacted model origin, canonical Tool IDs,
status, authority, duration, and counts.
