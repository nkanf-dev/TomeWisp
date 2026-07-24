# Development

OpenAllay's main development line targets Minecraft 26.2 and requires Java 25.
Use the checked-in Gradle wrapper; no system Gradle installation is required.

## Build and test

```bash
./gradlew :common:test
./gradlew :fabric:build :neoforge:build
```

For unreliable Maven connections, replace `./gradlew` with `./gradlew-curl`.
The helper extracts failed dependency URLs from Gradle output, downloads them
with curl retries into the ignored `.gradle/curl-mirror` directory, and resumes
the same command. On FLClash, its proxy can be selected explicitly:

```bash
OPENALLAY_CURL_PROXY=socks5h://127.0.0.1:7890 ./gradlew-curl build
```

## Continuous integration and releases

The `Quality` GitHub Actions workflow runs for pull requests, `main`, `mc/**`,
and manual dispatches. It validates automation sources, runs the clean common
test plus both loader build gate, inspects the Phase 4 and SQLite packaging
contracts, and verifies that the distribution contains exactly one production
JAR per loader under the OpenAllay identity.

Releases are created only by pushing an annotated strict-SemVer tag. The tag
must exactly match `version` in `gradle.properties`. A SemVer prerelease suffix,
including `-SNAPSHOT`, is published as a GitHub prerelease and a Modrinth alpha;
a version without a suffix is published as a stable release.

```bash
# Snapshot example; choose the intended version.
git tag -a v0.1.0-SNAPSHOT -m 'OpenAllay 0.1.0-SNAPSHOT'
git push origin main v0.1.0-SNAPSHOT
```

The release workflow rejects lightweight tags, tags whose version differs from
`gradle.properties`, tags outside `main`, existing releases, failed tests, and
malformed distributions, and a missing `MODRINTH_TOKEN` Actions secret. A
successful run publishes the Fabric and NeoForge production JARs to Modrinth,
publishes the same two JARs plus `SHA256SUMS` to GitHub, attaches build
provenance, and generates release notes containing changes, the complete commit
range, and contributors. The Modrinth publisher creates and submits the
`openallay` project on first release and is idempotent for each loader/version.
Enable protected `v*` tags, required `Quality` checks, read-only default Actions
permissions, and immutable releases in repository settings; workflow files
cannot enforce those repository-level controls themselves.

## Run environments

```bash
./gradlew :fabric:runClient
./gradlew :fabric:runServer
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
```

Dedicated validation is headless. Fabric accepts `--args nogui`; NeoForge's
development launcher is already headless and must be run without Gradle
`--args`, because that option replaces its launch main class.

The accepted Fabric 26.2 full-mod development profile uses Architectury Fabric
21.0.4. Versions through 21.0.2 are declared incompatible when the optional mod
is present because their screen-input delegate breaks character entry.
OpenAllay does not require Architectury, and the NeoForge profile is unaffected
by this Fabric-only compatibility boundary.

## Client model configuration

The main mode is pure client-side. The current player-managed format is
`config/openallay/models.json` schema 2. It contains no secret: each profile
retains only a qualified `credentialRef`. `model.json` remains an import path
only when the new file is absent.

Ordinary players enter an API key through the native masked password field.
OpenAllay stores it under an immutable `local:<uuid>` reference in
`config/openallay/credentials.sqlite3`; a saved key is never filled back into
the widget or exposed through copy/cut, settings snapshots, diagnostics, logs,
packets, prompts, or history. On POSIX systems the credential database receives
owner-only permissions on a best-effort basis. This is local restrictive
storage, not an OS-native vault.

Externally authored development or headless configurations may instead name an
environment reference. This form is not requested by the normal player UI:

```json
{
  "schemaVersion": 2,
  "defaultProfileId": "openrouter-main",
  "profiles": [
    {
      "id": "openrouter-main",
      "displayName": "OpenRouter Main",
      "enabled": true,
      "protocol": "openai_chat",
      "baseUrl": "https://openrouter.ai/api/v1/",
      "model": "provider/model-id",
      "credentialRef": "env:OPENROUTER_API_KEY",
      "contextWindowTokens": 256000,
      "maxOutputTokens": 8192,
      "connectTimeoutSeconds": 30,
      "requestTimeoutSeconds": 300
    }
  ]
}
```

`anthropic_messages` is the other protocol. Remote endpoints require HTTPS;
HTTP is accepted only for loopback development. Inline `apiKey` and legacy
player-facing `apiKeyEnv` are invalid in schema 2. `contextWindowTokens` is
required unless trusted
provider metadata or its local cache resolves it; an explicit value always
wins. The `256000` value above is an example, not a fallback.

`connectTimeoutSeconds` covers establishment of the provider connection.
`requestTimeoutSeconds` is the total budget for one dispatched model attempt,
including response headers, streaming body consumption, and decoding. A
scheduled cancellable watchdog closes a stalled body and reports the stable
`model_timeout` failure; cancellation, timeout, or completion wins exactly once
and late bytes cannot mutate the request.

