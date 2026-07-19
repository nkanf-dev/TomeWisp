# Phase 4 Observability and Observable Game-State Design

## Status

- Product phase: Phase 4, not a new product phase
- Design status: approved by the designer on 2026-07-19
- Decision basis: SKMB-2026-07-19-020
- Target: Minecraft 26.2, Java 25, Fabric and NeoForge

## Implementation and acceptance status

The design is implemented in the current Phase 4 worktree. Focused
deterministic coverage has passed for request progress and total-response
timeout, native input/layout/semantic streaming, Tool cards and schemas, prompt
guidance, and the unified observable game-state Tool. The Fabric and NeoForge
real-client controllers each completed the native semantic/UI correction
scenario and produced six native screenshots; each report records all eight
controlled component types and 32 semantic blocks. Both loaders also completed
the real-client eight-section game-state scenario. Final Phase
4 acceptance remains open until the latest clean common/Fabric/NeoForge
production gate and the final credential, diff, report, hash, and
evidence-manifest audit pass.

## Context

The corrected normal Fabric client can accept text and persist history, but
manual use exposed three remaining product gaps: model waits are not
observable and may outlive the intended stream deadline, streaming layout is
unstable, and the Agent cannot inspect the broad outer state already visible
to the player through Minecraft's UI and diagnostic surfaces.

This design treats the latter as a layer boundary, not a checklist of the
examples reported during testing.

1. **Player-observable outer state** is direct UI, menu, HUD/F3, player-owned
   UI, and authorized read-only query state. One sectioned Tool owns this.
2. **Deep content** is narrow but high-volume knowledge such as Recipes and
   Guides. These retain independent Tool families and sources.
3. **Interactive/spatial world content** is maps, nearby blocks, structures,
   external containers, and other facts requiring world interaction or scan.
   It is deferred to a later design.

## Goals

- Make every active request visibly explain what it is waiting for, elapsed
  time, most recent progress, and retry/deadline state.
- Make the configured request timeout cover the complete streaming response.
- Eliminate transcript jitter and fix Markdown list geometry without weakening
  semantic safety or long-history virtualization.
- Use Enter to send and Shift+Enter for multiline input.
- Give collapsed Tool cards enough friendly context to understand what was
  queried and what happened.
- Align natural resource resolution, recipe search schemas, system prompt and
  runtime validation so the Agent stops making structurally invalid calls.
- Expose all player-observable outer game state through one strict,
  progressively disclosed `inspect_game_state` Tool plus a bundled Skill.
- Preserve common-core ownership, read-only security, evidence semantics, and
  Fabric/NeoForge parity.

## Non-goals

- No map, nearby-block, structure, entity-neighborhood, external-container, or
  world-volume scan.
- No arbitrary Minecraft command string and no command parser delegation.
- No write operation, approval card, world/inventory mutation, or future write
  permission hidden inside the read-only Tool.
- No merging of Recipes or Guides into the outer-state Tool.
- No unrestricted reflection over Minecraft or optional-mod internals.
- No new product-wide result, history, option, or Tool-call count cap.

## 1. Request Progress and Transport Completion

`GuideRequestSnapshot` gains a small immutable progress record. It contains a
closed phase enum, request/phase/progress instants, attempt number, and optional
retry/deadline instants. It contains no player text or provider detail.
Lifecycle events update it at meaningful boundaries rather than for every
clock tick.

The UI uses one fixed-height status strip immediately above the composer. The
screen derives elapsed and relative time locally, for example:

- `等待模型响应 · 已用 1:42 · 最多还剩 3:18`
- `正在接收回复 · 最近更新 4 秒前`
- `模型限流 · 28 秒后第 2 次尝试`
- `正在读取游戏设置 · 3 秒`

The strip does not become a transcript row, so changing time text cannot move
history. Normal mode uses friendly labels. Debug Mode may append a stable code
and attempt count, never an endpoint, request body, or exception.

The HTTP adapter starts a scheduled watchdog when an attempt is dispatched and
cancels it only after the response decoder finishes or the attempt terminates.
Timeout closes the input stream, completes the decoder exceptionally as the
stable `model_timeout`, cancels scheduled work, and releases the endpoint
scheduler slot. Existing request identity/generation checks discard late data.
When progress crosses a dedicated-server bridge, the protocol carries the
relative attempt budget. The client creates its display-only deadline from its
own receipt clock; server/client clock skew therefore cannot alter the
server-owned watchdog or produce an invalid countdown.

