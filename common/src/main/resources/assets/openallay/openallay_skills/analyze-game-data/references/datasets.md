# Virtual datasets

The `resolve_resource` analytical input accepts `all`, `items`, `blocks`,
`effects`, `potions`, `entities`, or `attributes`.

Every row has identity paths such as `/id`, `/kind`, `/displayName`,
`/namespace`, `/provenance`, `/aliases`, `/tags`, and `/components`. Everything
else is runtime data under `/data`. Component IDs and nested field names are
not fixed by OpenAllay: discover them with `describe:true`.

Schema discovery recursively reports JSON Pointer paths, types, row coverage,
a representative encoded value, and allowed operations. Paths containing `/*`
refer to array elements. EXPAND the parent array path before comparing sibling
fields from each element.