OpenRouter metadata uses the official `GET /api/v1/models` catalog fields
`id`, `canonical_slug`, `context_length`, and the optional
`top_provider.max_completion_tokens`. Startup reads
`config/openallay/model-metadata.json` asynchronously. A cache miss refreshes in
the background without blocking startup; successful credential-free metadata
is cached across launches, and a failed refresh leaves explicit configuration
and prior cache intact. The cache is configuration-layer state, not an Agent
tool. Model providers and future online knowledge tools reuse the JDK HTTP
transport mechanics but retain separate credentials, permissions, codecs, and
evidence policy.

The guide screen's compact model control opens an explicit scrollable selector
for the selected session's named profiles and the server model when offered;
it never changes the model merely by cycling a button. Returning from settings
refreshes capabilities automatically. Switching during an active request
changes only the next request; the status line continues to show the model
captured by the running request. Commands provide the same semantics:

```text
/guide model list
/guide model profile <profile-id>
/guide model client
/guide model server
```

The last two forms remain compatibility shortcuts. `client` restores that
session's last named client profile and never silently chooses another one.

The Guide screen's gear button opens the common native settings screen on both
Fabric and NeoForge. Its Models page can create, edit, enable/disable, delete,
select the default profile, reload external edits, manually refresh trusted
metadata, and run one explicit connection test. Saving validates the whole
candidate and stages a new immutable local credential where needed, atomically
replaces `models.json`, and only then publishes the already-prepared runtime for
future requests. Active requests retain the runtime they captured at
submission. Replacing a key never overwrites the credential used by an active
profile; unreachable rows are collected only after successful publication.

The Models page configures client-owned profiles only. A connected server model
is shown in the Guide model selector as a synchronized, read-only choice and is
configured exclusively by the server's `server-model.json`. The model ID field
remains editable and can fetch an authenticated `/models` catalog using the
currently typed password first or the already-saved credential otherwise. A
saved credential is represented by an explicit saved-key hint; its value is
never filled back into the field. Anthropic profiles send their native API-key
and version headers plus Bearer compatibility for gateways whose catalog route
is OpenAI-style; every header remains confined to the validated provider
origin.

When a player explicitly selects the server model, the request advertises only
that player's currently enabled registered read-only client Tools. The server
intersects the IDs with its trusted registry and may call those Tools back on
the requesting client, including client options, installed mods, packs,
shaders, F3-style diagnostics, and player-visible state. Results return in
bounded chunks and remain correlated to the actor, request, and invocation.
Tool failures are returned to the model as structured results so it can explain
or recover; explicit cancellation, disconnect, and shutdown remain terminal.
The same rule applies to client-hosted models using server Tools: bridge
unavailability, malformed results, and the five-minute remote Tool deadline are
complete Tool failures, not Agent termination. Incomplete request, result, and
event chunk assemblies are sparse, accepted only for active requests, and
expire after the same deadline (or immediately at request termination).
Only the live Minecraft snapshot capture runs on the client thread. Tool
execution and potentially large normalization/chunk encoding run on a virtual
worker, and packet sends are marshalled back to the client thread. Invalid
settings fail closed to an empty advertised client Tool set.

The top-level settings sections are General, Models, Tools, Skills, History,
and Diagnostics. Tools and Skills are separate master-detail pages. Selecting a
Tool in the left pane shows its description, explicit enable control, settings,
and source editor on the right; selection never implicitly toggles the Tool.
Local Tool enablement remains a deny-only restriction over registered code and
cannot register a Tool or widen its authority. Tool enablement and its sources
are saved together in the Tool-owned file described below, so there is no
second generic Tool/Skill toggle document. Server-owned capabilities remain
read-only advertised state. Skills are documents rather than Tool-style
toggles; their filesystem packages, validation, provenance, and override rules
are described below.

The connection test displays a cost warning and requires a second confirmation.
It sends one non-streaming, non-retrying request capped at 64 output tokens with
no Guide history, Tools, Skills, game state, evidence, or trace. Assistant text
and provider bodies are discarded. The result contains only a stable category,
redacted endpoint authority, protocol, completion time, and latency. Model
metadata refresh/listing is a separate configuration operation and never counts
as a successful inference test. Closing settings cancels only an active probe;
an already-confirmed atomic save continues to its terminal result.

If neither model file exists, OpenAllay presents one disabled in-memory draft and
does not create a file until the player explicitly saves. Invalid startup files
remain untouched and produce a redacted settings notice. The screen receives
only credential presence and transient password-input state; it cannot read or
render a stored value.

Every knowledge or recipe source belongs to exactly one logical Tool. Strict,
independently versioned files live at
`config/openallay/tools/<tool-family-id>.json`. The common envelope is:

