---
name: analyze-game-data
description: Analyze, rank, group, aggregate, join, or batch-query captured Minecraft data with one JavaScript program instead of one Tool call per row.
allowed-tools: "openallay:run_javascript"
---
Use this Skill when the player asks about a set rather than one known object:
highest/lowest values, comparisons, counts, groups, relationships, all matches
under a mod namespace, or recipes for several targets.

1. Use `mc.capabilities` and the root names documented in
   `references/datasets.md` to choose the smallest dataset.
2. `mc.items` and `mc.recipes` are stable arrays. Do not call
   `Object.keys(mc)`, prove that these roots are arrays, enumerate every row's
   keys, or separately fetch a field that a loaded reference already documents.
   They are lazy read-only Java-backed views: `filter`, `map`, `flatMap`,
   `slice`, `reduce`, `some`, and `includes` work normally and produce ordinary
   JavaScript values. Never sort, push, splice, reverse, delete, or assign
   directly on an `mc` host view; filter/map/slice it first, then mutate only the
   derived JavaScript array when needed.
3. For an unfamiliar mod-added shape, run one short discovery program using
   `Object.keys`, `helpers.schema`, or representative samples. Do not guess
   arbitrary property paths.
4. Run one complete JavaScript program. Filter early, then map, join, aggregate,
   and sort inside that program.
5. Pass the smallest required top-level `roots` to `run_javascript`. Ranking
   swords needs `["items"]`; recipe comparison needs `["items", "recipes"]`.
   Omit `roots` only for one schema-discovery call.
6. Return only answer-sized rows and fields. Do not return a registry, recipe
   catalog, guide corpus, or full nested property tree.
7. When the returned model text says rows were omitted, preserve its exact
   handle. Pass that handle in the next `run_javascript` call's `handles`
   argument and use `workspace.open(handle)` to continue filtering.
8. Never call once per candidate. Arrays and maps are the batch interface.

One familiar set-level question should normally be one `run_javascript` call.
One unfamiliar question should normally be one schema/sample call followed by
one analysis call. A third call is justified only to reopen an intentionally
externalized result, not to repeat the same search.

Load only the reference required by the task:

- `references/datasets.md` when choosing a dataset or field name.
- `references/pipelines.md` for join, ranking, grouping, and workspace patterns.
- `references/examples.md` when composing one of the accepted analysis tasks.

For highest-damage sword, strongest poison item and production path, or
least-material container recipe, `references/examples.md` directly matches the
task. Load it, then issue the complete analysis as the first JavaScript call.
Do not perform schema discovery for these three familiar workflows.

The runtime is ordinary modern JavaScript, but it is isolated data analysis:
there is no Java/JVM access, reflection, network, shell, real filesystem, live
game object, or mutation. If required captured data is unavailable, report the
limitation instead of approximating it from names.
