# SKMB-2026-07-18-013: Shared Outbound HTTP Boundaries

- status: accepted
- decided_by: designer
- approval_source: designer required one shared HTTP adaptation layer while explicitly separating configuration metadata from future model-callable online knowledge tools
- date: 2026-07-18
- commit: 7e2a735
- patterns:
  - D_external_dependency
  - E_security_boundary
  - F_fail_semantics
- scope: common outbound HTTP infrastructure and authority separation

## Decision

TomeWisp has one engine-neutral HTTP contract for requests, response headers,
stream decoding, cancellation, and timeouts. It deliberately does not expose
JDK, OkHttp, Apache, loader, or Minecraft HTTP types to domain adapters. The
current JDK engine owns connection pooling, asynchronous send, redirect refusal,
and response lifetime. Model-provider clients, configuration metadata resolvers, and
future online knowledge tools may reuse that transport but remain separate
domain adapters with separate request construction, response codecs, failure
mapping, credentials, and authority.

OpenRouter model metadata is a TomeWisp configuration-layer request. It is not
an Agent tool, is not included in tool declarations, and cannot be invoked by
model output. Future MC Encyclopedia, Minecraft Wiki, or general Web Fetch
features belong to the knowledge/tool layer and require their own accepted
domain allowlists, evidence semantics, permissions, result normalization, and
tool registration before they can use the shared transport.

The shared transport itself contains no endpoint allowlist, API key, tool
registration, evidence policy, or model/provider fallback. Reusing transport
code never grants one adapter another adapter's authority.

The current engine uses JDK `HttpClient.sendAsync` directly and reuses its client.
It does not add a second connection-retry loop. HTTP 429 scheduling and any
future domain retry remain explicit higher-layer state decisions.

## Invariants

1. Common transport owns mechanics, not product authority.
2. Configuration metadata is unrepresentable as an Agent tool call.
3. Future online tools require explicit tool-layer authorization and evidence;
   transport availability alone cannot expose the network to a model.
4. Redirects remain disabled at the shared transport boundary; an adapter must
   explicitly handle any trusted redirect semantics.
5. Each adapter maps raw failures to redacted domain failures.
6. Replacing the HTTP engine must not require model, metadata, or tool protocol
   adapters to change their request/response semantics.

## Supersedes

Supersedes duplicated JDK connection/cancellation mechanics inside individual
model and metadata adapters. It does not authorize any new online Agent tool.

## Superseded By

None.