```json
{
  "schemaVersion": 1,
  "toolId": "openallay:guides",
  "enabled": true,
  "sources": [
    {
      "sourceId": "user:minecraft-notes",
      "sourceKind": "local_markdown",
      "displayName": "Minecraft Notes",
      "enabled": true,
      "config": {
        "directory": "minecraft-notes",
        "locale": "zh_cn"
      }
    }
  ]
}
```

The common envelope accepts no arbitrary source-kind fields. A trusted
`ToolSourceKind` registry supplies the strict config codec, localized controls,
lifecycle capabilities, capture/refresh implementation, evidence contract, and
optional credential-reference support for each owning Tool and `sourceKind`.
Built-in/discovered sources can be inspected, enabled/disabled, refreshed where
meaningful, and restored, but cannot be deleted or have their identity/kind
edited. Registered user-source kinds may support add, edit, delete, test, and
refresh. The initial user-creatable Guides kind is `local_markdown`, confined
below OpenAllay's managed configuration root. It does not grant arbitrary path or
network authority.

Recipes owns recipe search, exact lookup, item usage, all-known/unlocked
visibility, recipe sources, and preferred viewer selection. Inventory and
Craftability remain separate Tools. Guides owns knowledge search, exact
document loading, and Patchouli/FTB/local-document sources. JEI and REI adapters
are available where compatible; EMI is not fabricated when no verified 26.2
adapter exists. Web Fetch is a future Tool, not a source.

The Recipes family envelope at `tools/recipes.json` owns Tool enablement and
source enablement. Its typed behavior options (all-known/unlocked visibility and
preferred viewer) remain independently strict at
`tools/recipes-options.json`; both files belong only to the Recipes detail page
and neither creates a top-level settings domain.

Player-facing tool details are controlled separately by
`config/openallay/display.json` on both loaders. A missing file uses the safe
default below:

```json
{
  "schemaVersion": 3,
  "debugMode": false,
  "animationsEnabled": true,
  "assistantName": "OpenAllay"
}
```

`assistantName` is the player-chosen local name shown for the companion in the
native interface. It is trimmed, must not be blank, and cannot contain control
characters. Renaming it changes presentation only; it does not rewrite saved
conversation content, session IDs, evidence, or model/tool configuration.

Normal mode renders scrollable recipe, inventory, usage, and craftability cards
with native item icons, counts, tooltips, and typed recipe-viewer actions. It
does not expose tool/invocation IDs, evidence authority/completeness enums,
capture timestamps, provenance, internal failure codes, or normalized JSON.
Setting `debugMode` to `true` appends a clearly separated local diagnostic
section containing the already-redacted technical projection. An invalid file
keeps Debug Mode off and displays a localized notice; it never rewrites the
malformed file. The General page edits Debug Mode through the shared
`GuideDisplayRuntime`; an atomic save immediately reprojects both the settings
screen and newly rendered Guide content without requiring a restart. Reload
retains the last valid projection on malformed external edits.

`animationsEnabled` controls only subtle progress presentation. It does not
change semantic content, action availability, evidence, layout identity, or
narration. Schemas 1 and 2 are pre-release development state and are rejected
rather than migrated. Normal diagnostics say that history is loaded on demand and
show current-page loading/failure in friendly terms. Debug diagnostics add
only count-based window cursors, loaded/total counts, semantic cache hits and
misses, fallback counts, and context-token estimates; they never include raw
cursor/request payloads, transcripts, paths, provider bodies, actors, or scope
identifiers.

The History page projects only friendly connection kind, persistence health,
active-request state, and pending-write/deletion status. It can delete the
current player/world-or-server partition or all partitions belonging to the
current local player identity. Both actions require a fresh one-use
confirmation and are rejected as `history_delete_busy` while matching requests
or ordered writes are active. Whole-database reset is visible only in Debug
Mode and requires a distinct second confirmation. Settings delegates every
operation to the current `GuideService`/ordered repository; it never receives a
raw scope identifier, database path, or SQL string.

Diagnostics always presents localized, player-friendly cards for model,
knowledge/capability, recipe, history, and context health. Debug Mode adds a
separate whitelisted technical section containing only redacted endpoint
authority, metadata/checkpoint/source generations, counts, and stable status
codes. Neither projection can contain provider bodies, reasoning, transcript
content, credential values, raw history scopes, or filesystem paths. Closing
the screen discards drafts and confirmation tokens but does not cancel an
already confirmed history/configuration transaction; shutdown disconnects the
Guide service, closes ordered history, cancels any live probe, and closes the
metadata cache asynchronously on both loaders.

For an optional server-hosted model, use
`config/openallay/server-model.json` on the server. The capability is advertised
only when that configuration is valid. Client packets never contain the key.

## Player commands

