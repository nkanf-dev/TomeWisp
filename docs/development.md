# Development

TomeWisp's main development line targets Minecraft 26.2 and requires Java 25.
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
TOMEWISP_CURL_PROXY=socks5h://127.0.0.1:7890 ./gradlew-curl build
```

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

## Client model configuration

The main mode is pure client-side. Put configuration at
`config/tomewisp/model.json` in the client game directory. Keep the credential
in an environment variable:

```json
{
  "enabled": true,
  "protocol": "anthropic_messages",
  "baseUrl": "https://provider.example/v1/",
  "model": "model-id",
  "apiKeyEnv": "TOMEWISP_API_KEY",
  "maxOutputTokens": 8192,
  "connectTimeoutSeconds": 30,
  "requestTimeoutSeconds": 300
}
```

`openai_chat` is the second protocol. Remote endpoints require HTTPS; HTTP is
accepted only for loopback development. The API key is redacted from model
configuration diagnostics and live traces.

For an optional server-hosted model, use
`config/tomewisp/server-model.json` on the server. The capability is advertised
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
request in the same session returns `agent_busy`. TomeWisp applies no fixed
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
The server Agent event protocol is version 2 and is decoded once in common
code. Unknown or malformed events fail only their correlated request; there is
no silent client/server fallback.

The real-client probe is disabled unless `tomewisp.e2e.enabled=true`. When
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

Connect the launched client to a disposable world or test server. The fixture
requests the grounded iron-block chain: recipe search, exact recipe, inventory,
then deterministic craftability. The script is intentionally opt-in because it
opens a graphical client. CI validates the controller, both loader hooks, shell
syntax and fixture syntax, but does not claim a real-client run. A release may
claim that coverage only when the generated report is retained and reviewed.

## Player GUI

The configurable `key.tomewisp.open_guide` mapping defaults to `K`; bare
`/guide` uses the same opener on Fabric and NeoForge. The native full-screen
Screen does not pause the world. Escape and opening another Screen only detach
the UI subscription, so active work continues and reopening reconstructs from
the latest immutable GuideSnapshot.

The screen provides a responsive session rail/overlay, virtualized wrapped
transcript, multiline composer (`Ctrl+Enter` sends), stop/retry controls, and an
explicit local/server model selector. Only model text deltas are visible;
reasoning is absent from the UI view type. Assistant segments and tool cards
render in actual Agent event order, and a running card updates in place by its
tool invocation ID before later assistant text appears. Grounded recipe,
inventory and craftability tools receive first-class summaries, while other
tools use a deterministic normalized-result fallback. Clicking a tool or source
opens an in-game evidence detail panel; no browser is launched. Session switches
and disconnect cleanup remove stale detail state.

If the selected model is unavailable, the screen still opens and shows the
configuration/capability state. Client configuration remains at
`config/tomewisp/model.json`; credentials are never displayed. Model-mode
changes affect future requests only and never trigger silent fallback.

## Grounded built-in tools

Every factual success carries immutable evidence: authority, completeness,
capture time, source, provenance, game version, loader, and optional scoped
details. Client recipe-display facts are `CLIENT_VISIBLE`; server RecipeManager
facts are `SERVER_AUTHORITATIVE`. An unloaded knowledge snapshot is `UNKNOWN`,
not proof that no documents exist. The result normalizer rejects any
`EvidenceBearing` success whose evidence list is empty.

The Phase 3A recipe workflow is:

```text
tomewisp:resolve_resource
tomewisp:search_recipes
tomewisp:get_recipe
tomewisp:find_item_usages
tomewisp:inspect_inventory
tomewisp:calculate_craftability
```

`calculate_craftability` uses deterministic global capacity allocation, so
overlapping item/tag alternatives are not assigned greedily. It reports the
observed allocation, missing requirements, maximum crafts, and whether the
evidence is conclusive. It does not recursively craft intermediate items.
Incomplete recipe or inventory evidence may show an observed positive result,
but `conclusive` remains false. `tomewisp:find_recipes` is retained only as a
deprecated compatibility projection over the same recipe catalog.

## Knowledge and Skills

Patchouli is read directly from active client resource packs, without a binary
dependency. Locale precedence is active locale, `zh_cn`, then `en_us`.
Config/advancement-gated entries whose visibility cannot be proven are excluded.
Text, item/recipe links, and embedded dense or sparse multiblocks are indexed.

FTB Quests is optional. Its public API is resolved through allowlisted public
method handles; private reflection is never used. Only chapters and quests the
API reports visible for the current team enter the snapshot. On 26.2, where no
compatible FTB Quests release is currently available, the source reports an
explicit integration diagnostic and the rest of the Agent continues normally.

Bundled Skills use metadata-first progressive disclosure. The runtime supports
declared read-only references, but does not execute `scripts/`, fetch URLs, read
arbitrary paths, or let Skills register tools or permissions.

## Live provider acceptance

The normal test suite skips real network calls. To verify streaming, a real
tool call, tool-result continuation, grounded Chinese output, and secret
redaction, export credentials only in the shell environment and run:

```bash
TOMEWISP_MODEL_BASE_URL=https://provider.example/v1/ \
TOMEWISP_MODEL=model-id \
TOMEWISP_API_KEY=... \
TOMEWISP_MODEL_PROTOCOL=ANTHROPIC_MESSAGES \
./scripts/live-model-smoke.sh
```

Never commit a model JSON containing `apiKey`.

## Development commands

The following commands require game-master permission:

```text
/tomewisp dev tools
/tomewisp dev invoke tomewisp:platform_info
/tomewisp dev replay platform-info
/tomewisp dev replay iron-ingot-recipe
/tomewisp dev replay iron-block-craftability
/tomewisp dev replay find-recipes-compatibility
/tomewisp dev replay player-context
```

The initial development tool surface is intentionally read-only. It does not
provide shell execution, arbitrary code execution, server-command execution,
file-system access, or world mutation.

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

TomeWisp currently imposes no project-defined size, item-count, recipe-count,
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
tomewisp dev replay platform-info
tomewisp dev replay iron-ingot-recipe
tomewisp dev replay player-context
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
fabric/build/libs/tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar
neoforge/build/libs/tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar
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
