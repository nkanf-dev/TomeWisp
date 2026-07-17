# Phase 3C Player GUI Design

## 1. Product shape

TomeWisp uses an independent full-screen in-game chat screen. It opens from a
configurable key mapping whose default is `K`, from `/guide` with no arguments,
and from a settings/access button exposed by loader adapters where practical.

The screen is an in-world UI and does not pause single-player. Escape closes the
screen but leaves requests running. Reopening reconstructs the exact view from
GuideService state.

The GUI contains no model protocol, tool execution, network routing, context
capture, or session authority. It is a renderer and intent sender for
GuideService.

## 2. Layout

The normal-width layout has:

- a left session rail with new, select, rename/display, and close actions;
- a top bar with TomeWisp title, current model mode, connection/capability
  status, and settings entry;
- a central virtualized transcript;
- a bottom multiline composer with send/stop and compact status;
- an optional right-side detail drawer opened for tool or source details.

At narrow widths the session rail and detail drawer become modal overlays. Data
is never truncated because of screen size; rendering is virtualized and wrapped.

## 3. Transcript model

The transcript renders immutable message entries:

- user message;
- assistant streaming/final message;
- tool activity group;
- structured error with retry action;
- rate-limit/queue status;
- source chips attached to grounded claims when the service can associate them.

Only `TextDelta` contributes visible assistant prose. Reasoning deltas are not
shown. Final text reconciles the accumulated stream rather than appending a
duplicate answer.

Basic formatting supports paragraphs, lists, emphasis, inline code, and
Minecraft resource IDs without introducing a web renderer. Unsupported markup
is displayed as text. All interactive elements participate in keyboard focus
and narration.

## 4. Tool activity

Tool calls are grouped beneath the assistant turn and localized by stable tool
ID. A collapsed card shows friendly action, state, elapsed time, and evidence
summary. Expanding it shows redacted arguments, success/failure, authority,
completeness, capture time, source, and a concise result view.

Raw reasoning, API headers, credentials, full private traces, and server-only
data outside the player's authorized result are never shown.

Recipe-related cards have first-class views for candidate recipes, ingredient
requirements, outputs, workstation, processing facts, inventory allocation,
and missing materials. Generic tools use a deterministic JSON/component tree
fallback.

## 5. Sources

Source chips use typed `GuideSourceRef` values. Phase 3 supports:

- knowledge document detail (Patchouli/FTB/other indexed document);
- recipe detail from the active source-scoped reference;
- Patchouli multiblock coordinate detail;
- provenance/evidence detail.

Opening a source displays an in-game detail screen/drawer. It does not launch an
external browser or fabricate a link. A stale source explains that the active
connection/resource snapshot changed and offers a new search.

## 6. Sessions and model mode

The selected session is visible. Different sessions can show simultaneous
running indicators. The send action is disabled only for the selected busy
session, not globally.

Changing model mode requires an available target capability and affects new
requests. The GUI labels client-funded versus server-provided inference without
displaying credentials. If the server model disappears, the selector returns to
client mode for future requests and shows an explicit diagnostic; active server
requests fail rather than silently moving.

Session history is memory-only and connection-scoped in Phase 3. Closing a
session cancels its active request before clearing it. Clearing all sessions
requires one in-GUI confirmation because it destroys visible conversation
state; this confirmation is local and not a security approval workflow.

## 7. Configuration and empty states

The screen opens even if the client model is disabled or invalid. Instead of
failing to construct the product runtime, it shows:

- configuration status and redacted diagnostics;
- expected config path;
- required environment variable name, never value;
- server-model availability when connected;
- refresh/reload action.

No-world/title-screen usage may show configuration and previous non-sensitive
UI settings but cannot submit a guide question. In-world submission requires an
active local player.

## 8. Loader boundary

Common client code owns screen, widgets, view models, formatting, and service
binding because Minecraft client classes are shared. Fabric and NeoForge own
only key registration, client tick consumption, lifecycle callbacks, and
command registration. A common test continues to reject loader imports from
common production source.

Fabric adds only the required focused API modules for key binding and client
lifecycle. NeoForge uses `RegisterKeyMappingsEvent`, client tick events, and
client network lifecycle events.

## 9. Testing and acceptance

- Pure Java view-store tests cover every GuideEvent and request/session race.
- Widget/layout tests cover narrow and normal dimensions, scrolling,
  virtualization, focus, narration, and no duplicate streamed final text.
- Loader tests prove key registration and `/guide` opening.
- Real-client E2E proves typing/submission through the service, visible stream,
  tool cards, cancel, retry, session switching, model-mode switching, source
  opening, screen close/reopen continuity, and disconnect cleanup.
- Screenshots are retained for visual QA on both loaders at normal and narrow
  sizes. Pixel-perfect snapshots are diagnostic, not the sole behavioral proof.

