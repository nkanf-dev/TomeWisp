# Phase 2 Client Agent and Knowledge Integration Design

## 1. Objective

Phase 2 turns TomeWisp's deterministic Phase 1 tool and trace foundation into a
real, client-first Minecraft guide Agent. A player can install TomeWisp only on
their client, ask a question with `/guide`, let a configured model inspect live
client-visible facts through typed tools and progressively loaded Skills, and
receive a grounded answer. Installing TomeWisp on the server is optional and
adds player-scoped server tools or server-funded model inference.

The phase includes the complete vertical slice rather than a model demo:

- real streaming model transport and multi-round tool use;
- one lightweight general Agent loop;
- client context capture and local tool execution;
- standard-inspired Agent Skills with progressive loading and resource reload;
- a generic knowledge-source SPI and deterministic local search;
- Patchouli book, entry, page, item, recipe, and multiblock extraction;
- optional FTB Quests visible-task, dependency, and progress extraction;
- an optional client/server read-tool bridge;
- optional server-hosted inference;
- player commands, cancellation, short-term sessions, traces, diagnostics, and
  dual-loader verification.

## 2. Product Decisions

| Area | Decision |
| --- | --- |
| Primary topology | Client-local; no TomeWisp server required |
| Optional topology | Client model with server tools, or server-hosted model |
| Agent architecture | One `GameGuideAgent`; no sub-Agents |
| JVM approach | JDK `HttpClient`, protocol adapters, no LangChain/MCP/sandbox |
| First verified protocol | Anthropic Messages at `/v1/messages` |
| Secondary protocol | OpenAI Chat Completions for compatible providers |
| Skills | Progressive metadata then `load_skill`; no scripts or arbitrary URLs |
| Tool authority | ToolRegistry and server permission checks, never prompts |
| State | In-memory sessions; one active request per player/client |
| Writes | Diagnostic traces only; no world, inventory, quest, or command writes |
| Limits | No fixed TomeWisp document/result/round cap; provider-required output and operational timeout values are explicit configuration and are not silently clamped |
| Visual tutorials | Deferred to Phase 3; Phase 2 may discover structures but does not publish Ponder |

## 3. Evidence Behind the Model Design

The supplied gateway exposes `mimo-v2.5-pro`. Live probes on 2026-07-17 proved:

1. `GET /v1/models` lists the model.
2. OpenAI Chat Completions returns normal text, but the gateway's route does not
   pass function tools through to this model reliably.
3. Anthropic Messages returns a real `tool_use` block.
4. Sending a matching `tool_result` returns the grounded final answer.

Xiaomi's official MiMo V2.5 documentation declares streaming, function calling,
structured output, and a 1M context window for `mimo-v2.5-pro`. The Agent core
therefore depends on a protocol-neutral `ModelClient`, not on an OpenAI-specific
SDK. The first production adapter is Anthropic Messages; the OpenAI adapter is
kept for other compatible endpoints and tested with a mock server.

## 4. Current Mod Compatibility Reality

Official project state checked on 2026-07-17:

- Patchouli publishes through Minecraft 26.1.2 and has a `26.1` branch, but no
  26.2 release/branch.
- Ponder has a `mc26.1/dev` branch, but no 26.2 branch.
- Ponderer publishes 1.20.1 and 1.21.1, not 26.2.
- FTB Quests has maintained 1.21.11 lines but no confirmed 26.2 artifact.

TomeWisp must not add unavailable 26.2 hard dependencies. Patchouli content is
data-driven client content and can be extracted from loaded client resources
without linking Patchouli classes. FTB Quests needs synchronized player state,
so its adapter is optional and reflective behind a narrow compatibility
contract. Fixture tests mirror official API shapes, and runtime validation
disables mismatched mappings. This is a real optional integration, not a claim
that the unavailable upstream mod can run on 26.2 today.

## 5. Runtime Topologies

### 5.1 Client-local default

