---
name: answer-modded-minecraft-question
description: Fallback workflow for a modded Minecraft question when no narrower recipe, guide, machine, progression, or game-state Skill matches.
allowed-tools: "tomewisp:resolve_resource tomewisp:search_recipes tomewisp:get_recipe tomewisp:find_item_usages tomewisp:inspect_inventory tomewisp:calculate_craftability tomewisp:inspect_game_state tomewisp:search_knowledge tomewisp:get_knowledge_document"
---
Use this Skill only as a fallback when no narrower Skill matches. Never load it
after a more specific Skill. Choose one branch below from the player's intent;
do not run several branches speculatively.

For exact recipe details or craftability, pass sourceId, generation, and recipeId unchanged from the current recipe search result. A stale_reference requires a new search; never invent a generation. Recipe tool results include a catalog summary with source state, generation, completeness, counts, diagnostics, and conflicts; use it to state limitations directly instead of inferring source health from an empty result.

After fetching exact recipe details, prefer one `recipe_grid` component with the complete sourceId/generation/recipeId handle copied unchanged from that Tool result. Do not describe slots, coordinates, textures, GUI classes, or layouts in component properties; TomeWisp resolves and embeds the trusted native or neutral recipe view itself. Keep equivalent prose as the component fallback.

1. For settings, installed mods, packs, F3, or player-visible state, use only the game-state inspection branch. For installed content discovery, use the content catalog lookup and search localized names, IDs, aliases, tags, components, or public metadata; apply its item/block/effect/potion/entity/attribute kind filter only when the question is category-specific. A book may appear here as an item, but its guide text is available only through indexed knowledge. Do not search recipes or guides merely to enumerate installed content.
2. For crafting or processing recipes, resolve each natural/localized item or block name, search recipes, then fetch exact recipe details. Search guides only if the player asks why/how a mechanic works or the recipe evidence explicitly points to documentation.
3. For item usages, resolve the item and query usages. Do not also search crafting recipes unless the question asks for both.
4. For mechanics or progression explanations, search indexed knowledge and fetch only the most relevant complete document. Do not search recipes unless the explanation needs an exact active-pack recipe.
5. Inspect inventory and calculate craftability only when the player asks about sufficiency; do not estimate overlapping alternatives yourself.
6. State which source supports each important instruction and respect authority/completeness. Never invent an unavailable recipe or guide entry.
7. If evidence is unavailable, partial, contradictory, or unchanged after one materially corrected call, stop. Do not alternate equivalent branches without new evidence.

Answer in the player's language. Prefer concise ordered steps for procedures.
