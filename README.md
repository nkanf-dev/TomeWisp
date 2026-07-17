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
Phase 3A now adds the grounded tool substrate used by the upcoming shared
GuideService and GUI:

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

A server mod is not required for the main client model mode. Dynamic Ponder
generation remains Phase 4 because Ponder/Ponderer do not yet provide a 26.2
runtime target. Phase 3B/3C first complete the real-game command E2E, shared
GuideService, and player GUI. The knowledge layer already retains Patchouli
multiblock coordinates and structure references for the later Ponder workflow.

## License

[MIT](LICENSE)
