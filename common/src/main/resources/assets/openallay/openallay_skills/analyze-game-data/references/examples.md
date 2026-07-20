# Worked VFS examples

These examples show shapes. Preserve paths, fields, relations, receipts, and
cursors returned by the current request rather than copying placeholder IDs.

## Poison-related resources in one query

After inspecting the relevant schemas, search several roots together instead
of one call per resource kind:

```json
{"plans":[{"roots":["/effect","/potion","/item"],"pipeline":[
  {"operation":"search","query":"poison"},
  {"operation":"select","fields":["/@path","/id","/displayName"]}
]}]}
```

If returned rows expose an acquisition or recipe relation, query the returned
`/result/<id>` with a `follow` stage using that exact relation.

## Highest-saturation Farmer's Delight foods

First read the applicable `/@schema`. Suppose it reports numeric
`/components/minecraft:food/saturation` and `/components/minecraft:food/nutrition`:

```json
{"plans":[{"roots":["/item/farmersdelight"],"pipeline":[
  {"operation":"filter","field":"/components/minecraft:food/saturation","operator":"EXISTS"},
  {"operation":"sort","field":"/components/minecraft:food/saturation","direction":"DESC"},
  {"operation":"select","fields":["/@path","/displayName","/components/minecraft:food/nutrition","/components/minecraft:food/saturation"]},
  {"operation":"take","count":10}
]}]}
```

Do not substitute a remembered `nutrition` or `saturation` location when the
runtime schema uses another mod-defined field.

## Refine a large result

```json
{"searches":[{"roots":["/result/r_candidates"],"pattern":"saturation","mode":"TOKEN","fields":[]}]}
```

Then run `resource_query` on the new `/result` path. If the projection returns
a cursor, use `resource_read` with exactly that cursor only when more rows are
needed.

## Unknown mod raw data

Discover first:

```json
{"patterns":[{"pattern":"/mod/examplemod/raw/data/examplemod/**","kind":null}]}
```

Search likely text resources in one request, then batch-read only exact matches.
Unavailable or binary-only resources are not evidence of content.

## Mixed client/server resources

For a comparison that needs a client-visible option and a server-authoritative
query, batch the exact returned paths in one `resource_read`. Keep each row's
authority and completeness separate; never upgrade client-visible evidence to
server-authoritative because both appeared in one result.