```text
/guide <question>
/guide ask <question>
/guide cancel
/guide clear
/guide status
/guide skills
/guide sources
/guide session list
/guide session new <id>
/guide session switch <id>
/guide session close <id>
/guide model client
/guide model server
```

The same player may run requests concurrently in different sessions. A second
request in the same session returns `agent_busy`. OpenAllay applies no fixed
global concurrency or queue-count limit. Provider HTTP 429 closes only the
matching endpoint gate, honors `Retry-After` when present, and otherwise uses
cancellable exponential backoff with fair session rotation.

Client-local tools use detached client player, registry, and synchronized
recipe-display snapshots. If the server advertises enhancements, the same
client Agent also sees separately named server read tools. Model location and
tool location are independent.

## Shared GuideService and opt-in client E2E

Commands, the Phase 3C screen, and development probes consume one
connection-scoped `GuideService`. It owns immutable request/session snapshots,
model mode, topology, cancellation, retry, sources, and disconnect cleanup.
Each active snapshot carries only redacted lifecycle progress: phase, request
and phase start, most recent progress, attempt, and optional retry/deadline.
The GUI derives elapsed/countdown text locally in a fixed-height strip; clocks
do not append transcript rows or create history writes.
Across the client/server bridge, an attempt carries its relative timeout budget
rather than a server wall-clock epoch. The receiving client derives a local
display-only deadline, so clock skew cannot shorten, extend, or invalidate the
server-owned watchdog.
The server Agent protocol is version 5 and is decoded once in common
code. Unknown or malformed events fail only their correlated request; there is
no silent client/server fallback. Server-model requests carry only the
partition's visible user/completed-assistant history; they never carry restored
capabilities, live evidence, reasoning, credentials, or full normalized tool
results. Protocol v5 also carries the selected server model's actual context
budget and canonical model identity, and splits the encoded request into independently strict,
SHA-256-checked 24 KiB transport chunks so long histories do not depend on one
Minecraft custom-payload string.

Normal-mode guide history is stored at `config/openallay/history.sqlite3` in the
single current pre-release SQLite schema, currently schema 5. Because OpenAllay
has not shipped, recognized OpenAllay schemas 1, 2, 3, and 4 are not migrated: on
startup their application tables are transactionally rebuilt as schema 5.
Rollback preserves the recognized older database if rebuild fails and reports
`history_schema_rebuild_failed`. A future schema, corrupt database, missing or
inconsistent metadata, unrecognized tables, or foreign file still fails closed
without deletion. This automatic rebuild policy must be removed or replaced by
an explicit shipped-schema compatibility decision before formal release. Each
partition key is a SHA-256 digest of the player
UUID, connection kind, and normalized integrated-world path or multiplayer
address; the raw path/address is not stored. Database work runs on one ordered
background worker and never blocks the client or render thread.

The durable projection contains sessions, their selected model profiles, user
messages, chronological visible assistant/tool/status entries, each request's
captured model selection, evidence summaries, and terminal request state. It
excludes model reasoning, credentials, authorization data,
raw provider bodies, full inventory snapshots, and full normalized tool
results. Loading temporarily disables submission. A load or write failure keeps
the in-memory Agent usable and shows that new messages are not durable. Work
left active by process loss restores as `INTERRUPTED`; continuing it always
requires an explicit retry with a new request ID. Versioned compaction
checkpoints are retained separately as derived, non-evidence memory and reused
only when their source hash and prompt/schema versions match. The generating
model ID is provenance, not a reuse lock: changing provider or model keeps the
session transcript and a valid checkpoint, then re-estimates the projection
against the newly selected model's own budget. Player history administration
and redacted normal/debug diagnostics are available in native settings.
Startup restores partition/session metadata without materializing request
bodies. The screen requests viewport-sized history pages independently from
provider-neutral context reads, which use the selected model's actual budget.
Safe Markdown, validated Minecraft references, registered controlled
components, semantic fallback text, variable-height virtualization, stable
anchors, and presentation-only animation are implemented in common code.

The real-client probe is disabled unless `openallay.e2e.enabled=true`. When
enabled, it waits for a real client player, submits through the same
`GuideService`, records status transitions, tool IDs, evidence, timings and
payload hashes, writes a redacted JSON report, then optionally requests clean
client shutdown. Report writing belongs to this development harness and is not
an Agent tool.

The helper below starts a deterministic loopback OpenAI/SSE fixture and launches
the selected graphical development client:

```bash
./scripts/run-real-client-e2e.sh fabric
./scripts/run-real-client-e2e.sh neoforge
```

Connect the launched client to a disposable world or test server, or set
`OPENALLAY_E2E_QUICK_PLAY_WORLD` to an existing disposable single-player world.
The fixture waits for durable hydration and every enabled installed recipe
viewer to publish a non-empty current catalog. It streams deliberately split
Markdown/component tokens through a grounded five-tool chronology: recipe
search, exact lookup, inventory, deterministic craftability, and knowledge
sources. The report records redacted semantic/component/fallback counts and
history-window/cache metrics, and the script rejects any outcome other than
`COMPLETED`.