The client captures immutable snapshots from `Minecraft`, the local player,
client registries, the connection recipe manager, loaded client assets, and any
compatible client guide APIs. The Agent loop and model transport run in the
client process. Answers are rendered in client chat. No custom packet is sent,
and the server need not know TomeWisp exists.

### 5.2 Client model with server enhancement

If both sides have TomeWisp, a login capability handshake advertises remote
read-only tool descriptors. The client's model and Agent remain local. A remote
tool call contains a correlation ID, the public tool ID, and typed JSON
arguments. The server derives player identity from the network connection,
captures only the tool's declared context, invokes the registered read tool,
and returns a normalized result. The client cannot submit a UUID, permission
level, command, class name, or arbitrary method.

### 5.3 Server-hosted model

A server operator may configure a model credential. The client sends the player
question and receives progress/final events. The server owns the Agent loop and
server tools. The client's model credential is never sent. A server without
model configuration does not advertise this capability.

Model location and remote-tool availability are orthogonal. The UI exposes
`client` and `server` model modes; server mode is selectable only after a
successful capability handshake.

## 6. Module Boundaries

### 6.1 Agent core

`agent/` owns protocol-neutral messages, model events, the sequential loop,
session state, cancellation, and live traces. It imports no Minecraft client,
Fabric, NeoForge, FTB, or Patchouli class.

### 6.2 Model transport

`model/` owns configuration, secret resolution, HTTP requests, SSE parsing, and
Anthropic/OpenAI adapters. `ModelClient.complete(request, listener,
cancellation)` is the only interface the Agent needs. Provider-specific JSON
never enters tools or Skills.

### 6.3 Client runtime

`client/` captures client-visible context on the client thread, registers local
commands, publishes answer/progress events to chat, reloads assets and Skills,
and coordinates optional server capabilities. It never blocks the render/client
thread on HTTP.

### 6.4 Server runtime

`server/` retains Phase 1 commands and context capture, registers the optional
remote-tool bridge, and may host an Agent when configured. Dedicated-server
startup remains valid with no model configuration.

### 6.5 Knowledge and Skills

`knowledge/` owns normalized documents, source diagnostics, immutable snapshots,
and search. `skill/` owns discovery, metadata, content loading, references, and
the `load_skill` tool. Neither subsystem grants new permissions.

Loader modules contain only lifecycle, command, resource reload, networking,
disconnect, and client/server entrypoint adapters.

## 7. Model Configuration and Secrets

Client configuration lives at `config/tomewisp/model.json`; server configuration
uses the same schema in its config directory. Fields are:

- `enabled`;
- `mode` (`client` or `server` on clients);
- `protocol` (`anthropic_messages` or `openai_chat`);
- `baseUrl`;
- `model`;
- `apiKeyEnv`;
- optional local-only `apiKey`;
- positive provider-required `maxOutputTokens`;
- connect and request timeouts.

Environment variables override file values. The recommended path is
`TOMEWISP_API_KEY`; plaintext `apiKey` exists for ordinary launcher users but is
never printed, serialized into traces, sent over the game network, or returned
by diagnostics. HTTPS is required except for loopback hosts, allowing trusted
local models. Config reload replaces an immutable validated snapshot atomically.

The supplied credential is used only in ignored local test configuration or an
ephemeral environment variable and is never committed.

## 8. Model Protocol

The normalized request contains system instructions, chronological messages,
tool definitions, model ID, and provider options. Normalized response events are:

- text delta;
- reasoning delta (trace-only, never shown as authoritative answer);
- tool-use start/input delta/complete;
- usage update;
- message complete;
- structured provider failure.

The Anthropic adapter supports JSON and SSE responses, `tool_use`, `tool_result`,
thinking blocks, usage, and cancellation. Tool history preserves provider
content required for multi-turn compatibility. The OpenAI adapter supports
`tool_calls`, tool-role results, JSON and SSE, but capability diagnostics mark a
provider unusable for Agent mode if its live probe never emits tool calls.

Transport retries occur only for connection/TLS failures proven to happen before
an HTTP response. HTTP errors, interrupted response streams, and ambiguous POST
completion are not automatically replayed, preventing accidental duplicate
billing. Retry behavior and delay are observable.

