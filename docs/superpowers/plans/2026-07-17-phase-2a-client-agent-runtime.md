# Phase 2A Client Agent Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the protocol-neutral live model client, sequential GameGuideAgent, safe Skill runtime, client context capture, and `/guide` client command without requiring TomeWisp on the server.

**Architecture:** Pure Java records define model messages/events and Agent state. JDK `HttpClient` adapters implement Anthropic Messages and OpenAI Chat, while a deterministic mock transport drives tests. Client loader entrypoints capture Minecraft state on the client thread, then execute the Agent asynchronously and publish events back on that thread.

**Tech Stack:** Java 25, Gson 2.14, JDK HttpClient/HttpServer, JUnit 5, Minecraft 26.2, Fabric API client commands/lifecycle, NeoForge client events.

---

### Task 1: Model configuration and credential redaction

**Files:**
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelProtocol.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelConfig.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/ModelConfigLoader.java`
- Create: `common/src/main/java/dev/tomewisp/model/config/SecretValue.java`
- Test: `common/src/test/java/dev/tomewisp/model/config/ModelConfigLoaderTest.java`

- [ ] **Step 1: Write failing config validation and redaction tests**

```java
@Test void environmentOverridesFileAndSecretsNeverRender() {
    ModelConfig config = loader.load(json, Map.of("TOMEWISP_API_KEY", "secret"));
    assertEquals("secret", config.apiKey().reveal());
    assertFalse(config.toString().contains("secret"));
    assertFalse(gson.toJson(config.diagnosticView()).contains("secret"));
}

@Test void rejectsNonHttpsRemoteEndpoint() {
    assertEquals("invalid_model_config", failure("http://example.com/v1").code());
}
```

- [ ] **Step 2: Run the focused test and verify the classes are missing**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.model.config.*' --max-workers=1`

Expected: compilation fails because `ModelConfigLoader` does not exist.

- [ ] **Step 3: Implement immutable configuration**

```java
public record ModelConfig(boolean enabled, ModelProtocol protocol, URI baseUri,
        String model, SecretValue apiKey, int maxOutputTokens,
        Duration connectTimeout, Duration requestTimeout) {
    public ModelConfig {
        if (!baseUri.getScheme().equals("https") && !baseUri.getHost().equals("localhost")
                && !baseUri.getHost().equals("127.0.0.1")) {
            throw new IllegalArgumentException("Remote model endpoints require HTTPS");
        }
        if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens must be positive");
    }
}
```

`ModelConfigLoader` reads `config/tomewisp/model.json`, applies environment
overrides, validates all fields, and returns `ToolResult<ModelConfig>`.

- [ ] **Step 4: Run tests and verify redaction**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.model.config.*' --max-workers=1`

