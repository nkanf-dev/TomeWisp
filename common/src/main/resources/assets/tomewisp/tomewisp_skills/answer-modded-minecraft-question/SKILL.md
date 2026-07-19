---
name: answer-modded-minecraft-question
description: Answer a player's modded Minecraft question from live registries, recipes, and indexed documentation.
allowed-tools: "tomewisp:resolve_resource tomewisp:search_recipes tomewisp:get_recipe tomewisp:find_item_usages tomewisp:inspect_inventory tomewisp:calculate_craftability tomewisp:inspect_game_state tomewisp:search_knowledge tomewisp:get_knowledge_document"
---
Use this Skill for general questions about items, blocks, recipes, mechanics, or progression.

For exact recipe details or craftability, pass sourceId, generation, and recipeId unchanged from the current recipe search result. A stale_reference requires a new search; never invent a generation. Recipe tool results include a catalog summary with source state, generation, completeness, counts, diagnostics, and conflicts; use it to state limitations directly instead of inferring source health from an empty result.

1. Resolve every natural or localized item/block name with `resolve_resource` before using exact-ID-only recipe fields. If several exact matches are returned, ask for disambiguation or use new context; never select arbitrarily.
2. Search indexed knowledge and fetch the full relevant documents.
3. For recipes, search first and fetch exact details. Inspect inventory and calculate craftability only when the player asks about sufficiency; do not estimate overlapping alternatives yourself.
4. Use live recipe or `inspect_game_state` player sections for facts that may differ by pack or server.
5. State which source supports each important instruction and respect authority/completeness. Never invent an unavailable recipe or guide entry.
6. If evidence is unavailable, partial, or contradictory, say exactly what could not be verified and suggest the next observable check.
7. After one materially corrected invalid call, or one unchanged empty/partial search, stop. Do not alternate equivalent recipe/guide searches without new evidence.

Answer in the player's language. Prefer concise ordered steps for procedures.
