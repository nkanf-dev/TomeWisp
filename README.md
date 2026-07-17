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
  multi-session isolation, strict protocol-v2 server events, and disconnect
  cleanup;
- a default-off Fabric/NeoForge real-client E2E controller plus deterministic
  loopback model fixture and redacted report contract.

A server mod is not required for the main client model mode. Phase 4 is planned
to add all-known recipe sources and viewer integration, durable partitioned
history and context compaction, and Minecraft-native rich messages. Dynamic
Ponder generation is deferred beyond Phase 4 because Ponder/Ponderer do not yet
provide a verified 26.2 runtime target. Phase 3C now provides the native player
GUI; graphical E2E remains explicit opt-in and is never inferred from
compilation. The knowledge layer already retains Patchouli multiblock
coordinates and structure references for a later visual-tutorial workflow.

Press the configurable `K` mapping or run bare `/guide` in-world to open the
non-pausing screen. It streams the current GuideService transcript, displays
grounded tool/source details, and preserves the real Agent chronology: assistant
segments, inline tool calls/results, later continuations, and the final answer.
Running tool cards update in place. The screen also supports cancel, retry,
sessions and explicit client/server model selection. Escape closes only the
screen, not the request.

## License

[MIT](LICENSE)
