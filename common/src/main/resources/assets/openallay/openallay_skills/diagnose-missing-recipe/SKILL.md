---
name: diagnose-missing-recipe
description: Use when diagnosing why an expected crafting or processing recipe is missing from the active Minecraft pack.
allowed-tools: "openallay:resource_list openallay:resource_read openallay:resource_glob openallay:resource_grep openallay:resource_query"
---
Diagnose a missing recipe from the active ResourceView, not remembered upstream
defaults.

1. Resolve the expected output by searching `/item` or `/block`. If matches are
   ambiguous, show candidates or ask for context; never choose silently.
2. Search `/recipe` once for the exact returned path or ID. Prefer a registered
   recipe link when available, and preserve every canonical path and generation.
3. Batch-read matching recipes plus their `@source`, `@links`, or other returned
   source-state paths. Check provider availability, completeness, conflicts,
   active-pack precedence, and whether the recipe category is supported.
4. When a replacement input/output or alternate machine may explain the
   difference, run one typed `follow` pipeline using an exact returned relation.
5. Search `/guide` and `/knowledge` for pack scripts, changed progression,
   disabled recipes, quest gates, alternate machines, or replacement items;
   read only the most relevant complete section.
6. If the result is large, narrow its `/result` path or continue its cursor.
   Never repeat the same broad search to obtain more model text.
7. Report what the active sources prove. An unavailable or partial recipe mount
   cannot prove global absence, and upstream documentation cannot prove that a
   recipe exists in this pack.

For a successful exact recipe, use only its trusted returned presentation
reference for a rich recipe component. Never author slots or layout data.
