# Phase 4 Batch Query, Knowledge, and Live Acceptance Design

## Outcome

OpenAllay must answer broad comparison and multi-step Minecraft questions in a
small number of model turns. It does this through ordered parallel Tool
execution, typed batch/query surfaces, richer captured item data, fixed online
documentation sources, and provider failures that explain the actual class of
failure without exposing secrets.

## Runtime execution

`GameGuideAgent` treats every model turn as a Tool group. It launches distinct
calls together, correlates by invocation ID and input index, and appends results
in the exact ToolUse order required by OpenAI and Anthropic protocols. The
provider never receives a partial group. Duplicate calls within the group share
one execution and the duplicate slots receive a deterministic no-new-information
result.

Transport recovery lives in the model scheduler rather than HTTP codecs. Only
pre-response transport failures are automatically retryable. A progress-aware
attempt wrapper records whether response or stream events were observed, uses
cancellable short backoff, and never retries an HTTP 4xx or a partially visible
response.

## Game content as a virtual dataset

The existing game-content Tool remains the single catalog entry point. Its
simple form searches localized names, IDs, aliases, tags, components, and public
metadata. Its analytical form selects a registered virtual dataset and applies
a closed operation list:

```json
{
  "dataset": "items",
  "namespace": "farmersdelight",
  "pipeline": [
    {"op": "FILTER", "field": "/data/minecraft:food/saturation", "operator": "EXISTS"},
    {"op": "SORT", "field": "/data/minecraft:food/saturation", "direction": "DESC"},
    {"op": "SELECT", "fields": ["/id", "/displayName", "/data/minecraft:food/nutrition", "/data/minecraft:food/saturation"]},
    {"op": "TAKE", "count": 10}
  ]
}
```

Identity fields are stable; domain properties are not. A `describe:true` call
recursively derives RFC 6901 paths, types, row coverage, examples, and supported
operations from the currently captured property trees. Persistent item data
components are encoded generically through their registered codecs, so fields
added by another mod appear automatically. Nested arrays use `EXPAND` before
child-field comparisons. The Agent never invents a path and the query engine
does not contain a food, weapon, potion, or enchantment field whitelist.

Operations use literal comparisons and encoded numbers. There is no free-form
expression, regex engine, join against live objects, or operating-system path
access. Stage reports include input/output row counts. A mod whose facts exist
only behind a bespoke public API can add a trusted OpenAllay Extension
extractor; the core never falls back to private reflection.

Recipe search accepts `queries`, a list of the existing exact criteria record,
and returns a list of correlated result groups. A single `query` remains a
convenience projection. The model is instructed to use one batch request for a
known target set and the virtual dataset for comparison/aggregation instead of
calling the same Tool repeatedly.

## Progressive Skill guidance

Prompt assembly follows the shared pattern visible in the primary OpenClaw and
Hermes Agent implementations rather than embedding every domain procedure in a
monolithic prompt:

- OpenClaw builds ordered prompt sections, places a compact Skill catalog in a
  stable prefix, requires a catalog scan, chooses the most-specific match, and
  limits up-front loading to one Skill
  ([source](https://github.com/openclaw/openclaw/blob/main/src/agents/system-prompt.ts)).
- Hermes builds a capability-filtered metadata index, labels Skill selection as
  mandatory, and loads instructions through `skill_view` only after matching
  name and description
  ([source](https://github.com/NousResearch/hermes-agent/blob/main/agent/prompt_builder.py)).
- The Agent Skills specification defines three disclosure levels: startup
  metadata, activated `SKILL.md` instructions, and references only when needed
  ([specification](https://openagentskills.dev/docs/specification)).

OpenAllay therefore assembles a stable ordered prompt from identity, Tool
contract, mandatory Skill preflight, metadata catalog, execution rules,
authority/response rules, and semantic UI guidance. The catalog contains only
escaped Skill names and descriptions. Before a complex, multi-step, or
unfamiliar workflow, the model must load the single most-specific Skill whose
description clearly matches the needed method. A simple obvious one-Tool lookup
does not load a Skill merely because the Skill allows that Tool; greetings
remain Tool-free. This is a workflow requirement, not an authority grant: code
and Tool schemas still determine what can execute.

`load_skill` without a reference returns the selected `SKILL.md` instructions
and the declared reference names, not their contents. A later exact reference
argument returns one declared reference. This prevents loading every example
and schema into context merely because a Skill matched.

The bundled `analyze-game-data` Skill contains a short decision workflow.
Schema discovery rules, operation syntax, and worked examples live under
`references/`, with explicit conditions saying which one to load. Examples
are illustrative paths returned by `describe`, not a support whitelist, and
include highest-saturation Farmer's Delight food, all poison-related content,
mod namespace counts, and a batch recipe lookup.

## Knowledge sources

`search_knowledge` combines the existing local index with two fixed adapters:

- Minecraft Wiki through its MediaWiki search API;
- MC百科 through its fixed search page parser.

The input may choose `LOCAL`, `ONLINE`, or `ALL`; default is `ALL`. An online
hit contains source ID, title, excerpt, inert canonical reference, and partial
public-documentation evidence. Exact document fetching is deferred unless the
search excerpt is insufficient and a later accepted fixed-source fetch contract
is required. Each adapter has independent timeout/parse diagnostics and a small
in-memory query cache.

## Context and result size

Broad result dumps caused avoidable context growth. Query tools therefore
return structured cardinality and schema information. If a result would exceed
the safe normalized payload threshold, the Tool returns `result_too_large`
instead of a silent truncation. The model then adds projection, filtering,
aggregation, or `TAKE`. This preserves factual correctness and makes result-size
ownership explicit.

## Observable game-state correction

The `DIAGNOSTICS` position category is the canonical source for current biome,
coordinates, dimension, and direction. Tool descriptions and the bundled Skill
state this in the schema-facing text. A mistaken `WORLD_QUERY` location alias
returns a correction that is recoverable in the same Agent request and does not
perform or require a server command.

## Live acceptance

The opt-in live suite uses only environment-provided endpoint/model/key values
and writes a redacted report. It runs at least:

1. greeting without Tools;
2. installed-mod count and exact mod detail;
3. current biome/coordinates from client diagnostics;
4. highest-saturation Farmer's Delight food using one analytical query;
5. poison-related options, acquisition guidance, recipe batch, and inventory
   gap analysis;
6. a multi-turn follow-up that switches topic and then resumes prior context;
7. an online-knowledge fallback question;
8. a bulk target lookup large enough to prove it does not become one model turn
   per item.

Every run records model turns, Tool group sizes, invocations, elapsed time,
terminal code, and answer presence. It records no secret, endpoint, raw request,
raw provider error, or reasoning.
