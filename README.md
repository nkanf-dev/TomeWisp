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

Phase 1 provides a deterministic replay of realistic Agent traces. It executes
the real read-only tool registry against detached Minecraft context snapshots,
loads strict versioned traces from server data resources, and emits complete
verification reports on both loaders. The bundled traces cover platform,
recipe, and player-context calls.

No model SDK, endpoint, API key, or network model call is present yet. Guide
integrations, search, and Ponder generation remain later phases.

## License

[MIT](LICENSE)