## 9. Tool Exposure and Schema Generation

Tool IDs remain namespaced Java IDs such as `tomewisp:find_recipes`. Provider
protocol names use a reversible safe mapping such as
`tomewisp__find_recipes`, because function-name grammars do not allow colons.

`ToolSchemaGenerator` converts record inputs into the supported JSON Schema
subset: records, strings, numbers, integers, booleans, enums, optionals, lists,
maps with string keys, and nested records. Unknown Java shapes fail registration
rather than becoming unconstrained objects. JSON arguments are decoded through
the existing audited tool boundary.

Descriptors declare required context capabilities. Client/server coordinators
capture the union needed by exposed tools once per request into detached DTOs.
Tool results are normalized with the existing canonicalizer and returned in
full. The Agent has no arbitrary Java reflection, shell, command, filesystem, or
world-write tool.

An exact repeat of the same tool ID and canonical arguments without intervening
new information fails as `repeated_tool_call`. No fixed Agent round count is
introduced in Phase 2; explicit cancellation and the repeat invariant provide
termination without inventing an evidence-free round limit.

## 10. Agent Loop and Context Engineering

`GameGuideAgent` is a sequential state machine:

1. reserve the player's session;
2. snapshot caller, available tools, Skill metadata, knowledge diagnostics, and
   only the Minecraft capabilities required by exposed tools;
3. construct the system context from stable policy sections;
4. send the question and tools to the configured model;
5. execute each returned tool call through the selected local/remote executor;
6. append complete tool results and continue;
7. deliver the final answer with provenance labels;
8. close the session and trace.

The system context has separately testable sections:

- identity and product scope;
- authority ordering for dynamic facts;
- no-fabrication and unavailable-capability policy;
- tool-use instructions;
- available Skill metadata only;
- loaded Skill bodies and references;
- caller-visible environment summary;
- answer format and provenance requirements.

Large registries, recipes, books, quests, and inventories are not copied into
the prompt. They remain behind tools. Tool results preserve complete requested
data. Search tools support an optional caller-requested result count but default
to all matches; TomeWisp does not silently truncate.

## 11. Skills Runtime

TomeWisp implements the safe subset of the open Agent Skills directory model.
External authoring directories use:

```text
<skill-name>/
├── SKILL.md
├── references/
└── assets/
```

Minecraft asset packs use lowercase `skill.md` at
`assets/<namespace>/tomewisp_skills/<name>/skill.md` because resource identifiers
are lowercase. They have identical frontmatter and body semantics. Optional
references live below the same directory. Scripts are ignored and diagnosed;
they are never executed.

Validated metadata includes name, description, compatibility, required mods,
allowed tool IDs, and explicit references. Startup/resource reload builds a new
immutable index and atomically swaps it. Only name and description enter the
initial system context. `tomewisp:load_skill` returns the complete body and
explicit references. A Skill cannot register a tool or widen tool access.

Bundled Phase 2 Skills are:

- `answer-modded-minecraft-question`;
- `explain-machine-usage`;
- `diagnose-missing-recipe`;
- `guide-ftb-progression`;
- `search-guide-books`.

## 12. Knowledge Source SPI and Search

`KnowledgeSourceProvider` produces an immutable snapshot of normalized
`KnowledgeDocument` records. Each document has a stable source/document ID,
kind, title, full text, namespace, associated item/recipe IDs, optional structure
reference, visibility, and provenance. Providers publish diagnostics even when
unavailable.

The registry combines client assets, optional APIs, built-in docs, and server
enhancement results without exposing provider object models. Conflict ordering
is:

1. current runtime/player state;
2. synchronized server data and recipes;
3. loaded Patchouli/quest/guide resources;
4. modpack-authored Skills and references;
5. model training knowledge for explanation only.

The local index uses deterministic tokenization, exact resource-ID matching,
title/body term frequency, associated-item boosts, namespace boosts, and stable
tie-breaking. It does not use embeddings. Tools expose source listing, document
lookup, keyword search, and diagnostics, always with provenance.