The default retained recipe is `minecraft:iron_block`. A compatible mod recipe
can exercise native viewer embedding without changing the fixture, for example:

```bash
OPENALLAY_E2E_RECIPE_OUTPUT=farmersdelight:apple_cider \
OPENALLAY_E2E_RECIPE_ID=farmersdelight:cooking/apple_cider \
OPENALLAY_E2E_RECIPE_LABEL=苹果酒 \
./scripts/run-real-client-e2e.sh fabric
```

These variables affect only the deterministic loopback scenario. The exact
reference is still discovered from a real current recipe-provider generation;
the fixture never fabricates the viewer handle.

Set `OPENALLAY_E2E_HISTORY_SEED_REQUESTS` to create sequential durable seed
requests before the reported scenario. `OPENALLAY_E2E_MIN_HISTORY_REQUESTS`
asserts the durable total; `OPENALLAY_E2E_REQUIRE_PAGED_HISTORY=true` additionally
requires a restart to hydrate fewer requests than the durable total and expose
an earlier-page cursor. The harness temporarily replaces `models.json` with an
isolated loopback profile and restores the exact prior file on exit.

The harness is intentionally opt-in because it opens a graphical client. CI
validates the controller, both loader hooks, shell syntax, and fixture syntax,
but does not claim a real-client run. Earlier Phase 4C Fabric reports, redacted
logs, exact JEI navigation screenshots, artifact URLs, and hashes are retained
under `docs/verification/phase-4c-all-known-recipes/`. Earlier consolidated
Fabric/NeoForge semantic-history reports and compatibility boundaries are under
`docs/verification/phase-4-final-acceptance/`; those artifacts predate the
manual-acceptance correction set and do not close it.
The closing correction reports, 12 reviewed screenshots, exact game-state Tool
probes, runtime artifact provenance, and production hashes are retained under
`docs/verification/phase-4-final-corrections/`.

## Player GUI

The configurable `key.openallay.open_guide` mapping defaults to `K`; bare
`/guide` uses the same opener on Fabric and NeoForge. The native full-screen
Screen does not pause the world. Escape and opening another Screen only detach
the UI subscription, so active work continues and reopening reconstructs from
the latest immutable GuideSnapshot.

The screen provides a responsive session rail/overlay, virtualized wrapped
transcript, multiline composer (Enter sends, Shift+Enter inserts a line break,
and Ctrl+Enter remains a compatibility shortcut), stop/retry controls, and an
explicit local/server model selector. Enter outside the focused composer keeps
the selected widget/card action. Only model text deltas are visible;
reasoning is absent from the UI view type. Assistant segments and tool cards
render in actual Agent event order, and a running card updates in place by its
tool invocation ID before later assistant text appears. Grounded recipe,
inventory and craftability tools receive first-class summaries, while other
tools use a deterministic friendly fallback. Clicking a tool opens a scrollable
card detail panel; clicking an answer's evidence link shows a player-friendly
explanation in normal mode. Technical evidence metadata and normalized JSON are
only representable when the local default-off Debug Mode is enabled. No browser
is launched. Session switches and disconnect cleanup remove stale detail state.

Deleting a session from the Guide screen uses two native confirmations bound to
the originally selected session; the second warning states that durable history
is removed and any active request is stopped. User and assistant rows expose an
explicit local clipboard action. The Export action captures the complete
current session in request/timeline order and atomically writes a UTF-8 text
file below `openallay/exports` in the Minecraft game directory. The writer has no
path input, rejects symlink escape, and omits normalized Tool payloads,
checkpoints, model settings, raw diagnostics, and credential-shaped text.

Validated inline `recipe_grid` components bind only to the complete normalized
recipe result from their same-request Tool invocation. Visible bindings enter a
client-thread-only `NativeDomainView` lifecycle and are released when their row
leaves the viewport or the Screen closes. JEI 26.2 exact references re-resolve
against the current provider generation and embed its public
`IRecipeLayoutDrawable` (`drawRecipe`, overlays/tooltips, and `tick`). Oversized,
stale, missing, or failed layouts fall through to OpenAllay's neutral labelled
slot canvas. REI 26.2 currently exposes category widget construction but no
verified exact durable-reference re-resolution contract used by OpenAllay, so
its native provider records `rei_exact_embedding_unsupported` and uses the same
neutral fallback instead of claiming or approximating a mod-owned screen.

If the selected model is unavailable, the screen still opens and shows the
configuration/capability state. Client profiles remain at
`config/openallay/models.json`; the Models page accepts a transient masked API
key but never displays a stored secret. Model-mode changes affect future
requests only and never trigger silent fallback.

## Rhino Agent runtime

