# VFS datasets and schema discovery

Use canonical paths returned by the Tools. Common roots include:

- `/item`, `/block`, `/effect`, `/potion`, `/entity`, `/attribute`
- `/recipe`, `/guide`, `/knowledge`, `/mod`
- `/game` for player-visible settings, packs, diagnostics, and closed queries
- `/player` for actor-scoped visible player state
- `/result` for immutable results produced earlier in the live session

Installed mod metadata is under `/mod/<modid>`. Public logical resources that
do not have a typed mount remain inspectable beneath
`/mod/<modid>/raw/assets`, `/mod/<modid>/raw/data`, and
`/mod/<modid>/raw/metadata`. These are virtual paths, never host file paths.

Read the nearest returned `<path>/@schema` before querying unfamiliar fields.
It reports runtime-discovered RFC 6901-style field pointers, types, row
coverage, and legal operations. This is how a new mod-defined field becomes
queryable without an OpenAllay hard-code. A path containing `/*` describes an
array element; expand the parent list before comparing element fields.

Other reserved children are `@meta`, `@links`, and `@source`. Use `@links` to
discover exact relation names and `@source` only when provenance detail matters.
An unavailable schema means the requested field cannot be safely assumed.
