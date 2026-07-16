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

## Development commands

The following commands require game-master permission:

```text
/tomewisp dev tools
/tomewisp dev invoke tomewisp:platform_info
/tomewisp dev replay platform-info
/tomewisp dev replay iron-ingot-recipe
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

There is no model SDK, endpoint, credential, or model network request in this
phase.

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

The first two traces pass. The player trace fails explicitly with
`player_required` from a dedicated-server console.

## Loader boundary

Production source under `common/` must not import Fabric or NeoForge APIs. Loader
entrypoints, lifecycle hooks, and command registration remain in their respective
loader modules. A unit test enforces this boundary.
