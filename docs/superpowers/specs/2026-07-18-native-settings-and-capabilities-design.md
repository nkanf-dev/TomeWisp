# Native Settings and Capabilities Design

Date: 2026-07-18
Status: approved
Scope: Phase 4 native settings, capability/source management, model profile
administration, history controls, and redacted diagnostics

## 1. Outcome and boundaries

OpenAllay gains one Minecraft-native settings experience backed by a common
service rather than loader-specific screens or direct file edits. It manages
named model profiles, general presentation state, registered knowledge sources,
local Tools, bundled Skills, capability-owned settings such as recipe sources,
durable-history administration, metadata refresh, reload, and diagnostics.

The top-level player section is “知识与能力” (`Knowledge & Capabilities`), not
“配方” (`Recipes`). Recipes are one Tool entry. Opening that entry reveals its
own visibility, source, and viewer configuration, including installed
JEI/REI/EMI-style adapters. Other knowledge sources, Tools, and Skills use the
same catalog and child-page boundary instead of acquiring top-level mod pages.

This package does not add online knowledge tools, new network permissions,
arbitrary configuration panels, model-authored settings actions, or server
administration. It does not predefine animation/accessibility switches before
the corresponding rich renderer exists. Long-history paging, semantic dynamic
components, and their presentation-specific accessibility controls remain
separate Phase 4 packages that will plug into the settings boundary defined
here.

All current formats remain pre-release development formats. They are replaced
cleanly when needed; production code does not accumulate migrations for data
that has never shipped.

## 2. Selected architecture

Three approaches were considered:

1. A common settings service with independent typed domain stores and runtime
   adapters. This is selected because it preserves current ownership, gives the
   UI one immutable view, supports deterministic async tests, and keeps Fabric
   and NeoForge thin.
2. Direct Screen-to-file/runtime calls. This is rejected because threading,
   rollback, redaction, confirmations, and loader parity would become UI
   responsibilities.
3. One monolithic `settings.json`. This is rejected because model profiles,
   capability policy, recipe-tool preferences, display state, metadata cache,
   and history have different schemas, authorities, and failure lifetimes.

`ClientSettingsService` in common code is the sole settings operation owner. It
publishes immutable `ClientSettingsSnapshot` values, accepts typed actions, and
coordinates domain adapters. The service has a dedicated asynchronous worker
for file preparation and atomic writes, reuses the existing metadata and
history asynchronous owners, and publishes completions through
`ClientEventDispatcher` on the Minecraft client thread.

The native `OpenAllaySettingsScreen` is a projection and draft editor. It owns
focus, navigation, unsaved field values, and confirmation overlays only. It
never opens files, constructs provider clients, touches SQLite, refreshes
metadata directly, or mutates Guide/runtime state. Closing it detaches its
listener and discards drafts/confirmation tokens. Confirmed durable operations
remain service-owned; only its ephemeral live connection probe is cancelled.

Fabric and NeoForge construct the same common service with loader config paths,
the same runtime adapters, and the same screen. Loader code owns only paths,
lifecycle callbacks, key/command registration, installed integration hooks,
and client-thread dispatch.

## 3. Settings service contract

The snapshot contains only immutable, defensively copied values:

- current display/debug state and any redacted load failure;
- ordered model profile summaries, current default, availability, named
  credential presence, metadata provenance/status, and transient probe result;
- registered knowledge-source, Tool, and Skill cards plus enabled/available
  state and registered child-page identity;
- capability policy and capability-owned summaries such as recipe visibility,
  source states, and preferred viewer;
- current actor's history health, database schema status, current partition
  identity as a friendly label, write/active-request state, and allowed actions;
- compaction/metadata/source diagnostic summaries already authorized for normal
  or Debug Mode projection;
- current settings operation kind and terminal redacted notice.

Typed service actions cover:

- save/delete/default/change/reload model profiles;
- start/cancel a profile connection probe and refresh trusted metadata;
- save/reload local Tool and Skill policy;
- save/reload one registered capability-owned configuration;
- toggle Debug Mode and reload display state;
- delete current history partition, delete all current-actor partitions, and
  perform the separately confirmed Debug Mode database reset;
- reload all settings domains without treating one failure as another domain's
  failure.

One foreground settings action runs at a time, including a connection probe.
There is no hidden write queue. Conflicting mutations fail as `settings_busy`;
a conflicting probe fails as `connection_test_busy`. A probe is never
coalesced with metadata/list-model work. Startup metadata refresh may continue
as a background cache operation, but runtime application is generation-checked
through the service. Listeners may detach/re-attach without owning service
state.

## 4. Persistent domain formats and atomicity

