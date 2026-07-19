# Pipeline operations

Stages run in array order and report input/output row counts.

- `SEARCH`: `value` is required; optional JSON Pointer `field` restricts literal substring matching.
- `FILTER`: requires a schema-returned JSON Pointer `field` and `operator`. Operators are `EQ`, `NE`,
  `CONTAINS`, `EXISTS`, `GT`, `GTE`, `LT`, and `LTE`. Ordering operators require numeric values.
- `SORT`: requires `field`; `direction` is `ASC` or `DESC`.
- `SELECT`: requires non-empty `fields` and removes every other column.
- `GROUP`: requires `field` and returns the field plus `count`.
- `AGGREGATE`: uses `COUNT`, `MIN`, `MAX`, `SUM`, or `AVG`; all except `COUNT`
  require `field`. Optional `groupBy` computes one value per group.
- `EXPAND`: requires an array field path and emits one row per array element.
- `TAKE`: requires positive `count` and keeps the first rows.

Use registered field names exactly. An unknown field returns the available
fields; correct it once rather than guessing repeatedly.