## 13. Patchouli Adapter

Patchouli's official 26.1 resources place localized books under
`assets/<namespace>/patchouli_books/<book>/<locale>/`. The client adapter scans
the active client resource manager and loaded pack stack for:

- book definitions and localized metadata;
- categories;
- entries and page arrays;
- text, spotlight items, recipe IDs, links, and relations;
- dense/sparse multiblock page definitions and referenced structures.

The parser handles `zh_cn`, the active client locale, and `en_us` fallback in
that order while retaining provenance for each selected resource. Patchouli
formatting commands are converted to searchable plain text without executing
commands. Config-flag, advancement, and secret/locked content is included only
when the installed Patchouli API can prove it visible to the current player;
otherwise it is marked unavailable rather than leaked.

If Patchouli is installed and its public API shape validates, a narrow optional
bridge may open a book/entry and retrieve registered multiblock details. The
asset parser remains useful when Patchouli is absent but a pack contains books.

## 14. FTB Quests Adapter

The FTB adapter activates only when the loader reports FTB Quests and the
expected public classes/methods validate. It reads the client-synchronized quest
file and team/player data to normalize:

- chapters and visible quests;
- titles/descriptions;
- prerequisite/dependency IDs;
- completion and claimed state;
- task/reward summaries that are already visible;
- the next executable visible quests.

Identity always comes from the local player or server network sender. Hidden
quests and undisclosed descriptions are filtered before normalization. Method
handles are resolved once per compatibility mapping; there is no arbitrary
method chaining, accessibility bypass, field write, or expression evaluator.

Because no confirmed FTB Quests 26.2 artifact exists, acceptance requires a
fixture-backed compatibility contract and a real `integration_unavailable`
diagnostic on 26.2, not a successful live claim. When an upstream 26.2 artifact
appears, adding its mapping must not change Agent, Skill, or tool APIs.

## 15. Client/Server Read-Tool Bridge

The handshake advertises protocol version, server-hosted-model availability,
and remote tool descriptors. Payloads are versioned and bounded only by
Minecraft/network hard constraints; complete logical results can be fragmented
and reassembled with correlation IDs and hashes.

For every remote invocation the server:

1. derives the player from the connection;
2. verifies protocol version and correlation ownership;
3. looks up an explicitly exported `READ_ONLY` tool;
4. validates arguments against its descriptor;
5. captures declared context on the server thread;
6. invokes the tool and normalizes the result;
7. returns success/failure chunks to that player only.

Disconnect cancels outstanding calls. Unknown, duplicate, late, mismatched, and
cross-player correlations fail closed. No credential or complete session history
is required for client-model remote tools.

## 16. Player Experience

Client commands are:

```text
/guide <question>
/guide cancel
/guide clear
/guide status
/guide model client
/guide model server
/guide skills
/guide sources
```

The first command starts asynchronously. Chat shows concise state changes such
as preparing, calling a named tool, and waiting for the model. Text deltas update
client-side progress without blocking the game thread; the final complete answer
is posted once with source labels. Errors include a stable code and practical
operator/player action.

Existing operator-only `/tomewisp dev` commands gain model probe, Agent trace,
Skill reload/validation, knowledge diagnostics, and bridge diagnostics. They do
not reveal credentials.

## 17. Sessions, Cancellation, and Traces

Sessions keep chronological model messages, tool summaries, loaded Skills, and
the latest answer in memory. They are keyed by the local client profile or
server player UUID and are cleared on disconnect, explicit clear, and shutdown.
They are not permanent memory and never override live game facts.

One active request is allowed per key. New work while active returns
`agent_busy`. Cancellation is cooperative across HTTP, SSE, local tools, and
remote correlations. Late callbacks check request identity before publishing.

Every request produces a `LiveAgentTrace` containing request ID, mode, model
metadata, state transitions, normalized messages, tool arguments/results,
Skills loaded, usage, timings, retry diagnostics, final status, and redacted
errors. Credentials and authorization headers are structurally absent. The most
recent traces are inspectable in memory. Optional development persistence writes
canonical JSON below the configured TomeWisp trace directory; it is disabled by
default and has no retention deletion policy in Phase 2.