## 2. Stable Streaming, Lists, Scroll, and Input

GuideService remains free to publish every semantic event. The screen stores
the newest immutable view and applies no more than one projection update during
one client tick. The render method never reprojects rows or changes scroll.

Completed semantic blocks keep their cached measured layout. The mutable
streaming tail is rendered as literal wrapped text until a safe block boundary
is validated, preventing a half-written list/table/fence from repeatedly
changing node type and height. When the user is following the bottom, the
single tick application advances once; when the user has scrolled away, the
same anchor row and pixel offset remain stable.

The layout engine emits a list marker and the first content line as one visual
line. It measures the actual marker width and uses that width for all wrapped
continuation lines. Nested items compose their indent, and only the first line
of the first paragraph receives the marker.

Composer keyboard behavior is:

- Enter: submit nonblank content;
- Shift+Enter: insert newline;
- Ctrl+Enter: submit compatibility shortcut;
- Enter outside the composer: preserve the focused widget/card action.

IME composition, keypad Enter, paste, Tab traversal and narration receive
deterministic coverage where the Minecraft test surface permits it.

## 3. Friendly Tool Activity

Tool activity stores two safe typed projections:

- an invocation projection created by a registered per-Tool projector from an
  allowlist of non-sensitive fields;
- a result projection created from normalized output and evidence.

The event/history contract persists these projections, not raw arguments.
Collapsed cards display the action/object, running/success/failure state and up
to three concise result lines such as match counts, source coverage, selected
section, or the next safe step. Expanding a card opens its existing typed
detail presentation. Unknown Tools retain a deterministic friendly fallback;
only Debug Mode may show already-redacted normalized JSON.

## 4. Tool Contracts and Agent Guidance

`resolve_resource` becomes a real natural-name resolver. Its `query` accepts a
full namespaced ID, localized display text, or ID path tokens. Matching is
deterministic: exact ID, exact localized name/path, token-prefix/substring, then
stable lexical order. It returns all deterministic matches rather than making
an arbitrary choice. Result handles contain the exact IDs required by recipe
Tools.

`search_recipes` keeps exact-ID fields. Its generated schema describes each
field, constrains namespaced IDs, and enforces at least one criterion in both
schema and Java validation. The generic schema generator learns only the
closed metadata needed by registered records: descriptions, enums, patterns,
required fields and an explicit at-least-one field group.

One shared `AgentSystemPrompt` is used by client and server model paths. It
states universal rules rather than embedding domain manuals:

1. load the most relevant Skill before a multi-step domain workflow;
2. resolve natural names before using exact-ID-only fields;
3. carry returned handles unchanged;
4. make at most one corrected call after invalid arguments;
5. treat partial/unknown evidence honestly;
6. stop after an unchanged empty/partial result and explain what is missing.

Domain-specific section choice belongs to Skills.

## 5. Unified Observable Game-State Capability

### 5.1 Public Tool shape

The Agent sees exactly one new Tool:

```json
{
  "name": "openallay:inspect_game_state",
  "input": {
    "section": "mods | options | diagnostics | packs | player | world_query | ...",
    "query": "optional section-specific selector"
  }
}
```

The literal enum is generated from a registered common section catalog. The
catalog is extensible in code, but the model cannot invent a section, field
path, class name or operation. `query` is parsed by the selected section's
strict codec; it is not a generic expression language.

The Tool result has a common envelope:

```text
section, capturedAt, authority, completeness, source, provenance,
gameVersion, loader, typed data, section diagnostics
```

Large domains support summary/list/detail queries and stable continuation or
exact identifiers where necessary. There is no implicit full dump and no
arbitrary cap; the caller requests the next exact view when the result shape
requires it.

### 5.2 Player-observable section catalog

The initial catalog is comprehensive by observation class rather than a fixed
list of examples:

- **overview/runtime**: Minecraft/version/loader, development state, current
  connection and runtime capabilities safe for player display;
- **mods**: complete installed-mod index with only ID, name, version, and
  environment, followed by exact-ID public metadata such as description,
  authors, license/contact links represented as inert text, and dependency
  declarations where loader APIs expose them. The index does not embed every
  detail record;