Each JSON domain has a strict codec plus a narrow store. The reusable file
mechanism may encode UTF-8 to a temporary sibling and perform
`ATOMIC_MOVE + REPLACE_EXISTING`, but it does not understand domain JSON,
defaults, permissions, or runtime publication.

### 4.1 Model profiles

`models.json` keeps the accepted Phase 4F strict profile structure. The native
writer serializes exactly the fields accepted by `ModelProfilesConfigLoader`;
inline API keys remain unrepresentable. A candidate may name an absent
environment variable and save successfully, but the resulting profile is
visibly unavailable until the process environment supplies it.

Saving performs this transaction:

1. validate the complete immutable candidate;
2. resolve environment presence and trusted metadata from snapshots;
3. prepare the complete replacement registry state without mutation;
4. encode and atomically replace `models.json`;
5. publish the already-prepared registry state in one atomic reference swap.

No parsing, network request, or file I/O remains after step 4. Therefore a
failure before the move preserves the prior file/runtime, and publication after
the move is a non-failing in-memory operation. The first native save after a
legacy `model.json` import writes current `models.json`; the legacy file is not
rewritten and is no longer authoritative.

Background metadata completion never invokes registry replacement from the
profile snapshot it started with. It atomically updates only validated cache
entries and notifies the settings service with source/model identity. The
service re-resolves the current profile generation; a stale generation is
recomputed from current profiles or skipped, never allowed to overwrite a
newer profile save.

### 4.2 Local Tool and Skill policy

`capabilities.json` uses one current strict schema:

```json
{
  "schemaVersion": 1,
  "disabledTools": [],
  "disabledSkills": []
}
```

The arrays contain stable IDs/names, are duplicate-free in their canonical
projection, and retain unknown disabled identities for temporarily absent
optional integrations. Registered local read-only Tools and valid Skills are
enabled by default. The file can only remove capabilities from future local-
model requests; it cannot register anything, expose server capabilities, or
widen permissions.

An enabled Skill must have every declared allowed Tool enabled. Dependency
conflicts reject the complete candidate as `capability_dependency_conflict`;
OpenAllay never silently changes another toggle.

### 4.3 Capability-owned recipe settings

The existing adapter-specific recipe schema is replaced before release with a
generic current schema:

```json
{
  "schemaVersion": 2,
  "visibility": "ALL_KNOWN",
  "preferredViewer": "auto",
  "disabledSources": []
}
```

`preferredViewer` is `auto` or a stable registered viewer source ID.
`disabledSources` holds stable recipe-provider IDs such as the vanilla source
and installed viewer adapters. New authorized registered sources are enabled by
default. Unknown disabled IDs remain disabled if their integration later
returns. An explicit unavailable preferred viewer remains selected and yields
`viewer_unavailable`; it never silently chooses a different viewer. `auto`
uses the existing deterministic viewer preference order over enabled available
registrations.

This file belongs to the recipe Tool child page. Future capability-owned
settings use their own strict codecs and paths through registered common
adapters; the catalog does not accept arbitrary schemas or paths.

### 4.4 Display and automatic state

`display.json` continues to own `debugMode`, defaulting to false. Presentation
settings are added only alongside implemented renderer behavior. Metadata
cache remains automatically managed in `model-metadata.json`, and history
remains exclusively owned by `history.sqlite3` and its repository. The settings
screen never edits either automatic format directly.

Missing JSON files load documented defaults without immediately creating a
file. Explicit save materializes the current complete format. Unsupported or
invalid files fail closed, retain the last valid runtime, and expose a redacted
repair diagnostic. Reloading over a dirty screen draft requires explicit
discard confirmation.

## 5. Knowledge and capability catalog

`CapabilitySettingsCatalog` is a common code-owned registry of immutable
descriptors. Each descriptor has a stable ID, kind (`KNOWLEDGE_SOURCE`, `TOOL`,
or `SKILL`), localization keys, availability, enabled state, friendly status,
optional dependencies, and an optional typed child-page route. Registration is
performed only by OpenAllay bootstrap or trusted loader integrations; model
output, Skills, knowledge documents, resource packs, and provider responses
cannot register controls or callbacks.

Tool cards derive from the base `ToolRegistry` plus provider identity needed
for grouping. Skill cards derive from validated `SkillRepository` metadata.
Knowledge-source cards derive from immutable knowledge/source diagnostics.
Server-advertised capabilities appear separately as read-only server state;
local settings never claim authority to enable them.

The recipe Tool registers one child adapter that combines:

- current all-known visibility policy;
- registered vanilla/viewer source states and diagnostics;
- stable-ID enable/disable controls;
- preferred-viewer selection;
- installed viewer navigation capability and exact-recipe support.

