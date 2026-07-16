---
name: diagnose-missing-recipe
description: Diagnose why an expected crafting or processing recipe is absent in the active pack.
required-mods: []
allowed-tools: [tomewisp:resolve_resource, tomewisp:find_recipes, tomewisp:search_knowledge, tomewisp:get_knowledge_document]
references: []
---
Resolve the expected output ID and query the synchronized recipe snapshot. Search pack documentation for changed progression, disabled recipes, alternate machines, quest gates, or replacement items. Report the observed recipe list exactly. Do not infer that a recipe exists from upstream mod documentation when the active recipe manager does not contain it. If no recipe or explanation is available, label the result unavailable and suggest checking scripts or server-only configuration with an operator.
