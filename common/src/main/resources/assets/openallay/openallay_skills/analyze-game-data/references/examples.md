# Worked examples

These paths are examples, not a closed schema. Discover the actual captured
shape when it differs.

For the three workflows below, the documented fixture and normal captured data
use these exact stable roots and fields. Execute the matching program directly.
Do not precede it with root, array, key, sample, or per-row discovery calls.

## Highest-damage sword

Call `run_javascript` with `roots: ["items"]`.

```js
const damage = item =>
  item.properties["minecraft:attack_damage"]
  ?? item.properties.attributes?.attack_damage
  ?? item.properties["minecraft:attribute_modifiers"]?.attack_damage;
return mc.items
  .filter(item => item.tags.includes("minecraft:swords"))
  .map(item => ({id: item.id, name: item.displayName, damage: Number(damage(item))}))
  .filter(item => Number.isFinite(item.damage))
  .sort((a, b) => b.damage - a.damage)
  .slice(0, 5);
```

## Least-material container recipe

Call `run_javascript` with `roots: ["items", "recipes"]`.

```js
const items = new Map(mc.items.map(item => [item.id, item]));
const ingredientUnits = recipe =>
  (recipe.ingredients ?? [])
  .filter(ingredient => ingredient.consumed)
  .reduce((sum, ingredient) =>
    sum + Number(ingredient.count ?? 1), 0);
return mc.recipes
  .filter(recipe => {
    const outputId = recipe.outputs?.[0]?.stack?.itemId;
    const output = items.get(outputId);
    return output?.tags?.some(tag => tag.includes("container"));
  })
  .map(recipe => ({
    recipeId: recipe.id,
    output: recipe.outputs?.[0]?.stack?.itemId,
    materialUnits: ingredientUnits(recipe)
  }))
  .sort((a, b) => a.materialUnits - b.materialUnits)
  .slice(0, 10);
```

The first row is the answer. This program already compares every captured
container recipe and returns all requested fields. Answer immediately from a
`scope: complete` result. Do not call `calculate_craftability`, inspect that
recipe again, or reopen the result: inventory sufficiency is unrelated to
minimum ingredient units.

## Strongest poison effect and its production path

Call `run_javascript` with `roots: ["items", "recipes"]`.

```js
const poison = mc.items.flatMap(item =>
  (item.properties["minecraft:effects"] ?? [])
    .filter(effect => String(effect.id).includes("poison"))
    .map(effect => ({
      itemId: item.id,
      duration: Number(effect.duration ?? 0),
      amplifier: Number(effect.amplifier ?? 0)
    })))
  .sort((a, b) => (b.amplifier - a.amplifier) || (b.duration - a.duration));
const best = poison[0];
return {
  best,
  recipes: mc.recipes.filter(recipe =>
    recipe.outputs?.some(output => output.stack?.itemId === best?.itemId))
};
```

If effects are stored under components or NBT-like nested data instead, inspect
`helpers.schema` once and adapt the property traversal; do not issue one Tool
call per item.