JEI, REI, EMI, or a later viewer appears only when a compatible integration
registers its provider/navigator descriptor. The generic settings schema does
not imply that an unavailable integration exists or works.

Future MC Encyclopedia, Minecraft Wiki, or Web Fetch entries may reuse the
catalog and shared HTTP transport only after their own accepted endpoint,
permission, evidence, privacy, and failure design. A catalog card never grants
Agent tool authority.

## 6. Per-request capability capture

The base Tool and Skill repositories remain bootstrap catalogs. A validated
`ClientCapabilitySnapshot` builds an immutable request runtime containing:

- enabled model Tool definitions;
- the exact executable Tool map and required context set;
- enabled Skill metadata and documents;
- a derived `load_skill` Tool bound to that immutable Skill view when at least
  one Skill is enabled;
- redacted capability diagnostics.

Client runtimes for future requests are rebuilt/published against the prepared
capability snapshot while retaining the shared `AgentSessionStore`. An active
request keeps the runtime, Tool definitions, execution map, Skill metadata,
Skill documents, and context requirements captured when it started. Changing a
Tool or Skill never changes a continuation already in progress and never
clears or rewrites conversation history.

Execution validates against the captured map as well as the model-visible
definitions. A stale or malformed call to a disabled/missing Tool returns
`tool_unavailable`; a disabled Skill is absent from metadata and exact lookup
returns `skill_not_found`. Neither path falls back or exposes disabled content.
`load_skill` is internal capability plumbing rather than a separately
persistent player toggle; disabling all Skills removes it from future request
definitions.

## 7. Model profile administration and live probe

The Models page supports ordered profile creation, editing, enable/disable,
default selection, deletion, reload, trusted metadata refresh, and connection
testing. It edits protocol, HTTPS/loopback base URL, model ID, environment-
variable name, explicit/discovered context window, output limit, and timeouts.
The UI shows only whether the named environment variable is present.

Deleting a selected profile requires explicit confirmation. Conversation
history and the remembered selection are retained; later submission reports
the selection as unavailable until repaired. Active requests continue on their
captured runtime.

“Test connection” visibly warns that it sends a small possibly billable real
request. It accepts a valid saved or unsaved candidate, requires the named
credential to be present, and sends one isolated generation through the exact
configured protocol/endpoint/model and shared transport. It includes a fixed
instruction to answer `OK`, no Guide session, player message, history, Tool,
Skill, game state, evidence, or trace. The probe uses configured timeouts and
the smaller of the profile output limit and 64 tokens. Any protocol-valid
non-empty assistant response is success; its text is discarded.

The probe is cancellable, non-retrying, does not follow redirects, does not use
Guide scheduling, does not honor a 429 by sending later, and never falls back.
Only one probe may run in one settings controller. Close/disconnect/shutdown
cancels it. Results are transient and contain only redacted profile/protocol/
endpoint-authority identity, completion time, latency, and stable category.
Provider bodies, assistant output, credentials, headers, URL userinfo/query/
fragment, and raw exceptions are never retained or rendered.

Metadata refresh/listing remains a separate non-inference operation. Its
success never marks a connection probe successful.

## 8. Native screen structure

The existing OpenAllay screen keeps a settings button. It opens a native screen
with these top-level sections:

- `常规 / General`: Debug Mode and implemented presentation preferences;
- `模型 / Models`: profile list/editor, metadata, environment presence, and
  live probe;
- `知识与能力 / Knowledge & Capabilities`: filterable source/Tool/Skill cards,
  local deny toggles, and registered child pages such as recipe settings;
- `历史 / History`: friendly database health and actor-scoped deletion;
- `诊断 / Diagnostics`: friendly normal health cards and Debug Mode technical
  expansion.

Wide layouts use a section rail, list, and details/editor pane. Narrow layouts
show one level at a time with a native back action. Lists virtualize where they
can grow, preserve keyboard focus, use scissor boundaries, and provide
narration. Toggle state is conveyed by localized text/icon as well as color.

Normal mode uses friendly cards and repair actions. It does not display raw
IDs, confidence, authority/completeness enums, internal handles, normalized
JSON, stack traces, database paths, or provider bodies. Debug Mode appends a
visibly separate redacted section containing only already-authorized technical
IDs, topology, source generation/counts, database schema/health, context
estimates/checkpoint state, queue/rate state, and stable failure codes. It
cannot represent reasoning, credentials, authorization data, raw provider
bodies, another player's data, or arbitrary filesystem paths.

## 9. History administration

The History page operates on the current actor supplied by the active
`GuideService`; it never accepts an actor ID from a text field. Normal actions
delete the current partition or all partitions owned by the current actor.
They are unavailable while the actor has an active request or the ordered
history repository has pending writes. OpenAllay does not cancel work to make
deletion succeed.