Expected: PASS; test reports and failure messages contain no secret.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/tomewisp/model/config common/src/test/java/dev/tomewisp/model/config
git commit -m "feat: add redacted model configuration"
```

### Task 2: Protocol-neutral model messages and SSE parser

**Files:**
- Create: `common/src/main/java/dev/tomewisp/model/ModelClient.java`
- Create: `common/src/main/java/dev/tomewisp/model/ModelRequest.java`
- Create: `common/src/main/java/dev/tomewisp/model/ModelMessage.java`
- Create: `common/src/main/java/dev/tomewisp/model/ModelContent.java`
- Create: `common/src/main/java/dev/tomewisp/model/ModelToolDefinition.java`
- Create: `common/src/main/java/dev/tomewisp/model/ModelEvent.java`
- Create: `common/src/main/java/dev/tomewisp/model/ModelTurn.java`
- Create: `common/src/main/java/dev/tomewisp/model/ModelFailure.java`
- Create: `common/src/main/java/dev/tomewisp/model/CancellationSignal.java`
- Create: `common/src/main/java/dev/tomewisp/model/http/SseParser.java`
- Test: `common/src/test/java/dev/tomewisp/model/http/SseParserTest.java`

- [ ] **Step 1: Write split-chunk SSE tests**

```java
@Test void handlesUtf8AndEventBoundariesAcrossArbitraryChunks() {
    SseParser parser = new SseParser(events::add);
    bytes("event: content_block_delta\ndata: {\"text\":\"铁").forEach(parser::accept);
    bytes("锭\"}\n\n").forEach(parser::accept);
    parser.finish();
    assertEquals("铁锭", events.getFirst().data().get("text").getAsString());
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew-curl :common:test --tests dev.tomewisp.model.http.SseParserTest --max-workers=1`

Expected: FAIL because `SseParser` is missing.

- [ ] **Step 3: Implement the sealed model API and incremental parser**

```java
public sealed interface ModelEvent permits TextDelta, ReasoningDelta,
        ToolUseComplete, UsageUpdate, MessageComplete, ModelFailure {}

public interface ModelClient {
    CompletableFuture<ModelTurn> complete(ModelRequest request,
            Consumer<ModelEvent> events, CancellationSignal cancellation);
}
```

The parser uses `CharsetDecoder` so split multibyte UTF-8 sequences are retained,
supports CRLF/LF, repeated `data:` lines, comments, and a final unterminated event.

- [ ] **Step 4: Run model primitive tests**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.model.*' --max-workers=1`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/tomewisp/model common/src/test/java/dev/tomewisp/model
git commit -m "feat: define streaming model protocol"
```

### Task 3: Anthropic and OpenAI HTTP adapters

**Files:**
- Create: `common/src/main/java/dev/tomewisp/model/http/HttpModelTransport.java`
- Create: `common/src/main/java/dev/tomewisp/model/anthropic/AnthropicMessagesClient.java`
- Create: `common/src/main/java/dev/tomewisp/model/anthropic/AnthropicJsonCodec.java`
- Create: `common/src/main/java/dev/tomewisp/model/openai/OpenAiChatClient.java`
- Create: `common/src/main/java/dev/tomewisp/model/openai/OpenAiJsonCodec.java`
- Test: `common/src/test/java/dev/tomewisp/model/anthropic/AnthropicMessagesClientTest.java`
- Test: `common/src/test/java/dev/tomewisp/model/openai/OpenAiChatClientTest.java`

- [ ] **Step 1: Write mock HTTP tests for text, tools, continuation, streaming, and errors**

Use `com.sun.net.httpserver.HttpServer` bound to loopback. Assert the Anthropic
request uses `x-api-key`, never serializes it in the body, preserves `tool_use`
history, sends `tool_result`, and parses the final Chinese text. OpenAI fixtures
assert `tool_calls` and tool-role continuation.

- [ ] **Step 2: Run tests and verify missing adapters**

Run: `./gradlew-curl :common:test --tests '*AnthropicMessagesClientTest' --tests '*OpenAiChatClientTest' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement adapters through one transport**

```java
HttpRequest request = HttpRequest.newBuilder(config.baseUri().resolve("messages"))
        .timeout(config.requestTimeout())
        .header("x-api-key", config.apiKey().reveal())
        .header("anthropic-version", "2023-06-01")
        .header("content-type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(codec.requestBody(modelRequest)))
        .build();
```

Retry only `ConnectException`, `SSLHandshakeException`, and
`HttpConnectTimeoutException`; do not retry HTTP responses or interrupted bodies.
Close the response stream when cancellation fires.

- [ ] **Step 4: Run adapter tests**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.model.anthropic.*' --tests 'dev.tomewisp.model.openai.*' --max-workers=1`

Expected: PASS for JSON/SSE, tools, cancellation, and redacted failures.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/tomewisp/model common/src/test/java/dev/tomewisp/model
git commit -m "feat: add live model protocol adapters"
```

### Task 4: Tool JSON schemas and protocol-safe names

**Files:**
- Create: `common/src/main/java/dev/tomewisp/agent/tool/ToolSchemaGenerator.java`
- Create: `common/src/main/java/dev/tomewisp/agent/tool/ToolNameCodec.java`
- Create: `common/src/main/java/dev/tomewisp/agent/tool/AgentToolExecutor.java`
- Create: `common/src/main/java/dev/tomewisp/agent/tool/LocalAgentToolExecutor.java`
- Modify: `common/src/main/java/dev/tomewisp/tool/ToolDescriptor.java`
- Test: `common/src/test/java/dev/tomewisp/agent/tool/ToolSchemaGeneratorTest.java`
- Test: `common/src/test/java/dev/tomewisp/agent/tool/LocalAgentToolExecutorTest.java`

- [ ] **Step 1: Write schema and round-trip tests**

```java
@Test void mapsNamespacedToolsAndNestedRecords() {
    assertEquals("tomewisp__find_recipes", names.encode("tomewisp:find_recipes"));
    assertEquals("tomewisp:find_recipes", names.decode("tomewisp__find_recipes"));
    JsonObject schema = generator.generate(Input.class);
    assertEquals("object", schema.get("type").getAsString());
    assertEquals(List.of("outputItem"), strings(schema.getAsJsonArray("required")));
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.agent.tool.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement schemas and executor**

Extend descriptors with immutable `Set<ContextCapability> requiredContext` and
update built-ins: platform none, registry `REGISTRIES`, recipes `RECIPES`, player
`PLAYER`. Reject unsupported generic/reflection shapes at registration.

- [ ] **Step 4: Run common tests and both loader compilation**

Run: `./gradlew-curl :common:test :fabric:compileJava :neoforge:compileJava --max-workers=1`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/tomewisp/agent/tool common/src/main/java/dev/tomewisp/tool common/src/main/java/dev/tomewisp/tool/builtin common/src/test/java
git commit -m "feat: expose typed tools to models"
```

### Task 5: Agent state machine, sessions, and live traces

**Files:**
- Create: `common/src/main/java/dev/tomewisp/agent/AgentState.java`
- Create: `common/src/main/java/dev/tomewisp/agent/AgentRequest.java`
- Create: `common/src/main/java/dev/tomewisp/agent/AgentResult.java`
- Create: `common/src/main/java/dev/tomewisp/agent/AgentEvent.java`
- Create: `common/src/main/java/dev/tomewisp/agent/GameGuideAgent.java`
- Create: `common/src/main/java/dev/tomewisp/agent/session/AgentSessionStore.java`
- Create: `common/src/main/java/dev/tomewisp/agent/session/AgentSessionKey.java`
- Create: `common/src/main/java/dev/tomewisp/model/scheduling/ModelRequestScheduler.java`
- Create: `common/src/main/java/dev/tomewisp/agent/trace/LiveAgentTrace.java`
- Create: `common/src/main/java/dev/tomewisp/agent/trace/LiveAgentTraceRecorder.java`
- Test: `common/src/test/java/dev/tomewisp/agent/GameGuideAgentTest.java`
- Test: `common/src/test/java/dev/tomewisp/agent/session/AgentSessionStoreTest.java`

- [ ] **Step 1: Write state, busy, cancellation, repeat, and late-event tests**

```java
@Test void executesToolThenReturnsGroundedFinalText() {
    AgentResult result = agent.ask(request("铁锭怎么做？")).join();
    assertEquals(AgentState.COMPLETED, result.state());
    assertEquals(List.of("tomewisp:find_recipes"), trace.toolIds());
    assertTrue(result.text().contains("熔炼"));
}

@Test void secondConcurrentRequestFailsBusy() {
    assertEquals("agent_busy", failure(store.reserve(player, second)).code());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.agent.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement the sequential loop exactly as the SKMB transitions**

Use `(actorId, sessionId)` as the conversation key, one audited tool executor,
canonical tool-call keys, cooperative cancellation, and request identity checks
before every event publication. Different sessions may execute concurrently.
`ModelRequestScheduler` imposes no default concurrency cap; it queues/requeues
HTTP 429 by endpoint using Retry-After or cancellable exponential backoff.
`LiveAgentTrace`
contains no secret-bearing type and is immutable on completion.

- [ ] **Step 4: Run Agent tests**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.agent.*' --max-workers=1`

Expected: PASS, including complete untruncated tool results.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/tomewisp/agent common/src/test/java/dev/tomewisp/agent
git commit -m "feat: add cancellable game guide agent"
```

### Task 6: Skills discovery and progressive loading

**Files:**
- Create: `common/src/main/java/dev/tomewisp/skill/SkillMetadata.java`
- Create: `common/src/main/java/dev/tomewisp/skill/SkillDocument.java`
- Create: `common/src/main/java/dev/tomewisp/skill/SkillParser.java`
- Create: `common/src/main/java/dev/tomewisp/skill/SkillRepository.java`
- Create: `common/src/main/java/dev/tomewisp/skill/LoadSkillTool.java`
- Test: `common/src/test/java/dev/tomewisp/skill/SkillRepositoryTest.java`
- Test: `common/src/test/java/dev/tomewisp/skill/LoadSkillToolTest.java`

- [ ] **Step 1: Write frontmatter, references, reload, and permission tests**

Test standard `SKILL.md`, lowercase pack `skill.md`, duplicate names, missing
references, scripts diagnostics, required-mod filtering, and atomic snapshot
replacement. Assert metadata-only prompts do not contain the Skill body.

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.skill.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement parser, repository, and load tool**

```java
public record SkillMetadata(String name, String description,
        Set<String> requiredMods, Set<String> allowedTools,
        List<String> references, String provenance) {}
```

Do not execute `scripts/`, follow undeclared references, fetch URLs, or alter the
ToolRegistry. Atomically replace immutable maps after full validation.

- [ ] **Step 4: Run Skill tests**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.skill.*' --max-workers=1`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/tomewisp/skill common/src/test/java/dev/tomewisp/skill
git commit -m "feat: load safe agent skills progressively"
```

### Task 7: Client context and client-first runtime

**Files:**
- Create: `common/src/main/java/dev/tomewisp/client/context/ClientContextCapture.java`
- Create: `common/src/main/java/dev/tomewisp/client/ClientGuideRuntime.java`
- Create: `common/src/main/java/dev/tomewisp/client/ClientGuideEvents.java`
- Modify: `common/src/main/java/dev/tomewisp/TomeWispRuntime.java`
- Create: `fabric/src/main/java/dev/tomewisp/fabric/TomeWispFabricClient.java`
- Create: `fabric/src/main/java/dev/tomewisp/fabric/FabricGuideCommands.java`
- Create: `neoforge/src/main/java/dev/tomewisp/neoforge/TomeWispNeoForgeClient.java`
- Create: `neoforge/src/main/java/dev/tomewisp/neoforge/NeoForgeGuideCommands.java`
- Modify: `fabric/src/main/resources/fabric.mod.json`
- Test: `common/src/test/java/dev/tomewisp/client/ClientArchitectureTest.java`

- [ ] **Step 1: Add architecture and pure client-runtime tests**

Assert client runtime depends only on client context interfaces, not loader APIs;
model callbacks cannot directly call Minecraft; and a fake client scheduler
receives all UI events.

- [ ] **Step 2: Run and confirm missing runtime**

Run: `./gradlew-curl :common:test --tests 'dev.tomewisp.client.*' --max-workers=1`

Expected: compilation failure.

- [ ] **Step 3: Implement client snapshots and loader entrypoints**

Fabric adds a `client` entrypoint. NeoForge registers client-only listeners with
`Dist.CLIENT` guarding. `/guide` is a local client command with question,
cancel, clear, status, model, skills, and sources branches. Capture player,
inventory, registries, and synchronized recipes on the client thread before
dispatching a virtual-thread Agent request.

Add session branches for new, switch, list, and close. `/guide <question>` uses
the selected default session; requests in different sessions may overlap.

- [ ] **Step 4: Compile both clients and dedicated servers**

Run: `./gradlew-curl :common:test :fabric:compileJava :neoforge:compileJava --max-workers=1`

Expected: PASS; dedicated server code does not initialize client classes.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/tomewisp/client common/src/main/java/dev/tomewisp/TomeWispRuntime.java fabric neoforge common/src/test/java/dev/tomewisp/client
git commit -m "feat: add client-first guide runtime"
```

### Task 8: Phase 2A verification

**Files:**
- Modify: `docs/development.md`
- Modify: `README.md`

- [ ] **Step 1: Document model configuration and client-only guarantees**

Document both protocol choices, environment-only live testing, local config,
commands, cancellation, and the fact that no server mod is required.

- [ ] **Step 2: Run the full Phase 2A suite**

Run: `./gradlew-curl clean :common:test :fabric:build :neoforge:build --max-workers=1`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Inspect artifacts for client entrypoints and secrets**

Run: `jar tf fabric/build/libs/tomewisp-fabric-*.jar | rg 'TomeWispFabricClient|tomewisp_skills'`

Run: `rg -a 'sk-[A-Za-z0-9]+' fabric/build/libs neoforge/build/libs`

Expected: client entrypoint present; secret scan has no matches.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/development.md
git commit -m "docs: explain client guide agent"
```
