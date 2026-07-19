# SKMB-2026-07-18-010: Debug UI Projection

- status: accepted
- decided_by: designer
- approval_source: designer explicitly required the player-facing name “调试模式”, friendly visual cards in normal mode, and hiding confidence/authority/completeness-style technical details from ordinary players
- date: 2026-07-18
- commit: ba343f7, 366f2e2, 2f2a8d6, 6da6043
- patterns:
  - B_state_persistence
  - E_security_boundary
  - F_fail_semantics
- scope: normal tool/source detail cards, debug diagnostics, settings, and localization

## Context

The current tool detail panel always renders tool IDs, status enums, evidence
authority/completeness, capture time, provenance, and presentation lines. This
is useful for development but exposes implementation vocabulary instead of the
Minecraft-native, player-friendly information intended by Phase 4.

## Decision

The player-facing feature is named “调试模式” (`Debug Mode`), not “开发模式”.
It is disabled by default and is a local presentation/diagnostic setting.
Changing it rebuilds the current view but does not alter tool execution,
authority, evidence, messages, persistence records, or context sent to models.

Normal mode renders type-specific visual cards. Recipe cards show item icons,
counts, ingredients, outputs, workstation/processing facts, and typed viewer
actions. Inventory and craftability cards show item/count grids, available,
required, allocated, and missing amounts with color plus text/icon status.
Document/source/error/step tools use equally friendly bounded cards and plain
localized descriptions. Unknown tools use a deterministic friendly text
fallback; they never expose raw JSON in normal mode.

Normal mode may show a friendly source name or “来自 <source>” label when it
helps the player understand a card. It does not show raw tool IDs, invocation
or request IDs, confidence, authority/completeness enums, capture timestamps,
provenance strings, stable internal references, normalized JSON, stack traces,
or technical failure codes.

Debug mode appends a clearly separated diagnostic section containing the
technical identifiers, evidence metadata, normalized projection when retained,
checkpoint/request information, and structured failure codes that are already
authorized for local diagnostics. It never makes reasoning, credentials,
authorization data, raw provider bodies, or another player's state
representable.

History/performance diagnostics further narrow this contract: normal mode may
state only that history is loaded on demand and whether the current page is
loading or failed. Debug mode may add loaded/total counts, cursor sequence
counts without cursor UUIDs, semantic cache hit/miss counts, fallback counts,
and context-token estimates. It cannot carry transcript text, component
payloads, paths, actors, raw partition/scope identifiers, or provider bodies.

Every card has a text/narration equivalent. Color is never the only signal.
Malformed or unsupported card data falls back to readable friendly text; debug
mode may additionally show the redacted validation diagnostic.

## Applies To

- local display/settings configuration and both loader wiring paths
- `GuideUiView` normal/debug projections
- tool/source card presenters and the TomeWisp screen detail renderer
- English and Simplified Chinese translations
- deterministic privacy, fallback, accessibility, and rendering tests

## Invariants

1. Debug mode is disabled by default.
2. Normal mode cannot represent technical evidence internals or raw normalized JSON.
3. Debug mode cannot reveal reasoning, credentials, authorization, raw provider bodies, or foreign-player state.
4. Toggling debug mode changes projection only and never rewrites durable history.
5. Every visual card has a friendly text/narration equivalent.
6. Performance diagnostics are count-only and cannot reconstruct conversation content.

## Failure Semantics

- Malformed known-tool data: render a localized friendly fallback; in debug
  mode append a redacted validation reason.
- Unknown tool: render its friendly presentation lines or a generic completion
  card; raw normalized JSON is debug-only.
- Missing Minecraft resource: render the textual identifier/name and an invalid
  visual marker; never fabricate an icon or evidence.

## Supersedes

Supersedes the player-facing “developer mode” label in SKMB-2026-07-18-005.
It does not change the separately retained debug-data privacy contract.

## Superseded By

None.

## Verification

- The default/debug projection, defensive-copy, known-tool, malformed/failure,
  screen source-label, and loader-parity tests pass.
- The 2026-07-18 clean gate passed 229 common tests with zero failures/errors
  and one opt-in skip, followed by both production loader builds.
- Production artifacts passed the credential-pattern scan. The graphical
  client was not launched for this isolated UI work package; final consolidated
  Phase 4 graphical acceptance remains pending.