Every factual success carries immutable evidence: authority, completeness,
capture time, source, provenance, game version, loader, and optional scoped
details. The general model-facing analysis operation is:

```text
openallay:run_javascript
openallay:calculate_craftability
```

OpenAllay embeds the KubeJS-Mods Rhino fork directly; KubeJS itself is not a
runtime dependency. Every invocation gets a fresh safe Rhino context over an
immutable detached `mc` graph. `MinecraftAgentHostGraph` retains the original
detached Java records and lists; `RhinoHostAdapter` exposes record components,
collection elements, String-keyed map entries, Optional values, stable scalars,
and existing Gson leaves through lazy identity-cached read-only `Scriptable`
views. Request and workspace input is never converted to a second Gson tree,
embedded in source, stringified, or parsed with `JSON.parse`.

Normal JavaScript `filter`, `map`, `reduce`, grouping, joins, optional chaining,
and pure helper functions replace repeated per-row domain Tool calls. Host
arrays use the standard Array prototype but are not writable. Non-mutating
transforms create ordinary JavaScript arrays; call `filter`, `map`, `flatMap`,
or `slice` before `sort`, `reverse`, `splice`, or other mutation.

`run_javascript` accepts `roots`; normal analysis should select only the
required host views, for example `["items"]` or `["items", "recipes"]`.
Registry and recipe rows are exposed once in those JavaScript-native views,
while `mc.registries` and `mc.recipeCatalog` carry catalog metadata.

The graph exposes captured capabilities such as:

```text
mc.capabilities
mc.items / blocks / fluids / effects / enchantments / entities
mc.registries
mc.recipes / recipeCatalog
mc.player
mc.game
mc.knowledge
mc.extensions
```

Registry `properties` and extension objects are open-ended. Runtime code should
inspect `Object.keys` or `helpers.schema` rather than relying on a core whitelist
for fields such as damage, nutrition, effect duration, or mod-added metadata.
All capture still occurs on the Minecraft-owned thread and detaches immediately
before Rhino runs on a virtual worker.

Canonical results remain internal Gson JSON for evidence validation, UI cards,
traces, and subsequent programs. The provider receives a compact CLI-like text
projection without JSON wrappers or evidence payload duplication. Large results
stay in a request workspace under an opaque handle; a later program can pass
that handle explicitly and call `workspace.open(handle)`. Client-executed Tools
for a server-hosted model share the same request correlation and close their
workspace at the terminal request event.

The runtime rejects source and result graphs that exceed the accepted
depth/node/array/object/string budgets before workspace publication. A request
workspace atomically admits at most 16 canonical results and 32 MiB of
estimated content; one execution may reopen at most four handles totaling
8 MiB. The model and player UI share the same bounded structured preview, while
debug UI renders bounded metadata rather than raw normalized JSON. Provider
input is re-estimated before every continuation turn.

The Rhino scope exposes no Java packages, arbitrary host wrappers, reflection,
class loading, network, process, real filesystem, command execution, live game
object, or mutation. Instruction observation enforces cancellation and a
monotonic deadline. Functions, promises, cycles, unsupported wrappers, excessive
nesting, and non-finite results fail with stable `javascript_*` codes.

Trusted optional integrations first capture and detach public mod API state on
the owning Minecraft thread. Java-side `JavascriptDataModule` implementations
run later on the Agent worker and may only project immutable
`ToolInvocationContext` records or immutable collections into evidence-bearing
values under `mc.extensions`; they cannot call live APIs or use reflection.
Capture failures are isolated as module diagnostics. Unsupported nested values
remain isolated to the property that attempts to read them. Ordinary model
JavaScript receives component values, never Java methods, `Class`, generic
wrappers, or reflection authority.

`calculate_craftability` uses deterministic global capacity allocation, so
overlapping item/tag alternatives are not assigned greedily. It reports the
observed allocation, missing requirements, maximum crafts, and whether the
evidence is conclusive. It does not recursively craft intermediate items.
Incomplete recipe or inventory evidence may show an observed positive result,
but `conclusive` remains false. Legacy domain retrieval implementations remain
only for compatibility tests and internal projections; bootstrap does not
advertise them to the model.

## Knowledge and Skills

Patchouli is read directly from active client resource packs, without a binary
dependency. Locale precedence is active locale, `zh_cn`, then `en_us`.
Config/advancement-gated entries whose visibility cannot be proven are excluded.
Text, item/recipe links, and embedded dense or sparse multiblocks are indexed.

Each successful knowledge reload builds one immutable, in-memory index and
publishes it atomically with the matching snapshot evidence. Search first
protects exact document and linked item/recipe identities, then weights stable
path aliases, source metadata, titles and Markdown headings before applying a
Unicode-aware BM25-style score to section text. A result retains the exact
`sourceId`/`documentId` pair used by `get_knowledge_document` and adds a stable
heading-derived `sectionId` plus an evidenced excerpt. Documents without
headings use the stable `document` section.

