---
name: inspect-game-state
description: Use for multi-section troubleshooting that correlates Minecraft settings, packs, diagnostics, topology, and player-visible state.
allowed-tools: "openallay:resource_list openallay:resource_read openallay:resource_glob openallay:resource_grep openallay:resource_query"
---
Use this Skill only when the question correlates two or more outer client/game
areas. A simple installed-mod listing, exact option, coordinate, biome, or pack
read should use its exact resource directly without loading this Skill.

1. Inspect `/game` and `/player` with direct listing when paths are unknown.
   Typical subtrees cover options, packs, shaders, diagnostics/F3, topology,
   closed query-visible world state, and the player's own visible UI/state.
2. Read the nearest `/@schema` before filtering unfamiliar settings or
   diagnostics. Preserve exact option/category IDs.
3. Batch independent exact paths in one `resource_read`; do not serially fetch
   video, sound, controls, language, packs, and position.
4. For comparisons or summaries, use one `resource_query`, then refine its
   `/result` only if needed.
5. In mixed client/server resources, retain authority and completeness per
   field. A client-visible value does not become server-authoritative because
   it was returned beside one.
6. Player coordinates, biome, dimension, direction, yaw, and pitch are visible
   diagnostics. Do not request command permission for them.
7. If shaders, server state, or another public integration is unavailable,
   report that exact gap and stop rather than inspecting files or guessing.

Nearby blocks/entities, maps, structures, other containers, arbitrary paths,
raw commands, writes, and world interaction remain outside this workflow.