## 18. Error Model

Stable errors include:

- `model_not_configured`;
- `model_protocol_error`;
- `model_transport_error`;
- `model_http_error`;
- `agent_busy`;
- `agent_cancelled`;
- `repeated_tool_call`;
- `unknown_tool`;
- `invalid_arguments`;
- `tool_failure`;
- `capability_unavailable`;
- `integration_unavailable`;
- `remote_protocol_mismatch`;
- `remote_permission_denied`;
- `remote_disconnected`.

No failure silently switches model location, invokes a different tool, exposes
hidden content, or fabricates a text answer. Text-only fallback means answering
from facts already obtained, never claiming an unavailable fact.

## 19. Testing Strategy

### 19.1 Pure JVM tests

- Anthropic/OpenAI JSON and SSE fixtures, including split UTF-8/event chunks;
- tool-call history and tool-result continuation;
- schema generation and safe tool-name mapping;
- Agent state transitions, busy/cancel/late-event behavior, repeat detection;
- prompt sections, Skill progressive loading, and credential redaction;
- knowledge ranking, provenance, locale fallback, Patchouli pages/multiblocks;
- FTB compatibility mapping validation and hidden-task filtering;
- trace canonicalization and complete text/result preservation.

### 19.2 Mock HTTP integration

A local JDK HTTP server replays realistic Anthropic traces with text, tool use,
tool result, streaming, HTTP errors, TLS/connect-like failures, cancellation,
and malformed events. No CI secret is required.

### 19.3 Minecraft and loader tests

- common architecture tests keep loader/client/server boundaries explicit;
- Fabric and NeoForge client compilation and client entrypoint registration;
- dedicated servers start with no model configuration;
- optional handshake and remote tool payload codecs round-trip on both loaders;
- headless server console runs existing Phase 1 replay commands;
- a client-context harness verifies snapshots without launching a GUI;
- JAR inspection verifies client entrypoints, Skills, Patchouli fixtures, and no
  bundled secret/config.

### 19.4 Live provider acceptance

Using an environment-only credential, the supplied gateway must pass:

1. model listing;
2. plain response;
3. Anthropic tool use;
4. real TomeWisp tool result continuation;
5. final Chinese answer;
6. streaming event parsing;
7. credential-redaction inspection.

Live tests are opt-in and never run in public CI.

## 20. Completion Gate

Phase 2 is complete only when all of the following are proven:

- Fabric and NeoForge clients register the same `/guide` surface.
- Client-local Agent operation has no server-mod prerequisite.
- The real supplied model completes at least one TomeWisp tool round through the
  verified Anthropic protocol and returns a grounded Chinese answer.
- Skills are discovered, progressively loaded, reloadable, and unable to widen
  permissions.
- Generic knowledge search returns stable ranked provenance.
- Patchouli fixtures produce books, entries, pages, recipes/items, and
  multiblocks with locale fallback.
- FTB adapter mappings validate against fixtures and fail explicitly on the
  current 26.2 runtime where upstream is unavailable.
- Optional remote tools derive player identity server-side and reject invalid
  correlations/permissions.
- Optional server-hosted-model capability is advertised only when configured.
- Busy, cancel, disconnect, late callback, model failure, tool failure, and
  unavailable integration semantics match the SKMB.
- No API key appears in Git, built JARs, logs, traces, packets, or test reports.
- All unit/integration tests, dual-loader builds, dedicated-server smoke tests,
  JAR inspection, and GitHub Actions pass.

## 21. Explicit Non-Goals

- generating, publishing, syncing, or opening new Ponder scenes;
- embedding/vector databases before keyword evaluation proves a need;
- long-term or cross-server player memory;
- arbitrary scripts, shell, commands, world writes, quest mutation, item grants,
  or file access exposed to the model;
- claiming binary compatibility with an upstream mod that has no 26.2 release.
