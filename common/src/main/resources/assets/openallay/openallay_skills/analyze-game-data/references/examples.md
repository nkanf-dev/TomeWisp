# Worked examples

## Highest-saturation Farmer's Delight foods

```json
First discover the actual paths; suppose the returned schema contains
`/data/minecraft:food/saturation` and `/data/minecraft:food/nutrition`:

```json
{"dataset":"items","namespace":"farmersdelight","pipeline":[
  {"op":"FILTER","field":"/data/minecraft:food/saturation","operator":"EXISTS"},
  {"op":"SORT","field":"/data/minecraft:food/saturation","direction":"DESC"},
  {"op":"SELECT","fields":["/id","/displayName","/data/minecraft:food/nutrition","/data/minecraft:food/saturation"]},
  {"op":"TAKE","count":10}
]}
```

## Count captured items by mod namespace

```json
{"dataset":"items","pipeline":[
  {"op":"GROUP","field":"/namespace"},
  {"op":"SORT","field":"count","direction":"DESC"}
]}
```

## Find poison-related content without one query per kind

```json
{"dataset":"all","pipeline":[
  {"op":"SEARCH","value":"poison"},
  {"op":"SELECT","fields":["/id","/kind","/displayName","/namespace"]}
]}
```

## Search recipes for several exact outputs in one call

```json
{"queries":[
  {"outputItem":"minecraft:spider_eye"},
  {"outputItem":"minecraft:poisonous_potato"},
  {"outputItem":"minecraft:pufferfish"}
]}
```
