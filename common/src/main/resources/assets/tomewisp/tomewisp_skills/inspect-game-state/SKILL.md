---
name: inspect-game-state
description: Inspect directly player-observable Minecraft client, settings, mod, pack, F3, and read-only query state.
allowed-tools: "tomewisp:inspect_game_state"
---
Use this Skill for the outer game/client layer: information already visible in
Minecraft menus, the player's own UI, HUD/F3, installed-content screens, or a
closed non-mutating query.

Call exactly one `tomewisp:inspect_game_state` section at a time:

- `OVERVIEW` with `summary` for version, loader, topology, and runtime identity.
- `MODS` with `list`, then an exact returned mod ID for complete public metadata.
- `OPTIONS` with `groups`, then `group:<id>` or `key:<exact-id>`. This includes all
  option-report values and key mappings, grouped across video, sound, controls,
  mouse, accessibility, language/chat, online/privacy, packs, and general settings.
- `PACKS` with `summary`, `resource`, or `data` for selected and available packs.
- `SHADERS` with `summary` or `options`. Treat an unavailable public integration
  as unavailable; do not infer shader state from installed files.
- `DIAGNOSTICS` with `categories`, then `category:<id>` for detached F3-style
  position, direction, dimension, biome, renderer, performance, target, and
  network values.
- `PLAYER` with `summary`, `ui`, or `inventory` for only the player's own visible
  state.
- `WORLD_QUERY` with exactly one of `time`, `weather`, `difficulty`,
  `world_border`, or `spawn`. These are closed query equivalents; no command is
  run and client-visible values are not automatically server-authoritative.

Carry exact returned IDs into follow-up queries. State authority, completeness,
capture time, and scoped diagnostics. If a section is partial or unavailable,
say what is unavailable and stop rather than guessing.

Recipes and Guides are separate deep-content domains; load their Skills for
those questions. Nearby blocks/entities, maps, structures, another container's
contents, arbitrary paths/classes, raw command strings, and every write or
world interaction are unavailable in this Skill and Tool.
