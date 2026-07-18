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
  multi-session isolation, strict protocol-v5 server context/events, and disconnect
  cleanup;
- a default-off Fabric/NeoForge real-client E2E controller plus deterministic
  loopback model fixture and redacted report contract.

A server mod is not required for the main client model mode. Phase 4 remains
the active pre-release phase. Durable partitioned history, all-known recipe
capture, JEI/REI integration, context compaction, semantic rich messages,
long-history virtualization, model switching, settings, and diagnostics have
deterministic coverage, but a normal full-mod Fabric walkthrough exposed
manual-acceptance corrections that must be completed before Phase 4 can be
called accepted.

The approved correction contract gives the settings screen six sections:
General, Models, Tools, Skills, History, and Diagnostics. Ordinary players enter
an API key in a masked password field; `models.json` schema 2 retains only a
`credentialRef`, while the secret is held in the dedicated local
`credentials.sqlite3` store. Environment references remain available for
externally authored development and headless configuration, not as a field in
the player UI. Sources are not a top-level domain: every recipe, guide, or
future source is a strictly typed child of the logical Tool that consumes it.
Built-in sources can be enabled, disabled, inspected, refreshed where
meaningful, and restored, but not deleted; registered user-source kinds may
support full CRUD.

Tools and Skills are separate master-detail pages. Skills follow TomeWisp's
non-executable Agent Skills subset: packages use uppercase `SKILL.md`, bundled
packages are read-only, and player edits create local filesystem overrides.
Skills cannot grant tools, scripts, arbitrary paths, network access, or Agent
write authority. Recognized pre-release history schemas 1 through 3 are rebuilt
transactionally into the single current schema; future, corrupt, foreign, or
otherwise unrecognized databases still fail closed without mutation.

The accepted Fabric full-mod profile uses Architectury 21.0.4. Architectury
Fabric versions through 21.0.2 are incompatible because their screen-input
delegate breaks TomeWisp text entry; TomeWisp does not add a hard Architectury
dependency. Earlier redacted reports and artifact provenance remain under
`docs/verification/`, but they do not prove the correction set or close final
manual acceptance. Dynamic Ponder generation remains deferred beyond Phase 4.
The knowledge layer already retains Patchouli multiblock coordinates and
structure references for a later visual-tutorial workflow.

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
