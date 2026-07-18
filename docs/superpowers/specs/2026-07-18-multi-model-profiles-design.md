# TomeWisp Multi-Model Profiles Design

## Goal

Allow a player to change model or provider inside an existing TomeWisp
conversation without losing semantic context, while keeping active requests,
credentials, persistence, and failure behavior explicit.

## Product behavior

TomeWisp stores a provider-neutral transcript. A model selection is the runtime
used for the next request, not a property of the conversation itself. Each
session remembers its own current selection. Changing the selected profile in
one session does not affect another session and does not reroute an active
request. The selected profile is captured when a request starts.

Switching may cross both provider and model boundaries. The next request sends
the same common history through the newly selected adapter and computes a fresh
context projection using the new model's budget. Valid summaries may be reused
across the switch because they are derived text, not provider cache state or
factual evidence.

## Configuration model

`config/tomewisp/models.json` is a strict versioned document containing:

- `schemaVersion`;
- `defaultProfileId` for newly created sessions;
- an ordered list of named client profiles.

Each profile has a stable ID, display name, enabled flag, protocol, HTTPS base
URL, upstream model ID, `apiKeyEnv`, explicit or resolved context window,
maximum output tokens, timeouts, and optional metadata provenance. Profile IDs
use the same stable identifier grammar as sessions. API-key values are not a
representable field.

The existing single `model.json` remains readable as a legacy synthetic
`default` profile when `models.json` is absent. Settings save only the new
format and never copy a legacy inline key. A legacy inline-key configuration
can run for compatibility but must be converted to an environment variable
before the settings workflow persists it.

## Runtime ownership

Common introduces `GuideModelSelection`, representing either a client profile
ID or the connected server model. `GuideSessionSnapshot` persists this
selection. `GuideModelMode` remains a compatibility view derived from the
selected session.

`ClientModelRuntimeRegistry` owns an immutable map of profile ID to runtime.
Each runtime contains its configured `ModelClient`, scheduler, budgeted
compactor, and redacted diagnostics. All runtimes share the same
`AgentSessionStore` and tool executor, so history remains continuous when the
selected runtime changes. Registry replacement is atomic; in-flight calls hold
their captured runtime reference.

`GuideService.submit` snapshots the selected session's selection before
dispatch. A client selection is passed to `GuideLocalEndpoint`; a server
selection uses the existing remote endpoint. Retry uses the session's current
selection as a new request, rather than silently replaying the old payer/model.
The request snapshot records the actually captured selection for diagnostics.

## Metadata discovery

Metadata discovery is a settings/diagnostics operation, not a runtime bootstrap
dependency. `ModelMetadataResolver` returns immutable candidates containing the
provider source, upstream ID, context window, output limit when known,
supported parameters, capture time, and diagnostic state.

Resolution priority is:

1. explicit profile values;
2. trusted native metadata for the configured provider;
3. explicitly enabled advisory catalogs;
4. unresolved, which remains a configuration error.

The first native adapter targets OpenRouter's models API because it publishes
model IDs and `context_length`. The interface remains provider-neutral so
Gemini-style `inputTokenLimit`/`outputTokenLimit` or other native sources can be
added without changing GuideService. Generic OpenAI-compatible `/models`
responses are not assumed to include context limits. Advisory catalog data is
never authoritative and never overwrites an explicit value.

Metadata requests use HTTPS, existing timeout conventions, no model API key
unless the provider requires the same configured environment credential, and
redacted structured failures. No raw body or authorization value reaches logs,
history, traces, or GUI text.

## Persistence and migration

Guide history schema v3 stores a selection object per session. Schema v2
partitions migrate transactionally by projecting the partition's old global
`modelMode`: client mode maps to the configured default profile and server mode
maps to the server selection. The migration never modifies messages, timeline
entries, evidence, or checkpoints.

If a recovered selection references a profile that is no longer present, it is
retained and shown as unavailable. Submission fails closed until the player
chooses a valid profile. There is no silent fallback.

## User interface

The selected session's top bar exposes a model selector listing enabled client
profiles and the server model when advertised. Changing it updates only that
session. An active request keeps its captured model; the selector may change
the next-request preference immediately and shows both “running with” and
“next request” when they differ.

Profile settings support create, edit, disable, delete, reload, metadata
refresh, and redacted connection testing. Deleting a selected profile requires
an explicit user action but does not delete or rewrite any conversation.

## Failure and concurrency behavior

- Same-session request concurrency remains one active request.
- Different sessions may concurrently use different profiles/endpoints.
- Endpoint scheduling/rate limits remain isolated by endpoint/credential
  configuration.
- A selection or registry reload race cannot mutate the captured active runtime.
- Missing profile, invalid config, unresolved context limit, failed metadata,
  and unavailable server model are distinct diagnostics.
- Model/provider switching never changes tool authority or evidence semantics.

## Verification

Deterministic tests cover strict profile decoding, legacy import, secret
exclusion, metadata precedence/provenance, malformed and unavailable metadata,
per-session selection isolation, active-request capture, cross-model history
continuity, profile removal/reload races, schema-v2-to-v3 migration, client/
server selection, and Fabric/NeoForge UI parity. The full common suite and both
loader builds remain required. Live metadata and provider calls are opt-in and
cannot be claimed from deterministic fixtures.

## Deferred

Automatic model benchmarking, cost-based routing, silent failover, ensemble
requests, provider-side cache portability, and automatic external-catalog
networking remain outside this work package.
