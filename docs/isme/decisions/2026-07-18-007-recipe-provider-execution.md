# SKMB-2026-07-18-007: Recipe Provider Execution

- status: reviewable_default
- decided_by: statistical_default
- approval_source: designer accepted ALL_KNOWN, optional viewer integration, explicit configuration, and autonomous best-path execution in SKMB-2026-07-18-005
- date: 2026-07-18
- commit: 7e89bed
- patterns:
  - A_async_wait
  - B_state_persistence
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: Phase 4 client recipe provider capture, viewer compatibility, generation identity, visibility, merge, and navigation

## Context

Minecraft 26.2 synchronizes unlocked recipe display entries to the vanilla
client recipe book, not the complete server RecipeManager. The existing client
capture therefore cannot truthfully claim `ALL_KNOWN` or `COMPLETE`. Compatible
JEI and REI releases expose public client APIs that contain additional recipes;
EMI currently has no 26.2 artifact.

The accepted product decision requires source-scoped stable references,
generation-aware stale failure, independent optional integrations, conflict
preservation, and explicit viewer navigation. It does not select the capture
thread, generation representation, or behavior for viewer entries that cannot
be losslessly represented by TomeWisp's canonical recipe record.

## Decision

Every enabled client provider is sampled on the Minecraft client thread at
request-context capture. It must detach all viewer or Minecraft values into
immutable common records before model work starts. Providers never retain a
viewer registry, display, recipe, category, ItemStack, or UI object in the
captured context.

The synchronized vanilla recipe book is always available when connected but is
`PARTIAL` under `ALL_KNOWN`. JEI and REI are compile-only optional integrations
against their verified public APIs; neither is a TomeWisp runtime dependency.
An installed provider publishes `AVAILABLE`, `UNAVAILABLE`, or `FAILED` plus a
stable diagnostic. A failure changes aggregate completeness but does not remove
records from other providers.

Each provider snapshot has a deterministic generation digest over its complete
normalized detached records. `RecipeReference` includes source ID, generation,
and recipe ID. Exact lookup with an absent generation returns
`stale_reference`; it never resolves the same ID against changed contents.

`ALL_KNOWN` is the default persisted preference. `UNLOCKED_ONLY` includes only
records explicitly marked unlocked; viewer records with unknown vanilla unlock
state are excluded in that mode. Preference changes affect future captures
only.

Semantically identical records may be grouped in search results by a canonical
fingerprint over type, layout, workstation, inputs, catalysts, fluids, outputs,
byproducts, processing, conditions, and extensions. Every source reference and
evidence record remains attached. Same IDs with different fingerprints remain
separate variants. Exact lookup always returns one source record.

Unsupported viewer ingredient kinds or malformed displays are omitted with a
provider diagnostic and make that provider `PARTIAL`; they never become an
empty successful recipe. Navigation is a client-thread intent through a narrow
common bridge. JEI and REI support item recipes/usages; exact navigation is
advertised only when the verified API can select the exact record.

## Applies To

- recipe provider snapshots, diagnostics, generations, and canonical merge
- vanilla client recipe-book capture and unlock metadata
- JEI 30.12.0.69 and REI 26.2.820 optional adapters
- recipe visibility/preferred-viewer configuration
- recipe references and exact lookup failure codes
- recipe card navigation in the native screen

## Rationale

Capture-time sampling keeps thread ownership aligned with the existing context
boundary and makes every model/tool invocation operate on one immutable recipe
generation. Generation-bearing references prevent a stale result from silently
binding to changed data. Partial diagnostics are more honest than either
discarding the whole catalog or fabricating unsupported ingredients.

## Alternatives

- Treating the recipe book as complete was rejected by observed client behavior
  and the 26.2 packet/API surface.
- Private reflection into viewer internals was rejected because both compatible
  viewers expose sufficient public APIs.
- A hard JEI/REI runtime dependency was rejected because optional integrations
  must fail independently.
- Parsing every recipe data pack on the client was deferred because it would
  duplicate loader/server condition evaluation and can disagree with the
  authoritative loaded recipe set.

## Review Debt

Review the generation digest cost and unsupported-entry diagnostics using the
retained modded client smoke. Do not add a record/count/time cap without
observed evidence and a new decision.

## Implementation Evidence

Implemented through `5af5b4e` on top of the Phase 4C provider/catalog/action
commits. Deterministic coverage proves current-generation exact lookup,
unavailable/failed provider semantics, viewer metadata filtering, partial
unsupported-record handling, E2E readiness, detail-action routing, and both
loader discovery paths. The final clean gate reported 189 common tests with no
failures or errors and one opt-in skip, followed by successful Fabric and
NeoForge production builds.

The retained Fabric 26.2 client smoke installed JEI, REI, and Farmer's Delight
Refabricated together. It completed the all-known six-tool scenario and opened
the exact JEI apple-cider cooking page from TomeWisp. Artifact provenance,
redacted report/log, screenshots, hashes, the Patchouli resource-fixture
boundary, and the explicit no-EMI/no-Patchouli-runtime claim are recorded under
`docs/verification/phase-4c-all-known-recipes/`.

The smoke observed no need for a recipe-count or time cap. Unsupported viewer
layouts remained provider-local partial diagnostics; in particular, REI could
omit two Farmer's Delight displays while JEI and the rest of the Agent stayed
operational. The review debt therefore remains closed for Phase 4C without
introducing a new cap or changing authority semantics.

The consolidated Phase 4 run additionally launched NeoForge 26.2.0.25-beta
with JEI 30.12.0.72 and Cooking for Blockheads 26.2.0.2. The current NeoForge
REI 26.2.820 artifact displayed an upstream `@OnlyIn` member-stripping warning,
so it was disabled and is not claimed as a passing NeoForge integration. This
is an `integration_degraded` optional-adapter boundary, not a TomeWisp or sample
mod failure. Fabric remains the retained JEI+REI+Farmer's Delight coexistence
proof.

## Supersedes

None.

## Superseded By

None.
