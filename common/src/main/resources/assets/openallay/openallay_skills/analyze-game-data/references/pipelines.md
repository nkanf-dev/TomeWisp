# Typed VFS query pipelines

`resource_query` accepts `plans`; each plan has one or more `roots` and an
ordered `pipeline`. Put independent analyses in the same `plans` array.

Stages use these exact operation names:

- `search`: `query`, with optional schema-returned `field`.
- `filter`: `field`, `operator`, and `value` unless the operator is `EXISTS`.
  Operators are `EQ`, `NE`, `CONTAINS`, `EXISTS`, `GT`, `GTE`, `LT`, `LTE`.
- `select`: non-empty `fields`.
- `expand`: a list `field`.
- `sort`: `field` and `direction` (`ASC` or `DESC`).
- `group`: one `field` and its deterministic counts.
- `aggregate`: `function` (`COUNT`, `MIN`, `MAX`, `SUM`, or `AVG`), optional
  `field` for `COUNT`, and optional `groupBy`.
- `take`: non-negative `count`.
- `follow`: exact returned `relation` and optional positive `maxDepth`.

Stages run in order and report input/output cardinality. A typical ranking is
filter `EXISTS`, sort, select, then take. A returned `field_unavailable` or
`operation_unavailable` includes usable vocabulary; inspect `@schema`, correct
once, and do not guess another spelling.

Each query result is also mounted at `/result/<id>`. Use that path as a root for
the next pipeline. This retains exact typed values; do not parse or clean the
model-facing text.
