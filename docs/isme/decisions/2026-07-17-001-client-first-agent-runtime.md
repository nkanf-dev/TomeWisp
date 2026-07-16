# SKMB-2026-07-17-001: Client-First Agent Runtime

- status: accepted
- decided_by: designer
- approval_source: user explicitly corrected the architecture to “主线还是做客户端，而不是服务端” and delegated subsequent decisions with “接下来不需要跟我对齐了…一直推进这个 goal”
- date: 2026-07-17
- commit: 77b4970
- patterns:
  - A_async_wait
  - B_state_persistence
  - C_concurrent_operations
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
  - G_irreversible_action
- scope: Phase 2 client and optional server Agent runtime

## Context

TomeWisp must provide a useful Agent on an unmodified multiplayer server while
also supporting optional server-owned knowledge and optional server-funded model
inference. Model requests are asynchronous, tools may execute locally or across
an authenticated game connection, and traces can contain player questions and
tool facts.

## Decision

The product is client-first. Client-local mode is the default and requires no
TomeWisp server. Model location is independently selectable: the client can call
its configured model, or an installed TomeWisp server can host the model. A
TomeWisp server may additionally expose only player-scoped, read-only tools to a
client-local Agent.

Each player/client has at most one active request. A second request fails as
busy; only explicit cancel, disconnect, or shutdown cancels work. Sessions and
conversation state remain in memory. Credentials never cross the game network
or enter logs/traces. Unsupported integrations and all failures are explicit and
fail closed. Phase 2 performs no world mutation.

## Applies To

- client and server runtime selection
- model configuration and credential handling
- Agent session state and cancellation
- local and remote tool dispatch
- network payload validation and player scoping
- dynamic trace redaction and persistence
- client and server commands
- unit, protocol, loader, and headless tests

## Rationale

The user wants the mod to remain useful when only the client has TomeWisp. The
client already owns player-visible registries, recipes, inventory, Patchouli
assets, and synchronized quest state. Server installation is therefore an
enhancement rather than a prerequisite. Separating model location from tool
location preserves this property and lets server operators optionally provide
private knowledge or pay for inference.

## Alternatives

- Server-first Agent: rejected because it makes ordinary players depend on
  server installation and administration.
- Client-only with no server protocol: rejected because some authoritative
  quest, permission, and private-guide facts exist only on the server.
- Multiple specialized Agents: rejected in favor of one general loop plus
  deterministic tools and progressively loaded Skills.

## Supersedes

None.

## Superseded By

None.
