---
name: answer-modded-minecraft-question
description: Use for a complex Minecraft request that must coordinate multiple resource domains when no narrower recipe, guide, machine, progression, diagnostic, or analysis Skill matches.
allowed-tools: "openallay:resource_list openallay:resource_read openallay:resource_glob openallay:resource_grep openallay:resource_query"
---
Use this only as the complex multi-domain fallback. A greeting, installed-mod
listing, or exact-resource read does not match. Choose one branch from the
player's intent; do not explore all branches speculatively.

1. Discover unknown identities with one batched `resource_grep` or
   `resource_glob`, then preserve one unambiguous canonical path unchanged.
2. For recipes, search `/recipe` for the exact item path or follow a returned
   recipe relation. Read the exact recipe, source state, and completeness.
3. For mechanics or progression, search `/guide` and `/knowledge`, then read
   only the best matching complete document or section.
4. For settings, packs, diagnostics, installed mods, or visible player state,
   use exact `/game`, `/mod`, or `/player` paths. Do not query recipes or guides
   merely to enumerate installed content.
5. For questions spanning domains, batch independent exact reads. Use
   `resource_query` only when deterministic filtering, aggregation, or link
   traversal is required.
6. Refine a Tool-created `/result` rather than rerunning the source search.
   Continue a receipt cursor only if its omitted units matter to the answer.
7. Preserve any trusted recipe or rich-presentation reference returned by the
   exact resource. Never invent slots, coordinates, textures, UI classes, or
   identifiers; OpenAllay binds the native component from exact truth.
8. If evidence is unavailable, partial, stale, or contradictory after one
   materially corrected request, stop and explain the useful boundary.

Answer in the player's language. Prefer concise ordered steps for procedures
and readable provenance for facts that depend on the current pack.
