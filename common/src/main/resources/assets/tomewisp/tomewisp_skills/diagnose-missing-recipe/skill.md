---
name: diagnose-missing-recipe
description: Diagnose why an expected crafting or processing recipe is absent in the active pack.
required-mods: []
allowed-tools: [tomewisp:resolve_resource, tomewisp:search_recipes, tomewisp:get_recipe, tomewisp:find_item_usages, tomewisp:search_knowledge, tomewisp:get_knowledge_document]
references: []
---
Resolve the expected output ID, search the captured recipes, and fetch exact details for candidates. Query usages when a replacement input or output may explain the change. Search pack documentation for changed progression, disabled recipes, alternate machines, quest gates, or replacement items. Report the observed recipe list and its completeness exactly. Do not infer that a recipe exists from upstream mod documentation when the active recipe source does not contain it. If evidence is partial or unavailable, label that limitation and suggest checking scripts or server-only configuration with an operator.
