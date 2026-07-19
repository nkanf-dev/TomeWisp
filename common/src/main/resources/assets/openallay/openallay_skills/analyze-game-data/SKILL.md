---
name: analyze-game-data
description: Analyze, rank, group, aggregate, and batch-query captured Minecraft content without one Tool call per row.
allowed-tools: "openallay:resolve_resource openallay:search_recipes"
---
Use this Skill when the player asks about a set rather than one known object:
highest/lowest values, comparisons, counts, groups, all matches under a mod
namespace, or recipes for several already-resolved targets.

1. Choose the smallest virtual dataset that contains the facts.
2. If the exact field paths are not already present in a successful schema
   result retained in context, call `resolve_resource` once with
   `describe:true`, the dataset, and optional namespace. Never guess a path.
3. Use only the returned RFC 6901 JSON Pointers and their declared operations.
4. FILTER early, especially by `/namespace`, `/kind`, component, tag, or a
   discovered `/data/...` path.
5. For a ranking, FILTER the numeric field with `EXISTS`, SORT it, SELECT only
   answer fields, then TAKE the requested rows.
6. For counts or statistics, use GROUP or AGGREGATE instead of returning every
   row for the model to count.
7. Put multiple exact recipe criteria into one `queries` batch. Do not call
   `search_recipes` once per target.
8. If a result says `result_too_large`, add a stronger FILTER, SELECT,
   AGGREGATE, or TAKE stage. Never repeat the unchanged query.

One unfamiliar set-level question means one schema-discovery call followed by
one complete pipeline call. Do not search individual candidates, translate
names one at a time, or call the Tool again to read fields already returned.
When an array contains the values to compare, EXPAND its discovered parent
array path before filtering or sorting its child paths.

Load only the reference required by the task:

- `references/datasets.md` when choosing a dataset or field name.
- `references/pipelines.md` when selecting an operation, operator, sort, group,
  or aggregate contract.
- `references/examples.md` when composing a ranking, namespace summary, or
  multi-recipe batch and the schema alone is insufficient.

The pipeline is JSON data, not shell syntax: never invent pipes, scripts,
regular expressions, paths, or callbacks. If a required dataset or field is
unavailable, report that limitation instead of approximating it from names.
