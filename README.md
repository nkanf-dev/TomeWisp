# TomeWisp

TomeWisp（书灵）is an in-game knowledge companion for modded Minecraft. It is
designed to connect trusted, read-only mod knowledge to a lightweight agent and,
in later phases, turn structures and documentation into guided visual tutorials.

The main development line targets Minecraft 26.2 and Java 25, with Fabric and
NeoForge treated as equal first-class loaders.

## Build

```bash
./gradlew build
```

On an unstable connection, use the curl-backed wrapper, which mirrors failed
Maven downloads locally and retries the Gradle command:

```bash
./gradlew-curl build
```

## Development runs

```bash
./gradlew :fabric:runClient
./gradlew :fabric:runServer
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
```

See [the development guide](docs/development.md) for testing, loader boundaries,
and development commands.

## Status

Phase 2 provides a working client-first guide Agent on Fabric and NeoForge.
Phase 3A provides the grounded tool substrate, while Phase 3B now routes both
loaders through one shared GuideService and an opt-in real-client probe:

- Anthropic Messages and OpenAI-compatible HTTP adapters with streaming and
  tool-result continuation;
- independent multi-session conversations, one active request per session;
- no artificial global concurrency cap, with endpoint-scoped fair retry after
  provider HTTP 429;
- progressive, non-executable Agent Skills;
- deterministic search over Patchouli assets and optional visible FTB Quests;
- optional authenticated server read tools and optional server-hosted models;
- redacted live traces and deterministic Phase 1 trace replay.
- evidence-bearing client/server snapshots with explicit authority,
  completeness, capture time, source, provenance, game version, and loader;
- stable recipe search, exact detail, item usage, and inventory inspection;
- deterministic non-recursive craftability using global alternative allocation;
- fail-closed normalization for factual tools that omit evidence;
- a long recipe-to-inventory craftability replay, while `find_recipes` remains
  only as a deprecated compatibility projection.
- immutable shared command/GUI request state with cancellation, retry,
  multi-session isolation, strict protocol-v4 server context/events, and disconnect
  cleanup;
- a default-off Fabric/NeoForge real-client E2E controller plus deterministic
  loopback model fixture and redacted report contract.

A server mod is not required for the main client model mode. Phase 4 now has a
versioned SQLite history foundation: normal-mode conversation projections are
partitioned by player and world/server, interrupted work restores without an
automatic provider retry, and persistence health is visible in the screen.
All-known recipe capture now merges vanilla, server, JEI, and REI provider
snapshots with generation-bearing references, persisted visibility/source
preferences, source diagnostics, native item cards, and typed viewer actions.
The retained Fabric acceptance profile now proves JEI/REI coexistence,
viewer-only Farmer's Delight discovery, generation-safe exact lookup, and
TomeWisp-to-JEI navigation; EMI and Patchouli still have no verified 26.2
runtime artifact. Phase 4 context management now uses an explicit per-model
window, provider-neutral structural reduction, source-hashed derived summaries,
privacy-safe checkpoint persistence in the single current pre-release schema,
and local/server recovery. A
session is not bound to one model: validated history/checkpoints survive
provider or model changes and every future request is rebuilt against its
selected model budget. Tool details now use scrollable Minecraft-native recipe,
inventory, and material cards; technical identifiers, evidence internals, and
normalized data are absent by default and appear only in the local, default-off
Debug Mode. Multiple named model profiles, per-session runtime switching from
the native screen or commands, OpenRouter capability caching, and the shared
JDK asynchronous HTTP foundation are now implemented; the profile editor and
manual-refresh settings UI are now available on both loaders with atomic
profile CRUD, environment-only credentials, isolated billable-request warning,
redacted connection probes, and generation-safe metadata reconciliation.
Knowledge/Tool/Skill policy, capability-owned recipe settings, history
administration, model-authored rich messages, and the remaining controlled
dynamic components remain Phase 4 work. Dynamic
Ponder generation is deferred beyond Phase 4 because Ponder/Ponderer do not yet
provide a verified 26.2 runtime target. Phase 3C now provides the native player
GUI; graphical E2E remains explicit opt-in and is never inferred from
compilation. The knowledge layer already retains Patchouli multiblock
coordinates and structure references for a later visual-tutorial workflow.

Press the configurable `K` mapping or run bare `/guide` in-world to open the
non-pausing screen. It streams the current GuideService transcript, displays
friendly grounded tool/source cards, and preserves the real Agent chronology: assistant
segments, inline tool calls/results, later continuations, and the final answer.
Running tool cards update in place. The screen also supports cancel, retry,
sessions and explicit client/server model selection. Escape closes only the
screen, not the request. The gear button opens native settings; returning keeps
the same GuideService conversation and active request state.

## License

[MIT](LICENSE)