This index deliberately remains pure Java. Knowledge generations already live
as detached resource snapshots, so copying them into SQLite FTS would create a
second mutation and failure lifecycle without adding durability. The active
retriever is behind a narrow common interface so a later optional embedding or
reranking adapter can compose with the local candidates, but exact identities,
provenance, evidence and the deterministic offline path cannot be replaced by
an external score.

FTB Quests is optional. Its public API is resolved through allowlisted public
method handles; private reflection is never used. Only chapters and quests the
API reports visible for the current team enter the snapshot. On 26.2, where no
compatible FTB Quests release is currently available, the source reports an
explicit integration diagnostic and the rest of the Agent continues normally.

Skills follow a constrained Agent Skills filesystem format:

```text
skills/<skill-name>/
├── SKILL.md
├── references/     # optional read-only Markdown/text
└── assets/         # optional non-executable resources
```

`SKILL.md` contains YAML frontmatter and Markdown instructions. `name` and
`description` are required and the directory name matches `name`; optional
Agent Skills fields and OpenAllay namespaced string metadata remain strictly
validated. `allowed-tools` expresses a dependency only and never grants a
permission. Scripts, URL references, root escape, unsafe symlinks, arbitrary
paths, and unsupported files are rejected.

Bundled packages under the mod resources are immutable and use uppercase
`SKILL.md`. Local packages live under `config/openallay/skills/`; a valid local
package with the same name overrides its bundled package. The settings UI and
`openallay:manage_skill` share the confined package contract. Agent create,
update, and delete accept only an exact Skill name, complete `SKILL.md`, and
optional Markdown references below `references/`; they never accept a path.
Candidates are staged, parsed, dependency-checked, and published atomically.
Changes affect future request snapshots only. Deleting a bundled override
reveals the immutable bundled package.

`openallay:load_skill` returns at most 8192 characters at a time. When
`complete` is false, the model continues the same exact document with the
returned opaque cursor. The cursor is bound to the Skill name, reference path,
and content fingerprint; malformed, cross-document, and stale cursors fail
closed instead of falling back to a full read.

Skills are instructions and references, not executable Rhino modules. They
cannot fetch URLs, register Tools, grant permissions, or expand the JavaScript
sandbox. A Skill teaches the model how to use the registered runtime; it does
not itself execute.

## Live provider acceptance

The normal test suite skips real network calls. To verify streaming, a real
tool call, tool-result continuation, grounded Chinese output, and secret
redaction, export credentials only in the shell environment and run:

```bash
OPENALLAY_MODEL_BASE_URL=https://provider.example/v1/ \
OPENALLAY_MODEL=model-id \
OPENALLAY_API_KEY=... \
OPENALLAY_MODEL_PROTOCOL=ANTHROPIC_MESSAGES \
./scripts/live-model-smoke.sh
```

Never commit a model JSON containing `apiKey`.

To exercise the production Rhino prompt, bundled analytical Skill, JavaScript
Tool, compact model projection, and the two requested batch scenarios, use:

```bash
OPENALLAY_MODEL_BASE_URL=https://provider.example/v1/ \
OPENALLAY_MODEL=model-id \
OPENALLAY_API_KEY=... \
OPENALLAY_MODEL_PROTOCOL=OPENAI_CHAT \
./scripts/live-model-smoke.sh javascript-agent
```

The live test accepts `OPENALLAY_LIVE_STREAM=false` when isolating provider
stream transport from Agent/tool behavior. It records only redacted task,
Tool-ID, script, result-summary, and invocation-count diagnostics.
When an endpoint is intermittently unavailable, set
`OPENALLAY_LIVE_JAVASCRIPT_SCENARIO=sword` or `container` to rerun only that
scenario; the default `all` still exercises both.

To exercise exactly the native settings connection-probe contract from a
headless script, place a strict schema-2 `models.json`-format file in an ignored
path such as `run/openallay/settings-probe.json`. The externally authored file
uses `"credentialRef": "env:PROVIDER_KEY_NAMED_BY_THE_FILE"`; export that
environment variable in the shell, then run:

```bash
export OPENALLAY_SETTINGS_PROBE_CONFIG="$PWD/run/openallay/settings-probe.json"
export PROVIDER_KEY_NAMED_BY_THE_FILE='...'
./scripts/live-model-smoke.sh settings-probe
```

The script never accepts a credential on argv. It rejects inline `apiKey`,
legacy `apiKeyEnv`, URL credentials/query/fragment, and non-HTTPS remote
endpoints through the strict production loader. This environment-reference path
is for external/headless operation and is not a player settings workflow.
Retained output contains only the terminal code and, on success, profile ID,
protocol, redacted authority, and latency; it never prints assistant output or
raw provider bodies.

## Development commands

The following commands require game-master permission:

