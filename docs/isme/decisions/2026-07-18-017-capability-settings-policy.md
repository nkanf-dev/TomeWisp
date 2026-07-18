# SKMB-2026-07-18-017: Capability Settings Policy

- status: accepted
- decided_by: designer
- approval_source: designer corrected the top-level “配方” section to a general resource/knowledge layer that manages all tools and Skills, with recipe sources and JEI/REI/EMI-style options owned by the recipe tool's specific settings page; Phase 4 decisions are delegated to best technical judgment
- date: 2026-07-18
- commit: e7acf43, 507d628; implemented through 771cc94
- patterns:
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: knowledge/capability settings catalog, local Tool and Skill policy, per-request capture, and capability-owned child settings

## Context

TomeWisp currently registers local tools and bundled Skills at bootstrap. Model
runtimes capture tool definitions when constructed, while Skill lookup uses a
shared repository. Recipe preferences separately hard-code vanilla, JEI, and
REI booleans. That shape cannot present all sources/tools/Skills coherently,
cannot add EMI or later source adapters without schema fields, and cannot make
capability changes obey the active-request capture invariant.

## Decision

The native top-level section is “知识与能力” (`Knowledge & Capabilities`). A
common, code-owned catalog publishes immutable entries of three kinds:
knowledge source, tool, and Skill. Entries have stable IDs, localized labels,
availability/enablement state, friendly diagnostics, and an optional registered
child-settings route. Catalog data can originate only from trusted TomeWisp or
loader integration registration; neither model output nor arbitrary resource
JSON can register settings actions or capabilities.

Recipes are a tool entry, not a top-level configuration domain. Its child page
owns recipe visibility, source enablement, and viewer preference. Recipe source
configuration uses stable source IDs with a disabled-source set and either
`auto` or one stable preferred-viewer ID. It does not add one schema field or
enum constant per JEI, REI, EMI, or future adapter. Newly registered authorized
sources are enabled by default; an explicitly disabled ID remains disabled
when its integration disappears and later returns. An unavailable explicit
preferred viewer remains selected with an actionable diagnostic and never
silently falls back; `auto` deterministically chooses among enabled available
viewers.

`capabilities.json` stores only strict schema version plus disabled local tool
IDs and disabled Skill names. Unknown disabled identities are retained so an
optional integration cannot be re-enabled merely because it was absent during
one launch. Registered read-only local tools and valid Skills are enabled by
default. This policy can remove capabilities from future client-model requests
but cannot create a tool, enable an unregistered integration, widen a Skill's
declared tool set, expose a server tool, or bypass server authorization.

Saving a capability candidate validates dependency closure. An enabled Skill
whose declared allowed tool is disabled is a conflict; TomeWisp rejects the
candidate and asks the player to keep the tool enabled or also disable the
Skill. It never silently re-enables a tool or disables a Skill. The per-runtime
Skill view exposes only enabled Skills, and its `load_skill` tool resolves only
that immutable view. `load_skill` is derived internal plumbing rather than a
separately persisted player Tool toggle: it is present when the captured view
contains at least one enabled Skill and absent when no Skill can be loaded.

Every client Agent request captures one immutable capability snapshot at
submission: visible tool definitions, executable tool map, enabled Skill
metadata/documents, and required context. A settings change affects future
requests only. An active request keeps its captured definitions and Skill view
through all tool continuations, even if the player changes capability settings.
Rebuilding future client runtimes does not clear provider-neutral sessions or
history. Server-model tools and Skills remain controlled by the server and are
shown as read-only advertised capability state unless a later accepted server
settings design says otherwise.

Knowledge sources and future online tools may expose their own typed child
settings, but catalog registration does not authorize network access or model
tool use. They still require the domain allowlist, evidence, permissions, and
failure decisions required by SKMB-2026-07-18-013 and repository policy.

## States and Transitions

- `capability_policy_current -> capability_policy_saving`: the player confirms
  one validated candidate containing disabled local tool/Skill identities.
- `capability_policy_saving -> capability_policy_current`: atomic persistence
  succeeds; publish a prepared capability snapshot for future client requests.
- `capability_policy_saving -> capability_policy_current`: dependency,
  validation, or persistence fails; retain the prior file and runtime snapshot.
- `any active client request + capability policy change -> unchanged`: the
  request continues with its captured capability snapshot; later requests use
  the replacement.

## Invariants

1. Top-level settings represent all registered knowledge sources, tools, and
   Skills; tool-specific source controls live only in that tool's child page.
2. Capability settings can deny existing local capabilities but cannot grant
   registration, permissions, network authority, evidence authority, or server
   capabilities.
3. One active request's tool definitions, execution map, Skill metadata, Skill
   documents, and required context come from one immutable captured snapshot.
4. Enabled Skill dependencies are validated; no save silently changes another
   toggle to repair a conflict.
5. Stable source IDs, not hard-coded adapter-specific fields, own optional
   JEI/REI/EMI/future recipe source preferences.
6. Missing optional integrations remain visible as unavailable where a retained
   preference exists and never disable unrelated capabilities.

## Failure Semantics

- Capability config is malformed or an enabled Skill depends on a disabled
  tool: `invalid_capability_config` or `capability_dependency_conflict`; do not
  write or replace the current snapshot.
- A disabled or unavailable local tool is requested through stale/malformed
  model output: `tool_unavailable`; do not invoke it.
- A disabled Skill is requested: `skill_not_found`; expose no document body.
- An explicit recipe viewer is unavailable: `viewer_unavailable`; retain the
  preference and do not fall back to another viewer.
- One registered source/adapter fails: keep unrelated catalog entries and
  expose its existing redacted capability diagnostic.

## Applies To

- capability settings catalog/descriptors and native parent/child navigation
- `capabilities.json` strict codec/store and immutable policy snapshots
- `ToolRegistry`, local Agent tool executor, Skill catalog/view, and
  `load_skill` construction
- client runtime replacement and active-request capture
- generic stable-ID recipe source/viewer configuration
- read-only server capability projection
- deterministic dependency, race, unavailable, authority, and loader-parity
  tests

## Supersedes

Supersedes adapter-specific boolean/enum growth in the pre-release recipe
settings schema. It does not supersede recipe authority, visibility, viewer
navigation, or source-generation decisions in SKMB-2026-07-18-005/007.

## Superseded By

None.

## Implementation Status

Implemented through `771cc94`: strict capability and recipe codecs/stores,
immutable Tool/Skill catalogs, per-request client capability capture,
dependency validation, registered settings descriptors, common async settings
actions, friendly native parent/child pages, and shared Fabric/NeoForge recipe
runtime wiring are covered by deterministic tests. Current recipe-viewer
registrations are JEI (`viewer:jei`) and REI (`viewer:rei`); no EMI row or
runtime support is claimed.
