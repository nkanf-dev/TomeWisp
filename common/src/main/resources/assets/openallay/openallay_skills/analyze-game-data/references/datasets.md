# JavaScript data graph

`mc` is a lazy, immutable Java-backed view over detached snapshots captured for
the current request. Reading a root or component does not stringify, serialize,
or copy the complete graph. Start with:

- `mc.capabilities`: names of captured data areas.
- `mc.items`, `mc.blocks`, `mc.fluids`, `mc.effects`, `mc.enchantments`,
  `mc.entities`: convenient registry arrays when those kinds exist.
- `mc.registries`: registry evidence and entry-count metadata. Registry rows
  themselves appear once in the kind-specific arrays above.
- `mc.recipes`: recipe rows; `mc.recipeCatalog` also exposes providers, groups,
  diagnostics, and evidence.
- `mc.player`: the caller's detached inventory and visible player state.
- `mc.game`: installed mods, settings, packs, shaders, diagnostics, location,
  and closed world-query results.
- `mc.knowledge`: currently indexed guide and documentation records.
- `mc.extensions`: trusted extension-provided detached data modules.

Registry rows always include `id`, `kind`, `displayName`, `namespace`,
`provenance`, `aliases`, `tags`, `components`, and `properties`. The
`properties` object is intentionally open-ended: vanilla, loader, and mod
adapters may add arbitrary nested keys such as attributes, food components,
potion effects, durability, enchantment levels, or extension-specific values.

Recipe rows always include `id`, `type`, `layout`, `workstation`,
`ingredients`, `catalysts`, `fluids`, `outputs`, `byproducts`, `processing`,
`conditions`, `extensions`, `unlockState`, and `evidence`. Ingredient rows
include `count`, `consumed`, and `alternatives`; output item IDs are at
`output.stack.itemId`. Use `recipe.id`, never an invented `recipeId` field.

Discover shapes from data instead of memorizing a closed schema:

```js
return {
  roots: Object.keys(mc).sort(),
  itemSchema: helpers.schema(mc.items?.slice(0, 8) ?? [], 4),
  recipeSchema: helpers.schema(mc.recipes?.slice(0, 4) ?? [], 4)
};
```

Host arrays support normal non-mutating transforms. `filter`, `map`, `flatMap`,
and `slice` return ordinary JavaScript arrays, which may then be sorted. Direct
mutation of `mc` records or arrays fails by design.

Filter by a stable discriminator (namespace, kind, tag, component, recipe
type) before examining large nested property trees.