```text
/openallay dev tools
/openallay dev invoke openallay:inspect_game_state {"section":"OVERVIEW","query":"summary"}
/openallay dev replay platform-info
/openallay dev replay iron-ingot-recipe
/openallay dev replay iron-block-craftability
/openallay dev replay find-recipes-compatibility
/openallay dev replay player-context
```

The initial development tool surface is intentionally read-only. It does not
provide shell execution, arbitrary code execution, server-command execution,
file-system access, unrestricted reflection, arbitrary command parsing, world
or settings mutation, spatial scans, or external-container inspection.

## Deterministic Agent trace replay

Phase 1 deliberately replays recorded Agent traces instead of pretending that a
rule-based response is a live model. A tool-call step invokes the real
`ToolRegistry`; its normalized result is checked with an `exact`, `contains`, or
`schema` expectation. Assistant-message steps are explicitly pre-authored trace
content.

Trace JSON files live under:

```text
data/<namespace>/agent_traces/<trace-id>.json
```

Schema version 1 is strict. Unknown fields, duplicate JSON keys, unsupported
versions, malformed tool IDs, and a mismatch between filename and declared
trace ID are rejected. Resources are discovered from the active server resource
manager each time, so a normal data-pack reload updates the available traces.

The trace declares which context capabilities it needs: `registries`,
`recipes`, and/or `player`. Capture occurs on the Minecraft server thread and
immediately detaches game objects into immutable records before tools run.
Console replay of a player-required trace returns `player_required`.

OpenAllay currently imposes no project-defined size, item-count, recipe-count,
inventory-count, string-length, trace-step, or report-length limit. Reports
preserve complete requested data and only observe registry/recipe/inventory
counts, estimated serialized bytes, capture time, tool-result bytes, and replay
time. Limits will only be introduced after real model operation provides
evidence for them.

Phase 2 keeps the model transport in the Minecraft JVM and uses only JDK HTTP;
there is no Node/Python sidecar, MCP bridge, LangChain-style framework, shell
tool, or sandbox.

### Headless replay smoke test

After accepting the Minecraft EULA in each ignored server run directory, start
dedicated servers only:

```bash
./gradlew-curl :fabric:runServer --args nogui
./gradlew-curl :neoforge:runServer
```

At each server console, run:

```text
openallay dev replay platform-info
openallay dev replay iron-ingot-recipe
openallay dev replay player-context
stop
```

`platform-info`, `iron-ingot-recipe`, and `find-recipes-compatibility` can run
from the dedicated-server console. `iron-block-craftability` and
`player-context` fail explicitly with `player_required` there because no player
owns the invocation.

## Phase 3A verification baseline

On 2026-07-17 the complete common suite reported 100 tests, 0 failures, 0
errors, and 1 opt-in live-provider test skipped. Both production artifacts built
successfully:

```text
fabric/build/libs/openallay-fabric-26.2-0.1.0-SNAPSHOT.jar
neoforge/build/libs/openallay-neoforge-26.2-0.1.0-SNAPSHOT.jar
```

Both JARs contain the five new grounded workflow tools,
`EvidenceMetadata`, and `CraftabilityCalculator`. Repository and artifact
credential-pattern scans returned no matches. Phase 3B command E2E,
GuideService, and Phase 3C GUI are not included in this baseline.

## Phase 3B verification baseline

On 2026-07-17 the complete common suite reported 119 tests, 0 failures, 0
errors, and 1 opt-in live-provider test skipped. Fabric and NeoForge production
builds both passed and contain `GuideService`, `GuideStateReducer`, the strict
server event codec, and the gated real-client controller. Tracked-file and JAR
credential-pattern scans returned no matches. The deterministic HTTP/SSE model
fixture answered a direct contract request successfully. No graphical client
was launched during this unattended run, so no real-client report or visual
gameplay acceptance is claimed by this baseline.

## Phase 3 final verification baseline

The final clean build on 2026-07-17 reported 125 common tests, 0 failures, 0
errors, and 1 opt-in live-provider test skipped, followed by successful Fabric
and NeoForge production builds. Required GUI/service classes and language assets
are present in both artifacts. Tracked files, all Git objects (including the
unreachable-object set reported by `git fsck`), and every built JAR returned no
credential-pattern matches. Script syntax and the deterministic fixture parser
also passed.

```text
Fabric  SHA-256 157dcf0fd50bc4b85fa41ee24d363f42b8c7e8d8f24bb8b79a7fddb13cf55f56
NeoForge SHA-256 2d6d31bef6a8f023ee4f95399ba8e6ab6bd89333c7cb4276c77b99c2d2b7f347
```

This baseline does not include a graphical client launch, screenshot, or
manual interaction claim.

## Loader boundary

Production source under `common/` must not import Fabric or NeoForge APIs. Loader
entrypoints, lifecycle hooks, and command registration remain in their respective
loader modules. A unit test enforces this boundary.