- **options**: all values represented in Minecraft's option screens, grouped
  by the native domains. It includes video, sound, music, language, chat,
  controls/key mappings, mouse, accessibility, telemetry/privacy choices,
  realms/online and other future UI option groups when exposed by verified
  APIs. Sensitive account tokens and raw secrets are unrepresentable;
- **packs**: selected/available resource packs and directly visible data-pack
  state, with compatibility and source metadata;
- **shaders**: selected shader pack and public shader configuration when a
  compatible public integration exists; otherwise explicit unavailable state;
- **diagnostics**: F3/HUD-visible client, renderer, chunk, memory, target,
  dimension, coordinates, direction, biome and network/performance data that
  is already directly observable and safe to detach;
- **player**: the player's own UI-visible state, including inventory and
  status, never contents of another/external container;
- **world_query**: a closed operation registry for non-mutating query
  equivalents such as time, weather, difficulty, spawn/world-border or other
  facts the current topology can authoritatively expose without running a raw
  command. Permission and authority are rechecked per operation.

The categories can add new typed handlers as Minecraft adds observable UI or
diagnostic surfaces without adding Agent Tool IDs.

### 5.3 Capture and loader boundaries

`ContextCapability` gains an observable-state capability.
`ClientContextCapture` asks registered capturers for detached section records
on the Minecraft client thread. The immutable snapshot is stored in
`ToolInvocationContext`; asynchronous Tool code sees no `Minecraft`, `Player`,
`Level`, `Options`, renderer, pack repository, connection, registry, or mod
loader object.

Common records define loader-neutral mod metadata. Fabric and NeoForge
`PlatformService` adapters populate them through their verified public APIs.
Minecraft-owned settings/diagnostics/pack capture remains common source-set
code where mappings permit it. Optional shader adapters are isolated and may
mark only the shader section unavailable/partial.

Client-visible data never claims server authority. Query sections that require
a server use the existing authenticated bridge with a new strict payload only
where needed; a pure client stays useful and reports unavailable facts rather
than silently switching topology.

### 5.4 Skill workflow

The bundled `inspect-game-state` Skill explains the three-layer product model,
section discovery, list-to-detail follow-up, authority/completeness, and safe
answer patterns. Example workflows include installed-mod enumeration, exact mod
details, all-option inspection, resource/shader state, F3 diagnosis and
read-only world queries. It explicitly directs Recipes/Guides questions to
their domain Skills and rejects spatial/container inspection as unsupported.

## 6. Verification

Deterministic coverage must prove:

- complete-body timeout, cancellation, late-event fencing and friendly failure
  mapping;
- progress transitions/countdowns without durable clock writes;
- character-by-character streaming layout stability, bottom-follow and manual
  anchor ownership;
- flat/nested/multiline list marker geometry and Enter/Shift+Enter behavior;
- persisted friendly invocation/result card projections;
- natural-language resource-to-exact-recipe workflow and terminating unknown
  or partial searches;
- strict section/query schemas and rejection of commands, reflection, writes,
  secrets, world scans and external-container access;
- installed mod list/details, representative settings from every registered UI
  domain, packs/shader availability, F3/coordinates, player UI state and
  read-only query results;
- client-thread capture detachment, evidence/completeness, missing optional
  integration isolation, Fabric/NeoForge loader parity;
- full common test suite and both loader builds.

Final manual Fabric and NeoForge walkthroughs ask the Agent representative
questions in Chinese and English, induce a slow/failed network request, watch
streaming/list layout, and retain redacted evidence. They do not claim map,
container, spatial-world or write-command support.

The walkthrough is workstation-rendered acceptance, not a compilation claim.
The deterministic model fixture must deliberately emit headings, paragraphs,
ordered and unordered nested lists, emphasis, inline/fenced code, tables,
unsupported links/images/HTML fallbacks, validated Minecraft references,
recipe/item grids, ingredient/craftability/status/source components, an unknown
component fallback, and several streamed block-boundary splits. The controller
captures retained PNGs for wide and narrow layout, an in-progress stream,
terminal content, expanded Tool/source details, controlled dynamic components,
manual-scroll anchor behavior, and normal/debug separation on both loaders.
Each report records scenario, expected visible properties, game/loader/mod
versions, artifact paths and SHA-256 values. Visual review must reject clipping,
marker separation, unstable vertical placement, incorrect scissoring, illegible
fallbacks, or player-facing raw technical state.