Successful deletion transactionally removes the selected rows and resets the
matching in-memory session projection before accepting new writes. Failures
retain both durable and in-memory history. Whole-database reset is visible only
in Debug Mode and requires a distinct second confirmation. It is rejected
while any request/write is active. Unsupported pre-release schemas are never
deleted automatically.

All history operations use GuideService/repository methods; the settings layer
does not execute SQL or open the database itself.

## 10. Failure and concurrency model

Stable failures include:

- `settings_busy`, `settings_discard_confirmation_required`, and
  `settings_write_failed` for common coordination;
- existing domain-specific `invalid_*_config` failures;
- `capability_dependency_conflict`, `tool_unavailable`, `skill_not_found`, and
  `viewer_unavailable` for capability policy;
- `connection_test_busy`, `connection_cancelled`, `connection_auth_failed`,
  `connection_model_unavailable`, `connection_rate_limited`,
  `connection_timeout`, `connection_transport_failed`, and
  `connection_protocol_failed` for live probes;
- `history_delete_busy`, `history_delete_confirmation_required`, and
  `history_delete_failed` for history administration.

One domain failure does not disable unrelated settings or Agent capabilities.
Every failure is reduced at its adapter boundary and contains no raw provider
body, credential, SQL/path detail, or foreign-player state. The service retains
the last valid runtime unless the corresponding atomic operation committed.

The authoritative state/failure decisions are SKMB-2026-07-18-009,
SKMB-2026-07-18-010, SKMB-2026-07-18-012 through 017.

## 11. Verification

Deterministic common tests cover:

- strict canonical encoding/decoding and unknown-field/schema rejection for
  models, capabilities, generic recipe settings, and display state;
- temporary-write/atomic-move failure, prior-file/runtime retention, prepared
  publication, legacy model import save, and no cross-domain rewrite;
- service busy/detach/reopen/dirty-draft/reload/shutdown ordering;
- model profile CRUD, missing environment, metadata precedence, active-request
  capture, deletion without history rewrite, and every redacted probe outcome
  through fake ModelClients/transports;
- catalog registration authority, normal/debug projection privacy, stable
  ordering, unavailable integrations, and child-page routing;
- Tool/Skill dependency rejection, disabled lookup, immutable active-request
  Tool/Skill capture, future-request replacement, required-context filtering,
  and provider-neutral history continuity;
- generic recipe source IDs, absent/reappearing disabled sources, explicit
  unavailable viewer without fallback, `auto` ordering, and installed adapter
  states;
- actor-isolated history deletion, busy/pending-write rejection, rollback,
  late-save resurrection prevention, Debug Mode reset confirmation, and
  unsupported-schema preservation;
- keyboard/narration/narrow/wide screen state and Fabric/NeoForge wiring parity;
- credential/body/reasoning/path redaction in snapshots, logs, fixtures, and
  production JARs.

Implementation runs focused suites while iterating, then the complete gate:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

The real provider probe is opt-in. A temporary ignored profile file and an
environment variable supplied outside Git may exercise one explicitly
authorized small request. Retained evidence records only model/profile label,
protocol, redacted endpoint authority, timestamp, latency, terminal category,
artifact versions, and hashes. It never records the key, request/response body,
or assistant text. The credential already shared through conversation should
be rotated after acceptance.

Graphical acceptance opens the settings screen on both loaders, checks narrow
and wide navigation, edits/reloads each domain, verifies the recipe Tool child
page with every actually compatible installed viewer, exercises history
confirmations using test data, and confirms Debug Mode redaction. Claims are
limited to retained evidence; unavailable EMI/other integrations are reported
honestly rather than inferred from generic support.

## 12. Implementation order and completion boundary

Implementation proceeds in coherent slices:

1. atomic domain stores and `ClientSettingsService` snapshots/operation state;
2. model profile writer/prepared replacement and isolated live probe;
3. knowledge/capability catalog, local Tool/Skill policy, and per-request
   capability capture;
4. generic stable-ID recipe Tool settings and integration descriptors;
5. history administration service/store operations;
6. native screen, child routing, localization, diagnostics, and both loader
   adapters;
7. deterministic full gate plus opt-in real-provider and graphical evidence.

This package is complete only when every slice is implemented, both loaders are
in parity, the full clean gate passes, credentials remain absent from source and
artifacts, and retained smoke evidence supports every live/graphical claim.
Animation/accessibility behavior tied to not-yet-built rich components and
long-history paging remain explicit subsequent Phase 4 work rather than being
misrepresented as settings completion.
