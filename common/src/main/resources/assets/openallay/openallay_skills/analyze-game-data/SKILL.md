---
name: analyze-game-data
description: Use when a Minecraft question requires comparing, ranking, grouping, aggregating, or transforming a set of game resources.
allowed-tools: "openallay:resource_list openallay:resource_read openallay:resource_glob openallay:resource_grep openallay:resource_query"
---
Use this Skill for set-level analysis: highest or lowest values, comparisons,
counts, groups, all matches in a namespace, or a relationship traversal across
many resources. A simple exact read does not need this Skill.

1. Identify the narrowest VFS root that can contain the answer. Discover paths
   with `resource_list`, `resource_glob`, or `resource_grep`; do not guess IDs.
2. Before using an unfamiliar field, batch-read the relevant `/@schema` paths.
   Use only returned field pointers, types, coverage, and legal operations.
3. Use one `resource_query` pipeline for filtering, selecting, expanding,
   sorting, grouping, aggregating, taking, or following registered links. Batch
   independent plans in the same call.
4. Filter early and select only answer fields. Let typed stages count, compare,
   and sort; do not repeat their arithmetic in prose.
5. Every operation returns a `/result` path. Run later grep/query/read steps on
   that result when refinement is needed instead of fetching the original set
   again.
6. If a receipt has a cursor, continue with `resource_read` only when omitted
   semantic units could change the answer. Otherwise synthesize immediately.
7. If a field, mount, link, or source is unavailable, report that limitation.
   Never approximate a numeric answer from display names.

Load only the reference needed for the current step:

- `references/datasets.md` to choose VFS roots and discover fields.
- `references/pipelines.md` to compose a typed query stage.
- `references/examples.md` for worked poison, saturation, raw-mod, result
  refinement, or mixed-authority patterns.

The examples are Tool input data, not shell commands. Skills and their
references cannot execute content, register Tools, grant mounts, or authorize
paths beyond the current read-only ResourceView.
